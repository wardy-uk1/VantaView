// SPDX-License-Identifier: GPL-3.0-or-later
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.vantaview.app.feature.player

import android.graphics.Color
import android.graphics.Typeface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.vantaview.app.data.PlaybackSettings
import com.vantaview.app.data.SubtitleMode
import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One audio or subtitle track a viewer can pick. [group] null means "Off". */
data class TrackChoice(val label: String, val selected: Boolean, val group: Tracks.Group?, val index: Int)

/** Settings › Subtitles and audio, applied to a player. */
object TrackPrefs {
    fun apply(player: Player, s: PlaybackSettings) {
        val b = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguages(*listOfNotNull(s.audioLanguage.ifEmpty { null }).let { l -> (l + l.flatMap(com.vantaview.app.data::relatedLanguages)) }.toTypedArray())
            // Croatian, Serbian and Bosnian stand in for each other when the one chosen isn't there.
            .setPreferredTextLanguages(*listOfNotNull(s.subtitleLanguage.ifEmpty { null }).let { l -> (l + l.flatMap(com.vantaview.app.data::relatedLanguages)) }.toTypedArray())
            .setSelectUndeterminedTextLanguage(true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, s.subtitleMode == SubtitleMode.OFF)
        if (s.subtitleMode == SubtitleMode.ALWAYS) b.setIgnoredTextSelectionFlags(0) // pick a subtitle even if not forced
        player.trackSelectionParameters = b.build()
    }

    /** "When the audio isn't my language": once tracks are known, show subtitles only for foreign audio. */
    fun onTracks(player: Player, s: PlaybackSettings, tracks: Tracks) {
        if (s.subtitleMode != SubtitleMode.FOREIGN) return
        val mine = s.audioLanguage.ifEmpty { Locale.getDefault().language }
        val audio = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).language } }
        val foreign = audio != null && !audio.startsWith(mine, ignoreCase = true) && audio != C.LANGUAGE_UNDETERMINED
        val disabled = C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes
        if (foreign == disabled) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !foreign).build()
        }
    }

    /** Subtitle size, colour and background from Settings. */
    fun style(view: PlayerView, s: PlaybackSettings) {
        val sub = view.subtitleView ?: return
        val fg = if (s.subtitleYellow) Color.rgb(255, 214, 10) else Color.WHITE
        val bg = if (s.subtitleBox) Color.argb(170, 0, 0, 0) else Color.TRANSPARENT
        sub.setStyle(
            CaptionStyleCompat(
                fg, bg, Color.TRANSPARENT,
                if (s.subtitleBox) CaptionStyleCompat.EDGE_TYPE_NONE else CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                Color.BLACK, Typeface.DEFAULT_BOLD,
            )
        )
        sub.setApplyEmbeddedStyles(false)
        sub.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * s.subtitleSize.scale)
    }

    /** The tracks of one type, for the Audio or Subtitles picker. Subtitles start with "Off". */
    fun choices(player: Player, type: Int): List<TrackChoice> {
        val out = mutableListOf<TrackChoice>()
        val off = C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes
        if (type == C.TRACK_TYPE_TEXT) out += TrackChoice("Off", off || player.currentTracks.groups.none { it.type == type && it.isSelected }, null, -1)
        player.currentTracks.groups.filter { it.type == type }.forEach { g ->
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                out += TrackChoice(label(g.getTrackFormat(i), type, out.size), !off && g.isTrackSelected(i), g, i)
            }
        }
        return out
    }

    fun pick(player: Player, type: Int, c: TrackChoice) {
        val b = player.trackSelectionParameters.buildUpon()
        if (c.group == null) b.setTrackTypeDisabled(type, true)
        else b.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(c.group.mediaTrackGroup, c.index))
        player.trackSelectionParameters = b.build()
    }

    private fun label(f: Format, type: Int, n: Int): String {
        val lang = f.language?.takeIf { it != C.LANGUAGE_UNDETERMINED }?.let { Locale(it).getDisplayLanguage(Locale.getDefault()) }
            ?.replaceFirstChar { it.titlecase(Locale.getDefault()) }
        val name = f.label ?: lang ?: (if (type == C.TRACK_TYPE_AUDIO) "Audio $n" else "Subtitles $n")
        val extra = buildList {
            if (f.label != null && lang != null && !f.label!!.contains(lang, true)) add(lang)
            if (type == C.TRACK_TYPE_AUDIO) when (f.channelCount) { 1 -> add("Mono"); 2 -> add("Stereo"); 6 -> add("5.1"); 8 -> add("7.1") }
            if (f.selectionFlags and C.SELECTION_FLAG_FORCED != 0) add("Forced")
        }
        return if (extra.isEmpty()) name else "$name · ${extra.joinToString(" · ")}"
    }
}

/** The AI subtitle line, styled like normal subtitles, with a small "AI" tag. */
@androidx.compose.runtime.Composable
fun AiCaption(engine: com.vantaview.app.data.aisubs.AiSubtitleEngine, s: PlaybackSettings, modifier: androidx.compose.ui.Modifier) {
    val line by engine.line.collectAsState()
    val status by engine.status.collectAsState()
    androidx.compose.foundation.layout.Column(modifier, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        status?.let {
            com.vantaview.app.ui.Text(
                it, color = androidx.compose.ui.graphics.Color.White, fontSize = 12.sp,
                modifier = androidx.compose.ui.Modifier.background(androidx.compose.ui.graphics.Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        line?.let { l ->
            val fg = if (s.subtitleYellow) androidx.compose.ui.graphics.Color(0xFFFFD60A) else androidx.compose.ui.graphics.Color.White
            androidx.compose.material3.Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = androidx.compose.ui.graphics.Color(0xFF14E0E0), fontSize = 11.sp, fontWeight = FontWeight.Black)) { append("AI  ") }
                    append(l.text)
                },
                color = fg, fontSize = (18 * s.subtitleSize.scale).sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, maxLines = 3,
                style = if (s.subtitleBox) TextStyle.Default else TextStyle(shadow = Shadow(androidx.compose.ui.graphics.Color.Black, blurRadius = 6f)),
                modifier = androidx.compose.ui.Modifier.padding(top = 6.dp)
                    .then(if (s.subtitleBox) androidx.compose.ui.Modifier.background(androidx.compose.ui.graphics.Color(0xAA000000), RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 3.dp) else androidx.compose.ui.Modifier),
            )
        }
    }
}
