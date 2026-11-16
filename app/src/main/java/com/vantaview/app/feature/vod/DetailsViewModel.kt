// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.vod

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vantaview.app.data.addon.AddonRepository
import com.vantaview.app.data.addon.AddonStream
import com.vantaview.app.data.addon.MetaDetail
import com.vantaview.app.data.addon.Video
import com.vantaview.app.data.addon.VodRequest
import com.vantaview.app.data.addon.VodSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A stream list waiting for the user to pick one ("Choose stream"). */
data class StreamChoice(
    val title: String,
    val subtitle: String?,
    val streams: List<AddonStream>,
    val season: Int? = null,
    val episode: Int? = null,
    val video: Video? = null,
)

data class DetailsUi(
    val loading: Boolean = true,
    val meta: MetaDetail? = null,
    val season: Int? = null,
    val finding: String? = null,
    val message: String? = null,
    /** The episode the message is about, so it shows on that episode's row (null: the film or the series). */
    val messageFor: String? = null,
    val choice: StreamChoice? = null,
    /** Not suitable for the Kids profile in use. */
    val blocked: Boolean = false,
    /** Where this profile got to in this film or series. */
    val progress: com.vantaview.app.data.history.HistoryEntry? = null,
    /** "More like this" (TMDB recommendations). */
    val moreLike: List<com.vantaview.app.data.addon.MetaPreview> = emptyList(),
    /** Seasons announced but not out yet: number and first air date ("yyyy-mm-dd", or empty when not set). */
    val comingSeasons: List<Pair<Int, String>> = emptyList(),
    /** Web pages the add-ons offered instead of a stream (label, address), for "Open in browser". */
    val webLinks: List<Pair<String, String>> = emptyList(),
)

