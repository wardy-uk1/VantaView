// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.settings

import kotlinx.coroutines.launch
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import com.vantaview.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vantaview.app.BuildConfig
import com.vantaview.app.feature.terms.TERMS_TEXT
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.GradientButton
import com.vantaview.app.ui.Vanta

private const val SOURCE_URL = "https://github.com/wardy-uk1/VantaView"
private const val ISSUES_URL = "https://github.com/wardy-uk1/VantaView/issues"

private data class Credit(val name: String, val detail: String)

/** Online services VantaView uses. Add a line here when a new one is used. */
private val services = listOf(
    Credit("Free TV", "The optional free channels list · github.com/Free-TV/IPTV"),
    Credit("epgshare01", "TV guide for the free channels · epgshare01.online"),
    Credit("Open-Meteo", "Weather on Home · open-meteo.com (CC BY 4.0)"),
    Credit("TMDB", "Films and series behind the Movies and TV Series tiles · themoviedb.org. This product uses the TMDB API but is not endorsed or certified by TMDB."),
    Credit("Cinemeta", "Films and series behind the tiles when there's no TMDB key · v3-cinemeta.strem.io"),
    Credit("IntroDB", "Intro and end-credits times for Skip intro · introdb.app"),
    Credit("SkipDB", "Intro and end-credits times · skipdb.tv. Contains data from SkipDB, made available under the ODbL"),
    Credit("Cloudflare speed test", "The server the internet speed test downloads from · speed.cloudflare.com"),
    Credit("iptv-org channel database", "Topics, Importance and channel versions in Live TV · github.com/iptv-org/database (public domain)"),
    Credit("Trakt", "Watchlist, history, Continue watching, recommendations and lists sync · trakt.tv. Not made or endorsed by Trakt"),
)

/** People who helped shape VantaView with ideas and bug reports. */
private val community = listOf(
    Credit("u/CrazyDowntown5881 (Reddit)", "Quite a few suggestions and bug reports"),
    Credit("u/Turbulent-Invite2673 (Reddit)", "Picture sizes, full screen past the notch, player controls, stream choice, external players and subtitle timing"),
)

/** Open-source libraries VantaView is built with, and their licences. */
private val libraries = listOf(
    Credit("Android Jetpack (Compose, Room, Navigation, Lifecycle, Media3/ExoPlayer)", "Apache 2.0"),
    Credit("Kotlin and kotlinx.coroutines", "Apache 2.0"),
    Credit("Hilt / Dagger", "Apache 2.0"),
    Credit("OkHttp", "Apache 2.0"),
    Credit("Coil", "Apache 2.0"),
    Credit("ZXing (QR codes)", "Apache 2.0"),
    Credit("whisper.cpp and OpenAI Whisper models (AI subtitles)", "MIT"),
    Credit("libtorrent and libtorrent4j (playing torrents)", "BSD / MIT"),
    Credit("Tabler Icons (tile pictures) · tabler.io", "MIT"),
    Credit("flag-icons (International tile pictures) · github.com/lipis/flag-icons", "MIT"),
    Credit("Bebas Neue by Ryoichi Tsunekawa (tile titles)", "SIL Open Font License 1.1"),
    Credit("Stalker-Portal by Jitendra Unatti (how a MAG box signs in to a portal) · github.com/Jitendraunatti/Stalker-Portal", "GPL 3.0"),
    Credit("Stream Goat by Syed Ali Hamza (Xtream sign-in check) · github.com/sherazi5110/stream-goat", "GPL 3.0"),
)

