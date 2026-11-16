// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app

import com.vantaview.app.data.remote.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class M3uParserTest {
    private val sample = """
        #EXTM3U url-tvg="http://example.com/guide.xml"
        #EXTINF:-1 tvg-id="news24" tvg-logo="http://example.com/n.png" tvg-chno="7" group-title="News",News 24
        http://example.com/hls/news.m3u8|X-Token=demo&Referer=http%3A%2F%2Fsite.test%2F
        #EXTINF:-1 tvg-id="sport1" group-title="Sports;Extra",Sport One Ⓢ
        #EXTVLCOPT:http-user-agent=TestAgent/1.0
        #EXTVLCOPT:http-referrer=http://ref.test/
        http://example.com/live/sport.ts
        #EXTINF:-1 group-title="Films",A Film (2019)
        http://example.com/movie/user/pass/55.mkv
        #EXTINF:-1,No Group
        rtmp://example.com/live/stream
        #EXTINF:-1,Bad Address
        notaurl
    """.trimIndent()

    @Test
    fun parsesChannelsHeadersAndGuide() {
        val r = M3uParser.parse(sample.lineSequence())
        assertEquals("http://example.com/guide.xml", r.epgUrl)
        assertEquals(3, r.entries.size)
        assertEquals(1, r.skippedVod)

        val news = r.entries[0]
        assertEquals("News 24", news.name)
        assertEquals("http://example.com/hls/news.m3u8", news.url)
        assertEquals("News", news.group)
        assertEquals("news24", news.tvgId)
        assertEquals(7, news.number)
        assertEquals("demo", news.headers["X-Token"])
        assertEquals("http://site.test/", news.headers["Referer"])

        val sport = r.entries[1]
        assertEquals("Sport One", sport.name)
        assertEquals("Sports", sport.group)
        assertEquals("TestAgent/1.0", sport.headers["User-Agent"])
        assertEquals("http://ref.test/", sport.headers["Referer"])

        val last = r.entries[2]
        assertEquals("No Group", last.name)
        assertNull(last.group)
        assertEquals(emptyMap<String, String>(), last.headers)
    }

    @Test
    fun rejectsWebPages() {
        assertThrows(IllegalArgumentException::class.java) {
            M3uParser.parse(sequenceOf("<!DOCTYPE html>", "<html></html>"))
        }
    }

    @Test
    fun rejectsEmptyPlaylists() {
        assertThrows(IllegalArgumentException::class.java) { M3uParser.parse(sequenceOf("", "  ")) }
    }

    @Test
    fun convertsGithubBlobLinks() {
        assertEquals(
            "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8",
            M3uParser.rawGithub("https://github.com/Free-TV/IPTV/blob/master/playlist.m3u8"),
        )
        assertEquals("http://example.com/a.m3u", M3uParser.rawGithub(" http://example.com/a.m3u "))
    }

    @Test
    fun kodiDrmLines() {
        val r = M3uParser.parse(
            """
            #EXTM3U
            #KODIPROP:inputstream.adaptive.license_type=clearkey
            #KODIPROP:inputstream.adaptive.license_key=0123456789abcdef0123456789abcdef:fedcba9876543210fedcba9876543210
            #EXTINF:-1 group-title="Locked",Locked One
            #KODIPROP:inputstream.adaptive.manifest_type=mpd
            https://example.com/live/one.mpd
            #EXTINF:-1,Open Two
            https://example.com/live/two.m3u8
            """.trimIndent().lineSequence()
        )
        val one = r.entries.first { it.name == "Locked One" }
        assertEquals("clearkey", one.headers["#drm-type"])
        assertEquals("0123456789abcdef0123456789abcdef:fedcba9876543210fedcba9876543210", one.headers["#drm-key"])
        assertEquals("mpd", one.headers["#manifest"])
        assertNull(r.entries.first { it.name == "Open Two" }.headers["#drm-type"])
    }
}
