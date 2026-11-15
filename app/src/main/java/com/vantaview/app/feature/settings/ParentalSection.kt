// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.vantaview.app.feature.settings

import com.vantaview.app.ui.tvTextField
import com.vantaview.app.ui.tvSafe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import com.vantaview.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vantaview.app.data.db.CategoryEntity
import com.vantaview.app.data.db.ProviderEntity
import com.vantaview.app.data.db.VantaDb
import com.vantaview.app.data.history.WatchHistory
import com.vantaview.app.data.profile.ParentalControl
import com.vantaview.app.data.profile.ProfileRepository
import com.vantaview.app.data.profile.ProfileSession
import com.vantaview.app.feature.profiles.PinDialog
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.GradientButton
import com.vantaview.app.ui.Vanta
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ParentalViewModel @Inject constructor(
    val parental: ParentalControl,
    private val profiles: ProfileRepository,
    private val session: ProfileSession,
    private val history: WatchHistory,
    private val db: VantaDb,
) : ViewModel() {
    val unlocked: StateFlow<Boolean> = parental.unlocked
    val changes: StateFlow<Int> = parental.changes
    val hasParentPin: Boolean get() = profiles.hasParentPin
    val profileId: Long get() = session.id

    fun check(pin: String) = profiles.checkParentPin(pin)
    fun setPin(pin: String) { profiles.setParentPin(pin); parental.unlock() }

    private val _providers = MutableStateFlow<List<ProviderEntity>>(emptyList())
    val providers: StateFlow<List<ProviderEntity>> = _providers
    init { viewModelScope.launch { _providers.value = db.providers().allFor(session.id) } }

    suspend fun categories(providerId: Long): List<CategoryEntity> = db.content().categories(providerId)

    /** Live TV: forget the last channel (Home's preview and Start on last channel). */
    fun clearLive() = session.setLastChannel(0)
    /** Films and series: Continue watching and resume points. */
    fun clearVod() = history.clear()
}

private enum class Clear(val title: String, val detail: String) {
    LIVE("Live TV", "Forgets the last channel you watched."),
    VOD("Films and series", "Clears Continue watching and where you stopped in each title."),
    ALL("Everything", "Clears both."),
}

/** Settings › Parental control. Changes need the parent PIN; clearing history and the profile PIN don't. */
@Composable
fun ParentalSection(onEditProfile: (Long) -> Unit, vm: ParentalViewModel = hiltViewModel()) {
    val unlocked by vm.unlocked.collectAsStateWithLifecycle()
    vm.changes.collectAsStateWithLifecycle().value // re-read settings on every change
    val p = vm.parental
    var askPin by remember { mutableStateOf(false) }
    var hasPin by remember { mutableStateOf(vm.hasParentPin) }
    var clear by remember { mutableStateOf<Clear?>(null) }
    var cleared by remember { mutableStateOf<String?>(null) }

    when {
        !hasPin -> Section("Parent PIN", alwaysOpen = true) {
            Text(
                "Set a 4-digit parent PIN first. It guards these settings, Kids profiles and changing profiles.",
                color = Vanta.TextDim, fontSize = 14.sp,
            )
            NewPin("Set PIN") { vm.setPin(it); hasPin = true }
        }
        !unlocked -> Section("Locked", alwaysOpen = true) {
            Text(
                "Enter the parent PIN to change parental control. Adult categories then show, and Sources opens, until VantaView closes.",
                color = Vanta.TextDim, fontSize = 14.sp,
            )
            GradientButton("Enter PIN", onClick = { askPin = true })
        }
        else -> {
            Section("Unlocked") {
                Text("Adult categories show and Sources is open until VantaView closes.", color = Vanta.TextDim, fontSize = 14.sp)
                FocusCard(onClick = { p.lock() }) { Text("Lock now", color = Vanta.Text) }
            }
            Section("What the PIN locks") {
                Toggle("Hide adult categories", "They don't show anywhere until the PIN is entered", p.hideAdult) { p.hideAdult = it }
                Toggle("Lock Sources", "Ask for the PIN before playlists, add-ons and debrid can be changed", p.lockSources) { p.lockSources = it }
            }
            AdultCategories(vm)
            Section("Change the parent PIN") { NewPin("Change PIN") { vm.setPin(it) } }
        }
    }

    Section("Your profile PIN") {
        Text("Add, change or remove the PIN that opens your profile.", color = Vanta.TextDim, fontSize = 14.sp)
        FocusCard(onClick = { onEditProfile(vm.profileId) }) { Text("Profile PIN", color = Vanta.Text) }
    }

    Section("Clear watch history") {
        Text("For this profile only.", color = Vanta.TextDim, fontSize = 14.sp)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Clear.entries.forEach { c -> FocusCard(onClick = { clear = c; cleared = null }) { Text(c.title, color = Vanta.Text, maxLines = 1, softWrap = false) } }
        }
        cleared?.let { Text(it, color = Vanta.Cyan, fontSize = 13.sp) }
    }

    if (askPin) PinDialog(
        title = "Parent PIN", message = "Enter the parent PIN.", check = vm::check,
        onOk = { askPin = false; p.unlock() }, onCancel = { askPin = false },
    )

    clear?.let { c ->
        AlertDialog(
            onDismissRequest = { clear = null },
            containerColor = Vanta.Panel,
            title = { Text(com.vantaview.app.ui.tr("Clear {0} history?", com.vantaview.app.ui.tr(c.title).lowercase()), color = Vanta.Text) },
            text = { Text(com.vantaview.app.ui.tr(c.detail) + " " + com.vantaview.app.ui.tr("This can't be undone."), color = Vanta.TextDim) },
            confirmButton = {
                GradientButton("Clear", onClick = {
                    if (c != Clear.VOD) vm.clearLive()
                    if (c != Clear.LIVE) vm.clearVod()
                    cleared = "${c.title} history cleared."
                    clear = null
                })
            },
            dismissButton = { TextButton(onClick = { clear = null }) { Text("Cancel") } },
        )
    }
}

