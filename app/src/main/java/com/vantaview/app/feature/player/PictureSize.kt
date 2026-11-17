// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.player

import android.view.Gravity
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.vantaview.app.data.Picture
import com.vantaview.app.data.PicturePos
import com.vantaview.app.data.StreamSize
import kotlin.math.roundToInt

/**
 * Sizes the video inside a [PlayerView]: a shape (Fit, Stretch, Fill screen, 16:9, 4:3, 21:9, Original),
 * then an extra zoom held at the middle, top or bottom. Only the video frame changes, so subtitles and
 * controls stay where they are. Zoom resizes the frame (rather than scaling it), which works with the
 * SurfaceView the player draws on.
 */
@OptIn(UnstableApi::class)
object PictureSize {
    private class Hook(val picture: () -> Picture, val listener: Player.Listener)
    private val hooks = java.util.WeakHashMap<PlayerView, Hook>()

    /**
     * Hooks [view] up so the picture is kept right when the video or the screen changes size. Call once,
     * after its player is set; call [detach] when the view goes.
     */
    fun attach(view: PlayerView, picture: () -> Picture) {
        val l = object : Player.Listener {
            // Added after PlayerView's own listener, so a forced shape wins over the video's.
            override fun onVideoSizeChanged(videoSize: VideoSize) { apply(view) }
        }
        hooks[view] = Hook(picture, l)
        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> apply(v as PlayerView) }
        view.player?.addListener(l)
    }

    fun detach(view: PlayerView) { hooks.remove(view)?.let { view.player?.removeListener(it.listener) } }

    fun apply(view: PlayerView) {
        val p = hooks[view]?.picture?.invoke() ?: run { android.util.Log.d("PictureSize", "no hook for $view (${hooks.size} hooks)"); return }
        val frame = view.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame) ?: run { android.util.Log.d("PictureSize", "no content frame"); return }
        android.util.Log.d("PictureSize", "apply $p view=${view.width}x${view.height} frame=${frame.width}x${frame.height} mode=${view.resizeMode}")
        if (view.resizeMode != p.size.mode) view.resizeMode = p.size.mode
        val vs = view.player?.videoSize
        val videoAspect = vs?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width * it.pixelWidthHeightRatio / it.height } ?: 0f
        (p.size.ratio ?: videoAspect.takeIf { it > 0f })?.let { frame.setAspectRatio(it) }

        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return
        var k = p.zoom / 100f
        // Original: the video's own pixel size, when it's smaller than the screen.
        if (p.size == StreamSize.ORIGINAL && vs != null && vs.width > 0 && vs.height > 0) {
            val fit = minOf(w / (vs.width * vs.pixelWidthHeightRatio), h / vs.height.toFloat())
            if (fit > 1f) k /= fit
        }
        val lp = if (k == 1f) {
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER)
        } else {
            val fw = (w * k).roundToInt()
            val fh = (h * k).roundToInt()
            FrameLayout.LayoutParams(fw, fh, Gravity.CENTER).apply {
                // Larger than the screen: hold its top or bottom edge there instead of the middle.
                val shift = ((fh - h) / 2).coerceAtLeast(0)
                when (p.pos) {
                    PicturePos.TOP -> topMargin = shift
                    PicturePos.BOTTOM -> bottomMargin = shift
                    PicturePos.MIDDLE -> Unit
                }
            }
        }
        val old = frame.layoutParams as? FrameLayout.LayoutParams
        if (old == null || old.width != lp.width || old.height != lp.height || old.gravity != lp.gravity ||
            old.topMargin != lp.topMargin || old.bottomMargin != lp.bottomMargin
        ) frame.layoutParams = lp
    }
}
