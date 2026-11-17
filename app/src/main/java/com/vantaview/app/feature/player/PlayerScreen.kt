// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.player

import android.app.Activity
import android.content.pm.PackageManager
import androidx.annotation.OptIn
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import com.vantaview.app.data.ChannelNames
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.focus.onFocusChanged
import androidx.media3.common.C
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.GradientButton
import com.vantaview.app.ui.Vanta
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(onBack: () -> Unit, vm: PlayerViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val isTv = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }
    var overlay by remember { mutableStateOf(true) }
    var overlayTick by remember { mutableStateOf(0) }
    val focus = remember { FocusRequester() }
    // Swipe up/down: left side brightness, right side volume (to 200%).
    val levels = remember { PlayerLevels(activity, vm.player) }
    DisposableEffect(Unit) { onDispose { levels.release() } }
    // True while the Audio/Subtitles/Size buttons have focus or a picker is open: the controls stay.
    var hold by remember { mutableStateOf(false) }

    // Hide the overlay a few seconds after it was last shown, unless something needs attention.
    LaunchedEffect(overlay, overlayTick, ui.status, hold) {
        val seconds = vm.settings.controlsSeconds
        if (overlay && !hold && ui.status == PlayStatus.PLAYING && seconds > 0) {
            delay(seconds * 1000L)
            overlay = false
        }
    }
    LaunchedEffect(ui.status) { if (ui.status != PlayStatus.PLAYING) overlay = true }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // When the controls hide, the remote goes back to the video (channel up/down).
    LaunchedEffect(overlay) { if (!overlay) runCatching { focus.requestFocus() } }


    // Full screen on an upright phone (double-tap the video, or double-tap a channel in the list).
    var full by remember { mutableStateOf(false) }

    // Back: hides the controls first, then leaves full screen, then leaves the player.
    BackHandler(enabled = full) { full = false }
    BackHandler(enabled = overlay) { overlay = false }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.pause() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.resume() }

    fun zap(delta: Int) {
        vm.step(delta)
        overlay = true
        overlayTick++
    }

    // A tap, or OK on a remote: retry if the channel failed, otherwise show or hide the overlay.
    fun tapped() {
        if (ui.status == PlayStatus.FAILED && isTv) vm.retryNow() else overlay = !overlay
        overlayTick++
    }

    val list by vm.list.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()

    // Phones: Home while a channel plays shrinks it into picture-in-picture; the controls step aside there.
    val pip = PlayerPip.inPip
    DisposableEffect(Unit) { onDispose { PlayerPip.playing = false } }
    LaunchedEffect(Unit) { while (true) { PlayerPip.playing = !isTv && vm.player.isPlaying; delay(1000) } }
    LaunchedEffect(pip) { if (pip) overlay = false }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        // Phones held upright get the video on top and the channel list underneath; otherwise full screen.
        // The screen follows the device's own rotation (and its rotation lock).
        val upright = !isTv && maxHeight > maxWidth && !pip
        val portrait = upright && !full
        DisposableEffect(portrait) {
            val window = activity?.window
            val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (portrait) controller?.show(WindowInsetsCompat.Type.systemBars())
            else controller?.hide(WindowInsetsCompat.Type.systemBars())
            // Full screen covers the camera notch too.
            com.vantaview.app.ui.FullScreen.cover(window, !portrait)
            onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()); com.vantaview.app.ui.FullScreen.cover(window, false) }
        }

        val video: @Composable (Modifier) -> Unit = { m ->
            VideoArea(
                vm = vm, ui = ui, isTv = isTv, overlay = overlay, compact = portrait, focus = focus,
                modifier = m, onTap = ::tapped, onZap = ::zap, onBack = onBack,
                onHold = { hold = it; overlayTick++ }, onShowControls = { overlay = true; overlayTick++ },
                levels = levels,
                onDoubleTap = { if (upright && vm.settings.gestures) full = !full },
            )
        }
        if (portrait) {
            // Upright, the status and navigation bars stay, so the page keeps clear of them.
            Column(Modifier.fillMaxSize().background(Vanta.Bg).safeDrawingPadding()) {
                video(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                NowStrip(ui)
                PortraitChannelList(
                    list, current, Modifier.weight(1f).fillMaxWidth(),
                    onPick = { i ->
                        vm.playAt(i)
                        overlay = true
                        overlayTick++
                    },
                    onFull = { i ->
                        vm.playAt(i)
                        if (vm.settings.gestures) full = true
                    },
                )
            }
        } else {
            video(Modifier.fillMaxSize())
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoArea(
    vm: PlayerViewModel,
    ui: PlayerUi,
    isTv: Boolean,
    overlay: Boolean,
    compact: Boolean,
    focus: FocusRequester,
    modifier: Modifier,
    onTap: () -> Unit,
    onZap: (Int) -> Unit,
    onBack: () -> Unit,
    onDoubleTap: () -> Unit,
    onHold: (Boolean) -> Unit,
    onShowControls: () -> Unit,
    levels: PlayerLevels,
) {
    val density = LocalDensity.current
    val swipe = with(density) { 60.dp.toPx() }
    val picture by vm.picture.collectAsStateWithLifecycle()
    var pictureView by remember { mutableStateOf<PlayerView?>(null) }
    // Each change of size, zoom or hold is applied to the video straight away.
    LaunchedEffect(picture, pictureView) { pictureView?.let { PictureSize.apply(it) } }
    val gestures = vm.settings.gestures
    var levelShown by remember { mutableStateOf<Level?>(null) }
    // Number keys on the remote: collect digits, then jump after a short pause.
    var typed by remember { mutableStateOf("") }
    var typedMiss by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(typed) {
        if (typed.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.delay(1_500)
        val n = typed.toIntOrNull()
        typedMiss = if (n != null && !vm.playNumber(n)) "No channel $typed in this list" else null
        if (typedMiss == null) onShowControls()
        typed = ""
    }
    LaunchedEffect(typedMiss) { if (typedMiss != null) { kotlinx.coroutines.delay(2_000); typedMiss = null } }
    // Audio or subtitle picker (a track type), and the buttons that open it.
    var picker by remember { mutableStateOf<Int?>(null) }
    var moreOpen by remember { mutableStateOf(false) }
    var sourcesOpen by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    SleepWatcher(vm.player) { }
    val buttons = remember { FocusRequester() }
    // Phones: the lock stops stray touches; tap the screen, then Unlock.
    var locked by remember { mutableStateOf(false) }
    var showUnlock by remember { mutableStateOf(false) }
    LaunchedEffect(showUnlock) { if (showUnlock) { kotlinx.coroutines.delay(3000); showUnlock = false } }
    var videoFocused by remember { mutableStateOf(false) }
    var buttonsFocused by remember { mutableStateOf(false) }
    LaunchedEffect(picker, buttonsFocused) { onHold(picker != null || buttonsFocused) }
    Box(
        modifier
            .background(Color.Black)
            .focusRequester(focus)
            .onFocusChanged { videoFocused = it.isFocused }
            .onKeyEvent { e ->
                // Right on a remote moves to the Audio, Subtitles and Size buttons.
                if (e.key == Key.DirectionRight && videoFocused) {
                    if (e.type == KeyEventType.KeyDown) {
                        onShowControls()
                        runCatching { buttons.requestFocus() }
                    }
                    return@onKeyEvent true
                }
                val handled = when (e.key) {
                    Key.DirectionUp, Key.ChannelUp, Key.PageUp, Key.MediaNext -> 1
                    Key.DirectionDown, Key.ChannelDown, Key.PageDown, Key.MediaPrevious -> -1
                    else -> 0
                }
                val digit = when (e.key) {
                    Key.Zero, Key.NumPad0 -> 0; Key.One, Key.NumPad1 -> 1; Key.Two, Key.NumPad2 -> 2
                    Key.Three, Key.NumPad3 -> 3; Key.Four, Key.NumPad4 -> 4; Key.Five, Key.NumPad5 -> 5
                    Key.Six, Key.NumPad6 -> 6; Key.Seven, Key.NumPad7 -> 7; Key.Eight, Key.NumPad8 -> 8
                    Key.Nine, Key.NumPad9 -> 9; else -> -1
                }
                if (digit >= 0) {
                    if (e.type == KeyEventType.KeyDown && typed.length < 5) typed += digit
                    return@onKeyEvent true
                }
                // Remote media keys: play/pause, rewind and fast forward; Last for the previous channel.
                when (e.key) {
                    Key.LastChannel -> { if (e.type == KeyEventType.KeyUp) vm.lastChannel(); return@onKeyEvent true }
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { if (e.type == KeyEventType.KeyUp) vm.togglePause(); return@onKeyEvent true }
                    Key.MediaRewind -> { if (e.type == KeyEventType.KeyUp) vm.jump(-30); return@onKeyEvent true }
                    Key.MediaFastForward -> { if (e.type == KeyEventType.KeyUp) vm.jump(30); return@onKeyEvent true }
                    else -> Unit
                }
                // Menu on a remote changes the picture size (Fit, Fill, Zoom).
                if (e.key == Key.Menu) {
                    if (e.type == KeyEventType.KeyUp) vm.cycleSize()
                    return@onKeyEvent true
                }
                if (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter) {
                    if (e.type == KeyEventType.KeyUp) onTap()
                    return@onKeyEvent true
                }
                if (handled == 0) return@onKeyEvent false
                if (e.type == KeyEventType.KeyDown) onZap(handled)
                true
            }
            .focusable()
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    keepScreenOn = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    TrackPrefs.style(this, vm.settings)
                    player = vm.player
                    PictureSize.attach(this) { vm.picture.value }
                    pictureView = this
                }
            },
            update = { PictureSize.apply(it) },
            onRelease = { PictureSize.detach(it); it.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        // Stream info (More › Stream info).
        if (showInfo) StreamInfoOverlay(vm.player, vm.currentUrl, Modifier.align(Alignment.CenterStart).padding(start = 16.dp))

        // Channel number being typed on the remote.
        (if (typed.isNotEmpty()) typed else typedMiss)?.let { t ->
            Text(
                t, color = Color.White, fontSize = if (typedMiss != null) 18.sp else 40.sp, fontWeight = FontWeight.Black,
                modifier = Modifier.align(Alignment.TopEnd).padding(24.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // Brightness or volume while swiping.
        levelShown?.let { LevelBadge(it, Modifier.align(if (it.brightness) Alignment.CenterStart else Alignment.CenterEnd).padding(28.dp)) }

        // AI subtitles (beta), above the bottom controls.
        vm.ai?.let { AiCaption(it, vm.settings, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = if (overlay) 110.dp else 28.dp)) }

        // Tap (or OK on a remote) shows and hides the overlay; swipe up or down on the video changes channel.
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }, onDoubleTap = { onDoubleTap() }) }
                .pointerInput(ui.hasSiblings, gestures) {
                    if (!gestures) return@pointerInput
                    // Decide what a drag is once it has moved a little: sideways changes channel;
                    // up/down sets brightness on the left half and volume on the right half.
                    var mode = 0
                    var dx = 0f
                    var dy = 0f
                    var startX = 0f
                    val slop = viewConfiguration.touchSlop
                    detectDragGestures(
                        onDragStart = { o -> mode = 0; dx = 0f; dy = 0f; startX = o.x },
                        onDragEnd = {
                            if (mode == 1 && ui.hasSiblings) {
                                if (dx < -swipe) onZap(1) else if (dx > swipe) onZap(-1)
                            }
                            mode = 0
                            levelShown = null
                        },
                        onDragCancel = { mode = 0; levelShown = null },
                        onDrag = { change, d ->
                            change.consume()
                            dx += d.x; dy += d.y
                            if (mode == 0 && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                                mode = if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) 1 else if (startX < this@pointerInput.size.width / 2) 2 else 3
                            }
                            val span = this@pointerInput.size.height * 0.8f
                            when (mode) {
                                2 -> { levels.changeBrightness(-d.y / span); levelShown = Level(true, levels.brightness) }
                                3 -> { levels.changeVolume(-d.y * 2f / span); levelShown = Level(false, levels.volume) }
                            }
                        },
                    )
                }
        )
        if (locked) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { showUnlock = true } }) {
                if (showUnlock) FocusCard(onClick = { locked = false; showUnlock = false }, modifier = Modifier.align(Alignment.Center)) {
                    Text("🔓  Unlock", color = Vanta.Text, fontSize = 18.sp)
                }
            }
        }

        if (overlay && !locked && !PlayerPip.inPip) {
            val pad = if (compact) 12.dp else 20.dp
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                // The channel's name, and the Audio, Subtitles, Size, lock and picture-in-picture buttons.
                // A phone held upright has no room for both on one line, so the buttons go underneath.
                val buttonsRow: @Composable () -> Unit = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.onFocusChanged { buttonsFocused = it.hasFocus }
                            .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    ) {
                        FocusCard(onClick = { picker = C.TRACK_TYPE_AUDIO }, modifier = Modifier.focusRequester(buttons)) {
                            Text("Audio", color = Vanta.Text, fontSize = if (compact) 13.sp else 15.sp, maxLines = 1)
                        }
                        FocusCard(onClick = { picker = C.TRACK_TYPE_TEXT }) {
                            Text("Subtitles", color = Vanta.Text, fontSize = if (compact) 13.sp else 15.sp, maxLines = 1)
                        }
                        // A merged channel: pick which of its sources plays.
                        if (ui.sourceCount > 1) FocusCard(onClick = { sourcesOpen = true }) {
                            Text(com.vantaview.app.ui.tr("Source {0}/{1}", ui.sourceIndex + 1, ui.sourceCount), color = Vanta.Text, fontSize = if (compact) 13.sp else 15.sp, maxLines = 1)
                        }
                        FocusCard(onClick = vm::cycleSize) {
                            Text(com.vantaview.app.ui.tr("Size: {0}", com.vantaview.app.ui.tr(picture.size.label)), color = Vanta.Text, fontSize = if (compact) 13.sp else 15.sp, maxLines = 1)
                        }
                        FocusCard(onClick = vm::cycleZoom) {
                            Text(com.vantaview.app.ui.tr("Zoom {0}%", picture.zoom), color = Vanta.Text, fontSize = if (compact) 13.sp else 15.sp, maxLines = 1)
                        }
                        if (picture.zoom > 100) FocusCard(onClick = vm::cyclePos) {
                            Text(com.vantaview.app.ui.tr("Hold: {0}", com.vantaview.app.ui.tr(picture.pos.label)), color = Vanta.Text, fontSize = if (compact) 13.sp else 15.sp, maxLines = 1)
                        }
                        // Pause, rewind and Go live.
                        val sh = ui.shift
                        FocusCard(onClick = { vm.jump(-30) }) { Text("⏪ 30s", color = Vanta.Text, fontSize = if (compact) 13.sp else 15.sp, maxLines = 1) }
                        FocusCard(onClick = vm::togglePause) {
                            Text(if (sh?.paused == true) "▶ Play" else "⏸ Pause", color = Vanta.Text, fontSize = if (compact) 13.sp else 15.sp, maxLines = 1)
                        }
                        if (sh != null) {
                            FocusCard(onClick = { vm.jump(30) }) { Text("30s ⏩", color = Vanta.Text, fontSize = if (compact) 13.sp else 15.sp, maxLines = 1) }
                            FocusCard(onClick = vm::goLive) { Text("● Go live", color = Vanta.Error, fontSize = if (compact) 13.sp else 15.sp, maxLines = 1) }
                        }
                        if (vm.hasLastChannel) FocusCard(onClick = vm::lastChannel) { Text("↺ Last", color = Vanta.Text, fontSize = if (compact) 13.sp else 15.sp, maxLines = 1) }
                        FocusCard(onClick = { moreOpen = true }) { Text("⋯ More", color = Vanta.Text, fontSize = if (compact) 13.sp else 15.sp, maxLines = 1) }
                        if (!isTv) {
                            val activity = LocalContext.current as? Activity
                            FocusCard(onClick = { locked = true; showUnlock = false }) { Text("🔒", color = Vanta.Text, fontSize = if (compact) 13.sp else 15.sp) }
                            if (PlayerPip.supported(activity)) FocusCard(onClick = { PlayerPip.enter(activity) }) {
                                Text("⧉", color = Vanta.Text, fontSize = if (compact) 13.sp else 15.sp)
                            }
                        }
                    }
                }
                Column(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                        .windowInsetsPadding(WindowInsets.displayCutout)
                        .padding(horizontal = pad, vertical = if (compact) 8.dp else 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!isTv) {
                            FocusCard(onClick = onBack) { Text("‹ Back", color = Vanta.Text, maxLines = 1) }
                            Spacer(Modifier.width(if (compact) 10.dp else 14.dp))
                        }
                        Text(ui.number.toString(), color = Vanta.Cyan, fontSize = if (compact) 15.sp else 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            ui.title, color = Color.White, fontSize = if (compact) 16.sp else 20.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        )
                        ui.shift?.let { sh ->
                            Spacer(Modifier.width(10.dp))
                            val behind = if (sh.behindSec >= 3600) "%d:%02d:%02d".format(sh.behindSec / 3600, sh.behindSec / 60 % 60, sh.behindSec % 60)
                                else "%d:%02d".format(sh.behindSec / 60, sh.behindSec % 60)
                            Text(
                                sh.note ?: ((if (sh.paused) com.vantaview.app.ui.tr("Paused") + " · " else "") + "−$behind" + if (sh.fromArchive) " · " + com.vantaview.app.ui.tr("catch-up") else ""),
                                color = Vanta.Cyan, fontSize = if (compact) 13.sp else 15.sp, maxLines = 1,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (!compact) buttonsRow()
                    }
                    if (compact) buttonsRow()
                }

                Column(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                        .windowInsetsPadding(WindowInsets.displayCutout)
                        .padding(horizontal = pad, vertical = if (compact) 8.dp else 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (!compact && ui.now != null && ui.status == PlayStatus.PLAYING) {
                        NowInfo(ui, Modifier.fillMaxWidth().padding(bottom = 10.dp))
                    }
                    when (ui.status) {
                        PlayStatus.LOADING -> Text(ui.sourceNote ?: if (ui.eventOnly) "Looking for the event…" else "Loading…", color = Color.White)
                        PlayStatus.RECONNECTING -> Text(
                            "Reconnecting (try ${ui.attempt} of 5)…",
                            color = Vanta.Cyan,
                            modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(8.dp),
                        )
                        PlayStatus.FAILED -> {
                            Text(
                                ui.message ?: "This channel could not be played",
                                color = if (ui.eventOnly) Color.White else Vanta.Error,
                                fontSize = if (compact) 14.sp else 17.sp,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
                            if (!isTv) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                GradientButton("Try again", onClick = vm::retryNow)
                                if (ui.hasSiblings) FocusCard(onClick = { onZap(1) }) { Text("Next channel", color = Vanta.Text) }
                            }
                        }
                        PlayStatus.PLAYING -> Unit
                    }
                    if (!compact || ui.status == PlayStatus.PLAYING) Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
                    if (isTv) {
                        Text(
                            when {
                                ui.status == PlayStatus.FAILED -> "OK to try again   ·   ▲ ▼ change channel   ·   Back to exit"
                                ui.hasSiblings -> "▲ ▼ change channel   ·   ▶ audio, subtitles, size   ·   Back to exit"
                                else -> "▶ audio, subtitles, size   ·   Back to exit"
                            },
                            color = Vanta.TextDim, fontSize = 14.sp,
                        )
                    } else if (ui.hasSiblings && ui.status != PlayStatus.FAILED) {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            FocusCard(onClick = { onZap(-1) }) { ChannelStep(up = false, "Previous") }
                            if (!compact) Text("or swipe left and right", color = Vanta.TextDim, fontSize = 13.sp)
                            FocusCard(onClick = { onZap(1) }) { ChannelStep(up = true, "Next") }
                        }
                    }
                }
            }
        }
    }

    picker?.let { type -> TrackPicker(vm, type) { picker = null } }
    if (sourcesOpen) {
        val labels = remember(ui.sourceCount) { vm.sourceLabels() }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { sourcesOpen = false }, containerColor = Vanta.Panel,
            title = { Text("Sources", color = Vanta.Text) },
            text = {
                androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("The same channel from different places, best first. If one stops, the next is tried by itself.", color = Vanta.TextDim, fontSize = 13.sp)
                    labels.forEachIndexed { i, label ->
                        FocusCard(onClick = { vm.switchSource(i); sourcesOpen = false }, selected = i == ui.sourceIndex, modifier = Modifier.fillMaxWidth()) {
                            Text("${i + 1}. $label", color = if (i == ui.sourceIndex) Vanta.Cyan else Vanta.Text, maxLines = 2)
                        }
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { sourcesOpen = false }) { Text("Close") } },
        )
    }
    if (moreOpen) PlayerMoreDialog(
        onDismiss = { moreOpen = false },
        onInfo = { showInfo = !showInfo },
        onOpenElsewhere = vm.currentUrl?.let { u -> { openExternally(ctx, u, ui.title, vm.currentHeaders); Unit } },
    )
}

