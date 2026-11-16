// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data

import android.app.ActivityManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Low-memory mode (Settings › Appearance › Memory), for TVs and sticks with about 1 GB of memory.
 * When it's on, VantaView draws lighter effects (no blur, fades or moving backgrounds), loads artwork
 * smaller and keeps fewer pictures in memory, and the player keeps a smaller buffer.
 *
 * Auto turns it on by itself on small devices; On and Off override that. [active] is Compose state, so
 * screens redraw when it changes.
 */
object LowMemory {
    enum class Mode(val label: String) { AUTO("Auto"), ON("On"), OFF("Off") }

    private const val PREFS = "vantaview"
    private const val KEY = "low_memory_mode"

    /** This device has about 1 GB of memory (or says it's a low-memory device). */
    var device: Boolean = false
        private set

    /** Total memory, in MB, for Settings. */
    var totalMb: Long = 0
        private set

    var mode: Mode by mutableStateOf(Mode.AUTO)
        private set

    /** Low-memory mode is on now. */
    val active: Boolean get() = when (mode) { Mode.ON -> true; Mode.OFF -> false; Mode.AUTO -> device }

    private var appContext: Context? = null
    private val listeners = mutableListOf<() -> Unit>()

    fun init(context: Context) {
        appContext = context.applicationContext
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        totalMb = info.totalMem / 1_048_576
        // A "1 GB" device reports about 0.8 to 1 GB; 1.5 GB boxes are tight too.
        device = am.isLowRamDevice || info.totalMem in 1..1_600_000_000L || am.memoryClass <= 128
        mode = runCatching {
            Mode.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)!!)
        }.getOrDefault(Mode.AUTO)
    }

    fun set(m: Mode) {
        val was = active
        mode = m
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString(KEY, m.name)?.apply()
        if (was != active) listeners.forEach { runCatching { it() } }
    }

    /** Called when [active] changes (the picture loader is rebuilt for the new sizes). */
    fun onChange(l: () -> Unit) { listeners += l }
}
