// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.ui

import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** One set of colours for the whole app. */
data class VantaPalette(
    val bg: Color, val surface: Color, val surfaceHigh: Color, val text: Color, val textDim: Color,
    val highlight: Color, val error: Color, val accent: Color, val light: Boolean,
    /** Dialogs and menus: always solid, even when [surface] lets the background show through. */
    val panel: Color = surface,
    /** Looks with their own background (Glass, Broadcast, Wave, Classic) leave pages see-through. */
    val look: com.vantaview.app.data.Look = com.vantaview.app.data.Look.VANTA,
    /** A focused guide or channel row is filled with this colour (and its text turns [onFill]). */
    val fill: Color? = null,
    val onFill: Color = Color(0xFF0B1450),
)

private val darkPalette = VantaPalette(
    bg = Color(0xFF07080F), surface = Color(0xFF10131F), surfaceHigh = Color(0xFF1A1F33),
    text = Color(0xFFF2F4FF), textDim = Color(0xFF9AA3C7), highlight = Color(0xFF00E5FF),
    error = Color(0xFFFF5370), accent = Color(0xFF6E54E6), light = false,
)

/** Builds the palette for a theme and accent. Light themes darken the highlight so it reads on white. */
fun paletteFor(theme: com.vantaview.app.data.ThemeMode, systemDark: Boolean, highlight: Color, focus: Color): VantaPalette {
    val mode = if (theme == com.vantaview.app.data.ThemeMode.SYSTEM) {
        if (systemDark) com.vantaview.app.data.ThemeMode.DARK else com.vantaview.app.data.ThemeMode.LIGHT
    } else theme
    return when (mode) {
        com.vantaview.app.data.ThemeMode.BLACK -> darkPalette.copy(
            bg = Color.Black, surface = Color(0xFF0B0C12), surfaceHigh = Color(0xFF161A2A), highlight = highlight, accent = focus,
        )
        com.vantaview.app.data.ThemeMode.LIGHT -> VantaPalette(
            bg = Color(0xFFF4F5FA), surface = Color.White, surfaceHigh = Color(0xFFE3E6F2),
            text = Color(0xFF12141F), textDim = Color(0xFF5A6385),
            highlight = androidx.compose.ui.graphics.lerp(highlight, Color.Black, 0.35f),
            error = Color(0xFFD32F45), accent = focus, light = true,
        )
        else -> darkPalette.copy(highlight = highlight, accent = focus)
    }
}

