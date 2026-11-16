// SPDX-License-Identifier: GPL-3.0-or-later
// Stream collection adapted from Stream Goat (c) Syed Ali Hamza, GPL-3.0
// https://github.com/sherazi5110/stream-goat  (Stremio.kt: install, loadLinks)
package com.vantaview.app.data.addon

import com.vantaview.app.BuildConfig
import com.vantaview.app.data.db.AddonEntity
import com.vantaview.app.data.db.VantaDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class InstalledAddon(val entity: AddonEntity, val manifest: Manifest) {
    val base: String get() = entity.manifestUrl.removeSuffix("/manifest.json")
}

/** An add-on's server offered a certificate this device doesn't trust; the user can choose to trust it. */
class UntrustedServerException(val url: String, val cert: com.vantaview.app.data.security.UntrustedCert) :
    IllegalArgumentException("Couldn't reach that add-on: its server's security certificate isn't trusted by this device")

/** A catalog row: which add-on, which catalog, and an optional genre filter. */
data class CatalogRef(val addon: InstalledAddon, val catalog: CatalogDef, val genre: String? = null) {
    val key: String get() = "${addon.entity.id}/${catalog.type}/${catalog.id}/${genre.orEmpty()}"
    val title: String get() = listOfNotNull(genre, catalog.name).joinToString(" · ") + " · ${addon.manifest.name}"
}

