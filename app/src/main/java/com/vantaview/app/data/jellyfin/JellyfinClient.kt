// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.jellyfin

import android.content.Context
import com.vantaview.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/** A signed-in Jellyfin user: the session token Jellyfin gave, and who it belongs to. */
data class JellyfinLogin(val token: String, val userId: String, val userName: String)

/** A Quick Connect code to approve in Jellyfin (Settings › Quick Connect) on another device. */
data class QuickCode(val code: String, val secret: String)

/**
 * Talks to a Jellyfin server (an open-source media server people run at home) with its own web API:
 * signing in (password or Quick Connect), its films and series, Live TV channels and guide, and play
 * addresses. Works the same with Emby-compatible servers that share the API.
 */
@Singleton
class JellyfinClient @Inject constructor(
    private val http: OkHttpClient,
    @ApplicationContext context: Context,
) {
    private val sp = context.getSharedPreferences("jellyfin", Context.MODE_PRIVATE)

    /** This install's device id, which Jellyfin shows in its device list. */
    private val deviceId: String by lazy {
        sp.getString("device", null) ?: java.util.UUID.randomUUID().toString().also { sp.edit().putString("device", it).apply() }
    }

    private fun auth(token: String?): String =
        "MediaBrowser Client=\"VantaView\", Device=\"${android.os.Build.MODEL.replace("\"", "")}\", DeviceId=\"$deviceId\", " +
            "Version=\"${BuildConfig.VERSION_NAME}\"" + (token?.let { ", Token=\"$it\"" } ?: "")

    private fun request(server: String, path: String, token: String?): Request.Builder =
        Request.Builder().url(server.trimEnd('/') + path)
            .header("Authorization", auth(token))
            .header("X-Emby-Authorization", auth(token))
            .header("Accept", "application/json")

    private suspend fun call(b: Request.Builder): String = withContext(Dispatchers.IO) {
        http.newCall(b.build()).execute().use { r ->
            when (r.code) {
                401, 403 -> throw IllegalStateException("The Jellyfin server didn't accept that sign-in")
                in 200..299 -> r.body?.string().orEmpty()
                else -> throw IllegalStateException("The Jellyfin server answered with error ${r.code}")
            }
        }
    }

    private suspend fun get(server: String, path: String, token: String?): String = call(request(server, path, token).get())
    private suspend fun post(server: String, path: String, token: String?, body: JSONObject): String =
        call(request(server, path, token).post(body.toString().toRequestBody("application/json".toMediaType())))

    /** The address as typed, made into a base address ("https://host:8096"). */
    fun normalize(input: String): String {
        var u = input.trim().substringBefore('#').trimEnd('/')
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "https://$u"
        // People often paste the web app's address.
        return u.removeSuffix("/web/index.html").removeSuffix("/web").trimEnd('/')
    }

    /** The server's own name, which also checks it's a Jellyfin server. */
    suspend fun serverName(server: String): String {
        val o = runCatching { JSONObject(get(server, "/System/Info/Public", null)) }.getOrNull()
            ?: throw IllegalStateException("That address isn't a Jellyfin server (or it isn't answering)")
        return o.optString("ServerName").ifBlank { "Jellyfin" }
    }

    private fun login(o: JSONObject): JellyfinLogin {
        val user = o.optJSONObject("User")
        return JellyfinLogin(
            token = o.optString("AccessToken").ifBlank { throw IllegalStateException("The Jellyfin server didn't accept that sign-in") },
            userId = user?.optString("Id").orEmpty(), userName = user?.optString("Name").orEmpty(),
        )
    }

    suspend fun signIn(server: String, user: String, password: String): JellyfinLogin =
        login(JSONObject(post(server, "/Users/AuthenticateByName", null, JSONObject().put("Username", user.trim()).put("Pw", password))))

    suspend fun quickConnectOn(server: String): Boolean = runCatching { get(server, "/QuickConnect/Enabled", null).trim() == "true" }.getOrDefault(false)

    suspend fun quickStart(server: String): QuickCode {
        val o = JSONObject(call(request(server, "/QuickConnect/Initiate", null).post(ByteArray(0).toRequestBody(null))))
        return QuickCode(o.optString("Code"), o.optString("Secret").ifBlank { throw IllegalStateException("Quick Connect didn't start") })
    }

    /** Signed in once the code is approved; null while still waiting. */
    suspend fun quickCheck(server: String, q: QuickCode): JellyfinLogin? {
        val o = runCatching { JSONObject(get(server, "/QuickConnect/Connect?secret=" + enc(q.secret), null)) }.getOrNull() ?: return null
        if (!o.optBoolean("Authenticated")) return null
        return login(JSONObject(post(server, "/Users/AuthenticateWithQuickConnect", null, JSONObject().put("Secret", q.secret))))
    }

    /** The user a saved token belongs to. */
    suspend fun me(server: String, token: String): String =
        JSONObject(get(server, "/Users/Me", token)).optString("Id").ifBlank { throw IllegalStateException("Sign in to the Jellyfin server again") }

    // ---- Films, series and episodes ----

    /** Every item of [types] ("Movie", "Series", "Episode"), page by page, handed to [each]. */
    suspend fun items(server: String, token: String, userId: String, types: String, each: (JSONObject) -> Unit) {
        var start = 0
        while (true) {
            val path = "/Users/$userId/Items?IncludeItemTypes=$types&Recursive=true&StartIndex=$start&Limit=500" +
                "&Fields=Overview,Genres,ProviderIds,ProductionYear,DateCreated,ParentId,SeriesId,ParentIndexNumber,IndexNumber,Path" +
                "&EnableImageTypes=Primary&SortBy=SortName&SortOrder=Ascending"
            val o = JSONObject(get(server, path, token))
            val arr = o.optJSONArray("Items") ?: JSONArray()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(each)
            start += arr.length()
            if (arr.length() == 0 || start >= o.optInt("TotalRecordCount", start)) break
        }
    }

    fun poster(server: String, item: JSONObject): String? {
        val tag = item.optJSONObject("ImageTags")?.optString("Primary")?.ifBlank { null } ?: return null
        return "${server.trimEnd('/')}/Items/${item.optString("Id")}/Images/Primary?maxHeight=480&quality=90&tag=$tag"
    }

    /** A film's or episode's play address (the file as it is). */
    fun playAddress(server: String, token: String, itemId: String): String =
        "${server.trimEnd('/')}/Videos/$itemId/stream?static=true&MediaSourceId=$itemId&api_key=${enc(token)}"

    // ---- Live TV ----

    suspend fun channels(server: String, token: String, userId: String): List<JSONObject> = runCatching {
        val o = JSONObject(get(server, "/LiveTv/Channels?UserId=$userId&EnableImageTypes=Primary&SortBy=SortName&Limit=5000", token))
        val arr = o.optJSONArray("Items") ?: JSONArray()
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }.getOrDefault(emptyList())

    /** Programmes for [channelIds] between [from] and [to], handed over in batches. */
    suspend fun programmes(server: String, token: String, userId: String, channelIds: List<String>, from: Long, to: Long, each: suspend (List<JSONObject>) -> Unit) {
        val iso = { ms: Long -> java.time.Instant.ofEpochMilli(ms).toString() }
        for (chunk in channelIds.chunked(40)) {
            val path = "/LiveTv/Programs?UserId=$userId&ChannelIds=${chunk.joinToString(",")}&MinEndDate=${enc(iso(from))}" +
                "&MaxStartDate=${enc(iso(to))}&Fields=Overview,Genres&EnableImages=false&Limit=20000"
            val o = runCatching { JSONObject(get(server, path, token)) }.getOrNull() ?: continue
            val arr = o.optJSONArray("Items") ?: continue
            each((0 until arr.length()).mapNotNull { arr.optJSONObject(it) })
        }
    }

    /**
     * A live channel's play address. Jellyfin opens the tuner when asked for playback info; the stream
     * is then played as it is, or converted to HLS when the server says it has to be.
     */
    suspend fun liveAddress(server: String, token: String, userId: String, channelId: String): String {
        val profile = JSONObject()
            .put("MaxStreamingBitrate", 120_000_000)
            .put("DirectPlayProfiles", JSONArray().put(JSONObject().put("Type", "Video")).put(JSONObject().put("Type", "Audio")))
            .put("TranscodingProfiles", JSONArray().put(
                JSONObject().put("Container", "ts").put("Type", "Video").put("VideoCodec", "h264").put("AudioCodec", "aac")
                    .put("Protocol", "hls").put("Context", "Streaming"),
            ))
        val body = JSONObject().put("UserId", userId).put("AutoOpenLiveStream", true).put("IsPlayback", true).put("DeviceProfile", profile)
        val o = JSONObject(post(server, "/Items/$channelId/PlaybackInfo?UserId=$userId", token, body))
        val src = o.optJSONArray("MediaSources")?.optJSONObject(0) ?: throw IllegalStateException("The Jellyfin server couldn't open this channel")
        val base = server.trimEnd('/')
        src.optString("TranscodingUrl").ifBlank { null }?.let { t ->
            if (!src.optBoolean("SupportsDirectStream")) return base + t + (if ("api_key=" in t) "" else "&api_key=${enc(token)}")
        }
        val live = src.optString("LiveStreamId").ifBlank { null }
        return "$base/Videos/$channelId/stream?static=true&MediaSourceId=${enc(src.optString("Id"))}" +
            (live?.let { "&LiveStreamId=${enc(it)}" } ?: "") + "&api_key=${enc(token)}" +
            (src.optString("Container").ifBlank { null }?.let { "&Container=$it" } ?: "")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        fun ticksToMs(t: Long) = t / 10_000
        fun ms(iso: String?): Long = iso?.takeIf { it.isNotBlank() }?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
    }
}
