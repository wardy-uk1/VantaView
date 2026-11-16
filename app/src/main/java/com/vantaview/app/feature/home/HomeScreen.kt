// SPDX-License-Identifier: GPL-3.0-or-later
@file:kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.vantaview.app.feature.home

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import com.vantaview.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.vantaview.app.data.db.ChannelEntity
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.GradientButton
import com.vantaview.app.ui.Vanta
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Yellow = Color(0xFFFFC81A)

@OptIn(UnstableApi::class)
@Composable
fun HomeScreen(
    onSection: (String) -> Unit,
    onOpenTitle: (type: String, id: String, name: String, poster: String?, play: String?) -> Unit,
    onPlayChannel: (Long) -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val tiles by vm.tiles.collectAsStateWithLifecycle()
    val cont by vm.continueWatching.collectAsStateWithLifecycle()
    val favs by vm.favourites.collectAsStateWithLifecycle()
    val watchlist by vm.watchlist.collectAsStateWithLifecycle()
    val withNew by vm.withNewEpisode.collectAsStateWithLifecycle()
    val newShares by vm.newShares.collectAsStateWithLifecycle()
    val onNow by vm.onNow.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val newSeason by vm.newSeason.collectAsStateWithLifecycle()
    // Settings › Playback › Start on last channel (once, when the app starts).
    val openChannel by vm.openChannel.collectAsStateWithLifecycle()
    LaunchedEffect(openChannel) { openChannel?.let { vm.openedChannel(); onPlayChannel(it) } }

    // The muted preview only runs while Home is on screen (it shares the provider connection).
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onHidden() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.onShown() }

    val weather by vm.weather.collectAsStateWithLifecycle()
    val previewNow by vm.previewNow.collectAsStateWithLifecycle()
    var showForecast by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val askLocation = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) vm.refreshWeather(force = true) else onSection("settings") }
    // First launch: ask once for rough location so the weather is for here. Saying no still shows
    // weather for the device's time zone city; a town can be picked in Settings.
    val firstAsk = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { vm.refreshWeather(force = true) }
    LaunchedEffect(Unit) {
        if (vm.shouldAskLocation()) {
            vm.markAskedLocation()
            runCatching { firstAsk.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION) }
                .onFailure { vm.refreshWeather(force = true) }
        }
    }

    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val pullState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(15_000) } }

    BoxWithConstraints(Modifier.fillMaxSize().background(Vanta.Page)) {
        val wide = maxWidth >= 720.dp
        // Console looks (Settings › Appearance) turn Home into a dashboard on TVs and wide screens.
        if (wide && Vanta.Look.dashboard) {
            val profile by vm.profile.collectAsStateWithLifecycle()
            ConsoleHome(
                Vanta.Look,
                Dash(
                    now, profile, cont, favs, onNow, preview, previewNow, vm, onSection,
                    onOpen = { c -> onOpenTitle(c.entry.type, c.entry.metaId, c.entry.title, c.entry.poster, c.playVideoId?.let { v -> if (c.entry.type == "movie") "movie" else v }) },
                    onPlayChannel = onPlayChannel,
                ),
            )
        } else androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = refreshing, onRefresh = vm::refreshAll, modifier = Modifier.fillMaxSize(), state = pullState,
            // Pulling is for touch screens; on a TV the Refresh button shows its own spinner.
            indicator = {
                if (!com.vantaview.app.ui.TvInput.isTv) androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator(
                    state = pullState, isRefreshing = refreshing, modifier = Modifier.align(Alignment.TopCenter),
                )
            },
        ) { Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Greeting that follows the time of day, with a quick weather glance.
            GreetingRow(
                now = now,
                weather = weather,
                showSetUp = weather == null && vm.weatherEnabled && remember(weather) { !vm.weatherHasSource() },
                onWeather = { showForecast = true },
                onSearch = { onSection("search") },
                onRefresh = vm::refreshAll,
                refreshing = refreshing,
                onSetUp = {
                    val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                        context, android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (granted) onSection("settings")
                    else runCatching { askLocation.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION) }
                        .onFailure { onSection("settings") }
                },
            )

            // The big tiles: Catch-up joins them when a playlist has channels that keep past programmes.
            val byRoute = tiles.toMap()
            val order = listOf("live", "guide", "movies", "series") + listOfNotNull("catchup".takeIf { it in byRoute })
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().height(150.dp)) {
                    order.forEach { r -> BigTile(r, byRoute[r]?.subtitle, Modifier.weight(1f).fillMaxHeight()) { onSection(r) } }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    order.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().height(128.dp)) {
                            pair.forEach { r -> BigTile(r, byRoute[r]?.subtitle, Modifier.weight(1f).fillMaxHeight()) { onSection(r) } }
                        }
                    }
                }
            }

            // "Mum shared Doctor Who with you", until dismissed.
            newShares.firstOrNull()?.let { sh ->
                Row(
                    Modifier.fillMaxWidth().background(Vanta.Surface, RoundedCornerShape(14.dp)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            com.vantaview.app.ui.tr("{0} shared {1} with you", sh.from, sh.title.name) + if (newShares.size > 1) " " + com.vantaview.app.ui.tr("(and {0} more)", newShares.size - 1) else "",
                            color = Vanta.Text, fontWeight = FontWeight.Bold,
                        )
                        sh.message?.let { Text("\u201C$it\u201D", color = Vanta.TextDim, fontSize = 14.sp, maxLines = 2) }
                    }
                    FocusCard(onClick = { vm.dismissShares(); onOpenTitle(sh.title.type, sh.title.id, sh.title.name, sh.title.poster, null) }) {
                        Text("Open", color = Vanta.Text, maxLines = 1, softWrap = false)
                    }
                    FocusCard(onClick = vm::dismissShares) { Text("Dismiss", color = Vanta.Text, maxLines = 1, softWrap = false) }
                }
            }

            // Last watched channel: small muted preview, what's on, and a play button.
            preview?.let { p ->
                LastWatchedBar(p, previewNow, vm, wide, onFull = { onPlayChannel(p.channel.id) })
            }

            if (cont.isNotEmpty()) {
                com.vantaview.app.ui.SectionTitle("Continue watching", onClick = { onSection("library?tab=continue") })
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(cont, key = { it.entry.key }) { c ->
                        ContinueCard(c, Modifier.width(if (wide) 256.dp else 200.dp)) {
                            onOpenTitle(c.entry.type, c.entry.metaId, c.entry.title, c.entry.poster, c.playVideoId?.let { v -> if (c.entry.type == "movie") "movie" else v })
                        }
                    }
                }
            }

            // The Watchlist; series with a new episode out are marked and come first.
            if (watchlist.isNotEmpty()) {
                com.vantaview.app.ui.SectionTitle("Your Watchlist", onClick = { onSection("library?tab=watchlist") })
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(watchlist.sortedByDescending { it.id in withNew }, key = { "wl-" + it.type + it.id }) { t ->
                        com.vantaview.app.feature.vod.PosterCard(
                            com.vantaview.app.data.addon.MetaPreview(t.id, t.type, t.name, t.poster, t.year, null),
                            onClick = { onOpenTitle(t.type, t.id, t.name, t.poster, null) },
                            badge = if (t.id in withNew) "New episode" else null,
                        )
                    }
                }
            }

            if (onNow.isNotEmpty()) {
                com.vantaview.app.ui.SectionTitle("On now", onClick = { onSection("guide") })
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(onNow, key = { it.first.id }) { (ch, p) -> OnNowCard(ch, p, now.time) { onPlayChannel(ch.id) } }
                }
            }

            if (favs.isNotEmpty()) {
                com.vantaview.app.ui.SectionTitle("Favourite channels", onClick = { onSection("live") })
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(favs, key = { it.id }) { ch -> ChannelCard(ch) { com.vantaview.app.feature.player.ZapList.set(favs.map { it.id }); onPlayChannel(ch.id) } }
                }
            }

            if (cont.isEmpty() && favs.isEmpty() && watchlist.isEmpty()) {
                Text(
                    "Films and episodes you start appear under Continue watching, and channels you star appear here as favourites.",
                    color = Vanta.TextDim, fontSize = 14.sp,
                )
            }
            Spacer(Modifier.height(16.dp))
        } }
    }

    if (showForecast) weather?.let { w -> ForecastDialog(w) { showForecast = false } }

    newSeason?.let { ns ->
        AlertDialog(
            onDismissRequest = vm::dismissNewSeason,
            containerColor = Vanta.Panel,
            title = null,
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.width(110.dp).height(165.dp).clip(RoundedCornerShape(10.dp)).background(Vanta.SurfaceHigh)) {
                        ns.entry.poster?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        Badge("NEW SEASON", Yellow, Modifier.padding(6.dp))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("SEASON ${ns.season} IS HERE", color = Yellow, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(ns.entry.title, color = Vanta.Text, fontSize = 24.sp, fontWeight = FontWeight.Black, maxLines = 2)
                        Text(
                            if (ns.episodes == 1) com.vantaview.app.ui.tr("1 new episode is ready to watch. Pick up with S{0} E{1}.", ns.season, ns.first.episode)
                            else com.vantaview.app.ui.tr("{0} new episodes are ready to watch. Pick up with S{1} E{2}.", ns.episodes, ns.season, ns.first.episode),
                            color = Vanta.TextDim,
                        )
                    }
                }
            },
            confirmButton = {
                GradientButton("▶  Watch now", onClick = {
                    vm.dismissNewSeason()
                    onOpenTitle("series", ns.entry.metaId, ns.entry.title, ns.entry.poster, ns.first.id)
                })
            },
            dismissButton = { TextButton(onClick = vm::dismissNewSeason) { Text("Later") } },
        )
    }
}

