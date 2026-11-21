// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.catalog

/** Live TV's topic groups, in the order they're shown. */
enum class Topic(val label: String) {
    GENERAL("General"), NEWS("News"), SPORT("Sport"), FILMS("Films"), ENTERTAINMENT("Entertainment"),
    KIDS("Kids"), DOCS("Documentaries"), MUSIC("Music"), LIFESTYLE("Lifestyle"), RELIGION("Religion"), OTHER("Other");

    companion object {
        /** From iptv-org's categories (the first that means something here wins). */
        fun fromCategories(cats: List<String>): Topic {
            for (c in cats) when (c.lowercase()) {
                "news", "business", "weather", "legislative" -> return NEWS
                "sports", "outdoor", "auto" -> return SPORT
                "movies", "classic" -> return FILMS
                "kids", "animation", "family" -> return KIDS
                "documentary", "science", "education", "travel", "culture" -> return DOCS
                "music" -> return MUSIC
                "lifestyle", "cooking", "shop", "relax" -> return LIFESTYLE
                "religious" -> return RELIGION
                "entertainment", "series", "comedy" -> return ENTERTAINMENT
                "general", "public" -> return GENERAL
            }
            return OTHER
        }

        // Words in a channel's name, or its provider group, that give its topic away (several languages).
        private val rules = listOf(
            NEWS to Regex("""(?i)\b(news|nachrichten|noticias|nieuws|notizie|actualit|wiadomo|haber|journal|info\b|cnn|bbc news|sky news|euronews|al jazeera|bloomberg|cnbc|n-tv|welt\b|rtl nieuws|tvp info)"""),
            SPORT to Regex("""(?i)(sport|espn|dazn|eurosport|bein|arena sport|supersport|tnt sports|sky sports|premier league|football|f[uú]tbol|calcio|nba|nfl|nhl|ufc|wwe|golf|tennis|racing|motor|liga|ligue|bundesliga|serie a\b|ppv)"""),
            KIDS to Regex("""(?i)\b(kids?|kinder|ni[ñn]os|enfants|bambini|dzieci|[çc]ocuk|junior|cartoon|nick|disney|baby|boomerang|cbbc|cbeebies|toon|pbs kids|kika|super rtl)"""),
            FILMS to Regex("""(?i)(movie|film|cinema|cin[eé]|kino|peliculas|pel[ií]culas|hbo|cinemax|sky cinema|showtime|starz|tcm|amc\b|paramount|filmbox|vod)"""),
            DOCS to Regex("""(?i)(docu|doku|discovery|national geographic|nat ?geo|history|animal planet|planet|science|wild|viasat (explore|history|nature)|arte\b)"""),
            MUSIC to Regex("""(?i)(music|musik|m[uú]sica|musique|mtv|vh1|radio|hits|kiss|capital|clubland|trace|deluxe music)"""),
            RELIGION to Regex("""(?i)(church|gospel|god\b|christ|islam|quran|coran|bible|faith|catholic|ewtn|daystar|tbn\b)"""),
            LIFESTYLE to Regex("""(?i)(food|cook|travel|home|garden|fashion|lifestyle|hgtv|tlc\b|e!|bravo|shop|qvc)"""),
            ENTERTAINMENT to Regex("""(?i)(entertainment|series|comedy|drama|reality|show|fox\b|syfy|universal|comedy central|e4|dave|gold\b)"""),
        )

        /** A guess for a channel the catalog doesn't know: its name first, then its provider group's name. */
        fun guess(name: String, group: String?): Topic {
            rules.firstOrNull { it.second.containsMatchIn(name) }?.let { return it.first }
            if (group != null) rules.firstOrNull { it.second.containsMatchIn(group) }?.let { return it.first }
            return GENERAL
        }
    }
}

/** "+1", "+2", "Plus 1" or "-1h" versions of a channel (the same programmes an hour or two later). */
object TimeShift {
    private val mark = Regex("""(?i)(\s*\+\s*\d{1,2}\s*(?:h|hr|hours?)?\b|\s+plus\s*\d\b|\s*-\s*\d\s*h\b|\s*\(\s*\+\s*\d\s*\)|\s+timeshift\b)""")
    fun isShifted(name: String): Boolean = mark.containsMatchIn(name)
    fun strip(name: String): String = mark.replace(name, " ").trim()
}
