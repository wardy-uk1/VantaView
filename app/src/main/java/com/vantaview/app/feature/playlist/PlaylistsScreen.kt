// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.vantaview.app.feature.playlist

import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import com.vantaview.app.ui.OutlinedTextField
import com.vantaview.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vantaview.app.data.db.ProviderEntity
import com.vantaview.app.data.repo.SyncState
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.GradientButton
import com.vantaview.app.ui.Vanta
import com.vantaview.app.ui.VantaLogo

/**
 * Playlist list and add-playlist form, shown on the Sources page. In first-launch [setup] the form starts
 * with Smart Add only; the M3U, Xtream and Stalker forms are behind "Enter details yourself".
 */
@Composable
fun PlaylistsContent(vm: PlaylistsViewModel = hiltViewModel(), setup: Boolean = false) {
    var manual by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(!setup) }
    val providers by vm.providers.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val pickFile = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.addFile(uri) }
    var guideFor by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<ProviderEntity?>(null) }
    var renaming by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<ProviderEntity?>(null) }
    renaming?.let { p -> RenameDialog(p, onSave = { vm.rename(p, it); renaming = null }, onClose = { renaming = null }) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            providers.forEach { p ->
                ProviderCard(
                    p, sync[p.id], onRefresh = { vm.refresh(p.id) }, onCountries = { vm.editCountries(p.id) }, onDelete = { vm.delete(p.id) },
                    onGuide = if (p.kind == "STALKER") null else ({ guideFor = p }),
                    address = androidx.compose.runtime.remember(p.baseUrl, p.kind) { vm.address(p) },
                    onToggle = { vm.setEnabled(p, !p.enabled) },
                    onRename = { renaming = p },
                )
            }
            guideFor?.let { p -> GuideLinkDialog(
                p, vm.guideOffset(p), onOffset = { vm.setGuideOffset(p, it) },
                onSave = { link, done -> vm.setGuideLink(p, link, done) }, onClose = { guideFor = null },
            ) }
            if (providers.isEmpty() && !setup) {
                Text("No playlists yet. Add one below to get started.", color = Vanta.TextDim)
            }

            if (!vm.hasFreeTv(providers)) FreeTvCard(onAdd = vm::addFreeTv)

            PhoneAddCard(vm)

            Spacer(Modifier.height(6.dp))
            Text(if (setup) "Or add your own playlist" else "Add a playlist", color = Vanta.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)

            if (!manual) {
                if (form.tab != SourceTab.SMART) vm.edit { it.copy(tab = SourceTab.SMART) }
            } else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SourceTab.entries.forEach { tab ->
                    FocusCard(onClick = { vm.edit { it.copy(tab = tab) } }, selected = form.tab == tab) {
                        Text(
                            tab.label,
                            color = if (form.tab == tab) Vanta.Cyan else Vanta.Text,
                            fontWeight = if (form.tab == tab) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }

            when (form.tab) {
                SourceTab.SMART -> {
                    Text(
                        "Paste the message or link your provider sent you. VantaView will pick out the details, including a portal address and MAC address.",
                        color = Vanta.TextDim,
                    )
                    Field("Provider message or link", form.smartText, singleLine = false, minLines = 4) { v ->
                        vm.edit { it.copy(smartText = v) }
                    }
                    GradientButton("Find details", onClick = vm::detect, enabled = form.smartText.isNotBlank())
                    if (!manual) FocusCard(onClick = { manual = true }) { Text("Enter details yourself (M3U, Xtream Codes, Stalker portal)", color = Vanta.Text) }
                }
                SourceTab.M3U -> {
                    Field("Playlist name (optional)", form.name) { v -> vm.edit { it.copy(name = v) } }
                    Field("M3U link", form.m3uUrl, keyboard = KeyboardType.Uri) { v -> vm.edit { it.copy(m3uUrl = v) } }
                    Field("TV guide link (optional)", form.guide, keyboard = KeyboardType.Uri) { v -> vm.edit { it.copy(guide = v) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        GradientButton("Add playlist", onClick = vm::add, enabled = form.m3uUrl.isNotBlank())
                        FocusCard(onClick = {
                            runCatching { pickFile.launch(arrayOf("*/*")) }
                                .onFailure { vm.fileError("This device has no file picker. Use a link instead, or add the file from a phone.") }
                        }) { Text("Choose a file…", color = Vanta.Text) }
                    }
                }
                SourceTab.XTREAM -> {
                    Field("Playlist name (optional)", form.name) { v -> vm.edit { it.copy(name = v) } }
                    Field("Server address", form.server, keyboard = KeyboardType.Uri) { v -> vm.edit { it.copy(server = v) } }
                    Field("Username", form.username) { v -> vm.edit { it.copy(username = v) } }
                    Field("Password", form.password, password = true) { v -> vm.edit { it.copy(password = v) } }
                    Field("TV guide link (optional)", form.guide, keyboard = KeyboardType.Uri) { v -> vm.edit { it.copy(guide = v) } }
                    GradientButton(
                        "Add playlist", onClick = vm::add,
                        enabled = form.server.isNotBlank() && form.username.isNotBlank() && form.password.isNotBlank(),
                    )
                }
                SourceTab.STALKER -> {
                    Text(
                        "For a MAG-style portal subscription. Use the portal address and the MAC address your provider registered for you.",
                        color = Vanta.TextDim,
                    )
                    Field("Portal name (optional)", form.name) { v -> vm.edit { it.copy(name = v) } }
                    Field("Portal address (like http://example.com:8080/c/)", form.portal, keyboard = KeyboardType.Uri) { v -> vm.edit { it.copy(portal = v) } }
                    Field("MAC address (like 00:1A:79:12:34:56)", form.mac, keyboard = KeyboardType.Ascii) { v -> vm.edit { it.copy(mac = v) } }
                    FocusCard(onClick = { vm.edit { it.copy(boxOpen = !it.boxOpen) } }) {
                        Text((if (form.boxOpen) "▾ " else "▸ ") + "Advanced: box details (optional)", color = Vanta.Text)
                    }
                    if (form.boxOpen) {
                        Text(
                            "Only needed if your provider locks your subscription to one MAG box. Copy these from that box " +
                                "(Settings › About on most MAG boxes). Leave them empty otherwise.",
                            color = Vanta.TextDim, fontSize = 13.sp,
                        )
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            com.vantaview.app.data.stalker.StalkerClient.Box.MODELS.forEach { m ->
                                FocusCard(onClick = { vm.edit { it.copy(model = m) } }, selected = form.model == m) {
                                    Text(m, color = if (form.model == m) Vanta.Cyan else Vanta.Text, fontWeight = if (form.model == m) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                        Field("Serial number", form.serial) { v -> vm.edit { it.copy(serial = v) } }
                        Field("Device ID", form.deviceId) { v -> vm.edit { it.copy(deviceId = v) } }
                        Field("Device ID 2", form.deviceId2) { v -> vm.edit { it.copy(deviceId2 = v) } }
                        Field("Signature", form.signature) { v -> vm.edit { it.copy(signature = v) } }
                    }
                    GradientButton("Add portal", onClick = vm::add, enabled = form.portal.isNotBlank() && form.mac.isNotBlank())
                }
            }

            form.message?.let { Text(it, color = if (form.isError) Vanta.Error else Vanta.Cyan) }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    singleLine: Boolean = true,
    minLines: Int = 1,
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ProviderCard(
    p: ProviderEntity, state: SyncState?, onRefresh: () -> Unit, onCountries: () -> Unit, onDelete: () -> Unit, onGuide: (() -> Unit)? = null,
    address: String = "", onToggle: () -> Unit = {}, onRename: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth().background(Vanta.Surface, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).then(if (p.enabled) Modifier else Modifier.alpha(0.55f))) {
                Text(p.name, color = Vanta.Text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when (p.kind) { "XTREAM" -> "Xtream Codes"; "STALKER" -> "Stalker portal · live TV, films and series"; else -> "M3U playlist" },
                    color = Vanta.TextDim, fontSize = 13.sp,
                )
                // Which server it is, so playlists with the same name (or one provider's spare addresses) can be told apart.
                if (address.isNotBlank()) Text(address, color = Vanta.Cyan, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (p.kind == "XTREAM" && p.username.isNotBlank()) {
                    Text(com.vantaview.app.ui.tr("Username: {0}", p.username), color = Vanta.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val fmt = androidx.compose.runtime.remember { java.text.SimpleDateFormat("d MMM yyyy, HH:mm", com.vantaview.app.ui.I18n.locale) }
                Text(
                    com.vantaview.app.ui.tr("Added {0}", fmt.format(java.util.Date(p.createdAt))) +
                        if (p.lastSyncAt > 0) " · " + com.vantaview.app.ui.tr("Last loaded {0}", fmt.format(java.util.Date(p.lastSyncAt))) else "",
                    color = Vanta.TextDim, fontSize = 12.sp,
                )
            }
        }
        // The buttons wrap onto a second line when the card is narrow (a phone held upright).
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p.enabled) {
                if (p.channelCount > 0) FocusCard(onClick = onCountries) { Text("Countries", color = Vanta.Text, maxLines = 1, softWrap = false) }
                onGuide?.let { FocusCard(onClick = it) { Text(if (p.customEpg != null) "Guide ✓" else "Guide", color = Vanta.Text, maxLines = 1, softWrap = false) } }
                FocusCard(onClick = onRefresh) { Text("Refresh", color = Vanta.Text, maxLines = 1, softWrap = false) }
            }
            FocusCard(onClick = onRename) { Text("Rename", color = Vanta.Text, maxLines = 1, softWrap = false) }
            FocusCard(onClick = onToggle) { Text(if (p.enabled) "Switch off" else "Switch on", color = if (p.enabled) Vanta.Text else Vanta.Cyan, maxLines = 1, softWrap = false) }
            FocusCard(onClick = onDelete) { Text("Remove", color = Vanta.Error, maxLines = 1, softWrap = false) }
        }
        if (!p.enabled) {
            Text("Switched off: its channels, guide, films and series are hidden everywhere until you switch it on.", color = Vanta.TextDim, fontSize = 14.sp)
            return@Column
        }
        val (line, colour) = when (state) {
            is SyncState.Running -> "${state.stage}…" to Vanta.Cyan
            is SyncState.Failed -> state.message to Vanta.Error
            is SyncState.Done ->
                ("${state.channels} channels ready" +
                    if (state.skippedVod > 0) " · ${state.skippedVod} films and series" else "") to Vanta.TextDim
            null -> when {
                p.lastError != null -> p.lastError to Vanta.Error
                p.channelCount > 0 -> "${p.channelCount} channels" to Vanta.TextDim
                else -> "Not loaded yet" to Vanta.TextDim
            }
        }
        Text(line, color = colour, fontSize = 14.sp)
    }
}

/** Give a playlist your own name; blank goes back to the automatic one. */
@Composable
private fun RenameDialog(p: ProviderEntity, onSave: (String) -> Unit, onClose: () -> Unit) {
    var name by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(p.name) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose, containerColor = Vanta.Panel,
        title = { Text("Rename playlist", color = Vanta.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Playlist name", name) { name = it }
                Text("Leave it empty to use the automatic name (the server's).", color = Vanta.TextDim, fontSize = 13.sp)
            }
        },
        confirmButton = { GradientButton("Save", onClick = { onSave(name) }) },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/** Type your own TV guide (XMLTV) link for a playlist, or clear it to use the playlist's own. */
@Composable
private fun GuideLinkDialog(p: ProviderEntity, offset: Int, onOffset: (Int) -> Unit, onSave: (String, (String?) -> Unit) -> Unit, onClose: () -> Unit) {
    var link by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(p.customEpg.orEmpty()) }
    var shift by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(offset) }
    var error by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose, containerColor = Vanta.Panel,
        title = { Text("TV guide for ${p.name}", color = Vanta.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste an XMLTV guide link (plain or .gz). Several links can be separated by commas. Leave it empty to use the playlist's own guide.",
                    color = Vanta.TextDim, fontSize = 13.sp,
                )
                Field("TV guide link", link, keyboard = KeyboardType.Uri) { link = it; error = null }
                error?.let { Text(it, color = Vanta.Error, fontSize = 13.sp) }
                // Guides whose times are out by an hour or so (often a time-zone or summer-time slip).
                Text("Guide times", color = Vanta.Text, fontWeight = FontWeight.Bold)
                Text(
                    if (shift == 0) "As the guide gives them." else if (shift > 0) com.vantaview.app.ui.tr("Moved later by {0}.", offsetLabel(kotlin.math.abs(shift))) else com.vantaview.app.ui.tr("Moved earlier by {0}.", offsetLabel(kotlin.math.abs(shift))),
                    color = Vanta.TextDim, fontSize = 13.sp,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-180, -120, -60, -30, 0, 30, 60, 120, 180).forEach { m ->
                        FocusCard(onClick = { shift = m; onOffset(m) }, selected = shift == m) {
                            Text(if (m == 0) "None" else (if (m > 0) "+" else "−") + offsetLabel(kotlin.math.abs(m)),
                                color = if (shift == m) Vanta.Cyan else Vanta.Text, fontSize = 13.sp, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        },
        confirmButton = { GradientButton("Save", onClick = { onSave(link) { e -> if (e == null) onClose() else error = e } }) },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

private fun offsetLabel(m: Int) = if (m < 60) "$m min" else "${m / 60} h" + if (m % 60 != 0) " ${m % 60} min" else ""

/** "Add from your phone": a QR code that opens a form on a phone on the same Wi-Fi, which sends a playlist here. */
@Composable
private fun PhoneAddCard(vm: PlaylistsViewModel) {
    val url by vm.phone.collectAsStateWithLifecycle()
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { vm.stopPhone() } }
    Column(
        Modifier.fillMaxWidth().background(Vanta.Surface, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Add from your phone", color = Vanta.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        val u = url
        if (u == null) {
            Text(
                "Type, paste or pick your playlist on your phone instead of with the remote. Works with a provider message, an M3U link or file, and Xtream Codes.",
                color = Vanta.TextDim,
            )
            GradientButton("Show QR code", onClick = vm::startPhone)
        } else {
            // Side by side on a TV or tablet; on a phone held upright the code sits above the words.
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                val narrow = maxWidth < 520.dp
                val qr: @Composable () -> Unit = {
                    com.vantaview.app.feature.settings.QrCode(u, Modifier.width(200.dp).height(200.dp))
                }
                val words: @Composable (Modifier) -> Unit = { m ->
                    Column(m, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Scan this with your phone's camera. Your phone needs to be on the same Wi-Fi as this device.", color = Vanta.Text)
                        Text("Or open this address in a browser:", color = Vanta.TextDim, fontSize = 13.sp)
                        Text(u, color = Vanta.Cyan, fontSize = 13.sp)
                        Text(
                            "It only works on your home network, and stops after 15 minutes or when you leave this page.",
                            color = Vanta.TextDim, fontSize = 12.sp,
                        )
                        FocusCard(onClick = vm::stopPhone) { Text("Stop", color = Vanta.Text) }
                    }
                }
                if (narrow) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { qr() }
                        words(Modifier.fillMaxWidth())
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        qr()
                        words(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** Optional free channels from Free TV, added only when the user asks. */
@Composable
fun FreeTvCard(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Vanta.Surface, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Try free channels", color = Vanta.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            "Officially free-to-air TV channels from around the world. Pick your countries after adding. " +
                "Some channels only play inside their own country.",
            color = Vanta.TextDim,
        )
        GradientButton("Add free channels", onClick = onAdd)
        Text(
            "List by Free TV (github.com/Free-TV/IPTV). TV guide by epgshare01.online. Thanks to their maintainers and contributors.",
            color = Vanta.TextDim, fontSize = 12.sp,
        )
    }
}
