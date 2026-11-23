// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Fires when the menu's current page is chosen again, so the page can go back to its start. */
object Reselect {
    val events = kotlinx.coroutines.flow.MutableSharedFlow<Section>(extraBufferCapacity = 1)
}

enum class Section(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Filled.Home),
    SEARCH("search", "Search", Icons.Filled.Search),
    LIVE("live", "Live TV", Icons.Filled.PlayArrow),
    GUIDE("guide", "TV Guide", Icons.Filled.DateRange),
    MOVIES("movies", "Movies", Icons.Filled.Star),
    SERIES("series", "TV Series", Icons.Filled.List),
    LIBRARY("library", "Library", Icons.Filled.Favorite),
    SOURCES("sources", "Sources", Icons.Filled.AddCircle),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}

/**
 * Side rail on TVs, tablets and wide screens; menu drawer on phones. The page content always sits at the
 * same place in the tree, so switching between full-screen pages (player, details) and sections keeps state.
 */
@Composable
fun AppShell(
    current: Section?,
    profile: com.vantaview.app.data.db.ProfileEntity?,
    onSelect: (Section) -> Unit,
    onProfiles: () -> Unit,
    /** The players: drawn to every edge, notch included (they keep their own buttons clear of it). */
    edgeToEdge: Boolean = false,
    content: @Composable () -> Unit,
) {
    // Kids profiles can't open Sources.
    val sections = Section.entries.filter { !(profile?.isKids == true && (it == Section.SOURCES || it == Section.SETTINGS)) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Vanta.Bg).then(if (edgeToEdge) Modifier else Modifier.safeDrawingPadding())) {
        // Not behind the players: an animated background (Wave, Cross) would otherwise be redrawn up to 120 times a
        // second underneath the video, taking processor and graphics time from it on phones.
        if (!edgeToEdge) LookBackground(Vanta.Look)
        val wide = maxWidth >= 720.dp
        // Broadcast and Classic put the sections in a bar across the top on TVs and wide screens.
        // Console looks draw their own menu on Home (a dashboard), so the sidebar and bar step aside there.
        val dashboard = wide && current == Section.HOME && Vanta.Look.dashboard
        val topMenu = wide && current != null && !dashboard && Vanta.Look.topMenu
        val drawer = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val focusManager = LocalFocusManager.current

        // Side rail auto-hide: everywhere except Home, 5 seconds after focus leaves the rail.
        // Settings › Appearance › Hide the sidebar.
        // TVs are 540 dp tall: a tighter sidebar so all nine sections and the profile fit.
        val shortRail = maxHeight < 640.dp
        val autoHide = wide && !topMenu && current != null && current != Section.HOME && LocalAppearance.current.sidebarAutoHide
        var railShown by remember { mutableStateOf(true) }
        var railHasFocus by remember { mutableStateOf(false) }
        var focusRail by remember { mutableStateOf(false) }
        val railFocus = remember { FocusRequester() }
        fun showRail() { railShown = true; focusRail = true }
        LaunchedEffect(autoHide) { if (!autoHide) railShown = true }
        LaunchedEffect(autoHide, railShown, railHasFocus, current) {
            if (autoHide && railShown && !railHasFocus) { delay(5_000); railShown = false }
        }
        LaunchedEffect(railShown, focusRail) {
            if (railShown && focusRail) { delay(80); runCatching { railFocus.requestFocus() }; focusRail = false }
        }
        ModalNavigationDrawer(
            drawerState = drawer,
            gesturesEnabled = !wide && current != null,
            drawerContent = {
                ModalDrawerSheet(drawerContainerColor = Vanta.Panel, modifier = Modifier.width(260.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                            VantaLogo(size = 40.dp)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("VantaView", color = Vanta.Text, fontWeight = FontWeight.Black, fontSize = 20.sp)
                                Text("IPTV & VOD", color = Vanta.TextDim, fontSize = 12.sp)
                            }
                        }
                        sections.forEach { s ->
                            NavItem(s, s == current) { scope.launch { drawer.close() }; onSelect(s) }
                        }
                        Spacer(Modifier.height(12.dp))
                        ProfileChip(profile) { scope.launch { drawer.close() }; onProfiles() }
                    }
                }
            },
        ) {
            Row(Modifier.fillMaxSize()) {
                if (wide && current != null && !topMenu && !dashboard) {
                    AnimatedVisibility(visible = railShown, enter = expandHorizontally(), exit = shrinkHorizontally()) {
                        Column(
                            Modifier.width(172.dp).fillMaxHeight().background(Vanta.Surface)
                                .onFocusChanged { railHasFocus = it.hasFocus }.padding(horizontal = 12.dp, vertical = if (shortRail) 8.dp else 12.dp),
                            verticalArrangement = Arrangement.spacedBy(if (shortRail) 4.dp else 8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = if (shortRail) 4.dp else 12.dp)) {
                                VantaLogo(size = if (shortRail) 28.dp else 34.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("VantaView", color = Vanta.Text, fontWeight = FontWeight.Black, fontSize = 17.sp)
                            }
                            // The list scrolls when the screen is short (a phone on its side); the profile stays at the bottom.
                            Column(
                                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(if (shortRail) 2.dp else 8.dp),
                            ) {
                                sections.forEach { s ->
                                    NavItem(s, s == current, if (s == current) Modifier.focusRequester(railFocus) else Modifier) { onSelect(s) }
                                }
                            }
                            ProfileChip(profile, onProfiles)
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (topMenu) TopMenu(sections, current, profile, onSelect, onProfiles)
                    if (!wide && current != null) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FocusCard(
                                onClick = { scope.launch { drawer.open() } },
                                padding = PaddingValues(10.dp),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Filled.Menu, contentDescription = "Menu", tint = Vanta.Text) }
                            Spacer(Modifier.width(10.dp))
                            VantaLogo(size = 28.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("VantaView", color = Vanta.Text, fontWeight = FontWeight.Black, fontSize = 18.sp)
                        }
                    }
                    Box(
                        Modifier.weight(1f).fillMaxWidth().onKeyEvent { e ->
                            // Remote: pressing left with nothing further left brings the side bar back.
                            if (autoHide && !railShown && e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) {
                                if (!focusManager.moveFocus(FocusDirection.Left)) showRail()
                                true
                            } else false
                        },
                    ) {
                        content()
                        if (autoHide && !railShown) {
                            // Touch: tap or swipe in from the left edge.
                            Box(
                                Modifier.align(Alignment.CenterStart).fillMaxHeight().width(20.dp)
                                    .pointerInput(Unit) { detectTapGestures { showRail() } }
                                    .pointerInput(Unit) { detectHorizontalDragGestures { _, dx -> if (dx > 6f) showRail() } },
                            )
                        }
                    }
                }
            }
        }
        // Back brings the hidden side bar back before leaving the page.
        BackHandler(enabled = autoHide && !railShown) { showRail() }
    }
}

