// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vantaview.app.data.debrid.DebridAccount
import com.vantaview.app.data.debrid.DebridService
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.Vanta
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DebridContent(vm: DebridViewModel = hiltViewModel()) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val connect by vm.connect.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Connect a debrid account to play torrent results from your add-ons as normal streams. Nothing is downloaded " +
                "or shared from this device: your debrid service fetches the file and VantaView plays it from there. " +
                "Accounts are shared by all profiles and tried in the order below.",
            color = Vanta.TextDim,
        )
        (connect as? ConnectUi.Message)?.let { Text(it.text, color = if (it.isError) Vanta.Error else Vanta.Cyan) }

        Text("Connected (${accounts.size})", color = Vanta.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (accounts.isEmpty()) Text("No debrid accounts yet.", color = Vanta.TextDim)
        accounts.forEachIndexed { i, a ->
            AccountCard(
                a, first = i == 0, last = i == accounts.lastIndex,
                onUp = { vm.move(a.service, -1) }, onDown = { vm.move(a.service, 1) },
                onCheck = { vm.check(a.service) }, onRemove = { vm.remove(a.service) },
            )
        }

        Text("Add a service", color = Vanta.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        DebridService.entries.filter { s -> accounts.none { it.service == s } }.forEach { s ->
            Column(
                Modifier.fillMaxWidth().background(Vanta.Surface, RoundedCornerShape(14.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(s.label, color = Vanta.Text, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.hasCodeSignIn) FocusCard(onClick = { vm.startCode(s) }) { Text("Sign in with a code", color = Vanta.Cyan) }
                    FocusCard(onClick = { vm.enterKey(s) }) { Text("Paste API key", color = Vanta.Text) }
                }
            }
        }
    }

    when (val c = connect) {
        is ConnectUi.EnterKey -> KeyDialog(c, onSave = { vm.saveKey(c.service, it) }, onCancel = vm::cancel)
        is ConnectUi.Code -> AlertDialog(
            onDismissRequest = vm::cancel,
            containerColor = Vanta.Panel,
            title = { Text("Connect ${c.service.label}", color = Vanta.Text) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("On your phone or computer, go to", color = Vanta.TextDim, textAlign = TextAlign.Center)
                    Text(c.code.url.removePrefix("https://").removePrefix("http://"), color = Vanta.Cyan, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text("and enter this code:", color = Vanta.TextDim)
                    Text(c.code.userCode, color = Vanta.Text, fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
                    Text("Waiting for you to approve it…", color = Vanta.TextDim, fontSize = 13.sp)
                }
            },
            confirmButton = { TextButton(onClick = vm::cancel) { Text("Cancel") } },
        )
        else -> Unit
    }
}

@Composable
private fun AccountCard(
    a: DebridAccount, first: Boolean, last: Boolean,
    onUp: () -> Unit, onDown: () -> Unit, onCheck: () -> Unit, onRemove: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(Vanta.Surface, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(a.service.label, color = Vanta.Text, fontWeight = FontWeight.Bold)
        val until = a.premiumUntil.takeIf { it > 0 }?.let { com.vantaview.app.ui.tr("Premium until {0}", SimpleDateFormat("d MMM yyyy", com.vantaview.app.ui.I18n.locale).format(Date(it))) }
        Text(listOfNotNull(a.label.ifBlank { null }, until).joinToString(" · ").ifBlank { "Connected" }, color = Vanta.TextDim, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!first) FocusCard(onClick = onUp) { Text("▲", color = Vanta.Text) }
            if (!last) FocusCard(onClick = onDown) { Text("▼", color = Vanta.Text) }
            FocusCard(onClick = onCheck) { Text("Check", color = Vanta.Text) }
            FocusCard(onClick = onRemove) { Text("Remove", color = Vanta.Error) }
        }
    }
}

@Composable
private fun KeyDialog(state: ConnectUi.EnterKey, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Vanta.Panel,
        title = { Text("${state.service.label} API key", color = Vanta.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Find your key at ${state.service.keyPage.removePrefix("https://")}. It's stored encrypted on this device.",
                    color = Vanta.TextDim, fontSize = 13.sp,
                )
                OutlinedTextField(
                    value = key, onValueChange = { key = it.trim() }, label = { Text("API key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
                )
                state.error?.let { Text(it, color = Vanta.Error, fontSize = 13.sp) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(key) }, enabled = key.isNotBlank() && !state.busy) { Text(if (state.busy) "Checking…" else "Connect") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
