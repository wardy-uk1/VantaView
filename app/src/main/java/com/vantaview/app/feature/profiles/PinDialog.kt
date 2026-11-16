// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.profiles

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import com.vantaview.app.ui.OutlinedTextField
import com.vantaview.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.vantaview.app.ui.Vanta

/** Asks for a 4-digit PIN. [check] returns true when it's right. */
@Composable
fun PinDialog(title: String, message: String, check: (String) -> Boolean, onOk: () -> Unit, onCancel: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun submit() { if (check(pin)) onOk() else { wrong = true; pin = "" } }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Vanta.Panel,
        title = { Text(title, color = Vanta.Text) },
        text = {
            Column {
                Text(message, color = Vanta.TextDim)
                OutlinedTextField(
                    value = pin,
                    onValueChange = { v ->
                        pin = v.filter { it.isDigit() }.take(4); wrong = false
                        if (pin.length == 4) submit()
                    },
                    label = { Text("PIN") },
                    singleLine = true,
                    keyboardOnFocus = true,
                    isError = wrong,
                    supportingText = { if (wrong) Text("Wrong PIN, try again") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = { TextButton(onClick = ::submit, enabled = pin.length == 4) { Text("OK") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
