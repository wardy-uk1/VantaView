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
}
