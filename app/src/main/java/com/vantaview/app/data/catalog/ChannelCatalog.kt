// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.catalog

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import com.vantaview.app.data.ChannelMerge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * What VantaView knows about real channels, for topic groups, "most important first" and putting a
 * channel's regional versions on one line. It comes from the iptv-org channel database (public domain,
 * github.com/iptv-org/database): about 40,000 channels with their country, categories, other spellings
 * and regional feeds. It's downloaded when one of those features is first used, kept on the device in a
 * small file, and refreshed once a week.
 *
 * A playlist channel is matched by its TV guide ID when that's an iptv-org ID ("BBCOne.uk"), otherwise by its
 * name once quality tags and country prefixes are left out (the same tidying as merging channels).
 */
object ChannelCatalog {
    data class Entry(val id: String, val country: String, val topic: Topic, val nsfw: Boolean)

    private const val CHANNELS = "https://iptv-org.github.io/api/channels.json"
    private const val FEEDS = "https://iptv-org.github.io/api/feeds.json"
    private const val WEEK = 7 * 86_400_000L

    private lateinit var appContext: Context
    private lateinit var http: OkHttpClient
    private val lock = Mutex()

    @Volatile private var byKey: Map<String, Entry> = emptyMap()
    @Volatile private var byId: Map<String, Entry> = emptyMap()
    private val matches = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val NONE = Any()

    private val _version = MutableStateFlow(0)
    /** Goes up each time the catalog is (re)loaded, so lists can re-sort. */
    val version: StateFlow<Int> = _version

    private val _state = MutableStateFlow<String?>(null)
    /** "Downloading the channel catalog…" while it loads, or the last problem. */
    val state: StateFlow<String?> = _state

    fun init(context: Context, client: OkHttpClient) { appContext = context.applicationContext; http = client }

    private val file get() = File(appContext.filesDir, "channel-catalog.tsv")
    private val prefs get() = appContext.getSharedPreferences("catalog", Context.MODE_PRIVATE)

    val loaded: Boolean get() = byKey.isNotEmpty()
    val updatedAt: Long get() = if (::appContext.isInitialized) prefs.getLong("at", 0L) else 0L
    val size: Int get() = if (::appContext.isInitialized) prefs.getInt("count", 0) else 0

    /** Loads the catalog (downloading it first when it's missing or a week old, or [force]). Safe to call often. */
    suspend fun ensure(force: Boolean = false) = withContext(com.vantaview.app.data.Background.dispatcher) {
        if (!::appContext.isInitialized) return@withContext
        lock.withLock {
            val stale = !file.exists() || System.currentTimeMillis() - updatedAt > WEEK
            if (force || stale) {
                _state.value = "Downloading the channel catalog…"
                runCatching { download() }
                    .onSuccess { _state.value = null }
                    .onFailure { _state.value = if (file.exists()) null else "The channel catalog couldn't be downloaded (${it.message ?: "network error"})." }
            }
            if (byKey.isEmpty() || force || stale) runCatching { load() }
        }
    }

    /** The catalog's entry for a playlist channel, or null. */
    fun match(name: String, guideId: String?): Entry? {
        if (byKey.isEmpty()) return null
        val cacheKey = "$name\u0001${guideId.orEmpty()}"
        matches[cacheKey]?.let { return it as? Entry }
        val found = guideId?.trim()?.lowercase()?.substringBefore('@')?.takeIf { it.isNotEmpty() }?.let { byId[it] }
            ?: ChannelMerge.nameKey(TimeShift.strip(name)).takeIf { it.length >= 2 }?.let { byKey[it] }
        matches[cacheKey] = found ?: NONE
        return found
    }

    // ---- Building the file: one line per spelling, "key<TAB>id<TAB>country<TAB>topic<TAB>nsfw" ----

