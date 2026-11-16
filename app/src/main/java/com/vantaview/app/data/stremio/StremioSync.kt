// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.stremio

import android.content.Context
import com.vantaview.app.data.addon.AddonRepository
import com.vantaview.app.data.addon.Manifest
import com.vantaview.app.data.db.VantaDb
import com.vantaview.app.data.history.HistoryEntry
import com.vantaview.app.data.history.WatchHistory
import com.vantaview.app.data.library.LibraryChange
import com.vantaview.app.data.library.LibraryStore
import com.vantaview.app.data.library.SavedTitle
import com.vantaview.app.data.profile.ProfileSession
import com.vantaview.app.data.security.CredentialCrypto
import com.vantaview.app.data.trakt.TraktMerge
import com.vantaview.app.ui.tr
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** What a profile's Stremio account keeps in step. */
data class StremioOptions(
    val addons: Boolean = true,
    val library: Boolean = true,
    val progress: Boolean = true,
    val watched: Boolean = true,
)

data class StremioLink(val email: String, val key: String, val options: StremioOptions, val lastSync: Long)

sealed class StremioState {
    data object Idle : StremioState()
    data class Busy(val text: String) : StremioState()
    data class Done(val text: String) : StremioState()
    data class Failed(val text: String) : StremioState()
}

/**
 * Keeps a profile in step with its Stremio account, both ways: add-ons, Library (VantaView's Watchlist),
 * Continue watching and watched films. Like Trakt, the first sync only adds (nothing is removed on either
 * side); after that, adding or removing on one side does the same on the other. Each profile signs in to
 * its own account; the session key is encrypted on the device and the password is never kept.
 */
