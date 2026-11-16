// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.live

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import com.vantaview.app.ui.OutlinedTextField
import com.vantaview.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.vantaview.app.data.ChannelNames
import com.vantaview.app.data.db.CategoryEntity
import com.vantaview.app.data.db.ChannelEntity
import com.vantaview.app.data.db.ChannelRow
import com.vantaview.app.data.db.ProgrammeEntity
import com.vantaview.app.data.repo.SyncState
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.Vanta
import com.vantaview.app.ui.scrollWhenFocused
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Digit keys on a remote or keyboard, for typing a channel number. */
private val digitKeys = mapOf(
    Key.Zero to 0, Key.One to 1, Key.Two to 2, Key.Three to 3, Key.Four to 4,
    Key.Five to 5, Key.Six to 6, Key.Seven to 7, Key.Eight to 8, Key.Nine to 9,
    Key.NumPad0 to 0, Key.NumPad1 to 1, Key.NumPad2 to 2, Key.NumPad3 to 3, Key.NumPad4 to 4,
    Key.NumPad5 to 5, Key.NumPad6 to 6, Key.NumPad7 to 7, Key.NumPad8 to 8, Key.NumPad9 to 9,
)

@Composable
fun LiveScreen(
    onPlay: (Long) -> Unit,
    onAddPlaylist: () -> Unit,
    onAddAddon: () -> Unit,
    onCatchUpPage: () -> Unit = {},
    vm: LiveViewModel = hiltViewModel(),
) {
    val providers by vm.providers.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val isKids by vm.isKids.collectAsStateWithLifecycle()
    val radioCount by vm.radioCount.collectAsStateWithLifecycle()
    val countryLabel by vm.countryLabel.collectAsStateWithLifecycle()
    val guideVersion by vm.guideVersion.collectAsStateWithLifecycle()
    val state = active?.let { sync[it.id] }

    // Preview first (Settings › Playback): the first press plays the channel in a small window, the second goes
    // full screen. Channel up and down in the player then move through the list it was opened from.
    val preview: LivePreviewModel = hiltViewModel()
    val previewing by preview.channel.collectAsStateWithLifecycle()
    LivePreviewLifecycle(preview)
    val openFull: (Long) -> Unit = { id ->
        com.vantaview.app.feature.player.ZapList.set(channels.map { it.channel.id })
        preview.pause()
        onPlay(id)
    }
    val pick: (Long) -> Unit = { id ->
        val row = channels.firstOrNull { it.channel.id == id }
        if (preview.enabled && row != null && previewing?.id != id) preview.show(row.channel) else openFull(id)
    }

    // Re-read now and next every minute, and whenever a guide finishes loading.
    var minute by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); minute++ } }
    val guideTick = guideVersion * 100_000 + minute

    // Typing a channel number: digits collect for a moment, then that channel plays.
    var typed by remember { mutableStateOf("") }
    var notFound by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(typed) {
        if (typed.isEmpty()) return@LaunchedEffect
        delay(1_500)
        val n = typed.toIntOrNull()
        val id = n?.let { vm.channelIdByNumber(it) }
        if (id != null) onPlay(id) else notFound = typed
        typed = ""
    }
    LaunchedEffect(notFound) { if (notFound != null) { delay(2_000); notFound = null } }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Vanta.Page).onKeyEvent { e ->
            val d = digitKeys[e.key] ?: return@onKeyEvent false
            if (providers.isEmpty()) return@onKeyEvent false
            if (e.type == KeyEventType.KeyDown && typed.length < 5) typed += d
            true
        }
    ) {
        val wide = maxWidth >= 720.dp
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Header(
                wide = wide,
                playlistName = active?.name ?: "No playlist",
                providers = providers.map { it.id to it.name },
                countryLabel = countryLabel,
                showRefresh = providers.isNotEmpty(),
                onPickProvider = vm::selectProvider,
                onCountries = vm::editCountries,
                onRefresh = vm::refresh,
                onCatchUp = onCatchUpPage,
            )
            if (providers.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("No playlists yet", color = Vanta.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (isKids) "Ask a grown-up to add a playlist to this profile."
                            else "Add an IPTV playlist for live channels, or install add-ons for films and series.",
                            color = Vanta.TextDim,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.widthIn(max = 460.dp),
                        )
                        if (!isKids) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            com.vantaview.app.ui.GradientButton("Add a playlist", onClick = onAddPlaylist)
                            FocusCard(onClick = onAddAddon, contentAlignment = Alignment.Center) { Text("Add an add-on", color = Vanta.Text) }
                        }
                        if (!isKids) Box(Modifier.widthIn(max = 460.dp)) { com.vantaview.app.feature.playlist.FreeTvCard(onAdd = vm::addFreeTv) }
                    }
                }
                return@Column
            }
            Spacer(Modifier.height(10.dp))
            val sort by vm.sort.collectAsStateWithLifecycle()
            var sortOpen by remember { mutableStateOf(false) }
            var editFavs by remember { mutableStateOf(false) }
            var editGroups by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.vantaview.app.ui.SearchToggle(query, { vm.query.value = it }, "Search channels or numbers", Modifier.weight(1f, fill = false))
                if (query.isEmpty()) {
                    FocusCard(onClick = { sortOpen = true }) { Text("Order: ${sort.label} ▾", color = Vanta.Text, fontSize = 14.sp, maxLines = 1, softWrap = false) }
                    if (!isKids) FocusCard(onClick = { editGroups = true }) { Text("Groups ✎", color = Vanta.Text, fontSize = 14.sp, maxLines = 1, softWrap = false) }
                    if (filter == Filter.Favourites && channels.size > 1 && !isKids) {
                        FocusCard(onClick = { editFavs = true }) { Text("Edit favourites", color = Vanta.Text, fontSize = 14.sp) }
                    }
                }
            }
            if (editGroups) EditGroupsDialog(vm.groupsForEditing, vm.hiddenGroups(), onSave = { o, h -> vm.saveGroupLayout(o, h); editGroups = false }, onClose = { editGroups = false })
            if (sortOpen) SortDialog(sort, onPick = { vm.setSort(it); sortOpen = false }, onClose = { sortOpen = false })
            if (editFavs) EditFavouritesDialog(channels, onSave = { vm.saveFavouriteOrder(it); editFavs = false }, onRemove = vm::toggleFavourite, onClose = { editFavs = false })
            when (state) {
                is SyncState.Running -> Text("${state.stage}…", color = Vanta.Cyan, modifier = Modifier.padding(top = 8.dp))
                is SyncState.Failed -> Text(state.message, color = Vanta.Error, modifier = Modifier.padding(top = 8.dp))
                else -> Unit
            }
            Spacer(Modifier.height(10.dp))

            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    LazyColumn(
                        Modifier.width(260.dp).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        item { FilterChip("All channels", filter == Filter.All, Modifier.fillMaxWidth()) { vm.filter.value = Filter.All } }
                        item { FilterChip("★ Favourites", filter == Filter.Favourites, Modifier.fillMaxWidth()) { vm.filter.value = Filter.Favourites } }
                        if (radioCount > 0) item { FilterChip("📻 Radio", filter == Filter.Radio, Modifier.fillMaxWidth()) { vm.filter.value = Filter.Radio } }
                        items(categories, key = { it.id }) { c ->
                            FilterChip(c.name, filter == Filter.Category(c.remoteId), Modifier.fillMaxWidth()) {
                                vm.filter.value = Filter.Category(c.remoteId)
                            }
                        }
                    }
                    ChannelList(channels, filter, query, wide = true, Modifier.weight(1f).fillMaxHeight(), pick, vm::toggleFavourite, guideTick, vm::nowNext)
                    if (previewing != null) LivePreviewPanel(preview, wide = true, onFull = { openFull(it.id) }, Modifier.weight(0.9f))
                }
            } else {
                CategoryRow(categories, filter, radioCount > 0) { vm.filter.value = it }
                Spacer(Modifier.height(10.dp))
                if (previewing != null) {
                    LivePreviewPanel(preview, wide = false, onFull = { openFull(it.id) }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                }
                ChannelList(channels, filter, query, wide = false, Modifier.fillMaxSize(), pick, vm::toggleFavourite, guideTick, vm::nowNext)
            }
        }

        // The number being typed, or a note that it wasn't found.
        val badge = when {
            typed.isNotEmpty() -> "Channel $typed"
            notFound != null -> "No channel $notFound"
            else -> null
        }
        if (badge != null) {
            Text(
                badge,
                color = if (typed.isNotEmpty()) Vanta.Cyan else Vanta.Error,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(24.dp)
                    .background(Vanta.Surface, RoundedCornerShape(12.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun Header(
    wide: Boolean,
    playlistName: String,
    providers: List<Pair<Long, String>>,
    countryLabel: String?,
    showRefresh: Boolean,
    onPickProvider: (Long) -> Unit,
    onCountries: () -> Unit,
    onRefresh: () -> Unit,
    onCatchUp: () -> Unit,
) {
    if (wide) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            com.vantaview.app.ui.PageTitle("Live TV")
            PlaylistChip(playlistName, providers, onPickProvider)
            if (countryLabel != null) CountriesChip(countryLabel, onCountries)
            Spacer(Modifier.weight(1f))
            if (showRefresh) CatchUpButton(onCatchUp)
            if (showRefresh) RefreshButton(onRefresh)
        }
    } else {
        // Phones: title and refresh on one line, playlist and countries on the next.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                com.vantaview.app.ui.PageTitle("Live TV")
                Spacer(Modifier.weight(1f))
                if (showRefresh) CatchUpButton(onCatchUp)
                Spacer(Modifier.width(8.dp))
                if (showRefresh) RefreshButton(onRefresh)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlaylistChip(playlistName, providers, onPickProvider, Modifier.weight(1f, fill = false))
                if (countryLabel != null) CountriesChip(countryLabel, onCountries, Modifier.weight(1f, fill = false))
            }
        }
    }
}

