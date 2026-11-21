// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vantaview.app.data.PlaybackSettings
import com.vantaview.app.data.catalog.ChannelCatalog
import com.vantaview.app.data.catalog.ChannelOrder
import com.vantaview.app.data.catalog.MajorChannels
import com.vantaview.app.data.country.CountryDetect
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.Text
import com.vantaview.app.ui.Vanta
import com.vantaview.app.ui.tr
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Settings › Channels: topic groups, Importance order with your countries first, and regional and +1 versions on
 * one line, all from the channel catalog (iptv-org's open database).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun CatalogSection(playback: PlaybackSettings) {
    val scope = rememberCoroutineScope()
    val catalogState by ChannelCatalog.state.collectAsStateWithLifecycle()
    val version by ChannelCatalog.version.collectAsStateWithLifecycle()
    var topics by remember { mutableStateOf(ChannelOrder.byTopic(playback)) }
    var versions by remember { mutableStateOf(ChannelOrder.collapseVersions(playback)) }
    var order by remember { mutableStateOf(ChannelOrder.countries(playback)) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var present by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        present = runCatching {
            dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext, CatalogEntry::class.java).db().content().channelCountries()
        }.getOrDefault(emptyList())
    }
    fun saveOrder(l: List<String>) { order = l; ChannelOrder.setCountries(playback, l) }
    fun fetch() { scope.launch { ChannelCatalog.ensure() } }

    Section("Topics, importance and versions") {
        Text(
            "Live TV can group channels by topic (News, Sport, Kids…) instead of your provider's groups, put the best-known channels of your countries first (Order › Importance), and show a channel's regional and +1 versions as one line. These use an open channel database that's downloaded once and updated weekly.",
            color = Vanta.TextDim, fontSize = 14.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Group Live TV by topic", color = Vanta.Text)
                Text("Also a button in Live TV. Your provider's groups stay one press away.", color = Vanta.TextDim, fontSize = 13.sp)
            }
            Switch(checked = topics, onCheckedChange = { topics = it; ChannelOrder.setByTopic(playback, it); if (it) fetch() })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Regional and +1 versions as one line", color = Vanta.Text)
                Text("BBC One London and Scotland, or ITV1 and ITV1 +1, become one line. Pick another version with the Source button in the player.", color = Vanta.TextDim, fontSize = 13.sp)
            }
            Switch(checked = versions, onCheckedChange = { versions = it; ChannelOrder.setCollapseVersions(playback, it); if (it) fetch() })
        }
        Text(
            when {
                catalogState != null -> catalogState!!
                ChannelCatalog.updatedAt > 0 -> tr(
                    "Channel catalog: {0} channels, updated {1}.", "%,d".format(ChannelCatalog.size),
                    SimpleDateFormat("d MMM", com.vantaview.app.ui.I18n.locale).format(Date(ChannelCatalog.updatedAt)),
                ).also { version }
                else -> tr("The channel catalog downloads when you first use one of these.")
            },
            color = Vanta.TextDim, fontSize = 13.sp,
        )
        FocusCard(onClick = { scope.launch { ChannelCatalog.ensure(force = true) } }) { Text("Update the channel catalog", color = Vanta.Text) }
    }

    Section("Country order") {
        Text("For Order › Importance: channels from these countries come first, in this order.", color = Vanta.TextDim, fontSize = 14.sp)
        order.forEachIndexed { i, code ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${i + 1}. ${tr(CountryDetect.displayName(code))}", color = Vanta.Text, modifier = Modifier.weight(1f))
                if (i > 0) FocusCard(onClick = { saveOrder(order.toMutableList().apply { add(i - 1, removeAt(i)) }) }) { Text("▲", color = Vanta.Text) }
                if (i < order.size - 1) FocusCard(onClick = { saveOrder(order.toMutableList().apply { add(i + 1, removeAt(i)) }) }) { Text("▼", color = Vanta.Text) }
                if (order.size > 1) FocusCard(onClick = { saveOrder(order - code) }) { Text("✕", color = Vanta.Error) }
            }
        }
        val more = (present + MajorChannels.countries).distinct().filter { it !in order && it != CountryDetect.OTHER }
            .sortedBy { CountryDetect.displayName(it) }
        if (more.isNotEmpty()) {
            Text("Add a country:", color = Vanta.TextDim, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                more.forEach { c -> FocusCard(onClick = { saveOrder(order + c) }) { Text(tr(CountryDetect.displayName(c)), color = Vanta.Text, fontSize = 14.sp, maxLines = 1) } }
            }
        }
    }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal interface CatalogEntry {
    fun db(): com.vantaview.app.data.db.VantaDb
}
