// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.ui

import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat

/**
 * True full screen for the players: the picture also covers the camera notch or hole (the "cutout") on phones
 * that have one, when Fill or Zoom is chosen. The controls keep clear of it themselves.
 */
object FullScreen {
    fun cover(window: Window?, on: Boolean) {
        window ?: return
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = when {
                    !on -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                    Build.VERSION.SDK_INT >= 30 -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    else -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, !on)
    }

    /** Players using full screen now; it's only undone when the last one goes. */
    private var holders = 0

    /**
     * A player starts: bars hidden, notch covered. Opening one player from another (a reminder, the guide)
     * disposes the old one after the new one starts, so each [enter] is matched by a [leave] and full
     * screen is only undone when no player is left.
     */
    fun enter(window: Window?) {
        window ?: return
        holders++
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        cover(window, true)
    }

    fun leave(window: Window?) {
        window ?: return
        holders = (holders - 1).coerceAtLeast(0)
        if (holders > 0) return
        WindowCompat.getInsetsController(window, window.decorView).show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        cover(window, false)
    }
}
