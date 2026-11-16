// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vantaview.app.data.country.CountryDetect
import com.vantaview.app.data.country.CountryScan
import com.vantaview.app.data.country.CountryStore
import com.vantaview.app.data.db.CategoryEntity
import com.vantaview.app.data.db.ChannelEntity
import com.vantaview.app.data.db.ChannelRow
import com.vantaview.app.data.db.ProfileEntity
import com.vantaview.app.data.db.ProviderEntity
import com.vantaview.app.data.db.VantaDb
import com.vantaview.app.data.profile.KidsFilter
import com.vantaview.app.data.profile.ProfileRepository
import com.vantaview.app.data.profile.ProfileSession
import com.vantaview.app.data.repo.ProviderRepository
import com.vantaview.app.data.repo.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which group of channels is showing. */
sealed interface Filter {
    data object All : Filter
    data object Favourites : Filter
    /** Radio stations (kept out of All and the categories). */
    data object Radio : Filter
    data class Category(val remoteId: String) : Filter
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
/** Orders for the Live TV channel list. MINE is the order you set for favourites. */
enum class ChannelSort(val label: String) {
    NUMBER("Number"), AZ("A to Z"), ZA("Z to A"), RECENT("Recently starred"), MINE("My order");

    fun apply(list: List<ChannelRow>, favourites: Boolean): List<ChannelRow> = when (this) {
        NUMBER -> list
        AZ -> list.sortedBy { it.channel.sortKey }
        ZA -> list.sortedByDescending { it.channel.sortKey }
        RECENT -> list.sortedWith(compareByDescending<ChannelRow> { it.isFav }.thenByDescending { it.favAt })
        MINE -> if (favourites) list.sortedBy { it.favPos } else list
    }

    fun save(p: com.vantaview.app.data.PlaybackSettings) = p.setString("channel_sort", name)

