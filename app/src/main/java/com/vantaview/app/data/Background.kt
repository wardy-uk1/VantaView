// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * For the heavy work done behind the scenes (loading a playlist's channels and films, the TV guide, the channel
 * catalog): threads Android runs only when nothing more urgent needs the processor, so a phone playing a channel
 * while a big playlist or guide loads keeps the picture smooth.
 */
object Background {
    val dispatcher: CoroutineDispatcher = Executors.newFixedThreadPool(3) { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "vantaview-background").apply { isDaemon = true }
    }.asCoroutineDispatcher()
}
