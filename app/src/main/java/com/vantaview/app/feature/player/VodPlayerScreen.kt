// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.player

import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vantaview.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.vantaview.app.feature.vod.StreamListDialog
import com.vantaview.app.feature.vod.qualityLabel
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.Vanta
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun VodPlayerScreen(onBack: () -> Unit, vm: VodPlayerViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    val context = LocalContext.current
    val picture by vm.picture.collectAsStateWithLifecycle()
    val activity = context as? Activity
    val isTv = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }
    var controls by remember { mutableStateOf(true) }
    var picking by remember { mutableStateOf(false) }
    var speedOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    SleepWatcher(vm.player) { vm.pause() }
    var episodesOpen by remember { mutableStateOf(false) }
    // Phones: the lock stops stray touches (pocket, child); tap the screen, then Unlock.
    var locked by remember { mutableStateOf(false) }
    var showUnlock by remember { mutableStateOf(false) }
    val pip = PlayerPip.inPip
    // While this player is open and playing, Home shrinks it into picture-in-picture.
    DisposableEffect(Unit) { onDispose { PlayerPip.playing = false } }
    LaunchedEffect(Unit) { while (true) { PlayerPip.playing = vm.player.isPlaying; delay(1000) } }
    LaunchedEffect(locked, pip) { playerView?.useController = !locked && !pip; if (locked || pip) playerView?.hideController() }
    LaunchedEffect(showUnlock) { if (showUnlock) { delay(3000); showUnlock = false } }

    // Full screen. The screen keeps following the device's own rotation (and its rotation lock).
    DisposableEffect(Unit) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        // Full screen covers the camera notch too.
        com.vantaview.app.ui.FullScreen.cover(window, true)
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()); com.vantaview.app.ui.FullScreen.cover(window, false) }
    }

    // Back: the first press hides the controls, the next one leaves the player.
    BackHandler(enabled = controls && !picking && ui.failed == null && ui.preparing == null) { playerView?.hideController() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.pause() }
    LaunchedEffect(ui.notice) { if (ui.notice != null) { delay(4000); vm.clearNotice() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    keepScreenOn = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    // Subtitles button (the settings button already offers audio tracks when there are several).
                    setShowSubtitleButton(true)
                    TrackPrefs.style(this, vm.settings)
                    // Settings › Playback: how long the controls stay (0 = until hidden).
                    controllerShowTimeoutMs = vm.settings.controlsSeconds.let { if (it <= 0) 0 else it * 1000 }
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> controls = v == android.view.View.VISIBLE })
                    player = vm.player
                    PictureSize.attach(this) { vm.picture.value }
                    requestFocus()
                    playerView = this
                }
            },
            update = { picture.let { _ -> PictureSize.apply(it) } },
            onRelease = { PictureSize.detach(it); it.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        // When it ends, in this device's time: from the time left (and the playing speed), updated every few seconds.
        var endsAt by remember { mutableStateOf<String?>(null) }
        var paused by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            val fmt = java.text.SimpleDateFormat(if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a", com.vantaview.app.ui.I18n.locale)
            while (true) {
                val p = vm.player
                val dur = p.duration
                endsAt = if (dur > 0 && dur != androidx.media3.common.C.TIME_UNSET) {
                    val left = ((dur - p.currentPosition).coerceAtLeast(0) / p.playbackParameters.speed.coerceAtLeast(0.25f)).toLong()
                    val mins = left / 60_000
                    com.vantaview.app.ui.tr("Ends at {0}", fmt.format(java.util.Date(System.currentTimeMillis() + left))) + "  ·  " +
                        if (mins >= 60) com.vantaview.app.ui.tr("{0} h {1} min left", mins / 60, mins % 60) else com.vantaview.app.ui.tr("{0} min left", mins)
                } else null
                paused = !p.playWhenReady && p.playbackState == androidx.media3.common.Player.STATE_READY
                delay(2000)
            }
        }
        // Paused: when it would end if it carried on now, even with the controls hidden.
        if (paused && !controls && !locked && !pip) endsAt?.let {
            Column(
                Modifier.align(Alignment.TopEnd).padding(20.dp)
                    .background(Color(0xCC000000), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text("Paused", color = Vanta.Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(it, color = Color.White, fontSize = 15.sp)
            }
        }

        // AI subtitles (beta), kept clear of the playback controls.
        if (showInfo) StreamInfoOverlay(vm.player, vm.currentUrl, Modifier.align(Alignment.CenterStart).padding(start = 16.dp))
        vm.ai?.let { AiCaption(it, vm.settings, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = if (controls) 120.dp else 28.dp)) }

        // Loading messages (like a torrent's progress) show for 5 seconds, then get out of the way; a tap
        // that brings up the controls shows them again.
        var prepVisible by remember { mutableStateOf(true) }
        val preparingNow = ui.preparing != null
        LaunchedEffect(preparingNow, controls) {
            if (preparingNow) { prepVisible = true; kotlinx.coroutines.delay(5000); prepVisible = false }
        }
        val showPrep = preparingNow && prepVisible

        if (!locked && !pip && (controls || ui.failed != null || showPrep)) {
            // On an upright phone the buttons go on their own line under the title.
            val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600
            val buttons: @Composable () -> Unit = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    if (ui.isSeries) FocusCard(onClick = { vm.loadEpisodes(); episodesOpen = true }) { Text("Episodes", color = Vanta.Text, maxLines = 1) }
                    FocusCard(onClick = vm::cycleSize) { Text(com.vantaview.app.ui.tr("Size: {0}", com.vantaview.app.ui.tr(picture.size.label)), color = Vanta.Text, maxLines = 1) }
                    FocusCard(onClick = vm::cycleZoom) { Text(com.vantaview.app.ui.tr("Zoom {0}%", picture.zoom), color = Vanta.Text, maxLines = 1) }
                    if (picture.zoom > 100) FocusCard(onClick = vm::cyclePos) { Text(com.vantaview.app.ui.tr("Hold: {0}", com.vantaview.app.ui.tr(picture.pos.label)), color = Vanta.Text, maxLines = 1) }
                    FocusCard(onClick = { speedOpen = true }) { Text(if (ui.speed == 1f) "Speed" else "${ui.speed}×", color = Vanta.Text, maxLines = 1) }
                    FocusCard(onClick = { moreOpen = true }) { Text("⋯ More", color = Vanta.Text, maxLines = 1) }
                    if (ui.streams.size > 1) {
                        val cur = ui.streams.getOrNull(ui.index)
                        FocusCard(onClick = { picking = true }) {
                            Text("Streams" + (cur?.let { "  (${qualityLabel(it)})" } ?: ""), color = Vanta.Text, maxLines = 1)
                        }
                    }
                    if (!isTv) {
                        FocusCard(onClick = { locked = true; showUnlock = false }) { Text("🔒", color = Vanta.Text) }
                        if (PlayerPip.supported(activity)) FocusCard(onClick = { PlayerPip.enter(activity) }) { Text("⧉", color = Vanta.Text) }
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .padding(horizontal = if (compact) 12.dp else 20.dp, vertical = if (compact) 8.dp else 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isTv) {
                        FocusCard(onClick = onBack) { Text("‹ Back", color = Vanta.Text, maxLines = 1) }
                        Spacer(Modifier.width(if (compact) 10.dp else 14.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(ui.title, color = Color.White, fontSize = if (compact) 16.sp else 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ui.subtitle?.let { Text(it, color = Vanta.TextDim, fontSize = if (compact) 13.sp else 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        endsAt?.let { Text(it, color = Vanta.Cyan, fontSize = if (compact) 12.sp else 13.sp, maxLines = 1) }
                    }
                    if (!compact) buttons()
                }
                if (compact) buttons()
            }
        }
        // Skip intro, and the next episode during the end credits. They show even with the controls hidden.
        if (!locked && !pip && (ui.skipIntro || ui.nextUp != null)) {
            val skipFocus = remember { androidx.compose.ui.focus.FocusRequester() }
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = if (controls) 120.dp else 36.dp),
                horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (ui.skipIntro) {
                    FocusCard(onClick = vm::skipIntro, modifier = Modifier.focusRequester(skipFocus)) {
                        Text("Skip intro  ⏭", color = Vanta.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                    LaunchedEffect(Unit) { if (!controls) runCatching { skipFocus.requestFocus() } }
                }
                ui.nextUp?.let { n ->
                    Column(
                        Modifier.widthIn(max = 340.dp).background(Color(0xE6101018), androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(if (ui.nextIn > 0) "Next episode in ${ui.nextIn}s" else "Next episode", color = Vanta.Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("S${n.season} E${n.episode} · ${n.title.ifBlank { "Episode ${n.episode}" }}", color = Color.White, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FocusCard(onClick = vm::playNext, modifier = Modifier.focusRequester(skipFocus)) { Text("▶ Play now", color = Vanta.Text, maxLines = 1) }
                            FocusCard(onClick = vm::cancelNext) { Text("Cancel", color = Vanta.Text, maxLines = 1) }
                        }
                    }
                    LaunchedEffect(n.id) { if (!controls) runCatching { skipFocus.requestFocus() } }
                }
            }
        }
        if (locked) {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { showUnlock = true } },
            ) {
                if (showUnlock) FocusCard(onClick = { locked = false; showUnlock = false }, modifier = Modifier.align(Alignment.Center)) {
                    Text("🔓  Unlock", color = Vanta.Text, fontSize = 18.sp)
                }
            }
        }

        Column(
            Modifier.align(Alignment.Center).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ui.preparing?.takeIf { showPrep }?.let { p ->
                Text(
                    p, color = Vanta.Cyan, fontSize = 17.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.background(Color(0xAA000000), RoundedCornerShape(8.dp)).padding(12.dp),
                )
                if (ui.streams.size > 1) FocusCard(onClick = { picking = true }) { Text("Choose another stream", color = Vanta.Text) }
            }
            ui.failed?.let { f ->
                Text(f, color = Vanta.Error, fontSize = 17.sp, modifier = Modifier.background(Color(0xAA000000), RoundedCornerShape(8.dp)).padding(10.dp))
                if (ui.streams.size > 1) FocusCard(onClick = { picking = true }) { Text("Choose another stream", color = Vanta.Text) }
            }
        }
        ui.notice?.let { n ->
            Text(
                n, color = Vanta.Cyan,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(8.dp)).padding(10.dp),
            )
        }
    }

    if (moreOpen) PlayerMoreDialog(
        onDismiss = { moreOpen = false },
        onInfo = { showInfo = !showInfo },
        onOpenElsewhere = vm.currentUrl?.let { u -> { vm.pause(); openExternally(context, u, ui.title, vm.currentHeaders); Unit } },
    )
    if (speedOpen) SpeedDialog(ui.speed, onPick = { vm.setSpeed(it); speedOpen = false }, onClose = { speedOpen = false })
    if (episodesOpen) {
        val eps by vm.episodes.collectAsStateWithLifecycle()
        EpisodesDialog(eps, vm.currentVideoId, onPick = { episodesOpen = false; vm.playEpisode(it) }, onClose = { episodesOpen = false })
    }

    if (picking) {
        StreamListDialog(
            title = "Streams",
            streams = ui.streams,
            current = ui.index,
            onPick = { i -> picking = false; vm.switchTo(i) },
            onDismiss = { picking = false },
        )
    }
}

@kotlin.OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SpeedDialog(current: Float, onPick: (Float) -> Unit, onClose: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose, containerColor = Vanta.Panel,
        title = { Text("Playback speed", color = Vanta.Text) },
        text = {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { sp ->
                    FocusCard(onClick = { onPick(sp) }, selected = sp == current) {
                        Text(if (sp == 1f) "Normal" else "$sp×", color = if (sp == current) Vanta.Cyan else Vanta.Text)
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
private fun EpisodesDialog(
    episodes: List<com.vantaview.app.data.addon.Video>, current: String,
    onPick: (com.vantaview.app.data.addon.Video) -> Unit, onClose: () -> Unit,
) {
    val seasons = episodes.map { it.season }.distinct()
    var season by remember(episodes) { mutableStateOf(episodes.firstOrNull { it.id == current }?.season ?: seasons.firstOrNull() ?: 1) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose, containerColor = Vanta.Panel,
        title = { Text("Episodes", color = Vanta.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (episodes.isEmpty()) Text("Loading episodes…", color = Vanta.TextDim)
                if (seasons.size > 1) androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(seasons.size) { i ->
                        val s = seasons[i]
                        FocusCard(onClick = { season = s }, selected = s == season) { Text("Season $s", color = if (s == season) Vanta.Cyan else Vanta.Text) }
                    }
                }
                androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val list = episodes.filter { it.season == season }
                    items(list.size) { i ->
                        val v = list[i]
                        FocusCard(onClick = { onPick(v) }, selected = v.id == current, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "${v.episode}. ${v.title.ifBlank { "Episode ${v.episode}" }}" + if (v.id == current) "  · playing" else "",
                                color = if (v.id == current) Vanta.Cyan else Vanta.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("Close") } },
    )
}
