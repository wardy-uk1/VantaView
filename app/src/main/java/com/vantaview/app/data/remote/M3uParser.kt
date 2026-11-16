// SPDX-License-Identifier: GPL-3.0-or-later
// Portions adapted from Stream Goat (c) Syed Ali Hamza, GPL-3.0
// https://github.com/sherazi5110/stream-goat  (LiveTv.kt: parseM3u, rawGithub)
package com.vantaview.app.data.remote

data class M3uEntry(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String?,
    val tvgId: String?,
    val number: Int?,
    val headers: Map<String, String>,
    /** Catch-up rule for [com.vantaview.app.data.repo.CatchUp] ("mode|template"), or null. */
    val catchup: String? = null,
    val catchupDays: Int = 0,
    val radio: Boolean = false,
)

data class M3uResult(
    val entries: List<M3uEntry>,
    val epgUrl: String?,
    val skippedVod: Int,
    /** Film and series entries, for the Movies and TV Series pages. */
    val vod: List<M3uEntry> = emptyList(),
)

object M3uParser {
    private val attr = Regex("([\\w-]+)=\"([^\"]*)\"")
    private val circledLetters = Regex("[\\u24B6-\\u24E9]")
    private val spaces = Regex("\\s+")
    private val vodExt = Regex("\\.(mp4|mkv|avi|mov|wmv|flv)(\\?.*)?$", RegexOption.IGNORE_CASE)

    /** GitHub "blob" page links are turned into the raw file link. */
    /** Video-site page links (not streams). The Free TV list includes some; they can't play here. */
    private val webPageHosts = Regex("""^https?://([a-z0-9-]+\.)*(youtube\.com|youtu\.be|twitch\.tv|dailymotion\.com|dai\.ly)(/|$)""", RegexOption.IGNORE_CASE)

    fun rawGithub(url: String): String {
        val m = Regex("^https?://github\\.com/([^/]+)/([^/]+)/blob/(.+)$").find(url.trim()) ?: return url.trim()
        return "https://raw.githubusercontent.com/${m.groupValues[1]}/${m.groupValues[2]}/${m.groupValues[3]}"
    }

    fun looksLikeVod(url: String): Boolean {
        val u = url.substringBefore('|')
        return vodExt.containsMatchIn(u) || u.contains("/movie/") || u.contains("/series/")
    }

    /** Turns a playlist's catch-up attributes into a rule for CatchUp, or null when there's none. */
    fun catchupRule(type: String?, source: String?, rec: String?): String? {
        val t = type?.trim()?.lowercase().orEmpty()
        return when {
            t == "xc" -> "xc|"
            t == "shift" || t == "timeshift" -> "shift"
            t.startsWith("flussonic") || t == "fs" -> "flussonic"
            t == "append" && !source.isNullOrBlank() -> "append|$source"
            (t == "default" || t.isEmpty()) && !source.isNullOrBlank() -> "default|$source"
            t == "default" -> "shift"
            !rec.isNullOrBlank() && (rec.toIntOrNull() ?: 0) > 0 -> "shift"
            else -> null
        }
    }

