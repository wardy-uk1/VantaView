// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app

import com.vantaview.app.data.vod.VodNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VodNamesTest {
    @Test fun cleansNames() {
        assertEquals("The Batman", VodNames.clean("EN - The Batman (2022) 4K"))
        assertEquals(2022, VodNames.year("EN - The Batman (2022) 4K"))
        assertEquals("Heat", VodNames.clean("|UK| Heat - 1995"))
        assertEquals(1995, VodNames.year("|UK| Heat - 1995"))
        assertEquals("batman", VodNames.norm("The Batman (2022)"))
        assertEquals("fast and furious", VodNames.norm("Fast & Furious"))
    }

    @Test fun findsEpisodes() {
        assertEquals(Triple("Doctor Who", 1, 2), VodNames.episode("|UK| Doctor Who S01 E02"))
        assertEquals(Triple("Friends", 3, 14), VodNames.episode("Friends 3x14"))
        assertNull(VodNames.episode("The Batman (2022)"))
    }

    @Test fun langTag() {
        assertEquals("DE", VodNames.langTag("DE - FILME 2025/2026"))
        assertEquals("EN", VodNames.langTag("|EN| Action"))
        assertEquals("DE", VodNames.langTag("DE - ²¹⁶⁰ᵖ ᴰⱽ ᴴᴰᴿ¹⁰/ᴾᴸᵁˢ"))
        assertNull(VodNames.langTag("VOD - Action"))
        assertNull(VodNames.langTag("Action"))
        assertNull(VodNames.langTag(null))
    }
}
