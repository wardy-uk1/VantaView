// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.vantaview.app.data.ChannelNames
import com.vantaview.app.data.db.ChannelEntity
import com.vantaview.app.data.db.VantaDb
import com.vantaview.app.data.repo.PlayTarget
import com.vantaview.app.data.repo.ProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

enum class PlayStatus { LOADING, PLAYING, RECONNECTING, FAILED }

data class PlayerUi(
    val title: String = "",
    val number: Int = 0,
    val status: PlayStatus = PlayStatus.LOADING,
    val attempt: Int = 0,
    val message: String? = null,
    val hasSiblings: Boolean = false,
    /** A channel that only carries something while an event is on. */
    val eventOnly: Boolean = false,
    /** What's on now and next, from the TV guide (null when the channel has no guide). */
    val now: com.vantaview.app.data.db.ProgrammeEntity? = null,
    val next: com.vantaview.app.data.db.ProgrammeEntity? = null,
    /** Pause and rewind: null while simply watching live. */
    val shift: ShiftUi? = null,
)

/** Paused or behind live: how far behind, and where the rewound picture comes from. */
data class ShiftUi(val paused: Boolean, val behindSec: Int, val fromArchive: Boolean, val note: String? = null)

@OptIn(UnstableApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: VantaDb,
    private val repo: ProviderRepository,
    private val okHttp: OkHttpClient,
    private val profiles: com.vantaview.app.data.profile.ProfileRepository,
    private val session: com.vantaview.app.data.profile.ProfileSession,
    private val guide: com.vantaview.app.data.guide.GuideRepository,
    val settings: com.vantaview.app.data.PlaybackSettings,
    aiSubs: com.vantaview.app.data.aisubs.AiSubtitles,
    private val parental: com.vantaview.app.data.profile.ParentalControl,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val channelId: Long = savedState.get<Long>("channelId") ?: 0L

    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui

    // Buffer size from Settings › Playback (Medium unless changed).
    /** AI subtitles (beta), when turned on in Settings and the speech model is downloaded. */
    val ai: com.vantaview.app.data.aisubs.AiSubtitleEngine? =
        if (aiSubs.active) com.vantaview.app.data.aisubs.AiSubtitleEngine(aiSubs) else null

    val player: ExoPlayer = (ai?.let { ExoPlayer.Builder(context, it.renderers(context)) } ?: ExoPlayer.Builder(context))
        .setLoadControl(settings.loadControl())
        .build()

    /** How the video fills the screen: Fit, Fill or Zoom. */
    private val _size = MutableStateFlow(settings.size)
    val size: StateFlow<com.vantaview.app.data.StreamSize> = _size
    fun cycleSize() { _size.value = _size.value.next(); settings.size = _size.value }

    private var siblings: List<ChannelEntity> = emptyList()
    private var index = 0

    /** The channels you can switch between (the current category), shown under the video in portrait. */
    private val _list = MutableStateFlow<List<ChannelEntity>>(emptyList())
    val list: StateFlow<List<ChannelEntity>> = _list
    private val _index = MutableStateFlow(0)
    val current: StateFlow<Int> = _index
    private var eventTimer: Job? = null
    private var guideJob: Job? = null

    /** Addresses to try for the current channel: (url, treat as HLS). */
    private var candidates: List<Pair<String, Boolean>> = emptyList()
    private var candidate = 0
    private var working: Int? = null
    private var headers: Map<String, String> = emptyMap()
    private var attempts = 0
    private var watchdog: Job? = null
    private var retry: Job? = null
    private var loadJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            watchdog?.cancel()
            when (state) {
                Player.STATE_READY -> {
                    working = candidate
                    attempts = 0
                    _ui.update { it.copy(status = PlayStatus.PLAYING, message = null, attempt = 0) }
                }
                Player.STATE_BUFFERING -> {
                    if (working != null && player.playWhenReady && recorder == null && archiveStart == null) {
                        liveStalls++
                        settings.reportStall(liveStalls)
                    }
                    // A live stream stuck buffering for 12 seconds is treated as dropped.
                    watchdog = viewModelScope.launch {
                        delay(12_000)
                        if (player.playbackState == Player.STATE_BUFFERING) onFailure("The stream stopped responding")
                    }
                }
                Player.STATE_ENDED -> onFailure("The stream ended")
                else -> Unit
            }
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            _tracksVersion.value++
            if (!pickedTracks) TrackPrefs.onTracks(player, settings, tracks)
        }

        override fun onPlayerError(error: PlaybackException) {
            watchdog?.cancel()
            onFailure(describe(error))
        }
    }

    // ---- Audio and subtitles (Settings › Subtitles and audio, and the picker in the player) ----
    private var pickedTracks = false
    private val _tracksVersion = MutableStateFlow(0)
    /** Changes whenever the stream's tracks change, so the pickers re-read them. */
    val tracksVersion: StateFlow<Int> = _tracksVersion
    fun trackChoices(type: Int) = TrackPrefs.choices(player, type)
    fun pickTrack(type: Int, c: TrackChoice) { pickedTracks = true; TrackPrefs.pick(player, type, c) }

    init {
        player.addListener(listener)
        TrackPrefs.apply(player, settings)
        ai?.start()
        viewModelScope.launch {
            val channel = db.content().channel(channelId)
            if (channel == null) {
                _ui.update { it.copy(status = PlayStatus.FAILED, message = "That channel is no longer in the playlist") }
                return@launch
            }
            // Provider divider rows ("◆◆◆ CINEMA ◆◆◆") are headings, not channels to switch to.
            // The list it was opened from (Favourites, say), in that order; otherwise its own category.
            val from = ZapList.take(channelId)
            siblings = if (from != null) {
                val byId = db.content().channelsById(from).associateBy { it.id }
                from.mapNotNull { byId[it] }
            } else {
                db.content().siblings(channel.providerId, channel.categoryRemoteId)
            }.filter { it.id == channel.id || !ChannelNames.isDivider(it.name) }
            val profile = profiles.current()
            // A list from Favourites can mix playlists, so each playlist's own categories are checked.
            val providers = siblings.map { it.providerId }.distinct()
            if (profile?.isKids == true) {
                val kidsCats = db.profiles().kidsCategories(profile.id)
                val allowed = HashSet<Long>()
                providers.forEach { pid ->
                    val allow = kidsCats.filter { it.providerId == pid }.map { it.categoryRemoteId }.toSet()
                    val adultCats = db.content().categories(pid)
                        .filter { com.vantaview.app.data.profile.KidsFilter.looksAdult(it.name) }.map { it.remoteId }.toSet()
                    siblings.filter { it.providerId == pid && com.vantaview.app.data.profile.KidsFilter.channelAllowed(it, adultCats, allow) }
                        .forEach { allowed += it.id }
                }
                siblings = siblings.filter { it.id in allowed }
            } else if (parental.hiding) {
                // Settings › Parental control: don't zap into hidden adult channels.
                val hidden = HashSet<Long>()
                providers.forEach { pid ->
                    val ids = parental.adultIds(db.content().categories(pid))
                    siblings.filter { it.providerId == pid && parental.hidden(it, ids) }.forEach { hidden += it.id }
                }
                siblings = siblings.filter { it.id == channel.id || it.id !in hidden }
            }
            index = siblings.indexOfFirst { it.id == channel.id }.coerceAtLeast(0)
            if (siblings.isEmpty()) siblings = listOf(channel)
            _list.value = siblings
            start(siblings[index])
        }
    }

    private var previousChannel: ChannelEntity? = null
    val hasLastChannel: Boolean get() = previousChannel != null

    /** Back to the channel watched before this one (the remote's Last button). */
    fun lastChannel() {
        val prev = previousChannel ?: return
        val i = siblings.indexOfFirst { it.id == prev.id }
        if (i >= 0) index = i
        start(prev)
    }

    /** The address playing now and its headers (stream info, and opening in another player). */
    val currentUrl: String? get() = candidates.getOrNull(working ?: candidate)?.first
    val currentHeaders: Map<String, String> get() = headers

    /** Channel up (+1) or down (-1) within the current category. */
    fun step(delta: Int) {
        if (siblings.size < 2) return
        index = ((index + delta) % siblings.size + siblings.size) % siblings.size
        start(siblings[index])
    }

    /** Plays the channel at [i] in the list shown under the video. */
    /** A number typed on the remote: jump to that channel when it's in this list. */
    fun playNumber(n: Int): Boolean {
        val i = siblings.indexOfFirst { it.number == n }
        if (i < 0) return false
        if (i != index) { index = i; start(siblings[i]) }
        return true
    }

    fun playAt(i: Int) {
        if (i !in siblings.indices || i == index) return
        index = i
        start(siblings[index])
    }

    fun retryNow() {
        if (candidates.isEmpty()) return
        attempts = 0
        working = null
        candidate = 0
        _ui.update { it.copy(status = PlayStatus.LOADING, message = null) }
        eventTimer?.cancel()
        if (_ui.value.eventOnly) eventTimer = viewModelScope.launch {
            delay(10_000)
            if (_ui.value.status != PlayStatus.PLAYING) giveUpOnEvent()
        }
        prepareCurrent()
    }

    fun pause() { player.playWhenReady = false }

    fun resume() {
        if (candidates.isEmpty()) return
        stopShift()
        // Live TV: jump back to the live edge instead of resuming old buffered video.
        prepareCurrent()
    }

    private fun start(channel: ChannelEntity) {
        // A new channel: forget the last channel's picked tracks and use the Settings choices again.
        pickedTracks = false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverrides().build()
        TrackPrefs.apply(player, settings)
        session.setLastChannel(channel.id)
        _index.value = index
        watchdog?.cancel(); retry?.cancel(); loadJob?.cancel(); eventTimer?.cancel()
        stopShift()
        currentChannel?.takeIf { it.id != channel.id }?.let { previousChannel = it }
        currentChannel = channel
        player.stop()
        attempts = 0
        working = null
        candidate = 0
        candidates = emptyList()
        val event = ChannelNames.isEventOnly(channel.name)
        _ui.value = PlayerUi(
            title = ChannelNames.display(channel.name), number = channel.number, status = PlayStatus.LOADING,
            hasSiblings = siblings.size > 1, eventOnly = event,
        )
        // Now and next from the TV guide, refreshed every minute while watching.
        guideJob?.cancel()
        guideJob = viewModelScope.launch {
            val key = channel.epgChannelId ?: return@launch
            while (true) {
                val t = System.currentTimeMillis()
                val list = runCatching { guide.nowNext(channel.providerId, key, t) }.getOrDefault(emptyList())
                val now = list.firstOrNull()?.takeIf { it.start <= t }
                val next = if (now != null) list.getOrNull(1) else list.firstOrNull()
                _ui.update { it.copy(now = now, next = next) }
                delay(60_000)
            }
        }
        // Event channels are often empty: after 10 seconds without a picture, say so instead of spinning.
        if (event) eventTimer = viewModelScope.launch {
            delay(10_000)
            if (_ui.value.status != PlayStatus.PLAYING) giveUpOnEvent()
        }
        loadJob = viewModelScope.launch {
            val target: PlayTarget = try {
                repo.resolveStream(channel)
            } catch (e: Exception) {
                _ui.update { it.copy(status = PlayStatus.FAILED, message = e.message ?: "Could not open the channel") }
                return@launch
            }
            headers = target.headers
            val list = ArrayList<Pair<String, Boolean>>()
            val hls = looksHls(target.url)
            list += target.url to hls
            if (!hls) list += target.url to true
            target.altUrl?.let { list += it to looksHls(it) }
            candidates = list
            prepareCurrent()
        }
    }

    // ---- Pause, rewind and Go live ----

    private var currentChannel: ChannelEntity? = null
    private var recorder: com.vantaview.app.data.player.LiveRecorder? = null
    /** Wall-clock time at the start of the catch-up stream being played, while rewound past the recording. */
    private var archiveStart: Long? = null
    private var shiftTicker: Job? = null
    private var liveStalls = 0

    private fun hlsNow() = candidates.getOrNull(working ?: candidate)?.second == true

    /** How much to keep on the device: 30 minutes, or 10 on small devices or when space is short. */
    private fun windowSec(): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val free = context.cacheDir.usableSpace
        return if (am.isLowRamDevice || free < 3L * 1024 * 1024 * 1024) 600 else 1800
    }

    /** OK on a remote, the Pause button, or Play/Pause. */
    fun togglePause() {
        when {
            recorder != null || archiveStart != null || hlsNow() -> {
                player.playWhenReady = !player.playWhenReady
                startShiftTicker()
            }
            _ui.value.status == PlayStatus.PLAYING -> startRecording(pauseAtStart = true)
        }
    }

    /** Back or forward by [seconds] (negative = rewind). */
    fun jump(seconds: Int) {
        val ch = currentChannel ?: return
        val pos = player.currentPosition
        val dur = player.duration.takeIf { it > 0 && it != androidx.media3.common.C.TIME_UNSET } ?: 0L
        val target = pos + seconds * 1000L
        when {
            archiveStart != null ->
                if (seconds > 0 && dur > 0 && target >= dur - 5_000) goLive() else player.seekTo(target.coerceAtLeast(0))
            recorder != null || hlsNow() -> when {
                target < 0 && repo.canTimeshift(ch) -> playArchiveFrom(wallAt(pos) + seconds * 1000L)
                seconds > 0 && dur > 0 && target >= dur - 8_000 -> goLive()
                else -> player.seekTo(target.coerceAtLeast(0))
            }
            seconds < 0 && repo.canTimeshift(ch) -> playArchiveFrom(System.currentTimeMillis() + seconds * 1000L)
            seconds < 0 -> {
                // Nothing recorded yet: start now, so you can rewind from here on.
                startRecording(pauseAtStart = false)
                _ui.update { it.copy(shift = ShiftUi(false, 0, false, "Recording from now, so you can rewind from here")) }
            }
        }
    }

    /** Back to the live picture, dropping anything recorded. */
    fun goLive() {
        stopShift()
        _ui.update { it.copy(shift = null) }
        prepareCurrent()
    }

    /** Wall-clock time shown at player position [pos] of a live (recorded or HLS) stream. */
    private fun wallAt(pos: Long): Long {
        val dur = player.duration.takeIf { it > 0 && it != androidx.media3.common.C.TIME_UNSET } ?: return System.currentTimeMillis()
        return System.currentTimeMillis() - (dur - pos)
    }

    private fun startRecording(pauseAtStart: Boolean) {
        val (url, hls) = candidates.getOrNull(working ?: candidate) ?: return
        if (hls) { if (pauseAtStart) player.playWhenReady = false; return } // HLS keeps its own window
        watchdog?.cancel(); retry?.cancel()
        player.stop() // one connection: the recording takes over from the player
        val rec = com.vantaview.app.data.player.LiveRecorder(okHttp, url, headers, java.io.File(context.cacheDir, "timeshift"), windowSec())
        recorder = rec
        rec.start(viewModelScope)
        _ui.update { it.copy(shift = ShiftUi(pauseAtStart, 0, false, "Starting the recording…")) }
        viewModelScope.launch {
            val until = System.currentTimeMillis() + 15_000
            while (rec.pieces < 1 && rec.failed == null && System.currentTimeMillis() < until) delay(250)
            if (recorder !== rec) return@launch
            if (rec.pieces < 1) {
                stopShift()
                _ui.update { it.copy(shift = null, message = rec.failed ?: "This channel can't be paused") }
                prepareCurrent()
                return@launch
            }
            val item = MediaItem.Builder().setUri(android.net.Uri.fromFile(rec.playlist)).setMimeType(MimeTypes.APPLICATION_M3U8).build()
            player.setMediaSource(DefaultMediaSourceFactory(DefaultDataSource.Factory(context)).createMediaSource(item))
            player.prepare()
            if (pauseAtStart) { player.seekTo(0); player.playWhenReady = false } else player.playWhenReady = true
            startShiftTicker()
        }
    }

    private fun playArchiveFrom(from: Long) {
        val ch = currentChannel ?: return
        val earliest = System.currentTimeMillis() - ch.archiveDays * 86_400_000L
        val start = from.coerceAtLeast(earliest)
        recorder?.stop(); recorder = null
        _ui.update { it.copy(shift = ShiftUi(false, ((System.currentTimeMillis() - start) / 1000).toInt(), true, "Loading from catch-up…")) }
        viewModelScope.launch {
            val t = runCatching { repo.timeshift(ch, start, System.currentTimeMillis()) }.getOrNull()
            if (t == null) { goLive(); _ui.update { it.copy(message = "Couldn't rewind this channel") }; return@launch }
            archiveStart = start
            val http = OkHttpDataSource.Factory(okHttp).setDefaultRequestProperties(t.headers)
            val item = MediaItem.Builder().setUri(t.url).apply { if (looksHls(t.url)) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
            player.setMediaSource(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)).createMediaSource(item))
            player.prepare()
            player.playWhenReady = true
            startShiftTicker()
        }
    }

    private fun shiftProblem(reason: String) {
        val rec = recorder
        when {
            archiveStart != null -> goLive() // the catch-up stream reached now (or failed): carry on live
            rec != null && rec.failed != null -> { goLive(); _ui.update { it.copy(message = rec.failed) } }
            rec != null -> { player.seekToDefaultPosition(); player.prepare() } // fell out of the kept window
            else -> goLive()
        }
    }

    private fun startShiftTicker() {
        if (shiftTicker?.isActive == true) return
        shiftTicker = viewModelScope.launch {
            while (true) {
                val dur = player.duration.takeIf { it > 0 && it != androidx.media3.common.C.TIME_UNSET } ?: 0L
                val pos = player.currentPosition
                val a = archiveStart
                val behind = when {
                    a != null -> ((System.currentTimeMillis() - (a + pos)) / 1000).toInt()
                    dur > 0 -> ((dur - pos) / 1000).toInt()
                    else -> 0
                }
                val shifting = recorder != null || a != null || (!player.playWhenReady && hlsNow())
                _ui.update {
                    it.copy(shift = if (!shifting && behind < 15) null else ShiftUi(!player.playWhenReady, behind.coerceAtLeast(0), a != null,
                        it.shift?.note?.takeIf { _ -> player.playbackState != Player.STATE_READY }))
                }
                if (!shifting && behind < 15) break
                delay(1000)
            }
        }
    }

    private fun stopShift() {
        shiftTicker?.cancel(); shiftTicker = null
        recorder?.stop(); recorder = null
        archiveStart = null
    }

    private fun looksHls(url: String): Boolean = url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

    private fun prepareCurrent() {
        val (url, hls) = candidates.getOrNull(candidate) ?: return
        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
            ?: "VantaView/${BuildConfig.VERSION_NAME}"
        val http = OkHttpDataSource.Factory(okHttp)
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", true) })
        val item = MediaItem.Builder().setUri(url).apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
        val source = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)).createMediaSource(item)
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
    }

    private fun giveUpOnEvent() {
        watchdog?.cancel(); retry?.cancel(); eventTimer?.cancel()
        player.stop()
        _ui.update {
            it.copy(
                status = PlayStatus.FAILED,
                message = "Nothing is on this channel right now. Event channels only show something while an event is live.",
            )
        }
    }

    private fun onFailure(reason: String) {
        retry?.cancel()
        // Paused or rewound: problems are handled by the time-shift code, not by reconnecting to live.
        if (recorder != null || archiveStart != null) { shiftProblem(reason); return }
        if (candidates.isEmpty()) return
        when {
            // Still looking for an address that works: try the next one straight away.
            working == null && candidate < candidates.lastIndex -> {
                candidate++
                prepareCurrent()
            }
            // An event channel that never started is almost always just empty: don't keep retrying.
            _ui.value.eventOnly && working == null -> giveUpOnEvent()
            attempts < 5 -> {
                attempts++
                _ui.update { it.copy(status = PlayStatus.RECONNECTING, attempt = attempts, message = reason) }
                retry = viewModelScope.launch {
                    delay(1000L shl (attempts - 1))
                    candidate = working ?: 0
                    prepareCurrent()
                }
            }
            else -> _ui.update { it.copy(status = PlayStatus.FAILED, message = reason) }
        }
    }

    private fun describe(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "No connection to the stream"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            "The provider refused the stream (check your login or connection limit)"
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The stream was not found"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "This stream format isn't supported yet"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "This device could not decode the stream"
        else -> "Playback problem (${e.errorCodeName})"
    }

    override fun onCleared() {
        watchdog?.cancel(); retry?.cancel(); loadJob?.cancel(); eventTimer?.cancel(); guideJob?.cancel()
        stopShift()
        player.removeListener(listener)
        player.release()
        ai?.release()
    }
}
