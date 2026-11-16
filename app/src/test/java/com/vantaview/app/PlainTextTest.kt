// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app

import com.vantaview.app.ui.PlainText
import org.junit.Assert.assertEquals
import org.junit.Test

class PlainTextTest {
    @Test fun tinyLettersBecomeOrdinary() {
        assertEquals("DE - 2160p DV HDR10/PLUS", PlainText.of("DE - ²¹⁶⁰ᵖ ᴰⱽ ᴴᴰᴿ¹⁰/ᴾᴸᵁˢ"))
        assertEquals("DE - FILME 4K 3840P", PlainText.of("DE - FILME ᴬᴷ ³⁸⁴⁰ᴾ"))
        assertEquals("NETFLIX", PlainText.of("ɴᴇᴛꜰʟɪх".replace('х', 'X')))
    }

    @Test fun ordinaryTextUnchanged() {
        assertEquals("Über 10° · Café", PlainText.of("Über 10° · Café"))
    }
}
