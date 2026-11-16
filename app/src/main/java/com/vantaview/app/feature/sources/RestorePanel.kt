// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.sources

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import com.vantaview.app.ui.OutlinedTextField
import com.vantaview.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.Vanta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Fallback location when the device has no file picker (common on Android TV and Fire TV). */
private fun fallbackFile(context: Context) = File(context.getExternalFilesDir(null) ?: context.filesDir, "vantaview-addons.json")

/** Restore section of the Add-ons tab: import from a Stremio account, back up to a file, restore from a file. */
@Composable
fun RestorePanel(vm: AddonsViewModel) {
    val restore by vm.restore.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                val text = vm.backupText()
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
            }.isSuccess
            vm.showMessage(if (ok) "Backup saved." else "Couldn't save the backup.", !ok)
        }
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            }
            if (text == null) vm.showMessage("Couldn't open that file.", true) else vm.loadBackup(text)
        }
    }

    fun backUp() {
        val name = "vantaview-addons-" + SimpleDateFormat("yyyy-MM-dd", Locale.UK).format(Date()) + ".json"
        try {
            saveLauncher.launch(name)
        } catch (e: ActivityNotFoundException) {
            // No file picker on this device: save into the app's own folder instead.
            scope.launch {
                val f = fallbackFile(context)
                val ok = runCatching { val t = vm.backupText(); withContext(Dispatchers.IO) { f.writeText(t) } }.isSuccess
                vm.showMessage(if (ok) "This device has no file picker, so the backup was saved to ${f.absolutePath}" else "Couldn't save the backup.", !ok)
            }
        }
    }

    fun restoreFile() {
        try {
            openLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
        } catch (e: ActivityNotFoundException) {
            scope.launch {
                val f = fallbackFile(context)
                val text = withContext(Dispatchers.IO) { runCatching { f.readText() }.getOrNull() }
                if (text == null) vm.showMessage("This device has no file picker, and there's no backup in ${f.absolutePath} yet.", true)
                else vm.loadBackup(text)
            }
        }
    }

    Column(
        Modifier.fillMaxWidth().background(Vanta.Surface, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Restore add-ons", color = Vanta.Text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text(
            "Bring in the add-ons from your Stremio account, or save this profile's add-ons to a file and restore them later. " +
                "Nuvio backups also bring their Watchlist, watched films and Continue watching. " +
                "Backups can hold personal keys from add-on links, so keep the file private.",
            color = Vanta.TextDim, fontSize = 13.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FocusCard(onClick = vm::startStremio) { Text("Import from Stremio", color = Vanta.Cyan) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FocusCard(onClick = ::backUp) { Text("Back up to file", color = Vanta.Text) }
            FocusCard(onClick = ::restoreFile) { Text("Restore from file", color = Vanta.Text) }
        }
        when (val r = restore) {
            is RestoreUi.Loading -> Text(r.text, color = Vanta.Cyan)
            is RestoreUi.Done -> {
                Text(r.text, color = if (r.isError) Vanta.Error else Vanta.Cyan)
                r.untrusted.forEach { u -> TrustButton(u, onTrust = vm::trustAndInstall) }
            }
            else -> Unit
        }
    }

    when (val r = restore) {
        is RestoreUi.StremioLogin -> StremioLoginDialog(r, onSignIn = vm::signInStremio, onCancel = vm::cancelRestore)
        is RestoreUi.Pick -> PickDialog(r, onToggle = vm::togglePick, onToggleExtra = vm::toggleExtra, onImport = vm::importSelected, onCancel = vm::cancelRestore)
        else -> Unit
    }
}

