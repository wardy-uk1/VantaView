// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import com.vantaview.app.ui.OutlinedTextField
import com.vantaview.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vantaview.app.data.addon.MetaPreview
import com.vantaview.app.ui.GradientButton
import com.vantaview.app.ui.Vanta
import com.vantaview.app.ui.FocusCard

/** Movies or TV Series page: a row per add-on catalogue, plus search. */
@Composable
fun CatalogScreen(
    title: String,
    onOpen: (MetaPreview) -> Unit,
    onAddAddon: () -> Unit,
    onTile: (com.vantaview.app.data.browse.BrowseTile) -> Unit = {},
    /** Opens a whole row (its title was chosen): the row's key and title. */
    onRow: (String, String) -> Unit = { _, _ -> },
    vm: CatalogViewModel = hiltViewModel(),
) {
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 720
    // Sports TV: just the add-ons' own rows, without the film and series browsing (tiles, genres, years, order).
    val sports = vm.type == com.vantaview.app.data.addon.AddonRepository.TV
    val ui by vm.ui.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(Vanta.Page).padding(horizontal = 16.dp, vertical = 12.dp)) {
        com.vantaview.app.ui.PageTitle(title)
        Spacer(Modifier.height(10.dp))

        if (ui.loaded && ui.addonCount == 0 && ui.rows.isEmpty() && ui.kids) {
            Message("Ask a grown-up to add add-ons to this profile.")
            return@Column
        }
        // Only when there's truly nothing: no add-ons, and no portal or playlist with films or series.
        if (ui.loaded && ui.addonCount == 0 && ui.rows.isEmpty()) {
            Empty(
                if (sports) "No add-ons with live channels or events yet. Install one on the Sources page and its rows appear here."
                else if (vm.type == "series") "No add-ons or portals yet. Install Stremio or Nuvio add-ons, or add a Stalker portal, on the Sources page and their series appear here."
                else "No add-ons or portals yet. Install Stremio or Nuvio add-ons, or add a Stalker portal, on the Sources page and their films appear here.",
                "Add an add-on", onAddAddon,
            )
            return@Column
        }

        val sort by vm.sort.collectAsStateWithLifecycle()
        val filter by vm.filter.collectAsStateWithLifecycle()
        val grid by vm.grid.collectAsStateWithLifecycle()
        var focused by remember { mutableStateOf<MetaPreview?>(null) }
        var picking by remember { mutableStateOf<String?>(null) }
        // Search, sort and filters. While browsing they scroll away with the rows, so short screens
        // (a phone on its side) show the tiles; while searching they stay at the top.
        val header: @Composable () -> Unit = {
          Column {
        com.vantaview.app.ui.SearchToggle(query, { vm.query.value = it }, "Search ${title.lowercase()}")
        Spacer(Modifier.height(10.dp))
        // Sort: applies to every row and to search results.
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "view") {
                FocusCard(onClick = { vm.setGrid(!grid) }) { Text(if (grid) "☰ Rows" else "▦ Posters", color = Vanta.Text, fontSize = 14.sp, maxLines = 1, softWrap = false) }
            }
            if (!sports) items(CatalogSort.entries.toList(), key = { it.name }) { o ->
                FocusCard(onClick = { vm.setSort(o) }, selected = o == sort) {
                    Text(o.label, color = if (o == sort) Vanta.Cyan else Vanta.Text, fontSize = 14.sp,
                        fontWeight = if (o == sort) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        // Filters: genre, decade and source (add-on or portal).
        if (!sports) Spacer(Modifier.height(8.dp))
        if (!sports) androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FocusCard(onClick = { picking = "genre" }, selected = filter.genre != null) { Text("Genre: ${filter.genre ?: "Any"} ▾", color = Vanta.Text, fontSize = 14.sp) } }
            item {
                FocusCard(onClick = { picking = "year" }, selected = filter.decade != null) {
                    Text("Year: ${filter.decade?.let { CatalogFilter.decadeLabel(it) } ?: "Any"} ▾", color = Vanta.Text, fontSize = 14.sp)
                }
            }
            item { FocusCard(onClick = { picking = "source" }, selected = filter.source != null) { Text("Source: ${filter.source ?: "Any"} ▾", color = Vanta.Text, fontSize = 14.sp) } }
            if (filter.any) item { FocusCard(onClick = { vm.filter.value = CatalogFilter() }) { Text("Clear filters", color = Vanta.Cyan, fontSize = 14.sp) } }
        }
            Spacer(Modifier.height(12.dp))
          }
        }
        picking?.let { what ->
            val options: List<Pair<String, () -> Unit>> = when (what) {
                "genre" -> listOf("Any" to { vm.filter.value = filter.copy(genre = null) }) +
                    vm.genres().map { g -> g to { vm.filter.value = filter.copy(genre = g) } }
                "year" -> listOf("Any" to { vm.filter.value = filter.copy(decade = null) }) +
                    CatalogFilter.DECADES.map { d -> CatalogFilter.decadeLabel(d) to { vm.filter.value = filter.copy(decade = d) } }
                else -> listOf("Any" to { vm.filter.value = filter.copy(source = null) }) +
                    vm.sources().map { n -> n to { vm.filter.value = filter.copy(source = n) } }
            }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { picking = null }, containerColor = Vanta.Panel,
                title = { Text(when (what) { "genre" -> "Genre"; "year" -> "Year"; else -> "Source" }, color = Vanta.Text) },
                text = {
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(options.size) { i ->
                            val (label, go) = options[i]
                            FocusCard(onClick = { go(); picking = null }, modifier = Modifier.fillMaxWidth()) { Text(label, color = Vanta.Text) }
                        }
                    }
                },
                confirmButton = { androidx.compose.material3.TextButton(onClick = { picking = null }) { Text("Close") } },
            )
        }
        val browsing = query.isBlank() && !(ui.loaded && ui.rows.isEmpty() && ui.tiles.isEmpty())
        if (!browsing) header()

        val results = ui.results?.let { sort.apply(it) }?.filter(filter::keep)
        when {
            query.isNotBlank() && (ui.searching || results == null) -> Message("Searching…")
            query.isNotBlank() && results != null -> {
                if (results.isEmpty()) Message("Nothing found for \"$query\". Search only covers add-ons that support it.")
                else LazyVerticalGrid(
                    columns = GridCells.Adaptive(118.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(results, key = { it.id }) { PosterCard(it, onClick = { onOpen(it) }, modifier = Modifier) }
                }
            }
            ui.loaded && ui.rows.isEmpty() && ui.tiles.isEmpty() && ui.kids -> Message(
                "None of this profile's add-ons has a family or kids catalogue for ${if (vm.type == "series") "series" else "films"}."
            )
            ui.loaded && ui.rows.isEmpty() && ui.tiles.isEmpty() -> Message(
                "Your add-ons have no ${if (vm.type == "series") "series" else "film"} catalogues to browse. " +
                    "You can still search above, or add a catalogue add-on such as Cinemeta on the Sources page."
            )
            grid -> {
                // Posters only: every loaded title in one grid.
                header()
                val all = ui.rows.filter(filter::keepRow).flatMap { r -> (ui.rowStates[r.key] as? RowState.Ready)?.items.orEmpty() }
                    .let { l -> ui.traktRows.flatMap { it.items } + l }.distinctBy { it.id }.filter(filter::keep).let(sort::apply)
                if (all.isEmpty()) Message(if (ui.rowStates.values.any { it is RowState.Loading }) "Loading…" else "Nothing here matches.")
                else LazyVerticalGrid(
                    columns = GridCells.Adaptive(118.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(all, key = { it.id }) { PosterCard(it, onClick = { onOpen(it) }, modifier = Modifier) }
                }
            }
            else -> Column {
              // The banner: the newest titles (from a "new" or "year" catalogue when there is one).
              val newRow = Regex("(?i)\\b(new|recent|latest|year)")
              val bannerRow = ui.rows.firstOrNull { newRow.containsMatchIn(it.title) && ui.rowStates[it.key] is RowState.Ready }
                  ?: ui.rows.firstOrNull { ui.rowStates[it.key] is RowState.Ready }
              val bannerItems = (bannerRow?.let { ui.rowStates[it.key] } as? RowState.Ready)?.items.orEmpty().filter(filter::keep).take(8)
              val bannerLabel = if (bannerRow != null && newRow.containsMatchIn(bannerRow.title)) "Recently added" else "Popular now"
              // TVs and wide screens: the banner stays at the top and shows the poster with focus.
              if (wide && bannerItems.isNotEmpty()) {
                  HeroBanner(bannerLabel, bannerItems, focused, 220.dp, onOpen)
                  Spacer(Modifier.height(12.dp))
              }
              LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "header") { header() }
                if (!wide && bannerItems.isNotEmpty()) item(key = "banner") { HeroBanner(bannerLabel, bannerItems, null, 190.dp, onOpen) }
                if (ui.tiles.isNotEmpty() && !filter.any) browseGroups(ui.tiles, wide, onTile)
                items(ui.traktRows, key = { it.key }) { row ->
                    val shown = row.items.filter(filter::keep) // Trakt's own order
                    if (shown.isNotEmpty()) Column {
                        com.vantaview.app.ui.SectionTitle(row.title, onClick = { onRow(row.key, row.title) })
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(shown, key = { it.id }) { PosterCard(it, onClick = { onOpen(it) }, onFocused = if (wide) ({ m -> focused = m }) else null) }
                        }
                    }
                }
                items(ui.rows.filter(filter::keepRow), key = { it.key }) { row ->
                    Column {
                        com.vantaview.app.ui.SectionTitle(row.title, onClick = { onRow(row.key, row.title) })
                        Spacer(Modifier.height(8.dp))
                        when (val st = ui.rowStates[row.key]) {
                            is RowState.Ready ->
                                st.items.filter(filter::keep).let { shown ->
                                    if (shown.isEmpty()) Text(if (filter.any) "Nothing here matches the filters" else "Empty", color = Vanta.TextDim)
                                    else LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        items(sort.apply(shown), key = { it.id }) { PosterCard(it, onClick = { onOpen(it) }, onFocused = if (wide) ({ m -> focused = m }) else null) }
                                    }
                                }
                            RowState.Failed -> Text("Couldn't load this row", color = Vanta.Error)
                            else -> Text("Loading…", color = Vanta.TextDim)
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Vanta.TextDim, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 520.dp))
    }
}

@Composable
fun Empty(text: String, button: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(text, color = Vanta.TextDim, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 480.dp))
            GradientButton(button, onClick = onClick)
        }
    }
}