    private fun download() {
        val tmp = File(appContext.filesDir, "channel-catalog.part")
        val names = HashMap<String, String>()
        var count = 0
        tmp.bufferedWriter().use { out ->
            fun line(key: String, id: String, country: String, topic: Topic, nsfw: Boolean) {
                if (key.length >= 2) out.append(key).append('\t').append(id).append('\t').append(country).append('\t')
                    .append(topic.name).append('\t').append(if (nsfw) "1" else "0").append('\n')
            }
            val info = HashMap<String, Triple<String, Topic, Boolean>>()
            read(CHANNELS) { o ->
                if (o.closed) return@read
                val id = o.id.ifBlank { return@read }
                val topic = Topic.fromCategories(o.list("categories"))
                val country = o.str("country").uppercase()
                names[id] = o.str("name")
                info[id] = Triple(country, topic, o.nsfw)
                count++
                (listOf(o.str("name")) + o.list("alt_names")).distinct().forEach { line(ChannelMerge.nameKey(it), id, country, topic, o.nsfw) }
            }
            // Regional and other feeds ("BBC One London", "Sky Sports Main Event HD") point at their channel.
            runCatching {
                read(FEEDS) { o ->
                    val ch = o.str("channel")
                    val (country, topic, nsfw) = info[ch] ?: return@read
                    val base = names[ch] ?: return@read
                    (listOf(o.str("name")) + o.list("alt_names")).distinct().forEach { feed ->
                        if (feed.isNotBlank()) line(ChannelMerge.nameKey("$base $feed"), ch, country, topic, nsfw)
                    }
                }
            }
        }
        if (count < 1000) { tmp.delete(); throw IllegalStateException("the catalog came back nearly empty") }
        tmp.renameTo(file)
        prefs.edit().putLong("at", System.currentTimeMillis()).putInt("count", count).apply()
    }

    private fun load() {
        val keys = HashMap<String, Entry>(80_000)
        val ids = HashMap<String, Entry>(50_000)
        file.bufferedReader().useLines { lines ->
            lines.forEach { l ->
                val p = l.split('\t')
                if (p.size < 5) return@forEach
                val id = p[1]
                val e = ids.getOrPut(id.lowercase()) { Entry(id, p[2], runCatching { Topic.valueOf(p[3]) }.getOrDefault(Topic.OTHER), p[4] == "1") }
                // The first channel to claim a spelling keeps it (main names come before feeds).
                keys.putIfAbsent(p[0], e)
            }
        }
        byKey = keys
        byId = ids
        matches.clear()
        _version.value++
    }

    /** One channel or feed object, read as it downloads (only the fields used are kept). */
    private class Obj(val fields: Map<String, Any?>) {
        val id get() = str("id")
        val closed get() = fields["closed"] is String
        val nsfw get() = fields["is_nsfw"] == true
        fun str(k: String) = fields[k] as? String ?: ""
        @Suppress("UNCHECKED_CAST") fun list(k: String) = fields[k] as? List<String> ?: emptyList()
    }

    private fun read(url: String, each: (Obj) -> Unit) {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("error ${r.code}")
            JsonReader(r.body!!.charStream()).use { j ->
                j.beginArray()
                while (j.hasNext()) {
                    val m = HashMap<String, Any?>()
                    j.beginObject()
                    while (j.hasNext()) {
                        val k = j.nextName()
                        when (j.peek()) {
                            JsonToken.STRING -> m[k] = j.nextString()
                            JsonToken.BOOLEAN -> m[k] = j.nextBoolean()
                            JsonToken.BEGIN_ARRAY -> {
                                val l = ArrayList<String>()
                                j.beginArray()
                                while (j.hasNext()) if (j.peek() == JsonToken.STRING) l += j.nextString() else j.skipValue()
                                j.endArray()
                                m[k] = l
                            }
                            else -> j.skipValue()
                        }
                    }
                    j.endObject()
                    each(Obj(m))
                }
                j.endArray()
            }
        }
    }
}
