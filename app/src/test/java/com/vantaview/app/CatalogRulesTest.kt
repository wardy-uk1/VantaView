// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app

import com.vantaview.app.data.catalog.MajorChannels
import com.vantaview.app.data.catalog.TimeShift
import com.vantaview.app.data.catalog.Topic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRulesTest {
    @Test fun timeShift() {
        assertTrue(TimeShift.isShifted("ITV1 +1"))
        assertTrue(TimeShift.isShifted("Dave+1 HD"))
        assertTrue(TimeShift.isShifted("Channel 4 +1"))
        assertFalse(TimeShift.isShifted("Sky Sports 1"))
        assertFalse(TimeShift.isShifted("Canal+ Sport"))
        assertEquals("ITV1", TimeShift.strip("ITV1 +1"))
    }

    @Test fun topicGuess() {
        assertEquals(Topic.SPORT, Topic.guess("Sky Sports Main Event HD", null))
        assertEquals(Topic.NEWS, Topic.guess("BBC News", null))
        assertEquals(Topic.KIDS, Topic.guess("Cartoon Network", null))
        assertEquals(Topic.FILMS, Topic.guess("Sky Cinema Premiere", null))
        assertEquals(Topic.GENERAL, Topic.guess("BBC One", null))
        assertEquals(Topic.SPORT, Topic.guess("Channel 7", "UK | SPORTS"))
    }

    @Test fun categories() {
        assertEquals(Topic.NEWS, Topic.fromCategories(listOf("news")))
        assertEquals(Topic.DOCS, Topic.fromCategories(listOf("documentary", "general")))
        assertEquals(Topic.OTHER, Topic.fromCategories(emptyList()))
    }

    @Test fun majorRanks() {
        assertEquals(0, MajorChannels.rank("UK", "BBC One HD"))
        assertEquals(2, MajorChannels.rank("UK", "UK: ITV1 FHD"))
        assertEquals(2, MajorChannels.rank("UK", "ITV1 +1"))
        assertNull(MajorChannels.rank("UK", "Some Small Channel"))
        assertEquals(0, MajorChannels.rank("DE", "Das Erste HD"))
    }
}
