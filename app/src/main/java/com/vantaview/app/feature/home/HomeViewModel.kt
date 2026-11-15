// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.home

import android.content.Context
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.vantaview.app.data.addon.AddonRepository
import com.vantaview.app.data.addon.MetaDetail
import com.vantaview.app.data.addon.Video
import com.vantaview.app.data.db.ChannelEntity
import com.vantaview.app.data.db.ProfileEntity
import com.vantaview.app.data.db.VantaDb
import com.vantaview.app.data.history.HistoryEntry
import com.vantaview.app.data.history.WatchHistory
import com.vantaview.app.data.profile.KidsFilter
import com.vantaview.app.data.profile.ProfileRepository
import com.vantaview.app.data.profile.ProfileSession
import com.vantaview.app.data.repo.ProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.time.Instant
import javax.inject.Inject

/** A section tile: name, a line under it, and up to four images for the mosaic. */
data class Tile(val title: String, val subtitle: String, val images: List<String>)

enum class Badge { NEW_SEASON, COMING_SOON }

/** One card in Continue watching. [playVideoId] null means "open the details page" (nothing to play yet). */
data class ContinueItem(
    val entry: HistoryEntry,
    val line: String,
    val progress: Float?,
    val badge: Badge?,
    val playVideoId: String?,
)

/** The "New season" pop-up. */
data class NewSeason(val entry: HistoryEntry, val season: Int, val episodes: Int, val first: Video)

