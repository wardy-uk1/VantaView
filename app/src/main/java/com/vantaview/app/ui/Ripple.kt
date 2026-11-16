// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** False when the user turned animations off in Android's settings. */
fun animationsOn(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 26) ValueAnimator.areAnimatorsEnabled()
    else runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) != 0f

/**
 * The light ripple: glowing rings of light cut by a diagonal grid, tinted from the logo's cyan to
 * magenta. Drawn with OpenGL ES 2, so it runs on every supported device, Fire TV included.
 * [center] is in pixels within this view (null = the middle). With animations off it holds still.
 */
@Composable
fun RippleBackground(
    modifier: Modifier = Modifier,
    brightness: Float = 1f,
    speed: Float = 1f,
    center: Offset? = null,
) {
    if (com.vantaview.app.data.LowMemory.active) {
        // Low-memory mode: a still glow instead of an OpenGL surface (which needs its own memory).
        androidx.compose.foundation.Canvas(modifier) {
            val c = center ?: androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
            drawRect(
                androidx.compose.ui.graphics.Brush.radialGradient(
                    listOf(androidx.compose.ui.graphics.Color(0x5500E5FF), androidx.compose.ui.graphics.Color(0x33C2369F), androidx.compose.ui.graphics.Color.Transparent),
                    center = c, radius = size.maxDimension * 0.6f * brightness.coerceIn(0.5f, 1.5f),
                ),
            )
        }
        return
    }
    val context = LocalContext.current
    val moving = remember { animationsOn(context) }
    val renderer = remember { RippleRenderer() }
    SideEffect {
        renderer.brightness = brightness
        renderer.speed = if (moving) speed else 0f
        renderer.cx = center?.x ?: Float.NaN
        renderer.cy = center?.y ?: Float.NaN
    }
    val view = remember { arrayOfNulls<GLSurfaceView>(1) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { view[0]?.onPause() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { view[0]?.onResume() }
    AndroidView(
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                setEGLContextClientVersion(2)
                // Above other video surfaces (like Home's mini player) that may sit underneath.
                setZOrderMediaOverlay(true)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                view[0] = this
            }
        },
        update = { view[0] = it },
        onRelease = { it.onPause() },
        modifier = modifier,
    )
}

private class RippleRenderer : GLSurfaceView.Renderer {
    @Volatile var brightness = 1f
    @Volatile var speed = 1f
    @Volatile var cx = Float.NaN
    @Volatile var cy = Float.NaN

    private var program = 0
    private var uRes = 0; private var uTime = 0; private var uBright = 0; private var uCenter = 0; private var aPos = 0
    private var w = 1f; private var h = 1f
    private var t = 1f
    private var last = 0L

    private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = GLES20.glCreateProgram().also { p ->
            GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, VERTEX))
            GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
            GLES20.glLinkProgram(p)
        }
        aPos = GLES20.glGetAttribLocation(program, "p")
        uRes = GLES20.glGetUniformLocation(program, "resolution")
        uTime = GLES20.glGetUniformLocation(program, "time")
        uBright = GLES20.glGetUniformLocation(program, "uBrightness")
        uCenter = GLES20.glGetUniformLocation(program, "uCenter")
        last = SystemClock.elapsedRealtime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        w = width.toFloat(); h = height.toFloat()
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = SystemClock.elapsedRealtime()
        // Same pace as the mockup: time grows by 0.05 a frame at 60 frames a second.
        // The pattern repeats every 20 time units, so keep t small: TV chips (Fire TV) draw in lower
        // precision and a growing number turns the rings into broken dots.
        t = (t + (now - last).coerceAtMost(100) / 1000f * 3f * speed) % 20f
        last = now
        val m = minOf(w, h)
        val x = if (cx.isNaN()) 0f else (cx * 2f - w) / m
        val y = if (cy.isNaN()) 0f else -(cy * 2f - h) / m
        GLES20.glUseProgram(program)
        GLES20.glUniform2f(uRes, w, h)
        GLES20.glUniform1f(uTime, t)
        GLES20.glUniform1f(uBright, brightness)
        GLES20.glUniform2f(uCenter, x, y)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun compile(type: Int, src: String): Int = GLES20.glCreateShader(type).also {
        GLES20.glShaderSource(it, src)
        GLES20.glCompileShader(it)
    }

    private companion object {
        const val VERTEX = "attribute vec2 p;void main(){gl_Position=vec4(p,0.0,1.0);}"
        const val FRAGMENT = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform vec2 resolution;uniform float time;uniform float uBrightness;uniform vec2 uCenter;
void main(){
  vec2 uv=(gl_FragCoord.xy*2.0-resolution.xy)/min(resolution.x,resolution.y)-uCenter;
  float t=time*0.05;vec3 color=vec3(0.0);
  for(int j=0;j<3;j++){for(int i=0;i<5;i++){
    color[j]+=0.002*float(i*i)/abs(fract(t-0.01*float(j)+float(i)*0.01)*5.0-length(uv)+mod(uv.x+uv.y,0.2));
  }}
  float mono=(color.r+color.g+color.b)/3.0;
  vec3 tint=mix(vec3(0.08,0.88,0.88),vec3(1.0,0.31,0.85),smoothstep(-1.2,1.2,uv.x-uv.y*0.35));
  gl_FragColor=vec4(mono*tint*uBrightness,1.0);
}
"""
    }
}
