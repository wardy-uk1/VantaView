// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.vod

import com.vantaview.app.data.addon.AddonJson
import com.vantaview.app.data.addon.AddonStream
import com.vantaview.app.data.addon.CatalogDef
import com.vantaview.app.data.addon.CatalogRef
import com.vantaview.app.data.addon.InstalledAddon
import com.vantaview.app.data.addon.Manifest
import com.vantaview.app.data.addon.MetaDetail
import com.vantaview.app.data.addon.MetaPreview
import com.vantaview.app.data.addon.Video
import com.vantaview.app.data.browse.BrowseRepository
import com.vantaview.app.data.db.AddonEntity
import com.vantaview.app.data.db.ProviderEntity
import com.vantaview.app.data.db.VantaDb
import com.vantaview.app.data.db.VodEntity
import com.vantaview.app.data.profile.KidsFilter
import com.vantaview.app.data.repo.ProviderRepository
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Films and series from M3U playlists and Xtream Codes accounts, on the Movies and TV Series pages.
 *
 * They show in two ways: as their own rows ("<playlist> · <category>"), which act like an add-on next to
 * the real add-ons without changing them; and as an extra stream, "From your playlist", on any film or
 * series whose name (or IMDb id) matches, wherever it was opened from. Opening a playlist title looks it up
 * on TMDB for the poster, description and cast, and remembers the IMDb id it found.
 *
 * Ids: "pl:<playlist id>:<movie|series>:<item id>" for a title, and
 * "pl:<playlist id>:ep:<series item id>:<season>:<episode>" for an episode.
 */