@Singleton
class AddonRepository @Inject constructor(
    private val db: VantaDb,
    private val http: OkHttpClient,
    private val session: com.vantaview.app.data.profile.ProfileSession,
    private val portalVod: com.vantaview.app.data.stalker.StalkerVod,
    private val playlistVod: com.vantaview.app.data.vod.PlaylistVod,
    private val parental: com.vantaview.app.data.profile.ParentalControl,
) {
    private val userAgent = "VantaView/${BuildConfig.VERSION_NAME}"
    private val cache = ConcurrentHashMap<String, Pair<Long, JSONObject>>()

    /** Add-ons of the profile in use. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(): Flow<List<InstalledAddon>> =
        session.current.flatMapLatest { db.addons().observeFor(it) }.map { list -> list.mapNotNull(::parse) }

    suspend fun count(): Int = db.addons().countFor(session.id)

    /** Stalker portals of the profile in use: their films and series show next to add-on catalogues. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observePortals(): Flow<List<com.vantaview.app.data.db.ProviderEntity>> =
        session.current.flatMapLatest { db.providers().observeFor(it) }.map { list -> list.filter { it.kind == "STALKER" } }

    /** Playlists that can carry films and series (portals, M3U, Xtream); emits again after each refresh. */
    fun observeVodSources(): Flow<List<com.vantaview.app.data.db.ProviderEntity>> =
        session.current.flatMapLatest { db.providers().observeFor(it) }.map { list -> list.filter { it.kind in setOf("STALKER", "M3U", "XTREAM") } }

    private suspend fun portals() = db.providers().allFor(session.id).filter { it.kind == "STALKER" }
    private suspend fun playlists() = db.providers().allFor(session.id)

    private fun parse(e: AddonEntity): InstalledAddon? =
        runCatching { InstalledAddon(e, Manifest.parse(JSONObject(e.manifestJson))) }.getOrNull()

    suspend fun enabled(): List<InstalledAddon> = db.addons().allFor(session.id).filter { it.enabled }.mapNotNull(::parse)

    /** Downloads the manifest and installs (or updates) the add-on. Throws with a readable message. */
    suspend fun install(input: String): InstalledAddon = withContext(Dispatchers.IO) {
        require(input.isNotBlank()) { "Paste the add-on's install link first" }
        val url = Manifest.normalizeUrl(input)
        val body = try {
            getText(url)
        } catch (e: Exception) {
            val host = runCatching { java.net.URI(url).host }.getOrNull()
            val bad = if (e is javax.net.ssl.SSLException || e.cause is java.security.cert.CertificateException) host?.let { com.vantaview.app.data.security.CertPins.rejectedFor(it) } else null
            if (bad != null && !com.vantaview.app.data.security.CertPins.isPinned(bad.fingerprint)) throw UntrustedServerException(url, bad)
            if (bad != null) throw IllegalArgumentException("Couldn't reach that add-on even with its certificate trusted (${e.message ?: e.javaClass.simpleName})")
            throw IllegalArgumentException(
                if (e is javax.net.ssl.SSLException || e.cause is java.security.cert.CertificateException)
                    "Couldn't reach that add-on: its server's security certificate isn't one this device trusts (self-signed or out of date). Ask whoever runs it, or try its http:// link."
                else if (Regex("error 5\\d\\d").containsMatchIn(e.message.orEmpty()))
                    "Couldn't reach that add-on: its server isn't answering right now (${e.message}). Try again later."
                else "Couldn't reach that add-on (${e.message ?: "network error"})"
            )
        }
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw IllegalArgumentException("That link isn't an add-on install link (no manifest.json found)")
        val manifest = runCatching { Manifest.parse(json) }.getOrNull()
            ?: throw IllegalArgumentException("The add-on's manifest is missing its id")
        if (manifest.configurationRequired) {
            throw IllegalArgumentException("This add-on needs setting up first. Open its configure page, then copy the install link it gives you.")
        }
        if (manifest.resources.isEmpty() && manifest.catalogs.isEmpty()) {
            throw IllegalArgumentException("This add-on doesn't offer anything VantaView can use")
        }
        val existing = db.addons().byUrl(session.id, url)
        val position = existing?.position ?: db.addons().allFor(session.id).size
        val entity = AddonEntity(
            id = existing?.id ?: 0,
            profileId = session.id,
            manifestUrl = url,
            name = manifest.name,
            manifestJson = json.toString(),
            enabled = existing?.enabled ?: true,
            position = position,
            addedAt = existing?.addedAt ?: System.currentTimeMillis(),
        )
        val id = db.addons().upsert(entity)
        cache.clear()
        InstalledAddon(entity.copy(id = if (entity.id == 0L) id else entity.id), manifest)
    }

    suspend fun remove(id: Long) { db.addons().delete(id); cache.clear() }

    suspend fun setEnabled(id: Long, on: Boolean) { db.addons().setEnabled(id, on); cache.clear() }

    /** Rows for a page: every catalog of [type] that works without a required extra. */
    suspend fun catalogs(type: String): List<CatalogRef> =
        enabled().flatMap { a -> a.manifest.catalogs.filter { typeOk(it.type, type) && !it.needsExtra }.map { CatalogRef(a, it) } } +
            if (type == TV) emptyList() else
                portalVod.catalogs(portals(), type, parental.hiding) + playlistVod.catalogs(playlists(), type, parental.hiding)

    /** Whether any add-on has rows for the Sports TV page (Stremio's "tv" and "channel" types). */
    suspend fun hasTv(): Boolean = catalogs(TV).isNotEmpty()

    /**
     * Rows for a Kids profile: each catalogue filtered to a family genre where it offers one, plus catalogues
     * that are themselves for children.
     */
    suspend fun kidsCatalogs(type: String): List<CatalogRef> = enabled().flatMap { a ->
        a.manifest.catalogs.filter { typeOk(it.type, type) }.mapNotNull { c ->
            val family = c.genres.firstOrNull { com.vantaview.app.data.profile.KidsFilter.isFamilyGenre(it) }
            val onlyGenreRequired = c.requiredExtras.all { it == "genre" }
            when {
                family != null && onlyGenreRequired -> CatalogRef(a, c, family)
                !c.needsExtra && com.vantaview.app.data.profile.KidsFilter.looksFamily(c.name + " " + c.id) -> CatalogRef(a, c)
                else -> null
            }
        }
    }

    suspend fun loadCatalog(ref: CatalogRef, skip: Int = 0): List<MetaPreview> {
        if (playlistVod.isPlaylist(ref)) return playlistVod.load(ref, skip)
        if (portalVod.isPortal(ref)) {
            if (skip > 0) return emptyList()
            val p = db.providers().get(-ref.addon.entity.id) ?: return emptyList()
            return portalVod.load(p, ref)
        }
        val extra = listOfNotNull(ref.genre?.let { "genre=${enc(it)}" }, skip.takeIf { it > 0 }?.let { "skip=$it" })
            .joinToString("&").let { if (it.isEmpty()) "" else "/$it" }
        val url = "${ref.addon.base}/catalog/${enc(ref.catalog.type)}/${enc(ref.catalog.id)}$extra.json"
        return json(url, maxAgeMs = 6 * 3_600_000L)?.optJSONArray("metas")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { AddonJson.preview(it, ref.catalog.type) }
        }.orEmpty().distinctBy { it.id }
    }

    /** Searches every searchable catalog of [type] at once. */
    suspend fun search(type: String, query: String): List<MetaPreview> = coroutineScope {
        val q = query.trim()
        if (q.isEmpty()) return@coroutineScope emptyList()
        val fromPortals = async { if (type == TV) emptyList() else runCatching { portalVod.search(portals(), type, q) }.getOrDefault(emptyList()) }
        val fromPlaylists = async { if (type == TV) emptyList() else runCatching { playlistVod.search(playlists(), type, q) }.getOrDefault(emptyList()) }
        val fromAddons = enabled().flatMap { a -> a.manifest.catalogs.filter { typeOk(it.type, type) && it.searchable }.map { a to it } }
            .map { (a, c) ->
                async {
                    val url = "${a.base}/catalog/${enc(c.type)}/${enc(c.id)}/search=${enc(q)}.json"
                    json(url, maxAgeMs = 600_000L)?.optJSONArray("metas")?.let { arr ->
                        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { AddonJson.preview(it, type) }
                    }.orEmpty()
                }
            }.awaitAll().flatten()
        (fromAddons + fromPortals.await() + fromPlaylists.await()).distinctBy { it.id }
    }

    /** Full details from the first add-on that has them. */
    suspend fun meta(type: String, id: String): MetaDetail? {
        if (portalVod.isPortalId(id)) return portalVod.meta(type, id)
        if (playlistVod.isPlaylistId(id)) return playlistVod.meta(type, id)
        for (a in enabled().filter { it.manifest.serves("meta", type, id) }) {
            val o = json("${a.base}/meta/${enc(type)}/${enc(id)}.json", maxAgeMs = 6 * 3_600_000L)?.optJSONObject("meta")
            if (o != null && o.optString("name").isNotBlank()) return AddonJson.detail(o, type)
        }
        return null
    }

    /** True when at least one enabled add-on can supply streams for this kind of title. */
    suspend fun hasStreamSource(type: String, id: String): Boolean =
        portalVod.isPortalId(id) || playlistVod.isPlaylistId(id) || enabled().any { it.manifest.serves("stream", type, id) } ||
            playlistVod.eligible(playlists()).isNotEmpty()

    /** True when an add-on (not just a playlist) could have streams for this title. */
    suspend fun hasAddonStreams(type: String, id: String): Boolean =
        portalVod.isPortalId(id) || playlistVod.isPlaylistId(id) || enabled().any { it.manifest.serves("stream", type, id) }

    /**
     * Streams for a film or episode ([id] is the film's id or the episode's video id): the playlist's own
     * "From your playlist" first (matched by IMDb id, or [title] and [year]), then every enabled add-on's,
     * best first. A playlist title also gets add-on streams once it's matched to an IMDb id.
     */
    suspend fun streams(type: String, id: String, title: String? = null, year: String? = null): List<AddonStream> {
        if (portalVod.isPortalId(id)) return portalVod.streams(id)
        if (playlistVod.isPlaylistId(id)) {
            val own = runCatching { playlistVod.streams(id) }.getOrDefault(emptyList())
            val other = runCatching { playlistVod.addonIdFor(id) }.getOrNull()?.let { addonStreams(type, it) }.orEmpty()
            return own + other
        }
        val mine = runCatching { playlistVod.matches(playlists(), type, id, title, year) }.getOrDefault(emptyList())
        return (mine + addonStreams(type, id)).distinctBy { it.key }
    }

    private suspend fun addonStreams(type: String, id: String): List<AddonStream> = coroutineScope {
        val addons = enabled()
        val jobs = addons.withIndex().filter { it.value.manifest.serves("stream", type, id) }.map { (order, a) ->
            async {
                withTimeoutOrNull(25_000) {
                    json("${a.base}/stream/${enc(type)}/${enc(id)}.json", maxAgeMs = 0)
                }?.let { AddonJson.streams(it, a.manifest.name, order) }.orEmpty()
            }
        }
        AddonJson.rank(jobs.awaitAll().flatten().distinctBy { it.key })
    }

    /** Web pages the add-ons link to for [id] instead of a video ("Open in browser"), with the add-on's name. */
    suspend fun externalLinks(type: String, id: String): List<Pair<String, String>> = coroutineScope {
        enabled().filter { it.manifest.serves("stream", type, id) }.map { a ->
            async {
                withTimeoutOrNull(25_000) { json("${a.base}/stream/${enc(type)}/${enc(id)}.json", maxAgeMs = 0) }
                    ?.let { AddonJson.externalLinks(it, a.manifest.name) }.orEmpty()
            }
        }.awaitAll().flatten().distinctBy { it.second }
    }

    // ---- Restore: Stremio account import and backup files ----

    /** An add-on found in a Stremio account or a backup file. */
    data class RemoteAddon(val url: String, val name: String, val description: String, val alreadyInstalled: Boolean)

    private fun postJson(url: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return http.newCall(request).execute().use { r -> JSONObject(r.body?.string().orEmpty().ifBlank { "{}" }) }
    }

    /**
     * Signs in to Stremio once and returns the add-ons installed on that account.
     * The email, password and session key are not stored.
     */
    suspend fun stremioAccountAddons(email: String, password: String): List<RemoteAddon> = withContext(Dispatchers.IO) {
        require(email.isNotBlank() && password.isNotBlank()) { "Enter your Stremio email and password" }
        val login = try {
            postJson(
                "https://api.strem.io/api/login",
                JSONObject().put("type", "Login").put("email", email.trim()).put("password", password).put("facebook", false),
            )
        } catch (e: Exception) {
            throw IllegalStateException("Couldn't reach Stremio (${e.message ?: "network error"})")
        }
        login.optJSONObject("error")?.let { err ->
            throw IllegalStateException(err.optString("message").ifBlank { "Stremio didn't accept that email or password" })
        }
        val authKey = login.optJSONObject("result")?.optString("authKey").orEmpty()
        if (authKey.isBlank()) throw IllegalStateException("Stremio didn't accept that email or password")

        val got = postJson(
            "https://api.strem.io/api/addonCollectionGet",
            JSONObject().put("type", "AddonCollectionGet").put("authKey", authKey).put("update", true),
        )
        got.optJSONObject("error")?.let { err ->
            throw IllegalStateException(err.optString("message").ifBlank { "Couldn't read your Stremio add-ons" })
        }
        val arr = got.optJSONObject("result")?.optJSONArray("addons") ?: throw IllegalStateException("No add-ons found on that account")
        val installed = db.addons().allFor(session.id).map { it.manifestUrl }.toSet()
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { a ->
            val raw = a.optString("transportUrl")
            // Add-ons that only run inside Stremio itself (such as Local Files on 127.0.0.1) can't be used here.
            if (!raw.startsWith("http") || raw.contains("127.0.0.1") || raw.contains("localhost")) return@mapNotNull null
            val url = Manifest.normalizeUrl(raw)
            val m = a.optJSONObject("manifest")
            RemoteAddon(
                url = url,
                name = m?.optString("name").orEmpty().ifBlank { url.substringAfter("://").substringBefore('/') },
                description = m?.optString("description").orEmpty(),
                alreadyInstalled = url in installed,
            )
        }.distinctBy { it.url }
    }

    /**
     * The add-on list as a backup file. Install links can contain personal keys (debrid, API keys),
     * so the file should be kept private.
     */
    suspend fun backupJson(): String {
        val list = org.json.JSONArray()
        db.addons().allFor(session.id).forEach { a ->
            list.put(JSONObject().put("manifestUrl", a.manifestUrl).put("name", a.name).put("enabled", a.enabled))
        }
        return JSONObject()
            .put("app", "VantaView")
            .put("kind", "addons")
            .put("version", 1)
            .put("exportedAt", System.currentTimeMillis())
            .put("addons", list)
            .toString(2)
    }

    /**
     * Reads an add-on backup: VantaView's own, Stremio Account Bootstrapper's Stremio ({result:{addons:[…]}})
     * or Nuvio ([{url, name, enabled, sort_order}]) backups, or a plain list of install links (one per line).
     */
    suspend fun readBackup(text: String): List<RemoteAddon> {
        val installed = db.addons().allFor(session.id).map { it.manifestUrl }.toSet()
        // Add-ons that only run inside Stremio itself (Local Files on 127.0.0.1) can't be used here.
        val pairs = parseBackup(text).filter { (u, _) -> !u.contains("127.0.0.1") && !u.contains("localhost") }
        if (pairs.isEmpty()) throw IllegalArgumentException("No add-on links were found in that file")
        return pairs.map { (u, n) ->
            val url = Manifest.normalizeUrl(u)
            RemoteAddon(url, n.ifBlank { url.substringAfter("://").substringBefore('/') }, "", url in installed)
        }.distinctBy { it.url }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun getText(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        return http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("error ${r.code}")
            r.body?.string().orEmpty()
        }
    }

    private suspend fun json(url: String, maxAgeMs: Long): JSONObject? = withContext(Dispatchers.IO) {
        if (maxAgeMs > 0) cache[url]?.let { (at, j) -> if (System.currentTimeMillis() - at < maxAgeMs) return@withContext j }
        runCatching { JSONObject(getText(url)) }.getOrNull()?.also { if (maxAgeMs > 0) cache[url] = System.currentTimeMillis() to it }
    }

    companion object {
        /** The Sports TV page's type: it shows add-on rows of Stremio's "tv" and "channel" types. */
        const val TV = "tv"
        fun typeOk(catalogType: String, page: String) = catalogType == page || (page == TV && catalogType == "channel")
    }
}
