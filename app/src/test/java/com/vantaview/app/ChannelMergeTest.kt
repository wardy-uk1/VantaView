// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app

import com.vantaview.app.data.ChannelMerge
import com.vantaview.app.data.db.ChannelEntity
import com.vantaview.app.data.db.ChannelRow
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelMergeTest {
    private var next = 1L
    private fun row(name: String, provider: Long = 1, epg: String? = null) =
        ChannelRow(ChannelEntity(id = next++, providerId = provider, remoteId = name + next, name = name, sortKey = name, number = next.toInt(), streamUrl = "x", epgChannelId = epg), isFav = false)

    @Test fun namesWithoutTags() {
        assertEquals("bbcone", ChannelMerge.nameKey("UK: BBC One FHD"))
        assertEquals("bbcone", ChannelMerge.nameKey("|UK| BBC One HD (backup)"))
        assertEquals("bbcone", ChannelMerge.nameKey("BBC One"))
    }

    @Test fun mergesBestFirst() {
        val rows = listOf(row("BBC One SD"), row("ITV"), row("UK: BBC One FHD"), row("BBC One HD"))
        val r = ChannelMerge.merge(rows, withinPlaylist = true, acrossPlaylists = true, providerOrder = listOf(1))
        assertEquals(listOf("UK: BBC One FHD", "ITV"), r.rows.map { it.channel.name })
        assertEquals(3, r.sources.values.single().size)
    }

    @Test fun sameGuideId() {
        val rows = listOf(row("Sky Sports Main Event", epg = "SkySpMainEv.uk"), row("SS Main Event", epg = "skyspmainev.uk"))
        assertEquals(1, ChannelMerge.merge(rows, true, true, listOf(1)).rows.size)
    }

    @Test fun acrossOnly() {
        val rows = listOf(row("BBC One", 1), row("BBC One HD", 1), row("BBC One", 2))
        val r = ChannelMerge.merge(rows, withinPlaylist = false, acrossPlaylists = true, providerOrder = listOf(1, 2))
        assertEquals(2, r.rows.size)
    }

    @Test fun offLeavesAlone() {
        val rows = listOf(row("BBC One"), row("BBC One HD"))
        assertEquals(2, ChannelMerge.merge(rows, false, false, listOf(1)).rows.size)
    }
}
