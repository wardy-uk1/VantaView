// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.search

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import com.vantaview.app.ui.OutlinedTextField
import com.vantaview.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vantaview.app.data.ChannelNames
import com.vantaview.app.data.addon.AddonRepository
import com.vantaview.app.data.addon.MetaPreview
import com.vantaview.app.data.db.ChannelEntity
import com.vantaview.app.data.db.ProgrammeEntity
import com.vantaview.app.data.db.VantaDb
import com.vantaview.app.data.profile.KidsFilter
import com.vantaview.app.data.profile.ParentalControl
import com.vantaview.app.data.profile.ProfileRepository
import com.vantaview.app.data.profile.ProfileSession
import com.vantaview.app.feature.vod.PosterCard
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.Vanta
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class SearchResults(
    val query: String = "",
    val busy: Boolean = false,
    val channels: List<ChannelEntity> = emptyList(),
    val programmes: List<Pair<ProgrammeEntity, ChannelEntity>> = emptyList(),
    val films: List<MetaPreview> = emptyList(),
    val series: List<MetaPreview> = emptyList(),
    val people: List<com.vantaview.app.data.browse.BrowseRepository.Person> = emptyList(),
) {
    val empty get() = channels.isEmpty() && programmes.isEmpty() && films.isEmpty() && series.isEmpty() && people.isEmpty()
}

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val db: VantaDb,
    private val addons: AddonRepository,
    private val session: ProfileSession,
    private val profiles: ProfileRepository,
    private val parental: ParentalControl,
    private val browse: com.vantaview.app.data.browse.BrowseRepository,
) : ViewModel() {
    private val sp = context.getSharedPreferences("search", Context.MODE_PRIVATE)
    val query = MutableStateFlow("")
    private val _results = MutableStateFlow(SearchResults())
    val results: StateFlow<SearchResults> = _results
    private val _recent = MutableStateFlow(readRecent())
    val recent: StateFlow<List<String>> = _recent

    init {
        viewModelScope.launch {
            query.debounce(400).collectLatest { q -> run(q.trim()) }
        }
    }

    private suspend fun run(q: String) {
        if (q.length < 2) { _results.value = SearchResults(q); return }
        _results.value = _results.value.copy(query = q, busy = true)
        val profile = profiles.current()
        val kids = profile?.isKids == true
        val providers = db.providers().allFor(session.id)
        val ids = providers.map { it.id }
        val now = System.currentTimeMillis()

        // Which channels this profile may see (Kids' allowed categories; Parental control's hidden adult ones).
        val allowed: suspend (List<ChannelEntity>) -> List<ChannelEntity> = { list ->
            list.groupBy { it.providerId }.flatMap { (pid, chans) ->
                val cats = db.content().categories(pid)
                if (kids) {
                    val allow = db.profiles().kidsCategories(profile!!.id).filter { it.providerId == pid }.map { it.categoryRemoteId }.toSet()
                    val adult = cats.filter { KidsFilter.looksAdult(it.name) }.map { it.remoteId }.toSet()
                    chans.filter { KidsFilter.channelAllowed(it, adult, allow) }
                } else if (parental.hiding) {
                    val adult = parental.adultIds(cats)
                    chans.filter { !parental.hidden(it, adult) }
                } else chans
            }
        }

        coroutineScope {
            val films = async { runCatching { addons.search("movie", q) }.getOrDefault(emptyList()) }
            val series = async { runCatching { addons.search("series", q) }.getOrDefault(emptyList()) }
            val people = async { if (kids) emptyList() else runCatching { browse.people(q) }.getOrDefault(emptyList()) }
            val channels = if (ids.isEmpty()) emptyList() else allowed(db.content().searchChannels(ids, q).filter { !ChannelNames.isDivider(it.name) })
            val progs = if (ids.isEmpty()) emptyList() else db.guide().search(ids, q, now)
            // Match each programme to a channel the profile can see.
            val byKey = progs.groupBy { it.providerId }.flatMap { (pid, ps) ->
                val chans = allowed(db.content().channelsByGuideKey(pid, ps.map { it.channelKey }.distinct()))
                val map = chans.groupBy { it.epgChannelId }
                ps.mapNotNull { p -> map[p.channelKey]?.firstOrNull()?.let { p to it } }
            }.sortedBy { it.first.start }.take(40)
            _results.value = _results.value.copy(channels = channels.take(30), programmes = byKey)
            fun family(l: List<MetaPreview>) = if (kids) l.filter { KidsFilter.hasFamilyGenre(it.genres) } else l
            _results.value = _results.value.copy(films = family(films.await()), series = family(series.await()), people = people.await(), busy = false)
        }
    }

    // ---- Recent searches (per profile) ----
    private fun key() = "recent_${session.id}"
    private fun readRecent(): List<String> = runCatching {
        val a = JSONArray(sp.getString(key(), "[]")); (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    /** Remembers the search when a result is opened or the search key is pressed. */
    fun remember() {
        val q = query.value.trim()
        if (q.length < 2) return
        val list = (listOf(q) + _recent.value.filter { !it.equals(q, true) }).take(10)
        sp.edit().putString(key(), JSONArray(list).toString()).apply()
        _recent.value = list
    }

    fun clearRecent() { sp.edit().remove(key()).apply(); _recent.value = emptyList() }
}

/** Search across channels, TV guide programmes, films and series. */
@Composable
fun SearchScreen(
    onPlayChannel: (Long) -> Unit,
    onOpenTitle: (type: String, id: String, name: String, poster: String?, play: String?) -> Unit,
    onPerson: (String) -> Unit = {},
    vm: SearchViewModel = hiltViewModel(),
) {
    val q by vm.query.collectAsStateWithLifecycle()
    val r by vm.results.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }

    val voiceOk = remember { canListen(context) }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { vm.query.value = it }
    }
    val time = remember { SimpleDateFormat("HH:mm", Locale.UK) }
    val day = remember { SimpleDateFormat("EEE", com.vantaview.app.ui.I18n.locale) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { com.vantaview.app.ui.PageTitle("Search") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = q, onValueChange = { vm.query.value = it }, singleLine = true,
                    label = { Text("Channels, programmes, films, series") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.remember() }),
                    modifier = Modifier.weight(1f).focusRequester(field),
                    keyboardOnFocus = true,
                )
                if (voiceOk) FocusCard(onClick = {
                    runCatching {
                        voice.launch(
                            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say what to search for")
                        )
                    }
                }) { Text("🎤 Voice", color = Vanta.Text) }
            }
        }

        if (q.trim().length < 2) {
            if (recent.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Recent searches", color = Vanta.Text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        FocusCard(onClick = vm::clearRecent) { Text("Clear", color = Vanta.TextDim, fontSize = 13.sp) }
                    }
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(recent) { s -> FocusCard(onClick = { vm.query.value = s }) { Text(s, color = Vanta.Text) } }
                    }
                }
            } else item { Text("Type at least two letters.", color = Vanta.TextDim) }
            return@LazyColumn
        }

        if (r.busy && r.empty) item { Text("Searching…", color = Vanta.TextDim) }
        if (!r.busy && r.empty) item { Text("Nothing found for \"${r.query}\".", color = Vanta.TextDim) }

        if (r.channels.isNotEmpty()) {
            item { Heading("Channels") }
            items(r.channels, key = { "c" + it.id }) { c ->
                FocusCard(onClick = { vm.remember(); onPlayChannel(c.id) }, modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.number.toString(), color = Vanta.Cyan, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp))
                        Text(ChannelNames.display(c.name), color = Vanta.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (r.programmes.isNotEmpty()) {
            item { Heading("On TV") }
            items(r.programmes, key = { (p, c) -> "p${c.id}-${p.start}" }) { (p, c) ->
                val now = System.currentTimeMillis()
                val live = p.start <= now
                FocusCard(onClick = { vm.remember(); onPlayChannel(c.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Text(p.title, color = Vanta.Text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            (if (live) "On now" else "${day.format(Date(p.start))} ${time.format(Date(p.start))}") + " · ${ChannelNames.display(c.name)}",
                            color = if (live) Vanta.Cyan else Vanta.TextDim, fontSize = 13.sp,
                        )
                    }
                }
            }
        }
        listOf("Films" to r.films, "Series" to r.series).forEach { (label, list) ->
            if (list.isNotEmpty()) {
                item { Heading(label) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(list, key = { it.type + it.id }) { m ->
                            PosterCard(m, onClick = { vm.remember(); onOpenTitle(m.type, m.id, m.name, m.poster, null) })
                        }
                    }
                }
            }
        }
        if (r.people.isNotEmpty()) {
            item { Heading("People") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(r.people, key = { it.id }) { p ->
                        FocusCard(onClick = { vm.remember(); onPerson(p.id) }, padding = PaddingValues(8.dp), modifier = Modifier.width(120.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                Box(Modifier.size(84.dp).clip(CircleShape).background(Vanta.SurfaceHigh), contentAlignment = Alignment.Center) {
                                    if (p.photo != null) coil.compose.AsyncImage(
                                        model = p.photo, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    ) else Text(p.name.take(1), color = Vanta.TextDim, fontSize = 28.sp)
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(p.name, color = Vanta.Text, fontSize = 13.sp, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                p.job?.let { Text(it, color = Vanta.TextDim, fontSize = 11.sp, maxLines = 1) }
                            }
                        }
                    }
                }
            }
        }
        if (r.busy && !r.empty) item { Text("Still searching your add-ons…", color = Vanta.TextDim, fontSize = 13.sp) }
    }
}

@Composable
private fun Heading(text: String) = com.vantaview.app.ui.SectionTitle(text, Modifier.padding(top = 6.dp))

private fun canListen(context: Context): Boolean = runCatching {
    context.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()
}.getOrDefault(false)