/** Colours for each look. VANTA isn't here: it follows the theme and accent. */
fun lookPalette(look: com.vantaview.app.data.Look): VantaPalette? = when (look) {
    com.vantaview.app.data.Look.VANTA -> null
    com.vantaview.app.data.Look.GLASS -> VantaPalette(
        bg = Color(0xFF0A1A55), surface = Color(0x40FFFFFF), surfaceHigh = Color(0x66FFFFFF),
        text = Color.White, textDim = Color(0xFFC6D3FF), highlight = Color(0xFF9FD8FF),
        error = Color(0xFFFF7A8A), accent = Color.White, light = false, panel = Color(0xFF16307F), look = look,
    )
    com.vantaview.app.data.Look.BROADCAST -> VantaPalette(
        bg = Color(0xFF1F5AD0), surface = Color(0xFF2050C4), surfaceHigh = Color(0xFF3A72EC),
        text = Color.White, textDim = Color(0xFFD5E1FF), highlight = Color(0xFFFFCB3D),
        error = Color(0xFFFFB4B4), accent = Color(0xFFFFCB3D), light = false, panel = Color(0xFF1B47B0), look = look,
        fill = Color(0xFFFFCB3D),
    )
    com.vantaview.app.data.Look.WAVE -> VantaPalette(
        bg = Color(0xFF07305E), surface = Color(0x26FFFFFF), surfaceHigh = Color(0x4DFFFFFF),
        text = Color.White, textDim = Color(0xFFB7D3F0), highlight = Color(0xFF8FE3FF),
        error = Color(0xFFFF8A9A), accent = Color.White, light = false, panel = Color(0xFF0C3F78), look = look,
    )
    com.vantaview.app.data.Look.BLADES -> VantaPalette(
        bg = Color(0xFFC9CFC4), surface = Color(0xFFF1F3EE), surfaceHigh = Color(0xFFDDE7D2),
        text = Color(0xFF1E261B), textDim = Color(0xFF4F5A4A), highlight = Color(0xFF3F8A10),
        error = Color(0xFFB3261E), accent = Color(0xFF5AA612), light = true, panel = Color(0xFFF4F6F1), look = look,
        fill = Color(0xFF5AA612), onFill = Color.White,
    )
    com.vantaview.app.data.Look.STACK -> VantaPalette(
        bg = Color(0xFF0F1B2A), surface = Color(0xCC1C2A3A), surfaceHigh = Color(0xFF2A3D52),
        text = Color.White, textDim = Color(0xFFB7C7D6), highlight = Color(0xFF8FD14F),
        error = Color(0xFFFF8A9A), accent = Color(0xFF8FD14F), light = false, panel = Color(0xFF16263A), look = look,
    )
    com.vantaview.app.data.Look.TILES -> VantaPalette(
        bg = Color(0xFFECECEC), surface = Color.White, surfaceHigh = Color(0xFFDCEEDC),
        text = Color(0xFF1A1A1A), textDim = Color(0xFF5F5F5F), highlight = Color(0xFF107C10),
        error = Color(0xFFB3261E), accent = Color(0xFF107C10), light = true, panel = Color(0xFFF7F7F7), look = look,
        fill = Color(0xFF107C10), onFill = Color.White,
    )
    com.vantaview.app.data.Look.FLUENT -> VantaPalette(
        bg = Color(0xFF121614), surface = Color(0xE6222A26), surfaceHigh = Color(0xFF34403A),
        text = Color.White, textDim = Color(0xFFC9D3CD), highlight = Color(0xFF9BE27A),
        error = Color(0xFFFF8A9A), accent = Color.White, light = false, panel = Color(0xFF1A201D), look = look,
    )
    com.vantaview.app.data.Look.CROSS -> VantaPalette(
        bg = Color(0xFF0A2F63), surface = Color(0x26FFFFFF), surfaceHigh = Color(0x4DFFFFFF),
        text = Color.White, textDim = Color(0xFFD6EAFF), highlight = Color(0xFFBFE3FF),
        error = Color(0xFFFF8A9A), accent = Color.White, light = false, panel = Color(0xFF0E3D78), look = look,
    )
    com.vantaview.app.data.Look.SPOTLIGHT -> VantaPalette(
        bg = Color(0xFF05070F), surface = Color(0x1FFFFFFF), surfaceHigh = Color(0x3DFFFFFF),
        text = Color.White, textDim = Color(0xFFA9B3CF), highlight = Color(0xFF8FB4FF),
        error = Color(0xFFFF8A9A), accent = Color.White, light = false, panel = Color(0xFF10162C), look = look,
    )
    com.vantaview.app.data.Look.CLASSIC -> VantaPalette(
        bg = Color(0xFF0B1450), surface = Color(0xFF2341C4), surfaceHigh = Color(0xFF3557DA),
        text = Color.White, textDim = Color(0xFFBFCBFF), highlight = Color(0xFFFFC928),
        error = Color(0xFFFFB4B4), accent = Color(0xFFFFC928), light = false, panel = Color(0xFF16237A), look = look,
        fill = Color(0xFFFFC928),
    )
}

/**
 * The app's colours. They follow Settings › Appearance: the palette is snapshot state, so screens
 * redraw when the theme or accent changes.
 */
object Vanta {
    var palette by mutableStateOf(darkPalette)
    val Bg get() = palette.bg
    val Surface get() = palette.surface
    val SurfaceHigh get() = palette.surfaceHigh
    val Text get() = palette.text
    val TextDim get() = palette.textDim
    /** The highlight colour (named Cyan because that's the default). */
    val Cyan get() = palette.highlight
    val Error get() = palette.error
    /** Dialogs and menus. */
    val Panel get() = palette.panel
    /** A page's own background: see-through when the look draws one behind every page. */
    val Page get() = if (palette.look == com.vantaview.app.data.Look.VANTA) palette.bg else Color.Transparent
    val Look get() = palette.look
    /** Card corners for the look: square for Tiles, round for Spotlight. */
    val Radius: androidx.compose.ui.unit.Dp get() = when (palette.look) {
        com.vantaview.app.data.Look.TILES -> 0.dp
        com.vantaview.app.data.Look.FLUENT, com.vantaview.app.data.Look.CROSS -> 6.dp
        com.vantaview.app.data.Look.BLADES -> 10.dp
        com.vantaview.app.data.Look.SPOTLIGHT -> 18.dp
        else -> 12.dp
    }
    val Shape: Shape get() = RoundedCornerShape(Radius)
    val Gradient = listOf(
        Color(0xFF7C4DFF), Color(0xFF2979FF), Color(0xFF00E5FF),
        Color(0xFF00E676), Color(0xFFFFEA00), Color(0xFFFF9100),
    )
    /** Focus rings and main buttons; violet unless the accent is changed. */
    val Accent get() = palette.accent
    /** The full logo gradient, kept for the logo itself; the UI uses the solid [Accent] instead. */
    val LogoBrush: Brush get() = Brush.linearGradient(Gradient)
    val GradientBrush: Brush get() = androidx.compose.ui.graphics.SolidColor(Accent)
}