@Composable
private fun PlaylistChip(
    playlistName: String,
    providers: List<Pair<Long, String>>,
    onPickProvider: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        var open by remember { mutableStateOf(false) }
        FocusCard(onClick = { if (providers.size > 1) open = true }) {
            Text(
                playlistName + if (providers.size > 1) "  ▾" else "",
                color = Vanta.Cyan, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            providers.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onPickProvider(id); open = false })
            }
        }
    }
}

@Composable
private fun CountriesChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusCard(onClick = onClick, modifier = modifier) {
        Text("Countries: $label", color = Vanta.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Opens the Catch-up page (programmes from the last few days on channels that keep them). */
@Composable
internal fun CatchUpButton(onClick: () -> Unit) {
    FocusCard(onClick = onClick) { Text("⟲ Catch-up", color = Vanta.Text, maxLines = 1, softWrap = false) }
}

@Composable
private fun RefreshButton(onClick: () -> Unit) {
    FocusCard(onClick = onClick, padding = PaddingValues(10.dp), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Refresh, contentDescription = "Refresh playlist", tint = Vanta.Text)
    }
}

/** Reorder the playlist's groups and choose which show (this playlist and profile only). */
@Composable
private fun EditGroupsDialog(
    groups: List<CategoryEntity>,
    hidden: Set<String>,
    onSave: (List<String>, Set<String>) -> Unit,
    onClose: () -> Unit,
) {
    val order = remember { androidx.compose.runtime.mutableStateListOf(*groups.toTypedArray()) }
    val off = remember { androidx.compose.runtime.mutableStateListOf(*hidden.toTypedArray()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose, containerColor = Vanta.Panel,
        title = { Text("Groups", color = Vanta.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Move groups up or down, and turn off the ones you never watch.", color = Vanta.TextDim, fontSize = 13.sp)
                androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(order.size, key = { order[it].remoteId }) { i ->
                        val c = order[i]
                        val shown = c.remoteId !in off
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(c.name, color = if (shown) Vanta.Text else Vanta.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            FocusCard(onClick = { if (i > 0) { order.add(i - 1, order.removeAt(i)) } }, padding = PaddingValues(8.dp)) { Text("▲", color = Vanta.Text) }
                            FocusCard(onClick = { if (i < order.lastIndex) { order.add(i + 1, order.removeAt(i)) } }, padding = PaddingValues(8.dp)) { Text("▼", color = Vanta.Text) }
                            androidx.compose.material3.Switch(checked = shown, onCheckedChange = { on -> if (on) off.remove(c.remoteId) else off.add(c.remoteId) })
                        }
                    }
                }
            }
        },
        confirmButton = { com.vantaview.app.ui.GradientButton("Save", onClick = { onSave(order.map { it.remoteId }, off.toSet()) }) },
        dismissButton = {
            Row {
                androidx.compose.material3.TextButton(onClick = { onSave(emptyList(), emptySet()) }) { Text("Reset") }
                androidx.compose.material3.TextButton(onClick = onClose) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun SortDialog(current: ChannelSort, onPick: (ChannelSort) -> Unit, onClose: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose, containerColor = Vanta.Panel,
        title = { Text("Order channels by", color = Vanta.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ChannelSort.entries.forEach { s ->
                    FocusCard(onClick = { onPick(s) }, selected = s == current, modifier = Modifier.fillMaxWidth()) {
                        Text(s.label + if (s == ChannelSort.MINE) " (favourites)" else "", color = if (s == current) Vanta.Cyan else Vanta.Text)
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("Close") } },
    )
}

/** Put favourites in your own order, or take some off. */
@Composable
private fun EditFavouritesDialog(
    rows: List<ChannelRow>, onSave: (List<ChannelRow>) -> Unit, onRemove: (ChannelRow) -> Unit, onClose: () -> Unit,
) {
    var list by remember { mutableStateOf(rows.filter { it.isFav }) }
    fun move(i: Int, by: Int) {
        val j = i + by
        if (j !in list.indices) return
        list = list.toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose, containerColor = Vanta.Panel,
        title = { Text("Edit favourites", color = Vanta.Text) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(list.size, key = { list[it].channel.id }) { i ->
                    val r = list[i]
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${i + 1}.", color = Vanta.TextDim, modifier = Modifier.width(28.dp))
                        Text(ChannelNames.display(r.channel.name), color = Vanta.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        FocusCard(onClick = { move(i, -1) }, padding = PaddingValues(8.dp)) { Text("▲", color = Vanta.Text) }
                        FocusCard(onClick = { move(i, 1) }, padding = PaddingValues(8.dp)) { Text("▼", color = Vanta.Text) }
                        FocusCard(onClick = { onRemove(r); list = list - r }, padding = PaddingValues(8.dp)) { Text("✕", color = Vanta.Error) }
                    }
                }
            }
        },
        confirmButton = { com.vantaview.app.ui.GradientButton("Save order", onClick = { onSave(list) }) },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
internal fun CategoryRow(categories: List<CategoryEntity>, filter: Filter, radio: Boolean = false, onSelect: (Filter) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { FilterChip("All", filter == Filter.All) { onSelect(Filter.All) } }
        item { FilterChip("★ Favourites", filter == Filter.Favourites) { onSelect(Filter.Favourites) } }
        if (radio) item { FilterChip("📻 Radio", filter == Filter.Radio) { onSelect(Filter.Radio) } }
        items(categories, key = { it.id }) { c ->
            FilterChip(c.name, filter == Filter.Category(c.remoteId)) { onSelect(Filter.Category(c.remoteId)) }
        }
    }
}

@Composable
internal fun FilterChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusCard(onClick = onClick, selected = selected, modifier = modifier) {
        Text(
            label,
            color = if (selected) Vanta.Cyan else Vanta.Text,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ChannelList(
    channels: List<ChannelRow>,
    filter: Filter,
    query: String,
    wide: Boolean,
    modifier: Modifier,
    onPlay: (Long) -> Unit,
    onToggleFav: (ChannelRow) -> Unit,
    guideTick: Int,
    nowNext: suspend (ChannelEntity) -> List<ProgrammeEntity>,
) {
    if (channels.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                when {
                    query.isNotBlank() -> "No channels match \"$query\"."
                    filter is Filter.Favourites -> "No favourites yet. Press the star on a channel to add it."
                    else -> "No channels here yet."
                },
                color = Vanta.TextDim,
            )
        }
        return
    }
    val listState = rememberLazyListState()
    // Back to the top when the category or search changes.
    LaunchedEffect(filter, query) { listState.scrollToItem(0) }
    Row(modifier) {
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(channels, key = { it.channel.id }) { row -> ChannelItem(row, wide, onPlay, onToggleFav, guideTick, nowNext) }
        }
        if (channels.size > 60) FastScroller(listState, channels)
    }
}

@Composable
private fun ChannelItem(
    row: ChannelRow,
    wide: Boolean,
    onPlay: (Long) -> Unit,
    onToggleFav: (ChannelRow) -> Unit,
    guideTick: Int,
    nowNext: suspend (ChannelEntity) -> List<ProgrammeEntity>,
) {
    val name = row.channel.name
    if (ChannelNames.isDivider(name)) {
        // The provider's own heading between groups of channels: shown as a heading, not played.
        Text(
            ChannelNames.dividerTitle(name),
            color = Vanta.Cyan,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
        )
        return
    }
    val event = ChannelNames.isEventOnly(name)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // Favourite star on the left, away from the fast-scroll handle on the right.
        FocusCard(
            onClick = { onToggleFav(row) },
            padding = PaddingValues(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Star,
                contentDescription = if (row.isFav) "Remove from favourites" else "Add to favourites",
                tint = if (row.isFav) androidx.compose.ui.graphics.Color(0xFFFFEA00) else Vanta.TextDim.copy(alpha = 0.45f),
            )
        }
        com.vantaview.app.ui.FillCard(onClick = { onPlay(row.channel.id) }, modifier = Modifier.weight(1f)) { textColour, dimColour ->
            // Looks with a fill colour (Broadcast, Classic) turn the focused row's text dark.
            val filled = textColour != Vanta.Text
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(if (event) 0.65f else 1f)) {
                Text(
                    row.channel.number.toString(),
                    color = dimColour, fontSize = 13.sp, modifier = Modifier.width(44.dp), maxLines = 1,
                )
                Box(
                    Modifier.size(width = 56.dp, height = 36.dp).clip(RoundedCornerShape(6.dp)).background(Vanta.Bg),
                    contentAlignment = Alignment.Center,
                ) {
                    if (row.channel.logo != null) {
                        AsyncImage(
                            model = row.channel.logo,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(2.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        ChannelNames.display(name),
                        color = textColour, fontSize = 16.sp,
                        maxLines = if (wide) 1 else 2, overflow = if (wide) TextOverflow.Clip else TextOverflow.Ellipsis,
                        modifier = if (wide) Modifier.scrollWhenFocused() else Modifier,
                    )
                    if (event) Text("Event only · plays while an event is on", color = dimColour, fontSize = 12.sp, maxLines = 1)
                    NowNextLine(row.channel, guideTick, nowNext, if (filled) textColour else null, if (filled) dimColour else null)
                }
            }
        }
    }
}

/** A handle down the right-hand side: drag (or tap the track) to jump through a long list. */
@Composable
private fun FastScroller(state: LazyListState, channels: List<ChannelRow>) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    val fraction by remember(channels) {
        derivedStateOf { state.firstVisibleItemIndex.toFloat() / (channels.size - 1).coerceAtLeast(1) }
    }
    BoxWithConstraints(Modifier.width(28.dp).fillMaxHeight().padding(start = 4.dp)) {
        val trackPx = with(density) { maxHeight.toPx() }
        val thumbH = 44.dp
        val thumbPx = with(density) { thumbH.toPx() }
        fun jump(y: Float) {
            val f = ((y - thumbPx / 2) / (trackPx - thumbPx)).coerceIn(0f, 1f)
            val target = (f * (channels.size - 1)).toInt()
            scope.launch { state.scrollToItem(target) }
        }
        Box(
            Modifier.fillMaxSize()
                .pointerInput(channels) { detectTapGestures { jump(it.y) } }
                .pointerInput(channels) {
                    detectVerticalDragGestures(
                        onDragStart = { dragging = true; jump(it.y) },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onVerticalDrag = { change, _ -> change.consume(); jump(change.position.y) },
                    )
                }
        ) {
            val top = with(density) { (fraction * (trackPx - thumbPx)).toDp() }
            Box(
                Modifier.align(Alignment.TopEnd).offset(y = top).width(if (dragging) 10.dp else 6.dp).height(thumbH)
                    .clip(RoundedCornerShape(5.dp)).background(if (dragging) Vanta.Cyan else Vanta.TextDim.copy(alpha = 0.6f))
            )
        }
    }
}

private val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK)

/** "Now: title" with a thin progress bar, then "Next: 20:00 title". Nothing when the channel has no guide. */
@Composable
internal fun NowNextLine(
    channel: ChannelEntity, tick: Int, nowNext: suspend (ChannelEntity) -> List<ProgrammeEntity>,
    nowColour: androidx.compose.ui.graphics.Color? = null, nextColour: androidx.compose.ui.graphics.Color? = null,
) {
    if (channel.epgChannelId == null) return
    val progs by produceState(initialValue = emptyList<ProgrammeEntity>(), channel.id, tick) {
        value = runCatching { nowNext(channel) }.getOrDefault(emptyList())
    }
    val t = System.currentTimeMillis()
    val now = progs.firstOrNull()?.takeIf { it.start <= t }
    val next = if (now != null) progs.getOrNull(1) else progs.firstOrNull()
    val after = if (now != null) progs.getOrNull(2) else progs.getOrNull(1)
    if (now == null && next == null) return
    Column(Modifier.padding(top = 3.dp)) {
        if (now != null) {
            Text(
                "Now: ${now.title}", color = nowColour ?: Vanta.Cyan, fontSize = 13.sp, maxLines = 1,
                overflow = TextOverflow.Clip, modifier = Modifier.scrollWhenFocused(),
            )
            val f = ((t - now.start).toFloat() / (now.stop - now.start).coerceAtLeast(1)).coerceIn(0f, 1f)
            Box(Modifier.padding(vertical = 3.dp).width(140.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Vanta.SurfaceHigh)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(nowColour ?: Vanta.Cyan))
            }
        }
        if (next != null) {
            Text("Next: ${clock.format(java.util.Date(next.start))} ${next.title}", color = nextColour ?: Vanta.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (after != null) {
            Text("After: ${clock.format(java.util.Date(after.start))} ${after.title}", color = nextColour ?: Vanta.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
