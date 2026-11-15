// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.sources

import com.vantaview.app.ui.tvTextField
import com.vantaview.app.ui.tvSafe

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import com.vantaview.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.vantaview.app.data.addon.InstalledAddon
import com.vantaview.app.feature.playlist.PlaylistsContent
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.GradientButton
import com.vantaview.app.ui.Vanta

enum class SourcesTab(val label: String) { PLAYLISTS("Playlists"), ADDONS("Add-ons"), DEBRID("Debrid") }

@Composable
fun SourcesScreen(startTab: SourcesTab) {
    var tab by rememberSaveable(startTab) { mutableStateOf(startTab) }
    Box(Modifier.fillMaxSize().background(Vanta.Page).imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            com.vantaview.app.ui.PageTitle("Sources")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SourcesTab.entries.forEach { t ->
                    FocusCard(onClick = { tab = t }, selected = tab == t) {
                        Text(
                            t.label,
                            color = if (tab == t) Vanta.Cyan else Vanta.Text,
                            fontWeight = if (tab == t) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
            when (tab) {
                SourcesTab.PLAYLISTS -> PlaylistsContent()
                SourcesTab.ADDONS -> AddonsContent()
                SourcesTab.DEBRID -> DebridContent()
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AddonsContent(vm: AddonsViewModel = hiltViewModel()) {
    val addons by vm.addons.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Stremio add-ons work here exactly as they do in Stremio and Nuvio. Paste an add-on's install link " +
                "(it ends in manifest.json, or starts with stremio://). Their films and series appear on the " +
                "Movies and TV Series pages.",
            color = Vanta.TextDim,
        )
        OutlinedTextField(
            value = form.url,
            onValueChange = vm::editUrl,
            label = { Text("Add-on install link") },
            singleLine = true,
            keyboardOptions = (KeyboardOptions(keyboardType = KeyboardType.Uri)).tvSafe(),
            modifier = (Modifier.fillMaxWidth()).then(Modifier.tvTextField()),
        )
        GradientButton("Install add-on", onClick = { vm.install() }, enabled = form.url.isNotBlank() && !form.busy)
        form.message?.let { Text(it, color = if (form.isError) Vanta.Error else Vanta.Cyan) }
        form.untrusted?.let { u -> TrustButton(u, onTrust = vm::trustAndInstall) }

        if (addons.none { it.entity.manifestUrl == AddonsViewModel.CINEMETA }) {
            Column(
                Modifier.fillMaxWidth().background(Vanta.Surface, RoundedCornerShape(14.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Need film and series information?", color = Vanta.Text, fontWeight = FontWeight.Bold)
                Text(
                    "Cinemeta is Stremio's own catalogue add-on: posters, descriptions and episode lists. " +
                        "It has no streams; those come from the other add-ons you install.",
                    color = Vanta.TextDim, fontSize = 14.sp,
                )
                FocusCard(onClick = { vm.install(AddonsViewModel.CINEMETA) }) { Text("Add Cinemeta", color = Vanta.Cyan) }
            }
        }

        RestorePanel(vm)

        Spacer(Modifier.height(4.dp))
        Text("Installed (${addons.size})", color = Vanta.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (addons.isEmpty()) Text("No add-ons yet.", color = Vanta.TextDim)
        addons.forEach { a -> AddonCard(a, onToggle = { vm.toggle(a) }, onRemove = { vm.remove(a) }) }
    }
}

@Composable
private fun AddonCard(a: InstalledAddon, onToggle: () -> Unit, onRemove: () -> Unit) {
    val m = a.manifest
    val offers = buildList {
        val types = m.catalogs.map { it.type }.toSet() + m.types
        if ("movie" in types) add("films")
        if ("series" in types) add("series")
        if (types.any { it == "tv" || it == "channel" }) add("TV")
        if (m.hasResource("stream")) add("streams")
        if (m.hasResource("meta")) add("info")
        if (m.hasResource("subtitles")) add("subtitles")
    }
    Column(
        Modifier.fillMaxWidth().background(Vanta.Surface, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(Vanta.Bg), contentAlignment = Alignment.Center) {
                if (m.logo != null) AsyncImage(model = m.logo, contentDescription = null, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(m.name, color = Vanta.Text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (m.version.isNotBlank()) "v${m.version} · " else "") + offers.joinToString(", ").ifBlank { "Nothing usable" },
                    color = Vanta.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FocusCard(onClick = onToggle) {
                Text(if (a.entity.enabled) "On" else "Off", color = if (a.entity.enabled) Vanta.Cyan else Vanta.TextDim)
            }
            FocusCard(onClick = onRemove) { Text("Remove", color = Vanta.Error) }
        }
    }
}