/** Settings › Help and about: tour, about, report a problem, terms and credits. */
@Composable
fun HelpAbout() {
    val context = LocalContext.current
    var tour by remember { mutableStateOf(false) }
    var noBrowser by remember { mutableStateOf<String?>(null) }
    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { noBrowser = url } // Many TVs have no web browser: show the address instead.
    }

    Section("App tour") {
        Text("A quick look at where everything is.", color = Vanta.TextDim, fontSize = 14.sp)
        GradientButton("Take the tour", onClick = { tour = true })
    }

    Section("About") {
        Text("VantaView ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})", color = Vanta.Text, fontSize = 16.sp)
        Text(
            "VantaView doesn't supply any channels, films or series. You add your own playlists and sources.",
            color = Vanta.TextDim, fontSize = 14.sp,
        )
        Text(
            "Free software under the GNU General Public License, version 3 or later. You can read, change and share the source code.",
            color = Vanta.TextDim, fontSize = 14.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FocusCard(onClick = { open(SOURCE_URL) }) { Text("Source code", color = Vanta.Text) }
            FocusCard(onClick = { open(ISSUES_URL) }) { Text("Report a problem", color = Vanta.Text) }
        }
        noBrowser?.let { Text("No web browser here. Visit $it on your phone or computer.", color = Vanta.Cyan, fontSize = 13.sp) }
    }

    Section("Log file") {
        Text(
            "If something goes wrong, save a log file and attach it to your problem report. Logins, keys and MAC addresses are blanked out.",
            color = Vanta.TextDim, fontSize = 14.sp,
        )
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        var logNote by remember { mutableStateOf<String?>(null) }
        val save = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri ->
            if (uri != null) scope.launch {
                logNote = com.vantaview.app.data.support.LogExport.write(context, uri)
                    .fold({ "Saved the log ($it lines)." }, { "Couldn't save the log (${it.message})." })
            }
        }
        FocusCard(onClick = {
            runCatching { save.launch("vantaview-log-${java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.UK).format(java.util.Date())}.txt") }
                .onFailure { logNote = "This device has no file picker to save the log with." }
        }) { Text("Save a log file", color = Vanta.Text) }
        logNote?.let { Text(it, color = Vanta.Cyan, fontSize = 13.sp) }
    }

    Section("Terms of use") {
        Text(TERMS_TEXT, color = Vanta.TextDim, fontSize = 14.sp, lineHeight = 20.sp)
    }

    Section("Credits") {
        Text("Thanks to these services, and the people who run them:", color = Vanta.TextDim, fontSize = 14.sp)
        services.forEach { CreditLine(it) }
        Text("Thanks to the people who suggest ideas and report bugs:", color = Vanta.TextDim, fontSize = 14.sp)
        community.forEach { CreditLine(it) }
        Text("Built with these open-source libraries:", color = Vanta.TextDim, fontSize = 14.sp)
        libraries.forEach { CreditLine(it) }
    }

    if (tour) Tour { tour = false }
}

@Composable
private fun CreditLine(c: Credit) {
    Column {
        Text(c.name, color = Vanta.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(c.detail, color = Vanta.TextDim, fontSize = 13.sp)
    }
}

private val tourPages = listOf(
    "Welcome to VantaView" to "Add your own playlists in Sources, then pick a profile. Each profile keeps its own favourites and history. " +
        "On a TV, use the arrows and OK; Back always goes back a page.",
    "Home" to "Your shortcuts: Live TV, the TV Guide, Movies and Series, plus what's on now, Continue watching and your favourite channels.",
    "Live TV" to "Pick a category, then a channel. Type a number on the remote to jump to it, and use Countries to hide channels you don't want. " +
        "In the player, Up and Down or a sideways swipe change channel; swipe up or down for brightness (left) and volume (right); double tap for full screen.",
    "TV Guide" to "See what's on from yesterday to a week ahead. Select a programme to watch its channel, or Watch from start when your portal has catch-up.",
    "Movies and Series" to "Films and series come from the add-ons you install in Sources, and from a Stalker portal if you add one. Pick a title to see its details and choose a stream.",
    "Settings" to "Weather, backups and more. Back up your profiles and playlists to a file or your phone, and turn on automatic backups.",
)

/** A short tour, one page at a time. */
@Composable
private fun Tour(onClose: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    val (title, text) = tourPages[page]
    val last = page == tourPages.lastIndex
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Vanta.Panel,
        title = { Text(title, color = Vanta.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(text, color = Vanta.TextDim, fontSize = 15.sp, lineHeight = 21.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    tourPages.indices.forEach { i ->
                        Box(Modifier.size(if (i == page) 10.dp else 7.dp).background(if (i == page) Vanta.Cyan else Vanta.TextDim, CircleShape))
                    }
                }
            }
        },
        confirmButton = { GradientButton(if (last) "Done" else "Next", onClick = { if (last) onClose() else page++ }) },
        dismissButton = {
            if (page > 0) TextButton(onClick = { page-- }) { Text("Back") }
            else TextButton(onClick = onClose) { Text("Skip") }
        },
    )
}