@Singleton
class PlaylistVod @Inject constructor(
    private val db: VantaDb,
    private val providers: ProviderRepository,
    private val browse: BrowseRepository,
) {
    private val seriesInfo = ConcurrentHashMap<String, Pair<Long, JSONObject>>()

    fun isPlaylist(ref: CatalogRef) = ref.addon.entity.id <= -OFFSET
    fun isPlaylistId(id: String) = id.startsWith("pl:")

    private fun playlistOf(ref: CatalogRef) = -ref.addon.entity.id - OFFSET

    /** Playlists that can carry films and series. */
    fun eligible(all: List<ProviderEntity>) = all.filter { it.kind == "M3U" || it.kind == "XTREAM" || it.kind == "JELLYFIN" }

    private fun asAddon(p: ProviderEntity) = InstalledAddon(
        AddonEntity(id = -(p.id + OFFSET), profileId = p.profileId, manifestUrl = "playlist://${p.id}", name = p.name, manifestJson = "{}"),
        Manifest(
            id = "playlist.${p.id}", name = p.name, version = "", description = "", logo = null, types = listOf("movie", "series"),
            idPrefixes = listOf("pl:${p.id}:"), catalogs = emptyList(), resources = emptyList(), configurationRequired = false,
        ),
    )

    /** Rows for Movies ("movie") or TV Series ("series"): newest first (when the provider dates them), all, then each category. */
    suspend fun catalogs(playlists: List<ProviderEntity>, type: String, hideAdult: Boolean): List<CatalogRef> =
        eligible(playlists).flatMap { p ->
            if (db.vod().count(p.id, type) == 0) return@flatMap emptyList()
            val addon = asAddon(p)
            val groups = db.vod().groups(p.id, type).filter { g -> !(hideAdult && KidsFilter.looksAdult(g.groupName)) && g.groupName != null }
            val top = buildList {
                if (p.kind == "XTREAM" || p.kind == "JELLYFIN") add(CatalogRef(addon, CatalogDef(type, "#new", "${p.name} · Recently added", searchable = false, needsExtra = false)))
                add(CatalogRef(addon, CatalogDef(type, "*", "${p.name} · All", searchable = true, needsExtra = false)))
            }
            top + groups.take(MAX_ROWS).map { g -> CatalogRef(addon, CatalogDef(type, "g:" + g.groupName, "${p.name} · ${g.groupName}", searchable = false, needsExtra = false)) }
        }

    suspend fun load(ref: CatalogRef, skip: Int): List<MetaPreview> {
        val pid = playlistOf(ref)
        val type = ref.catalog.type
        val rows = when {
            ref.catalog.id == "#new" -> db.vod().newest(pid, type, PAGE, skip)
            ref.catalog.id == "*" -> db.vod().all(pid, type, PAGE, skip)
            else -> db.vod().inGroup(pid, type, ref.catalog.id.removePrefix("g:"), PAGE, skip)
        }
        return rows.map(::preview)
    }

    suspend fun search(playlists: List<ProviderEntity>, type: String, query: String): List<MetaPreview> {
        val ids = eligible(playlists).map { it.id }
        val q = VodNames.norm(query)
        if (ids.isEmpty() || q.length < 2) return emptyList()
        return db.vod().search(ids, type, "%$q%", 40).map(::preview)
    }

    private fun preview(v: VodEntity) = MetaPreview(
        id = "pl:${v.providerId}:${v.kind}:${v.remoteId}", type = v.kind, name = v.name, poster = v.poster,
        year = v.year?.toString(), rating = v.rating, genres = listOfNotNull(v.groupName),
        tag = VodNames.langTag(v.groupName) ?: VodNames.langTag(v.name),
    )

    private suspend fun row(id: String): VodEntity? {
        val b = id.split(':', limit = 4)
        val pid = b.getOrNull(1)?.toLongOrNull() ?: return null
        val kind = b.getOrNull(2)?.takeIf { it == "movie" || it == "series" } ?: return null
        return db.vod().find(pid, kind, b.getOrNull(3) ?: return null)
    }

    /** The title's IMDb id, from the provider or found on TMDB by name and year (then remembered). */
    private suspend fun imdb(v: VodEntity): String? {
        v.imdb?.let { return it }
        val found = runCatching { browse.findImdb(v.kind, v.name, v.year) }.getOrNull() ?: return null
        db.vod().setImdb(v.id, found)
        return found
    }

    suspend fun meta(type: String, id: String): MetaDetail? {
        val v = row(id) ?: return null
        val tt = imdb(v)
        val d = tt?.let { runCatching { browse.details(v.kind, it) }.getOrNull() }
        val info = if (v.kind == "series") seriesData(v) else null
        val infoMeta = info?.optJSONObject("info")
        return MetaDetail(
            id = id, type = v.kind, name = d?.name?.ifBlank { null } ?: v.name,
            poster = d?.poster ?: v.poster, background = d?.background, logo = null,
            description = d?.overview ?: v.plot ?: infoMeta?.optString("plot")?.ifBlank { null },
            year = d?.year ?: v.year?.toString(), runtime = d?.runtime, rating = d?.rating ?: v.rating,
            genres = d?.genres ?: listOfNotNull(v.groupName), cast = d?.cast.orEmpty(), director = d?.director.orEmpty(),
            videos = if (v.kind == "series") episodes(v, info) else emptyList(),
            trailer = d?.trailer,
            group = v.groupName,
        )
    }

    private suspend fun seriesData(v: VodEntity): JSONObject? {
        val p = db.providers().get(v.providerId) ?: return null
        if (p.kind != "XTREAM") return null
        val key = "${v.providerId}/${v.remoteId}"
        seriesInfo[key]?.let { (at, o) -> if (System.currentTimeMillis() - at < 3_600_000L) return o }
        return providers.xtreamSeriesInfo(v.providerId, v.remoteId)?.also { seriesInfo[key] = System.currentTimeMillis() to it }
    }

    /** Xtream episodes, season by season: (season, number, title, plot, picture, "id.ext"). */
    private fun xtreamEpisodes(info: JSONObject): List<Pair<Video, String>> {
        val eps = info.optJSONObject("episodes") ?: return emptyList()
        return eps.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: 0 }.flatMap { s ->
            val arr = eps.optJSONArray(s) ?: return@flatMap emptyList()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { e ->
                val epId = e.optString("id").ifBlank { return@mapNotNull null }
                val season = e.optInt("season", s.toIntOrNull() ?: 1)
                val n = e.optInt("episode_num", 0)
                val extra = e.optJSONObject("info")
                Video(
                    id = "", title = e.optString("title").ifBlank { "Episode $n" }, season = season, episode = n,
                    thumbnail = extra?.optString("movie_image")?.ifBlank { null }, overview = extra?.optString("plot")?.ifBlank { null },
                    released = extra?.optString("releasedate")?.ifBlank { null },
                ) to "$epId.${e.optString("container_extension").ifBlank { "mp4" }}"
            }
        }
    }

    private suspend fun episodes(v: VodEntity, info: JSONObject?): List<Video> {
        fun id(s: Int, e: Int) = "pl:${v.providerId}:ep:${v.remoteId}:$s:$e"
        if (info != null) return xtreamEpisodes(info).map { (video, _) -> video.copy(id = id(video.season, video.episode)) }
        return db.vod().episodes(v.providerId, v.remoteId).map { e ->
            Video(id(e.season, e.episode), e.title ?: "Episode ${e.episode}", e.season, e.episode, null, null, null)
        }
    }

    /** The stored address of an episode, or null. */
    private suspend fun episodeAddress(v: VodEntity, season: Int, episode: Int): String? {
        val info = seriesData(v)
        if (info != null) {
            return xtreamEpisodes(info).firstOrNull { (e, _) -> e.season == season && e.episode == episode }?.let { "xtream-series:" + it.second }
        }
        return db.vod().episodes(v.providerId, v.remoteId).firstOrNull { it.season == season && it.episode == episode }?.url
    }

    private suspend fun stream(v: VodEntity, stored: String): AddonStream? {
        val url = runCatching { providers.vodAddress(v.providerId, stored) }.getOrNull() ?: return null
        val name = db.providers().get(v.providerId)?.name ?: "Playlist"
        return AddonStream(
            url = url, addonName = name, label = "From your playlist", headers = emptyMap(),
            quality = AddonJson.qualityOf("${v.name} $url"), sizeBytes = null, addonOrder = -1,
        )
    }

    private fun filmAddress(v: VodEntity): String? = v.url ?: v.ext?.let { "xtream-movie:${v.remoteId}.$it" }

    /** Streams for a playlist id (a film, or an episode). */
    suspend fun streams(id: String): List<AddonStream> {
        val b = id.split(':')
        if (b.getOrNull(2) == "ep") {
            val pid = b.getOrNull(1)?.toLongOrNull() ?: return emptyList()
            val v = db.vod().find(pid, "series", b.getOrNull(3) ?: return emptyList()) ?: return emptyList()
            val stored = episodeAddress(v, b.getOrNull(4)?.toIntOrNull() ?: 1, b.getOrNull(5)?.toIntOrNull() ?: 1) ?: return emptyList()
            return listOfNotNull(stream(v, stored))
        }
        val v = row(id) ?: return emptyList()
        return listOfNotNull(filmAddress(v)?.let { stream(v, it) })
    }

    /**
     * For a playlist id: the add-on id of the same title ("tt…", or "tt…:season:episode"), so add-on streams
     * can be offered next to the playlist's own. Null when it isn't matched.
     */
    suspend fun addonIdFor(id: String): String? {
        val b = id.split(':')
        if (b.getOrNull(2) == "ep") {
            val v = db.vod().find(b.getOrNull(1)?.toLongOrNull() ?: return null, "series", b.getOrNull(3) ?: return null) ?: return null
            return imdb(v)?.let { "$it:${b.getOrNull(4)}:${b.getOrNull(5)}" }
        }
        return row(id)?.let { imdb(it) }
    }

    /**
     * "From your playlist" streams for a title opened elsewhere (an add-on or TMDB): matched by IMDb id or by
     * name and year. [id] is the add-on's id ("tt123", or "tt123:1:2" for an episode).
     */
    suspend fun matches(playlists: List<ProviderEntity>, type: String, id: String, title: String?, year: String?): List<AddonStream> {
        val ids = eligible(playlists).map { it.id }
        if (ids.isEmpty() || (title.isNullOrBlank() && !id.startsWith("tt"))) return emptyList()
        val bits = id.split(':')
        val imdb = bits[0].takeIf { it.startsWith("tt") } ?: "-"
        val norm = title?.let { VodNames.norm(it) }.orEmpty().ifBlank { "-" }
        val y = year?.take(4)?.toIntOrNull()
        val kind = if (type == "series") "series" else "movie"
        val found = db.vod().matches(ids, kind, norm, imdb).filter { v ->
            v.imdb == imdb || y == null || v.year == null || kotlin.math.abs(v.year - y) <= 1
        }
        return found.mapNotNull { v ->
            val stored = if (kind == "series") {
                val s = bits.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                val e = bits.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null
                episodeAddress(v, s, e)
            } else filmAddress(v)
            stored?.let { stream(v, it) }
        }
    }

    private companion object {
        const val OFFSET = 1_000_000_000L
        const val PAGE = 40
        const val MAX_ROWS = 40
    }
}
