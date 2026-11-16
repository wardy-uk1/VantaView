// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.vantaview.app.feature.vod

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vantaview.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.vantaview.app.data.addon.Video
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.GradientButton
import com.vantaview.app.ui.Vanta

@Composable
fun DetailsScreen(
    onPlay: () -> Unit,
    onBack: () -> Unit,
    onOpenTitle: (com.vantaview.app.data.addon.MetaPreview) -> Unit = {},
    onPerson: (String) -> Unit = {},
    vm: DetailsViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var noYouTube by remember { mutableStateOf(false) }
    var episodeActions by remember { mutableStateOf<Video?>(null) }
    var comingInfo by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var trailerOn by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(!vm.playback.trailerSound) }
    LaunchedEffect(ui.meta?.trailer) {
        if (vm.playback.autoTrailers && ui.meta?.trailer != null) { kotlinx.coroutines.delay(20_000); trailerOn = true }
    }
    val watchedEps by vm.watchedEps.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.play.collect { onPlay() } }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { vm.refreshProgress() }

    val meta = ui.meta
    val name = meta?.name?.ifBlank { null } ?: vm.fallbackName
    val poster = meta?.poster ?: vm.fallbackPoster
    // Stremio's "other" titles with several videos are shown like a series (episodes to pick).
    val isSeries = vm.type == "series" || (vm.type == "other" && (meta?.videos?.size ?: 0) > 1)

    BoxWithConstraints(Modifier.fillMaxSize().background(Vanta.Bg)) {
        val wide = maxWidth >= 720.dp
        meta?.background?.let { bg ->
            AsyncImage(
                model = bg, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(if (wide) 380.dp else 240.dp),
            )
        }
        // The trailer, playing behind the header after 20 seconds on the page (Settings › Playback).
        val yt = meta?.trailer
        if (trailerOn && yt != null) {
            TrailerView(yt, muted, Modifier.fillMaxWidth().height(if (wide) 380.dp else 240.dp))
        }
        if (meta?.background != null || (trailerOn && yt != null)) {
            Box(
                Modifier.fillMaxWidth().height(if (wide) 380.dp else 240.dp)
                    .background(Brush.verticalGradient(listOf(Color(0x6607080F), Vanta.Bg)))
            )
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                FocusCard(onClick = onBack) { Text("‹ Back", color = Vanta.Text) }
            }
            if (ui.blocked) {
                item { Text("This title isn't available on a Kids profile.", color = Vanta.Text, fontSize = 18.sp) }
                return@LazyColumn
            }
            item {
                Row(verticalAlignment = Alignment.Bottom) {
                    Box(Modifier.width(if (wide) 170.dp else 110.dp).height(if (wide) 255.dp else 165.dp).clip(RoundedCornerShape(12.dp)).background(Vanta.SurfaceHigh)) {
                        if (poster != null) AsyncImage(model = poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(name, color = Vanta.Text, fontSize = if (wide) 30.sp else 22.sp, fontWeight = FontWeight.Black, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        val facts = listOfNotNull(meta?.year, meta?.runtime, meta?.rating?.let { "IMDb $it" }).joinToString("  ·  ")
                        if (facts.isNotEmpty()) Text(facts, color = Vanta.TextDim, fontSize = 14.sp)
                        // Films: when it would end if started now (or resumed, from where it was left), in this device's time.
                        if (!isSeries) endsIfStarted(meta?.runtime, ui.progress?.takeIf { it.resumable }?.remainingMs)?.let {
                            Text(it, color = Vanta.Cyan, fontSize = 13.sp)
                        }
                        if (!meta?.genres.isNullOrEmpty()) Text(meta!!.genres.joinToString(", "), color = Vanta.TextDim, fontSize = 13.sp)
                        // Which of the provider's groups it's in, so the language or country is clear ("DE - FILME 2025/2026").
                        meta?.group?.let { g -> Text(com.vantaview.app.ui.tr("In your playlist: {0}", g), color = Vanta.Cyan, fontSize = 13.sp) }
                    }
                }
            }
            if (isSeries) vm.continueVideo()?.let { v ->
                item {
                    val p = ui.progress
                    val code = "S${v.season} E${v.episode}"
                    val label = when {
                        ui.finding == v.id -> "Finding streams…"
                        p != null && p.videoId == v.id && p.resumable -> "▶  Resume $code · ${minutesLeft(p.remainingMs)}"
                        else -> "▶  Play $code"
                    }
                    GradientButton(label, onClick = { vm.play(v, false) }, enabled = ui.finding == null)
                }
            }
            if (!isSeries) item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val p = ui.progress?.takeIf { it.resumable }
                    GradientButton(
                        when {
                            ui.finding != null -> "Finding streams…"
                            p != null -> "▶  Resume · ${minutesLeft(p.remainingMs)}"
                            else -> "▶  Play"
                        },
                        onClick = { vm.play(null, false) }, enabled = ui.finding == null,
                    )
                    FocusCard(onClick = { vm.play(null, true) }) { Text("Choose stream", color = Vanta.Text) }
                }
            }
            item {
                // Watchlist and Watched (shown in Library).
                val saved by vm.saved.collectAsStateWithLifecycle()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FocusCard(onClick = vm::toggleWatchlist, selected = saved.first) {
                        Text(if (saved.first) "✓ On Watchlist" else "+ Watchlist", color = if (saved.first) Vanta.Cyan else Vanta.Text)
                    }
                    FocusCard(onClick = vm::toggleWatched, selected = saved.second) {
                        Text(if (saved.second) "✓ Watched" else "Mark watched", color = if (saved.second) Vanta.Cyan else Vanta.Text)
                    }
                    meta?.trailer?.let { yt ->
                        // Trailers are on YouTube: open the YouTube app, or the website if there's no app.
                        FocusCard(onClick = {
                            val app = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("vnd.youtube:$yt"))
                            val web = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=$yt"))
                            noYouTube = runCatching { context.startActivity(app) }.recoverCatching { context.startActivity(web) }.isFailure
                        }) { Text("▶ Trailer", color = Vanta.Text) }
                        if (trailerOn) FocusCard(onClick = { muted = !muted }) { Text(if (muted) "🔇 Unmute" else "🔊 Mute", color = Vanta.Text) }
                        if (trailerOn) FocusCard(onClick = { trailerOn = false }) { Text("■ Stop trailer", color = Vanta.Text) }
                    }
                }
                if (noYouTube) Text("There's no YouTube app or browser here to play the trailer.", color = Vanta.TextDim, fontSize = 13.sp)
            }
            ui.message?.takeIf { ui.messageFor == null }?.let { m -> item { Text(m, color = Vanta.Error) } }
            if (ui.webLinks.isNotEmpty()) item { WebLinks(ui.webLinks) }
            meta?.description?.let { d -> item { Text(d, color = Vanta.Text, fontSize = 15.sp, lineHeight = 22.sp) } }
            if (!meta?.director.isNullOrEmpty()) item { Text(com.vantaview.app.ui.tr("Director: {0}", meta!!.director.joinToString(", ")), color = Vanta.TextDim, fontSize = 14.sp) }
            if (!meta?.cast.isNullOrEmpty()) item {
                if (vm.browse.hasTmdb) Column {
                    Text("Cast", color = Vanta.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    // Pick someone to see what else they're in.
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(meta!!.cast.take(15).size) { i ->
                            val person = meta.cast[i]
                            FocusCard(onClick = { onPerson(person) }) { Text(person, color = Vanta.Text, fontSize = 14.sp) }
                        }
                    }
                } else Text(com.vantaview.app.ui.tr("Cast: {0}", meta!!.cast.take(8).joinToString(", ")), color = Vanta.TextDim, fontSize = 14.sp)
            }
            if (ui.loading) item { Text("Loading details…", color = Vanta.TextDim) }
            else if (meta == null) item {
                Text(
                    if (isSeries) "None of your add-ons has details for this title, so its episodes can't be listed. Cinemeta usually fills this in." else "None of your add-ons has details for this title.",
                    color = Vanta.TextDim,
                )
            }

            if (isSeries && meta != null) {
                val seasons = meta.videos.map { it.season }.distinct().sorted()
                if (seasons.size > 1 || ui.comingSeasons.isNotEmpty()) item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(seasons) { s ->
                            FocusCard(onClick = { vm.selectSeason(s) }, selected = ui.season == s) {
                                Text(if (s == 0) "Specials" else "Season $s", color = if (ui.season == s) Vanta.Cyan else Vanta.Text)
                            }
                        }
                        // Seasons announced but not out yet.
                        items(ui.comingSeasons.filter { (n, _) -> n !in seasons }) { (n, date) ->
                            FocusCard(onClick = { comingInfo = n to date }) {
                                Text("Season $n · Coming soon", color = Vanta.TextDim, maxLines = 1)
                            }
                        }
                    }
                }
                comingInfo?.let { (n, date) ->
                    item {
                        Text(
                            (comingDate(date)?.let { com.vantaview.app.ui.tr("Season {0} is coming on {1}.", n, it) } ?: com.vantaview.app.ui.tr("Season {0} is coming (no date yet).", n)) +
                                " " + com.vantaview.app.ui.tr("Add the series to your Watchlist and Home will show a New episode badge when it starts."),
                            color = Vanta.Cyan, fontSize = 14.sp,
                        )
                    }
                }
                val episodes = meta.videos.filter { ui.season == null || it.season == ui.season }
                if (episodes.isEmpty()) item { Text("No episodes listed.", color = Vanta.TextDim) }
                items(episodes, key = { it.id }) { v ->
                    val p = ui.progress?.takeIf { it.videoId == v.id }
                    val note = when {
                        v.id in watchedEps -> "✓ Watched"
                        !episodeOut(v) -> comingDate(v.released)?.let { "Coming $it" } ?: "Coming soon"
                        p == null -> null
                        p.finished -> "✓ Watched"
                        p.resumable -> "Resume · ${minutesLeft(p.remainingMs)}"
                        else -> null
                    }
                    EpisodeRow(v, finding = ui.finding == v.id, note = ui.message?.takeIf { ui.messageFor == v.id } ?: note, problem = ui.messageFor == v.id && ui.message != null, onPlay = { vm.play(v, false) }, onMore = { episodeActions = v })
                }
            }

            // Similar titles go last, under the episodes.
            if (ui.moreLike.isNotEmpty()) item {
                Column {
                    Text("More like this", color = Vanta.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(ui.moreLike.size) { i ->
                            val m = ui.moreLike[i]
                            PosterCard(m, onClick = { vm.openSimilar(m, onOpenTitle) })
                        }
                    }
                }
            }
        }
    }

    episodeActions?.let { v ->
        EpisodeActionsDialog(
            v,
            watched = watchedEps.let { vm.isEpisodeWatched(v) },
            seasonWatched = watchedEps.let { vm.isSeasonWatched(v.season) },
            onWatched = { vm.setEpisodeWatched(v, it) },
            onSeasonWatched = { vm.setSeasonWatched(v.season, it) },
            onChooseStream = { episodeActions = null; vm.play(v, true) },
            onDismiss = { episodeActions = null },
        )
    }

    ui.choice?.let { c ->
        StreamListDialog(
            title = listOfNotNull(c.title, c.subtitle).joinToString(" · "),
            streams = c.streams, current = null, onPick = vm::pick, onDismiss = vm::dismissChoice,
        )
    }
}

