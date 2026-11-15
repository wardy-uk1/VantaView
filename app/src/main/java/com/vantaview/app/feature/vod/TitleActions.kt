// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.vod

import com.vantaview.app.ui.tvTextField
import com.vantaview.app.ui.tvSafe

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.vantaview.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.vantaview.app.data.addon.MetaPreview
import com.vantaview.app.data.addon.Video
import com.vantaview.app.data.browse.BrowseRepository
import com.vantaview.app.data.library.LibraryStore
import com.vantaview.app.data.library.SavedTitle
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.Vanta
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Watchlist and Watched for a film or series picked by pressing and holding its poster. */
@HiltViewModel
class TitleActionsViewModel @Inject constructor(
    private val library: LibraryStore,
    private val browse: BrowseRepository,
    private val profiles: com.vantaview.app.data.profile.ProfileRepository,
    private val history: com.vantaview.app.data.history.WatchHistory,
) : ViewModel() {
    fun inContinue(m: MetaPreview) = history.get(m.type, m.id) != null
    fun removeContinue(m: MetaPreview) = history.remove(m.type, m.id)

    /** The other profiles a title can be shared with. */
    val others = kotlinx.coroutines.flow.MutableStateFlow<List<com.vantaview.app.data.db.ProfileEntity>>(emptyList())
    init {
        viewModelScope.launch {
            val me = profiles.current()?.id
            others.value = profiles.all().filter { it.id != me }
        }
    }

    fun share(m: MetaPreview, to: com.vantaview.app.data.db.ProfileEntity, message: String, done: () -> Unit) {
        viewModelScope.launch {
            val from = profiles.current()?.name ?: "Someone"
            change(m, done) { t -> library.share(to.id, t, from, message) }
        }
    }

    fun inWatchlist(m: MetaPreview) = library.inWatchlist(m.type, m.id)
    fun isWatched(m: MetaPreview) = library.isWatched(m.type, m.id)

    /** Titles from TMDB are saved under their IMDb id, as the details page and add-ons use. */
    fun change(m: MetaPreview, done: () -> Unit, apply: (SavedTitle) -> Unit) {
        viewModelScope.launch {
            val id = if (m.id.startsWith("tmdb:")) runCatching { browse.imdbId(m.id) }.getOrNull() ?: m.id else m.id
            apply(SavedTitle(m.type, id, m.name, m.poster, m.year, 0))
            done()
        }
    }

    fun setWatchlist(m: MetaPreview, on: Boolean, done: () -> Unit) = change(m, done) { library.setWatchlist(it, on) }
    fun setWatched(m: MetaPreview, on: Boolean, done: () -> Unit) = change(m, done) { library.setWatched(it, on) }
}

/** The dimmed, blurred backdrop and centred column every quick-actions pop-up shares. */
@Composable
private fun ActionsBackdrop(image: String?, onDismiss: () -> Unit, content: @Composable (maxW: androidx.compose.ui.unit.Dp) -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(Color.Black)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            // The picture itself, heavily blurred, behind everything (blur needs Android 12; older ones just dim it).
            if (image != null) AsyncImage(
                model = image, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(60.dp) else Modifier),
            )
            Box(Modifier.fillMaxSize().background(Color(if (Build.VERSION.SDK_INT >= 31) 0xB3000000 else 0xE6000000)))
            val wide = maxWidth > maxHeight
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(24.dp)
                    // Taps on the card itself don't close it.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { content(if (wide) 420.dp else 360.dp) }
        }
    }
}

/** One line of the actions panel: a label with its symbol on the right. */
@Composable
private fun ActionRow(label: String, symbol: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusCard(onClick = onClick, shape = RoundedCornerShape(0.dp), padding = PaddingValues(horizontal = 22.dp, vertical = 18.dp), modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Vanta.Text, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Text(symbol, color = Vanta.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ActionsPanel(maxW: androidx.compose.ui.unit.Dp, rows: @Composable () -> Unit) {
    Column(
        Modifier.widthIn(max = maxW).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xFF1C1C20)),
    ) { rows() }
}

/** Press and hold a film or series poster: a big poster with Watchlist and Watched. */
@Composable
fun TitleActionsDialog(item: MetaPreview, onDismiss: () -> Unit, vm: TitleActionsViewModel = hiltViewModel()) {
    var tick by remember { mutableIntStateOf(0) }
    var sharing by remember { androidx.compose.runtime.mutableStateOf(false) }
    var sent by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val listed = remember(tick) { vm.inWatchlist(item) }
    val continuing = remember(tick) { vm.inContinue(item) }
    val watched = remember(tick) { vm.isWatched(item) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    ActionsBackdrop(item.poster, onDismiss) { maxW ->
        Box(
            Modifier.width(maxW * 0.62f).aspectRatio(2f / 3f).clip(RoundedCornerShape(26.dp)).background(Vanta.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text(item.name, color = Vanta.TextDim, textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp))
            if (item.poster != null) AsyncImage(model = item.poster, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(18.dp))
        Text(item.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        item.year?.let { Text(it, color = Vanta.TextDim, fontSize = 15.sp) }
        Spacer(Modifier.height(14.dp))
        ActionsPanel(maxW) {
            ActionRow(
                if (listed) "Remove from Watchlist" else "Add to Watchlist", if (listed) "✓" else "+",
                onClick = { vm.setWatchlist(item, !listed) { tick++ } }, modifier = Modifier.focusRequester(first),
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x22FFFFFF)))
            ActionRow(
                if (watched) "Mark as not watched" else "Mark as watched", if (watched) "✓" else "○",
                onClick = { vm.setWatched(item, !watched) { tick++ } },
            )
            if (continuing) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x22FFFFFF)))
                ActionRow("Remove from Continue watching", "✕", onClick = { vm.removeContinue(item); tick++ })
            }
            val others by vm.others.collectAsStateWithLifecycle()
            if (others.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x22FFFFFF)))
                ActionRow("Share with another profile", "➚", onClick = { sharing = true })
            }
        }
        sent?.let { Spacer(Modifier.height(10.dp)); Text(it, color = Vanta.Cyan, fontSize = 14.sp) }
    }
    if (sharing) ShareDialog(vm.others.collectAsStateWithLifecycle().value, onClose = { sharing = false }) { to, msg ->
        vm.share(item, to, msg) { sent = "Shared with ${to.name}."; sharing = false }
    }
}