/** True inside a focused [FocusCard] or [FillCard], so long titles can scroll only there. */
val LocalCardFocused = androidx.compose.runtime.compositionLocalOf { false }

/** Scrolls text that doesn't fit, while its card has focus. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Modifier.scrollWhenFocused(): Modifier =
    if (LocalCardFocused.current) this.basicMarquee(initialDelayMillis = 900) else this

/** Settings › Appearance for the whole app (sidebar auto-hide, loading screen...). */
val LocalAppearance = androidx.compose.runtime.staticCompositionLocalOf { com.vantaview.app.data.Appearance() }

@Composable
fun VantaTheme(appearance: com.vantaview.app.data.Appearance = com.vantaview.app.data.Appearance(), content: @Composable () -> Unit) {
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val highlight = Color(appearance.accent?.highlight ?: appearance.custom)
    val focus = appearance.accent?.let { Color(it.focus) } ?: androidx.compose.ui.graphics.lerp(Color(appearance.custom), Color.Black, 0.2f)
    val p = lookPalette(appearance.look) ?: paletteFor(appearance.theme, systemDark, highlight, focus)
    Vanta.palette = p
    // Dark status bar icons on the light theme.
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        @Suppress("DEPRECATION")
        run { window.statusBarColor = p.bg.toArgb(); window.navigationBarColor = p.bg.toArgb() }
        androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = p.light
            isAppearanceLightNavigationBars = p.light
        }
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Settings › Appearance › Screen size: Auto from the device and screen, or a fixed size.
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val tv = remember { ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) }
    val ui = appearance.uiSize
    val uiScale = ui.scale ?: com.vantaview.app.data.UiSize.auto(tv, config.screenWidthDp, config.smallestScreenWidthDp)
    val systemFont = if (ui == com.vantaview.app.data.UiSize.AUTO) density.fontScale.coerceAtMost(com.vantaview.app.data.UiSize.AUTO_MAX_FONT) else density.fontScale
    val colors = if (p.light) {
        androidx.compose.material3.lightColorScheme(
            primary = p.accent, onPrimary = Color.White, secondary = Color(0xFF7C4DFF),
            background = p.bg, onBackground = p.text, surface = p.panel, onSurface = p.text,
            surfaceVariant = p.surfaceHigh, onSurfaceVariant = p.textDim, error = p.error,
        )
    } else darkColorScheme(
        primary = p.accent, onPrimary = Color.White, secondary = Color(0xFF7C4DFF),
        background = p.bg, onBackground = p.text, surface = p.panel, onSurface = p.text,
        surfaceVariant = p.surfaceHigh, onSurfaceVariant = p.textDim, error = p.error,
    )
    androidx.compose.runtime.CompositionLocalProvider(
        LocalAppearance provides appearance,
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
            density.density * uiScale, systemFont * appearance.textSize.scale,
        ),
    ) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}

/**
 * A clickable block that works with touch and with a TV remote: it shows the logo-gradient ring
 * when it has focus.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    shape: Shape = Vanta.Shape,
    padding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    contentAlignment: Alignment = Alignment.CenterStart,
    /** Press and hold (or the remote's Menu key) — e.g. a title's quick actions. */
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (onLongClick == null) Modifier
                else Modifier.onPreviewKeyEvent { e ->
                    val menu = e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_MENU
                    if (menu && e.type == androidx.compose.ui.input.key.KeyEventType.KeyUp) onLongClick()
                    menu
                }
            )
            .clip(shape)
            .background(if (selected || focused) Vanta.SurfaceHigh else Vanta.Surface)
            .then(if (focused) Modifier.border(3.dp, Vanta.GradientBrush, shape) else Modifier)
            .then(
                if (onLongClick == null) Modifier.clickable(onClick = onClick)
                else Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
            )
            .padding(padding),
        contentAlignment = contentAlignment,
    ) { androidx.compose.runtime.CompositionLocalProvider(LocalCardFocused provides focused) { content() } }
}

