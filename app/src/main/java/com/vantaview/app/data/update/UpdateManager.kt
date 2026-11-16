// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.vantaview.app.BuildConfig
import com.vantaview.app.di.AppScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** A newer VantaView found on GitHub. */
data class UpdateInfo(
    val name: String,
    val notes: String,
    val apkUrl: String,
    val size: Long,
    val test: Boolean,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val at: Long) : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateState
    /** Android needs "Install unknown apps" allowed for VantaView first. */
    data class NeedsPermission(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Settings › Updates: checks GitHub releases for a newer VantaView (and test builds when switched
 * on), downloads the APK and hands it to Android's installer. Nothing is installed without the user
 * confirming in Android's own screen.
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    http: OkHttpClient,
    @AppScope private val scope: CoroutineScope,
) {
    private val http = http.newBuilder().followRedirects(true).build()
    private val sp = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    /** Set when the daily check finds an update the user hasn't put off: shows the notice on launch. */
    private val _notice = MutableStateFlow<UpdateInfo?>(null)
    val notice: StateFlow<UpdateInfo?> = _notice

    var testBuilds: Boolean
        get() = sp.getBoolean("test_builds", BuildConfig.VERSION_NAME.contains("-test"))
        set(v) { sp.edit().putBoolean("test_builds", v).apply() }

    val lastCheck: Long get() = sp.getLong("last_check", 0)

    // ---- "What's new" after an update ----

    /** Settings › Updates: show what's new the first time the app opens after an update. */
    var showWhatsNew: Boolean
        get() = sp.getBoolean("show_whats_new", true)
        set(v) { sp.edit().putBoolean("show_whats_new", v).apply() }

    /**
     * The bullets to show once after an update, or null. A fresh install shows nothing (it just
     * remembers this version).
     */
    fun whatsNewToShow(): List<String>? {
        val seen = sp.getInt("whats_new_seen", -1)
        val now = BuildConfig.VERSION_CODE
        if (seen == now) return null
        // A fresh install (never updated) has nothing new to show.
        val fresh = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.firstInstallTime == info.lastUpdateTime
        }.getOrDefault(false)
        if (seen == -1 && fresh) { markWhatsNewSeen(); return null }
        if (!showWhatsNew || seen > now) { markWhatsNewSeen(); return null }
        return bundledWhatsNew().ifEmpty { markWhatsNewSeen(); null }
    }

    fun markWhatsNewSeen() { sp.edit().putInt("whats_new_seen", BuildConfig.VERSION_CODE).apply() }

    /**
     * This version's "What's new" bullets, from the copy of docs/WHATS-NEW.md inside the app, in the app's
     * language when its translation (docs/whats-new/<language>.md) has the same number of bullets.
     */
    fun bundledWhatsNew(): List<String> {
        fun read(name: String) = runCatching { context.assets.open(name).bufferedReader().readLines() }.getOrDefault(emptyList())
            .map { it.trim() }.filter { it.startsWith("- ") || it.startsWith("* ") }
            .map { plainNotes(it).removePrefix("• ").trim() }
        val english = read("whats_new.md")
        val lang = com.vantaview.app.ui.I18n.lang
        if (lang == "en") return english
        return read("whats_new_$lang.md").takeIf { it.size == english.size } ?: english
    }

    /** Checks every time the app starts (at most every 10 minutes, so quick restarts don't ask GitHub again). */
    fun checkOnStart() {
        if (System.currentTimeMillis() - lastCheck < 10 * 60_000L) return
        scope.launch {
            val found = check()
            if (found != null && sp.getString("later", null) != found.apkUrl + found.name) _notice.value = found
        }
    }

    fun checkNow() { scope.launch { check() } }

    /** "Later": don't show this update's notice again; Settings › Updates still offers it. */
    fun later(info: UpdateInfo) {
        sp.edit().putString("later", info.apkUrl + info.name).apply()
        _notice.value = null
    }

    fun dismissNotice() { _notice.value = null }

    private suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Checking
        try {
            val candidates = buildList {
                fetch("releases/latest", test = false)?.let(::add)
                if (testBuilds) fetch("releases/tags/test-latest", test = true)?.let(::add)
            }
            sp.edit().putLong("last_check", System.currentTimeMillis()).apply()
            val best = candidates.filter { it.newer }.maxByOrNull { it.order }
            _state.value = if (best == null) UpdateState.UpToDate(System.currentTimeMillis()) else UpdateState.Available(best.info)
            best?.info
        } catch (e: Exception) {
            _state.value = UpdateState.Failed("Couldn't reach GitHub to check for updates. Check the internet connection.")
            null
        }
    }

    private class Found(val info: UpdateInfo, val newer: Boolean, val order: Long)

    private fun fetch(path: String, test: Boolean): Found? {
        val json = get("https://api.github.com/repos/$REPO/$path") ?: return null
        val o = JSONObject(json)
        val assets = o.optJSONArray("assets") ?: return null
        var apk: JSONObject? = null
        var versionTxt: String? = null
        var translated: String? = null
        val lang = com.vantaview.app.ui.I18n.lang
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val n = a.optString("name")
            if (n.endsWith(".apk", true)) apk = a
            if (n == "version.txt") versionTxt = a.optString("browser_download_url")
            if (lang != "en" && n == "whats-new-$lang.md") translated = a.optString("browser_download_url")
        }
        apk ?: return null
        val code = versionTxt?.let { get(it)?.trim()?.toLongOrNull() }
        val published = runCatching { Instant.parse(o.optString("published_at")).toEpochMilli() }.getOrDefault(0L)
        // Newer when its version code is higher; without one, when it was published well after this APK was built.
        val newer = if (code != null) code > BuildConfig.VERSION_CODE else published > BuildConfig.BUILD_TIME + 30 * 60_000L
        val body = o.optString("body")
        val name = if (test) {
            Regex("""Test build (\d+)""").find(body)?.let { "Test build ${it.groupValues[1]}" } ?: "Test build"
        } else o.optString("name").ifBlank { o.optString("tag_name") }
        return Found(
            UpdateInfo(
                // The release's notes in the app's language when the build attached them, otherwise in English.
                name = name, notes = plainNotes(translated?.let { get(it) }?.trim()?.ifBlank { null } ?: whatsNew(body)),
                apkUrl = apk.optString("browser_download_url"),
                size = apk.optLong("size"), test = test,
            ),
            newer, code ?: published,
        )
    }

    private fun get(url: String): String? =
        http.newCall(
            Request.Builder().url(url).header("Accept", "application/vnd.github+json")
                .header("User-Agent", "VantaView/${BuildConfig.VERSION_NAME}").build()
        ).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }

    /** Downloads the update, then opens Android's installer. */
    fun install(info: UpdateInfo) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(info)
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "VantaView-update.apk")
                _state.value = UpdateState.Downloading(info, 0)
                http.newCall(Request.Builder().url(info.apkUrl).header("User-Agent", "VantaView/${BuildConfig.VERSION_NAME}").build())
                    .execute().use { r ->
                        if (!r.isSuccessful) error("download failed")
                        val body = r.body ?: error("empty")
                        val total = body.contentLength().takeIf { it > 0 } ?: info.size
                        body.byteStream().use { input ->
                            file.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                var done = 0L
                                var last = -1
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                                    if (pct != last) { last = pct; _state.value = UpdateState.Downloading(info, pct) }
                                }
                            }
                        }
                    }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                // UpdatedReceiver opens VantaView again once the installer has replaced it.
                sp.edit().putLong("installing_at", System.currentTimeMillis()).commit()
                withContext(Dispatchers.Main) { context.startActivity(intent) }
                _state.value = UpdateState.Available(info)
            } catch (e: Exception) {
                _state.value = UpdateState.Failed("The update couldn't be downloaded. Try again.")
            }
        }
    }

    /** Fire TV keeps this setting somewhere else, so the steps are shown instead. */
    val isFireTv: Boolean get() = context.packageManager.hasSystemFeature("amazon.hardware.fire_tv")

    /** Opens Android's "Install unknown apps" page for VantaView; false when the device has no such page. */
    fun openInstallPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        return runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
    }

    companion object {
        const val REPO = "wardy-uk1/VantaView"

        /** The part of the notes for people: everything before a "---" line (build details come after). */
        fun whatsNew(body: String): String =
            body.lines().takeWhile { it.trim() != "---" }.joinToString("\n").trim()

        /** Release notes as plain text: no Markdown marks, links shown as their words. */
        fun plainNotes(md: String): String = md.lines().joinToString("\n") { l ->
            l.replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
                .replace(Regex("""^#{1,6}\s*"""), "")
                .replace(Regex("""^\s*[-*]\s+"""), "• ")
                .replace("**", "").replace("`", "")
        }.trim()
    }
}