/** Pick who to share with, and an optional message. */
@Composable
private fun ShareDialog(
    others: List<com.vantaview.app.data.db.ProfileEntity>,
    onClose: () -> Unit,
    onShare: (com.vantaview.app.data.db.ProfileEntity, String) -> Unit,
) {
    var to by remember { androidx.compose.runtime.mutableStateOf(others.firstOrNull()) }
    var msg by remember { androidx.compose.runtime.mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose, containerColor = Vanta.Panel,
        title = { Text("Share with", color = Vanta.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                others.forEach { p ->
                    FocusCard(onClick = { to = p }, selected = to?.id == p.id, modifier = Modifier.fillMaxWidth()) {
                        Text(p.name, color = if (to?.id == p.id) Vanta.Cyan else Vanta.Text)
                    }
                }
                androidx.compose.material3.OutlinedTextField(
                    value = msg, onValueChange = { if (it.length <= 140) msg = it }, singleLine = false, maxLines = 3,
                    label = { Text("Message (optional)") }, modifier = Modifier.fillMaxWidth().then(Modifier.tvTextField()),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default.tvSafe(),
                )
            }
        },
        confirmButton = { com.vantaview.app.ui.GradientButton("Share", onClick = { to?.let { onShare(it, msg) } }, enabled = to != null) },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/** The "⋯" of an episode (or press and hold it): the episode's card with Watched for it and its season. */
@Composable
fun EpisodeActionsDialog(
    v: Video,
    watched: Boolean,
    seasonWatched: Boolean,
    onWatched: (Boolean) -> Unit,
    onSeasonWatched: (Boolean) -> Unit,
    onChooseStream: () -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val code = "S${v.season} E${v.episode}"
    val name = v.title.ifBlank { "Episode ${v.episode}" }
    val seasonName = if (v.season == 0) "Specials" else "Season ${v.season}"
    ActionsBackdrop(v.thumbnail, onDismiss) { maxW ->
        Box(
            Modifier.widthIn(max = maxW + 40.dp).fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(22.dp))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(22.dp)).background(Vanta.SurfaceHigh),
        ) {
            if (v.thumbnail != null) AsyncImage(model = v.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.25f to Color.Transparent, 1f to Color(0xF2000000))))
            Column(Modifier.align(Alignment.BottomStart).padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(code, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 3.dp))
                Text(name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                v.overview?.takeIf { it.isNotBlank() }?.let { Text(it, color = Color(0xDDFFFFFF), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                releasedLabel(v.released)?.let { Text(it, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.End)) }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(code, color = Vanta.TextDim, fontSize = 15.sp)
        Spacer(Modifier.height(14.dp))
        ActionsPanel(maxW) {
            ActionRow(
                if (watched) "Mark as not watched" else "Mark as watched", if (watched) "✓" else "○",
                onClick = { onWatched(!watched) }, modifier = Modifier.focusRequester(first),
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x22FFFFFF)))
            ActionRow(
                if (seasonWatched) "Mark $seasonName as not watched" else "Mark $seasonName as watched", if (seasonWatched) "✓" else "☰",
                onClick = { onSeasonWatched(!seasonWatched) },
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x22FFFFFF)))
            ActionRow("Choose a stream", "▶", onClick = onChooseStream)
        }
    }
}

/** "2025-03-30T…" as "30 March 2025". */
private fun releasedLabel(s: String?): String? = runCatching {
    val d = java.time.LocalDate.parse(s!!.take(10))
    d.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", com.vantaview.app.ui.I18n.locale))
}.getOrNull()
