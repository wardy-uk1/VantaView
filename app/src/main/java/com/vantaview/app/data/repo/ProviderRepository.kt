// SPDX-License-Identifier: GPL-3.0-or-later
// Xtream login check adapted from Stream Goat (c) Syed Ali Hamza, GPL-3.0
// https://github.com/sherazi5110/stream-goat  (LiveTv.kt: xtream)
package com.vantaview.app.data.repo

import com.vantaview.app.BuildConfig
import com.vantaview.app.data.db.CategoryEntity
import com.vantaview.app.data.db.ChannelEntity
import com.vantaview.app.data.db.FavouriteEntity
import com.vantaview.app.data.db.ProviderEntity
import com.vantaview.app.data.db.VantaDb
import com.vantaview.app.data.db.VodEntity
import com.vantaview.app.data.remote.M3uParser
import com.vantaview.app.data.remote.RadioDetect
import com.vantaview.app.data.remote.SmartAdd
import com.vantaview.app.data.security.CredentialCrypto
import com.vantaview.app.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SyncState {
    data class Running(val stage: String) : SyncState
    data class Done(val channels: Int, val skippedVod: Int) : SyncState
    data class Failed(val message: String) : SyncState
}

/** What the player needs to open a channel. */
data class PlayTarget(
    val url: String, val altUrl: String?, val headers: Map<String, String>,
    /** A ClearKey or Widevine protected channel (M3U #KODIPROP lines). */
    val drm: com.vantaview.app.data.player.Drm.Info? = null,
    /** A DASH (.mpd) stream. */
    val dash: Boolean = false,
)

object GuideLinks {
    fun valid(link: String) = link.split(',').map { it.trim() }.filter { it.isNotEmpty() }.all { it.startsWith("http://", true) || it.startsWith("https://", true) }
}

/** Free TV (github.com/Free-TV/IPTV): an M3U playlist of free TV channels around the world. */
const val FREE_TV_URL = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"

internal const val USER_TOKEN = "{{vv_user}}"
internal const val PASS_TOKEN = "{{vv_pass}}"

