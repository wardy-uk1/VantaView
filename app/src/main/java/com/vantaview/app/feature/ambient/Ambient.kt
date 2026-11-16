// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.ambient

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import com.vantaview.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.vantaview.app.feature.vod.backdropOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** Pictures and the weather for the screensaver. */
@HiltViewModel
class AmbientViewModel @Inject constructor(
    private val library: com.vantaview.app.data.library.LibraryStore,
    private val history: com.vantaview.app.data.history.WatchHistory,
    private val browse: com.vantaview.app.data.browse.BrowseRepository,
    val playback: com.vantaview.app.data.PlaybackSettings,
    weatherRepo: com.vantaview.app.data.weather.WeatherRepository,
) : ViewModel() {
    val weather: StateFlow<com.vantaview.app.data.weather.Weather?> = weatherRepo.weather

    /** Artwork: the profile's own films and series first, then what's popular. */
    suspend fun pictures(): List<Pair<String, String>> {
        val mine = (library.watchlistNow().map { com.vantaview.app.data.addon.MetaPreview(it.id, it.type, it.name, it.poster, it.year, null) } +
            history.all().map { com.vantaview.app.data.addon.MetaPreview(it.metaId, it.type, it.title, it.poster, null, null) })
        val popular = runCatching {
            browse.tile("trending")?.let { browse.page("movie", it, 1, false) }.orEmpty()
        }.getOrDefault(emptyList())
        return (mine + popular).distinctBy { it.id }.mapNotNull { m -> backdropOf(m)?.let { it to m.name } }.shuffled().take(40)
    }
}

/**
 * The screensaver: after [minutes] without a key press or touch on a menu, slowly drifting artwork with
 * the time, date and weather. Any key or touch closes it. Never shows while something is playing.
 */
@Composable
fun AmbientHost(lastInput: StateFlow<Long>, allowed: Boolean, vm: AmbientViewModel = hiltViewModel()) {
    val last by lastInput.collectAsStateWithLifecycle()
    var showing by remember { mutableStateOf(false) }
    LaunchedEffect(last, allowed) {
        showing = false
        val minutes = vm.playback.ambientMinutes
        if (!allowed || minutes <= 0) return@LaunchedEffect
        delay(minutes * 60_000L)
        showing = true
    }
    if (!showing) return
    var pics by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var i by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { pics = vm.pictures() }
    LaunchedEffect(pics.size) { while (pics.size > 1) { delay(20_000); i = (i + 1) % pics.size } }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(10_000) } }
    val weather by vm.weather.collectAsStateWithLifecycle()
    val drift = rememberInfiniteTransition(label = "drift")
    val scale by drift.animateFloat(1.0f, 1.12f, infiniteRepeatable(tween(20_000, easing = LinearEasing), RepeatMode.Reverse), label = "scale")

    Dialog(onDismissRequest = { showing = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Any key or touch closes it.
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(Unit) { delay(100); runCatching { focus.requestFocus() } }
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .focusRequester(focus)
                .onPreviewKeyEvent { showing = false; true }
                .focusable()
                .pointerInput(Unit) { detectTapGestures { showing = false } },
        ) {
            val low = com.vantaview.app.data.LowMemory.active
            Crossfade(pics.getOrNull(i), animationSpec = androidx.compose.animation.core.tween(if (low) 0 else 300), label = "art") { p ->
                if (p != null) AsyncImage(
                    model = p.first, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer { val k = if (low) 1f else scale; scaleX = k; scaleY = k; alpha = 0.75f },
                )
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xCC000000))))
            Column(Modifier.align(Alignment.BottomStart).padding(40.dp)) {
                Text(SimpleDateFormat("HH:mm", Locale.UK).format(Date(now)), color = Color.White, fontSize = 72.sp, fontWeight = FontWeight.Light)
                Text(SimpleDateFormat("EEEE d MMMM", com.vantaview.app.ui.I18n.locale).format(Date(now)), color = Color(0xDDFFFFFF), fontSize = 20.sp)
                weather?.let { w ->
                    Text(
                        "${w.temp}° · ${com.vantaview.app.data.weather.WeatherRepository.describe(w.code)}",
                        color = Color(0xDDFFFFFF), fontSize = 18.sp,
                    )
                }
            }
            pics.getOrNull(i)?.second?.let { name ->
                Text(name, color = Color(0x99FFFFFF), fontSize = 14.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(40.dp))
            }
        }
    }
}
