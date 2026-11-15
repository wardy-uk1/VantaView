// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * Text boxes with a TV remote. Up and down always move on to the next control, instead of getting stuck in
 * the box (or moving its cursor), and the on-screen keyboard only opens when OK is pressed on the box, not
 * every time the remote passes over it. Phones and tablets are unchanged.
 */
object TvInput {
    /** Set when the app starts. */
    @Volatile var isTv: Boolean = false
}

fun Modifier.tvTextField(): Modifier = composed {
    if (!TvInput.isTv) return@composed Modifier
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Modifier.onPreviewKeyEvent { e ->
        when (e.key) {
            Key.DirectionDown -> {
                if (e.type == KeyEventType.KeyDown) { keyboard?.hide(); focus.moveFocus(FocusDirection.Down) || focus.moveFocus(FocusDirection.Next) }
                true
            }
            Key.DirectionUp -> {
                if (e.type == KeyEventType.KeyDown) { keyboard?.hide(); focus.moveFocus(FocusDirection.Up) || focus.moveFocus(FocusDirection.Previous) }
                true
            }
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                if (e.type == KeyEventType.KeyUp) keyboard?.show()
                false
            }
            else -> false
        }
    }
}

/** On a TV, the keyboard waits for OK instead of opening as soon as the remote reaches the box. */
fun KeyboardOptions.tvSafe(): KeyboardOptions = if (TvInput.isTv) copy(showKeyboardOnFocus = false) else this