@Composable
private fun StremioLoginDialog(state: RestoreUi.StremioLogin, onSignIn: (String, String) -> Unit, onCancel: () -> Unit) {
    var email by remember { mutableStateOf(state.email) }
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Vanta.Panel,
        title = { Text("Import from Stremio", color = Vanta.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Sign in once to copy the add-ons installed on your Stremio account. " +
                        "VantaView doesn't keep your email, password or Stremio session.",
                    color = Vanta.TextDim, fontSize = 13.sp,
                )
                OutlinedTextField(
                    value = email, onValueChange = { email = it }, label = { Text("Stremio email") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
                )
                state.error?.let { Text(it, color = Vanta.Error, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSignIn(email, password) }, enabled = email.isNotBlank() && password.isNotBlank()) { Text("Sign in") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun PickDialog(state: RestoreUi.Pick, onToggle: (String) -> Unit, onToggleExtra: () -> Unit, onImport: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Vanta.Panel,
        title = { Text(state.title, color = Vanta.Text) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.extra?.let { x ->
                    item(key = "extra") {
                        val on = state.extraOn
                        FocusCard(onClick = onToggleExtra, selected = on, modifier = Modifier.fillMaxWidth()) {
                            Column {
                                Text((if (on) "☑  " else "☐  ") + "Watchlist, Watched and Continue watching", color = if (on) Vanta.Cyan else Vanta.Text, fontWeight = FontWeight.Bold)
                                Text(com.vantaview.app.ui.tr(x.summary()) + ". " + com.vantaview.app.ui.tr("Added to this profile."), color = Vanta.TextDim, fontSize = 12.sp, maxLines = 2)
                            }
                        }
                    }
                }
                // Add-ons you don't have yet come first, ticked; ones you already have are listed after.
                val fresh = state.items.filter { !it.alreadyInstalled }
                val have = state.items.filter { it.alreadyInstalled }
                if (state.items.isNotEmpty()) item(key = "new-title") {
                    Text(
                        if (fresh.isEmpty()) "You already have every add-on in this backup."
                        else if (fresh.size == 1) com.vantaview.app.ui.tr("1 add-on you don't have yet") else com.vantaview.app.ui.tr("{0} add-ons you don't have yet", fresh.size),
                        color = if (fresh.isEmpty()) Vanta.TextDim else Vanta.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    )
                }
                items(fresh, key = { it.url }) { a -> AddonRow(a, a.url in state.selected) { onToggle(a.url) } }
                if (have.isNotEmpty()) {
                    item(key = "have-title") { Text("Already installed (tick to reinstall)", color = Vanta.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
                    items(have, key = { it.url }) { a -> AddonRow(a, a.url in state.selected) { onToggle(a.url) } }
                }
            }
        },
        confirmButton = {
            val extra = state.extra != null && state.extraOn
            TextButton(onClick = onImport, enabled = state.selected.isNotEmpty() || extra) {
                Text(
                    if (state.selected.isEmpty()) "Restore"
                    else if (state.selected.size == 1) (if (extra) com.vantaview.app.ui.tr("Add 1 add-on and restore") else com.vantaview.app.ui.tr("Add 1 add-on"))
                    else if (extra) com.vantaview.app.ui.tr("Add {0} add-ons and restore", state.selected.size) else com.vantaview.app.ui.tr("Add {0} add-ons", state.selected.size)
                )
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun AddonRow(a: com.vantaview.app.data.addon.AddonRepository.RemoteAddon, on: Boolean, onToggle: () -> Unit) {
    FocusCard(onClick = onToggle, selected = on, modifier = Modifier.fillMaxWidth()) {
        Column {
            Text((if (on) "☑  " else "☐  ") + a.name, color = if (on) Vanta.Cyan else Vanta.Text, fontWeight = FontWeight.Bold)
            a.description.takeIf { it.isNotBlank() }?.let { Text(it, color = Vanta.TextDim, fontSize = 12.sp, maxLines = 2) }
        }
    }
}

/** "Trust this server" for an add-on whose certificate was refused, with what the certificate says. */
@Composable
fun TrustButton(u: Untrusted, onTrust: (Untrusted) -> Unit) {
    var asking by remember { mutableStateOf(false) }
    FocusCard(onClick = { asking = true }) { Text("Trust ${u.name}'s server…", color = Vanta.Text) }
    if (asking) {
        val c = u.cert
        AlertDialog(
            onDismissRequest = { asking = false },
            containerColor = Vanta.Panel,
            title = { Text("Trust ${c.host}?", color = Vanta.Text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        when {
                            c.expired -> "Its certificate ran out on ${c.validUntil}."
                            c.selfSigned -> "Its certificate is self-signed (it vouches for itself)."
                            else -> "Its certificate was issued by \"${c.issuer}\", which this device doesn't recognise."
                        },
                        color = Vanta.Text, fontSize = 14.sp,
                    )
                    Text("Issued to ${c.subject} by ${c.issuer}, valid until ${c.validUntil}.", color = Vanta.TextDim, fontSize = 13.sp)
                    Text(
                        "Only trust it if you know who runs this add-on. VantaView will trust this exact certificate for this server; " +
                            "if it ever changes, you'll be asked again. Fingerprint: ${c.fingerprint.take(23)}…",
                        color = Vanta.TextDim, fontSize = 12.sp,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { asking = false; onTrust(u) }) { Text("Trust and install") } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
        )
    }
}
