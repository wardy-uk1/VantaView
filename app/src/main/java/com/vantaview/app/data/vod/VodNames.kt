// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.vod

/**
 * Playlist film and series names are messy ("EN - The Batman (2022) 4K", "|UK| Doctor Who S01 E02").
 * These turn them into a clean name to show, a year, an episode number, and a matching key.
 */
object VodNames {
    private val tagRe = Regex("""^\W*([A-Z]{2,3})\W*(?:[-:|\]]|\s)""")
    private val notLanguage = setOf("VOD", "4K", "HD", "UHD", "FHD", "SD", "TOP", "NEW", "ALL", "THE", "VIP", "PPV", "TV", "DV", "HDR", "BOX")

    /**
     * The country or language a provider put at the front of a group or title ("DE - FILME 2025/2026" is DE,
     * "|EN| Action" is EN), for the small tag on playlist films and series. Null when there isn't one.
     */
    fun langTag(text: String?): String? {
        val t = com.vantaview.app.ui.PlainText.of(text ?: return null)
        val code = tagRe.find(t)?.groupValues?.get(1)?.uppercase() ?: return null
        return code.takeIf { it !in notLanguage }
    }

    private val prefix = Regex("""^\s*(?:[|\[(]\s*[A-Za-z]{2,4}\s*[|\])]|[A-Z]{2,4}\s*[-:|]|(?:4K|UHD|FHD|HD|VOD|MULTI)\s*[-:|])\s*""")
    private val yearRe = Regex("""[(\[]\s*((?:19\d{2}|20[0-3]\d))\s*[)\]]|[-–]\s*((?:19\d{2}|20[0-3]\d))\s*$|\s((?:19\d{2}|20[0-3]\d))\s*$""")
    private val quality = Regex("""(?i)[\s\-|\[(]*\b(2160p|1080p|720p|480p|4k|uhd|fhd|hdr|hevc|x26[45]|h\.?26[45]|multi(?:-sub)?|vostfr|dual|sub|dubbed)\b[)\]]*""")
    private val episodeRe = Regex("""(?i)\bS(\d{1,2})\s*[-.]?\s*E(\d{1,3})\b|\b(\d{1,2})x(\d{1,3})\b""")
    private val nonWord = Regex("""[^\p{L}\p{N}]+""")

    fun year(name: String): Int? = yearRe.find(name)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()

    /** The name to show: no language prefix, year, or quality tags. */
    fun clean(name: String): String {
        var n = name.trim()
        repeat(2) { n = prefix.replace(n, "") }
        n = yearRe.replace(n, " ")
        n = quality.replace(n, " ")
        return n.replace(Regex("""\s+"""), " ").trim(' ', '-', '|', ':', '.').ifBlank { name.trim() }
    }

    /** Lower case, no punctuation or "the", "&" as "and": what two sources' names are compared by. */
    fun norm(name: String): String =
        nonWord.replace(clean(name).lowercase().replace("&", " and "), " ").trim()
            .removePrefix("the ").replace(Regex("""\s+"""), " ")

    /** For an episode entry: the show's name, season and episode. */
    fun episode(name: String): Triple<String, Int, Int>? {
        val m = episodeRe.find(name) ?: return null
        val s = (m.groupValues[1].ifEmpty { m.groupValues[3] }).toIntOrNull() ?: return null
        val e = (m.groupValues[2].ifEmpty { m.groupValues[4] }).toIntOrNull() ?: return null
        val show = clean(name.substring(0, m.range.first))
        if (show.isBlank()) return null
        return Triple(show, s, e)
    }

    /** A key safe to use inside an id (no colons or spaces). */
    fun key(s: String): String = norm(s).replace(' ', '-').ifBlank { Integer.toHexString(s.hashCode()) }
}
