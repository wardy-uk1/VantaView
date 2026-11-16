// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.vod

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vantaview.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vantaview.app.data.addon.MetaPreview
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.Vanta
import kotlinx.coroutines.delay

/** Wide artwork for a title: the backdrop by IMDb id from Cinemeta's image service, or its poster. */
fun backdropOf(m: MetaPreview): String? =
    if (m.id.startsWith("tt")) "https://images.metahub.space/background/medium/${m.id}/img" else m.poster

/**
 * The banner at the top of Movies and TV Series: the newest titles, changing every eight seconds.
 * On TVs and wide screens it shows the poster that has focus instead (title, year, rating, genres).
 */
@Composable
fun HeroBanner(label: String, items: List<MetaPreview>, focused: MetaPreview?, height: Dp, onOpen: (MetaPreview) -> Unit) {
    if (items.isEmpty() && focused == null) return
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(items.size, focused == null) {
        while (focused == null && items.size > 1) { delay(8_000); index = (index + 1) % items.size }
    }
    val shown = focused ?: items.getOrNull(index % items.size.coerceAtLeast(1)) ?: return
    Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(18.dp)).background(Vanta.SurfaceHigh)) {
        // Low-memory mode swaps pictures straight away (a fade holds two big pictures at once).
        Crossfade(shown, animationSpec = androidx.compose.animation.core.tween(if (com.vantaview.app.data.LowMemory.active) 0 else 300), label = "hero") { m ->
            Box(Modifier.fillMaxSize()) {
                AsyncImage(model = backdropOf(m), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xEE07080F), 0.65f to Color(0x6607080F), 1f to Color.Transparent)))
                Column(Modifier.align(Alignment.CenterStart).padding(start = 22.dp, end = 22.dp).widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (focused == null) Text(label.uppercase(), color = Vanta.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text(m.name, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val facts = listOfNotNull(m.year, m.rating?.let { "★ $it" }, m.genres.take(3).joinToString(", ").ifBlank { null }).joinToString("  ·  ")
                    if (facts.isNotEmpty()) Text(facts, color = Color(0xDDFFFFFF), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (focused == null) FocusCard(onClick = { onOpen(m) }) { Text("▶ Open", color = Vanta.Text, maxLines = 1) }
                }
                if (focused == null && items.size > 1) Row(Modifier.align(Alignment.BottomEnd).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    items.indices.forEach { i ->
                        Box(Modifier.size(if (i == index % items.size) 8.dp else 6.dp).clip(CircleShape).background(if (i == index % items.size) Color.White else Color(0x88FFFFFF)))
                    }
                }
            }
        }
    }
}