@Composable
private fun EpisodeRow(v: Video, finding: Boolean, note: String?, problem: Boolean = false, onPlay: () -> Unit, onMore: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FocusCard(onClick = onPlay, onLongClick = onMore, modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 112.dp, height = 63.dp).clip(RoundedCornerShape(8.dp)).background(Vanta.Bg)) {
                    if (v.thumbnail != null) AsyncImage(model = v.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${v.episode}. ${v.title.ifBlank { "Episode ${v.episode}" }}",
                        color = Vanta.Text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when {
                            finding -> "Finding streams…"
                            note != null -> note
                            else -> v.overview ?: v.released?.take(10) ?: ""
                        },
                        color = if (problem) Vanta.Error else if (finding || note != null) Vanta.Cyan else Vanta.TextDim, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        FocusCard(onClick = onMore, padding = PaddingValues(12.dp), contentAlignment = Alignment.Center) {
            // Watched for this episode or its season, or choose a stream.
            Text("⋯", color = Vanta.Text, fontSize = 18.sp, modifier = Modifier.semantics { contentDescription = "More for this episode" })
        }
    }
}

/** A YouTube trailer in YouTube's own embedded player, without controls, behind the page header. */
@Composable
private fun TrailerView(videoId: String, muted: Boolean, modifier: Modifier) {
    val html = """
        <html style="height:100%"><body style="margin:0;height:100%;background:#000;overflow:hidden">
        <iframe style="position:absolute;top:0;left:0;width:100%;height:100%;border:0" allow="autoplay; encrypted-media"
          src="https://www.youtube-nocookie.com/embed/$videoId?autoplay=1&mute=${if (muted) 1 else 0}&controls=0&loop=1&playlist=$videoId&rel=0&playsinline=1&modestbranding=1"></iframe>
        </body></html>
    """.trimIndent()
    androidx.compose.runtime.key(muted) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                android.webkit.WebView(ctx).apply {
                    isFocusable = false
                    isFocusableInTouchMode = false
                    setBackgroundColor(android.graphics.Color.BLACK)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    // Android's web view only draws HTML5 video (not just its sound) with a chrome client.
                    webChromeClient = android.webkit.WebChromeClient()
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                    // YouTube's embedded player needs a web address to play from.
                    loadDataWithBaseURL("https://www.youtube-nocookie.com", html, "text/html", "utf-8", null)
                }
            },
            onRelease = { it.destroy() },
            modifier = modifier,
        )
    }
}

