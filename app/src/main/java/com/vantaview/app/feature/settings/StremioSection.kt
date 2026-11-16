// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vantaview.app.data.profile.ProfileSession
import com.vantaview.app.data.stremio.StremioLink
import com.vantaview.app.data.stremio.StremioLinkCode
import com.vantaview.app.data.stremio.StremioOptions
import com.vantaview.app.data.stremio.StremioState
import com.vantaview.app.data.stremio.StremioSync
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.GradientButton
import com.vantaview.app.ui.OutlinedTextField
import com.vantaview.app.ui.Text
import com.vantaview.app.ui.Vanta
import com.vantaview.app.ui.tr
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class StremioViewModel @Inject constructor(
    private val stremio: StremioSync,
    session: ProfileSession,
) : ViewModel() {
    val link: StateFlow<StremioLink?> = combine(stremio.version, session.current) { _, p -> stremio.link(p) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, stremio.link())
    val state: StateFlow<StremioState> = stremio.state

    private val _code = MutableStateFlow<StremioLinkCode?>(null)
    val code: StateFlow<StremioLinkCode?> = _code
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _message = MutableStateFlow<Pair<String, Boolean>?>(null)
    /** A message, and whether it's an error. */
    val message: StateFlow<Pair<String, Boolean>?> = _message

    private var job: Job? = null

    fun startQr() {
        job?.cancel(); _message.value = null
        job = viewModelScope.launch {
            try {
                val c = stremio.startQr()
                _code.value = c
                stremio.finishQr(c)
                _message.value = tr("Signed in to Stremio. Syncing now…") to false
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) _message.value = (e.message ?: tr("Couldn't sign in to Stremio.")) to true
            } finally { _code.value = null }
        }
    }

    fun cancel() { job?.cancel(); _code.value = null }

    fun signIn(email: String, password: String) {
        job?.cancel(); _message.value = null
        job = viewModelScope.launch {
            _busy.value = true
            try {
                stremio.signIn(email, password)
                _message.value = tr("Signed in to Stremio. Syncing now…") to false
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) _message.value = (e.message ?: tr("Couldn't sign in to Stremio.")) to true
            } finally { _busy.value = false }
        }
    }

    fun setOptions(o: StremioOptions) = stremio.setOptions(o)
    fun syncNow() = stremio.syncNow()
    fun signOut() { stremio.signOut(); _message.value = tr("Signed out of Stremio on this profile.") to false }
}

/** Settings › Stremio: sign this profile in to a Stremio account and keep add-ons and the library in step. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun StremioSection(vm: StremioViewModel = hiltViewModel()) {
    val link by vm.link.collectAsStateWithLifecycle()
    val code by vm.code.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val l = link
        if (l == null) {
            Section("Sign in to Stremio", alwaysOpen = true) {
                Text(
                    "Sign in with your Stremio account and VantaView keeps this profile in step with it, both ways: your add-ons, " +
                        "your Library (VantaView's Watchlist), Continue watching and watched films. Each profile signs in to its own account. " +
                        "Your password is never kept.",
                    color = Vanta.TextDim, fontSize = 14.sp,
                )
                val c = code
                if (c != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        QrCode(c.link, Modifier.size(150.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Scan this with your phone, sign in to Stremio there and confirm. Or go to", color = Vanta.TextDim, fontSize = 14.sp)
                            Text(c.link.removePrefix("https://"), color = Vanta.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text("Code:", color = Vanta.TextDim, fontSize = 14.sp)
                            Text(c.code, color = Vanta.Cyan, fontSize = 28.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, letterSpacing = 3.sp)
                            Text("Waiting for you to confirm…", color = Vanta.TextDim, fontSize = 13.sp)
                        }
                    }
                    FocusCard(onClick = vm::cancel) { Text("Cancel", color = Vanta.Text) }
                } else {
                    GradientButton("Sign in with a QR code", onClick = vm::startQr)
                    var email by remember { mutableStateOf("") }
                    var password by remember { mutableStateOf("") }
                    Text("Or with your email and password:", color = Vanta.TextDim, fontSize = 13.sp)
                    OutlinedTextField(
                        value = email, onValueChange = { email = it }, label = { Text("Stremio email") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password, onValueChange = { password = it }, label = { Text("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                    )
                    FocusCard(onClick = { if (!busy && email.isNotBlank() && password.isNotBlank()) vm.signIn(email, password) }) {
                        Text(if (busy) "Signing in…" else "Sign in", color = Vanta.Text)
                    }
                }
            }
        } else {
            Section("Stremio") {
                Text(
                    if (l.email.isNotBlank()) tr("Signed in as {0}", l.email) else tr("Signed in"),
                    color = Vanta.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                )
                Text(
                    if (l.lastSync > 0) tr("Last synced {0}. Syncs every half hour while VantaView is open.", SimpleDateFormat("EEE d MMM, HH:mm", com.vantaview.app.ui.I18n.locale).format(Date(l.lastSync)))
                    else tr("Not synced yet."),
                    color = Vanta.TextDim, fontSize = 13.sp,
                )
                when (val s = state) {
                    is StremioState.Busy -> Text(s.text, color = Vanta.Cyan, fontSize = 14.sp)
                    is StremioState.Done -> Text(s.text, color = Vanta.Cyan, fontSize = 14.sp)
                    is StremioState.Failed -> Text(s.text, color = Vanta.Error, fontSize = 14.sp)
                    StremioState.Idle -> Unit
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FocusCard(onClick = vm::syncNow) { Text("Sync now", color = Vanta.Text, maxLines = 1, softWrap = false) }
                    FocusCard(onClick = vm::signOut) { Text("Sign out", color = Vanta.Error, maxLines = 1, softWrap = false) }
                }
            }
            Section("What stays in step") {
                val o = l.options
                StremioToggle("Add-ons", "Add-ons installed here and on your Stremio account match, both ways.", o.addons) { vm.setOptions(o.copy(addons = it)) }
                StremioToggle("Library", "Your Stremio Library and VantaView's Watchlist match, both ways.", o.library) { vm.setOptions(o.copy(library = it)) }
                StremioToggle("Continue watching", "Pick up where you stopped in Stremio, and Stremio picks up where you stopped here.", o.progress) { vm.setOptions(o.copy(progress = it)) }
                StremioToggle("Watched films", "Films marked watched, both ways. (Watched episodes stay on each side; Trakt can share those.)", o.watched) { vm.setOptions(o.copy(watched = it)) }
            }
        }
        message?.let { (t, err) -> Text(t, color = if (err) Vanta.Error else Vanta.Cyan, fontSize = 14.sp) }
        Text("Uses Stremio's account service. VantaView isn't made or endorsed by Stremio.", color = Vanta.TextDim, fontSize = 11.sp)
    }
}

@Composable
private fun StremioToggle(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Vanta.Text)
            Text(detail, color = Vanta.TextDim, fontSize = 13.sp)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}
