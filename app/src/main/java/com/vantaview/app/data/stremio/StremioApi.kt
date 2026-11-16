// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.stremio

import com.vantaview.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

class StremioException(message: String) : Exception(message)

/** A sign-in code from Stremio's link service: the person opens [link] (or scans it) and confirms. */
data class StremioLinkCode(val code: String, val link: String)

/**
 * Stremio's account service (api.strem.io), the same one the Stremio apps use: signing in, the account's
 * add-on collection, and its library (Library, Continue watching and watched films live in one list there).
 * Library items are kept as Stremio sent them and only the fields VantaView changes are touched, so nothing
 * else in someone's Stremio library is lost.
 */
class StremioApi(private val http: OkHttpClient) {
    private val ua = "VantaView/${BuildConfig.VERSION_NAME}"

    private suspend fun post(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val r = Request.Builder().url("https://api.strem.io/api/$path").header("User-Agent", ua)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val o = try {
            http.newCall(r).execute().use { JSONObject(it.body?.string().orEmpty().ifBlank { "{}" }) }
        } catch (e: Exception) {
            throw StremioException("Couldn't reach Stremio (${e.message ?: "network error"})")
        }
        o.optJSONObject("error")?.let { throw StremioException(it.optString("message").ifBlank { "Stremio said no" }) }
        o.optJSONObject("result") ?: JSONObject().put("value", o.opt("result"))
    }

    private suspend fun get(url: String): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(url).header("User-Agent", ua).build()).execute().use {
                JSONObject(it.body?.string().orEmpty().ifBlank { "{}" })
            }
        }.getOrNull()
    }

    // ---- Signing in ----

    /** Email and password. Returns the session key (the password isn't kept). */
    suspend fun login(email: String, password: String): String {
        val r = post("login", JSONObject().put("type", "Login").put("email", email.trim()).put("password", password).put("facebook", false))
        return r.optString("authKey").ifBlank { throw StremioException("Stremio didn't accept that email or password") }
    }

    /** Starts a QR sign-in. */
    suspend fun linkCreate(): StremioLinkCode {
        val r = get("https://link.stremio.com/api/v2/create?type=Create")?.optJSONObject("result")
            ?: throw StremioException("Couldn't reach Stremio to start signing in")
        val code = r.optString("code").ifBlank { throw StremioException("Stremio didn't give a sign-in code") }
        return StremioLinkCode(code, r.optString("link").ifBlank { "https://link.stremio.com" })
    }

    /** The session key once the person has confirmed the code; null while still waiting. */
    suspend fun linkRead(code: String): String? =
        get("https://link.stremio.com/api/v2/read?type=Read&code=" + java.net.URLEncoder.encode(code, "UTF-8"))
            ?.optJSONObject("result")?.optString("authKey")?.ifBlank { null }

    /** The account's email, to show who's signed in. */
    suspend fun email(key: String): String = runCatching {
        post("getUser", JSONObject().put("type", "GetUser").put("authKey", key)).optString("email")
    }.getOrDefault("")

    suspend fun logout(key: String) { runCatching { post("logout", JSONObject().put("type", "Logout").put("authKey", key)) } }

    // ---- Add-ons ----

    /** The account's add-ons, in Stremio's order, as Stremio keeps them (transportUrl, manifest, flags). */
    suspend fun addons(key: String): List<JSONObject> {
        val r = post("addonCollectionGet", JSONObject().put("type", "AddonCollectionGet").put("authKey", key).put("update", true))
        val a = r.optJSONArray("addons") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    suspend fun setAddons(key: String, addons: List<JSONObject>) {
        post("addonCollectionSet", JSONObject().put("type", "AddonCollectionSet").put("authKey", key).put("addons", JSONArray(addons)))
    }

    // ---- Library ----

    suspend fun library(key: String): List<JSONObject> {
        val r = post("datastoreGet", JSONObject().put("authKey", key).put("collection", "libraryItem").put("ids", JSONArray()).put("all", true))
        val a = r.optJSONArray("value") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    /** One library item by id, or null when the account doesn't have it. */
    suspend fun libraryItem(key: String, type: String, id: String): JSONObject? {
        val r = post("datastoreGet", JSONObject().put("authKey", key).put("collection", "libraryItem").put("ids", JSONArray().put(id)).put("all", false))
        val a = r.optJSONArray("value") ?: return null
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { it.optString("type") == type }
    }

    suspend fun putLibrary(key: String, items: List<JSONObject>) {
        if (items.isEmpty()) return
        items.chunked(200).forEach { c ->
            post("datastorePut", JSONObject().put("authKey", key).put("collection", "libraryItem").put("changes", JSONArray(c)))
        }
    }

    companion object {
        /** A text field, treating JSON null as missing. */
        fun str(o: JSONObject, k: String): String? = o.optString(k).takeIf { !o.isNull(k) && it.isNotBlank() && it != "null" }

        fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()
        fun ms(iso: String?): Long = iso?.takeIf { it.isNotBlank() && it != "null" }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

        /** A new library item, shaped as the Stremio apps make them. */
        fun newItem(id: String, type: String, name: String, poster: String?, now: Long): JSONObject = JSONObject()
            .put("_id", id).put("name", name).put("type", type).put("poster", poster.orEmpty()).put("posterShape", "poster")
            .put("removed", true).put("temp", true).put("_ctime", iso(now)).put("_mtime", iso(now))
            .put("state", JSONObject()
                .put("lastWatched", iso(now)).put("timeWatched", 0).put("timeOffset", 0).put("overallTimeWatched", 0)
                .put("timesWatched", 0).put("flaggedWatched", 0).put("duration", 0).put("video_id", JSONObject.NULL)
                .put("watched", JSONObject.NULL).put("noNotif", false))
            .put("behaviorHints", JSONObject().put("defaultVideoId", JSONObject.NULL).put("featuredVideoId", JSONObject.NULL).put("hasScheduledVideos", false))
    }
}
