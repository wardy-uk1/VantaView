// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app

import com.vantaview.app.data.BufferSize
import com.vantaview.app.data.ConnectionTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTuningTest {
    @Test fun fastSteadyLine() {
        val a = ConnectionTuning.advise(100.0, 5.0)
        assertEquals(BufferSize.SMALL, a.buffer); assertEquals(0, a.maxQuality); assertTrue(a.torrents); assertTrue(a.miniPlayer)
    }

    @Test fun fastButShaky() = assertEquals(BufferSize.MEDIUM, ConnectionTuning.advise(60.0, 80.0).buffer)

    @Test fun slowLine() {
        val a = ConnectionTuning.advise(4.0, 10.0)
        assertEquals(BufferSize.LARGE, a.buffer); assertEquals(480, a.maxQuality); assertFalse(a.torrents); assertFalse(a.miniPlayer)
    }

    @Test fun fittingStreamsFirst() {
        assertEquals(listOf(1080, 0, 2160), ConnectionTuning.preferFitting(listOf(2160, 1080, 0), 1080) { it })
        assertEquals(listOf(2160, 1080), ConnectionTuning.preferFitting(listOf(2160, 1080), 0) { it })
    }
}
