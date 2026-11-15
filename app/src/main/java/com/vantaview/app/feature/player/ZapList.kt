// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.player

/**
 * The list a channel was opened from (Favourites, a category, search results, the Home favourites row), so
 * channel up and down, and swiping, move through that list. Set just before opening the player; the player
 * takes it once. Without one (the TV Guide, a reminder), it moves through the channel's own category.
 */
object ZapList {
    @Volatile private var ids: List<Long> = emptyList()

    fun set(ids: List<Long>) { this.ids = ids.distinct() }

    /** The list, if [channelId] is in it and there's more than one; it's then forgotten. */
    fun take(channelId: Long): List<Long>? {
        val list = ids
        ids = emptyList()
        return list.takeIf { it.size > 1 && channelId in it }
    }
}
