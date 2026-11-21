// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.catalog

import com.vantaview.app.data.PlaybackSettings
import com.vantaview.app.data.country.CountryDetect
import com.vantaview.app.data.db.ChannelEntity
import com.vantaview.app.data.db.ChannelRow

/**
 * Live TV's catalog-based choices (Settings › Channels): topic groups, "Importance" order with your countries
 * first, and regional and +1 versions on one line.
 */
object ChannelOrder {
    private const val GROUPING = "live_grouping"
    private const val COUNTRIES = "country_order"
    private const val VERSIONS = "collapse_versions"

    fun byTopic(p: PlaybackSettings) = p.getString(GROUPING) == "topic"
    fun setByTopic(p: PlaybackSettings, on: Boolean) = p.setString(GROUPING, if (on) "topic" else "provider")

    /** Regional and +1 versions of a channel on one line (off by default). */
    fun collapseVersions(p: PlaybackSettings) = p.getString(VERSIONS) == "1"
    fun setCollapseVersions(p: PlaybackSettings, on: Boolean) = p.setString(VERSIONS, if (on) "1" else "0")

    /** Countries whose channels come first, in order; this device's country when none are chosen. */
    fun countries(p: PlaybackSettings): List<String> =
        p.getString(COUNTRIES)?.split(',')?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(CountryDetect.deviceCountry())
    fun setCountries(p: PlaybackSettings, codes: List<String>) = p.setString(COUNTRIES, codes.distinct().joinToString(","))

    /** Whether any of these needs the catalog (so it's downloaded only for people who use them). */
    fun needsCatalog(p: PlaybackSettings, importance: Boolean) = importance || byTopic(p) || collapseVersions(p)

    fun countryOf(c: ChannelEntity): String? =
        c.country?.takeIf { it != CountryDetect.OTHER } ?: ChannelCatalog.match(c.name, c.epgChannelId)?.country

    /** A channel's topic: the catalog's, or a guess from its name and its provider group. */
    fun topicOf(c: ChannelEntity, group: String?): Topic =
        ChannelCatalog.match(c.name, c.epgChannelId)?.topic?.takeIf { it != Topic.OTHER } ?: Topic.guess(c.name, group)

    /**
     * "Importance": channels from your countries first (in your order), the best-known ones first within each,
     * time-shifted (+1) versions after the rest; otherwise the list keeps its order (the provider's numbers).
     */
    fun importance(rows: List<ChannelRow>, countries: List<String>): List<ChannelRow> {
        val place = countries.withIndex().associate { (i, c) -> c to i }
        return rows.withIndex().sortedWith(
            compareBy<IndexedValue<ChannelRow>> { place[countryOf(it.value.channel)] ?: countries.size }
                .thenBy { if (TimeShift.isShifted(it.value.channel.name)) 1 else 0 }
                .thenBy { MajorChannels.rank(countryOf(it.value.channel), com.vantaview.app.data.ChannelNames.display(it.value.channel.name)) ?: 10_000 }
                .thenBy { it.index }
        ).map { it.value }
    }

    /** The key two versions of one channel share when versions are collapsed (catalog ID, else the name without "+1"). */
    fun versionKey(c: ChannelEntity): String? =
        ChannelCatalog.match(c.name, c.epgChannelId)?.id?.let { "cat:" + it.lowercase() }
            ?: com.vantaview.app.data.ChannelMerge.nameKey(TimeShift.strip(c.name)).takeIf { it.length >= 2 }?.let { "ts:$it" }
}
