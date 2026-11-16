// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import com.vantaview.app.ui.OutlinedTextField
import androidx.compose.material3.Switch
import com.vantaview.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.vantaview.app.data.backup.RestoreMode
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.GradientButton
import com.vantaview.app.ui.Vanta
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Ask { BACKUP, AUTO }

@Composable
fun SettingsScreen(onEditProfile: (Long) -> Unit = {}, vm: SettingsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val lastAuto by vm.lastAuto.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var ask by remember { mutableStateOf<Ask?>(null) }
    val when_ = SimpleDateFormat("EEE d MMM, HH:mm", com.vantaview.app.ui.I18n.locale)

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.writeTo(uri, context)
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.open(uri)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            vm.setFolder(uri)
        }
    }

    val backupContent: @Composable () -> Unit = {
        Section("Backup and restore") {
            Text(
                "Saves your profiles, playlists and logins, favourites, watch history, add-ons, debrid accounts and settings. " +
                    "Logins are locked with a backup password. Channels and the TV guide download again after restoring.",
                color = Vanta.TextDim, fontSize = 14.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GradientButton("Back up now", onClick = { ask = Ask.BACKUP })
                FocusCard(onClick = {
                    try { openFile.launch(arrayOf("*/*")) }
                    catch (_: ActivityNotFoundException) { /* TVs without a file picker use the list below */ }
                }) { Text("Restore from a file", color = Vanta.Text) }
            }
        }

        Section("Automatic backups") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Back up after every change", color = Vanta.Text)
                    Text(
                        "When you add, edit or remove a playlist or profile. Keeps the last 5." +
                            if (lastAuto > 0) "\nLast one: ${when_.format(Date(lastAuto))}" else "",
                        color = Vanta.TextDim, fontSize = 13.sp,
                    )
                }
                Switch(checked = ui.autoEnabled, onCheckedChange = { on ->
                    if (on && !ui.hasPassword) ask = Ask.AUTO else vm.setAuto(on)
                })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                FocusCard(onClick = {
                    try { pickFolder.launch(null) } catch (_: ActivityNotFoundException) { vm.setFolder(null) }
                }) { Text("Choose folder", color = Vanta.Text) }
                Text(
                    ui.folderName?.let { "Saving to: $it" } ?: "Saving in the app's folder (removed if the app is uninstalled)",
                    color = Vanta.TextDim, fontSize = 13.sp, modifier = Modifier.weight(1f),
                )
            }
            if (ui.hasPassword) {
                FocusCard(onClick = vm::forgetPassword) { Text("Forget the backup password", color = Vanta.Text) }
            }
        }

        if (ui.stored.isNotEmpty()) {
            Section("Backups on this device") {
                ui.stored.take(10).forEach { b ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (b.name.startsWith("vantaview-auto-")) "Automatic backup" else "Backup", color = Vanta.Text)
                            Text(when_.format(Date(b.modified)), color = Vanta.TextDim, fontSize = 13.sp)
                        }
                        FocusCard(onClick = { vm.open(b.uri) }) { Text("Restore", color = Vanta.Text) }
                    }
                }
            }
        }

        ui.busy?.let { Text(it, color = Vanta.Cyan) }
        ui.message?.let { Text(it, color = if (ui.isError) Vanta.Error else Vanta.Cyan) }
    }

    SettingsLayout { section -> androidx.compose.runtime.key(section) { SectionGroup {
        when (section) {
            SettingsSection.WEATHER -> WeatherSection(vm)
            SettingsSection.TRAKT -> TraktSection()
            SettingsSection.BACKUP -> backupContent()
            SettingsSection.ABOUT -> HelpAbout()
            SettingsSection.PLAYBACK -> PlaybackSection(vm.playback)
            SettingsSection.PARENTAL -> ParentalSection(onEditProfile)
            SettingsSection.APPEARANCE -> { AppearanceSection(vm.appearance); ScreensaverSection(vm.playback) }
            SettingsSection.UPDATES -> UpdatesSection(vm.updates)
            SettingsSection.SOURCES -> { SourcesSection(); SpeedTestSection() }
            SettingsSection.CHANNELS -> ChannelsSection(vm.playback)
            SettingsSection.SUBTITLES -> { SubtitlesSection(vm.playback); AiSubtitlesSection(vm.aiSubs) }
            else -> ComingSoon(section)
        }
    } } }

    ask?.let { purpose ->
        PasswordDialog(
            purpose = purpose,
            remembered = vm.rememberedPassword,
            onDismiss = { ask = null },
            onSaveFile = { pw, remember ->
                ask = null
                vm.prepare(pw, remember) {
                    try { saveFile.launch(vm.fileName()) } catch (_: ActivityNotFoundException) { vm.saveInAppFolder() }
                }
            },
            onSendPhone = { pw, remember -> ask = null; vm.prepare(pw, remember) { vm.shareToPhone() } },
            onTurnOnAuto = { pw -> ask = null; vm.setAuto(true, pw) },
        )
    }

    ui.pending?.let { p ->
        var pw by remember(p.info.createdAt) { mutableStateOf(vm.rememberedPassword.orEmpty()) }
        AlertDialog(
            onDismissRequest = vm::cancelRestore,
            containerColor = Vanta.Panel,
            title = { Text("Restore this backup?", color = Vanta.Text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Made ${when_.format(Date(p.info.createdAt))}", color = Vanta.TextDim)
                    Text(
                        "${p.info.profiles} profile(s), ${p.info.playlists} playlist(s), ${p.info.addons} add-on(s)",
                        color = Vanta.TextDim,
                    )
                    if (p.info.locked) {
                        OutlinedTextField(
                            value = pw, onValueChange = { pw = it }, singleLine = true,
                            label = { Text("Backup password") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        )
                        if (p.wrongPassword) Text("That password isn't right.", color = Vanta.Error)
                        Text("Leave it blank to restore everything except logins.", color = Vanta.TextDim, fontSize = 12.sp)
                    }
                    Text(
                        "Replace: this device ends up exactly like the backup.\nAdd: keeps what's here and adds what's missing.",
                        color = Vanta.TextDim, fontSize = 13.sp,
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.restore(pw, RestoreMode.MERGE) }) { Text("Add") }
                    GradientButton("Replace", onClick = { vm.restore(pw, RestoreMode.REPLACE) })
                }
            },
            dismissButton = { TextButton(onClick = vm::cancelRestore) { Text("Cancel") } },
        )
    }

    ui.shareUrl?.let { url ->
        AlertDialog(
            onDismissRequest = vm::stopSharing,
            containerColor = Vanta.Panel,
            title = { Text("Scan with your phone", color = Vanta.Text) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    QrCode(url, Modifier.size(220.dp))
                    Text(
                        "Your phone must be on the same Wi-Fi. It downloads the backup file; keep it somewhere safe. " +
                            "This stops when you close it, or after 10 minutes.",
                        color = Vanta.TextDim, fontSize = 13.sp,
                    )
                    Text(url, color = Vanta.TextDim, fontSize = 11.sp)
                }
            },
            confirmButton = { TextButton(onClick = vm::stopSharing) { Text("Done") } },
        )
    }
}

