// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.vantaview.app.feature.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vantaview.app.data.ChannelNames
import com.vantaview.app.data.db.CategoryEntity
import com.vantaview.app.data.db.ChannelPick
import com.vantaview.app.data.db.HiddenChannelEntity
import com.vantaview.app.data.db.ProviderEntity
import com.vantaview.app.data.db.VantaDb
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.GradientButton
import com.vantaview.app.ui.Text
import com.vantaview.app.ui.Vanta
import com.vantaview.app.ui.tr
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Show or hide channels in bulk: tick channels (or a whole group), then Hide or Show them all at once. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class ChannelVisibilityViewModel @Inject constructor(
    private val db: VantaDb,
    private val session: com.vantaview.app.data.profile.ProfileSession,
) : ViewModel() {
    val providers = MutableStateFlow<List<ProviderEntity>>(emptyList())
    val provider = MutableStateFlow<Long?>(null)
    val category = MutableStateFlow<String?>(null)
    val query = MutableStateFlow("")
    val hiddenOnly = MutableStateFlow(false)
    val selected = MutableStateFlow<Set<String>>(emptySet())

    val categories: StateFlow<List<CategoryEntity>> = provider.flatMapLatest { p ->
        if (p == null) flowOf(emptyList()) else db.content().observeCategories(p)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val channels: StateFlow<List<ChannelPick>> = combine(provider, category, query.debounce(250), hiddenOnly) { p, c, q, h -> Quad(p, c, q, h) }
        .flatMapLatest { (p, c, q, h) -> if (p == null) flowOf(emptyList()) else db.content().observePicks(p, c, q.trim(), if (h) 1 else 0) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hiddenCount: StateFlow<Int> = provider.flatMapLatest { p -> if (p == null) flowOf(0) else db.content().observeHiddenCount(p) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private data class Quad(val p: Long?, val c: String?, val q: String, val h: Boolean)

    fun load(preferred: Long?) {
        viewModelScope.launch {
            val list = db.providers().allFor(session.id).filter { it.channelCount > 0 }
            providers.value = list
            provider.value = list.firstOrNull { it.id == preferred }?.id ?: list.firstOrNull()?.id
        }
    }

    fun pickProvider(id: Long) { provider.value = id; category.value = null; selected.value = emptySet() }
    fun pickCategory(c: String?) { category.value = c; selected.value = emptySet() }
    fun toggle(remoteId: String) { selected.value = selected.value.let { if (remoteId in it) it - remoteId else it + remoteId } }
    fun selectAllShown() { selected.value = channels.value.map { it.channel.remoteId }.toSet() }
    fun clear() { selected.value = emptySet() }

    /** Hides ([hide]) or shows every ticked channel. */
    fun apply(hide: Boolean) {
        val p = provider.value ?: return
        val ids = selected.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            if (hide) ids.chunked(500).forEach { c -> db.content().hide(c.map { HiddenChannelEntity(p, it) }) }
            else ids.chunked(500).forEach { db.content().unhide(p, it) }
            selected.value = emptySet()
        }
    }
}

/** The show or hide screen, full screen over the page it's opened from. [providerId] is the playlist to start on. */
@Composable
fun ChannelVisibilityDialog(providerId: Long?, onClose: () -> Unit, vm: ChannelVisibilityViewModel = hiltViewModel()) {
    LaunchedEffect(providerId) { vm.load(providerId) }
    val providers by vm.providers.collectAsStateWithLifecycle()
    val provider by vm.provider.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val category by vm.category.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val hiddenOnly by vm.hiddenOnly.collectAsStateWithLifecycle()
    val hiddenCount by vm.hiddenCount.collectAsStateWithLifecycle()

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Vanta.Page).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Show or hide channels", color = Vanta.Text, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Text(
                        "Tick channels, then Hide or Show them all at once. Hidden channels are left out of Live TV, the TV Guide, search and channel up and down.",
                        color = Vanta.TextDim, fontSize = 13.sp,
                    )
                }
                FocusCard(onClick = onClose) { Text("Done", color = Vanta.Text) }
            }
            if (providers.isEmpty()) {
                Text("No playlists with channels yet.", color = Vanta.TextDim)
                return@Column
            }
            if (providers.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(providers, key = { it.id }) { p ->
                    FocusCard(onClick = { vm.pickProvider(p.id) }, selected = p.id == provider) {
                        Text(p.name, color = if (p.id == provider) Vanta.Cyan else Vanta.Text, maxLines = 1)
                    }
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FocusCard(onClick = { vm.pickCategory(null) }, selected = category == null && !hiddenOnly) {
                        Text("All groups", color = if (category == null) Vanta.Cyan else Vanta.Text, maxLines = 1)
                    }
                }
                item {
                    FocusCard(onClick = { vm.hiddenOnly.value = !hiddenOnly; vm.clear() }, selected = hiddenOnly) {
                        Text(tr("Hidden ({0})", hiddenCount), color = if (hiddenOnly) Vanta.Cyan else Vanta.Text, maxLines = 1)
                    }
                }
                items(categories, key = { it.id }) { c ->
                    FocusCard(onClick = { vm.pickCategory(c.remoteId) }, selected = category == c.remoteId) {
                        Text(c.name, color = if (category == c.remoteId) Vanta.Cyan else Vanta.Text, maxLines = 1)
                    }
                }
            }
            com.vantaview.app.ui.OutlinedTextField(
                value = query, onValueChange = { vm.query.value = it }, singleLine = true,
                label = { Text("Search channels or numbers") }, modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FocusCard(onClick = vm::selectAllShown) { Text(tr("Tick all {0}", channels.size), color = Vanta.Text, maxLines = 1) }
                if (selected.isNotEmpty()) FocusCard(onClick = vm::clear) { Text("Untick all", color = Vanta.Text, maxLines = 1) }
                GradientButton(tr("Hide {0}", selected.size), onClick = { vm.apply(hide = true) }, enabled = selected.isNotEmpty())
                GradientButton(tr("Show {0}", selected.size), onClick = { vm.apply(hide = false) }, enabled = selected.isNotEmpty())
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(channels, key = { it.channel.id }) { row ->
                    val on = row.channel.remoteId in selected
                    FocusCard(onClick = { vm.toggle(row.channel.remoteId) }, selected = on, modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (on) "☑" else "☐", color = if (on) Vanta.Cyan else Vanta.TextDim, fontSize = 18.sp, modifier = Modifier.width(30.dp))
                            Text(row.channel.number.toString(), color = Vanta.TextDim, fontSize = 13.sp, modifier = Modifier.width(56.dp))
                            Text(
                                ChannelNames.display(row.channel.name), color = Vanta.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).alpha(if (row.hidden) 0.5f else 1f),
                            )
                            if (row.hidden) Box(Modifier.padding(start = 8.dp)) { Text("Hidden", color = Vanta.Error, fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
    }
}