@Composable
private fun Badge(text: String, colour: Color, modifier: Modifier = Modifier) {
    Text(
        text, color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Black,
        modifier = modifier.background(colour, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun SectionTile(t: Tile, modifier: Modifier, onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = modifier, padding = PaddingValues(10.dp), contentAlignment = Alignment.TopStart) {
        Column(Modifier.fillMaxSize()) {
            // 2 x 2 mosaic of channel logos or posters.
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (r in 0..1) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in 0..1) {
                        val img = t.images.getOrNull(r * 2 + c)
                        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(Vanta.SurfaceHigh)) {
                            if (img != null) AsyncImage(
                                model = img, contentDescription = null,
                                contentScale = if (t.title == "LIVE TV" || t.title == "TV GUIDE") ContentScale.Fit else ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().padding(if (t.title == "LIVE TV" || t.title == "TV GUIDE") 6.dp else 0.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(t.title, color = Vanta.Text, fontWeight = FontWeight.Black, fontSize = 15.sp)
            Text(t.subtitle, color = Vanta.TextDim, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
internal fun PreviewBox(p: Preview?, vm: HomeViewModel, modifier: Modifier, onFull: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Vanta.Surface)) {
        if (p == null) {
            Text(
                "Watch a live channel and it will play here.",
                color = Vanta.TextDim, modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
            return@Box
        }
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    player = vm.player
                }
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        // Double-tap the preview to watch full screen.
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onDoubleTap = { onFull() }) })
        Badge("LIVE", Color(0xFFFF3B4E), Modifier.padding(10.dp))
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(p.channel.name, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(p.error ?: (p.channel.categoryRemoteId?.let { "Last watched" } ?: "Last watched"), color = Vanta.TextDim, fontSize = 12.sp)
            }
            FocusCard(onClick = vm::toggleMute, shape = CircleShape, padding = PaddingValues(10.dp), contentAlignment = Alignment.Center) {
                Text(if (p.muted) "🔇" else "🔊", fontSize = 16.sp)
            }
            Spacer(Modifier.width(8.dp))
            FocusCard(onClick = onFull, shape = CircleShape, padding = PaddingValues(10.dp), contentAlignment = Alignment.Center) {
                Text("⛶", color = Color.White, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun ContinueCard(c: ContinueItem, modifier: Modifier, onClick: () -> Unit) {
    // Press and hold (or Menu on a remote): remove from Continue watching, Watchlist, Watched, Share.
    var actions by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (actions) com.vantaview.app.feature.vod.TitleActionsDialog(
        com.vantaview.app.data.addon.MetaPreview(c.entry.metaId, c.entry.type, c.entry.title, c.entry.poster, null, null),
        onDismiss = { actions = false },
    )
    Column(modifier) {
        FocusCard(onClick = onClick, onLongClick = { actions = true }, padding = PaddingValues(0.dp), modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Vanta.SurfaceHigh)) {
                (c.entry.background ?: c.entry.poster)?.let {
                    AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                when (c.badge) {
                    Badge.NEW_SEASON -> Badge("NEW SEASON", Yellow, Modifier.padding(8.dp))
                    Badge.COMING_SOON -> Badge("COMING SOON", Vanta.TextDim, Modifier.padding(8.dp))
                    null -> Unit
                }
                c.progress?.let { f ->
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66FFFFFF))) {
                        Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(Vanta.GradientBrush))
                    }
                }
            }
        }
        Text(c.entry.title, color = Vanta.Text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(c.line, color = if (c.badge == Badge.NEW_SEASON) Yellow else Vanta.TextDim, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun ChannelCard(ch: ChannelEntity, onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = Modifier.width(220.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Vanta.Bg), contentAlignment = Alignment.Center) {
                if (ch.logo != null) AsyncImage(model = ch.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
                else Text(ch.name.take(2).uppercase(), color = Vanta.TextDim, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(ch.name, color = Vanta.Text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Channel ${ch.number}", color = Vanta.TextDim, fontSize = 12.sp)
            }
        }
    }
}

/** A favourite channel with what's on it now and how far through it is. */
@Composable
private fun OnNowCard(ch: ChannelEntity, p: com.vantaview.app.data.db.ProgrammeEntity, now: Long, onClick: () -> Unit) {
    val clock = SimpleDateFormat("HH:mm", Locale.UK)
    FocusCard(onClick = onClick, modifier = Modifier.width(260.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(Vanta.Bg), contentAlignment = Alignment.Center) {
                    if (ch.logo != null) AsyncImage(model = ch.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    com.vantaview.app.data.ChannelNames.display(ch.name), color = Vanta.TextDim, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(p.title, color = Vanta.Text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${clock.format(Date(p.start))} – ${clock.format(Date(p.stop))}", color = Vanta.TextDim, fontSize = 12.sp)
            val f = ((now - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1)).coerceIn(0f, 1f)
            Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Vanta.SurfaceHigh)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(Vanta.Cyan))
            }
        }
    }
}

/** "Good morning/afternoon/evening", the date and time, and the weather on the right. */
@Composable
private fun GreetingRow(
    now: Date,
    weather: com.vantaview.app.data.weather.Weather?,
    showSetUp: Boolean,
    onWeather: () -> Unit,
    onSetUp: () -> Unit,
    onSearch: () -> Unit,
    onRefresh: () -> Unit = {},
    refreshing: Boolean = false,
) {
    val hour = java.util.Calendar.getInstance().apply { time = now }.get(java.util.Calendar.HOUR_OF_DAY)
    val greeting = when (hour) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }
    val date = SimpleDateFormat("EEEE d MMMM · HH:mm", com.vantaview.app.ui.I18n.locale).format(now)
    val search: @Composable () -> Unit = {
        FocusCard(onClick = onSearch, shape = CircleShape, padding = PaddingValues(12.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Search, contentDescription = "Search", tint = Vanta.Text)
        }
    }
    val refresh: @Composable () -> Unit = {
        FocusCard(onClick = onRefresh, shape = CircleShape, padding = PaddingValues(12.dp), contentAlignment = Alignment.Center) {
            if (refreshing) androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), color = Vanta.Cyan, strokeWidth = 2.dp)
            else Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = Vanta.Text)
        }
    }
    val weatherCard: @Composable () -> Unit = {
        when {
            weather != null -> FocusCard(onClick = onWeather, padding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.vantaview.app.ui.WeatherIcon(weather.code, weather.isDay, Modifier.size(30.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("${weather.temp}°", color = Vanta.Text, fontSize = 20.sp, fontWeight = FontWeight.Black)
                        Text(
                            "${com.vantaview.app.data.weather.WeatherRepository.describe(weather.code)} · ${weather.high}° / ${weather.low}°",
                            color = Vanta.TextDim, fontSize = 11.sp, maxLines = 1,
                        )
                    }
                }
            }
            showSetUp -> FocusCard(onClick = onSetUp, padding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.vantaview.app.ui.WeatherIcon(2, true, Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Show weather", color = Vanta.Text, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 480.dp) {
            // A phone held upright: greeting and search on one line, the date, then the weather underneath.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(greeting, color = Vanta.Text, fontSize = 24.sp, fontWeight = FontWeight.Black, maxLines = 1)
                        Text(date, color = Vanta.TextDim, fontSize = 14.sp, maxLines = 1)
                    }
                    search()
                }
                weatherCard()
            }
        } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(greeting, color = Vanta.Text, fontSize = 26.sp, fontWeight = FontWeight.Black)
                Text(date, color = Vanta.TextDim, fontSize = 14.sp)
            }
            refresh()
            search()
            weatherCard()
        }
    }
}

