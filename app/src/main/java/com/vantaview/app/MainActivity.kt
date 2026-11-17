// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app

import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vantaview.app.data.Prefs
import com.vantaview.app.data.addon.AddonRepository
import com.vantaview.app.data.repo.ProviderRepository
import com.vantaview.app.feature.home.HomeScreen
import com.vantaview.app.feature.live.LiveScreen
import com.vantaview.app.feature.player.PlayerScreen
import com.vantaview.app.feature.profiles.ChooseProfileScreen
import com.vantaview.app.feature.profiles.ProfileEditScreen
import com.vantaview.app.data.profile.ProfileRepository
import com.vantaview.app.feature.player.VodPlayerScreen
import com.vantaview.app.feature.sources.SourcesScreen
import com.vantaview.app.feature.sources.SourcesTab
import com.vantaview.app.feature.terms.TermsScreen
import com.vantaview.app.feature.vod.CatalogScreen
import com.vantaview.app.feature.vod.DetailsScreen
import com.vantaview.app.ui.AppShell
import com.vantaview.app.ui.Section
import com.vantaview.app.ui.Vanta
import com.vantaview.app.ui.VantaTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var prefs: Prefs
    @Inject lateinit var providers: ProviderRepository
    @Inject lateinit var addons: AddonRepository
    @Inject lateinit var profiles: ProfileRepository
    @Inject lateinit var backups: com.vantaview.app.data.backup.BackupManager
    @Inject lateinit var parental: com.vantaview.app.data.profile.ParentalControl
    @Inject lateinit var appearance: com.vantaview.app.data.AppearanceSettings
    @Inject lateinit var updates: com.vantaview.app.data.update.UpdateManager
    @Inject lateinit var sourceSettings: com.vantaview.app.data.SourceSettings
    @Inject lateinit var playbackSettings: com.vantaview.app.data.PlaybackSettings

    /** The last key press or touch, for the screensaver. */
    private val lastInput = kotlinx.coroutines.flow.MutableStateFlow(System.currentTimeMillis())
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean { lastInput.value = System.currentTimeMillis(); return super.dispatchKeyEvent(event) }
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean { lastInput.value = System.currentTimeMillis(); return super.dispatchTouchEvent(ev) }
    @Inject lateinit var library: com.vantaview.app.data.library.LibraryStore
    @Inject lateinit var reminderAlarms: com.vantaview.app.data.library.ReminderScheduler

    /** A channel to open, from a reminder notification. */
    private val playRequest = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getLongExtra(EXTRA_PLAY_CHANNEL, 0).takeIf { it > 0 }?.let { playRequest.value = it }
    }

    override fun onStart() { super.onStart(); com.vantaview.app.data.library.AppVisible.now = true }
    override fun onResume() {
        super.onResume()
        // The setting belongs to the profile in use.
        com.vantaview.app.feature.player.PlayerPip.enabled = playbackSettings.pip
    }

    override fun onStop() { super.onStop(); com.vantaview.app.data.library.AppVisible.now = false }

    companion object { const val EXTRA_PLAY_CHANNEL = "play_channel" }

    /** Pressing Home while a film or channel plays shrinks it into picture-in-picture (phones). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        com.vantaview.app.feature.player.PlayerPip.enabled = playbackSettings.pip
        if (com.vantaview.app.feature.player.PlayerPip.playing) com.vantaview.app.feature.player.PlayerPip.enter(this)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        com.vantaview.app.feature.player.PlayerPip.inPip = isInPictureInPictureMode
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.vantaview.app.ui.TvInput.isTv = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        // Automatic backups after playlist or profile changes (when switched on in Settings).
        backups.startAutomatic()
        // Settings › Updates: look for a new version every time VantaView starts (not on a screen turn).
        if (savedInstanceState == null) updates.checkOnStart()
        intent?.getLongExtra(EXTRA_PLAY_CHANNEL, 0)?.takeIf { it > 0 }?.let { playRequest.value = it }
        // Reminder alarms don't survive a restart of the device, so set them again.
        reminderAlarms.rescheduleAll(library.allReminders())
        // Settings › Sources and storage: refresh playlists that are out of date.
        lifecycleScope.launch { runCatching { providers.autoRefresh(sourceSettings.playlists) } }
        setContent {
            val look by appearance.state.collectAsState()
            VantaTheme(look) {
                AppNav(prefs, providers, addons, profiles, parental, updates, library, playRequest, remindWhilePlaying = { playbackSettings.remindersWhilePlaying }, lastInput = lastInput, onExit = { finish() })
            }
        }
    }
}

/** Where to land: Live TV if there are playlists, Movies if there are only add-ons, else Live TV's empty state. */
@Suppress("UNUSED_PARAMETER")
private suspend fun landing(providers: ProviderRepository, addons: AddonRepository): String = Section.HOME.route

