// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.live

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.vantaview.app.data.ChannelNames
import com.vantaview.app.data.db.ChannelEntity
import com.vantaview.app.data.db.ProgrammeEntity
import com.vantaview.app.data.repo.ProviderRepository
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.GradientButton
import com.vantaview.app.ui.Text
import com.vantaview.app.ui.Vanta
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Date
import javax.inject.Inject

/**
 * Live TV › preview: the first press on a channel plays it in a small window beside (TV) or above (phone)
 * the list, with what's on; pressing it again, or the window, opens it full screen. Settings › Playback
 * can turn this off to go straight to full screen.
 */
@HiltViewModel
class LivePreviewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val providers: ProviderRepository,
    private val okHttp: OkHttpClient,
    private val guide: com.vantaview.app.data.guide.GuideRepository,
    private val playback: com.vantaview.app.data.PlaybackSettings,
) : ViewModel() {
    val enabled: Boolean get() = playback.livePreview

    private val _channel = MutableStateFlow<ChannelEntity?>(null)
    val channel: StateFlow<ChannelEntity?> = _channel
    private val _nowNext = MutableStateFlow<List<ProgrammeEntity>>(emptyList())
    val nowNext: StateFlow<List<ProgrammeEntity>> = _nowNext
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    @OptIn(UnstableApi::class)
    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { _error.value = "Preview unavailable" }
        })
    }
    private var job: Job? = null

    @OptIn(UnstableApi::class)
    fun show(ch: ChannelEntity) {
        job?.cancel()
        player.stop()
        _channel.value = ch
        _error.value = null
        _nowNext.value = emptyList()
        job = viewModelScope.launch {
            ch.epgChannelId?.let { key -> _nowNext.value = runCatching { guide.nowNext(ch.providerId, key) }.getOrDefault(emptyList()) }
            try {
                val t = providers.resolveStream(ch)
                val ua = t.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: "VantaView"
                val http = OkHttpDataSource.Factory(okHttp).setUserAgent(ua)
                    .setDefaultRequestProperties(t.headers.filterKeys { !it.equals("User-Agent", true) })
                val url = t.altUrl ?: t.url
                val item = MediaItem.Builder().setUri(url).apply {
                    if (url.substringBefore('?').endsWith(".m3u8", true)) setMimeType(MimeTypes.APPLICATION_M3U8)
                }.build()
                player.setMediaSource(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)).createMediaSource(item))
                player.prepare()
                player.playWhenReady = true
            } catch (e: Exception) {
                _error.value = "Preview unavailable"
            }
        }
    }

    /** Stops playing (leaving the page, or going full screen: providers often allow one stream at a time). */
    fun pause() { job?.cancel(); player.stop() }

    /** Back on the page: plays the channel that was showing again. */
    fun resume() { _channel.value?.let(::show) }

    fun close() { pause(); _channel.value = null }

    override fun onCleared() = player.release()
}

/** The preview window: the picture, the channel, what's on now and next, and full screen. */
@OptIn(UnstableApi::class)
@Composable
fun LivePreviewPanel(vm: LivePreviewModel, wide: Boolean, onFull: (ChannelEntity) -> Unit, modifier: Modifier = Modifier) {
    val ch by vm.channel.collectAsStateWithLifecycle()
    val nowNext by vm.nowNext.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val c = ch ?: return
    val time = remember { SimpleDateFormat("HH:mm", com.vantaview.app.ui.I18n.locale) }
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Vanta.Surface).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        player = vm.player
                    }
                },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )
            error?.let {
                Text(it, color = Vanta.TextDim, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
            }
            Box(Modifier.fillMaxSize().clickable { onFull(c) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${c.number}  ${ChannelNames.display(c.name)}", color = Vanta.Text, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val now = nowNext.firstOrNull()
                if (now != null) {
                    Text(
                        com.vantaview.app.ui.tr("Now: {0}", now.title), color = Vanta.Text, fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val f = ((System.currentTimeMillis() - now.start).toFloat() / (now.stop - now.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                    Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Vanta.SurfaceHigh)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(Vanta.Cyan))
                    }
                    nowNext.getOrNull(1)?.let {
                        Text(
                            com.vantaview.app.ui.tr("Next: {0} {1}", time.format(Date(it.start)), it.title), color = Vanta.TextDim, fontSize = 13.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            GradientButton("Watch full screen", onClick = { onFull(c) })
            FocusCard(onClick = vm::close) { Text("Close", color = Vanta.Text) }
        }
        if (wide) Text("Press OK on the channel again for full screen.", color = Vanta.TextDim, fontSize = 12.sp)
    }
}

/** Stops the preview when the page goes away or the app is in the background, and starts it again after. */
@Composable
fun LivePreviewLifecycle(vm: LivePreviewModel) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> vm.pause()
                Lifecycle.Event.ON_START -> vm.resume()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); vm.pause() }
    }
}