/** The sections of one settings page: which one is open (one at a time), and how many can open and close. */
internal class SectionGroupState {
    var open by mutableStateOf<String?>(null)
    val titles = androidx.compose.runtime.mutableStateListOf<String>()
}

internal val LocalSections = androidx.compose.runtime.staticCompositionLocalOf<SectionGroupState?> { null }

/** A settings page whose sections are drop-downs: all closed to start, and opening one closes the others. */
@Composable
internal fun SectionGroup(content: @Composable () -> Unit) {
    val state = remember { SectionGroupState() }
    androidx.compose.runtime.CompositionLocalProvider(LocalSections provides state, content = content)
}

/**
 * A card of settings. On a settings page it's a drop-down that opens when its title is pressed; a page with only
 * one, and [alwaysOpen] ones (something waiting for the viewer, like a PIN or an update), are always open.
 */
@Composable
internal fun Section(title: String, alwaysOpen: Boolean = false, content: @Composable () -> Unit) {
    val group = LocalSections.current?.takeIf { !alwaysOpen }
    if (group != null) androidx.compose.runtime.DisposableEffect(group, title) {
        group.titles += title
        onDispose { group.titles -= title; if (group.open == title) group.open = null }
    }
    val fixed = group == null || group.titles.size == 1
    val expanded = fixed || group?.open == title
    // Keeps this title where it is on screen while sections above close and this one opens, instead of the
    // page jumping: each frame of the animation, the page scrolls by however far the title has moved.
    val scroll = LocalSettingsScroll.current
    val headerY = remember { floatArrayOf(Float.NaN) }
    var anchorAt by remember { mutableStateOf<Float?>(null) }
    androidx.compose.runtime.LaunchedEffect(anchorAt) {
        val anchor = anchorAt ?: return@LaunchedEffect
        if (scroll == null) return@LaunchedEffect
        val until = System.nanoTime() + 700_000_000L
        while (System.nanoTime() < until) {
            androidx.compose.runtime.withFrameNanos { }
            val moved = headerY[0] - anchor
            if (!moved.isNaN() && kotlin.math.abs(moved) >= 1f) scroll.dispatchRawDelta(moved)
        }
        anchorAt = null
    }
    Column(
        Modifier.fillMaxWidth()
            .onGloballyPositioned { headerY[0] = it.positionInRoot().y }.background(Vanta.Surface, RoundedCornerShape(14.dp)).padding(if (fixed) 16.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (fixed) {
            Text(title, color = Vanta.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        } else {
            FocusCard(
                onClick = {
                    anchorAt = headerY[0].takeIf { !it.isNaN() }
                    group!!.open = if (group.open == title) null else title
                },
                selected = expanded,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = Vanta.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Filled.KeyboardArrowDown, contentDescription = null,
                        tint = if (expanded) Vanta.Cyan else Vanta.TextDim,
                        modifier = Modifier.rotate(if (expanded) 0f else -90f),
                    )
                }
            }
        }
        androidx.compose.animation.AnimatedVisibility(expanded) {
            Column(
                Modifier.padding(horizontal = if (fixed) 0.dp else 8.dp).padding(bottom = if (fixed) 0.dp else 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) { content() }
        }
    }
}

@Composable
private fun PasswordDialog(
    purpose: Ask,
    remembered: String?,
    onDismiss: () -> Unit,
    onSaveFile: (String, Boolean) -> Unit,
    onSendPhone: (String, Boolean) -> Unit,
    onTurnOnAuto: (String) -> Unit,
) {
    var pw by remember { mutableStateOf(remembered.orEmpty()) }
    var again by remember { mutableStateOf(remembered.orEmpty()) }
    var rememberIt by remember { mutableStateOf(true) }
    val auto = purpose == Ask.AUTO
    val matches = pw == again
    val ok = matches && (pw.length >= 4 || (!auto && pw.isEmpty()))
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Vanta.Panel,
        title = { Text(if (auto) "Set a backup password" else "Back up now", color = Vanta.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (auto) "Automatic backups use this password to lock your logins. You'll need it to restore."
                    else "Choose a password to lock your logins in the backup. You'll need it to restore. " +
                        "Leave it blank to back up without logins.",
                    color = Vanta.TextDim, fontSize = 13.sp,
                )
                OutlinedTextField(
                    value = pw, onValueChange = { pw = it }, singleLine = true, label = { Text("Backup password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                OutlinedTextField(
                    value = again, onValueChange = { again = it }, singleLine = true, label = { Text("Type it again") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (!matches) Text("The two passwords don't match.", color = Vanta.Error, fontSize = 13.sp)
                if (pw.isNotEmpty() && pw.length < 4) Text("Use at least 4 characters.", color = Vanta.Error, fontSize = 13.sp)
                if (!auto) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = rememberIt, onCheckedChange = { rememberIt = it })
                    Text("Remember on this device (for automatic backups)", color = Vanta.Text, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            if (auto) {
                GradientButton("Turn on", onClick = { onTurnOnAuto(pw) }, enabled = ok && pw.isNotEmpty())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onSendPhone(pw, rememberIt && pw.isNotEmpty()) }, enabled = ok) { Text("Send to phone") }
                    GradientButton("Save to a file", onClick = { onSaveFile(pw, rememberIt && pw.isNotEmpty()) }, enabled = ok)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Draws a QR code for [text] (black on white, with a quiet border). */
@Composable
internal fun QrCode(text: String, modifier: Modifier) {
    val matrix = remember(text) { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0) }
    Canvas(modifier.background(Color.White).padding(10.dp)) {
        val n = matrix.width
        val cell = size.minDimension / n
        for (y in 0 until n) for (x in 0 until n) {
            if (matrix.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

/** Settings › Channels: the show or hide screen. */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun ChannelsSection(playback: com.vantaview.app.data.PlaybackSettings) {
    var open by remember { mutableStateOf(false) }
    var merge by remember {
        mutableStateOf(com.vantaview.app.data.ChannelMerge.Mode.entries.firstOrNull { it.name == playback.getString("merge_channels") } ?: com.vantaview.app.data.ChannelMerge.Mode.BOTH)
    }
    Section("Merge the same channel") {
        Text(
            "Several sources of the same channel become one line: the best one plays, and if it fails or keeps stopping the next is tried by itself. Channels count as the same when they share a TV guide ID, or the same name without HD, FHD, 4K or a country in front.",
            color = Vanta.TextDim, fontSize = 14.sp,
        )
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            com.vantaview.app.data.ChannelMerge.Mode.entries.forEach { m ->
                FocusCard(onClick = { merge = m; playback.setString("merge_channels", m.name) }, selected = merge == m) {
                    Text(m.label, color = if (merge == m) Vanta.Cyan else Vanta.Text, maxLines = 1)
                }
            }
        }
        Text("Across playlists applies when Live TV shows All playlists. A Source button in the player lets you pick one yourself.", color = Vanta.TextDim, fontSize = 12.sp)
    }
    Section("Show or hide channels") {
        Text(
            "Tick channels, or a whole group, then Hide or Show them all at once. Hidden channels are left out of Live TV, the TV Guide, search and channel up and down, and stay hidden when the playlist refreshes.",
            color = Vanta.TextDim, fontSize = 14.sp,
        )
        GradientButton("Show or hide channels", onClick = { open = true })
    }
    if (open) com.vantaview.app.feature.live.ChannelVisibilityDialog(null, onClose = { open = false })
}

/** Weather on Home: on or off, °C or °F, and where (this device's location or a chosen town). */
@Composable
private fun WeatherSection(vm: SettingsViewModel) {
    val w by vm.weatherUi.collectAsStateWithLifecycle()
    val places by vm.places.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.useDeviceLocation()
    }
    Section("Weather on Home") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Show the weather", color = Vanta.Text, modifier = Modifier.weight(1f))
            Switch(checked = w.enabled, onCheckedChange = vm::setWeatherOn)
        }
        if (!w.enabled) return@Section
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Use °F instead of °C", color = Vanta.Text, modifier = Modifier.weight(1f))
            Switch(checked = w.fahrenheit, onCheckedChange = vm::setFahrenheit)
        }
        Text(
            when {
                w.place != null -> "Showing weather for ${w.place}."
                w.usingDevice -> "Showing weather for this device's rough location."
                else -> "Choose a town, or let VantaView use this device's rough location."
            },
            color = Vanta.TextDim, fontSize = 13.sp,
        )
        FocusCard(onClick = {
            runCatching { askLocation.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION) }
        }) { Text("Use this device's location", color = Vanta.Text) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text("Town or city") }, modifier = Modifier.weight(1f),
            )
            FocusCard(onClick = { vm.searchPlaces(query) }) { Text("Find", color = Vanta.Text) }
        }
        places.forEach { p ->
            FocusCard(onClick = { vm.pickPlace(p); query = "" }, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(p.name, color = Vanta.Text)
                    if (p.area.isNotBlank()) Text(p.area, color = Vanta.TextDim, fontSize = 12.sp)
                }
            }
        }
        Text("Weather data by Open-Meteo.com", color = Vanta.TextDim, fontSize = 11.sp)
    }
}
