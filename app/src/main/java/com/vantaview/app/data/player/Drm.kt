// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.player

import android.util.Base64
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import org.json.JSONArray
import org.json.JSONObject

/**
 * Channels protected with ClearKey or Widevine, as M3U playlists describe them with Kodi's lines:
 * `#KODIPROP:inputstream.adaptive.license_type=clearkey` and `…license_key=…` (a key "kid:key", several
 * separated by commas, a ClearKey JSON, or a licence server address with "|Header=value" after it).
 * They're kept with the channel's headers under keys starting with "#", which are never sent as headers.
 */
object Drm {
    const val TYPE = "#drm-type"
    const val KEY = "#drm-key"
    const val MANIFEST = "#manifest"

    data class Info(val scheme: String, val license: String)

    /** Reads one #KODIPROP line into [into]; returns false when it isn't one VantaView uses. */
    fun readKodiProp(line: String, into: MutableMap<String, String>): Boolean {
        val prop = line.substringAfter(':').trim()
        val name = prop.substringBefore('=').trim().lowercase()
        val value = prop.substringAfter('=', "").trim()
        if (value.isEmpty()) return false
        when (name) {
            "inputstream.adaptive.license_type" -> into[TYPE] = when {
                "clearkey" in value.lowercase() -> "clearkey"
                "widevine" in value.lowercase() -> "widevine"
                else -> return false
            }
            "inputstream.adaptive.license_key" -> into[KEY] = value
            "inputstream.adaptive.manifest_type" -> into[MANIFEST] = value.lowercase()
            "inputstream.adaptive.stream_headers", "inputstream.adaptive.common_headers" ->
                value.split('&').forEach { kv ->
                    val k = kv.substringBefore('=').trim()
                    val v = runCatching { java.net.URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
                    if (k.isNotEmpty() && v.isNotEmpty()) into[if (k.equals("referer", true) || k.equals("referrer", true)) "Referer" else k] = v
                }
            else -> return false
        }
        return true
    }

    fun info(headers: Map<String, String>): Info? {
        val key = headers[KEY] ?: return null
        val scheme = headers[TYPE] ?: if (key.startsWith("http", true)) "widevine" else "clearkey"
        return Info(scheme, key)
    }

    /** The headers to send over HTTP: everything but the DRM notes. */
    fun httpHeaders(headers: Map<String, String>): Map<String, String> = headers.filterKeys { !it.startsWith("#") }

    fun isDash(url: String, headers: Map<String, String>): Boolean =
        headers[MANIFEST] == "mpd" || url.substringBefore('?').endsWith(".mpd", ignoreCase = true)

    /** Sets up [factory] and [item] for a protected channel; [http] makes the licence requests. */
    @OptIn(UnstableApi::class)
    fun apply(info: Info, item: MediaItem.Builder, factory: DefaultMediaSourceFactory, http: DataSource.Factory) {
        val uuid = if (info.scheme == "widevine") C.WIDEVINE_UUID else C.CLEARKEY_UUID
        val keys = if (info.scheme == "clearkey") clearKeyJson(info.license) else null
        if (keys != null) {
            // Keys in the playlist itself: answered on the device, no licence server.
            val manager = DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setMultiSession(true)
                .build(LocalMediaDrmCallback(keys.toByteArray()))
            factory.setDrmSessionManagerProvider { manager }
            item.setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID).build())
            return
        }
        // A licence server: "https://…|Header=value&Header2=value".
        val url = info.license.substringBefore('|')
        val headers = info.license.substringAfter('|', "").split('&').mapNotNull { kv ->
            val k = kv.substringBefore('=').trim()
            val v = runCatching { java.net.URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
            if (k.isNotEmpty() && v.isNotEmpty() && !k.startsWith("R{")) k to v else null
        }.toMap()
        factory.setDrmSessionManagerProvider(DefaultDrmSessionManagerProvider().apply { setDrmHttpDataSourceFactory(http) })
        item.setDrmConfiguration(MediaItem.DrmConfiguration.Builder(uuid).setLicenseUri(url).setLicenseRequestHeaders(headers).build())
    }

    /** ClearKey keys as the JSON the player expects, from "kid:key[,kid:key]" (hex or base64) or a JSON already. */
    fun clearKeyJson(license: String): String? {
        val t = license.trim()
        if (t.startsWith("http", true)) return null
        if (t.startsWith("{")) {
            val o = runCatching { JSONObject(t) }.getOrNull() ?: return null
            if (o.has("keys")) return o.toString()
            // {"kid":"key", …}
            val arr = JSONArray()
            o.keys().forEach { k -> arr.put(JSONObject().put("kty", "oct").put("kid", b64(k)).put("k", b64(o.optString(k)))) }
            return JSONObject().put("keys", arr).put("type", "temporary").toString()
        }
        val pairs = t.split(',').mapNotNull { p -> p.split(':').takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
        if (pairs.isEmpty()) return null
        val arr = JSONArray()
        pairs.forEach { (kid, key) -> arr.put(JSONObject().put("kty", "oct").put("kid", b64(kid)).put("k", b64(key))) }
        return JSONObject().put("keys", arr).put("type", "temporary").toString()
    }

    /** A key or key id as base64url without padding: hex is converted; base64 is tidied. */
    private fun b64(s: String): String {
        val hex = s.length % 2 == 0 && s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        val bytes = if (hex) s.chunked(2).map { it.toInt(16).toByte() }.toByteArray() else return s.replace('+', '-').replace('/', '_').trimEnd('=')
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
