// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.player

/**
 * The other sources of merged channels (Settings › Channels › merge), set by Live TV and the TV Guide: a channel's
 * id → all its sources' ids, best first. The player tries the next one when a source fails.
 */
object ChannelSources {
    @Volatile private var map: Map<Long, List<Long>> = emptyMap()

    fun set(m: Map<Long, List<Long>>) { map = m }

    /** Every source of [channelId] (itself first when it's the best), or just itself. */
    fun of(channelId: Long): List<Long> = map[channelId] ?: map.values.firstOrNull { channelId in it } ?: listOf(channelId)
}
