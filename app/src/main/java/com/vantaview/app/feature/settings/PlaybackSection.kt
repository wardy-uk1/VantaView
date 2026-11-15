// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import com.vantaview.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vantaview.app.data.BufferSize
import com.vantaview.app.data.PlaybackSettings
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.Vanta

/** Settings › Playback. Changes apply the next time a video starts. */
/** Settings › Playback, for the profile in use. */
@Composable
fun PlaybackSection(s: PlaybackSettings) {
    var buffer by remember { mutableStateOf(s.buffer) }
    var controls by remember { mutableStateOf(s.controlsSeconds) }
    var mini by remember { mutableStateOf(s.miniPlayer) }
    var livePreview by remember { mutableStateOf(s.livePreview) }
    var gestures by remember { mutableStateOf(s.gestures) }
    var pip by remember { mutableStateOf(s.pip) }
    var startLast by remember { mutableStateOf(s.startOnLastChannel) }
    var rememberSize by remember { mutableStateOf(s.rememberSize) }
    var remindMins by remember { mutableStateOf(s.reminderMinutes) }
    var auto by remember { mutableStateOf(s.autoBuffer) }
    var skipIntro by remember { mutableStateOf(s.skipIntro) }
    var torrents by remember { mutableStateOf(s.playTorrents) }
    var nextEp by remember { mutableStateOf(s.nextEpisode) }
    var autoTrailers by remember { mutableStateOf(s.autoTrailers) }
    var trailerSound by remember { mutableStateOf(s.trailerSound) }
    var remindPlaying by remember { mutableStateOf(s.remindersWhilePlaying) }

    Section("Buffer size") {
        Choices(BufferSize.entries, buffer, { it.label }) { buffer = it; s.buffer = it }
        Text(buffer.detail, color = Vanta.TextDim, fontSize = 13.sp)
        Toggle(
            "Adjust automatically",
            com.vantaview.app.ui.tr("If a stream keeps stopping to load, the next one uses a bigger buffer; after a couple of hours of smooth watching it steps back. Small devices (like 1 GB Fire TV Sticks) never go above Medium.") +
                (if (auto && s.effectiveBuffer != buffer) " " + com.vantaview.app.ui.tr("Using {0} right now.", com.vantaview.app.ui.tr(s.effectiveBuffer.label)) else ""),
            auto,
        ) { auto = it; s.autoBuffer = it }
    }

    Section("Films and series") {
        Toggle(
            "Skip intro",
            "A Skip intro button when the intro's times are known (from the community databases IntroDB and SkipDB).",
            skipIntro,
        ) { skipIntro = it; s.skipIntro = it }
        Toggle("Next episode countdown", "During the end credits, count down and play the next episode.", nextEp) { nextEp = it; s.nextEpisode = it }
        Toggle("Play trailers on details pages", "After 20 seconds on a film or series page, its trailer plays in the background.", autoTrailers) {
            autoTrailers = it; s.autoTrailers = it
        }
        if (autoTrailers) Toggle("Trailers with sound", "Off starts them muted; you can unmute on the page.", trailerSound) { trailerSound = it; s.trailerSound = it }
        Toggle(
            "Play torrents in VantaView",
            "Torrent results play here when no debrid account has them ready. While it plays, a torrent shares the parts you have with other people, and they can see your IP address; it stops sharing and deletes the file when you close the player.",
            torrents,
        ) { torrents = it; s.playTorrents = it }
    }

    Section("Player controls") {
        Text("How long the controls stay on screen", color = Vanta.Text)
        Choices(listOf(3, 5, 10, 0), controls, { if (it == 0) "Always" else "$it seconds" }) { controls = it; s.controlsSeconds = it }
        if (controls == 0) Text("They stay until you hide them (tap, or press OK or Back).", color = Vanta.TextDim, fontSize = 13.sp)
    }

    Section("Live TV") {
        Toggle("Mini player on Home", "A small, muted preview of the last channel you watched", mini) { mini = it; s.miniPlayer = it }
        Toggle(
            "Preview channels first",
            "In Live TV, the first press on a channel plays it in a small window; press it again for full screen",
            livePreview,
        ) { livePreview = it; s.livePreview = it }
        Toggle("Start on last channel", "Open straight into the last channel when VantaView starts", startLast) { startLast = it; s.startOnLastChannel = it }
        Toggle(
            "Remember picture size",
            "Keep Fit, Fill or Zoom between channels. Change it with the Size button, or Menu on a remote.",
            rememberSize,
        ) { rememberSize = it; s.rememberSize = it }
    }

    Section("Programme reminders") {
        Text("Remind me this long before a programme starts", color = Vanta.Text)
        Choices(listOf(1, 5, 10, 15), remindMins, { "$it min" }) { remindMins = it; s.reminderMinutes = it }
        Toggle("Remind me while watching", "Show reminders over a film or channel too (otherwise only on menus).", remindPlaying) {
            remindPlaying = it; s.remindersWhilePlaying = it
        }
    }

    Section("Phone gestures") {
        Toggle("Swipe and double tap", "Swipe left or right to change channel. Swipe up or down on the left for brightness, on the right for volume (up to 200%). Double tap for full screen.", gestures) { gestures = it; s.gestures = it }
        Toggle(
            "Picture-in-picture",
            "Pressing Home while something plays shrinks it into a small window over your other apps, and the player has a button for it.",
            pip,
        ) { pip = it; s.pip = it }
    }

    Text("Changes apply the next time a video starts.", color = Vanta.TextDim, fontSize = 13.sp)
}

/** Choice chips that keep their words whole: they wrap onto a second line instead of squeezing. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
/** Settings › Appearance › Screensaver (this profile). */
@Composable
fun ScreensaverSection(s: PlaybackSettings) {
    var mins by remember { mutableStateOf(s.ambientMinutes) }
    Section("Screensaver") {
        Text(
            "After a while without a key press or touch on a menu, show slowly changing film and series pictures with the time and weather. " +
                "Never while something is playing.",
            color = Vanta.TextDim, fontSize = 13.sp,
        )
        Choices(listOf(0, 5, 10, 15, 30), mins, { if (it == 0) "Off" else "$it min" }) { mins = it; s.ambientMinutes = it }
    }
}

@kotlin.OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(options: List<T>, chosen: T, label: (T) -> String, onPick: (T) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            FocusCard(onClick = { onPick(o) }, selected = o == chosen, contentAlignment = Alignment.Center) {
                Text(
                    label(o), color = if (o == chosen) Vanta.Cyan else Vanta.Text, maxLines = 1, softWrap = false,
                    fontWeight = if (o == chosen) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp,
                )
            }
        }
    }
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
