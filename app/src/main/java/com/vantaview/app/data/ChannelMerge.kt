// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data

import com.vantaview.app.data.db.ChannelRow

/**
 * Merges several sources of the same channel into one line (Settings › Channels). Two channels are the same
 * when they share a TV guide ID, or have the same name once quality tags ("HD", "FHD", "4K", "(backup)"…) and
 * country prefixes ("UK:", "|DE|") are left out. The line shows the best source; the rest are kept, best first,
 * for the player to fall back on.
 */
object ChannelMerge {
    enum class Mode(val label: String) {
        OFF("Off"), PLAYLIST("Within each playlist"), ACROSS("Across playlists"), BOTH("Within and across playlists")
    }

    private val prefix = Regex("""^\s*(?:[|\[(]\s*[A-Za-z]{2,4}\s*[|\])]|[A-Za-z]{2,3}\s*[:|]|[A-Za-z]{2,3}\s+-\s+)\s*""")
    private val tags = Regex(
        """(?i)\b(uhd|fhd|hd|sd|4k|8k|hevc|h\.?26[45]|x26[45]|1080[pi]?|720p?|2160p?|576[pi]?|480p?|50\s*fps|60\s*fps|fps|backup|back\s*up|raw|vip|hdr|dolby|multi|plus\+?|ʜᴅ|ꜰʜᴅ)\b"""
    )
    private val bits = Regex("""[(\[{][^)\]}]*[)\]}]""")
    private val nonWord = Regex("""[^\p{L}\p{N}]+""")

    /** The name with quality tags, bracketed notes and country prefixes left out ("UK: BBC One FHD" → "bbcone"). */
    fun nameKey(name: String): String {
        var n = com.vantaview.app.ui.PlainText.of(ChannelNames.display(name))
        n = prefix.replace(n, "")
        n = bits.replace(n, " ")
        n = tags.replace(n, " ")
        return nonWord.replace(n.lowercase(), "")
    }

    /** How good a source looks from its name: 4K over FHD over HD over SD; unknown counts as HD. */
    fun quality(name: String): Int {
        val n = com.vantaview.app.ui.PlainText.of(name).lowercase()
        return when {
            Regex("""\b(4k|uhd|2160p?)\b""").containsMatchIn(n) -> 4
            Regex("""\b(fhd|1080[pi]?)\b""").containsMatchIn(n) -> 3
            Regex("""\b(hd|720p?)\b""").containsMatchIn(n) -> 2
            Regex("""\b(sd|576[pi]?|480p?)\b""").containsMatchIn(n) -> 1
            Regex("""\b(backup|back up)\b""").containsMatchIn(n) -> 0
            else -> 2
        }
    }

    data class Result(val rows: List<ChannelRow>, val sources: Map<Long, List<Long>>)

    /**
     * Merges [rows] (in the order shown). [acrossPlaylists]/[withinPlaylist] say which pairs may merge. Each line keeps
     * its first place in the list, shows the best source, and is a favourite when any of its sources is.
     */
    fun merge(rows: List<ChannelRow>, withinPlaylist: Boolean, acrossPlaylists: Boolean, providerOrder: List<Long>): Result {
        if (rows.size < 2 || (!withinPlaylist && !acrossPlaylists)) return Result(rows, emptyMap())
        val parent = IntArray(rows.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        fun join(byKey: Map<String, List<Int>>) {
            byKey.values.filter { it.size > 1 }.forEach { members ->
                val picked = when {
                    withinPlaylist && acrossPlaylists -> listOf(members)
                    // Only within a playlist: each playlist's own duplicates.
                    withinPlaylist -> members.groupBy { rows[it].channel.providerId }.values.toList()
                    // Only across playlists: one source from each playlist (the first in the list).
                    else -> listOf(members.distinctBy { rows[it].channel.providerId })
                }
                picked.filter { it.size > 1 }.forEach { g -> g.drop(1).forEach { m -> parent[find(m)] = find(g.first()) } }
            }
        }
        // Radio stations and event-only channels are left as they are.
        val eligible = rows.indices.filter { !rows[it].channel.isRadio && !ChannelNames.isEventOnly(rows[it].channel.name) && !ChannelNames.isDivider(rows[it].channel.name) }
        join(eligible.filter { !rows[it].channel.epgChannelId.isNullOrBlank() }.groupBy { "id:" + rows[it].channel.epgChannelId!!.trim().lowercase() })
        join(eligible.map { it to nameKey(rows[it].channel.name) }.filter { it.second.length >= 2 }.groupBy({ it.second }, { it.first }))

        val groups = rows.indices.groupBy { find(it) }
        if (groups.size == rows.size) return Result(rows, emptyMap())
        val order = providerOrder.withIndex().associate { (i, id) -> id to i }
        val out = ArrayList<ChannelRow>(groups.size)
        val sources = HashMap<Long, List<Long>>()
        // Lines in the place of their first source.
        groups.values.sortedBy { it.min() }.forEach { members ->
            val best = members.sortedWith(
                compareByDescending<Int> { quality(rows[it].channel.name) }
                    .thenBy { order[rows[it].channel.providerId] ?: Int.MAX_VALUE }
                    .thenBy { it }
            )
            val shown = rows[best.first()]
            out += shown.copy(isFav = members.any { rows[it].isFav })
            if (best.size > 1) sources[shown.channel.id] = best.map { rows[it].channel.id }
        }
        return Result(out, sources)
    }
}