/** Pick an audio or subtitle track for the channel playing now. */
@Composable
private fun TrackPicker(vm: PlayerViewModel, type: Int, onClose: () -> Unit) {
    val version by vm.tracksVersion.collectAsStateWithLifecycle()
    val choices = remember(version) { vm.trackChoices(type) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(50); runCatching { first.requestFocus() } }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        containerColor = Vanta.Panel,
        title = { Text(if (type == C.TRACK_TYPE_AUDIO) "Audio" else "Subtitles", color = Vanta.Text) },
        text = {
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val real = choices.count { it.group != null }
                if (real == 0) Text(
                    if (type == C.TRACK_TYPE_AUDIO) "This channel has one audio track." else "This channel has no subtitles.",
                    color = Vanta.TextDim,
                )
                choices.forEachIndexed { i, c ->
                    if (c.group == null && real == 0) return@forEachIndexed
                    FocusCard(
                        onClick = { vm.pickTrack(type, c); onClose() }, selected = c.selected,
                        modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                    ) {
                        Text((if (c.selected) "✓  " else "     ") + c.label, color = if (c.selected) Vanta.Cyan else Vanta.Text)
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("Close") } },
    )
}

/** The category's channels under the video when the phone is upright. Tap one to switch to it. */
@Composable
private fun PortraitChannelList(
    channels: List<com.vantaview.app.data.db.ChannelEntity>,
    current: Int,
    modifier: Modifier,
    onPick: (Int) -> Unit,
    onFull: (Int) -> Unit,
) {
    val state = rememberLazyListState()
    LaunchedEffect(current, channels.size) {
        if (channels.isNotEmpty()) state.animateScrollToItem((current - 2).coerceAtLeast(0))
    }
    LazyColumn(
        modifier,
        state = state,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        itemsIndexed(channels, key = { _, c -> c.id }) { i, c ->
            val event = ChannelNames.isEventOnly(c.name)
            // Tap to switch; double-tap to switch and go full screen.
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (i == current) Vanta.SurfaceHigh else Vanta.Surface)
                    .pointerInput(i) { detectTapGestures(onTap = { onPick(i) }, onDoubleTap = { onFull(i) }) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(if (event && i != current) 0.65f else 1f)) {
                    Text(c.number.toString(), color = Vanta.TextDim, fontSize = 13.sp, modifier = Modifier.width(44.dp), maxLines = 1)
                    Box(
                        Modifier.size(width = 52.dp, height = 32.dp).clip(RoundedCornerShape(6.dp)).background(Vanta.Bg),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (c.logo != null) AsyncImage(
                            model = c.logo, contentDescription = null, contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(2.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        ChannelNames.display(c.name) + if (i == current) "  · now playing" else "",
                        color = if (i == current) Vanta.Cyan else Vanta.Text,
                        fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private val hhmm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK)

/** Now: title, times and progress, then Next. Used over the video in full screen. */
@Composable
private fun NowInfo(ui: PlayerUi, modifier: Modifier = Modifier) {
    val now = ui.now ?: return
    val t = System.currentTimeMillis()
    Column(modifier) {
        Text(
            "Now: ${now.title}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${hhmm.format(java.util.Date(now.start))} – ${hhmm.format(java.util.Date(now.stop))}", color = Vanta.TextDim, fontSize = 13.sp)
            Spacer(Modifier.width(10.dp))
            val f = ((t - now.start).toFloat() / (now.stop - now.start).coerceAtLeast(1)).coerceIn(0f, 1f)
            Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x55FFFFFF))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(Vanta.Cyan))
            }
        }
        ui.next?.let { n ->
            Text("Next: ${hhmm.format(java.util.Date(n.start))} ${n.title}", color = Vanta.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Under the video on an upright phone: what's on now with its description, and what's next. */
@Composable
private fun NowStrip(ui: PlayerUi) {
    val now = ui.now
    Column(Modifier.fillMaxWidth().background(Vanta.Surface).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ui.number.toString(), color = Vanta.Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(ui.title, color = Vanta.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (now == null) {
            Text("No guide information for this channel", color = Vanta.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            return@Column
        }
        Spacer(Modifier.height(4.dp))
        NowInfo(ui.copy(next = null))
        now.description?.let {
            Text(it, color = Vanta.TextDim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        ui.next?.let { n ->
            Text("Next: ${hhmm.format(java.util.Date(n.start))} ${n.title}", color = Vanta.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** What a swipe is changing: brightness (0..1) or volume (0..2). */
private data class Level(val brightness: Boolean, val value: Float)

@Composable
private fun LevelBadge(l: Level, modifier: Modifier) {
    val pct = (l.value * 100).roundToInt()
    val boosted = !l.brightness && l.value > 1f
    val max = if (l.brightness) 1f else PlayerLevels.MAX
    Column(
        modifier.background(Color(0xCC000000), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (l.brightness) "☀" else if (pct == 0) "🔇" else "🔊", fontSize = 22.sp)
        Box(Modifier.padding(vertical = 8.dp).size(width = 8.dp, height = 140.dp).background(Color(0x33FFFFFF), RoundedCornerShape(4.dp)), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier.fillMaxWidth().fillMaxHeight((l.value / max).coerceIn(0f, 1f))
                    .background(if (boosted) Color(0xFFFF9100) else Vanta.Cyan, RoundedCornerShape(4.dp))
            )
        }
        Text("$pct%", color = if (boosted) Color(0xFFFF9100) else Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        if (boosted) Text("Boost", color = Color(0xFFFF9100), fontSize = 11.sp)
    }
}

/** A channel up or down button, with the chevron used on TV remotes' CH buttons. */
@Composable
private fun ChannelStep(up: Boolean, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.material3.Icon(
            if (up) androidx.compose.material.icons.Icons.Filled.KeyboardArrowUp else androidx.compose.material.icons.Icons.Filled.KeyboardArrowDown,
            contentDescription = null, tint = Vanta.Text,
        )
        Text("CH", color = Vanta.TextDim, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Text(label, color = Vanta.Text)
    }
}
