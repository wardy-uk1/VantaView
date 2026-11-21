// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.vantaview.app.feature.country

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vantaview.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
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
import com.vantaview.app.data.country.CountryDetect
import com.vantaview.app.data.country.CountryScan
import com.vantaview.app.data.country.CountryStore
import com.vantaview.app.data.db.ProviderEntity
import com.vantaview.app.data.profile.ProfileRepository
import com.vantaview.app.data.profile.ProfileSession
import com.vantaview.app.data.repo.ProviderRepository
import com.vantaview.app.data.repo.SyncState
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.GradientButton
import com.vantaview.app.ui.Vanta
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One open country chooser. [firstTime] is the question asked after a playlist is added. */
data class CountryPrompt(
    val provider: ProviderEntity,
    val scan: CountryScan,
    val selected: Set<String>,
    val firstTime: Boolean,
)

@HiltViewModel
class CountryViewModel @Inject constructor(
    private val store: CountryStore,
    private val session: ProfileSession,
    private val repo: ProviderRepository,
    profiles: ProfileRepository,
) : ViewModel() {
    private val _prompt = MutableStateFlow<CountryPrompt?>(null)
    val prompt: StateFlow<CountryPrompt?> = _prompt

    init {
        // Ask once per playlist, after its channels have loaded.
        viewModelScope.launch {
            combine(repo.observe(), repo.syncStates, profiles.observeCurrent(), store.changes) { list, sync, p, _ ->
                if (p == null || p.isKids) null
                else list.firstOrNull {
                    it.channelCount > 0 && sync[it.id] !is SyncState.Running && !store.asked(p.id, it.id)
                }
            }.collect { p ->
                if (p == null || _prompt.value != null) return@collect
                val scan = store.scan(p)
                if (scan.countries.size < 2) { store.markAsked(session.id, p.id); return@collect }
                val device = CountryDetect.deviceCountry()
                _prompt.value = CountryPrompt(p, scan, setOfNotNull(device?.takeIf { it in scan.counts }), firstTime = true)
            }
        }
        // Opened by hand from Live TV or Sources.
        viewModelScope.launch {
            store.editRequest.collect { id ->
                if (id == null) return@collect
                store.editRequest.value = null
                val p = repo.observe().first().firstOrNull { it.id == id } ?: return@collect
                val scan = store.scan(p)
                _prompt.value = CountryPrompt(p, scan, store.chosen(session.id, p.id) ?: scan.counts.keys, firstTime = false)
            }
        }
    }

    fun toggle(code: String) {
        val p = _prompt.value ?: return
        _prompt.value = p.copy(selected = if (code in p.selected) p.selected - code else p.selected + code)
    }

    fun save() {
        val p = _prompt.value ?: return
        val all = p.scan.counts.keys
        store.setChosen(session.id, p.provider.id, if (p.selected.containsAll(all)) null else p.selected)
        _prompt.value = null
    }

    fun showAll() {
        val p = _prompt.value ?: return
        store.setChosen(session.id, p.provider.id, null)
        _prompt.value = null
    }

    fun dismiss() {
        _prompt.value?.let { if (it.firstTime) store.markAsked(session.id, it.provider.id) }
        _prompt.value = null
    }
}

/** Shows the country chooser whenever one is due. Place once, above the app's pages. */
@Composable
fun CountryChooserHost(vm: CountryViewModel = hiltViewModel()) {
    val prompt by vm.prompt.collectAsStateWithLifecycle()
    val p = prompt ?: return
    Dialog(onDismissRequest = vm::dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CountryChooser(p, vm::toggle, vm::save, vm::showAll, vm::dismiss)
    }
}

@Composable
private fun CountryChooser(
    p: CountryPrompt,
    onToggle: (String) -> Unit,
    onSave: () -> Unit,
    onShowAll: () -> Unit,
    onCancel: () -> Unit,
) {
    val device = remember { CountryDetect.deviceCountry() }
    // Device country first, then by size; channels with no country last.
    val codes = remember(p.scan) {
        p.scan.countries.sortedWith(compareBy<String> { it != device }.thenByDescending { p.scan.counts[it] ?: 0 }) +
            listOfNotNull(CountryDetect.OTHER.takeIf { it in p.scan.counts })
    }
    val wide = LocalConfiguration.current.screenWidthDp >= 720
    // TVs are only 540 dp tall: keep the list short enough that Save stays on screen.
    val listMax = (LocalConfiguration.current.screenHeightDp - 270).coerceIn(140, 360).dp
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(100); runCatching { first.requestFocus() } }
    val total = p.scan.counts.values.sum()
    Column(
        Modifier.padding(16.dp).widthIn(max = 760.dp).fillMaxWidth()
            .background(Vanta.DialogBg, RoundedCornerShape(24.dp)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(if (wide) 10.dp else 14.dp),
    ) {
        Text(
            if (p.firstTime) "${p.provider.name} added · ${"%,d".format(total)} channels in ${p.scan.countries.size} countries"
            else "Countries · ${p.provider.name}",
            color = Vanta.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
        )
        Text(
            if (p.firstTime) "Do you want to choose which countries show? You can change this any time from Live TV or Sources."
            else "Switch countries on or off. Hidden countries don't show in Live TV or channel search.",
            color = Vanta.TextDim,
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (wide) 2 else 1),
            modifier = Modifier.heightIn(max = listMax),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(codes, key = { it }) { code ->
                val on = code in p.selected
                FocusCard(
                    onClick = { onToggle(code) }, selected = on,
                    modifier = Modifier.fillMaxWidth().then(if (code == codes.firstOrNull()) Modifier.focusRequester(first) else Modifier),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            CountryDetect.displayName(code) + if (code == device) "  ·  " + com.vantaview.app.ui.tr("This device") else "",
                            color = Vanta.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        Text("%,d".format(p.scan.counts[code] ?: 0), color = Vanta.TextDim, fontSize = 13.sp)
                        Spacer(Modifier.width(12.dp))
                        Switch(on)
                    }
                }
            }
        }
        // Buttons wrap onto a second line on narrow screens rather than squeezing their words.
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GradientButton(if (p.firstTime) "Save choices" else "Save", onClick = onSave, enabled = p.selected.isNotEmpty())
            FocusCard(onClick = onShowAll, contentAlignment = Alignment.Center) { Text("Show all countries", color = Vanta.Text, maxLines = 1, softWrap = false) }
            if (!p.firstTime) FocusCard(onClick = onCancel, contentAlignment = Alignment.Center) { Text("Cancel", color = Vanta.TextDim, maxLines = 1, softWrap = false) }
        }
    }
}

/** A small read-only switch; the whole row is the button, so it works with a TV remote. */
@Composable
private fun Switch(on: Boolean) {
    Box(
        Modifier.size(width = 44.dp, height = 26.dp).clip(CircleShape)
            .background(if (on) Vanta.Accent else Vanta.SurfaceHigh).padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(if (on) Vanta.OnAccent else Vanta.TextDim))
    }
}
