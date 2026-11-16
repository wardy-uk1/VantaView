// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.vantaview.app.BuildConfig
import com.vantaview.app.data.addon.AddonStream
import com.vantaview.app.data.addon.VodSession
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

data class VodUi(
    val title: String = "",
    val subtitle: String? = null,
    val streams: List<AddonStream> = emptyList(),
    val index: Int = 0,
    val notice: String? = null,
    val failed: String? = null,
    /** "Getting link…" or download progress on a debrid service, while a torrent is being prepared. */
    val preparing: String? = null,
    /** Playback speed (1 = normal). */
    val speed: Float = 1f,
    /** True for an episode of a series: the player offers Episodes. */
    val isSeries: Boolean = false,
    /** Showing "Skip intro" (the intro is playing and its end is known). */
    val skipIntro: Boolean = false,
    /** The next episode, with seconds left before it starts, during the end credits. */
    val nextUp: com.vantaview.app.data.addon.Video? = null,
    val nextIn: Int = 0,
)

/** Plays a film or episode from add-on streams, moving to the next stream if one fails. */
@OptIn(UnstableApi::class)
@HiltViewModel
class VodPlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val settings: com.vantaview.app.data.PlaybackSettings,
    aiSubs: com.vantaview.app.data.aisubs.AiSubtitles,
    private val library: com.vantaview.app.data.library.LibraryStore,
    private val okHttp: OkHttpClient,
    private val debrid: com.vantaview.app.data.debrid.DebridManager,
    private val history: com.vantaview.app.data.history.WatchHistory,
    private val addons: com.vantaview.app.data.addon.AddonRepository,
    private val session: VodSession,
    private val trakt: com.vantaview.app.data.trakt.TraktSync,
    private val skips: com.vantaview.app.data.player.SkipSegments,
    private val torrents: com.vantaview.app.data.torrent.TorrentEngine,
) : ViewModel() {
    private val _ui = MutableStateFlow(VodUi())
    val ui: StateFlow<VodUi> = _ui

    /** AI subtitles (beta), when turned on in Settings and the speech model is downloaded. */
    val ai: com.vantaview.app.data.aisubs.AiSubtitleEngine? =
        if (aiSubs.active) com.vantaview.app.data.aisubs.AiSubtitleEngine(aiSubs) else null

    val player: ExoPlayer = com.vantaview.app.data.player.PlayerTuning.builder(context, ai?.renderers(context), live = false)
        .setLoadControl(settings.loadControl())
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .build()

    private var startedOk = false
    private var stalls = 0
    private var lastSeekAt = 0L
    private var segments: com.vantaview.app.data.player.Segments? = null
    private var segmentsFor: String? = null
    /** The episode the viewer turned the countdown off for. */
    private var nextDismissedFor: String? = null
    private var season: Int? = null
    private var episode: Int? = null
    private var resolveJob: kotlinx.coroutines.Job? = null
    private var request: com.vantaview.app.data.addon.VodRequest? = null

    private val listener = object : Player.Listener {
        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) lastSeekAt = System.currentTimeMillis()
        }

        override fun onPlaybackStateChanged(state: Int) {
            // A stall: buffering again while playing, not just after a seek.
            if (state == Player.STATE_BUFFERING && startedOk && player.playWhenReady && System.currentTimeMillis() - lastSeekAt > 3_000) {
                stalls++
                settings.reportStall(stalls)
            }
            if (state == Player.STATE_ENDED) {
                saveProgress(); scrobble("stop", 100f)
                if (_ui.value.nextUp != null) playNext()
            }
            if (state == Player.STATE_READY) {
                startedOk = true
                _ui.update { it.copy(failed = null) }
            }
        }

        // Trakt: "watching now" while playing, and keeps the place when paused.
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) scrobble("start") else if (player.playbackState != Player.STATE_ENDED) scrobble("pause")
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            _tracksVersion.value++
            if (!pickedTracks) TrackPrefs.onTracks(player, settings, tracks)
        }

        override fun onPlayerError(error: PlaybackException) {
            val s = _ui.value
            val next = s.index + 1
            // Before a stream has ever started, quietly move on to the next one.
            if (!startedOk && next < s.streams.size) {
                _ui.update { it.copy(notice = "That stream didn't work, trying the next one (${next + 1} of ${s.streams.size})") }
                playIndex(next, 0, explicit = false)
            } else {
                _ui.update { it.copy(failed = "Playback problem (${error.errorCodeName}). Try another stream.") }
            }
        }
    }

    // ---- Audio and subtitles (Settings › Subtitles and audio, and the picker in the player) ----
    private var pickedTracks = false
    private val _tracksVersion = MutableStateFlow(0)
    /** Changes whenever the stream's tracks change, so the pickers re-read them. */
    val tracksVersion: StateFlow<Int> = _tracksVersion
    fun trackChoices(type: Int) = TrackPrefs.choices(player, type)
    fun pickTrack(type: Int, c: TrackChoice) { pickedTracks = true; TrackPrefs.pick(player, type, c) }

    /** How the picture fills the screen (Fit, Fill or Zoom), shared with live TV. */
    private val _size = kotlinx.coroutines.flow.MutableStateFlow(settings.size)
    val size: kotlinx.coroutines.flow.StateFlow<com.vantaview.app.data.StreamSize> = _size
    fun cycleSize() { _size.value = _size.value.next(); settings.size = _size.value }

    private var subsJob: kotlinx.coroutines.Deferred<List<com.vantaview.app.data.addon.AddonSubtitle>>? = null

    init {
        player.addListener(listener)
        TrackPrefs.apply(player, settings)
        ai?.start()
        val req = session.current
        if (req == null || req.streams.isEmpty()) {
            _ui.value = VodUi(failed = "Nothing to play")
        } else {
            _ui.value = VodUi(title = req.title, subtitle = req.subtitle, streams = req.streams, index = req.startIndex, isSeries = req.type == "series")
            season = req.season
            episode = req.episode
            request = req
            // Subtitles from subtitle add-ons, fetched alongside the stream (for the languages in Settings).
            subsJob = viewModelScope.async {
                val id = if (req.type == "series" && req.videoId.isNotBlank()) req.videoId else req.metaId
                if (id.isBlank()) emptyList() else runCatching { pickSubtitles(addons.subtitles(req.type, id)) }.getOrDefault(emptyList())
            }
            playIndex(req.startIndex.coerceIn(0, req.streams.lastIndex), req.startPositionMs, explicit = req.chosen)
            // Save progress every 10 seconds while playing.
            viewModelScope.launch {
                while (true) {
                    kotlinx.coroutines.delay(10_000)
                    if (player.isPlaying) { saveProgress(); if (stalls == 0) settings.reportSmooth(10_000) }
                }
            }
            // Skip intro and the next-episode countdown (after the constructor has finished, hence inside the loop).
            viewModelScope.launch {
                kotlinx.coroutines.delay(300)
                if (req.type == "series") loadEpisodes()
                while (true) {
                    kotlinx.coroutines.delay(500)
                    tickSkips()
                }
            }
        }
    }

    /** Switches stream, keeping the current position (used by "Streams" in the player). */
    fun switchTo(index: Int) {
        val pos = player.currentPosition.takeIf { it > 0 && player.duration != C.TIME_UNSET } ?: 0
        startedOk = false
        _ui.update { it.copy(notice = null, failed = null) }
        playIndex(index, pos, explicit = true)
    }

    fun clearNotice() = _ui.update { it.copy(notice = null) }

    // ---- Speed ----

    fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
        _ui.update { it.copy(speed = speed, notice = if (speed == 1f) "Normal speed" else "Speed ${speed}×") }
    }

    // ---- Episodes (series only) ----

    /** The series' episodes, loaded when Episodes is opened. */
    private val _episodes = MutableStateFlow<List<com.vantaview.app.data.addon.Video>>(emptyList())
    val episodes: StateFlow<List<com.vantaview.app.data.addon.Video>> = _episodes
    val currentVideoId: String get() = request?.videoId.orEmpty()

    fun loadEpisodes() {
        val r = request ?: return
        if (_episodes.value.isNotEmpty()) return
        viewModelScope.launch {
            val meta = runCatching { addons.meta("series", r.metaId) }.getOrNull()
            _episodes.value = meta?.videos.orEmpty().filter { it.season > 0 }.sortedWith(compareBy({ it.season }, { it.episode }))
        }
    }

    /** Plays another episode: finds its streams, then starts the best one. */
    fun playEpisode(v: com.vantaview.app.data.addon.Video) {
        val r = request ?: return
        saveProgress()
        scrobble("stop")
        _ui.update { it.copy(preparing = "Finding streams for S${v.season} E${v.episode}…", failed = null) }
        resolveJob?.cancel()
        resolveJob = viewModelScope.launch {
            val raw = runCatching { addons.streams("series", v.id, r.title) }.getOrDefault(emptyList())
            val streams = runCatching { debrid.annotate(raw, settings.playTorrents) }.getOrDefault(raw.filter { !it.isTorrent })
            if (streams.isEmpty()) {
                _ui.update { it.copy(preparing = null, failed = "No playable streams for S${v.season} E${v.episode}.") }
                return@launch
            }
            val sub = listOf("S${v.season} E${v.episode}", v.title).filter { it.isNotBlank() }.joinToString(" · ")
            val next = r.copy(
                subtitle = sub, streams = streams, startIndex = 0, season = v.season, episode = v.episode,
                videoId = v.id, episodeTitle = v.title.ifBlank { null }, startPositionMs = 0, chosen = false,
            )
            request = next
            session.current = next
            season = v.season
            episode = v.episode
            startedOk = false
            _ui.update { it.copy(subtitle = sub, streams = streams, index = 0, preparing = null, failed = null) }
            playIndex(0, 0, explicit = false)
        }
    }

    fun pause() { player.playWhenReady = false; saveProgress() }

    // ---- Skip intro and Next episode ----

    private fun nextEpisodeOf(id: String): com.vantaview.app.data.addon.Video? {
        val list = _episodes.value
        val i = list.indexOfFirst { it.id == id }
        return if (i >= 0) list.getOrNull(i + 1)?.takeIf { v ->
            v.released == null || runCatching { java.time.Instant.parse(v.released) <= java.time.Instant.now() }.getOrDefault(true)
        } else null
    }

    private var countdownFrom = 0L

    private fun tickSkips() {
        val r = request ?: return
        val dur = player.duration
        val pos = player.currentPosition
        if (dur == C.TIME_UNSET || dur <= 0 || !startedOk) return
        // Look the episode up once its length is known (SkipDB matches by length).
        if (segmentsFor != r.videoId && r.metaId.startsWith("tt")) {
            segmentsFor = r.videoId
            segments = null
            viewModelScope.launch { segments = skips.lookup(r.metaId, r.season, r.episode, dur) }
        }
        val seg = segments
        val showSkip = settings.skipIntro && seg?.hasIntro == true && pos >= (seg.introStart ?: 0L) && pos < seg.introEnd!! - 1_000
        // The countdown: from the end credits (when known), otherwise the last 30 seconds.
        val next = if (r.type == "series" && settings.nextEpisode && nextDismissedFor != r.videoId) nextEpisodeOf(r.videoId) else null
        val creditsAt = seg?.outroStart?.takeIf { it in (dur / 2)..dur } ?: (dur - 30_000)
        var nextIn = 0
        if (next != null && pos >= creditsAt && _ui.value.preparing == null) {
            if (countdownFrom == 0L) countdownFrom = System.currentTimeMillis()
            nextIn = (10 - ((System.currentTimeMillis() - countdownFrom) / 1000)).toInt().coerceAtLeast(0)
            if (nextIn == 0 && player.isPlaying) { playNext(); return }
        } else countdownFrom = 0L
        val shown = if (next != null && pos >= creditsAt) next else null
        if (_ui.value.skipIntro != showSkip || _ui.value.nextUp != shown || _ui.value.nextIn != nextIn) {
            _ui.update { it.copy(skipIntro = showSkip, nextUp = shown, nextIn = nextIn) }
        }
    }

    fun skipIntro() { segments?.introEnd?.let { player.seekTo(it) } }

    fun playNext() {
        val n = _ui.value.nextUp ?: request?.let { nextEpisodeOf(it.videoId) } ?: return
        countdownFrom = 0L
        _ui.update { it.copy(nextUp = null, nextIn = 0) }
        playEpisode(n)
    }

    fun cancelNext() {
        nextDismissedFor = request?.videoId
        countdownFrom = 0L
        _ui.update { it.copy(nextUp = null, nextIn = 0) }
    }

    /** Tells Trakt (when linked) what's playing and how far through it is. */
    private fun scrobble(action: String, percent: Float? = null) {
        val r = request ?: return
        val dur = player.duration
        val p = percent ?: if (dur > 0 && dur != C.TIME_UNSET) player.currentPosition * 100f / dur else return
        trakt.scrobble(action, r.type, r.metaId, r.videoId, r.title, p)
    }

    /** Records where this profile got to, for Continue watching and resume. */
    private fun saveProgress() {
        val r = request ?: return
        if (r.metaId.isBlank()) return
        val dur = player.duration
        val pos = player.currentPosition
        if (dur == C.TIME_UNSET || dur <= 0 || pos <= 0) return
        history.record(
            com.vantaview.app.data.history.HistoryEntry(
                type = r.type, metaId = r.metaId, videoId = r.videoId, title = r.title,
                poster = r.poster, background = r.background, season = r.season, episode = r.episode,
                episodeTitle = r.episodeTitle, positionMs = pos, durationMs = dur,
                updatedAt = System.currentTimeMillis(), knownSeason = r.knownSeason,
            )
        )
        // A film watched to the end counts as Watched (Library).
        if (r.type == "movie" && pos >= dur * 0.92 && !library.isWatched(r.type, r.metaId)) {
            library.setWatched(com.vantaview.app.data.library.SavedTitle(r.type, r.metaId, r.title, r.poster, null, 0), true)
        }
        // An episode watched to the end is marked watched too.
        if (r.type == "series" && r.videoId.isNotBlank() && pos >= dur * 0.92 && r.videoId !in library.watchedEpisodes(r.metaId)) {
            library.setEpisodesWatched(r.metaId, listOf(r.videoId), true)
        }
    }

    /**
     * Plays stream [index]. A torrent is first turned into a link through the debrid accounts; when none has
     * it ready, VantaView plays the torrent itself if that's on (Settings › Playback). With that off, an
     * [explicit] pick is downloaded on the debrid service, otherwise the next stream is tried.
     */
    private fun playIndex(index: Int, positionMs: Long, explicit: Boolean) {
        val s = _ui.value.streams.getOrNull(index) ?: return
        resolveJob?.cancel()
        _ui.update { it.copy(index = index, preparing = null) }
        // A torrent that was playing stops sharing when another stream takes over.
        torrents.stopAddress(currentUrl)
        if (!s.isTorrent) { playUrl(s.url, s.headers, positionMs); return }

        player.stop()
        resolveJob = viewModelScope.launch {
            val own = settings.playTorrents
            if (debrid.hasAccounts) {
                _ui.update { it.copy(preparing = "Getting the link from your debrid service…") }
                // Debrid first. When it doesn't have the file ready, VantaView plays the torrent itself (if
                // that's on) rather than waiting for the service to download it.
                val r = debrid.resolve(s, season, episode, allowDownload = explicit && !own) { svc, pct ->
                    _ui.update { it.copy(preparing = com.vantaview.app.ui.tr("Not cached yet. Downloading on {0}", svc.label) + (pct?.let { p -> ": $p%" } ?: "…") + "\n" + com.vantaview.app.ui.tr("It will play when ready.")) }
                }
                if (r is com.vantaview.app.data.debrid.Resolve.Ready) {
                    _ui.update { it.copy(preparing = null) }
                    playUrl(r.url, emptyMap(), positionMs)
                    return@launch
                }
                if (!own) {
                    val reason = when (r) {
                        is com.vantaview.app.data.debrid.Resolve.Failed -> r.message
                        else -> "Not cached on your debrid service"
                    }
                    failOrNext(index, explicit, reason, "$reason. Choose another stream, or pick this one to download it first.")
                    return@launch
                }
            }
            if (!own) { failOrNext(index, explicit, "Torrent", "Your add-ons only found torrent results. Connect a debrid account on Sources → Debrid to play them."); return@launch }
            _ui.update { it.copy(preparing = "Finding people sharing this torrent…") }
            val url = runCatching {
                torrents.prepare(s, season, episode) { line -> _ui.update { it.copy(preparing = line) } }
            }.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                failOrNext(index, explicit, e.message ?: "The torrent couldn't be started.", e.message ?: "The torrent couldn't be started.")
                return@launch
            }
            _ui.update { it.copy(preparing = null) }
            playUrl(url, emptyMap(), positionMs)
        }
    }

    /** After [index] couldn't play: the next stream when this one was picked automatically, otherwise an error. */
    private fun failOrNext(index: Int, explicit: Boolean, reason: String, failed: String) {
        val next = index + 1
        if (!explicit && next < _ui.value.streams.size) {
            _ui.update { it.copy(preparing = null, notice = "$reason. Trying the next stream (${next + 1} of ${_ui.value.streams.size})") }
            playIndex(next, 0, explicit = false)
        } else {
            _ui.update { it.copy(preparing = null, failed = failed) }
        }
    }

    /**
     * The add-on subtitles to offer: the chosen subtitle language (or the device's), Croatian, Serbian and
     * Bosnian standing in for each other, two of each at most, and one English as a spare.
     */
    private fun pickSubtitles(all: List<com.vantaview.app.data.addon.AddonSubtitle>): List<com.vantaview.app.data.addon.AddonSubtitle> {
        val first = settings.subtitleLanguage.ifEmpty { java.util.Locale.getDefault().language }
        val wanted = listOf(first) + com.vantaview.app.data.relatedLanguages(first)
        val mine = wanted.flatMap { l -> all.filter { it.lang == l }.take(2) }
        val english = if ("en" in wanted) emptyList() else all.filter { it.lang == "en" }.take(1)
        return mine + english
    }

    /** The address playing now and its headers (stream info, and opening in another player). */
    var currentUrl: String? = null; private set
    var currentHeaders: Map<String, String> = emptyMap(); private set

    private fun playUrl(url: String, headers: Map<String, String>, positionMs: Long) {
        currentUrl = url
        currentHeaders = headers
        viewModelScope.launch {
            // Add-on subtitles join the stream when they've arrived (waiting a few seconds at most).
            val subs = kotlinx.coroutines.withTimeoutOrNull(4_000) { subsJob?.await() }.orEmpty()
            if (currentUrl == url) prepareUrl(url, headers, positionMs, subs)
        }
    }

    private fun prepareUrl(url: String, headers: Map<String, String>, positionMs: Long, subs: List<com.vantaview.app.data.addon.AddonSubtitle>) {
        val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: "VantaView/${BuildConfig.VERSION_NAME}"
        // A torrent may pause while pieces arrive, so its local address gets a long wait before giving up.
        val client = if (com.vantaview.app.data.torrent.TorrentEngine.isLocal(url))
            okHttp.newBuilder().readTimeout(3, java.util.concurrent.TimeUnit.MINUTES).build() else okHttp
        val http = OkHttpDataSource.Factory(client)
            .setUserAgent(ua)
            .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", true) })
        val item = MediaItem.Builder().setUri(url).apply {
            if (url.substringBefore('?').endsWith(".m3u8", true)) setMimeType(MimeTypes.APPLICATION_M3U8)
            if (subs.isNotEmpty()) setSubtitleConfigurations(subs.map { s ->
                val path = s.url.substringBefore('?').lowercase()
                MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(s.url))
                    .setMimeType(
                        when {
                            path.endsWith(".vtt") -> MimeTypes.TEXT_VTT
                            path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
                            else -> MimeTypes.APPLICATION_SUBRIP
                        }
                    )
                    .setLanguage(s.lang)
                    .setLabel(listOfNotNull(s.lang?.let { l -> java.util.Locale(l).getDisplayLanguage(com.vantaview.app.ui.I18n.locale) }, s.addonName).joinToString(" · "))
                    .build()
            })
        }.build()
        player.setMediaSource(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http), com.vantaview.app.data.player.PlayerTuning.extractors()).createMediaSource(item))
        player.prepare()
        if (positionMs > 0) player.seekTo(positionMs)
        player.playWhenReady = true
    }

    override fun onCleared() {
        saveProgress()
        if (player.playbackState != Player.STATE_ENDED) scrobble("stop")
        resolveJob?.cancel()
        // The torrent stops sharing and its file is deleted as soon as the player closes.
        torrents.stopAddress(currentUrl)
        player.removeListener(listener)
        player.release()
        ai?.release()
    }
}