data class Preview(val channel: ChannelEntity, val muted: Boolean = true, val error: String? = null)

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: VantaDb,
    private val providers: ProviderRepository,
    private val addons: AddonRepository,
    private val history: WatchHistory,
    private val session: ProfileSession,
    private val okHttp: OkHttpClient,
    private val guide: com.vantaview.app.data.guide.GuideRepository,
    private val weatherRepo: com.vantaview.app.data.weather.WeatherRepository,
    private val playback: com.vantaview.app.data.PlaybackSettings,
    private val parental: com.vantaview.app.data.profile.ParentalControl,
    library: com.vantaview.app.data.library.LibraryStore,
    newEpisodes: com.vantaview.app.data.library.NewEpisodes,
    profiles: ProfileRepository,
) : ViewModel() {
    /** Titles shared with this profile that Home hasn't shown yet. */
    val newShares: StateFlow<List<com.vantaview.app.data.library.LibraryStore.Shared>> = library.shared
        .mapLatest { library.unseenShares() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val lib = library
    fun dismissShares() = lib.markSharesSeen()

    /** The Watchlist for the row on Home, newest first. */
    val watchlist: StateFlow<List<com.vantaview.app.data.library.SavedTitle>> =
        library.watchlist.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Watchlist series with an episode out since they were added or last opened. */
    val withNewEpisode: StateFlow<Set<String>> = library.watchlist
        .mapLatest { newEpisodes.withNew(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val profile: StateFlow<ProfileEntity?> = profiles.observeCurrent().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val providerIds = providers.observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val favourites: StateFlow<List<ChannelEntity>> = providerIds
        .flatMapLatest { list -> if (list.isEmpty()) flowOf(emptyList<ChannelEntity>()) else db.content().observeFavouriteChannels(list.map { it.id }) }
        .let { f ->
            // Settings › Parental control: hidden adult channels stay off Home too.
            combine(f, parental.changes) { list, _ ->
                if (!parental.hiding) list
                else list.groupBy { it.providerId }.flatMap { (pid, chans) ->
                    val ids = parental.adultIds(db.content().categories(pid))
                    chans.filter { !parental.hidden(it, ids) }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Weather for the greeting (null when off or nowhere to get it for). */
    val weather: StateFlow<com.vantaview.app.data.weather.Weather?> = weatherRepo.weather
    val weatherEnabled: Boolean get() = weatherRepo.enabled
    fun weatherHasSource(): Boolean = weatherRepo.hasSource()
    /** True only the first time: Home then asks for location so the weather can show. */
    fun shouldAskLocation(): Boolean = weatherRepo.enabled && !weatherRepo.askedForLocation && !weatherRepo.hasLocationPermission() &&
        // TVs and Fire TV have no location to give; they use the time zone's city instead.
        !context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
    fun markAskedLocation() { weatherRepo.askedForLocation = true }
    fun refreshWeather(force: Boolean = false) { viewModelScope.launch { weatherRepo.refresh(force) } }

    /** What's on now on the last-watched channel. */
    private val _previewNow = MutableStateFlow<com.vantaview.app.data.db.ProgrammeEntity?>(null)
    val previewNow: StateFlow<com.vantaview.app.data.db.ProgrammeEntity?> = _previewNow

    /** Favourite channels with what's on now (from the TV guide). */
    private val _onNow = MutableStateFlow<List<Pair<ChannelEntity, com.vantaview.app.data.db.ProgrammeEntity>>>(emptyList())
    val onNow: StateFlow<List<Pair<ChannelEntity, com.vantaview.app.data.db.ProgrammeEntity>>> = _onNow

    private val _tiles = MutableStateFlow<List<Pair<String, Tile>>>(emptyList())
    /** Keyed by section route: "live", "movies", "series". */
    val tiles: StateFlow<List<Pair<String, Tile>>> = _tiles

    private val _continue = MutableStateFlow<List<ContinueItem>>(emptyList())
    val continueWatching: StateFlow<List<ContinueItem>> = _continue

    private val _newSeason = MutableStateFlow<NewSeason?>(null)
    val newSeason: StateFlow<NewSeason?> = _newSeason

    private val _preview = MutableStateFlow<Preview?>(null)
    val preview: StateFlow<Preview?> = _preview

    /** Muted live preview of the last channel watched. */
    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply { volume = 0f }

    private val metaCache = HashMap<String, MetaDetail?>()

    init {
        viewModelScope.launch {
            combine(providerIds, addons.observe(), profile) { p, a, pr -> Triple(p, a, pr) }.collect { buildTiles() }
        }
        viewModelScope.launch {
            combine(history.entries, profile) { e, _ -> e }.collect { buildContinue(it) }
        }
        viewModelScope.launch {
            combine(session.current, session.lastChannelChanges, parental.changes) { _, _, _ -> }.collect { loadPreview() }
        }
        // TV guide: load it when missing or old, then keep "On now" for favourite channels up to date.
        viewModelScope.launch { providerIds.collect { list -> list.forEach { guide.ensureFresh(it) } } }
        viewModelScope.launch {
            combine(favourites, guide.version, minuteTicks()) { f, _, _ -> f }.collect { buildOnNow(it) }
        }
        viewModelScope.launch {
            combine(_preview, guide.version, minuteTicks()) { p, _, _ -> p?.channel }.collect { ch ->
                val key = ch?.epgChannelId
                _previewNow.value = if (ch == null || key == null) null
                else runCatching { guide.nowNext(ch.providerId, key) }.getOrDefault(emptyList())
                    .firstOrNull()?.takeIf { it.start <= System.currentTimeMillis() }
            }
        }
        // Weather: now, then every 30 minutes.
        viewModelScope.launch { while (true) { weatherRepo.refresh(); kotlinx.coroutines.delay(30 * 60_000L) } }
    }

    // ---- On now (favourite channels with a guide) ----


    private fun minuteTicks() = kotlinx.coroutines.flow.flow {
        while (true) { emit(System.currentTimeMillis()); kotlinx.coroutines.delay(60_000) }
    }

    private suspend fun buildOnNow(favs: List<ChannelEntity>) {
        val out = ArrayList<Pair<ChannelEntity, com.vantaview.app.data.db.ProgrammeEntity>>()
        favs.filter { it.epgChannelId != null }.groupBy { it.providerId }.forEach { (pid, chans) ->
            val progs = runCatching { guide.onNow(pid, chans.mapNotNull { it.epgChannelId }) }.getOrDefault(emptyList())
                .associateBy { it.channelKey }
            chans.forEach { c -> progs[c.epgChannelId]?.let { out += c to it } }
        }
        _onNow.value = out
    }

    // ---- Tiles ----

    private suspend fun buildTiles() {
        val kids = profile.value?.isKids == true
        val ids = providerIds.value.map { it.id }
        val out = ArrayList<Pair<String, Tile>>()
        if (ids.isNotEmpty()) {
            val count = db.content().observeChannelCount(ids).first()
            val logos = runCatching { db.content().sampleLogos(ids) }.getOrDefault(emptyList())
            out += "live" to Tile("LIVE TV", "$count channels", logos)
            out += "guide" to Tile("TV GUIDE", "What's on", logos.drop(4).ifEmpty { logos })
            // Catch-up, when any channel keeps past programmes.
            val past = runCatching { db.content().catchUpCount(ids) }.getOrDefault(0)
            if (past > 0) out += "catchup" to Tile("CATCH-UP", "$past channels", emptyList())
        } else {
            out += "live" to Tile("LIVE TV", "Add a playlist", emptyList())
        }
        for ((route, type, title) in listOf(Triple("movies", "movie", "MOVIES"), Triple("series", "series", "TV SERIES"))) {
            val rows = runCatching { if (kids) addons.kidsCatalogs(type) else addons.catalogs(type) }.getOrDefault(emptyList())
            val posters = rows.firstOrNull()?.let { r -> runCatching { addons.loadCatalog(r) }.getOrDefault(emptyList()) }
                ?.mapNotNull { it.poster }?.take(4).orEmpty()
            out += route to Tile(
                title,
                if (rows.isEmpty()) "Add an add-on" else "${rows.size} catalogue" + if (rows.size == 1) "" else "s",
                posters,
            )
        }
        _tiles.value = out
    }

    // ---- Continue watching and New season ----

    private fun released(v: Video): Boolean =
        v.released == null || runCatching { Instant.parse(v.released) <= Instant.now() }.getOrDefault(true)

    private suspend fun meta(e: HistoryEntry): MetaDetail? =
        metaCache.getOrPut(e.metaId) { runCatching { addons.meta("series", e.metaId) }.getOrNull() }

    private suspend fun buildContinue(entries: List<HistoryEntry>) {
        // A quick first pass from history alone, then the series are filled in from their add-on details.
        _continue.value = entries.take(20).mapNotNull { quick(it) }
        val items = ArrayList<ContinueItem>()
        var popup: NewSeason? = null
        for (e in entries.take(20)) {
            if (e.type != "series") { quick(e)?.let { items += it }; continue }
            val m = meta(e)
            if (m == null) { quick(e)?.let { items += it }; continue }
            val videos = m.videos.filter { it.season > 0 }
            val i = videos.indexOfFirst { it.id == e.videoId }
            val maxReleased = videos.filter(::released).maxOfOrNull { it.season } ?: 0
            val watchedUpTo = maxOf(e.knownSeason, e.season ?: 0)

            // New season: a season this profile hadn't seen released when it last watched.
            if (popup == null && maxReleased > watchedUpTo && watchedUpTo > 0 && !history.newSeasonSeen(e.metaId, maxReleased)) {
                val eps = videos.filter { it.season == maxReleased && released(it) }
                eps.firstOrNull()?.let { popup = NewSeason(e, maxReleased, eps.size, it) }
            }

            if (!e.finished) { quick(e)?.let { items += it }; continue }
            val next = if (i >= 0) videos.getOrNull(i + 1) else null
            when {
                next == null -> Unit
                released(next) && next.season > watchedUpTo ->
                    items += ContinueItem(e, "S${next.season} E${next.episode} · New", null, Badge.NEW_SEASON, next.id)
                released(next) ->
                    items += ContinueItem(e, "S${next.season} E${next.episode} · Up next", null, null, next.id)
                next.season != e.season ->
                    items += ContinueItem(e, "Season ${next.season} · Coming soon", null, Badge.COMING_SOON, null)
                else ->
                    items += ContinueItem(e, "S${next.season} E${next.episode} · Coming ${next.released?.take(10).orEmpty()}", null, Badge.COMING_SOON, null)
            }
        }
        _continue.value = items
        if (_newSeason.value == null) _newSeason.value = popup
    }

    /** Card from history alone: films and episodes left part-way through. */
    private fun quick(e: HistoryEntry): ContinueItem? {
        if (e.finished) return null
        val left = com.vantaview.app.feature.vod.minutesLeft(e.remainingMs)
        val line = if (e.type == "series") "S${e.season ?: 0} E${e.episode ?: 0} · $left" else left
        return ContinueItem(e, line, e.fraction.takeIf { it > 0f }, null, e.videoId)
    }

    fun dismissNewSeason() {
        _newSeason.value?.let { history.markNewSeasonSeen(it.entry.metaId, it.season) }
        _newSeason.value = null
    }

    fun removeFromContinue(e: HistoryEntry) = history.remove(e.type, e.metaId)

    // ---- Refresh ----

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    /** Home › Refresh (pull down on a phone): playlists, TV guide, weather and the rows on Home load again. */
    fun refreshAll() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            try {
                val list = db.providers().allFor(session.id)
                list.forEach { providers.sync(it.id) }
                list.forEach { runCatching { guide.refresh(it) } }
                runCatching { weatherRepo.refresh(true) }
                runCatching { buildTiles() }
                runCatching { buildContinue(history.entries.first()) }
                runCatching { buildOnNow(favourites.value) }
                runCatching { loadPreview() }
                // Playlists carry on loading in the background; their progress shows on Live TV and Sources.
                kotlinx.coroutines.delay(800)
            } finally {
                _refreshing.value = false
            }
        }
    }

    // ---- Live preview ----

    /** Fires once after the app starts when "Start on last channel" is on: open this channel. */
    private val _openChannel = MutableStateFlow<Long?>(null)
    val openChannel: StateFlow<Long?> = _openChannel
    fun openedChannel() { _openChannel.value = null }

    /** Settings › Playback › Mini player: off shows the last channel without the muted video. */
    val miniPlayer: Boolean get() = playback.miniPlayer

    private suspend fun loadPreview() {
        val firstStart = playback.firstStart()
        val id = session.lastChannel()
        val ch = if (id > 0) db.content().channel(id) else null
        val mine = ch != null && db.providers().allFor(session.id).any { it.id == ch.providerId }
        if (ch == null || !mine) { player.stop(); _preview.value = null; return }
        if (profile.value?.isKids == true && (ch.isAdult || KidsFilter.looksAdult(ch.name))) { player.stop(); _preview.value = null; return }
        if (parental.hiding && parental.hidden(ch, parental.adultIds(db.content().categories(ch.providerId)))) { player.stop(); _preview.value = null; return }
        // Same channel already previewing: leave it playing.
        if (_preview.value?.channel?.id == ch.id && player.playbackState != androidx.media3.common.Player.STATE_IDLE) return
        player.stop()
        _preview.value = Preview(ch)
        if (firstStart && playback.startOnLastChannel) { _openChannel.value = ch.id; return }
        if (visible) startPreview()
    }

    fun startPreview() {
        if (!playback.miniPlayer) return
        val ch = _preview.value?.channel ?: return
        viewModelScope.launch {
            try {
                val t = providers.resolveStream(ch)
                val ua = t.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: "VantaView"
                val http = OkHttpDataSource.Factory(okHttp).setUserAgent(ua)
                    .setDefaultRequestProperties(t.headers.filterKeys { !it.equals("User-Agent", true) })
                val url = t.altUrl ?: t.url
                val item = MediaItem.Builder().setUri(url).apply {
                    if (url.substringBefore('?').endsWith(".m3u8", true)) setMimeType(MimeTypes.APPLICATION_M3U8)
                }.build()
                player.setMediaSource(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)).createMediaSource(item))
                player.prepare()
                player.playWhenReady = true
            } catch (e: Exception) {
                _preview.update { it?.copy(error = "Preview unavailable") }
            }
        }
    }

    /** True while Home is on screen; the preview only plays then. */
    private var visible = false
    fun onShown() { visible = true; startPreview() }
    fun onHidden() { visible = false; stopPreview() }

    fun stopPreview() = player.stop()

    fun toggleMute() {
        _preview.update { p -> p?.copy(muted = !p.muted) }
        player.volume = if (_preview.value?.muted == false) 1f else 0f
    }

    override fun onCleared() = player.release()
}