@Singleton
class ProviderRepository @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val db: VantaDb,
    private val http: OkHttpClient,
    private val crypto: CredentialCrypto,
    private val session: com.vantaview.app.data.profile.ProfileSession,
    private val stalker: com.vantaview.app.data.stalker.StalkerClient,
    @AppScope private val scope: CoroutineScope,
) {
    private val userAgent = "VantaView/${BuildConfig.VERSION_NAME}"

    private val _syncStates = MutableStateFlow<Map<Long, SyncState>>(emptyMap())
    val syncStates: StateFlow<Map<Long, SyncState>> = _syncStates

    /** Playlists of the profile in use. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(): Flow<List<ProviderEntity>> =
        session.current.flatMapLatest { db.providers().observeFor(it) }

    /** Every playlist, switched off ones too, for the Playlists page. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeEvery(): Flow<List<ProviderEntity>> =
        session.current.flatMapLatest { db.providers().observeEveryFor(it) }

    /** Switches a playlist on or off. Switched on again, it's refreshed if it's out of date. */
    suspend fun setEnabled(id: Long, on: Boolean) {
        db.providers().setEnabled(id, on)
        if (on) db.providers().get(id)?.takeIf { System.currentTimeMillis() - it.lastSyncAt > 6 * 3_600_000L }?.let { sync(it.id) }
    }

    /** Where a playlist comes from, for the Playlists page: the server's address, never the password. */
    fun address(p: ProviderEntity): String = runCatching {
        when {
            p.kind == "M3U" && p.baseUrl.startsWith("file:") -> com.vantaview.app.ui.tr("A file on this device")
            p.kind == "M3U" -> hostOf(m3uLink(p))
            else -> hostOf(p.baseUrl)
        }
    }.getOrDefault("")

    private fun hostOf(url: String): String {
        val u = java.net.URI(url.trim())
        val scheme = u.scheme?.lowercase() ?: "http"
        val port = if (u.port > 0) ":${u.port}" else ""
        return "$scheme://${u.host ?: url}$port"
    }

    /** Renames a playlist; a blank name goes back to the automatic one (its server's name). */
    suspend fun rename(id: Long, name: String) {
        val p = db.providers().get(id) ?: return
        val auto = when {
            p.kind == "M3U" && p.baseUrl.startsWith("file:") -> "My playlist"
            p.kind == "M3U" -> defaultName(m3uLink(p), "My playlist")
            p.kind == "STALKER" -> defaultName(p.baseUrl, "My portal")
            else -> defaultName(p.baseUrl, "My playlist")
        }
        db.providers().update(p.copy(name = name.trim().ifBlank { auto }))
    }

    /** A name for a playlist added without one: its server's name, so two playlists can be told apart. */
    private fun defaultName(url: String, fallback: String): String =
        runCatching { java.net.URI(url.trim().let { if ("://" in it) it else "http://$it" }).host }.getOrNull()
            ?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: fallback

    /** Copies every playlist of one profile to another and loads the copies. */
    suspend fun copyAll(from: Long, to: Long) {
        db.providers().everyFor(from).forEach { p ->
            val id = db.providers().insert(
                p.copy(id = 0, profileId = to, createdAt = System.currentTimeMillis(), lastSyncAt = 0, channelCount = 0, lastError = null)
            )
            sync(id)
        }
    }

    suspend fun deleteAllFor(profileId: Long) {
        db.providers().everyFor(profileId).forEach { delete(it.id) }
    }

    /** Sources: a guide link typed for a playlist (blank clears it and goes back to the playlist's own). */
    suspend fun setGuideLink(id: Long, link: String) {
        val p = db.providers().get(id) ?: return
        val clean = link.trim().ifBlank { null }
        require(clean == null || GuideLinks.valid(clean)) { "The guide link must start with http:// or https://" }
        db.providers().update(p.copy(customEpg = clean))
    }

    /**
     * An M3U playlist from a file on this device. A copy is kept inside the app (the original may
     * move or be deleted); pick the file again to update it.
     */
    suspend fun addM3uFile(name: String, uri: android.net.Uri, guide: String = ""): Long = withContext(Dispatchers.IO) {
        val dir = java.io.File(context.filesDir, "playlists").apply { mkdirs() }
        val file = java.io.File(dir, "playlist-${System.currentTimeMillis()}.m3u")
        val input = context.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("Couldn't open that file")
        input.use { i -> file.outputStream().use { o -> i.copyTo(o) } }
        val shown = name.ifBlank {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null
            } ?: "My playlist"
        }
        val id = db.providers().insert(
            ProviderEntity(profileId = session.id, name = shown, kind = "M3U", baseUrl = file.toURI().toString(), customEpg = guide.trim().ifBlank { null })
        )
        sync(id)
        id
    }

    suspend fun addM3u(name: String, url: String, guide: String = ""): Long {
        val clean = M3uParser.rawGithub(url)
        require(clean.startsWith("http://", true) || clean.startsWith("https://", true)) {
            "The playlist link must start with http:// or https://"
        }
        val (user, pass) = linkLogin(clean)
        val id = db.providers().insert(
            ProviderEntity(
                profileId = session.id, name = name.ifBlank { defaultName(clean, "My playlist") }, kind = "M3U", baseUrl = storedLink(clean),
                username = user.orEmpty(), passwordEnc = pass?.let(crypto::encrypt).orEmpty(), customEpg = guide.trim().ifBlank { null },
            )
        )
        sync(id)
        return id
    }

    /** True when this playlist is the optional Free TV list. */
    /** The username and password inside an M3U link (…get.php?username=…&password=…), when it has them. */
    private fun linkLogin(link: String): Pair<String?, String?> {
        fun param(n: String) = Regex("""[?&]$n=([^&#]+)""", RegexOption.IGNORE_CASE).find(link)?.groupValues?.get(1)
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }?.ifBlank { null }
        val u = param("username")
        val p = param("password")
        return if (u != null && p != null) u to p else null to null
    }

    /** How an M3U link is kept: encrypted, except the public Free TV list and files on this device. */
    private fun storedLink(link: String): String =
        if (link.equals(FREE_TV_URL, ignoreCase = true) || link.startsWith("file:")) link else crypto.protect(link)

    /** The real address of an M3U playlist. */
    fun m3uLink(p: ProviderEntity): String = crypto.reveal(p.baseUrl)

    init {
        // Playlists added before links were encrypted: encrypt them now, and load them again so their channel and
        // film addresses are saved with the login hidden too.
        scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(3_000) // after start-up, and after this class has finished setting up
            runCatching {
                db.backup().providers().filter { it.kind == "M3U" && !it.baseUrl.startsWith(CredentialCrypto.ENC) }.forEach { p ->
                    val stored = storedLink(p.baseUrl)
                    if (stored == p.baseUrl) return@forEach
                    val (user, pass) = linkLogin(p.baseUrl)
                    db.providers().update(
                        p.copy(baseUrl = stored, username = user ?: p.username, passwordEnc = pass?.let(crypto::encrypt) ?: p.passwordEnc)
                    )
                    sync(p.id)
                }
            }
        }
    }

    fun isFreeTv(p: ProviderEntity): Boolean = p.kind == "M3U" && p.baseUrl.equals(FREE_TV_URL, ignoreCase = true)

    /** Adds the optional Free TV list (officially free channels), fetched now from its GitHub page, never bundled. */
    suspend fun addFreeTv(): Long {
        db.providers().everyFor(session.id).firstOrNull { isFreeTv(it) }?.let { if (!it.enabled) db.providers().setEnabled(it.id, true); return it.id }
        return addM3u("Free TV", FREE_TV_URL)
    }

    suspend fun addXtream(name: String, server: String, username: String, password: String, guide: String = ""): Long {
        require(server.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
            "Server, username and password are all needed"
        }
        val id = db.providers().insert(
            ProviderEntity(
                profileId = session.id,
                name = name.ifBlank { defaultName(server, "My playlist") },
                kind = "XTREAM",
                baseUrl = SmartAdd.normalizeServer(server),
                username = username.trim(),
                passwordEnc = crypto.encrypt(password.trim()),
                customEpg = guide.trim().ifBlank { null },
            )
        )
        sync(id)
        return id
    }

    /** Live TV › Favourites › Edit: saves the new order. */
    suspend fun reorderFavourites(rows: List<ChannelEntity>) {
        rows.forEachIndexed { i, c -> db.content().setFavouritePosition(c.providerId, c.remoteId, i + 1) }
    }

    /** A Stalker (MAG) portal: the portal address and the MAC address your provider registered. */
    suspend fun addStalker(
        name: String, portal: String, mac: String,
        model: String = "MAG250", serial: String = "", deviceId: String = "", deviceId2: String = "", signature: String = "",
    ): Long {
        require(portal.isNotBlank()) { "Enter the portal address" }
        val clean = com.vantaview.app.data.stalker.StalkerClient.normalizeMac(mac)
            ?: throw IllegalArgumentException("That MAC address doesn't look right. It's 12 letters and numbers, like 00:1A:79:12:34:56")
        var url = portal.trim()
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) url = "http://$url"
        val id = db.providers().insert(
            ProviderEntity(
                profileId = session.id,
                name = name.ifBlank { defaultName(portal, "My portal") },
                kind = "STALKER",
                baseUrl = url.substringBefore('#').trimEnd('/'),
                passwordEnc = crypto.encrypt(
                    com.vantaview.app.data.stalker.StalkerClient.Box(
                        clean, model, serial.trim(), deviceId.trim(), deviceId2.trim(), signature.trim(),
                    ).encode()
                ),
            )
        )
        sync(id)
        return id
    }

    suspend fun delete(id: Long) {
        stalker.forget(id)
        db.content().deleteChannels(id)
        db.content().deleteCategories(id)
        db.content().deleteFavourites(id)
        db.content().deleteHidden(id)
        db.vod().deleteFor(id)
        db.vod().deleteEpisodesFor(id)
        db.providers().delete(id)
        _syncStates.update { it - id }
    }

    suspend fun setFavourite(channel: ChannelEntity, on: Boolean) {
        if (on) db.content().addFavourite(
            FavouriteEntity(channel.providerId, channel.remoteId, position = db.content().maxFavouritePosition(channel.providerId) + 1)
        )
        else db.content().removeFavourite(channel.providerId, channel.remoteId)
    }

    /** Settings › Sources and storage: refresh this profile's playlists that are older than allowed. */
    suspend fun autoRefresh(every: com.vantaview.app.data.RefreshEvery) {
        if (every.hours == 0) return
        val now = System.currentTimeMillis()
        db.providers().allFor(session.id).filter { now - it.lastSyncAt > every.ms }.forEach { sync(it.id) }
    }

    fun sync(id: Long) {
        if (_syncStates.value[id] is SyncState.Running) return
        setState(id, SyncState.Running("Connecting"))
        scope.launch(Dispatchers.IO) {
            val provider = db.providers().get(id) ?: return@launch
            try {
                val done = when (provider.kind) {
                    "XTREAM" -> syncXtream(provider)
                    "STALKER" -> syncStalker(provider)
                    else -> syncM3u(provider)
                }
                db.providers().get(id)?.let {
                    db.providers().update(
                        it.copy(lastSyncAt = System.currentTimeMillis(), channelCount = done.channels, lastError = null)
                    )
                }
                setState(id, done)
            } catch (e: Exception) {
                val message = friendly(e)
                db.providers().get(id)?.let { db.providers().update(it.copy(lastError = message)) }
                setState(id, SyncState.Failed(message))
            }
        }
    }

    /** Xtream servers' time zones (from sign-in), for catch-up. */
    private val zones = java.util.concurrent.ConcurrentHashMap<Long, String>()
    private fun xtreamZone(id: Long): String = zones[id] ?: "UTC"

    private fun setState(id: Long, state: SyncState) = _syncStates.update { it + (id to state) }

    private fun friendly(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "Could not find that server. Check the address."
        is java.net.SocketTimeoutException -> "The server took too long to answer."
        is java.net.ConnectException -> "Could not connect to the server."
        is javax.net.ssl.SSLException -> "Secure connection failed. Try http:// instead of https://."
        is org.json.JSONException -> "The server sent an answer the app could not read."
        else -> e.message ?: "Something went wrong"
    }

    private suspend fun syncM3u(p: ProviderEntity): SyncState.Done {
        val result = if (p.baseUrl.startsWith("file:")) {
            setState(p.id, SyncState.Running("Reading playlist file"))
            val f = java.io.File(java.net.URI(p.baseUrl))
            if (!f.exists()) throw IllegalStateException("The playlist file is gone. Remove this playlist and add the file again.")
            f.bufferedReader().useLines { M3uParser.parse(it) }
        } else {
            setState(p.id, SyncState.Running("Downloading playlist"))
            val request = Request.Builder().url(m3uLink(p)).header("User-Agent", userAgent).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("The server answered with error ${response.code}")
                val body = response.body ?: throw IllegalStateException("The playlist is empty")
                body.charStream().buffered().useLines { M3uParser.parse(it) }
            }
        }
        if (result.entries.isEmpty() && result.vod.isEmpty()) throw IllegalStateException("No channels were found in this playlist")
        // With the login known (from the link), it's hidden in every saved channel and film address.
        val login = if (p.username.isNotEmpty()) crypto.decrypt(p.passwordEnc).takeIf { it.isNotEmpty() }?.let { p.username to it } else null
        return saveM3uResult(p, result, hideCredentials = login)
    }

    /**
     * Saves parsed playlist entries. When hideCredentials is set, the login inside each stream
     * address is swapped for placeholders, so it is not stored in plain text in channel rows.
     */
    private suspend fun saveM3uResult(
        p: ProviderEntity,
        result: com.vantaview.app.data.remote.M3uResult,
        hideCredentials: Pair<String, String>?,
    ): SyncState.Done {
        setState(p.id, SyncState.Running("Saving ${result.entries.size} channels"))
        val groups = LinkedHashMap<String, Int>()
        result.entries.forEach { e -> e.group?.let { g -> groups.getOrPut(g) { groups.size } } }
        val categories = groups.map { (name, pos) -> CategoryEntity(providerId = p.id, remoteId = name, name = name, position = pos) }
        // Some lists give every channel the same number (often 1); then their order is used instead.
        val given = result.entries.mapNotNull { it.number }
        val useGiven = given.size > result.entries.size / 2 && given.toSet().size > given.size * 0.9
        val channels = result.entries.mapIndexed { i, e ->
            val stored = hideCredentials?.let { (u, pw) ->
                "xtreamurl:" + e.url.replace("/$u/", "/$USER_TOKEN/").replace("/$pw/", "/$PASS_TOKEN/")
            } ?: e.url
            ChannelEntity(
                providerId = p.id,
                remoteId = sha1(stored),
                name = e.name,
                sortKey = e.name.lowercase(),
                number = if (useGiven) e.number ?: (i + 1) else i + 1,
                logo = e.logo,
                categoryRemoteId = e.group,
                streamUrl = stored,
                epgChannelId = e.tvgId,
                headersJson = if (e.headers.isEmpty()) null else JSONObject(e.headers as Map<*, *>).toString(),
                archiveDays = if (e.catchup != null) e.catchupDays else 0,
                isRadio = e.radio,
                // An Xtream playlist download with catchup="xc" needs the server's time zone.
                catchupSource = e.catchup?.let { if (it == "xc|") "xc|" + xtreamZone(p.id) else it },
            )
        }
        db.content().replaceLive(p.id, categories, channels)
        if (result.epgUrl != null) db.providers().get(p.id)?.let {
            // The playlist's own guide link often carries the login too; it's kept with placeholders.
            val epg = hideCredentials?.let { (u, pw) -> hideLogin(result.epgUrl, u, pw) } ?: result.epgUrl
            db.providers().update(it.copy(epgUrl = epg))
        }
        // Films and series go to the Movies and TV Series pages.
        if (result.vod.isNotEmpty()) setState(p.id, SyncState.Running("Saving films and series"))
        val (items, episodes) = com.vantaview.app.data.vod.VodImport.fromM3u(p.id, result.vod) { url ->
            hideCredentials?.let { (u, pw) -> "xtreamurl:" + url.replace("/$u/", "/$USER_TOKEN/").replace("/$pw/", "/$PASS_TOKEN/") } ?: url
        }
        db.vod().replace(p.id, items, episodes)
        return SyncState.Done(channels.size, items.size)
    }

    /**
     * An Xtream account's films and series (the whole list, read as it downloads so big lists fit in memory).
     * A failed download keeps what was there. Returns how many there are.
     */
    private suspend fun syncXtreamVod(p: ProviderEntity, ua: String?): Int {
        setState(p.id, SyncState.Running("Loading films and series"))
        fun groups(action: String): List<Pair<String, String>> = runCatching {
            jsonItems(text(xtreamApi(p, action), ua)).map { it.optString("category_id") to it.optString("category_name") }
        }.getOrDefault(emptyList())
        fun stream(action: String, each: (Map<String, String>) -> Unit): Boolean = runCatching {
            val b = Request.Builder().url(xtreamApi(p, action))
            if (ua != null) b.header("User-Agent", ua)
            http.newCall(b.build()).execute().use { r ->
                if (!r.isSuccessful) return@runCatching false
                r.body?.charStream()?.let { com.vantaview.app.data.vod.VodImport.readItems(it, each) }
                true
            }
        }.getOrDefault(false)
        val items = ArrayList<VodEntity>()
        for ((kind, cats, list) in listOf(
            Triple("movie", "get_vod_categories", "get_vod_streams"),
            Triple("series", "get_series_categories", "get_series"),
        )) {
            val g = groups(cats)
            val names = g.toMap()
            val order = g.withIndex().associate { (i, c) -> c.first to i }
            var i = 0
            val ok = stream(list) { o ->
                // Rows follow the provider's category order; titles keep their order within each.
                val pos = (order[o["category_id"]] ?: g.size) * 1_000_000 + i++
                val v = if (kind == "movie") com.vantaview.app.data.vod.VodImport.xtreamFilm(p.id, o, names, pos)
                    else com.vantaview.app.data.vod.VodImport.xtreamSeries(p.id, o, names, pos)
                if (v != null) items += v
            }
            if (!ok) return db.vod().count(p.id, "movie") + db.vod().count(p.id, "series")
        }
        db.vod().replace(p.id, items, emptyList())
        return items.size
    }

    private fun hideLogin(url: String, u: String, pw: String): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return url.replace("=${enc(u)}", "=$USER_TOKEN").replace("=$u", "=$USER_TOKEN")
            .replace("=${enc(pw)}", "=$PASS_TOKEN").replace("=$pw", "=$PASS_TOKEN")
    }

    /** A guide link saved with placeholders, filled in with the playlist's login. */
    fun withLogin(p: ProviderEntity, url: String): String =
        if (USER_TOKEN in url || PASS_TOKEN in url) url.replace(USER_TOKEN, URLEncoder.encode(p.username, "UTF-8"))
            .replace(PASS_TOKEN, URLEncoder.encode(crypto.decrypt(p.passwordEnc), "UTF-8")) else url

    /** The address to play for a playlist film or episode, from how it was stored. */
    suspend fun vodAddress(providerId: Long, stored: String): String = withContext(Dispatchers.IO) {
        val p = db.providers().get(providerId) ?: throw IllegalStateException("Playlist not found")
        val pass by lazy { crypto.decrypt(p.passwordEnc) }
        when {
            stored.startsWith("xtreamurl:") -> stored.removePrefix("xtreamurl:").replace(USER_TOKEN, p.username).replace(PASS_TOKEN, pass)
            stored.startsWith("xtream-movie:") -> "${p.baseUrl}/movie/${p.username}/$pass/${stored.removePrefix("xtream-movie:")}"
            stored.startsWith("xtream-series:") -> "${p.baseUrl}/series/${p.username}/$pass/${stored.removePrefix("xtream-series:")}"
            else -> stored
        }
    }

    /** An Xtream series' seasons and episodes (get_series_info), or null. */
    suspend fun xtreamSeriesInfo(providerId: Long, seriesId: String): JSONObject? = withContext(Dispatchers.IO) {
        val p = db.providers().get(providerId)?.takeIf { it.kind == "XTREAM" } ?: return@withContext null
        val url = xtreamApi(p, "get_series_info") + "&series_id=" + URLEncoder.encode(seriesId, "UTF-8")
        for (ua in xtreamAgents) {
            val o = runCatching { JSONObject(text(url, ua)) }.getOrNull()
            if (o != null && (o.has("episodes") || o.has("info"))) return@withContext o
        }
        null
    }

    private fun xtreamApi(p: ProviderEntity, action: String? = null): String {
        val user = URLEncoder.encode(p.username, "UTF-8")
        val pass = URLEncoder.encode(crypto.decrypt(p.passwordEnc), "UTF-8")
        return "${p.baseUrl}/player_api.php?username=$user&password=$pass" + (action?.let { "&action=$it" } ?: "")
    }

    /** Fetches a page as text. A null userAgent sends the HTTP library's own default one. */
    private fun text(url: String, ua: String? = userAgent): String {
        val builder = Request.Builder().url(url)
        if (ua != null) builder.header("User-Agent", ua)
        return http.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("The server answered with error ${response.code}")
            response.body?.string().orEmpty()
        }
    }

    /**
     * Xtream servers answer list calls in different shapes: usually an array, sometimes an object
     * keyed by number. Both are read here; anything else gives an empty list.
     */
    private fun jsonItems(body: String): List<JSONObject> {
        val t = body.trim().removePrefix("\uFEFF").trim()
        return when {
            t.startsWith("[") -> {
                val a = JSONArray(t)
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            }
            t.startsWith("{") -> {
                val o = JSONObject(t)
                o.keys().asSequence().mapNotNull { o.optJSONObject(it) }.toList()
            }
            else -> emptyList()
        }
    }

    private fun streamId(o: JSONObject): String = o.optString("stream_id").ifBlank { o.optString("id") }

    /**
     * App identities tried in turn. Some providers only answer apps they recognise, and many block
     * the HTTP library's default identity.
     */
    private val xtreamAgents: List<String?> = listOf(
        userAgent,
        "IPTVSmartersPro",
        "VLC/3.0.20 LibVLC/3.0.20",
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36",
        null,
    )

    private suspend fun syncXtream(p: ProviderEntity): SyncState.Done {
        setState(p.id, SyncState.Running("Signing in"))
        val login = JSONObject(text(xtreamApi(p)))
        val info = login.optJSONObject("user_info")
        val auth = info?.optString("auth")
        if (auth == null || auth == "0") throw IllegalStateException("The provider refused the username or password")
        val status = info?.optString("status").orEmpty().ifBlank { "unknown" }
        // Catch-up (timeshift) times are in the server's own time zone.
        login.optJSONObject("server_info")?.optString("timezone")?.ifBlank { null }?.let { zones[p.id] = it }
        val notes = ArrayList<String>()

        // Tries the whole list first, then one category at a time (some servers only answer that way).
        fun load(ua: String?): Pair<List<CategoryEntity>, List<JSONObject>> {
            val cats = runCatching { jsonItems(text(xtreamApi(p, "get_live_categories"), ua)) }.getOrDefault(emptyList())
            val categories = cats.mapIndexedNotNull { i, o ->
                val id = o.optString("category_id")
                if (id.isBlank()) null
                else CategoryEntity(
                    providerId = p.id,
                    remoteId = id,
                    name = o.optString("category_name").ifBlank { "Category ${i + 1}" },
                    position = i,
                )
            }
            val all = runCatching { jsonItems(text(xtreamApi(p, "get_live_streams"), ua)) }
                .getOrDefault(emptyList())
                .filter { streamId(it).isNotBlank() }
            if (all.isNotEmpty() || categories.isEmpty()) return categories to all

            val perCategory = ArrayList<JSONObject>()
            categories.forEachIndexed { i, c ->
                setState(p.id, SyncState.Running("Loading channels, category ${i + 1} of ${categories.size}"))
                val url = xtreamApi(p, "get_live_streams") + "&category_id=" + URLEncoder.encode(c.remoteId, "UTF-8")
                runCatching { jsonItems(text(url, ua)) }.getOrDefault(emptyList())
                    .filter { streamId(it).isNotBlank() }
                    .forEach { o ->
                        if (o.optString("category_id").isBlank()) o.put("category_id", c.remoteId)
                        perCategory += o
                    }
            }
            return categories to perCategory
        }

        var categories: List<CategoryEntity> = emptyList()
        var streams: List<JSONObject> = emptyList()
        var goodUa: String? = userAgent
        for ((i, ua) in xtreamAgents.withIndex()) {
            setState(p.id, SyncState.Running("Loading channels (attempt ${i + 1} of ${xtreamAgents.size + 1})"))
            val (c, s) = load(ua)
            if (c.size > categories.size) categories = c
            if (s.isNotEmpty()) { categories = c; streams = s; goodUa = ua; break }
        }
        if (streams.isEmpty()) notes += "the channel list came back empty"

        if (streams.isNotEmpty()) {
            val seen = HashSet<String>()
            val channels = streams.mapIndexedNotNull { i, o ->
                val id = streamId(o)
                if (!seen.add(id)) return@mapIndexedNotNull null
                val name = o.optString("name").trim().ifBlank { "Channel ${i + 1}" }
                val catName = categories.firstOrNull { it.remoteId == o.optString("category_id") }?.name
                ChannelEntity(
                    isRadio = RadioDetect.isRadio(name, catName, o.optString("stream_type").contains("radio", true)),
                    providerId = p.id,
                    remoteId = id,
                    name = name,
                    sortKey = name.lowercase(),
                    number = o.optInt("num", i + 1),
                    logo = o.optString("stream_icon").ifBlank { null },
                    categoryRemoteId = o.optString("category_id").ifBlank { null },
                    streamUrl = "xtream:$id",
                    epgChannelId = o.optString("epg_channel_id").ifBlank { null },
                    isAdult = o.optString("is_adult") == "1",
                    archiveDays = if (o.optString("tv_archive") == "1") o.optInt("tv_archive_duration", 0).coerceAtLeast(1) else 0,
                    catchupSource = if (o.optString("tv_archive") == "1") "xc|" + xtreamZone(p.id) else null,
                )
            }
            setState(p.id, SyncState.Running("Saving ${channels.size} channels"))
            db.content().replaceLive(p.id, categories, channels)
            // Films and series too; a problem there never stops the channels working.
            val vod = runCatching { syncXtreamVod(p, goodUa) }.getOrDefault(0)
            return SyncState.Done(channels.size, vod)
        }

        // Last resort: the same account's M3U download (get.php), which some servers still serve.
        setState(p.id, SyncState.Running("Trying the provider's playlist download"))
        val pass = crypto.decrypt(p.passwordEnc)
        val m3uUrl = "${p.baseUrl}/get.php?username=${URLEncoder.encode(p.username, "UTF-8")}" +
            "&password=${URLEncoder.encode(pass, "UTF-8")}&type=m3u_plus&output=ts"
        for (ua in xtreamAgents) {
            val result = runCatching {
                val b = Request.Builder().url(m3uUrl)
                if (ua != null) b.header("User-Agent", ua)
                http.newCall(b.build()).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("error ${r.code}")
                    r.body!!.charStream().buffered().useLines { M3uParser.parse(it) }
                }
            }
            val parsed = result.getOrNull()
            if (parsed == null) { notes += "playlist download: ${result.exceptionOrNull()?.message ?: "failed"}"; continue }
            if (parsed.entries.isEmpty()) { notes += "playlist download had no live channels"; continue }
            val saved = saveM3uResult(p, parsed, hideCredentials = p.username to pass)
            return saved
        }

        throw IllegalStateException(
            com.vantaview.app.ui.tr(
                "Signed in (account status: {0}), but this provider sent no live channels to VantaView. Live categories found: {1}. Tried {2} app identities and the playlist download ({3}). If another app shows channels with the same login, the provider may limit which apps it serves.",
                status, categories.size, xtreamAgents.size, notes.distinct().joinToString("; "),
            )
        )
    }

    private suspend fun syncStalker(p: ProviderEntity): SyncState.Done {
        setState(p.id, SyncState.Running("Signing in to the portal"))
        stalker.check(p)
        setState(p.id, SyncState.Running("Loading categories"))
        val genres = runCatching { stalker.liveGenres(p) }.getOrDefault(emptyList())
        val censored = genres.filter { it.censored }.map { it.id }.toSet()
        val categories = genres.mapIndexed { i, g -> CategoryEntity(providerId = p.id, remoteId = g.id, name = g.title, position = i) }
        setState(p.id, SyncState.Running("Loading channels"))
        val list = stalker.liveChannels(p) { setState(p.id, SyncState.Running(it)) }
        val seen = HashSet<String>()
        val channels = list.mapIndexedNotNull { i, o ->
            val id = o.optString("id")
            val cmd = o.optString("cmd")
            if (id.isBlank() || cmd.isBlank() || !seen.add(id)) return@mapIndexedNotNull null
            val name = o.optString("name").trim().ifBlank { "Channel ${i + 1}" }
            val genre = o.optString("tv_genre_id").ifBlank { null }
            ChannelEntity(
                isRadio = RadioDetect.isRadio(name, genres.firstOrNull { it.id == genre }?.title, o.optString("radio") == "1" || o.optBoolean("radio", false)),
                providerId = p.id,
                remoteId = id,
                name = name,
                sortKey = name.lowercase(),
                number = o.optString("number").toIntOrNull() ?: (i + 1),
                logo = stalker.absolute(p, o.optString("logo")),
                categoryRemoteId = genre,
                streamUrl = "stalker:$cmd",
                // The portal's own guide is keyed by channel id.
                epgChannelId = id,
                isAdult = o.optString("censored") == "1" || (genre != null && genre in censored),
                archiveDays = if (o.optString("tv_archive") == "1" || o.optInt("tv_archive", 0) == 1)
                    (o.optInt("tv_archive_duration", 0).let { h -> if (h > 0) (h + 23) / 24 else 1 }) else 0,
            )
        }
        if (channels.isEmpty()) throw IllegalStateException("Signed in, but the portal sent no live channels")
        setState(p.id, SyncState.Running("Saving ${channels.size} channels"))
        db.content().replaceLive(p.id, categories, channels)
        return SyncState.Done(channels.size, 0)
    }

    /**
     * Catch-up: an address for a programme that has already aired, when the channel's provider keeps
     * recordings (Stalker portals with TV archive). Null when there's none.
     */
    suspend fun catchUp(channel: ChannelEntity, programme: com.vantaview.app.data.db.ProgrammeEntity): PlayTarget? {
        if (channel.archiveDays <= 0) return null
        val p = db.providers().get(channel.providerId) ?: return null
        if (p.kind == "STALKER") {
            val id = programme.remoteId ?: return null
            return PlayTarget(stalker.archiveLink(p, id), null, stalker.playHeaders)
        }
        val rule = channel.catchupSource ?: return null
        val live = resolveStream(channel)
        val url = CatchUp.build(rule, live.url, programme.start, programme.stop, System.currentTimeMillis())
            ?: throw IllegalStateException("This channel's catch-up address isn't one VantaView understands yet")
        return PlayTarget(url, null, live.headers)
    }

    /** True when the channel can play from any recent time (Xtream and M3U catch-up, not Stalker programme links). */
    fun canTimeshift(channel: ChannelEntity): Boolean = channel.archiveDays > 0 && channel.catchupSource != null

    /** The channel from [from] (wall clock) up to [to], from the provider's catch-up archive. */
    suspend fun timeshift(channel: ChannelEntity, from: Long, to: Long): PlayTarget? {
        if (!canTimeshift(channel)) return null
        val live = resolveStream(channel)
        val url = CatchUp.build(channel.catchupSource!!, live.url, from, to, System.currentTimeMillis()) ?: return null
        return PlayTarget(url, null, live.headers)
    }

    /** True when catch-up can be offered for this programme. */
    fun hasCatchUp(channel: ChannelEntity, programme: com.vantaview.app.data.db.ProgrammeEntity, now: Long = System.currentTimeMillis()): Boolean =
        channel.archiveDays > 0 && (programme.remoteId != null || channel.catchupSource != null) && programme.stop <= now &&
            programme.start > now - channel.archiveDays * 86_400_000L

    /** Turns a stored channel into a real address (and a second one to try) just before playback. */
    suspend fun resolveStream(channel: ChannelEntity): PlayTarget = withContext(Dispatchers.IO) {
        val headers = channel.headersJson?.let { json ->
            runCatching {
                val o = JSONObject(json)
                o.keys().asSequence().associateWith { o.optString(it) }
            }.getOrNull()
        } ?: emptyMap()
        if (channel.streamUrl.startsWith("xtream:")) {
            val p = db.providers().get(channel.providerId) ?: throw IllegalStateException("Playlist not found")
            val id = channel.streamUrl.removePrefix("xtream:")
            val base = "${p.baseUrl}/live/${p.username}/${crypto.decrypt(p.passwordEnc)}/$id"
            PlayTarget("$base.ts", "$base.m3u8", headers)
        } else if (channel.streamUrl.startsWith("xtreamurl:")) {
            val p = db.providers().get(channel.providerId) ?: throw IllegalStateException("Playlist not found")
            val url = channel.streamUrl.removePrefix("xtreamurl:")
                .replace(USER_TOKEN, p.username)
                .replace(PASS_TOKEN, crypto.decrypt(p.passwordEnc))
            val m3u8 = if (url.endsWith(".ts")) url.removeSuffix(".ts") + ".m3u8" else null
            PlayTarget(url, m3u8, headers)
        } else if (channel.streamUrl.startsWith("stalker:")) {
            val p = db.providers().get(channel.providerId) ?: throw IllegalStateException("Playlist not found")
            PlayTarget(stalker.createLink(p, "itv", channel.streamUrl.removePrefix("stalker:")), null, stalker.playHeaders + headers)
        } else {
            PlayTarget(
                channel.streamUrl, null, com.vantaview.app.data.player.Drm.httpHeaders(headers),
                drm = com.vantaview.app.data.player.Drm.info(headers), dash = com.vantaview.app.data.player.Drm.isDash(channel.streamUrl, headers),
            )
        }
    }

    private fun sha1(s: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
