// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import com.vantaview.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.vantaview.app.data.ChannelNames
import com.vantaview.app.data.db.ChannelEntity
import com.vantaview.app.data.db.ProgrammeEntity
import com.vantaview.app.data.guide.GuideState
import com.vantaview.app.data.guide.XmltvTime
import com.vantaview.app.feature.live.CategoryRow
import com.vantaview.app.feature.live.LiveViewModel
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.Vanta
import com.vantaview.app.ui.scrollWhenFocused
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val MINUTE = 60_000L
private const val DAY = 24 * 60 * MINUTE
private val clock = SimpleDateFormat("HH:mm", Locale.UK)

/** One selected programme and the channel it's on, shown in the panel at the top. */
private data class Picked(val channel: ChannelEntity, val programme: ProgrammeEntity?)

/**
 * TV Guide: channels down the side, times across the top. Uses the same channel list, categories and
 * countries as Live TV. Days run from yesterday to a week ahead.
 */
@Composable
fun GuideScreen(onPlay: (Long) -> Unit, onCatchUp: () -> Unit = {}, onCatchUpPage: () -> Unit = {}, vm: LiveViewModel = hiltViewModel()) {
    val providers by vm.providers.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val rows by vm.channels.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val guideState by vm.guideState.collectAsStateWithLifecycle()
    val guideVersion by vm.guideVersion.collectAsStateWithLifecycle()

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }

    var day by remember { mutableIntStateOf(0) } // -1 = yesterday … 7
    val dayStart = remember(day) { XmltvTime.dayStart(day) }
    var picked by remember { mutableStateOf<Picked?>(null) }

    // Hold Back (a TV remote): straight back to the channel last watched, full screen. A short press is still Back.
    var backHeld by remember { mutableStateOf(false) }
    val holdBack = Modifier.onPreviewKeyEvent { e ->
        if (e.key != androidx.compose.ui.input.key.Key.Back) return@onPreviewKeyEvent false
        val native = e.nativeKeyEvent
        when {
            e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && native.repeatCount > 0 -> {
                if (!backHeld) {
                    val last = vm.lastChannelId()
                    if (last > 0) { backHeld = true; onPlay(last) }
                }
                backHeld
            }
            // The Back released after a hold mustn't also close the page the player returns to.
            e.type == androidx.compose.ui.input.key.KeyEventType.KeyUp && backHeld -> { backHeld = false; true }
            else -> false
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Vanta.Page).then(holdBack)) {
        val wide = maxWidth >= 720.dp
        val density = LocalDensity.current
        val minutePx = with(density) { (if (wide) 5.dp else 4.dp).toPx() }
        val chanW = if (wide) 210.dp else 118.dp
        val chanPx = with(density) { chanW.toPx() }
        val gridPx = with(density) { maxWidth.toPx() } - chanPx - with(density) { 32.dp.toPx() }
        val maxScroll = (24 * 60 * minutePx - gridPx).coerceAtLeast(0f)

        // Horizontal position of the timeline in pixels from the day's midnight.
        var scrollX by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(day, gridPx) {
            val anchor = if (day == 0) now - 30 * MINUTE else dayStart + 18 * 60 * MINUTE
            scrollX = (((anchor - dayStart) / MINUTE) * minutePx).coerceIn(0f, maxScroll)
        }
        fun xOf(t: Long): Float = ((t - dayStart).toFloat() / MINUTE) * minutePx - scrollX

        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            // Title, status and refresh.
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.vantaview.app.ui.PageTitle("TV Guide")
                Spacer(Modifier.width(12.dp))
                val status = when (val s = guideState) {
                    is GuideState.Loading -> s.stage + "…"
                    is GuideState.Failed -> s.message
                    else -> null
                }
                if (status != null) {
                    Text(
                        status, color = if (guideState is GuideState.Failed) Vanta.Error else Vanta.Cyan, fontSize = 13.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                } else Spacer(Modifier.weight(1f))
                // On a TV, a reminder of the hold-Back shortcut.
                if (com.vantaview.app.ui.TvInput.isTv && remember { vm.lastChannelId() } > 0) {
                    Text("Hold Back for full screen", color = Vanta.TextDim, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(end = 10.dp))
                }
                if (providers.isNotEmpty()) {
                    FocusCard(onClick = vm::refreshGuide, padding = PaddingValues(10.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reload the TV guide", tint = Vanta.Text)
                    }
                }
            }
            if (providers.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Add a playlist to see its TV guide.", color = Vanta.TextDim)
                }
                return@Column
            }
            Spacer(Modifier.height(8.dp))

            // Days: yesterday to a week ahead.
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items((-1..7).toList()) { d ->
                    val label = when (d) {
                        -1 -> "Yesterday"
                        0 -> "Today"
                        1 -> "Tomorrow"
                        else -> SimpleDateFormat("EEE d", com.vantaview.app.ui.I18n.locale).format(Date(XmltvTime.dayStart(d)))
                    }
                    com.vantaview.app.feature.live.FilterChip(label, d == day) { day = d; picked = null }
                }
            }
            Spacer(Modifier.height(8.dp))
            val radioCount by vm.radioCount.collectAsStateWithLifecycle()
            CategoryRow(categories, filter, radioCount > 0) { vm.filter.value = it }
            Spacer(Modifier.height(8.dp))

            // Details of the chosen programme.
            val remindersSet by vm.reminderList.collectAsStateWithLifecycle()
            val notify = rememberNotificationAsk()
            val catchUpMessage by vm.catchUpMessage.collectAsStateWithLifecycle()
            LaunchedEffect(picked) { vm.catchUpMessage.value = null }
            DetailsPanel(
                picked, now, wide, onPlay,
                reminded = { c, pr -> remindersSet.any { it.channelId == c.id && it.start == pr.start } },
                onRemind = { c, pr -> if (vm.toggleReminder(c, pr)) notify() },
                canCatchUp = { c, pr -> vm.hasCatchUp(c, pr) },
                onCatchUp = { c, pr -> vm.catchUp(c, pr, onCatchUp) },
                catchUpMessage = catchUpMessage,
            )

            // Time ruler.
            Row(Modifier.fillMaxWidth().height(26.dp)) {
                Spacer(Modifier.width(chanW))
                Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                    for (i in 0 until 48) {
                        val t = dayStart + i * 30 * MINUTE
                        val x = xOf(t)
                        if (x < -100 || x > gridPx + 10) continue
                        Text(
                            clock.format(Date(t)), color = Vanta.TextDim, fontSize = 12.sp,
                            modifier = Modifier.offset { IntOffset(x.roundToInt() + 4, 0) },
                        )
                    }
                }
            }

            // The grid. Drag sideways anywhere to move through the day; scroll up and down for channels.
            Box(
                Modifier.weight(1f).fillMaxWidth().pointerInput(maxScroll) {
                    detectHorizontalDragGestures { change, dx ->
                        change.consume()
                        scrollX = (scrollX - dx).coerceIn(0f, maxScroll)
                    }
                }
            ) {
                val list = remember(rows) { rows.filter { !ChannelNames.isDivider(it.channel.name) } }
                if (list.isEmpty()) {
                    Text("No channels here.", color = Vanta.TextDim, modifier = Modifier.align(Alignment.Center))
                }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(list, key = { it.channel.id }) { row ->
                        GuideRow(
                            channel = row.channel,
                            chanW = chanW,
                            wide = wide,
                            dayStart = dayStart,
                            gridPx = gridPx,
                            version = guideVersion,
                            xOf = ::xOf,
                            load = { c -> vm.programmes(c, dayStart, dayStart + DAY) },
                            picked = picked,
                            now = now,
                            onPick = { p -> picked = p },
                            onFocusProgramme = { p ->
                                // Keep a focused programme on screen when moving with a remote.
                                val x = xOf(p.start.coerceAtLeast(dayStart))
                                if (x < 0 || x > gridPx - 80) {
                                    scrollX = (((p.start.coerceAtLeast(dayStart) - dayStart) / MINUTE) * minutePx - 40f).coerceIn(0f, maxScroll)
                                }
                            },
                            onPlay = onPlay,
                        )
                    }
                }
                // Now line.
                if (day == 0) {
                    val x = xOf(now)
                    if (x in 0f..gridPx) {
                        Box(
                            Modifier.offset { IntOffset((chanPx + x).roundToInt(), 0) }
                                .width(2.dp).fillMaxHeight().background(Vanta.Cyan)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailsPanel(
    picked: Picked?, now: Long, wide: Boolean, onPlay: (Long) -> Unit,
    reminded: (ChannelEntity, com.vantaview.app.data.db.ProgrammeEntity) -> Boolean,
    onRemind: (ChannelEntity, com.vantaview.app.data.db.ProgrammeEntity) -> Unit,
    canCatchUp: (ChannelEntity, com.vantaview.app.data.db.ProgrammeEntity) -> Boolean = { _, _ -> false },
    onCatchUp: (ChannelEntity, com.vantaview.app.data.db.ProgrammeEntity) -> Unit = { _, _ -> },
    catchUpMessage: String? = null,
) {
    val p = picked ?: return
    val prog = p.programme
    // Glass shows the programme big over the background; Broadcast adds the channel's logo.
    val look = Vanta.Look
    val glass = wide && look == com.vantaview.app.data.Look.GLASS
    val broadcast = wide && look == com.vantaview.app.data.Look.BROADCAST
    val open = glass || broadcast
    Column(
        if (open) Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp)
        else Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp)).background(Vanta.Surface).padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (broadcast) {
                Box(
                    Modifier.size(width = 96.dp, height = 64.dp).clip(RoundedCornerShape(8.dp)).background(Vanta.SurfaceHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    if (p.channel.logo != null) AsyncImage(
                        model = p.channel.logo, contentDescription = null, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(6.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                if (!glass) Text(
                    if (prog != null) "${clock.format(Date(prog.start))} – ${clock.format(Date(prog.stop))} · ${ChannelNames.display(p.channel.name)}"
                    else ChannelNames.display(p.channel.name),
                    color = Vanta.Cyan, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    prog?.title ?: "No guide information", color = Vanta.Text,
                    fontSize = if (glass) 30.sp else if (broadcast) 21.sp else 18.sp, fontWeight = FontWeight.Bold,
                    maxLines = if (glass) 1 else 2, overflow = TextOverflow.Ellipsis,
                )
                if (glass && prog != null) {
                    val mins = ((prog.stop - prog.start) / 60_000L).toInt()
                    val length = if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"
                    val started = when {
                        prog.start > now -> "Starts at ${clock.format(Date(prog.start))}"
                        prog.stop <= now -> "Aired at ${clock.format(Date(prog.start))}"
                        else -> "Started at ${clock.format(Date(prog.start))}"
                    }
                    Text(
                        "$length · $started · ${ChannelNames.display(p.channel.name)}", color = Vanta.Text, fontSize = 15.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val live = prog == null || (prog.start <= now && prog.stop > now)
            if (prog != null && prog.start > now) {
                val on = reminded(p.channel, prog)
                FocusCard(onClick = { onRemind(p.channel, prog) }, selected = on) {
                    Text(if (on) "🔔 Reminder set" else "🔔 Remind me", color = if (on) Vanta.Cyan else Vanta.Text, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(8.dp))
            }
            val catchUp = prog != null && canCatchUp(p.channel, prog)
            if (catchUp) {
                FocusCard(onClick = { onCatchUp(p.channel, prog!!) }) {
                    Text("⟲ Watch from start", color = Vanta.Cyan, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(8.dp))
            }
            FocusCard(onClick = { onPlay(p.channel.id) }) {
                Text(if (live) "▶ Watch" else "▶ Watch channel", color = Vanta.Text, fontWeight = FontWeight.Bold)
            }
        }
        val extra = listOfNotNull(prog?.category, prog?.description).joinToString(" · ")
        if (extra.isNotBlank()) {
            Text(
                extra, color = if (open) Vanta.Text.copy(alpha = 0.9f) else Vanta.TextDim, fontSize = if (open) 15.sp else 13.sp,
                maxLines = if (wide) 3 else 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp).then(if (open) Modifier.fillMaxWidth(0.7f) else Modifier),
            )
        }
        if (glass && prog != null && prog.start <= now && prog.stop > now) {
            // How far through the programme we are.
            val done = ((now - prog.start).toFloat() / (prog.stop - prog.start)).coerceIn(0f, 1f)
            Box(Modifier.padding(top = 10.dp).width(260.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Vanta.Text.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxWidth(done).fillMaxHeight().background(Vanta.Text))
            }
        }
        if (catchUpMessage != null) {
            Text(catchUpMessage, color = Vanta.Cyan, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        } else if (prog != null && prog.stop <= now) {
            Text(
                if (canCatchUp(p.channel, prog)) "Already aired. Your provider keeps a recording you can watch from the start."
                else "Already aired. This channel has no catch-up.",
                color = Vanta.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
            )
        } else if (prog != null && prog.start > now) {
            Text(
                com.vantaview.app.ui.tr("Starts at {0}.", clock.format(Date(prog.start))) + if (reminded(p.channel, prog)) " " + com.vantaview.app.ui.tr("You'll be reminded before it starts.") else "",
                color = Vanta.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun GuideRow(
    channel: ChannelEntity,
    chanW: androidx.compose.ui.unit.Dp,
    wide: Boolean,
    dayStart: Long,
    gridPx: Float,
    version: Int,
    xOf: (Long) -> Float,
    load: suspend (ChannelEntity) -> List<ProgrammeEntity>,
    picked: Picked?,
    now: Long,
    onPick: (Picked) -> Unit,
    onFocusProgramme: (ProgrammeEntity) -> Unit,
    onPlay: (Long) -> Unit,
) {
    val rowH = if (wide) 60.dp else 56.dp
    val density = LocalDensity.current
    val progs by produceState(initialValue = emptyList<ProgrammeEntity>(), channel.id, dayStart, version) {
        value = runCatching { load(channel) }.getOrDefault(emptyList())
    }
    Row(Modifier.fillMaxWidth().height(rowH), verticalAlignment = Alignment.CenterVertically) {
        // Channel: tap to watch.
        com.vantaview.app.ui.FillCard(
            onClick = { onPlay(channel.id) },
            modifier = Modifier.width(chanW - 4.dp).fillMaxHeight(),
            padding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
        ) { textColour, dimColour ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 40.dp, height = 28.dp).clip(RoundedCornerShape(5.dp)).background(Vanta.Bg), contentAlignment = Alignment.Center) {
                    if (channel.logo != null) AsyncImage(
                        model = channel.logo, contentDescription = null, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(2.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(channel.number.toString(), color = dimColour, fontSize = 11.sp, maxLines = 1)
                    Text(
                        ChannelNames.display(channel.name), color = textColour, fontSize = if (wide) 13.sp else 11.sp,
                        modifier = if (wide) Modifier.scrollWhenFocused() else Modifier,
                        maxLines = if (wide) 1 else 2, overflow = TextOverflow.Ellipsis, lineHeight = 13.sp,
                    )
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            if (progs.isEmpty()) {
                FocusCard(
                    onClick = { onPlay(channel.id) },
                    modifier = Modifier.fillMaxSize(),
                    padding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) { Text("No guide information", color = Vanta.TextDim, fontSize = 12.sp) }
            }
            for (p in progs) {
                val x0 = xOf(p.start).coerceAtLeast(xOf(dayStart))
                val x1 = xOf(p.stop)
                if (x1 < 0 || x0 > gridPx) continue
                val w = with(density) { (x1 - x0 - 3f).coerceAtLeast(6f).toDp() }
                val isNow = p.start <= now && p.stop > now
                val past = p.stop <= now
                val sel = picked?.programme?.id == p.id
                com.vantaview.app.ui.FillCard(
                    // Choosing a programme plays its channel; the player shows what's on now.
                    onClick = { onPlay(channel.id) },
                    selected = sel || isNow,
                    modifier = Modifier
                        .offset { IntOffset(x0.roundToInt(), 0) }
                        .width(w).fillMaxHeight()
                        .onFocusChanged { if (it.isFocused) { onPick(Picked(channel, p)); onFocusProgramme(p) } },
                    shape = RoundedCornerShape(8.dp),
                    padding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) { textColour, dimColour ->
                    Column {
                        Text(
                            p.title, color = if (past) dimColour else textColour, fontSize = 13.sp,
                            fontWeight = if (isNow) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Clip, modifier = Modifier.scrollWhenFocused(),
                        )
                        Text(clock.format(Date(p.start)), color = dimColour, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** On Android 13+, asks once for permission to show reminder notifications when VantaView is closed. */
@Composable
fun rememberNotificationAsk(): () -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    return {
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) runCatching { launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
    }
}
