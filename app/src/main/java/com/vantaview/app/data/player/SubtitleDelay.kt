// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory

/**
 * Subtitle delay for the films and series player: subtitles shown earlier or later than the video says, for
 * subtitles that are out of step. It works for every subtitle (in the file, or from a subtitle add-on): the
 * player's subtitle part is simply told the time is [delayMs] earlier (later subtitles) or later (earlier ones).
 */
@OptIn(UnstableApi::class)
object SubtitleDelay {
    /** + shows subtitles later, - earlier. */
    @Volatile var delayMs: Long = 0L

    /** [base]'s renderers, with each subtitle renderer shifted by [delayMs]. */
    fun wrap(base: RenderersFactory): RenderersFactory = RenderersFactory { handler, video, audio, text, metadata ->
        base.createRenderers(handler, video, audio, text, metadata).map { r ->
            if (r.trackType == C.TRACK_TYPE_TEXT) Shifted(r) else r
        }.toTypedArray()
    }

    private class Shifted(private val inner: Renderer) : Renderer by inner {
        override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
            inner.render((positionUs - delayMs * 1000L).coerceAtLeast(0L), elapsedRealtimeUs)
        }
    }
}