/** Switches section. [restore] brings back the section as it was left (off when opening a specific tab). */
/**
 * After the terms: make the first profile, pick one when there are several, or go straight in with one.
 */
private suspend fun afterTerms(profiles: ProfileRepository, providers: ProviderRepository, addons: AddonRepository): String {
    val all = profiles.all()
    return when {
        all.isEmpty() -> "profile/setup"
        all.size > 1 -> "profiles"
        else -> { profiles.select(all.first().id); landing(providers, addons) }
    }
}

private fun NavHostController.goTo(route: String, restore: Boolean = true) {
    // Home sits at the bottom of the page history, so Back from any section returns to Home.
    val homeInHistory = runCatching { getBackStackEntry(Section.HOME.route) }.isSuccess
    if (!homeInHistory) {
        navigate(Section.HOME.route) { popUpTo(graph.id) { inclusive = true } }
    }
    navigate(route) {
        popUpTo(Section.HOME.route) { saveState = restore }
        launchSingleTop = true
        restoreState = restore
    }
}

@Composable
private fun AppNav(
    prefs: Prefs,
    providers: ProviderRepository,
    addons: AddonRepository,
    profiles: ProfileRepository,
    parental: com.vantaview.app.data.profile.ParentalControl,
    updates: com.vantaview.app.data.update.UpdateManager,
    library: com.vantaview.app.data.library.LibraryStore,
    playRequest: kotlinx.coroutines.flow.MutableStateFlow<Long?>,
    remindWhilePlaying: () -> Boolean = { true },
    lastInput: kotlinx.coroutines.flow.StateFlow<Long>? = null,
    onExit: () -> Unit,
) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    var start by remember { mutableStateOf<String?>(null) }

    // First launch: terms, then "Who's watching?" (create a profile), then Sources. No playlist is required.
    LaunchedEffect(Unit) { start = if (!prefs.termsAccepted) "terms" else afterTerms(profiles, providers, addons) }
    val profile by profiles.observeCurrent().collectAsState(initial = null)

    /** Clears the back stack and opens the right place for the profile now in use. */
    fun enterApp() = scope.launch {
        val next = landing(providers, addons)
        nav.navigate(next) { popUpTo(nav.graph.id) { inclusive = true } }
    }

    // Loading screen with the light ripple: shown at least 1.6 seconds when the app starts (not on rotation).
    val showLoading = com.vantaview.app.ui.LocalAppearance.current.loadingScreen
    var splash by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(showLoading) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(1_600); splash = false }

    val startRoute = start
    if (startRoute == null) {
        if (showLoading) com.vantaview.app.ui.SplashScreen() else Box(Modifier.fillMaxSize().background(Vanta.Bg))
        return
    }

    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val section = when {
        route == null -> null
        route == "home" -> Section.HOME
        route == "live" -> Section.LIVE
        route == "catchup" -> Section.LIVE // Catch-up sits under Live TV in the menu
        route == "sportstv" -> Section.HOME // Sports TV opens from its tile on Home
        route == "guide" -> Section.GUIDE
        route == "search" -> Section.SEARCH
        route.startsWith("library") -> Section.LIBRARY
        route == "movies" -> Section.MOVIES
        route == "series" -> Section.SERIES
        route.startsWith("sources") -> Section.SOURCES
        route == "settings" -> Section.SETTINGS
        else -> null
    }

    Box(Modifier.fillMaxSize()) {
    AppShell(
        current = section,
        profile = profile,
        // Choosing the page you're already on starts it over (Settings goes back to its list) instead of navigating.
        onSelect = { if (it == section && route != "catchup") com.vantaview.app.ui.Reselect.events.tryEmit(it) else nav.goTo(it.route) },
        onProfiles = { nav.navigate("profiles") },
        edgeToEdge = route?.let { it.startsWith("player") || it == "vod" } == true,
    ) {
        NavHost(navController = nav, startDestination = startRoute) {
            composable("terms") {
                TermsScreen(
                    onAgree = {
                        prefs.termsAccepted = true
                        scope.launch {
                            val next = afterTerms(profiles, providers, addons)
                            nav.navigate(next) { popUpTo(nav.graph.id) { inclusive = true } }
                        }
                    },
                    onDisagree = onExit,
                )
            }
            composable("profile/setup") {
                ProfileEditScreen(
                    profileId = 0L,
                    first = true,
                    onDone = { id ->
                        profiles.select(id)
                        // The profile is made: Back from the next step mustn't return here (it would make a second one).
                        nav.navigate("setup/playlist") { popUpTo(nav.graph.id) { inclusive = true } }
                    },
                    onBack = null,
                )
            }
            // First launch, steps 3 to 5: live TV, the connection check, then films and series (all can be skipped).
            composable("setup/playlist") {
                com.vantaview.app.feature.setup.SetupPlaylistScreen(onNext = { nav.navigate("setup/connection") })
            }
            composable("setup/connection") {
                com.vantaview.app.feature.setup.SetupConnectionScreen(onNext = { nav.navigate("setup/addons") })
            }
            composable("setup/addons") {
                com.vantaview.app.feature.setup.SetupAddonsScreen(onDone = {
                    nav.navigate("home") { popUpTo(nav.graph.id) { inclusive = true } }
                })
            }
            composable("profiles") {
                ChooseProfileScreen(
                    onChosen = { enterApp() },
                    onEdit = { id -> nav.navigate("profile/edit/$id") },
                    onAdd = { nav.navigate("profile/edit/0") },
                )
            }
            composable("profile/edit/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                ProfileEditScreen(
                    profileId = e.arguments?.getLong("id") ?: 0L,
                    first = false,
                    onDone = { nav.popBackStack() },
                    onBack = { nav.popBackStack() },
                )
            }
            composable("home") {
                // Back on Home: press twice to leave the app, so a stray press doesn't close it.
                var exitArmed by remember { mutableStateOf(false) }
                val context = androidx.compose.ui.platform.LocalContext.current
                LaunchedEffect(exitArmed) { if (exitArmed) { kotlinx.coroutines.delay(2_000); exitArmed = false } }
                androidx.activity.compose.BackHandler(enabled = !exitArmed) {
                    exitArmed = true
                    android.widget.Toast.makeText(context, com.vantaview.app.ui.tr("Press Back again to exit"), android.widget.Toast.LENGTH_SHORT).show()
                }
                HomeScreen(
                    // A link with a choice ("library?tab=watchlist") opens that page fresh on that tab.
                    onSection = { route -> nav.goTo(route, restore = '?' !in route) },
                    onOpenTitle = { type, id, name, poster, play ->
                        nav.navigate(
                            "details/${Uri.encode(type)}?id=${Uri.encode(id)}&name=${Uri.encode(name)}" +
                                "&poster=${Uri.encode(poster.orEmpty())}&play=${Uri.encode(play.orEmpty())}"
                        )
                    },
                    onPlayChannel = { id -> nav.navigate("player/$id") },
                )
            }
            composable("live") {
                LiveScreen(
                    onPlay = { id -> nav.navigate("player/$id") },
                    onAddPlaylist = { nav.goTo("sources?tab=playlists", restore = false) },
                    onAddAddon = { nav.goTo("sources?tab=addons", restore = false) },
                    onCatchUpPage = { nav.navigate("catchup") },
                )
            }
            // A whole row of Movies or TV Series.
            composable(
                "row/{type}?key={key}&title={title}",
                arguments = listOf(
                    navArgument("type") { type = NavType.StringType },
                    navArgument("key") { type = NavType.StringType; defaultValue = "" },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                ),
            ) {
                com.vantaview.app.feature.vod.CatalogRowScreen(onOpen = { m ->
                    nav.navigate("details/${Uri.encode(m.type)}?id=${Uri.encode(m.id)}&name=${Uri.encode(m.name)}&poster=${Uri.encode(m.poster.orEmpty())}")
                })
            }
            composable("catchup") {
                com.vantaview.app.feature.catchup.CatchUpScreen(onPlayRecording = { nav.navigate("vod") })
            }
            composable("settings") {
                // Kids profiles can't change settings.
                if (profile?.isKids == true) Box(Modifier.fillMaxSize().background(Vanta.Bg)) {
                    com.vantaview.app.ui.Text("Ask a grown-up to change settings.", color = Vanta.TextDim, modifier = Modifier.align(androidx.compose.ui.Alignment.Center))
                } else com.vantaview.app.feature.settings.SettingsScreen(onEditProfile = { id -> nav.navigate("profile/edit/$id") })
            }
            composable("search") {
                com.vantaview.app.feature.search.SearchScreen(
                    onPlayChannel = { id -> nav.navigate("player/$id") },
                    onPerson = { id -> nav.navigate("browse/movie/${Uri.encode("person:$id")}") },
                    onOpenTitle = { type, id, name, poster, play ->
                        nav.navigate(
                            "details/${Uri.encode(type)}?id=${Uri.encode(id)}&name=${Uri.encode(name)}" +
                                "&poster=${Uri.encode(poster.orEmpty())}&play=${Uri.encode(play.orEmpty())}"
                        )
                    },
                )
            }
            composable("library?tab={tab}", arguments = listOf(navArgument("tab") { type = NavType.StringType; nullable = true; defaultValue = null })) { e ->
                com.vantaview.app.feature.library.LibraryScreen(
                    initialTab = e.arguments?.getString("tab"),
                    onOpenTitle = { type, id, name, poster, play ->
                        nav.navigate(
                            "details/${Uri.encode(type)}?id=${Uri.encode(id)}&name=${Uri.encode(name)}" +
                                "&poster=${Uri.encode(poster.orEmpty())}&play=${Uri.encode(play.orEmpty())}"
                        )
                    },
                    onPlayChannel = { id -> nav.navigate("player/$id") },
                )
            }
            composable("guide") {
                com.vantaview.app.feature.guide.GuideScreen(onPlay = { id -> nav.navigate("player/$id") }, onCatchUp = { nav.navigate("vod") }, onCatchUpPage = { nav.navigate("catchup") })
            }
            // Movies and TV Series are separate destinations so each keeps its own page and state.
            // Sports TV: add-on rows of Stremio's "tv" and "channel" types (live channels and events).
            listOf("movies" to "movie", "series" to "series", "sportstv" to "tv").forEach { (route, kind) ->
                composable(route, arguments = listOf(navArgument("type") { type = NavType.StringType; defaultValue = kind })) {
                    CatalogScreen(
                        title = when (kind) { "series" -> "TV Series"; "tv" -> "Sports TV"; else -> "Movies" },
                        onOpen = { m ->
                            nav.navigate(
                                "details/${Uri.encode(m.type)}?id=${Uri.encode(m.id)}&name=${Uri.encode(m.name)}&poster=${Uri.encode(m.poster.orEmpty())}"
                            )
                        },
                        onAddAddon = { nav.goTo("sources?tab=addons", restore = false) },
                        onTile = { t -> nav.navigate("browse/$kind/${t.key}") },
                        onRow = { key, title -> nav.navigate("row/$kind?key=${Uri.encode(key)}&title=${Uri.encode(title)}") },
                    )
                }
            }
            composable(
                "browse/{type}/{key}",
                arguments = listOf(navArgument("type") { type = NavType.StringType }, navArgument("key") { type = NavType.StringType }),
            ) {
                com.vantaview.app.feature.vod.BrowseScreen(onOpen = { m ->
                    nav.navigate("details/${Uri.encode(m.type)}?id=${Uri.encode(m.id)}&name=${Uri.encode(m.name)}&poster=${Uri.encode(m.poster.orEmpty())}")
                })
            }
            composable(
                "sources?tab={tab}",
                arguments = listOf(navArgument("tab") { type = NavType.StringType; defaultValue = "playlists" }),
            ) { e ->
                // Settings › Parental control › Lock Sources: ask for the parent PIN first.
                val unlocked by parental.unlocked.collectAsState()
                parental.changes.collectAsState().value
                if (parental.lockSources && !unlocked) {
                    Box(Modifier.fillMaxSize().background(Vanta.Bg))
                    com.vantaview.app.feature.profiles.PinDialog(
                        title = "Sources is locked", message = "Enter the parent PIN to change playlists, add-ons and debrid.",
                        check = profiles::checkParentPin,
                        onOk = { parental.unlock() },
                        onCancel = { if (!nav.popBackStack()) nav.goTo(Section.HOME.route) },
                    )
                } else SourcesScreen(
                    when (e.arguments?.getString("tab")) {
                        "addons" -> SourcesTab.ADDONS
                        "debrid" -> SourcesTab.DEBRID
                        else -> SourcesTab.PLAYLISTS
                    }
                )
            }
            composable(
                "details/{type}?id={id}&name={name}&poster={poster}&play={play}",
                arguments = listOf(
                    navArgument("type") { type = NavType.StringType },
                    navArgument("id") { type = NavType.StringType; defaultValue = "" },
                    navArgument("name") { type = NavType.StringType; defaultValue = "" },
                    navArgument("poster") { type = NavType.StringType; defaultValue = "" },
                    navArgument("play") { type = NavType.StringType; defaultValue = "" },
                ),
            ) {
                DetailsScreen(
                    onPlay = { nav.navigate("vod") }, onBack = { nav.popBackStack() },
                    onOpenTitle = { m ->
                        nav.navigate("details/${Uri.encode(m.type)}?id=${Uri.encode(m.id)}&name=${Uri.encode(m.name)}&poster=${Uri.encode(m.poster.orEmpty())}")
                    },
                    onPerson = { name -> nav.navigate("browse/movie/${Uri.encode("name:$name")}") },
                )
            }
            composable(
                route = "player/{channelId}",
                arguments = listOf(navArgument("channelId") { type = NavType.LongType }),
            ) {
                PlayerScreen(onBack = { nav.popBackStack() })
            }
            composable("vod") {
                VodPlayerScreen(onBack = { nav.popBackStack() })
            }
        }
        if (profile != null) com.vantaview.app.feature.country.CountryChooserHost()
    }
    // The ripple is drawn by OpenGL, which can't be faded as a layer, so the splash dims itself out.
    var splashShown by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(showLoading) }
    LaunchedEffect(splash) { if (!splash) { kotlinx.coroutines.delay(420); splashShown = false } }
    if (splashShown) com.vantaview.app.ui.SplashScreen(leaving = !splash)
    // Programme reminders: pop-ups while the app is open, and opening a channel from a notification.
    // The screensaver, on menus only (never over a film or channel).
    if (!splashShown && profile != null && lastInput != null) com.vantaview.app.feature.ambient.AmbientHost(
        lastInput, allowed = route?.let { !it.startsWith("player") && it != "vod" && !it.startsWith("details") } == true,
    )
    if (!splashShown && profile != null) com.vantaview.app.feature.library.ReminderHost(
        library,
        // Settings › Playback can keep reminders off the screen while something plays.
        quiet = { !remindWhilePlaying() && nav.currentDestination?.route?.let { it.startsWith("player") || it.startsWith("vod") } == true },
    ) { id -> nav.navigate("player/$id") }
    val play by playRequest.collectAsState()
    LaunchedEffect(play, splashShown, profile) {
        val id = play ?: return@LaunchedEffect
        if (!splashShown && profile != null) { playRequest.value = null; nav.navigate("player/$id") }
    }

    // "What's new" once after an update (Settings › Updates can turn it off), before any update notice.
    var whatsNew by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(Unit) { whatsNew = updates.whatsNewToShow() }
    whatsNew?.let { bullets ->
        if (!splashShown && profile != null) {
            com.vantaview.app.feature.settings.WhatsNewDialog(
                bullets,
                onClose = { updates.markWhatsNewSeen(); whatsNew = null },
                onTurnOff = { updates.showWhatsNew = false; updates.markWhatsNewSeen(); whatsNew = null },
            )
        }
    }

    // Update notice on launch (not for Kids profiles, and not over the loading screen).
    val notice by updates.notice.collectAsState()
    notice?.let { info ->
        if (!splashShown && profile != null && profile?.isKids != true && whatsNew == null) {
            com.vantaview.app.feature.settings.UpdateNotice(info, updates, onSettings = { nav.goTo(Section.SETTINGS.route) })
        }
    }
    }
}
