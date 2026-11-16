// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.home

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import com.vantaview.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vantaview.app.data.ChannelNames
import com.vantaview.app.data.Look
import com.vantaview.app.data.db.ChannelEntity
import com.vantaview.app.data.db.ProfileEntity
import com.vantaview.app.data.db.ProgrammeEntity
import com.vantaview.app.ui.AvatarCircle
import com.vantaview.app.ui.FillCard
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.Section
import com.vantaview.app.ui.Vanta
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Something to show on a dashboard: a title in progress, a favourite channel or what's on now. */
internal data class DashCard(
    val key: String,
    val title: String,
    val sub: String?,
    val image: String?,
    /** True for channel logos (fit, not cropped). */
    val logo: Boolean,
    val progress: Float?,
    val onClick: () -> Unit,
)

/** Everything a console-style Home needs, gathered once by [HomeScreen]. */
internal class Dash(
    val now: Date,
    val profile: ProfileEntity?,
    val cont: List<ContinueItem>,
    val favs: List<ChannelEntity>,
    val onNow: List<Pair<ChannelEntity, ProgrammeEntity>>,
    val preview: Preview?,
    val previewNow: ProgrammeEntity?,
    val vm: HomeViewModel,
    val onSection: (String) -> Unit,
    val onOpen: (ContinueItem) -> Unit,
    val onPlayChannel: (Long) -> Unit,
) {
    /** The sections, as the menu shows them (Kids profiles have no Sources or Settings). */
    val sections: List<Section> = Section.entries.filter {
        it != Section.HOME && !(profile?.isKids == true && (it == Section.SOURCES || it == Section.SETTINGS))
    }

    val clock: String get() = SimpleDateFormat("h.mma", Locale.UK).format(now).lowercase()
    val date: String get() = SimpleDateFormat("EEE d MMM", com.vantaview.app.ui.I18n.locale).format(now)

    private fun contCard(c: ContinueItem) = DashCard(
        "c:" + c.entry.key, c.entry.title, c.line, c.entry.background ?: c.entry.poster, false, c.progress,
    ) { onOpen(c) }

    private fun favCard(ch: ChannelEntity) = DashCard(
        "f:${ch.id}", ChannelNames.display(ch.name), onNow.firstOrNull { it.first.id == ch.id }?.second?.title?.let { "Now: $it" } ?: "Channel ${ch.number}",
        ch.logo, true, null,
    ) { com.vantaview.app.feature.player.ZapList.set(favs.map { it.id }); onPlayChannel(ch.id) }

    private fun nowCard(p: Pair<ChannelEntity, ProgrammeEntity>) = DashCard(
        "n:${p.first.id}", p.second.title, ChannelNames.display(p.first.name), p.first.logo, true,
        ((now.time - p.second.start).toFloat() / (p.second.stop - p.second.start).coerceAtLeast(1)).coerceIn(0f, 1f),
    ) { onPlayChannel(p.first.id) }

    /** What to show for a section (or "home"). */
    fun cards(route: String): List<DashCard> = when (route) {
        "home" -> cont.map(::contCard) + favs.map(::favCard)
        "live" -> favs.map(::favCard) + onNow.filter { n -> favs.none { it.id == n.first.id } }.map(::nowCard)
        "guide" -> onNow.map(::nowCard)
        "movies" -> cont.filter { it.entry.type == "movie" }.map(::contCard)
        "series" -> cont.filter { it.entry.type == "series" }.map(::contCard)
        "library" -> cont.map(::contCard)
        else -> emptyList()
    }.distinctBy { it.key }.take(12)

    /** A line about a section for the big title areas. */
    fun blurb(route: String): String = when (route) {
        "live" -> preview?.let { p ->
            "Last watched: ${ChannelNames.display(p.channel.name)}" + (previewNow?.let { " · Now: ${it.title}" } ?: "")
        } ?: "Your channels, by category."
        "guide" -> "What's on, from yesterday to a week ahead."
        "movies" -> cont.count { it.entry.type == "movie" }.let { if (it > 0) "$it in Continue watching" else "Films from your add-ons and portals." }
        "series" -> cont.count { it.entry.type == "series" }.let { if (it > 0) "$it in Continue watching" else "Series from your add-ons and portals." }
        "library" -> "Your Watchlist, Continue watching, Watched and Reminders."
        "search" -> "Channels, programmes, films and series."
        "sources" -> "Playlists, portals and add-ons."
        "settings" -> "Playback, looks, subtitles and more."
        else -> ""
    }
}