private val tileLook = mapOf(
    "live" to Triple("Live TV", Icons.Filled.PlayArrow, listOf(Color(0xFF2F7BFF), Color(0xFF14E0E0))),
    "guide" to Triple("TV Guide", Icons.Filled.DateRange, listOf(Color(0xFF7B5CFF), Color(0xFF2F7BFF))),
    "movies" to Triple("Movies", Icons.Filled.Star, listOf(Color(0xFFFF5A14), Color(0xFFFFC81A))),
    "series" to Triple("TV Series", Icons.Filled.List, listOf(Color(0xFF19E57A), Color(0xFF0F8C78))),
    "catchup" to Triple("Catch-up", Icons.Filled.Refresh, listOf(Color(0xFFE5197A), Color(0xFF8C2FFF))),
)

/** A big coloured tile with an icon, the section name and a short line. */
/** A soft shadow so white tile text stays readable on the bright (yellow, cyan) gradients. */
private val tileShadow = androidx.compose.ui.text.TextStyle(
    shadow = androidx.compose.ui.graphics.Shadow(Color(0x99000000), androidx.compose.ui.geometry.Offset(0f, 2f), 6f),
)

@Composable
private fun BigTile(route: String, subtitle: String?, modifier: Modifier, onClick: () -> Unit) {
    val (title, icon, colours) = tileLook[route] ?: return
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(Brush.linearGradient(colours))
            .then(if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x40070810)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = Color.White) }
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black, style = tileShadow)
            subtitle?.let {
                Text(
                    if (route == "guide") "What's on now" else it,
                    color = Color.White.copy(alpha = 0.92f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    style = tileShadow,
                )
            }
        }
    }
}

