// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.guide

import android.content.Context
import android.util.Xml
import com.vantaview.app.BuildConfig
import com.vantaview.app.data.country.CountryStore
import com.vantaview.app.data.db.ProgrammeEntity
import com.vantaview.app.data.db.ProviderEntity
import com.vantaview.app.data.db.VantaDb
import com.vantaview.app.data.profile.ProfileSession
import com.vantaview.app.data.security.CredentialCrypto
import com.vantaview.app.di.AppScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

sealed interface GuideState {
    data class Loading(val stage: String) : GuideState
    data class Ready(val programmes: Int) : GuideState
    data class Failed(val message: String) : GuideState
}

/**
 * Downloads a playlist's TV guide (XMLTV, plain or gzipped), keeps yesterday to 7 days ahead for the
 * channels in use, and answers "what's on" questions for the Guide, Live TV and Home pages.
 */
@Singleton
class GuideRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val db: VantaDb,
    http: OkHttpClient,
    private val crypto: CredentialCrypto,
    private val session: ProfileSession,
    private val countries: CountryStore,
    private val schedule: com.vantaview.app.data.SourceSettings,
    private val stalker: com.vantaview.app.data.stalker.StalkerClient,
    private val jellyfin: com.vantaview.app.data.jellyfin.JellyfinClient,
    @AppScope private val scope: CoroutineScope,
) {
    private val sp = context.getSharedPreferences("guide", Context.MODE_PRIVATE)
    // Guides can be large and slow to send.
    private val http = http.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()

    private val _states = MutableStateFlow<Map<Long, GuideState>>(emptyMap())
    val states: StateFlow<Map<Long, GuideState>> = _states

    /** Goes up by one each time a guide finishes loading, so pages can re-read. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private fun setState(id: Long, s: GuideState) = _states.update { it + (id to s) }

    /**
     * Loads the guide if it has never loaded, the playlist was re-synced since, or it's older than
     * Settings › Sources and storage › TV guide allows (Never = only when refreshed by hand).
     */
    fun ensureFresh(p: ProviderEntity) {
        if (_states.value[p.id] is GuideState.Loading) return
        val loadedAt = sp.getLong("at_${p.id}", 0)
        val every = schedule.guide
        val stale = loadedAt == 0L || (every.hours > 0 && (loadedAt < p.lastSyncAt || System.currentTimeMillis() - loadedAt > every.ms))
        if (stale && p.lastSyncAt > 0) refresh(p)
        else if (_states.value[p.id] == null && loadedAt > 0) setState(p.id, GuideState.Ready(sp.getInt("n_${p.id}", 0)))
    }

    fun refresh(p: ProviderEntity) {
        if (_states.value[p.id] is GuideState.Loading) return
        setState(p.id, GuideState.Loading("Loading the TV guide"))
        scope.launch(Dispatchers.IO) {
            try {
                val n = load(p)
                sp.edit().putLong("at_${p.id}", System.currentTimeMillis()).putInt("n_${p.id}", n).apply()
                setState(p.id, GuideState.Ready(n))
                _version.update { it + 1 }
            } catch (e: Exception) {
                setState(p.id, GuideState.Failed(e.message ?: "The TV guide could not be loaded"))
            }
        }
    }

    private fun guideUrls(p: ProviderEntity): List<String> {
        // A guide link the user typed comes first.
        GuideSources.split(p.customEpg).takeIf { it.isNotEmpty() }?.let { return it }
        GuideSources.split(p.epgUrl).takeIf { it.isNotEmpty() }?.let { links ->
            // A playlist's own guide link is saved with its login as placeholders.
            val u = com.vantaview.app.data.repo.USER_TOKEN
            val pw = com.vantaview.app.data.repo.PASS_TOKEN
            return links.map { l ->
                if (u in l || pw in l) l.replace(u, URLEncoder.encode(p.username, "UTF-8")).replace(pw, URLEncoder.encode(crypto.decrypt(p.passwordEnc), "UTF-8")) else l
            }
        }
        if (p.kind == "XTREAM") {
            val u = URLEncoder.encode(p.username, "UTF-8")
            val pw = URLEncoder.encode(crypto.decrypt(p.passwordEnc), "UTF-8")
            return listOf("${p.baseUrl}/xmltv.php?username=$u&password=$pw")
        }
        return emptyList()
    }

    /** Running totals while one or more guide files are read into a playlist's guide. */
    private class Sink(val providerId: Long) {
        var cleared = false
        var total = 0
    }

    /**
     * Free TV playlists added before 0.7.1 saved only the first of its guide links. Read the list's
     * first line again to get them all, and save them, without re-downloading every channel.
     */
    private suspend fun freeTvLinks(p: ProviderEntity): ProviderEntity {
        if (!p.baseUrl.equals(com.vantaview.app.data.repo.FREE_TV_URL, ignoreCase = true) || GuideSources.split(p.epgUrl).size > 1) return p
        val header = runCatching {
            http.newCall(Request.Builder().url(p.baseUrl).build()).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.source()?.readUtf8Line()
            }
        }.getOrNull() ?: return p
        val links = Regex("""(?:url-tvg|x-tvg-url)="([^"]*)"""", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)
        val list = GuideSources.split(links)
        if (list.size <= 1) return p
        val updated = p.copy(epgUrl = list.joinToString(","))
        db.providers().update(updated)
        return updated
    }

    private suspend fun load(provider: ProviderEntity): Int {
        val p = freeTvLinks(provider)
        val all = guideUrls(p)
        val pick = countries.chosen(session.id, p.id)
        val keys = (if (pick == null) db.content().guideKeys(p.id) else db.content().guideKeysIn(p.id, pick.toList())).toHashSet()
        if (all.isEmpty() && p.kind == "STALKER") return loadStalker(p, keys)
        if (all.isEmpty() && p.kind == "JELLYFIN") return loadJellyfin(p, keys)
        if (all.isEmpty()) throw IllegalStateException("This playlist doesn't include a TV guide link")
        if (keys.isEmpty()) throw IllegalStateException("The channels in this playlist don't have guide IDs")

        val urls = GuideSources.pick(all, keys)
        val from = XmltvTime.dayStart(-1)
        val to = XmltvTime.dayStart(8)
        val sink = Sink(p.id)
        var lastError: Exception? = null
        urls.forEachIndexed { i, url ->
            if (urls.size > 1) setState(p.id, GuideState.Loading("Loading the TV guide (${i + 1} of ${urls.size})"))
            try {
                fetch(url) { input -> parseInto(sink, input, keys, from, to) }
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (sink.total == 0) {
            if (sink.cleared || lastError == null) {
                db.guide().deleteFor(p.id)
                throw IllegalStateException("The guide had nothing for your channels in the next week")
            }
            throw lastError!!
        }
        return sink.total
    }

    /** A Jellyfin server's Live TV guide, from yesterday to a week ahead. */
    private suspend fun loadJellyfin(p: ProviderEntity, keys: Set<String>): Int {
        if (keys.isEmpty()) throw IllegalStateException("This Jellyfin server has no Live TV channels")
        val token = crypto.decrypt(p.passwordEnc)
        val user = jellyfin.me(p.baseUrl, token)
        val from = XmltvTime.dayStart(-1)
        val to = XmltvTime.dayStart(8)
        val dao = db.guide()
        var cleared = false
        var total = 0
        jellyfin.programmes(p.baseUrl, token, user, keys.toList(), from, to) { list ->
            val rows = list.mapNotNull { o ->
                val start = com.vantaview.app.data.jellyfin.JellyfinClient.ms(o.optString("StartDate"))
                val stop = com.vantaview.app.data.jellyfin.JellyfinClient.ms(o.optString("EndDate"))
                val ch = o.optString("ChannelId")
                if (start <= 0 || stop <= start || ch !in keys) null
                else ProgrammeEntity(
                    providerId = p.id, channelKey = ch, start = start, stop = stop, title = o.optString("Name").ifBlank { "—" },
                    description = o.optString("Overview").ifBlank { null }, category = o.optJSONArray("Genres")?.optString(0)?.ifBlank { null },
                )
            }
            if (rows.isEmpty()) return@programmes
            if (!cleared) { dao.deleteFor(p.id); cleared = true }
            dao.insert(rows)
            total += rows.size
            setState(p.id, GuideState.Loading("Loading the TV guide: ${"%,d".format(total)} programmes"))
        }
        if (total == 0) throw IllegalStateException("The Jellyfin server's TV guide had nothing for the next week")
        return total
    }

    /** A Stalker portal's own guide (get_epg_info), from yesterday to a week ahead where the portal has it. */
    private suspend fun loadStalker(p: ProviderEntity, keys: Set<String>): Int {
        if (keys.isEmpty()) throw IllegalStateException("This portal sent no channels")
        val from = XmltvTime.dayStart(-1)
        val to = XmltvTime.dayStart(8)
        val dao = db.guide()
        // Portals often only send from now on, so programmes that have finished are kept from earlier
        // loads: they're the ones catch-up can play.
        val cut = System.currentTimeMillis()
        var cleared = false
        var total = 0
        var past: HashSet<String>? = null
        val hours = ((to - cut) / 3_600_000L).toInt().coerceIn(24, 192)
        stalker.guide(p, hours, keys, from, to) { incoming ->
            if (!cleared) { dao.keepPast(p.id, from, cut); past = dao.pastKeys(p.id, cut).toHashSet(); cleared = true }
            val batch = incoming.filter { it.stop > cut || past!!.add("${it.channelId}|${it.start}") }
            dao.insert(batch.map {
                ProgrammeEntity(
                    providerId = p.id, channelKey = it.channelId, start = it.start, stop = it.stop, title = it.title,
                    description = it.description, category = it.category, remoteId = it.id,
                )
            })
            total += batch.size
            setState(p.id, GuideState.Loading("Loading the TV guide: ${"%,d".format(total)} programmes"))
        }
        if (total == 0) throw IllegalStateException("The portal's TV guide had nothing for the next week")
        return total
    }

    /** Downloads one guide file, trying a few app names because some servers only answer known players. */
    private suspend fun fetch(url: String, read: suspend (InputStream) -> Unit) {
        val agents = listOf("VantaView/${BuildConfig.VERSION_NAME}", "IPTVSmartersPro", null)
        var lastError: Exception? = null
        var reading = false
        for (ua in agents) {
            try {
                val b = Request.Builder().url(url)
                if (ua != null) b.header("User-Agent", ua)
                val r = http.newCall(b.build()).execute()
                r.use {
                    if (!r.isSuccessful) throw IllegalStateException("The guide server answered with error ${r.code}")
                    val body = r.body ?: throw IllegalStateException("The guide was empty")
                    reading = true
                    read(unzip(body.byteStream()))
                }
                return
            } catch (e: Exception) {
                // Once programmes are being saved, trying again would save them twice.
                if (reading) throw e
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("The TV guide could not be loaded")
    }

    /** Guides are often gzipped whatever the address says; look at the first two bytes. */
    private fun unzip(raw: InputStream): InputStream {
        val s = BufferedInputStream(raw, 64 * 1024)
        s.mark(2)
        val a = s.read(); val b = s.read()
        s.reset()
        return if (a == 0x1f && b == 0x8b) BufferedInputStream(GZIPInputStream(s, 64 * 1024), 64 * 1024) else s
    }

    private suspend fun parseInto(sink: Sink, input: InputStream, keys: Set<String>, from: Long, to: Long) {
        val providerId = sink.providerId
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val dao = db.guide()
        val batch = ArrayList<ProgrammeEntity>(2000)

        suspend fun flush() {
            if (batch.isEmpty()) return
            if (!sink.cleared) { dao.deleteFor(providerId); sink.cleared = true }
            dao.insert(batch.toList())
            sink.total += batch.size
            batch.clear()
            setState(providerId, GuideState.Loading("Loading the TV guide: ${"%,d".format(sink.total)} programmes"))
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "programme") {
                val channel = parser.getAttributeValue(null, "channel")
                val start = XmltvTime.parse(parser.getAttributeValue(null, "start"))
                val stop = XmltvTime.parse(parser.getAttributeValue(null, "stop"))
                val wanted = channel != null && channel in keys && start != null && stop != null && stop > from && start < to
                var title: String? = null
                var desc: String? = null
                var cat: String? = null
                // Read (or skip) everything inside <programme>.
                var depth = 1
                while (depth > 0) {
                    event = parser.next()
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            depth++
                            if (wanted && depth == 2) {
                                when (parser.name) {
                                    "title" -> if (title == null) { title = parser.nextText().trim(); depth-- }
                                    "desc" -> if (desc == null) { desc = parser.nextText().trim().take(400); depth-- }
                                    "category" -> if (cat == null) { cat = parser.nextText().trim(); depth-- }
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> depth--
                        XmlPullParser.END_DOCUMENT -> depth = 0
                    }
                }
                if (wanted && !title.isNullOrBlank()) {
                    batch += ProgrammeEntity(
                        providerId = providerId, channelKey = channel!!, start = start!!, stop = stop!!,
                        title = title, description = desc?.ifBlank { null }, category = cat?.ifBlank { null },
                    )
                    if (batch.size >= 2000) flush()
                }
            }
            if (event != XmlPullParser.END_DOCUMENT) event = parser.next()
        }
        flush()
    }

    /** Settings › Sources and storage › Clear TV guide: everything goes, and loads again when next needed. */
    suspend fun clearAll() {
        db.guide().clearAll()
        sp.edit().clear().apply()
        _states.value = emptyMap()
        _version.update { it + 1 }
    }

    // ---- Questions the pages ask ----

    // ---- Guide time offset per source (for guides whose times are an hour or so out) ----

    /** Minutes added to every programme time of a source's guide (negative = earlier). */
    fun offsetMinutes(providerId: Long): Int = sp.getInt("offset_$providerId", 0)
    fun setOffsetMinutes(providerId: Long, minutes: Int) { sp.edit().putInt("offset_$providerId", minutes).apply(); _version.update { it + 1 } }
    private fun off(providerId: Long) = offsetMinutes(providerId) * 60_000L

    /** A programme with its source's offset applied. */
    fun shifted(p: ProgrammeEntity): ProgrammeEntity = off(p.providerId).let { o -> if (o == 0L) p else p.copy(start = p.start + o, stop = p.stop + o) }

    suspend fun range(providerId: Long, key: String, from: Long, to: Long): List<ProgrammeEntity> {
        val o = off(providerId)
        return db.guide().range(providerId, key, from - o, to - o).map(::shifted)
    }

    suspend fun nowNext(providerId: Long, key: String, now: Long = System.currentTimeMillis()): List<ProgrammeEntity> =
        db.guide().nowNext(providerId, key, now - off(providerId)).map(::shifted)

    suspend fun onNow(providerId: Long, keys: List<String>, now: Long = System.currentTimeMillis()): List<ProgrammeEntity> =
        if (keys.isEmpty()) emptyList() else keys.chunked(500).flatMap { db.guide().onNow(providerId, it, now - off(providerId)) }.map(::shifted)
}