    companion object {
        fun load(p: com.vantaview.app.data.PlaybackSettings) =
            entries.firstOrNull { it.name == p.getString("channel_sort") } ?: NUMBER
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

/** Live TV › "All playlists": a pretend playlist id, and the prefix of merged group ids (a group's name). */
const val ALL_PLAYLISTS = -1L
private const val NAME = "name:"

@HiltViewModel
class LiveViewModel @Inject constructor(
    private val db: VantaDb,
    private val repo: ProviderRepository,
    private val session: ProfileSession,
    private val countries: CountryStore,
    private val guide: com.vantaview.app.data.guide.GuideRepository,
    private val parental: com.vantaview.app.data.profile.ParentalControl,
    private val library: com.vantaview.app.data.library.LibraryStore,
    private val reminders: com.vantaview.app.data.library.ReminderScheduler,
    private val playback: com.vantaview.app.data.PlaybackSettings,
    private val vodSession: com.vantaview.app.data.addon.VodSession,
    profiles: ProfileRepository,
) : ViewModel() {
    val providers: StateFlow<List<ProviderEntity>> =
        repo.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val profile: StateFlow<ProfileEntity?> =
        profiles.observeCurrent().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val providerId = MutableStateFlow(session.lastProvider())
    val filter = MutableStateFlow<Filter>(Filter.All)
    val query = MutableStateFlow("")
    /** How the channel list is ordered (remembered on this device). */
    val sort = MutableStateFlow(ChannelSort.load(playback))
    fun setSort(s: ChannelSort) { sort.value = s; s.save(playback) }

    // ---- All playlists: channels from every playlist in one list (not on Kids profiles) ----

    private val allChoice = MutableStateFlow(playback.getString("live_all") == "1")

    /** Showing every playlist together. */
    val showingAll: StateFlow<Boolean> = combine(allChoice, providers, profile) { a, list, p -> a && list.size > 1 && p?.isKids != true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun selectAll() {
        allChoice.value = true
        playback.setString("live_all", "1")
        filter.value = Filter.All
    }

    private val allIds = combine(showingAll, providers) { all, list -> if (all) list.map { it.id } else null }.distinctUntilChanged()

    val active: StateFlow<ProviderEntity?> =
        combine(providers, providerId) { list, id -> list.firstOrNull { it.id == id } ?: list.firstOrNull() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val activeId = active.map { it?.id }.distinctUntilChanged()

    /** For a Kids profile: the categories a parent allowed on the active playlist (empty = not picked yet). */
    private val allowed = combine(profile, activeId) { p, id -> p to id }
        .flatMapLatest { (p, id) ->
            if (p?.isKids != true || id == null) flowOf(null)
            else db.profiles().observeKidsCategories(p.id).map { rows ->
                rows.filter { it.providerId == id }.map { it.categoryRemoteId }.toSet()
            }
        }

    /** Every playlist's real groups (All playlists), for adult filtering per playlist. */
    private val realAllCategories = allIds.flatMapLatest { ids -> if (ids == null) flowOf(emptyList()) else db.content().observeCategoriesIn(ids) }

    private val allCategories = combine(activeId, allIds) { id, ids -> id to ids }
        .flatMapLatest { (id, ids) ->
            when {
                // All playlists: groups with the same name are one group ("name:<name>").
                ids != null -> db.content().observeCategoriesIn(ids).map { cats ->
                    cats.distinctBy { it.name.trim().lowercase() }.mapIndexed { i, c -> CategoryEntity(id = -(i + 1L), providerId = ALL_PLAYLISTS, remoteId = NAME + c.name, name = c.name, position = i) }
                }
                id == null -> flowOf(emptyList<CategoryEntity>())
                else -> db.content().observeCategories(id)
            }
        }

    /** Countries in the active playlist (re-read after each sync). */
    private val scan: StateFlow<CountryScan?> = active
        .distinctUntilChanged { a, b -> a?.id == b?.id && a?.lastSyncAt == b?.lastSyncAt }
        .flatMapLatest { p -> if (p == null) flowOf(null) else flow<CountryScan?> { emit(null); emit(countries.scan(p)) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Countries this profile shows for the active playlist; null = all. */
    private val chosen = combine(session.current, activeId, countries.changes) { prof, id, _ ->
        if (id == null) null else countries.chosen(prof, id)
    }.distinctUntilChanged()

    private val device = CountryDetect.deviceCountry()

    // ---- Group order and visibility, per playlist (and per profile, with the other Playback settings) ----

    private val groupLayout = MutableStateFlow(0)
    private val SEP = "\u0001"
    private fun orderOf(pid: Long): List<String> = playback.getString("catorder_$pid")?.split(SEP)?.filter { it.isNotEmpty() }.orEmpty()
    private fun hiddenOf(pid: Long): Set<String> = playback.getString("cathidden_$pid")?.split(SEP)?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    /** The groups before hiding and reordering (for the Edit groups dialog). */
    var groupsForEditing: List<CategoryEntity> = emptyList(); private set
    fun hiddenGroups(): Set<String> = active.value?.let { hiddenOf(it.id) }.orEmpty()

    fun saveGroupLayout(order: List<String>, hidden: Set<String>) {
        val pid = active.value?.id ?: return
        playback.setString("catorder_$pid", order.joinToString(SEP))
        playback.setString("cathidden_$pid", hidden.joinToString(SEP))
        groupLayout.value++
    }

    private fun applyLayout(list: List<CategoryEntity>): List<CategoryEntity> {
        val pid = list.firstOrNull()?.providerId ?: return list
        if (pid == ALL_PLAYLISTS) return list
        groupsForEditing = list
        val order = orderOf(pid).withIndex().associate { (i, id) -> id to i }
        val hidden = hiddenOf(pid)
        return list.filter { it.remoteId !in hidden }
            .let { l -> if (order.isEmpty()) l else l.withIndex().sortedBy { (i, c) -> order[c.remoteId]?.toDouble() ?: (order.size + i).toDouble() }.map { it.value } }
    }

    val categories: StateFlow<List<CategoryEntity>> =
        combine(combine(allCategories, groupLayout) { c, _ -> c }, allowed, combine(scan, allIds) { s, ids -> if (ids != null) null else s }, chosen, parental.changes) { cats, allow, sc, pick, _ ->
            val kidsOk = when {
                allow != null -> KidsFilter.categories(cats, allow)
                parental.hiding -> cats.filter { !parental.isAdult(it) } // Settings › Parental control
                else -> cats
            }
            // Radio groups live under the Radio tab.
            val tvOnly = kidsOk.filter { !Regex("(?i)\\bradio|rádio|радио").containsMatchIn(it.name) }
            val base = if (sc == null) tvOnly
            else tvOnly
                .filter { c -> pick == null || sc.inCategory(c.remoteId).any { it in pick } }
                // Categories with this device's country first; otherwise keep the provider's order.
                .sortedBy { if (device != null && device in sc.inCategory(it.remoteId)) 0 else 1 }
            applyLayout(base)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Short label for the Countries button, or null when the playlist has fewer than two countries. */
    val countryLabel: StateFlow<String?> = combine(scan, chosen, profile, showingAll) { sc, pick, p, all ->
        when {
            all -> null
            sc == null || sc.countries.size < 2 || p?.isKids == true -> null
            pick == null -> "All countries"
            else -> pick.filter { it != CountryDetect.OTHER }.let { l ->
                if (l.size <= 3) l.joinToString(", ").ifEmpty { "Other" } else "${l.size} countries"
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun editCountries() { active.value?.let { countries.editRequest.value = it.id } }

    val channels: StateFlow<List<ChannelRow>> =
        combine(combine(activeId, filter, query.debounce(250)) { id, f, q -> Triple(id, f, q.trim()) }, scan, chosen, allIds) { t, sc, pick, ids ->
            Quad(t, sc, pick, ids)
        }
            .flatMapLatest { (t, sc, pick, ids) ->
                val (id, f, q) = t
                val fav = if (f is Filter.Favourites) 1 else 0
                val cat = (f as? Filter.Category)?.remoteId
                val radio = if (f is Filter.Radio) 1 else 0
                when {
                    ids != null -> db.content().observeChannelsAll(ids, cat?.removePrefix(NAME), q, fav, radio)
                    id == null -> flowOf(emptyList<ChannelRow>())
                    pick == null || sc == null -> db.content().observeChannels(id, cat, q, fav, radio)
                    // Only channels from the chosen countries.
                    else -> db.content().observeChannelsIn(id, cat, pick.toList(), q, fav, radio)
                }
            }
            .let { rows -> combine(rows, sort, filter) { list, so, f -> so.apply(list, f is Filter.Favourites) } }
            .let { rows ->
                combine(rows, combine(allCategories, realAllCategories, allIds) { c, real, ids -> if (ids != null) real else c }, allowed, parental.changes) { list, cats, allow, _ ->
                    if (allow == null && cats.any { it.providerId != list.firstOrNull()?.channel?.providerId }) {
                        // Several playlists: each channel is checked against its own playlist's adult groups.
                        if (!parental.hiding) list
                        else {
                            val byProvider = cats.groupBy { it.providerId }.mapValues { (_, c) -> parental.adultIds(c) }
                            list.filter { !parental.hidden(it.channel, byProvider[it.channel.providerId].orEmpty()) }
                        }
                    } else if (allow == null) {
                        if (!parental.hiding) list
                        else parental.adultIds(cats).let { ids -> list.filter { !parental.hidden(it.channel, ids) } }
                    } else {
                        val adultCats = cats.filter { KidsFilter.looksAdult(it.name) }.map { it.remoteId }.toSet()
                        list.filter { KidsFilter.channelAllowed(it.channel, adultCats, allow) }
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Radio stations in the active playlist; Live TV shows a Radio tab when there are some. */
    val radioCount: StateFlow<Int> = combine(activeId, allIds) { id, ids -> id to ids }
        .flatMapLatest { (id, ids) ->
            when {
                ids != null -> db.content().observeRadioCountIn(ids)
                id == null -> flowOf(0)
                else -> db.content().observeRadioCount(id)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val isKids: StateFlow<Boolean> = profile.map { it?.isKids == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val sync: StateFlow<Map<Long, SyncState>> = repo.syncStates

    /** TV guide state for the active playlist, and a counter that changes when a guide finishes loading. */
    val guideState = combine(active, guide.states, showingAll) { p, m, all -> if (all) null else p?.let { m[it.id] } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val guideVersion: StateFlow<Int> = guide.version

    fun addFreeTv() { viewModelScope.launch { repo.addFreeTv() } }

    fun refreshGuide() {
        if (showingAll.value) providers.value.forEach { guide.refresh(it) } else active.value?.let { guide.refresh(it) }
    }

    /** Now and next on a channel (empty when it has no guide). */
    suspend fun nowNext(channel: com.vantaview.app.data.db.ChannelEntity): List<com.vantaview.app.data.db.ProgrammeEntity> {
        val key = channel.epgChannelId ?: return emptyList()
        return guide.nowNext(channel.providerId, key)
    }

    /** Programmes on a channel between two times. */
    suspend fun programmes(channel: com.vantaview.app.data.db.ChannelEntity, from: Long, to: Long): List<com.vantaview.app.data.db.ProgrammeEntity> {
        val key = channel.epgChannelId ?: return emptyList()
        return guide.range(channel.providerId, key, from, to)
    }

    init {
        // Load the TV guide for the playlist in use when it's missing or old.
        viewModelScope.launch {
            // All playlists: every playlist's guide is loaded (when it's missing or old).
            combine(active, showingAll, providers) { p, all, list -> if (all) list else listOfNotNull(p) }
                .collect { list -> list.forEach { guide.ensureFresh(it) } }
        }
        // A different profile has its own last-used playlist.
        viewModelScope.launch {
            session.current.collect {
                providerId.value = session.lastProvider()
                filter.value = Filter.All
            }
        }
    }

    fun selectProvider(id: Long) {
        allChoice.value = false
        playback.setString("live_all", "0")
        session.setLastProvider(id)
        providerId.value = id
        filter.value = Filter.All
    }

    fun toggleFavourite(row: ChannelRow) {
        viewModelScope.launch { repo.setFavourite(row.channel, !row.isFav) }
    }

    /** The channel last watched full screen (0 when none), for holding Back in the TV Guide. */
    fun lastChannelId(): Long = session.lastChannel()

    fun refresh() {
        if (showingAll.value) providers.value.forEach { repo.sync(it.id) } else active.value?.let { repo.sync(it.id) }
    }

    /** Live TV › Favourites › Edit: the new order of the favourites shown. */
    fun saveFavouriteOrder(rows: List<ChannelRow>) {
        viewModelScope.launch { repo.reorderFavourites(rows.map { it.channel }) }
        setSort(ChannelSort.MINE)
    }

    /** A channel by number in the active playlist, for typing a number on the remote. */
    // ---- Programme reminders (the guide's Remind me) ----

    val reminderList = library.reminders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun hasCatchUp(channel: ChannelEntity, prog: com.vantaview.app.data.db.ProgrammeEntity): Boolean = repo.hasCatchUp(channel, prog)

    /** Catch-up message for the guide ("Getting the recording…" or a problem); null when there's nothing to say. */
    val catchUpMessage = MutableStateFlow<String?>(null)

    /** Asks the provider for a past programme's recording, then hands it to the film player. */
    fun catchUp(channel: ChannelEntity, prog: com.vantaview.app.data.db.ProgrammeEntity, onReady: () -> Unit) {
        catchUpMessage.value = "Getting the recording…"
        viewModelScope.launch {
            val target = runCatching { repo.catchUp(channel, prog) }
            val t = target.getOrNull()
            if (t == null) {
                catchUpMessage.value = target.exceptionOrNull()?.message ?: "This programme isn't available to catch up on."
                return@launch
            }
            val name = com.vantaview.app.data.ChannelNames.display(channel.name)
            vodSession.current = com.vantaview.app.data.addon.VodRequest(
                title = prog.title,
                subtitle = name + " · " + java.text.SimpleDateFormat("EEE d MMM, HH:mm", com.vantaview.app.ui.I18n.locale).format(java.util.Date(prog.start)),
                streams = listOf(com.vantaview.app.data.addon.AddonStream(t.url, name, "Catch-up", t.headers, 0, null, 0)),
                type = "catchup",
            )
            catchUpMessage.value = null
            onReady()
        }
    }

    /** Sets or cancels a reminder; returns true when one is now set. */
    fun toggleReminder(channel: ChannelEntity, prog: com.vantaview.app.data.db.ProgrammeEntity): Boolean {
        val existing = library.remindersNow().firstOrNull { it.channelId == channel.id && it.start == prog.start }
        if (existing != null) {
            library.removeReminder(existing); reminders.cancel(existing); return false
        }
        val r = com.vantaview.app.data.library.Reminder(
            channel.id, com.vantaview.app.data.ChannelNames.display(channel.name), prog.title, prog.start, prog.stop, playback.reminderMinutes,
        )
        library.addReminder(r); reminders.schedule(r)
        return true
    }

    suspend fun channelIdByNumber(number: Int): Long? {
        val id = active.value?.id ?: return null
        channels.value.firstOrNull { it.channel.number == number }?.let { return it.channel.id }
        if (isKids.value) return null // Kids only reach what their list shows.
        val ch = db.content().channelByNumber(id, number) ?: return null
        if (parental.hiding && parental.hidden(ch, parental.adultIds(db.content().categories(id)))) return null
        return ch.id
    }
}
