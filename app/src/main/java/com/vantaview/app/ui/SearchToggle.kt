// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp

/**
 * A search box that starts as a magnifier button. Pressing it opens the box (with the keyboard);
 * the cross clears the search and closes it again. It stays open while there's something typed.
 */
@Composable
fun SearchToggle(query: String, onQuery: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    var justOpened by remember { mutableStateOf(false) }
    if (open || query.isNotEmpty()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text(label) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = { onQuery(""); open = false }) { Icon(Icons.Filled.Close, contentDescription = "Close search") }
            },
            modifier = (modifier.fillMaxWidth().focusRequester(focus)).then(Modifier.tvTextField()),
        )
        LaunchedEffect(justOpened) { if (justOpened) { runCatching { focus.requestFocus() }; justOpened = false } }
    } else {
        FocusCard(
            onClick = { open = true; justOpened = true }, modifier = modifier, shape = CircleShape,
            padding = PaddingValues(12.dp), contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Search, contentDescription = label, tint = Vanta.Text, modifier = Modifier.size(22.dp)) }
    }
}
