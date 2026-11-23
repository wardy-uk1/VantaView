// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.vod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import com.vantaview.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vantaview.app.data.addon.AddonStream
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.Vanta

fun qualityLabel(s: AddonStream): String = when {
    s.quality >= 2160 -> "4K"
    s.quality > 0 -> "${s.quality}p"
    else -> "?"
}

/** "TB cached", "RD check on play", "Download first" for torrent results; null for direct links. */
fun debridLabel(s: AddonStream): String? {
    if (!s.isTorrent) return null
    val cached = s.cache.filterValues { it == com.vantaview.app.data.debrid.Availability.CACHED }.keys
    val unknown = s.cache.filterValues { it == com.vantaview.app.data.debrid.Availability.UNKNOWN }.keys
    return when {
        cached.isNotEmpty() -> "⚡ " + cached.joinToString("/") { it.short } + " cached"
        unknown.isNotEmpty() -> unknown.joinToString("/") { it.short } + " · checked on play"
        // Not on any debrid service (or none connected): VantaView plays it as a torrent, if that's on.
        s.cache.isEmpty() -> "Torrent"
        else -> "Torrent · not cached"
    }
}

fun sizeLabel(s: AddonStream): String? = s.sizeBytes?.let { b ->
    if (b >= 1_073_741_824L) "%.1f GB".format(b / 1_073_741_824.0) else "%d MB".format(b / 1_048_576L)
}

/** Dialog listing streams; [current] is highlighted. */
@Composable
fun StreamListDialog(
    title: String,
    streams: List<AddonStream>,
    current: Int?,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    /** Some add-ons haven't answered yet; their streams are added when they do. */
    loading: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Vanta.Panel,
        title = { Text(title, color = Vanta.Text) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (loading) item(key = "loading") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(if (streams.isEmpty()) "Looking for streams…" else "Looking in more add-ons…", color = Vanta.TextDim, fontSize = 13.sp)
                    }
                }
                itemsIndexed(streams) { i, s ->
                    FocusCard(onClick = { onPick(i) }, selected = i == current, modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Text(
                                listOfNotNull(qualityLabel(s), sizeLabel(s), debridLabel(s), s.addonName).joinToString("  ·  "),
                                color = if (i == current) Vanta.Cyan else Vanta.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                            )
                            Text(s.label, color = Vanta.TextDim, fontSize = 12.sp, maxLines = 3)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