@HiltViewModel
class DetailsViewModel @Inject constructor(
    private val repo: AddonRepository,
    private val session: VodSession,
    private val profiles: com.vantaview.app.data.profile.ProfileRepository,
    private val debrid: com.vantaview.app.data.debrid.DebridManager,
    private val history: com.vantaview.app.data.history.WatchHistory,
    private val library: com.vantaview.app.data.library.LibraryStore,
    val browse: com.vantaview.app.data.browse.BrowseRepository,
    val playback: com.vantaview.app.data.PlaybackSettings,
    savedState: SavedStateHandle,
) : ViewModel() {
    val type: String = savedState.get<String>("type") ?: "movie"
    val id: String = savedState.get<String>("id").orEmpty()
    val fallbackName: String = savedState.get<String>("name").orEmpty()
    val fallbackPoster: String? = savedState.get<String>("poster")?.ifBlank { null }
    /** Opened from Continue watching: play this straight away ("movie", or an episode's video id). */
    private val autoplay: String? = savedState.get<String>("play")?.ifBlank { null }

    private val _ui = MutableStateFlow(DetailsUi())
    val ui: StateFlow<DetailsUi> = _ui

    private val _play = Channel<Unit>(Channel.BUFFERED)
    /** Fires when a film or episode is ready in the player session. */
    val play = _play.receiveAsFlow()

    init {
        viewModelScope.launch {
            val meta = runCatching { repo.meta(type, id) }.getOrNull()
            if (profiles.isKids() && meta != null && meta.genres.isNotEmpty() &&
                !com.vantaview.app.data.profile.KidsFilter.hasFamilyGenre(meta.genres)
            ) {
                _ui.update { it.copy(loading = false, blocked = true) }
                return@launch
            }
            val progress = history.get(type, id)
            val seasons = meta?.videos?.map { it.season }?.distinct()?.sorted().orEmpty()
            val firstSeason = progress?.season?.takeIf { it in seasons } ?: seasons.firstOrNull { it > 0 } ?: seasons.firstOrNull()
            _ui.update { it.copy(loading = false, meta = meta, season = firstSeason, progress = progress) }
            // TMDB (when the build has a key): fuller text, artwork, cast, trailer and upcoming seasons.
            launch {
                val d = runCatching { browse.details(type, id) }.getOrNull() ?: return@launch
                if (profiles.isKids() && meta == null && d.genres.isNotEmpty() && !com.vantaview.app.data.profile.KidsFilter.hasFamilyGenre(d.genres)) {
                    _ui.update { it.copy(blocked = true) }
                    return@launch
                }
                val m = _ui.value.meta
                val merged = com.vantaview.app.data.addon.MetaDetail(
                    id = m?.id ?: id, type = m?.type ?: type, name = m?.name?.ifBlank { null } ?: d.name,
                    poster = m?.poster ?: d.poster, background = d.background ?: m?.background, logo = m?.logo,
                    description = d.overview ?: m?.description, year = m?.year ?: d.year, runtime = m?.runtime ?: d.runtime,
                    rating = m?.rating ?: d.rating, genres = m?.genres?.takeIf { it.isNotEmpty() } ?: d.genres,
                    cast = d.cast.takeIf { it.isNotEmpty() } ?: m?.cast.orEmpty(),
                    director = m?.director?.takeIf { it.isNotEmpty() } ?: d.director,
                    videos = m?.videos.orEmpty(), trailer = m?.trailer ?: d.trailer,
                )
                val today = java.time.LocalDate.now().toString()
                val have = merged.videos.map { it.season }.toSet()
                val coming = d.seasons.filter { (n, date, _) -> n > 0 && (date.isBlank() || date > today) && (n !in have || merged.videos.none { v -> v.season == n && released(v) }) }
                    .map { (n, date, _) -> n to date }
                _ui.update { it.copy(meta = merged, comingSeasons = coming) }
            }
            launch {
                val more = runCatching { browse.moreLikeThis(type, id) }.getOrDefault(emptyList())
                    .let { l -> if (profiles.isKids()) emptyList() else l }
                _ui.update { it.copy(moreLike = more) }
            }
            when {
                autoplay == null -> Unit
                autoplay == "movie" || (type != "series" && (type != "other" || (meta?.videos?.size ?: 0) <= 1)) -> play(null, false)
                else -> meta?.videos?.firstOrNull { it.id == autoplay }?.let { play(it, false) }
            }
        }
    }

    private fun released(v: Video): Boolean =
        v.released == null || runCatching { java.time.Instant.parse(v.released) <= java.time.Instant.now() }.getOrDefault(true)

    val title: String get() = _ui.value.meta?.name?.ifBlank { null } ?: fallbackName

    fun selectSeason(s: Int) = _ui.update { it.copy(season = s) }

    /** Called when the page comes back into view (e.g. after the player) to show the latest progress. */
    fun refreshProgress() = _ui.update { it.copy(progress = history.get(type, id)) }

    /** For a series: the episode to resume, or the one after the last finished one. */
    fun continueVideo(): Video? {
        val p = _ui.value.progress ?: return null
        val videos = _ui.value.meta?.videos.orEmpty()
        val i = videos.indexOfFirst { it.id == p.videoId }
        if (i < 0) return null
        return if (p.finished) videos.drop(i + 1).firstOrNull { it.season > 0 } else videos[i]
    }

    fun dismissChoice() = _ui.update { it.copy(choice = null) }

    /** Opens a "More like this" title: TMDB titles are opened by their IMDb id, which add-ons use. */
    fun openSimilar(m: com.vantaview.app.data.addon.MetaPreview, go: (com.vantaview.app.data.addon.MetaPreview) -> Unit) {
        if (!m.id.startsWith("tmdb:")) { go(m); return }
        viewModelScope.launch {
            val tt = runCatching { browse.imdbId(m.id) }.getOrNull()
            if (tt != null) go(m.copy(id = tt)) else _ui.update { it.copy(message = "Your add-ons can't look up ${m.name} yet.", messageFor = null) }
        }
    }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    /** Plays the film ([video] null) or an episode. With [choose], shows the stream list instead of auto-playing. */
    /** Highest season that has released episodes. */
    private fun releasedSeason(): Int {
        val now = java.time.Instant.now()
        return _ui.value.meta?.videos.orEmpty().filter { v ->
            v.season > 0 && (v.released == null || runCatching { java.time.Instant.parse(v.released) <= now }.getOrDefault(true))
        }.maxOfOrNull { it.season } ?: 0
    }

    /** Where to start: the saved position if this exact film or episode was left part-way through. */
    private fun startAt(videoId: String): Long =
        _ui.value.progress?.takeIf { it.videoId == videoId && it.resumable }?.positionMs ?: 0L

    private fun request(streams: List<AddonStream>, index: Int, video: Video?, subtitle: String?, chosen: Boolean): VodRequest {
        val meta = _ui.value.meta
        val vid = video?.id ?: id
        return VodRequest(
            title = title, subtitle = subtitle, streams = streams, startIndex = index,
            season = video?.season?.takeIf { it > 0 }, episode = video?.episode?.takeIf { video.season > 0 },
            chosen = chosen, type = type, metaId = id, videoId = vid,
            poster = meta?.poster ?: fallbackPoster, background = meta?.background,
            episodeTitle = video?.title?.ifBlank { null }, knownSeason = releasedSeason(), startPositionMs = startAt(vid),
        )
    }

    private var findJob: kotlinx.coroutines.Job? = null

    fun play(video: Video?, choose: Boolean) {
        if (_ui.value.blocked) return
        // Pressing another episode (or ⋯) while one is still looking starts over with the new one.
        findJob?.cancel()
        // An "other" title with a single video plays that video (as Stremio does).
        val streamId = video?.id ?: (if (type == "other") _ui.value.meta?.videos?.singleOrNull()?.id else null) ?: id
        val subtitle = video?.let { v ->
            val code = if (v.season > 0) "S${v.season} E${v.episode}" else "Episode ${v.episode}"
            listOf(code, v.title).filter { it.isNotBlank() }.joinToString(" · ")
        }
        _ui.update { it.copy(finding = streamId, message = null, messageFor = video?.id, webLinks = emptyList()) }
        findJob = viewModelScope.launch {
            val hasSource = repo.hasStreamSource(type, streamId)
            val raw = if (hasSource) runCatching { repo.streams(type, streamId, title, _ui.value.meta?.year) }.getOrDefault(emptyList()) else emptyList()
            // Torrent results only stay when a debrid account is connected; cached ones are moved up.
            val streams = runCatching { debrid.annotate(raw, playback.playTorrents) }.getOrDefault(raw.filter { !it.isTorrent })
            val season = video?.season?.takeIf { it > 0 }
            val episode = video?.episode?.takeIf { video.season > 0 }
            when {
                !hasSource || (raw.isEmpty() && !repo.hasAddonStreams(type, streamId)) -> _ui.update {
                    it.copy(finding = null, message = "None of your add-ons supply streams for this. Add a stream add-on on the Sources page.")
                }
                streams.isEmpty() && raw.any { it.isTorrent } -> _ui.update {
                    it.copy(finding = null, message = "Your add-ons only found torrent results. Turn on Play torrents in Settings › Playback, or connect a debrid account on Sources → Debrid.")
                }
                streams.isEmpty() -> {
                    val links = runCatching { repo.externalLinks(type, streamId) }.getOrDefault(emptyList())
                    _ui.update {
                        it.copy(
                            finding = null, webLinks = links.take(6),
                            message = if (links.isEmpty()) "Your add-ons found no playable streams."
                            else "Your add-ons only link to web pages for this, which VantaView can't play itself. You can open them in your web browser.",
                        )
                    }
                }
                choose -> _ui.update { it.copy(finding = null, choice = StreamChoice(title, subtitle, streams, season, episode, video)) }
                else -> {
                    // Settings: streams that fit the connection are tried first (the bigger ones are still there after).
                    val ordered = com.vantaview.app.data.ConnectionTuning.preferFitting(streams, playback.maxQuality) { it.quality }
                    session.current = request(ordered, 0, video, subtitle, chosen = false)
                    _ui.update { it.copy(finding = null) }
                    _play.send(Unit)
                }
            }
        }
    }

    fun pick(index: Int) {
        val c = _ui.value.choice ?: return
        session.current = request(c.streams, index, c.video, c.subtitle, chosen = true)
        _ui.update { it.copy(choice = null) }
        viewModelScope.launch { _play.send(Unit) }
    }

    // ---- Watchlist and Watched ----

    init { if (type == "series" && id.isNotBlank()) library.markOpened(id) }

    private val _saved = kotlinx.coroutines.flow.MutableStateFlow(library.inWatchlist(type, id) to library.isWatched(type, id))
    /** (on the Watchlist, watched) */
    val saved: kotlinx.coroutines.flow.StateFlow<Pair<Boolean, Boolean>> = _saved

    private fun title() = com.vantaview.app.data.library.SavedTitle(
        type, id, _ui.value.meta?.name ?: fallbackName, _ui.value.meta?.poster ?: fallbackPoster, _ui.value.meta?.year, 0,
    )

    fun toggleWatchlist() {
        val on = !_saved.value.first
        library.setWatchlist(title(), on)
        _saved.value = library.inWatchlist(type, id) to library.isWatched(type, id)
    }

    fun toggleWatched() {
        val on = !_saved.value.second
        library.setWatched(title(), on)
        _saved.value = library.inWatchlist(type, id) to library.isWatched(type, id)
    }

    // ---- Watched episodes ----

    private val _watchedEps = kotlinx.coroutines.flow.MutableStateFlow(library.watchedEpisodes(id))
    /** Ids of this series' episodes marked as watched. */
    val watchedEps: kotlinx.coroutines.flow.StateFlow<Set<String>> = _watchedEps

    fun isEpisodeWatched(v: Video) = v.id in _watchedEps.value ||
        _ui.value.progress?.let { it.videoId == v.id && it.finished } == true

    fun seasonEpisodes(season: Int) = _ui.value.meta?.videos.orEmpty().filter { it.season == season }

    fun isSeasonWatched(season: Int) = seasonEpisodes(season).let { eps -> eps.isNotEmpty() && eps.all(::isEpisodeWatched) }

    fun setEpisodeWatched(v: Video, on: Boolean) {
        library.setEpisodesWatched(id, listOf(v.id), on)
        _watchedEps.value = library.watchedEpisodes(id)
    }

    fun setSeasonWatched(season: Int, on: Boolean) {
        library.setEpisodesWatched(id, seasonEpisodes(season).map { it.id }, on)
        _watchedEps.value = library.watchedEpisodes(id)
    }
}
