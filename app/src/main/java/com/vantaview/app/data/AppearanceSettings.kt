// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode(val label: String) { DARK("Dark"), BLACK("Black (OLED)"), LIGHT("Light"), SYSTEM("Follow device") }

/** Highlight colour (selected items, progress) and focus colour (focus rings, main buttons). */
enum class AccentPreset(val label: String, val highlight: Long, val focus: Long) {
    CYAN("Cyan", 0xFF00E5FF, 0xFF6E54E6),
    MAGENTA("Magenta", 0xFFFF4FD8, 0xFFC2369F),
    PURPLE("Purple", 0xFFA88BFF, 0xFF6E54E6),
    ORANGE("Orange", 0xFFFF9100, 0xFFE0631A),
    GREEN("Green", 0xFF19E57A, 0xFF12A65A),
    GOLD("Gold", 0xFFFFC81A, 0xFFD19A00),
}

/**
 * A whole look for the app: colours, background, menu and the TV Guide's layout. VANTA is the
 * original look, where the theme and accent colour below apply.
 */
enum class Look(
    val label: String,
    val description: String,
    /** Sections in a bar across the top on wide screens (instead of the sidebar). */
    val topMenu: Boolean = false,
    /** Home is a console-style dashboard on wide screens. */
    val dashboard: Boolean = false,
) {
    VANTA("VantaView", "The original dark look, with your theme and accent colour"),
    GLASS("Glass", "Deep blue glass, with the chosen programme shown big at the top of the guide"),
    BROADCAST("Broadcast", "Bright blue, a menu across the top and a TV Guide like a set-top box", topMenu = true),
    WAVE("Wave", "Flowing waves behind floating lists"),
    CLASSIC("Classic", "Navy and royal blue with a yellow highlight and a menu across the top", topMenu = true),
    BLADES("Blades", "Silver-green tabs you flick through sideways, like a 2005 games console", dashboard = true),
    STACK("Stack", "A menu down the side and a 3D stack of panels, like a 2008 games console", dashboard = true),
    TILES("Tiles", "Light grey with green tiles and a lower-case menu across the top, like a 2011 games console", topMenu = true, dashboard = true),
    FLUENT("Fluent", "Dark, with a big picture of what's on and a row of square tiles, like a 2020 games console", topMenu = true, dashboard = true),
    CROSS("Cross", "Blue waves, icons across and items down, like a 2006 games console", topMenu = true, dashboard = true),
    SPOTLIGHT("Spotlight", "Dark blue, rounded tiles and a big title with a Watch button, like a 2020 games console", topMenu = true, dashboard = true),
}

/**
 * Screen size (Settings › Appearance): how big everything is drawn. Auto picks it from the device and screen;
 * the others are fixed sizes for when Auto isn't right on a particular screen.
 */
enum class UiSize(val label: String, val scale: Float?) {
    AUTO("Auto", null), COMPACT("Compact", 0.85f), NORMAL("Normal", 1f), LARGE("Large", 1.15f);

    companion object {
        /**
         * Auto: TVs that report a very wide screen (some 4K sets) are scaled up so the layout fills them as on
         * a 1080p TV; very narrow phones are scaled down a little so pages fit across.
         */
        fun auto(tv: Boolean, widthDp: Int, smallestDp: Int): Float = when {
            tv && widthDp > 1100 -> (widthDp / 960f).coerceAtMost(1.6f)
            !tv && smallestDp in 1..359 -> 0.9f
            else -> 1f
        }

        /** Auto also keeps a very large system font size from breaking layouts. */
        const val AUTO_MAX_FONT = 1.3f
    }
}

enum class TextSize(val label: String, val scale: Float) { SMALL("Small", 0.9f), NORMAL("Normal", 1f), LARGE("Large", 1.15f) }

data class Appearance(
    val look: Look = Look.VANTA,
    val theme: ThemeMode = ThemeMode.DARK,
    val accent: AccentPreset? = AccentPreset.CYAN,
    /** Used when [accent] is null (a custom colour), as 0xAARRGGBB. */
    val custom: Long = 0xFF00E5FF,
    val textSize: TextSize = TextSize.NORMAL,
    val uiSize: UiSize = UiSize.AUTO,
    val sidebarAutoHide: Boolean = true,
    val loadingScreen: Boolean = true,
    /** A language code from [com.vantaview.app.ui.I18n.languages], or "" to follow the device. */
    val language: String = "",
)

/**
 * Settings › Appearance. Each profile has its own; a profile that never changed it uses the
 * device's original settings.
 */
@Singleton
class AppearanceSettings @Inject constructor(
    @ApplicationContext context: Context,
    private val session: com.vantaview.app.data.profile.ProfileSession,
    @com.vantaview.app.di.AppScope scope: kotlinx.coroutines.CoroutineScope,
) {
    private val sp = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Appearance> = _state

    init {
        com.vantaview.app.ui.I18n.use(context, _state.value.language)
        scope.launch { session.current.collect { _state.value = read() } }
        // Each profile can have its own language; switching profile or changing it applies at once.
        scope.launch { _state.collect { com.vantaview.app.ui.I18n.use(context, it.language) } }
    }

    /** This profile's value for [key], or the device-wide one from before appearances were per profile. */
    private fun k(key: String) = "p${session.id}_$key".takeIf { sp.contains(it) } ?: key

    private fun read() = Appearance(
        look = runCatching { Look.valueOf(sp.getString(k("look"), null)!!) }.getOrDefault(Look.VANTA),
        theme = runCatching { ThemeMode.valueOf(sp.getString(k("theme"), null)!!) }.getOrDefault(ThemeMode.DARK),
        accent = sp.getString(k("accent"), AccentPreset.CYAN.name).let { n -> AccentPreset.entries.firstOrNull { it.name == n } },
        custom = sp.getLong(k("custom"), 0xFF00E5FF),
        textSize = runCatching { TextSize.valueOf(sp.getString(k("text"), null)!!) }.getOrDefault(TextSize.NORMAL),
        uiSize = runCatching { UiSize.valueOf(sp.getString(k("ui_size"), null)!!) }.getOrDefault(UiSize.AUTO),
        sidebarAutoHide = sp.getBoolean(k("sidebar_autohide"), true),
        loadingScreen = sp.getBoolean(k("loading_screen"), true),
        language = sp.getString(k("language"), "") ?: "",
    )

    fun update(change: (Appearance) -> Appearance) {
        val a = change(_state.value)
        val p = "p${session.id}_"
        sp.edit()
            .putString(p + "look", a.look.name)
            .putString(p + "theme", a.theme.name)
            .putString(p + "accent", a.accent?.name ?: "CUSTOM")
            .putLong(p + "custom", a.custom)
            .putString(p + "text", a.textSize.name)
            .putString(p + "ui_size", a.uiSize.name)
            .putBoolean(p + "sidebar_autohide", a.sidebarAutoHide)
            .putBoolean(p + "loading_screen", a.loadingScreen)
            .putString(p + "language", a.language)
            .apply()
        _state.value = a
    }

    /** Re-reads after a backup is restored. */
    fun reload() { _state.value = read() }
}