/** The last channel watched: a small muted preview (double-tap for full screen), what's on, sound and play. */
@OptIn(UnstableApi::class)
@Composable
private fun LastWatchedBar(
    p: Preview,
    now: com.vantaview.app.data.db.ProgrammeEntity?,
    vm: HomeViewModel,
    wide: Boolean,
    onFull: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Vanta.Surface).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = if (wide) 200.dp else 128.dp, height = if (wide) 112.dp else 72.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
            if (vm.miniPlayer) AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        player = vm.player
                    }
                },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            ) else coil.compose.AsyncImage(
                model = p.channel.logo, contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(14.dp),
            )
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onDoubleTap = { onFull() }, onTap = { onFull() }) })
            Badge("LIVE", Color(0xFFFF3B4E), Modifier.padding(6.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                com.vantaview.app.data.ChannelNames.display(p.channel.name) + " · last watched",
                color = Vanta.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                p.error ?: now?.title ?: "Live now", color = Vanta.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (now != null) {
                val t = System.currentTimeMillis()
                val f = ((t - now.start).toFloat() / (now.stop - now.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Vanta.SurfaceHigh)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(Vanta.Cyan))
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        if (vm.miniPlayer) {
            FocusCard(onClick = vm::toggleMute, shape = CircleShape, padding = PaddingValues(10.dp), contentAlignment = Alignment.Center) {
                Text(if (p.muted) "🔇" else "🔊", fontSize = 15.sp)
            }
            Spacer(Modifier.width(8.dp))
        }
        // Play: shows a ring when the TV remote is on it.
        var playFocused by remember { mutableStateOf(false) }
        Box(
            Modifier.size(46.dp).onFocusChanged { playFocused = it.isFocused }
                .then(if (playFocused) Modifier.border(3.dp, Vanta.Accent, CircleShape) else Modifier)
                .clip(CircleShape).background(Vanta.Text).clickable(onClick = onFull),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.PlayArrow, contentDescription = "Watch full screen", tint = Vanta.Bg) }
    }
}

/** Today and the next four days. */
@Composable
private fun ForecastDialog(w: com.vantaview.app.data.weather.Weather, onClose: () -> Unit) {
    val dayName = SimpleDateFormat("EEE", com.vantaview.app.ui.I18n.locale)
    val parse = SimpleDateFormat("yyyy-MM-dd", Locale.UK)
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Vanta.Panel,
        title = { Text(w.place ?: "Weather where you are", color = Vanta.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                w.days.forEachIndexed { i, d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (i == 0) "Today" else runCatching { dayName.format(parse.parse(d.date)!!) }.getOrDefault(d.date),
                            color = Vanta.Text, modifier = Modifier.width(64.dp),
                        )
                        com.vantaview.app.ui.WeatherIcon(d.code, true, Modifier.size(26.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(com.vantaview.app.data.weather.WeatherRepository.describe(d.code), color = Vanta.TextDim, modifier = Modifier.weight(1f), maxLines = 1)
                        Text("${d.high}°", color = Vanta.Text, fontWeight = FontWeight.Bold)
                        Text("  ${d.low}°", color = Vanta.TextDim)
                    }
                }
                Text("Weather data by Open-Meteo.com", color = Vanta.TextDim, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}
