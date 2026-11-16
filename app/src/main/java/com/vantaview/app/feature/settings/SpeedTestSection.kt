// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.GradientButton
import com.vantaview.app.ui.Text
import com.vantaview.app.ui.Vanta
import com.vantaview.app.ui.tr
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.log10

enum class SpeedPhase { PING, DOWN, UP }

data class SpeedUi(
    val running: Boolean = false,
    val phase: SpeedPhase? = null,
    /** The speed (Mbit/s) or ping (ms) the dial shows right now. */
    val live: Double = 0.0,
    val ping: Double? = null,
    val jitter: Double? = null,
    val down: Double? = null,
    val up: Double? = null,
    val status: String? = null,
    val error: Boolean = false,
    /** The last test was from a playlist channel (download only). */
    val playlist: Boolean = false,
    /** What "Adjust my settings" changed, one line each, once it's been used. */
    val tuned: List<String>? = null,
)

/**
 * Settings › Sources: a speed test. Ping and jitter, then download and upload with several connections at once
 * (as browser speed tests do), against Cloudflare's speed-test server; or a download from your own playlist.
 */
@HiltViewModel
class SpeedTestViewModel @Inject constructor(
    http: OkHttpClient,
    private val db: com.vantaview.app.data.db.VantaDb,
    private val repo: com.vantaview.app.data.repo.ProviderRepository,
    private val session: com.vantaview.app.data.profile.ProfileSession,
    private val playback: com.vantaview.app.data.PlaybackSettings,
) : ViewModel() {
    /** After a full test: sets the buffer, stream quality, torrents and mini player to suit this connection. */
    fun adjust() {
        val u = _ui.value
        val down = u.down ?: return
        if (u.playlist) return
        val lines = com.vantaview.app.data.ConnectionTuning.apply(com.vantaview.app.data.ConnectionTuning.advise(down, u.jitter), playback)
        _ui.update { it.copy(tuned = lines) }
    }

    private val client = http.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS)
        .cache(null).build()
    private val _ui = MutableStateFlow(SpeedUi())
    val ui: StateFlow<SpeedUi> = _ui
    private var job: Job? = null
    private val calls = CopyOnWriteArrayList<Call>()

    fun start() {
        stop()
        _ui.value = SpeedUi(running = true)
        job = viewModelScope.launch {
            try {
                _ui.update { it.copy(phase = SpeedPhase.PING, status = tr("Measuring ping…")) }
                val (ping, jitter) = ping()
                _ui.update { it.copy(ping = ping, jitter = jitter, live = 0.0, phase = SpeedPhase.DOWN, status = tr("Testing download…")) }
                val down = download(listOf(DOWN_URL), emptyMap(), CONNECTIONS)
                _ui.update { it.copy(down = down, live = 0.0, phase = SpeedPhase.UP, status = tr("Testing upload…")) }
                val up = upload()
                _ui.update { it.copy(up = up, live = 0.0, phase = null, running = false, status = verdict(down, ping)) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(running = false, phase = null, live = 0.0, error = true, status = tr("The test didn't finish ({0}).", e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    fun playlist() {
        stop()
        _ui.value = SpeedUi(running = true, playlist = true, status = tr("Finding a channel…"))
        job = viewModelScope.launch {
            val pid = session.lastProvider().takeIf { it > 0 } ?: db.providers().allFor(session.id).firstOrNull()?.id
            val ch = pid?.let { db.content().channelsPage(it, 1).firstOrNull() }
            if (ch == null) { _ui.value = SpeedUi(playlist = true, status = tr("Add a playlist first."), error = true); return@launch }
            val t = runCatching { repo.resolveStream(ch) }.getOrNull()
            if (t == null) { _ui.value = SpeedUi(playlist = true, status = tr("Couldn't open a channel to test."), error = true); return@launch }
            val name = com.vantaview.app.data.ChannelNames.display(ch.name)
            _ui.update { it.copy(phase = SpeedPhase.DOWN, status = tr("Download from your playlist ({0})", name)) }
            try {
                // One connection: providers often allow only one stream at a time.
                val down = download(listOf(t.url), t.headers, 1)
                _ui.update {
                    it.copy(
                        down = down, live = 0.0, phase = null, running = false,
                        status = tr("Download from your playlist ({0})", name) + ": " + verdict(down, null) + " " +
                            tr("Live channels only send as fast as they play, so this shows the channel's own rate at least."),
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(running = false, phase = null, live = 0.0, error = true, status = tr("The test didn't finish ({0}).", e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        calls.forEach { it.cancel() }; calls.clear()
        _ui.update { if (it.running) it.copy(running = false, phase = null, live = 0.0, status = null) else it }
    }

    override fun onCleared() = stop()

    private fun track(c: Call): Call = c.also { calls += it }

    /** Round trips to the server: a warm-up, then [PINGS] timed ones. Returns the middle value and the jitter. */
    private suspend fun ping(): Pair<Double, Double> = withContext(Dispatchers.IO) {
        val times = ArrayList<Double>()
        repeat(PINGS + 1) { i ->
            val t0 = System.nanoTime()
            track(client.newCall(Request.Builder().url(PING_URL).build())).execute().use { r ->
                if (!r.isSuccessful) error(tr("the server said {0}", r.code))
                val ms = (System.nanoTime() - t0) / 1e6
                // Take off the time the server itself spent, when it says.
                val server = r.header("Server-Timing")?.let { Regex("dur=([0-9.]+)").find(it)?.groupValues?.get(1)?.toDoubleOrNull() } ?: 0.0
                if (i > 0) {
                    times += (ms - server).coerceAtLeast(0.5)
                    _ui.update { it.copy(live = times.last()) }
                }
            }
            delay(80)
        }
        val sorted = times.sorted()
        val jitter = times.zipWithNext { a, b -> abs(a - b) }.average()
        sorted[sorted.size / 2] to jitter
    }

    /**
     * Downloads over [connections] connections for [SECONDS] seconds. The first second (while the connections
     * speed up) isn't counted. Returns Mbit/s.
     */
    private suspend fun download(urls: List<String>, headers: Map<String, String>, connections: Int): Double =
        measure { bytes, until ->
            coroutineScope {
                (0 until connections).map { n ->
                    async(Dispatchers.IO) {
                        runCatching {
                            while (isActive && System.nanoTime() < until) {
                                val b = Request.Builder().url(urls[n % urls.size])
                                headers.forEach { (k, v) -> b.header(k, v) }
                                track(client.newCall(b.build())).execute().use { r ->
                                    if (!r.isSuccessful) error(tr("the server said {0}", r.code))
                                    val input = r.body!!.byteStream()
                                    val buf = ByteArray(64 * 1024)
                                    while (isActive && System.nanoTime() < until) {
                                        val read = input.read(buf); if (read < 0) break
                                        bytes.addAndGet(read.toLong())
                                    }
                                }
                            }
                        }.exceptionOrNull()
                    }
                }.awaitAll().filterNotNull().firstOrNull()?.takeIf { bytes.get() == 0L }?.let { throw it }
                Unit
            }
        }

    /** Uploads over several connections for [SECONDS] seconds. Returns Mbit/s. */
    private suspend fun upload(): Double {
        val chunk = ByteArray(64 * 1024).also { java.util.Random().nextBytes(it) }
        return measure { bytes, until ->
            coroutineScope {
                (0 until CONNECTIONS).map {
                    async(Dispatchers.IO) {
                        runCatching {
                            while (isActive && System.nanoTime() < until) {
                                val body = object : RequestBody() {
                                    override fun contentType() = "application/octet-stream".toMediaType()
                                    override fun contentLength() = UPLOAD_BYTES
                                    override fun writeTo(sink: BufferedSink) {
                                        var left = UPLOAD_BYTES
                                        while (left > 0) {
                                            if (System.nanoTime() >= until) throw java.io.IOException("time")
                                            val n = minOf(left, chunk.size.toLong()).toInt()
                                            sink.write(chunk, 0, n); sink.flush()
                                            bytes.addAndGet(n.toLong()); left -= n
                                        }
                                    }
                                }
                                track(client.newCall(Request.Builder().url(UP_URL).post(body).build())).execute().close()
                            }
                        }.exceptionOrNull()
                    }
                }.awaitAll().filterNotNull().firstOrNull()?.takeIf { bytes.get() == 0L }?.let { throw it }
                Unit
            }
        }
    }

    /** Runs [work] for [SECONDS] seconds, showing the speed on the dial as it goes. */
    private suspend fun measure(work: suspend (AtomicLong, Long) -> Unit): Double = coroutineScope {
        val bytes = AtomicLong()
        val start = System.nanoTime()
        val until = start + SECONDS * 1_000_000_000L
        var countedFrom = 0L
        var countedAt = start
        var warmedUp = false
        val sampler = launch {
            var lastBytes = 0L
            var lastAt = start
            var smooth = 0.0
            while (isActive) {
                delay(250)
                val now = System.nanoTime()
                val b = bytes.get()
                val mbps = (b - lastBytes) * 8 / 1e6 / ((now - lastAt) / 1e9)
                smooth = if (smooth == 0.0) mbps else smooth * 0.6 + mbps * 0.4
                lastBytes = b; lastAt = now
                if (!warmedUp && now - start > WARMUP_NANOS) { warmedUp = true; countedFrom = b; countedAt = now }
                _ui.update { it.copy(live = smooth) }
            }
        }
        try { work(bytes, until) } finally { sampler.cancel(); calls.forEach { it.cancel() }; calls.clear() }
        val end = minOf(System.nanoTime(), until)
        val secs = ((end - countedAt) / 1e9).coerceAtLeast(0.2)
        (bytes.get() - countedFrom) * 8 / 1e6 / secs
    }

    private fun verdict(down: Double, ping: Double?): String {
        val v = when {
            down >= 25 -> "Plenty for 4K."
            down >= 8 -> "Good for HD (1080p)."
            down >= 4 -> "OK for HD at times; SD is safe. A bigger buffer helps."
            else -> "Slow: expect stopping to load. Try SD streams and a Large buffer."
        }
        return tr(v) + if (ping != null && ping > 100) " " + tr("Ping is high, so channels may take a little longer to start.") else ""
    }

    private companion object {
        const val PING_URL = "https://speed.cloudflare.com/__down?bytes=0"
        const val DOWN_URL = "https://speed.cloudflare.com/__down?bytes=50000000"
        const val UP_URL = "https://speed.cloudflare.com/__up"
        const val UPLOAD_BYTES = 8L * 1024 * 1024
        const val CONNECTIONS = 4
        const val PINGS = 10
        const val SECONDS = 10
        const val WARMUP_NANOS = 1_500_000_000L
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SpeedTestSection(vm: SpeedTestViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    Section("Speed test") {
        Text(
            "Check how fast and steady this device's connection is: download, upload and ping. Useful for choosing a buffer size or finding where stopping comes from.",
            color = Vanta.TextDim, fontSize = 13.sp,
        )
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { SpeedDial(ui) }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ResultTile("Ping", ui.ping?.let { "%.0f".format(it) }, "ms", ui.jitter?.let { tr("Jitter {0} ms", "%.0f".format(it)) }, ui.phase == SpeedPhase.PING)
            ResultTile("Download", ui.down?.let(::mbps), "Mbit/s", null, ui.phase == SpeedPhase.DOWN)
            ResultTile("Upload", ui.up?.let(::mbps), "Mbit/s", null, ui.phase == SpeedPhase.UP)
        }
        ui.status?.let { Text(it, color = if (ui.error) Vanta.Error else Vanta.Cyan, fontSize = 14.sp) }
        ui.tuned?.let { lines ->
            Text("Settings adjusted for this connection:", color = Vanta.Text, fontSize = 14.sp)
            lines.forEach { Text("• $it", color = Vanta.TextDim, fontSize = 13.sp) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!ui.running && ui.down != null && !ui.playlist && ui.tuned == null) {
                FocusCard(onClick = vm::adjust) { Text("Adjust my settings for this connection", color = Vanta.Cyan, maxLines = 1, softWrap = false) }
            }
            if (ui.running) {
                FocusCard(onClick = vm::stop) { Text("Stop", color = Vanta.Text, maxLines = 1, softWrap = false) }
            } else {
                GradientButton(if (ui.down != null && !ui.playlist) "Test again" else "Start test", onClick = vm::start)
                FocusCard(onClick = vm::playlist) { Text("Test my playlist", color = Vanta.Text, maxLines = 1, softWrap = false) }
            }
        }
        Text("The test uses Cloudflare's speed-test server. Test my playlist downloads from one of your own channels.", color = Vanta.TextDim, fontSize = 11.sp)
    }
}

internal fun mbps(v: Double) = if (v >= 100) "%.0f".format(v) else "%.1f".format(v)

@Composable
internal fun ResultTile(label: String, value: String?, unit: String, extra: String?, active: Boolean) {
    Column(
        Modifier.widthIn(min = 120.dp).background(if (active) Vanta.Cyan.copy(alpha = 0.12f) else Vanta.Panel, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = if (active) Vanta.Cyan else Vanta.TextDim, fontSize = 13.sp)
        Text(value ?: "–", color = Vanta.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(unit, color = Vanta.TextDim, fontSize = 12.sp)
        extra?.let { Text(it, color = Vanta.TextDim, fontSize = 11.sp) }
    }
}

/** The dial: 0 to 1000 Mbit/s on a log scale (so slow and fast lines both move it), or the ping while that's measured. */
@Composable
internal fun SpeedDial(ui: SpeedUi) {
    val pingPhase = ui.phase == SpeedPhase.PING
    val target = when {
        !ui.running -> 0f
        pingPhase -> (log10(1 + ui.live.coerceIn(0.0, 300.0)) / log10(301.0)).toFloat()
        else -> (log10(1 + ui.live.coerceIn(0.0, 1000.0)) / log10(1001.0)).toFloat()
    }
    val fraction by animateFloatAsState(target, tween(300), label = "dial")
    val measurer = rememberTextMeasurer()
    val dim = Vanta.TextDim
    val track = Vanta.Panel
    val number = when {
        !ui.running -> ""
        pingPhase -> "%.0f".format(ui.live)
        else -> mbps(ui.live)
    }
    val unit = if (pingPhase) tr("ms") else tr("Mbit/s")
    val phase = when (ui.phase) {
        SpeedPhase.PING -> tr("Ping")
        SpeedPhase.DOWN -> tr("Download")
        SpeedPhase.UP -> tr("Upload")
        null -> ""
    }
    Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(240.dp)) {
            val stroke = 18.dp.toPx()
            val inset = stroke / 2 + 22.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            drawArc(track, 135f, 270f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (fraction > 0.002f) drawArc(
                Brush.sweepGradient(listOf(Color(0xFF14E0E0), Color(0xFF6E54E6), Color(0xFFFF4FD8), Color(0xFF14E0E0)), center),
                135f, 270f * fraction, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
            )
            // Scale marks.
            val marks = if (pingPhase) listOf(0, 10, 50, 100, 300) else listOf(0, 5, 10, 50, 100, 250, 500, 1000)
            val top = if (pingPhase) 301.0 else 1001.0
            val radius = arcSize.width / 2 + stroke / 2 + 12.dp.toPx()
            marks.forEach { m ->
                val f = (log10(1.0 + m) / log10(top)).toFloat()
                val angle = Math.toRadians((135 + 270 * f).toDouble())
                val label = measurer.measure(m.toString(), TextStyle(color = dim, fontSize = 10.sp))
                val x = center.x + (radius * kotlin.math.cos(angle)).toFloat() - label.size.width / 2
                val y = center.y + (radius * kotlin.math.sin(angle)).toFloat() - label.size.height / 2
                drawText(label, topLeft = Offset(x, y))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (ui.running) {
                Text(number, color = Vanta.Text, fontSize = 40.sp, fontWeight = FontWeight.Black)
                Text(unit, color = Vanta.TextDim, fontSize = 13.sp)
                Text(phase, color = Vanta.Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            } else {
                Text(ui.down?.let(::mbps) ?: "–", color = Vanta.Text, fontSize = 40.sp, fontWeight = FontWeight.Black)
                Text(tr("Mbit/s"), color = Vanta.TextDim, fontSize = 13.sp)
                Text(tr("Download"), color = Vanta.TextDim, fontSize = 13.sp)
            }
        }
    }
}