/** Home as a games-console style dashboard (TVs and wide screens). */
@Composable
internal fun ConsoleHome(look: Look, d: Dash) {
    when (look) {
        Look.BLADES -> BladesHome(d)
        Look.STACK -> StackHome(d)
        Look.TILES -> TilesHome(d)
        Look.FLUENT -> FluentHome(d)
        Look.CROSS -> CrossHome(d)
        Look.SPOTLIGHT -> SpotlightHome(d)
        else -> Unit
    }
}

// ---- Shared pieces ----

/** A focusable spot with no box of its own: the dashboards draw focus themselves. */
private fun Modifier.spot(onFocus: (Boolean) -> Unit, onClick: () -> Unit): Modifier =
    this.onFocusChanged { onFocus(it.isFocused) }.clickable(onClick = onClick)

@Composable
private fun CardImage(c: DashCard, modifier: Modifier) {
    Box(modifier.background(if (c.logo) Color(0x33000000) else Vanta.SurfaceHigh)) {
        if (c.image != null) AsyncImage(
            model = c.image, contentDescription = null,
            contentScale = if (c.logo) ContentScale.Fit else ContentScale.Crop,
            modifier = Modifier.fillMaxSize().padding(if (c.logo) 10.dp else 0.dp),
        ) else Text(c.title.take(2).uppercase(), color = Vanta.TextDim, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        c.progress?.let { f ->
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x55FFFFFF))) {
                Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(Vanta.Cyan))
            }
        }
    }
}