/** The sections in a bar across the top (Broadcast and Classic looks), with the clock and profile. */
@Composable
private fun TopMenu(
    sections: List<Section>,
    current: Section?,
    profile: com.vantaview.app.data.db.ProfileEntity?,
    onSelect: (Section) -> Unit,
    onProfiles: () -> Unit,
) {
    var clock by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val f = java.text.SimpleDateFormat("h.mma", java.util.Locale.UK)
        while (true) { clock = f.format(java.util.Date()).lowercase(); delay(15_000) }
    }
    Row(
        Modifier.fillMaxWidth().background(Vanta.Panel.copy(alpha = 0.55f)).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VantaLogo(size = 30.dp)
        Spacer(Modifier.width(14.dp))
        androidx.compose.foundation.lazy.LazyRow(
            Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(sections.size) { i ->
                val s = sections[i]
                val on = s == current
                FocusCard(
                    onClick = { onSelect(s) }, selected = on, shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                    padding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(s.icon, contentDescription = null, tint = if (on) Vanta.Cyan else Vanta.TextDim, modifier = Modifier.width(18.dp))
                        Spacer(Modifier.width(6.dp))
                        // Tiles uses a lower-case menu; Cross shows only the chosen section's name under the icons.
                        val label = when (Vanta.Look) {
                            com.vantaview.app.data.Look.TILES -> s.label.lowercase()
                            com.vantaview.app.data.Look.CROSS -> if (on) s.label else ""
                            else -> s.label
                        }
                        if (label.isNotEmpty()) Text(label, color = if (on) Vanta.Cyan else Vanta.Text, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp)
                    }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(clock, color = Vanta.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        if (profile != null) {
            Spacer(Modifier.width(10.dp))
            FocusCard(onClick = onProfiles, shape = androidx.compose.foundation.shape.CircleShape, padding = PaddingValues(3.dp)) {
                AvatarCircle(profile.avatar, 28.dp)
            }
        }
    }
}

@Composable
private fun NavItem(s: Section, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // Short screens (a TV is 540 dp tall) get slimmer items so the whole sidebar fits.
    val short = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 640
    FocusCard(
        onClick = onClick, selected = selected, modifier = modifier.fillMaxWidth(),
        padding = PaddingValues(horizontal = 14.dp, vertical = if (short) 6.dp else 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(s.icon, contentDescription = null, tint = if (selected) Vanta.Cyan else Vanta.TextDim)
            Spacer(Modifier.width(12.dp))
            Text(
                s.label,
                color = if (selected) Vanta.Cyan else Vanta.Text,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
    Spacer(Modifier.height(0.dp))
}

/** Current profile, opens "Who's watching?" to switch. */
@Composable
private fun ProfileChip(profile: com.vantaview.app.data.db.ProfileEntity?, onClick: () -> Unit) {
    if (profile == null) return
    FocusCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AvatarCircle(profile.avatar, 30.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(profile.name, color = Vanta.Text, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(if (profile.isKids) "Kids · switch" else "Switch profile", color = Vanta.TextDim, fontSize = 12.sp)
            }
        }
    }
}