/** An episode that has aired (or has no date). */
private fun episodeOut(v: Video): Boolean =
    v.released == null || runCatching { java.time.Instant.parse(v.released) <= java.time.Instant.now() }.getOrDefault(true)

/** "2027-03-12…" as "12 March 2027". */
private fun comingDate(s: String?): String? = runCatching {
    java.time.LocalDate.parse(s!!.take(10)).format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", com.vantaview.app.ui.I18n.locale))
}.getOrNull()

fun minutesLeft(ms: Long): String {
    val m = (ms / 60_000).coerceAtLeast(1)
    return if (m >= 60) "${m / 60} h ${m % 60} min left" else "$m min left"
}

/** "Open in browser" for the web pages an add-on offered instead of a stream. */
@kotlin.OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun WebLinks(links: List<Pair<String, String>>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var noBrowser by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        Text("These pages belong to the add-on, not VantaView.", color = Vanta.TextDim, fontSize = 13.sp)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            links.forEach { (label, url) ->
                com.vantaview.app.ui.FocusCard(onClick = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.onFailure { noBrowser = true }
                }) {
                    Text(com.vantaview.app.ui.tr("Open in browser · {0}", label), color = Vanta.Text, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
        if (noBrowser) Text("This device has no web browser to open it with.", color = Vanta.Error, fontSize = 13.sp)
    }
}

/** "Ends at 22:41 if you start now" (or "if you carry on now", for a film part watched), from a runtime like "1h 47m" or "107 min". */
@Composable
private fun endsIfStarted(runtime: String?, remainingMs: Long?): String? {
    val context = androidx.compose.ui.platform.LocalContext.current
    val minutes = remainingMs?.let { it / 60_000 } ?: runtime?.let { r ->
        val h = Regex("(\\d+)\\s*h").find(r)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val m = Regex("(\\d+)\\s*m").find(r)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        (h * 60 + m).takeIf { it > 0 }
    } ?: return null
    var now by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30_000); now = System.currentTimeMillis() } }
    val fmt = java.text.SimpleDateFormat(if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a", com.vantaview.app.ui.I18n.locale)
    val at = fmt.format(java.util.Date(now + minutes * 60_000))
    return if (remainingMs != null) com.vantaview.app.ui.tr("Ends at {0} if you carry on now", at) else com.vantaview.app.ui.tr("Ends at {0} if you start now", at)
}