@Singleton
class StremioSync @Inject constructor(
    @ApplicationContext context: Context,
    http: OkHttpClient,
    private val crypto: CredentialCrypto,
    private val db: VantaDb,
    private val addons: AddonRepository,
    private val library: LibraryStore,
    private val history: WatchHistory,
    private val session: ProfileSession,
    @com.vantaview.app.di.AppScope private val scope: CoroutineScope,
) {
    val api = StremioApi(http)
    private val sp = context.getSharedPreferences("stremio", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val _state = MutableStateFlow<StremioState>(StremioState.Idle)
    val state: StateFlow<StremioState> = _state
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version
    private fun bump() { _version.value++ }

    // ---- The link, per profile ----

    fun link(profile: Long = session.id): StremioLink? {
        val key = sp.getString("key_$profile", null)?.let { runCatching { crypto.decrypt(it) }.getOrNull() }?.ifBlank { null } ?: return null
        return StremioLink(
            email = sp.getString("email_$profile", "").orEmpty(), key = key,
            options = StremioOptions(
                addons = sp.getBoolean("o_addons_$profile", true), library = sp.getBoolean("o_lib_$profile", true),
                progress = sp.getBoolean("o_prog_$profile", true), watched = sp.getBoolean("o_seen_$profile", true),
            ),
            lastSync = sp.getLong("synced_$profile", 0L),
        )
    }

    fun setOptions(o: StremioOptions, profile: Long = session.id) {
        sp.edit().putBoolean("o_addons_$profile", o.addons).putBoolean("o_lib_$profile", o.library)
            .putBoolean("o_prog_$profile", o.progress).putBoolean("o_seen_$profile", o.watched).apply()
        bump()
    }

    private suspend fun saveLink(profile: Long, key: String) {
        val email = api.email(key)
        sp.edit().putString("key_$profile", crypto.encrypt(key)).putString("email_$profile", email).apply()
        bump()
        scope.launch { runCatching { sync(profile, force = true) } }
    }

    suspend fun signIn(email: String, password: String, profile: Long = session.id) {
        if (email.isBlank() || password.isBlank()) throw StremioException(tr("Enter your Stremio email and password"))
        saveLink(profile, api.login(email, password))
    }

    suspend fun startQr(): StremioLinkCode = api.linkCreate()

    /** Waits up to 5 minutes for the code to be confirmed, then links the profile and syncs. */
    suspend fun finishQr(code: StremioLinkCode, profile: Long = session.id) {
        val until = System.currentTimeMillis() + 5 * 60_000L
        while (System.currentTimeMillis() < until) {
            delay(3_000)
            val key = api.linkRead(code.code) ?: continue
            saveLink(profile, key)
            return
        }
        throw StremioException(tr("The code ran out of time. Start again."))
    }

    fun signOut(profile: Long = session.id) {
        link(profile)?.let { l -> scope.launch { api.logout(l.key) } }
        val e = sp.edit()
        sp.all.keys.filter { it.endsWith("_$profile") }.forEach { e.remove(it) }
        e.apply()
        _state.value = StremioState.Idle
        bump()
    }

    private fun snapshot(profile: Long, name: String): Set<String>? =
        if (sp.contains("snap_${name}_$profile")) sp.getStringSet("snap_${name}_$profile", emptySet()).orEmpty().toSet() else null

    private fun saveSnapshot(profile: Long, name: String, keys: Set<String>) { sp.edit().putStringSet("snap_${name}_$profile", keys).apply() }

    private fun editSnapshot(profile: Long, name: String, key: String, on: Boolean) {
        val s = snapshot(profile, name) ?: return
        saveSnapshot(profile, name, if (on) s + key else s - key)
    }

    // ---- Running ----

    private var started = false

    /** Listens for Library changes and profile switches, and syncs every half hour. Called once at start-up. */
    fun start() {
        if (started) return
        started = true
        scope.launch { library.changes.collect { (profile, c) -> runCatching { push(profile, c) } } }
        scope.launch { session.current.drop(1).collect { runCatching { sync(it, force = false) } } }
        scope.launch {
            delay(10_000)
            while (true) {
                runCatching { sync(session.id, force = false) }
                delay(30 * 60_000L)
            }
        }
    }

    fun syncNow() { scope.launch { runCatching { sync(session.id, force = true) } } }

    suspend fun sync(profile: Long, force: Boolean) = lock.withLock {
        val l = link(profile) ?: return@withLock
        if (!force && System.currentTimeMillis() - l.lastSync < 25 * 60_000L) return@withLock
        val done = ArrayList<String>()
        try {
            // Add-ons install for the profile in use, so they're only synced for that one.
            if (l.options.addons && profile == session.id) { _state.value = StremioState.Busy(tr("Syncing add-ons…")); done += syncAddons(profile, l.key) }
            if (l.options.library || l.options.progress || l.options.watched) {
                _state.value = StremioState.Busy(tr("Syncing your Stremio library…"))
                done += syncLibrary(profile, l)
            }
            sp.edit().putLong("synced_$profile", System.currentTimeMillis()).apply()
            bump()
            _state.value = StremioState.Done(done.filter { it.isNotBlank() }.joinToString(" · ").ifBlank { tr("Everything already matched.") })
        } catch (e: Exception) {
            _state.value = StremioState.Failed(e.message ?: tr("Stremio sync failed."))
        }
    }

    /** Add-ons that only work inside Stremio itself (Local Files on this computer). */
    private fun localOnly(url: String) = !url.startsWith("http") || "127.0.0.1" in url || "localhost" in url

    private suspend fun syncAddons(profile: Long, key: String): String {
        val remote = api.addons(key)
        val remoteByUrl = LinkedHashMap<String, JSONObject>()
        remote.forEach { a -> a.optString("transportUrl").takeIf { !localOnly(it) }?.let { remoteByUrl[Manifest.normalizeUrl(it)] = a } }
        val localRows = db.addons().allFor(profile)
        val localByUrl = localRows.associateBy { it.manifestUrl }
        val m = TraktMerge.merge(localByUrl.keys, remoteByUrl.keys, snapshot(profile, "addons"))
        // Stremio's own add-ons marked protected (like Cinemeta) are never taken off the account.
        val keepRemote = m.pushRemove.filter { remoteByUrl[it]?.optJSONObject("flags")?.optBoolean("protected") == true }.toSet()
        var installed = 0
        // One that won't install here (it needs setting up, or its server is down) is tried again next time,
        // and is never taken off the account because of it.
        val failed = HashSet<String>()
        for (url in m.result - localByUrl.keys) if (runCatching { addons.install(url) }.isSuccess) installed++ else failed += url
        var removedHere = 0
        for (url in localByUrl.keys - m.result) localByUrl[url]?.let { addons.remove(it.id); removedHere++ }
        if (m.pushAdd.isNotEmpty() || (m.pushRemove - keepRemote).isNotEmpty()) {
            val out = remote.filter { a ->
                val u = a.optString("transportUrl")
                localOnly(u) || Manifest.normalizeUrl(u) !in m.pushRemove || Manifest.normalizeUrl(u) in keepRemote
            }.toMutableList()
            m.pushAdd.forEach { url ->
                val row = localByUrl[url] ?: return@forEach
                val manifest = runCatching { JSONObject(row.manifestJson) }.getOrNull() ?: return@forEach
                out += JSONObject().put("transportUrl", url).put("transportName", "http").put("manifest", manifest)
                    .put("flags", JSONObject().put("official", false).put("protected", false))
            }
            api.setAddons(key, out)
        }
        saveSnapshot(profile, "addons", m.result + keepRemote - failed)
        return listOfNotNull(
            installed.takeIf { it > 0 }?.let { tr("{0} add-ons from Stremio", it) },
            m.pushAdd.size.takeIf { it > 0 }?.let { tr("{0} add-ons sent to Stremio", it) },
            (removedHere + (m.pushRemove - keepRemote).size).takeIf { it > 0 }?.let { tr("{0} add-ons removed", it) },
        ).joinToString(", ")
    }

    private fun kind(o: JSONObject) = o.optString("type").takeIf { it == "movie" || it == "series" }
    private fun inLibrary(o: JSONObject) = !o.optBoolean("removed") && !o.optBoolean("temp")
    private fun st(o: JSONObject): JSONObject = o.optJSONObject("state") ?: JSONObject().also { o.put("state", it) }
    private fun isWatchedFilm(o: JSONObject) = o.optString("type") == "movie" && (st(o).optInt("timesWatched") > 0 || st(o).optInt("flaggedWatched") > 0)
    private fun keyOf(type: String, id: String) = "$type:$id"
    private fun keyOf(o: JSONObject) = keyOf(o.optString("type"), o.optString("_id"))

    private suspend fun syncLibrary(profile: Long, l: StremioLink): String {
        val items = api.library(l.key).filter { kind(it) != null }
        val byKey = items.associateBy(::keyOf).toMutableMap()
        val changed = LinkedHashMap<String, JSONObject>()
        val now = System.currentTimeMillis()
        fun item(type: String, id: String, name: String, poster: String?): JSONObject =
            byKey.getOrPut(keyOf(type, id)) { StremioApi.newItem(id, type, name, poster, now) }
        fun touch(o: JSONObject) { o.put("_mtime", StremioApi.iso(now)); changed[keyOf(o)] = o }
        val out = ArrayList<String>()

        // Library ↔ Watchlist.
        if (l.options.library) {
            val local = library.watchlistNow(profile)
            // Only films and series are shared; anything else in the Watchlist stays as it is.
            val localByKey = local.filter { it.type == "movie" || it.type == "series" }.associateBy { keyOf(it.type, it.id) }
            val remote = items.filter(::inLibrary).associateBy(::keyOf)
            val m = TraktMerge.merge(localByKey.keys, remote.keys, snapshot(profile, "lib"))
            m.pushAdd.forEach { k -> localByKey[k]?.let { t -> item(t.type, t.id, t.name, t.poster).apply { put("removed", false); put("temp", false); touch(this) } } }
            m.pushRemove.forEach { k -> remote[k]?.apply { put("removed", true); touch(this) } }
            val fromStremio = (m.result - localByKey.keys).mapNotNull { remote[it] }.map(::saved)
            library.replaceFromSync(profile, fromStremio + local.filter { keyOf(it.type, it.id) !in localByKey || keyOf(it.type, it.id) in m.result }, null)
            saveSnapshot(profile, "lib", m.result)
            out += counts(tr("Library"), fromStremio.size, m.pushAdd.size, (localByKey.keys - m.result).size + m.pushRemove.size)
        }

        // Watched films.
        if (l.options.watched) {
            val seen = library.watchedNow(profile)
            val localFilms = seen.filter { it.type == "movie" }.associateBy { keyOf(it.type, it.id) }
            val remote = items.filter(::isWatchedFilm).associateBy(::keyOf)
            val m = TraktMerge.merge(localFilms.keys, remote.keys, snapshot(profile, "seen"))
            m.pushAdd.forEach { k -> localFilms[k]?.let { t -> item(t.type, t.id, t.name, t.poster).apply { markWatched(this, true, now); touch(this) } } }
            m.pushRemove.forEach { k -> remote[k]?.apply { markWatched(this, false, now); touch(this) } }
            val fromStremio = (m.result - localFilms.keys).mapNotNull { remote[it] }.map(::saved)
            library.replaceFromSync(profile, null, fromStremio + seen.filter { it.type != "movie" || keyOf(it.type, it.id) in m.result })
            saveSnapshot(profile, "seen", m.result)
            out += counts(tr("watched films"), fromStremio.size, m.pushAdd.size, 0)
        }

        // Continue watching: whichever side watched it last wins.
        if (l.options.progress) {
            var pulled = 0
            var pushed = 0
            for (o in items) {
                val s = st(o)
                val offset = s.optLong("timeOffset")
                val at = StremioApi.ms(s.optString("lastWatched"))
                if (offset <= 0 || at <= 0 || (o.optBoolean("removed") && !o.optBoolean("temp"))) continue
                val type = kind(o) ?: continue
                val id = o.optString("_id")
                if (history.removedAt(profile, type, id) >= at) continue
                val have = history.getFor(profile, type, id)
                if (have != null && have.updatedAt >= at) continue
                val video = StremioApi.str(s, "video_id") ?: id
                val parts = video.split(':')
                history.recordFor(profile, HistoryEntry(
                    type = type, metaId = id, videoId = video, title = o.optString("name"),
                    poster = have?.poster ?: StremioApi.str(o, "poster"), background = have?.background,
                    season = if (type == "series") parts.getOrNull(parts.size - 2)?.toIntOrNull() else null,
                    episode = if (type == "series") parts.lastOrNull()?.toIntOrNull() else null,
                    episodeTitle = null, positionMs = offset, durationMs = s.optLong("duration"), updatedAt = at,
                    knownSeason = have?.knownSeason ?: 0,
                ))
                pulled++
            }
            for (e in historyOf(profile)) {
                val o = byKey[keyOf(e.type, e.metaId)]
                val at = o?.let { StremioApi.ms(st(it).optString("lastWatched")) } ?: 0L
                if (e.updatedAt <= at + 1_000) continue
                writeProgress(item(e.type, e.metaId, e.title, e.poster), e)
                touch(byKey.getValue(keyOf(e.type, e.metaId)))
                pushed++
            }
            // Removed from Continue watching here since it was last watched there.
            for (o in items) {
                val type = kind(o) ?: continue
                val s = st(o)
                if (s.optLong("timeOffset") > 0 && history.removedAt(profile, type, o.optString("_id")) > StremioApi.ms(s.optString("lastWatched"))) {
                    s.put("timeOffset", 0); touch(o)
                }
            }
            out += listOfNotNull(
                pulled.takeIf { it > 0 }?.let { tr("{0} in Continue watching from Stremio", it) },
                pushed.takeIf { it > 0 }?.let { tr("{0} sent to Stremio", it) },
            ).joinToString(", ")
        }

        api.putLibrary(l.key, changed.values.toList())
        return out.filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun historyOf(profile: Long): List<HistoryEntry> =
        if (profile == session.id) history.all() else emptyList()

    private fun writeProgress(o: JSONObject, e: HistoryEntry) {
        val s = st(o)
        s.put("timeOffset", if (e.finished) 0 else e.positionMs)
        s.put("duration", e.durationMs)
        s.put("lastWatched", StremioApi.iso(e.updatedAt))
        s.put("video_id", e.videoId)
        if (e.finished && e.type == "movie") s.put("timesWatched", maxOf(1, s.optInt("timesWatched")))
    }

    private fun markWatched(o: JSONObject, on: Boolean, now: Long) {
        val s = st(o)
        if (on) { s.put("flaggedWatched", 1); s.put("timesWatched", maxOf(1, s.optInt("timesWatched"))); s.put("lastWatched", StremioApi.iso(now)) }
        else { s.put("flaggedWatched", 0); s.put("timesWatched", 0) }
    }

    private fun saved(o: JSONObject) = SavedTitle(
        o.optString("type"), o.optString("_id"), o.optString("name"), StremioApi.str(o, "poster"), null,
        StremioApi.ms(o.optString("_ctime")).takeIf { it > 0 } ?: System.currentTimeMillis(),
    )

    private fun counts(what: String, from: Int, to: Int, removed: Int): String = listOfNotNull(
        from.takeIf { it > 0 }?.let { tr("{0} {1} from Stremio", it, what) },
        to.takeIf { it > 0 }?.let { tr("{0} sent to Stremio", it) },
        removed.takeIf { it > 0 }?.let { tr("{0} removed", it) },
    ).joinToString(", ")

    // ---- Changes made here go to Stremio straight away ----

    /** One item as Stremio has it now (so its other fields are kept), or a new one. */
    private suspend fun current(key: String, type: String, id: String, name: String, poster: String?): JSONObject =
        api.libraryItem(key, type, id) ?: StremioApi.newItem(id, type, name, poster, System.currentTimeMillis())

    private suspend fun push(profile: Long, c: LibraryChange) {
        val l = link(profile) ?: return
        val now = System.currentTimeMillis()
        when (c) {
            is LibraryChange.Watchlist -> if (l.options.library && (c.title.type == "movie" || c.title.type == "series")) {
                val o = current(l.key, c.title.type, c.title.id, c.title.name, c.title.poster)
                o.put("removed", !c.on); if (c.on) o.put("temp", false); o.put("_mtime", StremioApi.iso(now))
                api.putLibrary(l.key, listOf(o))
                editSnapshot(profile, "lib", keyOf(c.title.type, c.title.id), c.on)
            }
            is LibraryChange.Watched -> if (l.options.watched && c.title.type == "movie") {
                val o = current(l.key, c.title.type, c.title.id, c.title.name, c.title.poster)
                markWatched(o, c.on, now); o.put("_mtime", StremioApi.iso(now))
                api.putLibrary(l.key, listOf(o))
                editSnapshot(profile, "seen", keyOf(c.title.type, c.title.id), c.on)
            }
            is LibraryChange.Episodes -> Unit // Stremio stores watched episodes in its own packed format; not shared.
        }
    }

    /** The player paused or stopped: send where this profile got to (after it's been saved). */
    fun progress(type: String, metaId: String) {
        val profile = session.id
        val l = link(profile) ?: return
        if (!l.options.progress || metaId.isBlank()) return
        scope.launch {
            runCatching {
                delay(2_000)
                val e = history.getFor(profile, type, metaId) ?: return@runCatching
                val o = current(l.key, type, metaId, e.title, e.poster)
                writeProgress(o, e); o.put("_mtime", StremioApi.iso(System.currentTimeMillis()))
                api.putLibrary(l.key, listOf(o))
            }
        }
    }
}
