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

/** How video fills the screen. */
enum class StreamSize(val label: String, val mode: Int) {
    FIT("Fit", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    FILL("Fill", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    ZOOM("Zoom", AspectRatioFrameLayout.RESIZE_MODE_ZOOM);

    fun next(): StreamSize = entries[(ordinal + 1) % entries.size]
}

enum class SubtitleMode(val label: String) { OFF("Off"), FOREIGN("When the audio isn't my language"), ALWAYS("Always") }
enum class SubtitleSize(val label: String, val scale: Float) { SMALL("Small", 0.8f), MEDIUM("Medium", 1f), LARGE("Large", 1.3f) }

/** Languages offered in Settings › Subtitles and audio (ISO 639-1 codes). Empty code = the stream's own choice. */
val TRACK_LANGUAGES = listOf(
    "" to "Stream default", "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German", "it" to "Italian",
    "pt" to "Portuguese", "nl" to "Dutch", "pl" to "Polish", "ro" to "Romanian", "el" to "Greek", "tr" to "Turkish",
    "ar" to "Arabic", "hi" to "Hindi", "ur" to "Urdu", "pa" to "Punjabi", "ru" to "Russian", "uk" to "Ukrainian",
    "sv" to "Swedish", "da" to "Danish", "no" to "Norwegian", "fi" to "Finnish", "hu" to "Hungarian", "cs" to "Czech",
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

    /** The stream size last picked in the player; Fit when not remembered. */
    var size: StreamSize
        get() = if (!rememberSize) StreamSize.FIT
            else runCatching { StreamSize.valueOf(sp.getString("size", null)!!) }.getOrDefault(StreamSize.FIT)
        set(v) { sp.edit().putString("size", v.name).apply() }

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

    /** Small-memory devices (1 GB Fire TV Sticks and the like) never go above Medium. */
    private val lowMemory: Boolean by lazy {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        am.isLowRamDevice || am.memoryClass < 192
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