    /**
     * Reads a playlist line by line: #EXTINF attributes, #EXTGRP, #EXTVLCOPT headers and the
     * "url|Header=value&Header2=value" form. Movie and series entries are kept apart, for the Movies and TV Series pages.
     */
    fun parse(lines: Sequence<String>): M3uResult {
        val out = ArrayList<M3uEntry>()
        val vod = ArrayList<M3uEntry>()
        var info: String? = null
        var extGroup: String? = null
        var epgUrl: String? = null
        var skipped = 0
        var first = true
        val options = mutableMapOf<String, String>()
        // #KODIPROP lines (DRM, DASH, headers) may come before or after #EXTINF; they belong to the next address.
        val kodi = mutableMapOf<String, String>()
        // Catch-up given once for the whole list on the #EXTM3U line.
        var listCatchup: String? = null
        var listSource: String? = null
        var listDays: Int? = null

        for (raw in lines) {
            val line = raw.trim().removePrefix("\uFEFF")
            if (first && line.isNotEmpty()) {
                first = false
                if (line.startsWith("<")) throw IllegalArgumentException("This link is a web page, not a playlist")
                if (line.startsWith("#EXTM3U", true)) {
                    val attrs = attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    // Some lists (Free TV) give many guide links separated by commas; keep them all.
                    epgUrl = (attrs["url-tvg"] ?: attrs["x-tvg-url"])?.split(',')
                        ?.map { it.trim() }?.filter { it.startsWith("http", true) }
                        ?.joinToString(",")?.ifBlank { null }
                    listCatchup = attrs["catchup"] ?: attrs["catchup-type"]
                    listSource = attrs["catchup-source"]
                    listDays = attrs["catchup-days"]?.toIntOrNull()
                    continue
                }
            }
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", true) -> { info = line; options.clear(); extGroup = null }
                line.startsWith("#EXTGRP:", true) -> extGroup = line.substringAfter(':').trim()
                line.startsWith("#EXTVLCOPT:", true) -> {
                    val opt = line.substringAfter(':')
                    when {
                        opt.startsWith("http-referrer=", true) || opt.startsWith("http-referer=", true) ->
                            options["Referer"] = opt.substringAfter('=')
                        opt.startsWith("http-user-agent=", true) -> options["User-Agent"] = opt.substringAfter('=')
                        opt.startsWith("http-origin=", true) -> options["Origin"] = opt.substringAfter('=')
                    }
                }
                line.startsWith("#KODIPROP:", true) -> com.vantaview.app.data.player.Drm.readKodiProp(line, kodi)
                line.startsWith("#") -> Unit
                else -> {
                    val inf = info ?: continue
                    info = null
                    val attrs = attr.findAll(inf).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    val rawName = inf.substringAfterLast(",", "").trim()
                        .ifBlank { attrs["tvg-name"] ?: "Channel ${out.size + 1}" }
                    // Free-TV marks channels with circled letters; they are not part of the name.
                    val name = rawName.replace(circledLetters, "").replace(spaces, " ").trim().ifBlank { rawName }
                    val headers = options.toMutableMap()
                    headers.putAll(kodi); kodi.clear()
                    var url = line
                    if ('|' in line) {
                        url = line.substringBefore('|')
                        line.substringAfter('|').split('&').forEach { kv ->
                            val k = kv.substringBefore('=').trim()
                            val v = runCatching { java.net.URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8") }
                                .getOrDefault("")
                            if (k.isNotEmpty() && v.isNotEmpty()) {
                                headers[if (k.equals("referrer", true) || k.equals("referer", true)) "Referer" else k] = v
                            }
                        }
                    }
                    val ok = url.startsWith("http", true) || url.startsWith("rtmp", true) ||
                        url.startsWith("rtsp", true) || url.startsWith("udp", true)
                    if (!ok) continue
                    if (webPageHosts.containsMatchIn(url)) continue
                    if (looksLikeVod(url)) {
                        skipped++
                        vod += M3uEntry(
                            name = name, url = url, logo = attrs["tvg-logo"]?.ifBlank { null },
                            group = (attrs["group-title"] ?: extGroup)?.substringBefore(';')?.trim()?.ifBlank { null },
                            tvgId = null, number = null, headers = headers,
                        )
                        continue
                    }
                    out += M3uEntry(
                        name = name,
                        url = url,
                        logo = attrs["tvg-logo"]?.ifBlank { null },
                        group = (attrs["group-title"] ?: extGroup)?.substringBefore(';')?.trim()?.ifBlank { null },
                        tvgId = attrs["tvg-id"]?.ifBlank { null },
                        number = attrs["tvg-chno"]?.toIntOrNull(),
                        headers = headers,
                        catchup = catchupRule(
                            attrs["catchup"] ?: attrs["catchup-type"] ?: listCatchup,
                            attrs["catchup-source"] ?: listSource,
                            attrs["tvg-rec"],
                        ),
                        radio = RadioDetect.isRadio(name, (attrs["group-title"] ?: extGroup), attrs["radio"].equals("true", true)),
                        catchupDays = (attrs["catchup-days"]?.toIntOrNull() ?: attrs["tvg-rec"]?.toIntOrNull() ?: listDays ?: 0),
                    ).let { e -> if (e.catchup != null && e.catchupDays <= 0) e.copy(catchupDays = 7) else e }
                }
            }
        }
        if (first) throw IllegalArgumentException("The playlist is empty")
        return M3uResult(out, epgUrl, skipped, vod)
    }
}
