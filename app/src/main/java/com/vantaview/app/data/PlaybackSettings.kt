// SPDX-License-Identifier: GPL-3.0-or-later
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.vantaview.app.data

import android.content.Context
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.ui.AspectRatioFrameLayout
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** How much video is loaded ahead. Bigger copes better with poor connections but takes longer to start. */
enum class BufferSize(val label: String, val detail: String, val minMs: Int, val maxMs: Int, val startMs: Int, val rebufferMs: Int) {
    SMALL("Small", "Starts fastest. Best on a fast, steady connection.", 5_000, 15_000, 1_500, 3_000),
    MEDIUM("Medium", "A balance of quick starts and smooth playing.", 15_000, 30_000, 2_500, 5_000),
    LARGE("Large", "Slowest to start, but rides out a poor connection.", 30_000, 60_000, 2_500, 5_000),
}

/**
 * How video fills the screen. [ratio] forces a shape (the picture is stretched to it, then fitted);
 * ORIGINAL shows it at its own pixel size when that's smaller than the screen.
 * (FILL and ZOOM keep their old stored names: FILL stretches, ZOOM fills the screen by cropping.)
 */
enum class StreamSize(val label: String, val mode: Int, val ratio: Float? = null) {
    FIT("Fit", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    FILL("Stretch", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    ZOOM("Fill screen", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    WIDE("16:9", AspectRatioFrameLayout.RESIZE_MODE_FIT, 16f / 9f),
    STANDARD("4:3", AspectRatioFrameLayout.RESIZE_MODE_FIT, 4f / 3f),
    CINEMA("21:9", AspectRatioFrameLayout.RESIZE_MODE_FIT, 21f / 9f),
    ORIGINAL("Original", AspectRatioFrameLayout.RESIZE_MODE_FIT);

    fun next(): StreamSize = entries[(ordinal + 1) % entries.size]
}

/** Where a zoomed picture is held: the middle, or its top or bottom edge. */
enum class PicturePos(val label: String) {
    MIDDLE("Middle"), TOP("Top"), BOTTOM("Bottom");
    fun next(): PicturePos = entries[(ordinal + 1) % entries.size]
}

/** The picture's size, extra zoom (in %) and where a zoomed picture sits. */
data class Picture(val size: StreamSize = StreamSize.FIT, val zoom: Int = 100, val pos: PicturePos = PicturePos.MIDDLE) {
    fun nextZoom(): Picture = copy(zoom = ZOOMS.firstOrNull { it > zoom } ?: 100)
    fun encode() = "${size.name}|$zoom|${pos.name}"

    companion object {
        val ZOOMS = listOf(100, 110, 120, 135, 150, 175, 200)
        fun decode(s: String?): Picture? = s?.split('|')?.let { p ->
            runCatching { Picture(StreamSize.valueOf(p[0]), p.getOrNull(1)?.toIntOrNull() ?: 100, p.getOrNull(2)?.let(PicturePos::valueOf) ?: PicturePos.MIDDLE) }.getOrNull()
        }
    }
}

enum class SubtitleMode(val label: String) { OFF("Off"), FOREIGN("When the audio isn't my language"), ALWAYS("Always") }
enum class SubtitleSize(val label: String, val scale: Float) { SMALL("Small", 0.8f), MEDIUM("Medium", 1f), LARGE("Large", 1.3f) }

/**
 * Languages close enough to stand in for each other when there's nothing in the one chosen: Croatian, Serbian
 * and Bosnian are understood by speakers of all three.
 */
fun relatedLanguages(code: String): List<String> = when (code) {
    "hr" -> listOf("bs", "sr")
    "sr" -> listOf("hr", "bs")
    "bs" -> listOf("hr", "sr")
    else -> emptyList()
}

/** A language as an ISO 639-1 code ("hrv", "scc", "Croatian" and "hr-HR" all become "hr"), or null when unknown. */
fun normalizeLanguage(raw: String?): String? {
    val t = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    when (t) {
        "scr", "hrv" -> return "hr"; "scc", "srp" -> return "sr"; "bos" -> return "bs"; "pob", "pb" -> return "pt"
        "ze", "zht", "zhs", "chi" -> return "zh"; "gre" -> return "el"; "ger" -> return "de"; "fre" -> return "fr"; "dut" -> return "nl"
        "cze" -> return "cs"; "rum" -> return "ro"; "per" -> return "fa"; "slo" -> return "sk"; "mac" -> return "mk"; "alb" -> return "sq"
    }
    if (t.length == 2) return t
    t.substringBefore('-').substringBefore('_').takeIf { it.length == 2 }?.let { return it }
    java.util.Locale.getISOLanguages().firstOrNull { runCatching { java.util.Locale(it).isO3Language }.getOrNull() == t }?.let { return it }
    TRACK_LANGUAGES.firstOrNull { it.second.equals(raw.trim(), true) }?.first?.takeIf { it.isNotEmpty() }?.let { return it }
    return java.util.Locale.getISOLanguages().firstOrNull { java.util.Locale(it).getDisplayLanguage(java.util.Locale.ENGLISH).equals(raw.trim(), true) }
}

/** Languages offered in Settings › Subtitles and audio (ISO 639-1 codes). Empty code = the stream's own choice. */
val TRACK_LANGUAGES = listOf(
    "" to "Stream default", "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German", "it" to "Italian",
    "pt" to "Portuguese", "nl" to "Dutch", "pl" to "Polish", "ro" to "Romanian", "el" to "Greek", "tr" to "Turkish",
    "ar" to "Arabic", "hi" to "Hindi", "ur" to "Urdu", "pa" to "Punjabi", "ru" to "Russian", "uk" to "Ukrainian",
    "sv" to "Swedish", "da" to "Danish", "no" to "Norwegian", "fi" to "Finnish", "hu" to "Hungarian", "cs" to "Czech",
    "hr" to "Croatian", "sr" to "Serbian", "bs" to "Bosnian", "sl" to "Slovenian", "mk" to "Macedonian", "bg" to "Bulgarian",
    "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese",
)

/**
 * Settings › Playback and Subtitles and audio, kept separately for each profile. A profile without its own
 * copy yet starts from the settings the device had before they became per profile.
 */
@Singleton
class PlaybackSettings @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: com.vantaview.app.data.profile.ProfileSession,
) {
    private val shared = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
    private val sp: android.content.SharedPreferences
        get() {
            val id = session.id
            val p = context.getSharedPreferences("playback_p$id", Context.MODE_PRIVATE)
            if (!p.getBoolean("_made", false)) {
                val e = p.edit()
                shared.all.forEach { (k, v) ->
                    when (v) {
                        is String -> e.putString(k, v); is Int -> e.putInt(k, v); is Boolean -> e.putBoolean(k, v)
                        is Long -> e.putLong(k, v); is Float -> e.putFloat(k, v)
                    }
                }
                e.putBoolean("_made", true).apply()
            }
            return p
        }

    /** Small remembered choices (such as the Live TV order). */
    fun getString(key: String): String? = sp.getString(key, null)
    fun setString(key: String, value: String) = sp.edit().putString(key, value).apply()

    var buffer: BufferSize
        get() = runCatching { BufferSize.valueOf(sp.getString("buffer", null)!!) }.getOrDefault(BufferSize.MEDIUM)
        set(v) { sp.edit().putString("buffer", v.name).apply() }

    /** Seconds the player controls stay on screen; 0 means they stay until hidden. */
    /** Films and series: false = Play starts the best stream; true = Play shows the list of streams to pick from. */
    var chooseStreams: Boolean
        get() = sp.getBoolean("choose_streams", false)
        set(v) { sp.edit().putBoolean("choose_streams", v).apply() }

    /** Streams bigger than this (GB) are only tried after the others when picking by itself; 0 = no limit. */
    var maxAutoGb: Int
        get() = sp.getInt("max_auto_gb", 20)
        set(v) { sp.edit().putInt("max_auto_gb", v).apply() }

    /** Films and series open in this app instead of VantaView's player: a package name, "ask" (choose each time), or "" (VantaView). */
    var externalPlayer: String
        get() = sp.getString("external_player", "").orEmpty()
        set(v) { sp.edit().putString("external_player", v).apply() }

    var controlsSeconds: Int
        get() = sp.getInt("controls_seconds", 5)
        set(v) { sp.edit().putInt("controls_seconds", v).apply() }

    /** Picture-in-picture on phones: the PiP button, and shrinking the player when you press Home. */
    var pip: Boolean
        get() = sp.getBoolean("pip", true)
        set(v) { sp.edit().putBoolean("pip", v).apply(); com.vantaview.app.feature.player.PlayerPip.enabled = v }

    /** Live TV: the first press on a channel previews it in a small window; the second goes full screen. */
    var livePreview: Boolean
        get() = sp.getBoolean("live_preview", true)
        set(v) { sp.edit().putBoolean("live_preview", v).apply() }

    /** The best quality to try first (2160, 1080, 720, 480), or 0 for no limit: set from a speed test, or by hand. */
    var maxQuality: Int
        get() = sp.getInt("max_quality", 0)
        set(v) { sp.edit().putInt("max_quality", v).apply() }

    var miniPlayer: Boolean
        get() = sp.getBoolean("mini_player", true)
        set(v) { sp.edit().putBoolean("mini_player", v).apply() }

    /** Swipes in the live player (channel, brightness, volume) and double tap for full screen. */
    var gestures: Boolean
        get() = sp.getBoolean("gestures", true)
        set(v) { sp.edit().putBoolean("gestures", v).apply() }

    var startOnLastChannel: Boolean
        get() = sp.getBoolean("start_last", false)
        set(v) { sp.edit().putBoolean("start_last", v).apply() }

    var rememberSize: Boolean
        get() = sp.getBoolean("remember_size", true)
        set(v) { sp.edit().putBoolean("remember_size", v).apply() }

    /** The default picture size (Settings › Playback), for channels and titles without their own. */
    var size: StreamSize
        get() = runCatching { StreamSize.valueOf(sp.getString("size", null)!!) }.getOrDefault(StreamSize.FIT)
        set(v) { sp.edit().putString("size", v.name).apply() }

    private val pictures by lazy { context.getSharedPreferences("picture", Context.MODE_PRIVATE) }

    /** The picture for a channel or title ([key]): its own when remembered, otherwise the default. */
    fun picture(key: String?): Picture =
        (if (rememberSize && key != null) Picture.decode(pictures.getString(key, null)) else null) ?: Picture(size)

    /** Remembers the picture for [key] (when remembering is on). */
    fun savePicture(key: String?, p: Picture) {
        if (rememberSize && key != null) pictures.edit().putString(key, p.encode()).apply()
    }

    // ---- Settings › Subtitles and audio ----

    /** Preferred audio language code; empty = whatever the stream picks. */
    var audioLanguage: String
        get() = sp.getString("audio_lang", "en")!!
        set(v) { sp.edit().putString("audio_lang", v).apply() }

    var subtitleLanguage: String
        get() = sp.getString("sub_lang", "en")!!
        set(v) { sp.edit().putString("sub_lang", v).apply() }

    var subtitleMode: SubtitleMode
        get() = runCatching { SubtitleMode.valueOf(sp.getString("sub_mode", null)!!) }.getOrDefault(SubtitleMode.FOREIGN)
        set(v) { sp.edit().putString("sub_mode", v.name).apply() }

    var subtitleSize: SubtitleSize
        get() = runCatching { SubtitleSize.valueOf(sp.getString("sub_size", null)!!) }.getOrDefault(SubtitleSize.MEDIUM)
        set(v) { sp.edit().putString("sub_size", v.name).apply() }

    var subtitleYellow: Boolean
        get() = sp.getBoolean("sub_yellow", false)
        set(v) { sp.edit().putBoolean("sub_yellow", v).apply() }

    var subtitleBox: Boolean
        get() = sp.getBoolean("sub_box", true)
        set(v) { sp.edit().putBoolean("sub_box", v).apply() }

    /** Programme reminders: minutes before the start. */
    var reminderMinutes: Int
        get() = sp.getInt("reminder_minutes", 5)
        set(v) { sp.edit().putInt("reminder_minutes", v).apply() }

    // ---- Automatic buffering ----

    /** Let VantaView change the buffer itself: up after stalls, back down after smooth watching. */
    var autoBuffer: Boolean
        get() = sp.getBoolean("auto_buffer", true)
        set(v) { sp.edit().putBoolean("auto_buffer", v).apply() }

    /** Small-memory devices (1 GB Fire TV Sticks and the like, or low-memory mode) never go above Medium. */
    private val lowMemory: Boolean get() = LowMemory.active || smallHeap
    private val smallHeap: Boolean by lazy {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        am.memoryClass < 192
    }

    /** The buffer actually used: the chosen one, or the one automatic buffering has settled on. */
    val effectiveBuffer: BufferSize
        get() {
            val b = if (autoBuffer) runCatching { BufferSize.valueOf(sp.getString("auto_level", null)!!) }.getOrDefault(buffer) else buffer
            return if (lowMemory && b == BufferSize.LARGE) BufferSize.MEDIUM else b
        }

    /** The player stalled while playing. Two stalls in one session move the next stream up a size. */
    fun reportStall(stallsThisSession: Int) {
        if (!autoBuffer || stallsThisSession < 2) return
        val now = effectiveBuffer
        val up = BufferSize.entries.getOrNull(now.ordinal + 1) ?: return
        if (lowMemory && up == BufferSize.LARGE) return
        sp.edit().putString("auto_level", up.name).putLong("auto_smooth_ms", 0).apply()
    }

    /** Smooth playing time; after two hours without a stall the buffer steps back towards the chosen size. */
    fun reportSmooth(ms: Long) {
        if (!autoBuffer || ms <= 0) return
        val total = sp.getLong("auto_smooth_ms", 0) + ms
        val now = effectiveBuffer
        if (total >= 2 * 3_600_000L && now.ordinal > buffer.ordinal) {
            sp.edit().putString("auto_level", BufferSize.entries[now.ordinal - 1].name).putLong("auto_smooth_ms", 0).apply()
        } else sp.edit().putLong("auto_smooth_ms", total).apply()
    }

    fun loadControl(): LoadControl = effectiveBuffer.let {
        DefaultLoadControl.Builder().setBufferDurationsMs(it.minMs, it.maxMs, it.startMs, it.rebufferMs)
            // Keep a little behind the play position so short rewinds don't reload (less on small devices).
            .setBackBuffer(if (lowMemory) 10_000 else 30_000, true)
            // Low-memory mode: at most 40 MB of video waiting (the player's own limit is over 100 MB).
            .apply { if (LowMemory.active) setTargetBufferBytes(40 * 1024 * 1024) }
            .build()
    }

    // ---- Films and series ----

    /** Show "Skip intro" when the intro databases know where an episode's intro is. */
    /** Torrent results play inside VantaView when no debrid account has them ready. */
    var playTorrents: Boolean
        get() = sp.getBoolean("play_torrents", true)
        set(v) { sp.edit().putBoolean("play_torrents", v).apply() }

    var skipIntro: Boolean
        get() = sp.getBoolean("skip_intro", true)
        set(v) { sp.edit().putBoolean("skip_intro", v).apply() }

    /** Count down to the next episode at the end credits. */
    var nextEpisode: Boolean
        get() = sp.getBoolean("next_episode", true)
        set(v) { sp.edit().putBoolean("next_episode", v).apply() }

    /** Play a title's trailer on its details page after 20 seconds. */
    var autoTrailers: Boolean
        get() = sp.getBoolean("auto_trailers", true)
        set(v) { sp.edit().putBoolean("auto_trailers", v).apply() }

    /** Trailers start with sound (otherwise muted until you unmute). */
    var trailerSound: Boolean
        get() = sp.getBoolean("trailer_sound", true)
        set(v) { sp.edit().putBoolean("trailer_sound", v).apply() }

    /** Minutes without a key press or touch on a menu before the screensaver starts; 0 = never. */
    var ambientMinutes: Int
        get() = sp.getInt("ambient_minutes", 10)
        set(v) { sp.edit().putInt("ambient_minutes", v).apply() }

    /** Programme reminders also show while something is playing. */
    var remindersWhilePlaying: Boolean
        get() = sp.getBoolean("remind_playing", true)
        set(v) { sp.edit().putBoolean("remind_playing", v).apply() }

    /** True only the first time it's asked after the app starts (for "Start on last channel"). */
    private var startHandled = false
    fun firstStart(): Boolean = if (startHandled) false else { startHandled = true; true }
}