/** A picture card with its title under it; the focus ring follows the look's accent. */
@Composable
private fun PictureCard(c: DashCard, w: Dp, h: Dp, radius: Dp = 8.dp, showText: Boolean = true) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(radius)
    Column(Modifier.width(w)) {
        CardImage(
            c,
            Modifier.width(w).height(h).clip(shape)
                .then(if (f) Modifier.border(3.dp, Vanta.Accent, shape) else Modifier)
                .spot({ f = it }, c.onClick),
        )
        if (showText) {
            Text(c.title, color = Vanta.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            c.sub?.let { Text(it, color = Vanta.TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun Live(d: Dash, modifier: Modifier) {
    if (d.vm.miniPlayer && d.preview != null) PreviewBox(d.preview, d.vm, modifier) { d.onPlayChannel(d.preview.channel.id) }
    else Box(modifier.clip(RoundedCornerShape(12.dp)).background(Vanta.Surface), contentAlignment = Alignment.Center) {
        Text("Watch a live channel and it plays here.", color = Vanta.TextDim, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun Avatar(d: Dash, size: Dp = 32.dp) {
    d.profile?.let { p ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            AvatarCircle(p.avatar, size)
            Spacer(Modifier.width(8.dp))
            Text(p.name, color = Vanta.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun icon(route: String): ImageVector = Section.entries.first { it.route == route }.icon

// ---- Blades: tabs stacked down the left, the front one open ----

@Composable
private fun BladesHome(d: Dash) {
    data class Blade(val label: String, val menu: List<Pair<String, () -> Unit>>, val right: String)
    val blades = buildList {
        add(Blade("HOME", buildList {
            d.cont.firstOrNull()?.let { c -> add("Continue: ${c.entry.title}" to { d.onOpen(c) }) }
            d.preview?.let { p -> add("Watch ${ChannelNames.display(p.channel.name)}" to { d.onPlayChannel(p.channel.id) }) }
            add("Search" to { d.onSection("search") })
            add("Library" to { d.onSection("library") })
        }, "live"))
        add(Blade("LIVE TV", buildList {
            add("All channels" to { d.onSection("live") })
            add("TV Guide" to { d.onSection("guide") })
            add("Catch-up" to { d.onSection("catchup") })
            d.favs.take(4).forEach { ch -> add(ChannelNames.display(ch.name) to { com.vantaview.app.feature.player.ZapList.set(d.favs.map { it.id }); d.onPlayChannel(ch.id) }) }
        }, "live"))
        add(Blade("MOVIES & SERIES", listOf(
            "Movies" to { d.onSection("movies") }, "TV Series" to { d.onSection("series") }, "Sports TV" to { d.onSection("sportstv") },
            "Library" to { d.onSection("library") },
        ), "cards"))
        add(Blade("SYSTEM", buildList {
            if (d.sections.any { it == Section.SETTINGS }) add("Settings" to { d.onSection("settings") })
            if (d.sections.any { it == Section.SOURCES }) add("Sources" to { d.onSection("sources") })
            add("Search" to { d.onSection("search") })
        }, "about"))
    }
    var cur by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(d)
            Spacer(Modifier.width(10.dp))
            Text("· ${d.clock}", color = Vanta.TextDim, fontSize = 14.sp)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            blades.forEachIndexed { i, b ->
                if (i == cur) {
                    Row(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp))
                            .background(Brush.verticalGradient(listOf(Color(0xFFF4F6F1), Color(0xFFDFE4DA)))).padding(20.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        Column(Modifier.width(250.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(b.label.lowercase().replaceFirstChar { it.titlecase() }, color = Color(0xFF3F8A10), fontSize = 28.sp, fontWeight = FontWeight.Light)
                            Spacer(Modifier.height(6.dp))
                            b.menu.forEach { (label, go) ->
                                FillCard(onClick = go, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(6.dp)) { t, _ ->
                                    Text(label, color = t, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            when (b.right) {
                                "live" -> {
                                    Live(d, Modifier.fillMaxWidth().weight(1f).border(3.dp, Color.White, RoundedCornerShape(12.dp)))
                                    d.previewNow?.let { Text("Now: ${it.title}", color = Color(0xFF26301F), fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                                }
                                "cards" -> LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    items(d.cards("library"), key = { it.key }) { c -> PictureCard(c, 200.dp, 112.dp) }
                                }
                                else -> Text("VantaView ${com.vantaview.app.BuildConfig.VERSION_NAME}. Press OK on a tab to open it.", color = Color(0xFF4A5544), fontSize = 14.sp)
                            }
                        }
                    }
                } else {
                    var f by remember { mutableStateOf(false) }
                    val green = i == cur + 1 || i == cur - 1
                    Box(
                        Modifier.width(42.dp).fillMaxHeight()
                            .clip(RoundedCornerShape(if (i < cur) 10.dp else 0.dp, 0.dp, 0.dp, if (i < cur) 10.dp else 0.dp))
                            .background(if (f) Color(0xFF5AA612) else if (green) Color(0xFF6E786A) else Color(0xFF5A6356))
                            .spot({ f = it }) { cur = i },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            b.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                            maxLines = 1, softWrap = false, modifier = Modifier.wrapContentWidth(unbounded = true).rotate(-90f),
                        )
                    }
                }
            }
        }
    }
}

// ---- Stack: menu down the side, 3D stack of panels ----

@Composable
private fun StackHome(d: Dash) {
    val routes = listOf("home") + d.sections.map { it.route }
    val labels = listOf("Home") + d.sections.map { it.label }
    var sel by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Avatar(d, 34.dp)
            Spacer(Modifier.weight(1f))
            Text("${d.date} · ${d.clock}", color = Vanta.TextDim, fontSize = 15.sp)
        }
        Column(Modifier.padding(top = 70.dp).width(230.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            labels.forEachIndexed { i, label ->
                val dist = kotlin.math.abs(i - sel)
                var f by remember { mutableStateOf(false) }
                Text(
                    label,
                    color = Color.White.copy(alpha = when (dist) { 0 -> 1f; 1 -> 0.6f; 2 -> 0.38f; else -> 0.2f }),
                    fontSize = when (dist) { 0 -> 34.sp; 1 -> 21.sp; 2 -> 17.sp; else -> 14.sp },
                    fontWeight = if (dist == 0) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.spot({ f = it; if (it) sel = i }) { if (routes[i] != "home") d.onSection(routes[i]) }
                        .then(if (f) Modifier.border(2.dp, Vanta.Cyan.copy(alpha = 0.6f), RoundedCornerShape(6.dp)) else Modifier)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        // The stack: the front panel plays or opens; the ones behind lean away.
        val route = routes[sel]
        val cards = d.cards(route)
        Box(Modifier.padding(start = 280.dp, top = 80.dp).fillMaxSize()) {
            val behind = cards.drop(1).take(3)
            behind.reversed().forEachIndexed { j, c ->
                val k = behind.size - j
                Box(
                    Modifier.offset(x = (k * 110).dp, y = (k * 14).dp).width(300.dp).height(210.dp)
                        .graphicsLayer { rotationY = -24f - k * 6f; cameraDistance = 14f * density; alpha = 1f - k * 0.22f }
                        .clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A3A4A)),
                ) {
                    CardImage(c, Modifier.fillMaxSize())
                }
            }
            var f by remember { mutableStateOf(false) }
            val front = cards.firstOrNull()
            Box(
                Modifier.width(360.dp).height(250.dp)
                    .graphicsLayer { rotationY = -12f; cameraDistance = 14f * density }
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF23303D), Color(0xFF121A23))))
                    .border(3.dp, if (f) Color.White else Vanta.Cyan, RoundedCornerShape(10.dp))
                    .spot({ f = it }) { front?.onClick?.invoke() ?: if (route != "home") d.onSection(route) else Unit },
            ) {
                if (route == "home" || route == "live") {
                    if (d.vm.miniPlayer && d.preview != null) Live(d, Modifier.fillMaxSize())
                    else front?.let { CardImage(it, Modifier.fillMaxSize()) }
                } else front?.let { CardImage(it, Modifier.fillMaxSize()) }
                Column(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000)))).padding(14.dp),
                ) {
                    Text(
                        if (route == "home") "CONTINUE" else labels[sel].uppercase(), color = Vanta.Cyan, fontSize = 12.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                    )
                    Text(front?.title ?: labels[sel], color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(front?.sub ?: d.blurb(route), color = Color(0xFFB7C7D6), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ---- Tiles: lower-case menu across the top, green tiles ----

@Composable
private fun SolidTile(label: String, route: String?, colour: Color, modifier: Modifier, textColour: Color = Color.White, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Box(
        modifier.then(if (f) Modifier.border(4.dp, Vanta.Accent).padding(4.dp) else Modifier)
            .background(colour).spot({ f = it }, onClick).padding(10.dp),
    ) {
        route?.let { Icon(icon(it), contentDescription = null, tint = textColour, modifier = Modifier.size(28.dp)) }
        Text(label, color = textColour, fontSize = 14.sp, modifier = Modifier.align(Alignment.BottomStart), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TilesHome(d: Dash) {
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Text("home", color = Vanta.Cyan, fontSize = 30.sp, fontWeight = FontWeight.Light)
            d.sections.take(6).forEach { s ->
                var f by remember { mutableStateOf(false) }
                Text(
                    s.label.lowercase(), color = if (f) Vanta.Text else Color(0xFF9A9A9A), fontSize = 26.sp, fontWeight = FontWeight.Light,
                    modifier = Modifier.spot({ f = it }) { d.onSection(s.route) },
                )
            }
            Spacer(Modifier.weight(1f))
            Text("${d.profile?.name ?: ""} · ${d.clock}", color = Vanta.TextDim, fontSize = 14.sp)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().height(250.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // The big tile: the last channel playing, or the first thing in progress.
            var f by remember { mutableStateOf(false) }
            val first = d.cont.firstOrNull()
            Box(
                Modifier.weight(3f).fillMaxHeight().then(if (f) Modifier.border(4.dp, Vanta.Accent).padding(4.dp) else Modifier)
                    .background(Color(0xFF2B2B2B))
                    .spot({ f = it }) { d.preview?.let { d.onPlayChannel(it.channel.id) } ?: first?.let(d.onOpen) ?: d.onSection("live") },
            ) {
                if (d.preview != null && d.vm.miniPlayer) Live(d, Modifier.fillMaxSize())
                else first?.let { CardImage(d.cards("home").first(), Modifier.fillMaxSize()) }
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0x99000000)).padding(10.dp)) {
                    Text("CONTINUE WATCHING", color = Color(0xFF9BE29B), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(
                        d.preview?.let { ChannelNames.display(it.channel.name) } ?: first?.entry?.title ?: "Pick a channel to start",
                        color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    )
                }
            }
            Column(Modifier.weight(2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SolidTile("Live TV", "live", Color(0xFF107C10), Modifier.weight(1f).fillMaxHeight()) { d.onSection("live") }
                    SolidTile("TV Guide", "guide", Color(0xFF1D8F1D), Modifier.weight(1f).fillMaxHeight()) { d.onSection("guide") }
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SolidTile("Movies", "movies", Color(0xFF0B5E0B), Modifier.weight(1f).fillMaxHeight()) { d.onSection("movies") }
                    SolidTile("Search", "search", Color.White, Modifier.weight(1f).fillMaxHeight(), textColour = Color(0xFF107C10)) { d.onSection("search") }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(116.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            d.cards("live").take(3).forEach { c ->
                var f by remember { mutableStateOf(false) }
                Box(
                    Modifier.weight(1f).fillMaxHeight().then(if (f) Modifier.border(4.dp, Vanta.Accent).padding(4.dp) else Modifier)
                        .background(Color(0xFFD6D6D6)).spot({ f = it }, c.onClick),
                ) {
                    c.image?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(14.dp)) }
                    Text(c.title, color = Color(0xFF1A1A1A), fontSize = 13.sp, maxLines = 1, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
                }
            }
            SolidTile("TV Series", "series", Color(0xFF3A3A3A), Modifier.weight(1f).fillMaxHeight()) { d.onSection("series") }
            SolidTile("Library", "library", Color(0xFF3A3A3A), Modifier.weight(1f).fillMaxHeight()) { d.onSection("library") }
            if (d.sections.contains(Section.SETTINGS)) SolidTile("Settings", "settings", Color(0xFF3A3A3A), Modifier.weight(1f).fillMaxHeight()) { d.onSection("settings") }
        }
    }
}

// ---- Fluent: big picture of what's on, a row of square tiles ----

@Composable
private fun SquareTile(label: String, route: String?, size: Dp, image: DashCard? = null, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.size(size).then(if (f) Modifier.border(3.dp, Color.White, shape) else Modifier)
            .clip(shape).background(Color(0xFF2B3530)).spot({ f = it }, onClick),
    ) {
        image?.let { CardImage(it, Modifier.fillMaxSize()) }
        route?.let { Icon(icon(it), contentDescription = null, tint = Color.White, modifier = Modifier.padding(10.dp).size(26.dp)) }
        Text(
            label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000)))).padding(8.dp),
        )
    }
}

@Composable
private fun FluentHome(d: Dash) {
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(d)
            Spacer(Modifier.weight(1f))
            listOf("search", "settings").filter { r -> r == "search" || d.sections.contains(Section.SETTINGS) }.forEach { r ->
                FocusCard(onClick = { d.onSection(r) }, shape = CircleShape, padding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
                    Icon(icon(r), contentDescription = r, tint = Vanta.Text, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
            }
            Text(d.clock, color = Vanta.Text, fontSize = 15.sp)
        }
        Spacer(Modifier.height(26.dp))
        Text("ON NOW", color = Vanta.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(
            d.previewNow?.title ?: d.preview?.let { ChannelNames.display(it.channel.name) } ?: "Welcome back",
            color = Vanta.Text, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(d.blurb("live"), color = Vanta.TextDim, fontSize = 14.sp, maxLines = 2)
        Spacer(Modifier.height(20.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            val first = d.cards("home").firstOrNull()
            item {
                SquareTile("Continue", null, 150.dp, first) {
                    d.preview?.let { d.onPlayChannel(it.channel.id) } ?: first?.onClick?.invoke() ?: d.onSection("live")
                }
            }
            items(d.sections.filter { it != Section.SEARCH && it != Section.SETTINGS }, key = { it.route }) { s ->
                SquareTile(s.label, s.route, 116.dp) { d.onSection(s.route) }
            }
        }
        Spacer(Modifier.height(18.dp))
        val favs = d.cards("live")
        if (favs.isNotEmpty()) {
            Text("Favourite channels", color = Vanta.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(favs, key = { it.key }) { c -> PictureCard(c, 160.dp, 64.dp, 6.dp, showText = false) }
            }
        }
    }
}

// ---- Cross: icons across, items down ----

@Composable
private fun CrossHome(d: Dash) {
    val routes = listOf("home") + d.sections.map { it.route }
    val labels = listOf("Home") + d.sections.map { it.label }
    var sel by remember { mutableIntStateOf(0) }
    val step = 96.dp
    val shift by animateDpAsState(-(step * sel), label = "cross")
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Text(
            SimpleDateFormat("d/M H:mm", Locale.UK).format(d.now), color = Color.White, fontSize = 15.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(horizontal = 32.dp, vertical = 18.dp),
        )
        // The icon row slides so the chosen icon stays above the list.
        Row(Modifier.padding(top = 70.dp).offset(x = 160.dp + shift), verticalAlignment = Alignment.Bottom) {
            routes.forEachIndexed { i, r ->
                var f by remember { mutableStateOf(false) }
                val on = i == sel
                Column(
                    Modifier.width(step).spot({ f = it; if (it) sel = i }) { if (r != "home") d.onSection(r) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        if (r == "home") Section.HOME.icon else icon(r), contentDescription = labels[i], tint = Color.White.copy(alpha = if (on) 1f else 0.55f),
                        modifier = Modifier.size(if (on) 52.dp else 38.dp),
                    )
                    Text(
                        if (on) labels[i] else "", color = Color.White, fontSize = 15.sp, maxLines = 1,
                        modifier = Modifier.padding(top = 4.dp).then(if (f) Modifier.border(1.dp, Color(0x99FFFFFF), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp) else Modifier),
                    )
                }
            }
        }
        // Items under the chosen icon.
        val route = routes[sel]
        val list = d.cards(route).take(6)
        Column(Modifier.padding(start = 160.dp + 14.dp, top = 180.dp).width(420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (list.isEmpty()) {
                var f by remember { mutableStateOf(false) }
                Text(
                    if (route == "home") "Nothing in progress yet" else "Open ${labels[sel]}", color = Color.White, fontSize = 20.sp,
                    modifier = Modifier.spot({ f = it }) { if (route != "home") d.onSection(route) }
                        .then(if (f) Modifier.border(1.dp, Color(0x99FFFFFF), RoundedCornerShape(4.dp)) else Modifier).padding(4.dp),
                )
                Text(d.blurb(route), color = Color(0xFFD6EAFF), fontSize = 13.sp)
            }
            list.forEachIndexed { i, c ->
                var f by remember { mutableStateOf(false) }
                Row(
                    Modifier.spot({ f = it }, c.onClick).graphicsLayer { alpha = if (f || i == 0) 1f else 0.75f - i * 0.06f },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CardImage(c.copy(progress = null), Modifier.width(if (f) 72.dp else 56.dp).height(if (f) 44.dp else 34.dp).clip(RoundedCornerShape(4.dp)))
                    Column {
                        Text(c.title, color = Color.White, fontSize = if (f) 21.sp else 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (f) c.sub?.let { Text(it, color = Color(0xFFD6EAFF), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
        if (route == "home" || route == "live") {
            Live(d, Modifier.align(Alignment.TopEnd).padding(top = 190.dp, end = 32.dp).width(300.dp).height(170.dp))
        }
    }
}

// ---- Spotlight: rounded tiles across the top, a big title and a Watch button ----

@Composable
private fun SpotlightHome(d: Dash) {
    val all = d.sections.filter { it != Section.SEARCH && it != Section.SETTINGS }
    var sel by remember { mutableIntStateOf(0) }
    val s = all.getOrNull(sel) ?: Section.LIVE
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Home", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            listOf("search", "settings").filter { r -> r == "search" || d.sections.contains(Section.SETTINGS) }.forEach { r ->
                FocusCard(onClick = { d.onSection(r) }, shape = CircleShape, padding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
                    Icon(icon(r), contentDescription = r, tint = Vanta.Text, modifier = Modifier.size(18.dp))
                }
            }
            d.profile?.let { AvatarCircle(it.avatar, 28.dp) }
            Text(d.clock, color = Color(0xFFDFE5F5), fontSize = 14.sp)
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            all.forEachIndexed { i, sec ->
                var f by remember { mutableStateOf(false) }
                val on = i == sel
                val size by animateDpAsState(if (on) 92.dp else 62.dp, label = "tile")
                Box(
                    Modifier.size(size).clip(RoundedCornerShape(16.dp))
                        .background(if (on) Brush.linearGradient(listOf(Color(0xFF3A5BD0), Color(0xFF1C2C74))) else Brush.linearGradient(listOf(Color(0xFF1D2440), Color(0xFF1D2440))))
                        .then(if (f) Modifier.border(3.dp, Color.White, RoundedCornerShape(16.dp)) else Modifier)
                        .spot({ f = it; if (it) sel = i }) { d.onSection(sec.route) },
                    contentAlignment = Alignment.Center,
                ) { Icon(sec.icon, contentDescription = sec.label, tint = Color.White, modifier = Modifier.size(if (on) 38.dp else 26.dp)) }
                if (on) Text(sec.label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(26.dp))
        Text(s.label, color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Light)
        Text(d.blurb(s.route), color = Color(0xFFC4CCE4), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            var f by remember { mutableStateOf(false) }
            Text(
                if (s == Section.LIVE && d.preview != null) "Watch" else "Open", color = Color(0xFF0B1020), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(26.dp)).background(Color.White)
                    .then(if (f) Modifier.border(3.dp, Color(0xFF8FB4FF), RoundedCornerShape(26.dp)) else Modifier)
                    .spot({ f = it }) { if (s == Section.LIVE && d.preview != null) d.onPlayChannel(d.preview.channel.id) else d.onSection(s.route) }
                    .padding(horizontal = 36.dp, vertical = 12.dp),
            )
            val second = when (s) { Section.LIVE -> "TV Guide" to "guide"; Section.GUIDE -> "Live TV" to "live"; else -> "Search" to "search" }
            FocusCard(onClick = { d.onSection(second.second) }, shape = RoundedCornerShape(26.dp), padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
                Text(second.first, color = Color.White, fontSize = 16.sp)
            }
        }
        Spacer(Modifier.height(18.dp))
        val cards = d.cards(s.route)
        if (cards.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(cards, key = { it.key }) { c -> PictureCard(c, 200.dp, 96.dp, 10.dp) }
            }
        }
    }
}
