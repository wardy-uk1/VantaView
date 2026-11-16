// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.ui

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.VisualTransformation

/** Set when the app starts. */
object TvInput {
    @Volatile var isTv: Boolean = false
}

/**
 * The app's text box (used in place of Material's, with the same parameters). On phones and tablets it's
 * Material's own. With a TV remote, passing over a box doesn't start typing: up and down move straight on, and
 * the keyboard only opens when OK is pressed on the box. Leaving the box stops typing again.
 * [keyboardOnFocus] is for boxes you go to only to type, like search and PINs: those start typing at once.
 */
@Composable
fun OutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    shape: Shape = OutlinedTextFieldDefaults.shape,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
    keyboardOnFocus: Boolean = false,
) {
    if (!TvInput.isTv) {
        androidx.compose.material3.OutlinedTextField(
            value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled, readOnly = readOnly,
            label = label, placeholder = placeholder, leadingIcon = leadingIcon, trailingIcon = trailingIcon,
            supportingText = supportingText, isError = isError, visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, singleLine = singleLine,
            maxLines = maxLines, minLines = minLines, shape = shape, colors = colors,
        )
        return
    }
    var typing by remember { mutableStateOf(keyboardOnFocus) }
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    // Starting to type: the box becomes editable, then the keyboard is asked for.
    LaunchedEffect(typing, focused) {
        if (typing && focused) { kotlinx.coroutines.delay(60); keyboard?.show() }
    }
    androidx.compose.material3.OutlinedTextField(
        value = value, onValueChange = onValueChange,
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused && !keyboardOnFocus) typing = false
            }
            .onPreviewKeyEvent { e ->
                when (e.key) {
                    Key.DirectionDown, Key.DirectionUp -> {
                        if (e.type == KeyEventType.KeyDown) {
                            keyboard?.hide()
                            val down = e.key == Key.DirectionDown
                            focus.moveFocus(if (down) FocusDirection.Down else FocusDirection.Up) ||
                                focus.moveFocus(if (down) FocusDirection.Next else FocusDirection.Previous)
                        }
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (typing || readOnly || !enabled) false
                        else { if (e.type == KeyEventType.KeyUp) typing = true; true }
                    }
                    else -> false
                }
            },
        enabled = enabled,
        // Not typing yet: the box can be passed over without the keyboard opening.
        readOnly = readOnly || !typing,
        label = label, placeholder = placeholder, leadingIcon = leadingIcon, trailingIcon = trailingIcon,
        supportingText = supportingText, isError = isError, visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, singleLine = singleLine,
        maxLines = maxLines, minLines = minLines, shape = shape, colors = colors,
    )
}