/** Two boxes for a new 4-digit PIN. */
@Composable
private fun NewPin(button: String, onSet: (String) -> Unit) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        PinBox("New PIN", a, Modifier.weight(1f)) { a = it; msg = null }
        PinBox("Again", b, Modifier.weight(1f)) { b = it; msg = null }
    }
    GradientButton(button, onClick = {
        when {
            a.length != 4 -> msg = "The PIN must be 4 digits."
            a != b -> msg = "The two PINs don't match."
            else -> { onSet(a); a = ""; b = ""; msg = "PIN saved." }
        }
    })
    msg?.let { Text(it, color = if (it == "PIN saved.") Vanta.Cyan else Vanta.Error, fontSize = 13.sp) }
}

@Composable
private fun PinBox(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(4)) },
        label = { Text(label) }, singleLine = true, modifier = (modifier).then(Modifier.tvTextField()),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = (KeyboardOptions(keyboardType = KeyboardType.NumberPassword)).tvSafe(),
    )
}

@Composable
private fun Toggle(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Vanta.Text)
            Text(detail, color = Vanta.TextDim, fontSize = 13.sp)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}

/** Per playlist: tick the categories that count as adult. Ones found automatically start ticked. */
@Composable
private fun AdultCategories(vm: ParentalViewModel) {
    val providers by vm.providers.collectAsStateWithLifecycle()
    if (providers.isEmpty()) return
    var open by remember { mutableStateOf<Long?>(null) }
    Section("Adult categories") {
        Text(
            "Found automatically from names like Adult or XXX, and the playlist's own adult flag. Tick or untick to change them.",
            color = Vanta.TextDim, fontSize = 14.sp,
        )
        providers.forEach { pr ->
            FocusCard(onClick = { open = if (open == pr.id) null else pr.id }, selected = open == pr.id, modifier = Modifier.fillMaxWidth()) {
                Text((if (open == pr.id) "▾ " else "▸ ") + pr.name, color = Vanta.Text)
            }
            if (open == pr.id) CategoryTicks(vm, pr.id)
        }
    }
}

@Composable
private fun CategoryTicks(vm: ParentalViewModel, providerId: Long) {
    var cats by remember(providerId) { mutableStateOf<List<CategoryEntity>?>(null) }
    var query by remember(providerId) { mutableStateOf("") }
    LaunchedEffect(providerId) { cats = vm.categories(providerId) }
    val p = vm.parental
    vm.changes.collectAsStateWithLifecycle().value
    val all = cats ?: run { Text("Loading…", color = Vanta.TextDim); return }
    OutlinedTextField(
        value = query, onValueChange = { query = it }, singleLine = true,
        label = { Text("Find a category") }, modifier = (Modifier.fillMaxWidth()).then(Modifier.tvTextField()),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default.tvSafe(),
    )
    // Adult ones first, so they're easy to check.
    val shown = all.filter { query.isBlank() || it.name.contains(query.trim(), true) }
        .sortedBy { if (p.isAdult(it)) 0 else 1 }
        .take(if (query.isBlank()) 60 else 200)
    shown.forEach { c ->
        val adult = p.isAdult(c)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = adult, onCheckedChange = { p.setAdult(c, it) })
            Column(Modifier.weight(1f)) {
                Text(c.name, color = Vanta.Text, fontSize = 14.sp)
                if (p.isAuto(c)) Text("Found automatically", color = Vanta.TextDim, fontSize = 12.sp)
            }
        }
    }
    if (query.isBlank() && all.size > shown.size) {
        Text("Showing ${shown.size} of ${all.size}. Search to find others.", color = Vanta.TextDim, fontSize = 12.sp)
    }
}
