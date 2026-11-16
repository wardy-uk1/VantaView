// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data

import com.vantaview.app.ui.tr

/**
 * Settings that suit a connection, from a speed test: the buffer size, the best stream quality to try first,
 * built-in torrents and the Home mini player. Used by the first-launch connection step and by "Adjust my
 * settings for this connection" in Settings › Sources.
 */
object ConnectionTuning {
    data class Advice(val buffer: BufferSize, val maxQuality: Int, val torrents: Boolean, val miniPlayer: Boolean)

    /** [down] in Mbit/s; [jitter] in ms (how much the ping wobbles: a sign of an unsteady line). */
    fun advise(down: Double, jitter: Double?): Advice {
        val shaky = (jitter ?: 0.0) > 40
        val buffer = when {
            down >= 25 && !shaky -> BufferSize.SMALL
            down >= 8 && !shaky -> BufferSize.MEDIUM
            down >= 25 -> BufferSize.MEDIUM
            else -> BufferSize.LARGE
        }
        val quality = when {
            down >= 25 -> 0
            down >= 12 -> 1080
            down >= 5 -> 720
            else -> 480
        }
        return Advice(buffer, quality, torrents = down >= 10, miniPlayer = down >= 8)
    }

    /** Applies [a] and returns what changed, one line each, for the viewer. */
    fun apply(a: Advice, s: PlaybackSettings): List<String> {
        s.buffer = a.buffer
        s.maxQuality = a.maxQuality
        val torrentsWere = s.playTorrents
        if (!a.torrents) s.playTorrents = false
        if (!a.miniPlayer) s.miniPlayer = false
        return buildList {
            add(tr("Buffer size: {0}", tr(a.buffer.label)))
            add(if (a.maxQuality == 0) tr("Streams: the best quality first") else tr("Streams: up to {0}p first", a.maxQuality))
            if (!a.torrents && torrentsWere) add(tr("Torrents: off (they rarely play smoothly on this connection)"))
            if (!a.miniPlayer) add(tr("Mini player on Home: off, to save your connection for what you watch"))
        }
    }

    /**
     * Streams in the order to try them: with a quality limit ([max], 0 for none), the ones within it (or of
     * unknown quality) first, keeping their order; the bigger ones after, in case nothing else plays.
     */
    fun <T> preferFitting(list: List<T>, max: Int, quality: (T) -> Int): List<T> {
        if (max <= 0) return list
        val (fit, big) = list.partition { quality(it) == 0 || quality(it) <= max }
        return fit + big
    }
}
