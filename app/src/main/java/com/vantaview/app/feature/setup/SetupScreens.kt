// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.setup

import com.vantaview.app.ui.tvTextField
import com.vantaview.app.ui.tvSafe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import com.vantaview.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vantaview.app.feature.playlist.PlaylistsContent
import com.vantaview.app.feature.playlist.PlaylistsViewModel
import com.vantaview.app.feature.sources.AddonsViewModel
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.GradientButton
import com.vantaview.app.ui.Vanta
import com.vantaview.app.ui.VantaLogo

/** The page frame shared by the setup steps: step label, title, text, the content, then Continue and Skip. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SetupPage(
    step: String, title: String, text: String,
    canContinue: Boolean, onContinue: () -> Unit, onSkip: () -> Unit,
    content: @Composable () -> Unit,
) {
    // Continue and Skip stay at the bottom of the screen, so a TV remote never has to scroll to find them.
    Column(Modifier.fillMaxSize().background(Vanta.Bg), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.weight(1f).widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                VantaLogo(48.dp)
                Text(step, color = Vanta.TextDim, fontSize = 13.sp)
                Text(title, color = Vanta.Text, fontSize = 28.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
                Text(text, color = Vanta.TextDim, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(4.dp))
            content()
        }
        Box(Modifier.fillMaxWidth().background(Vanta.Surface), contentAlignment = Alignment.Center) {
            FlowRow(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GradientButton("Continue", onClick = onContinue, enabled = canContinue)
                FocusCard(onClick = onSkip) { Text("Skip for now", color = Vanta.Text, maxLines = 1, softWrap = false) }
            }
        }
    }
}

/** First launch, step 3: add live TV (Smart Add or Free TV); skippable. */
@Composable
fun SetupPlaylistScreen(onNext: () -> Unit, vm: PlaylistsViewModel = hiltViewModel()) {
    val providers by vm.providers.collectAsStateWithLifecycle()
    SetupPage(
        step = "Step 3 of 4 · Live TV",
        title = "Add your TV",
        text = "Paste the message or link your provider sent you, or try free channels. You can add more later on the Sources page.",
        canContinue = providers.isNotEmpty(), onContinue = onNext, onSkip = onNext,
    ) {
        PlaylistsContent(vm, setup = true)
    }
}

/** First launch, step 4: films and series add-ons (Cinemeta in one press, or an add-on link); skippable. */
@Composable
fun SetupAddonsScreen(onDone: () -> Unit, vm: AddonsViewModel = hiltViewModel()) {
    val addons by vm.addons.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val hasCinemeta = addons.any { it.entity.manifestUrl == AddonsViewModel.CINEMETA }
    SetupPage(
        step = "Step 4 of 4 · Films and series",
        title = "Films and series",
        text = "VantaView uses Stremio and Nuvio add-ons for films and series. Cinemeta adds posters, details and episode lists; " +
            "streams come from add-ons you choose. You can skip this and add them later on the Sources page.",
        canContinue = addons.isNotEmpty(), onContinue = onDone, onSkip = onDone,
    ) {
        Column(
            Modifier.fillMaxWidth().background(Vanta.Surface, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Cinemeta", color = Vanta.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Stremio's own catalogue: popular films and series, details and episode lists. It has no streams.", color = Vanta.TextDim)
            if (hasCinemeta) Text("✓ Added", color = Vanta.Cyan)
            else GradientButton("Add Cinemeta", onClick = { vm.install(AddonsViewModel.CINEMETA) }, enabled = !form.busy)
        }
        Text("Have an add-on link?", color = Vanta.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = form.url, onValueChange = vm::editUrl, singleLine = true,
            label = { Text("Add-on install link") }, modifier = (Modifier.fillMaxWidth()).then(Modifier.tvTextField()),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default.tvSafe(),
        )
        FocusCard(onClick = { vm.install() }) { Text(if (form.busy) "Installing…" else "Install add-on", color = Vanta.Text) }
        form.message?.let { Text(it, color = if (form.isError) Vanta.Error else Vanta.Cyan, fontSize = 14.sp) }
        addons.filter { it.entity.manifestUrl != AddonsViewModel.CINEMETA }.forEach { a ->
            Text("✓ ${a.manifest.name}", color = Vanta.Cyan, fontSize = 14.sp)
        }
    }
}