/** Main action button filled with the logo gradient. */
@Composable
fun GradientButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    // Looks with a white or yellow accent (Glass, Wave, Broadcast…) get dark writing, so the label shows.
    val lightFill = enabled && Vanta.Accent.luminance() > 0.45f
    val ink = if (lightFill) Color(0xFF0A1028) else Color.White
    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (enabled) Vanta.GradientBrush else Brush.linearGradient(listOf(Vanta.SurfaceHigh, Vanta.SurfaceHigh)))
            .then(if (focused) Modifier.border(3.dp, ink, shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) ink else Vanta.TextDim, fontWeight = FontWeight.Bold)
    }
}

/**
 * Like [FocusCard], for guide cells and channel rows: in looks with a fill colour (Broadcast,
 * Classic) the focused row turns that colour. [content] gets the text colour to use.
 */
@Composable
fun FillCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    shape: Shape = Vanta.Shape,
    padding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    content: @Composable (text: Color, dim: Color) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val fill = Vanta.palette.fill
    val filled = focused && fill != null
    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (filled) fill!! else if (selected || focused) Vanta.SurfaceHigh else Vanta.Surface)
            .then(if (focused && !filled) Modifier.border(3.dp, Vanta.GradientBrush, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(padding),
        contentAlignment = Alignment.CenterStart,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalCardFocused provides focused) {
            if (filled) content(Vanta.palette.onFill, Vanta.palette.onFill.copy(alpha = 0.75f)) else content(Vanta.Text, Vanta.TextDim)
        }
    }
}

/** A page's title, in the look's own style (lower case on Tiles, light on Spotlight, green on Blades...). */
@Composable
fun PageTitle(text: String, modifier: Modifier = Modifier) {
    val look = Vanta.Look
    val shown = if (look == com.vantaview.app.data.Look.TILES) text.lowercase() else text
    val (size, weight, colour) = when (look) {
        com.vantaview.app.data.Look.TILES -> Triple(34, FontWeight.Light, Vanta.Text)
        com.vantaview.app.data.Look.SPOTLIGHT -> Triple(32, FontWeight.Light, Vanta.Text)
        com.vantaview.app.data.Look.BLADES -> Triple(30, FontWeight.Light, Vanta.Cyan)
        com.vantaview.app.data.Look.STACK -> Triple(30, FontWeight.SemiBold, Vanta.Text)
        com.vantaview.app.data.Look.FLUENT -> Triple(26, FontWeight.SemiBold, Vanta.Text)
        com.vantaview.app.data.Look.CROSS -> Triple(24, FontWeight.Normal, Vanta.Text)
        else -> Triple(24, FontWeight.Bold, Vanta.Text)
    }
    Text(
        shown, color = colour, fontWeight = weight, fontSize = androidx.compose.ui.unit.TextUnit(size.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp),
        style = if (look == com.vantaview.app.data.Look.CROSS) androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.White.copy(alpha = 0.6f), blurRadius = 14f)) else androidx.compose.ui.text.TextStyle.Default,
        modifier = modifier,
    )
}

/** A heading inside a page (a catalogue row, a group of tiles), in the look's own style. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val look = Vanta.Look
    val label: @Composable () -> Unit = {
        Text(
            (if (look == com.vantaview.app.data.Look.TILES) text.lowercase() else text) + if (onClick != null) "  ›" else "",
            color = if (look == com.vantaview.app.data.Look.BLADES) Vanta.Cyan else Vanta.Text,
            fontWeight = when (look) {
                com.vantaview.app.data.Look.TILES, com.vantaview.app.data.Look.SPOTLIGHT -> FontWeight.Light
                com.vantaview.app.data.Look.FLUENT, com.vantaview.app.data.Look.CROSS -> FontWeight.SemiBold
                else -> FontWeight.Bold
            },
            fontSize = androidx.compose.ui.unit.TextUnit(if (look == com.vantaview.app.data.Look.TILES) 21f else 17f, androidx.compose.ui.unit.TextUnitType.Sp),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
    // A section that opens: the title is a button to its full page ("See all").
    if (onClick == null) Box(modifier) { label() }
    else FocusCard(onClick = onClick, modifier = modifier, padding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) { label() }
}
