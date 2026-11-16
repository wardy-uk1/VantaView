// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.vantaview.app.data.Look
import kotlin.math.PI
import kotlin.math.sin

/** The background a look draws behind every page. VANTA has none (pages are solid). */
@Composable
fun LookBackground(look: Look, modifier: Modifier = Modifier.fillMaxSize()) {
    when (look) {
        Look.VANTA -> Unit
        Look.GLASS -> Canvas(modifier) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF081650), Color(0xFF16399E), Color(0xFF2F63D8))))
            // Soft light from the top right, like a lit pane of glass.
            drawRect(Brush.radialGradient(listOf(Color(0x405FA8FF), Color.Transparent), center = Offset(size.width * 0.85f, 0f), radius = size.maxDimension * 0.7f))
            drawRect(Brush.radialGradient(listOf(Color(0x30000020), Color.Transparent), center = Offset(0f, size.height), radius = size.maxDimension * 0.6f))
        }
        Look.BROADCAST -> Canvas(modifier) {
            drawRect(Brush.linearGradient(listOf(Color(0xFF1A4FC4), Color(0xFF2F6CE2), Color(0xFF4C88F2)), start = Offset.Zero, end = Offset(size.width, size.height)))
        }
        Look.CLASSIC -> Canvas(modifier) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF0B1450), Color(0xFF13207A), Color(0xFF1C2E9A))))
        }
        Look.BLADES -> Canvas(modifier) {
            drawRect(Brush.linearGradient(listOf(Color(0xFFDDE1D9), Color(0xFFB4BBB0), Color(0xFF7F887A)), start = Offset.Zero, end = Offset(size.width * 0.6f, size.height)))
        }
        Look.STACK -> Canvas(modifier) {
            drawRect(Color(0xFF0A1220))
            drawRect(Brush.radialGradient(listOf(Color(0xFF3A5A7A), Color(0xFF16263A), Color(0xFF0A1220)), center = Offset(size.width * 0.7f, size.height * 0.3f), radius = size.maxDimension * 0.75f))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0x2E78BE3C)), startY = size.height * 0.75f, endY = size.height))
        }
        Look.TILES -> Canvas(modifier) { drawRect(Color(0xFFECECEC)) }
        Look.FLUENT -> Canvas(modifier) {
            drawRect(Brush.linearGradient(listOf(Color(0xFF1B2A24), Color(0xFF2D4436), Color(0xFF121614)), start = Offset.Zero, end = Offset(size.width, size.height * 0.6f)))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEB0A0C0B)), startY = size.height * 0.3f, endY = size.height * 0.8f))
        }
        Look.SPOTLIGHT -> Canvas(modifier) {
            drawRect(Brush.linearGradient(listOf(Color(0xFF05070F), Color(0xFF101A3A), Color(0xFF2A3F7A)), start = Offset.Zero, end = Offset(size.width, size.height * 0.4f)))
            drawRect(Brush.horizontalGradient(listOf(Color(0xEB03050C), Color(0x8C03050C), Color.Transparent)))
        }
        Look.CROSS -> {
            val t = drift("cross")
            Canvas(modifier) {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF0A2F63), Color(0xFF14569E), Color(0xFF2A7FC8))))
                wave(t, 0.62f, 0.07f, 1.1f, Color(0x29A0D7FF))
                wave(t + 0.5f, 0.70f, 0.05f, 0.9f, Color(0x1FA0D7FF))
                waveLine(t + 0.25f, 0.56f, 0.06f, 1.3f, Color(0x59DCF0FF))
            }
        }
        Look.WAVE -> {
            // One slow loop a minute: cheap enough for a Fire TV Stick.
            val t = drift("wave")
            Canvas(modifier) {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF041E40), Color(0xFF0A3B73), Color(0xFF0F4F8E))))
                wave(t, 0.58f, 0.08f, 1.0f, Color(0x2E9FD4FF))
                wave(t + 0.33f, 0.66f, 0.06f, 1.4f, Color(0x269FD4FF))
                wave(t + 0.66f, 0.74f, 0.05f, 0.8f, Color(0x1FCFEAFF))
                // A few still specks of light.
                val dots = listOf(0.12f to 0.2f, 0.3f to 0.65f, 0.48f to 0.12f, 0.62f to 0.85f, 0.77f to 0.35f, 0.9f to 0.7f, 0.2f to 0.9f)
                dots.forEach { (x, y) -> drawCircle(Color(0x66FFFFFF), radius = 2.5f, center = Offset(size.width * x, size.height * y)) }
            }
        }
    }
}

/** A slow loop from 0 to 1 a minute; held still in low-memory mode (no redrawing behind every page). */
@Composable
private fun drift(label: String): Float {
    if (com.vantaview.app.data.LowMemory.active) return 0.2f
    val t by rememberInfiniteTransition(label = label).animateFloat(
        0f, 1f, infiniteRepeatable(tween(60_000, easing = LinearEasing), RepeatMode.Restart), label = "t",
    )
    return t
}

/** A filled band whose top edge is a sine wave, drifting sideways with [t]. */
private fun DrawScope.wave(t: Float, at: Float, height: Float, cycles: Float, color: Color) {
    val w = size.width
    val h = size.height
    val path = Path()
    val steps = 48
    for (i in 0..steps) {
        val x = w * i / steps
        val y = h * at + h * height * sin((i.toFloat() / steps * cycles + t) * 2 * PI).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.lineTo(w, h); path.lineTo(0f, h); path.close()
    drawPath(path, color)
}

/** A thin sine line (the bright edge on the Cross look). */
private fun DrawScope.waveLine(t: Float, at: Float, height: Float, cycles: Float, color: Color) {
    val w = size.width
    val h = size.height
    val path = Path()
    val steps = 48
    for (i in 0..steps) {
        val x = w * i / steps
        val y = h * at + h * height * sin((i.toFloat() / steps * cycles + t) * 2 * PI).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
}
