// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import com.vantaview.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.ExoPlayer
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.Vanta
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The sleep timer, shared by the live and film players: pauses playback when it runs out. */
object SleepTimer {
    private val _endsAt = MutableStateFlow<Long?>(null)
    /** When playback will pause (wall clock), or null when off. */
    val endsAt: StateFlow<Long?> = _endsAt
    fun start(minutes: Int) { _endsAt.value = System.currentTimeMillis() + minutes * 60_000L }
    fun cancel() { _endsAt.value = null }
    /** True (once) when the time is up. */
    fun due(): Boolean {
        val t = _endsAt.value ?: return false
        if (System.currentTimeMillis() < t) return false
        _endsAt.value = null
        return true
    }
}

/** Watches the sleep timer for a player and pauses it when the time is up. */
@Composable
fun SleepWatcher(player: Player, onSlept: () -> Unit) {
    LaunchedEffect(player) {
        while (true) {
            delay(5_000)
            if (SleepTimer.due()) { player.playWhenReady = false; onSlept() }
        }
    }
}

/**
 * Settings › Playback › Play films and series in: opens [url] in [pkg] (or lets the viewer choose when [pkg] is
 * "ask" or isn't installed), with the title, where to start, the add-on subtitles and the stream's headers, in
 * the ways VLC, MX Player and mpv read them. Returns false when no player could be opened.
 */
fun openInPlayer(context: Context, pkg: String, url: String, title: String, headers: Map<String, String>, positionMs: Long, subtitles: List<String>): Boolean {
    val i = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(url), "video/*")
        putExtra("title", title)
        // Where to start: VLC reads a Long, MX Player an Int (both milliseconds).
        if (positionMs > 0) {
            if (pkg.startsWith("org.videolan")) putExtra("position", positionMs) else putExtra("position", positionMs.toInt())
        }
        if (headers.isNotEmpty()) putExtra("headers", headers.flatMap { listOf(it.key, it.value) }.toTypedArray())
        if (subtitles.isNotEmpty()) {
            putExtra("subtitles_location", subtitles.first()) // VLC
            putExtra("subs", subtitles.map { Uri.parse(it) }.toTypedArray()) // MX Player
            putExtra("subs.enable", arrayOf(Uri.parse(subtitles.first())))
        }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (pkg != "ask") {
        val direct = Intent(i).setPackage(pkg)
        if (runCatching { context.startActivity(direct) }.isSuccess) return true
    }
    return runCatching { context.startActivity(Intent.createChooser(i, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}

/** Video player apps on this device that can open a web address (for Settings › Playback), as (package, name). */
fun videoPlayerApps(context: Context): List<Pair<String, String>> {
    val pm = context.packageManager
    val probe = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("https://example.com/video.mp4"), "video/*")
    @Suppress("DEPRECATION")
    return runCatching { pm.queryIntentActivities(probe, 0) }.getOrDefault(emptyList())
        .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        .filter { it.first != context.packageName }
        .distinctBy { it.first }
        .sortedBy { it.second.lowercase() }
}

/** Opens a stream in another installed player (VLC, MX Player…), passing its headers where the player supports them. */
fun openExternally(context: Context, url: String, title: String, headers: Map<String, String>): Boolean {
    val i = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(url), "video/*")
        putExtra("title", title)
        // MX Player reads headers as alternating name/value pairs.
        if (headers.isNotEmpty()) putExtra("headers", headers.flatMap { listOf(it.key, it.value) }.toTypedArray())
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return runCatching { context.startActivity(Intent.createChooser(i, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}

/** "More" in a player: sleep timer, stream info and opening the stream in another app. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PlayerMoreDialog(
    onDismiss: () -> Unit,
    onInfo: () -> Unit,
    onOpenElsewhere: (() -> Unit)?,
) {
    val endsAt by SleepTimer.endsAt.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Vanta.Panel,
        title = { Text("More", color = Vanta.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Sleep timer", color = Vanta.Text)
                endsAt?.let { t ->
                    val mins = ((t - System.currentTimeMillis()) / 60_000L + 1).coerceAtLeast(1)
                    Text("Pausing in about $mins min.", color = Vanta.Cyan, fontSize = 13.sp)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 45, 60, 90, 120).forEach { m ->
                        FocusCard(onClick = { SleepTimer.start(m); onDismiss() }) { Text(if (m < 60) "$m min" else "${m / 60} h" + if (m % 60 != 0) " ${m % 60}" else "", color = Vanta.Text, maxLines = 1, softWrap = false) }
                    }
                    if (endsAt != null) FocusCard(onClick = { SleepTimer.cancel(); onDismiss() }) { Text("Turn off", color = Vanta.Error, maxLines = 1, softWrap = false) }
                }
                FocusCard(onClick = { onInfo(); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("ⓘ Stream info", color = Vanta.Text) }
                onOpenElsewhere?.let { open ->
                    FocusCard(onClick = { open(); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("↗ Open in another player (VLC, MX Player…)", color = Vanta.Text) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** A small box of technical details about what's playing, updated every second. */
@Composable
fun StreamInfoOverlay(player: ExoPlayer, url: String?, modifier: Modifier = Modifier) {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    @Suppress("UNUSED_EXPRESSION") tick
    val v = player.videoFormat
    val a = player.audioFormat
    val lines = buildList {
        v?.let {
            add("Video  ${it.width}×${it.height}" + (if (it.frameRate > 0) " · ${"%.0f".format(it.frameRate)} fps" else "") +
                (it.sampleMimeType?.substringAfter('/')?.let { m -> " · $m" } ?: ""))
            if (it.bitrate > 0) add("Bitrate  ${it.bitrate / 1000} kbit/s")
        }
        a?.let { add("Audio  " + (it.sampleMimeType?.substringAfter('/') ?: "?") + (if (it.channelCount > 0) " · ${it.channelCount} ch" else "") + (it.language?.let { l -> " · $l" } ?: "")) }
        add("Buffered  ${player.totalBufferedDuration / 1000} s ahead")
        val counters = player.videoDecoderCounters
        counters?.let { add("Dropped frames  ${it.droppedBufferCount}") }
        url?.let { u -> runCatching { Uri.parse(u) }.getOrNull()?.let { add("From  ${it.scheme}://${it.host}") } }
    }
    Column(
        modifier.widthIn(max = 360.dp).background(Color(0xCC000000), RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        lines.forEach { Text(it, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
    }
}
