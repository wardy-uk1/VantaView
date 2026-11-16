// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

/**
 * Settings that make IPTV play more smoothly:
 * - if the device's own video decoder can't cope with a stream, another decoder is tried instead of stuttering
 *   or stopping;
 * - live streams play at exactly normal speed (the player otherwise speeds up and slows down slightly to keep
 *   close to live, which some devices play as judder and wobbling sound);
 * - MPEG-TS streams (most IPTV channels) are read in a way that copes with the odd ones many providers send,
 *   so they start sooner and don't freeze on broken frames.
 */
@OptIn(UnstableApi::class)
object PlayerTuning {
    fun renderers(context: Context, base: RenderersFactory? = null): RenderersFactory =
        ((base as? DefaultRenderersFactory) ?: DefaultRenderersFactory(context)).apply { setEnableDecoderFallback(true) }

    fun builder(context: Context, base: RenderersFactory?, live: Boolean): ExoPlayer.Builder =
        ExoPlayer.Builder(context, renderers(context, base)).apply {
            if (live) setLivePlaybackSpeedControl(
                DefaultLivePlaybackSpeedControl.Builder().setFallbackMinPlaybackSpeed(1f).setFallbackMaxPlaybackSpeed(1f).build()
            )
        }

    fun extractors(): DefaultExtractorsFactory = DefaultExtractorsFactory()
        .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
        .setConstantBitrateSeekingEnabled(true)
}
