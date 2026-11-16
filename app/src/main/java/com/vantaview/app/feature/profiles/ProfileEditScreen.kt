// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.feature.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import com.vantaview.app.ui.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import com.vantaview.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vantaview.app.data.db.ProfileEntity
import com.vantaview.app.ui.AvatarCircle
import com.vantaview.app.ui.Avatars
import com.vantaview.app.ui.FocusCard
import com.vantaview.app.ui.GradientButton
import com.vantaview.app.ui.Vanta
import kotlinx.coroutines.launch

/**
 * Creates or edits a profile. [first] is the first-launch "Who's watching?" step: a standard profile,
 * no type or copy options. [profileId] 0 means a new profile.
 */
@Composable
fun ProfileEditScreen(
    profileId: Long,
    first: Boolean,
    onDone: (Long) -> Unit,
    onBack: (() -> Unit)?,
    vm: ProfilesViewModel = hiltViewModel(),
) {
    val profiles by vm.profiles.collectAsState()
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(profileId == 0L) }
    var existing by remember { mutableStateOf<ProfileEntity?>(null) }
    var name by remember { mutableStateOf("") }
    var avatar by remember { mutableStateOf(0) }
    var kids by remember { mutableStateOf(false) }
    var copy by remember { mutableStateOf(!first) }
    var usePin by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var parentPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var copySource by remember { mutableStateOf<ProfileEntity?>(null) }
    var categories by remember { mutableStateOf<List<PlaylistCategories>>(emptyList()) }

    LaunchedEffect(profileId) {
        if (profileId != 0L) {
            val p = vm.repo.get(profileId)
            existing = p
            if (p != null) {
                name = p.name; avatar = p.avatar; kids = p.isKids; usePin = p.pinHash != null
                if (p.isKids) categories = vm.categoriesFor(p.id)
            }
            loaded = true
        } else {
            val cur = vm.current()
            copySource = cur?.takeIf { !it.isKids } ?: vm.repo.all().firstOrNull { !it.isKids }
            avatar = vm.repo.all().size % Avatars.all.size
        }
    }
    val allowed by remember(profileId) { vm.repo.observeKidsCategories(profileId) }.collectAsState(initial = emptyList())

    val standardCount = profiles.count { !it.isKids }
    val isLastStandard = existing?.let { !it.isKids && standardCount <= 1 } == true
    val needsParentPin = kids && !vm.repo.hasParentPin

    fun save() {
        error = null
        val pinNeeded = usePin && (existing?.pinHash == null || pin.isNotEmpty())
        when {
            name.isBlank() -> { error = "Give the profile a name"; return }
            pinNeeded && (pin.length != 4) -> { error = "The profile PIN must be 4 digits"; return }
            needsParentPin && parentPin.length != 4 -> { error = "Set a 4-digit parent PIN for Kids profiles"; return }
            kids && isLastStandard -> { error = "Keep at least one standard profile"; return }
        }
        scope.launch {
            try {
                if (needsParentPin) vm.repo.setParentPin(parentPin)
                val id = existing?.let { p ->
                    vm.repo.update(
                        p.copy(name = name, avatar = avatar, isKids = kids),
                        newPin = if (usePin) pin.ifEmpty { null } else null,
                        removePin = !usePin,
                    )
                    p.id
                } ?: vm.repo.create(
                    name = name, avatar = avatar, kids = kids,
                    pin = if (usePin) pin else null,
                    copyFrom = if (copy && !first) copySource?.id else null,
                )
                onDone(id)
            } catch (e: Exception) {
                error = e.message ?: "Couldn't save the profile"
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Vanta.Bg).imePadding(), contentAlignment = Alignment.TopCenter) {
        if (!loaded) return@Box
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = if (first) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            if (first) {
                Text("Step 2 of 5 · Profile", color = Vanta.TextDim, fontSize = 13.sp)
                Text("Who's watching?", color = Vanta.Text, fontSize = 30.sp, fontWeight = FontWeight.Black)
                Text("Create your profile. You can add more people later.", color = Vanta.TextDim, textAlign = TextAlign.Center)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    onBack?.let { FocusCard(onClick = it) { Text("‹ Back", color = Vanta.Text) }; Spacer(Modifier.width(12.dp)) }
                    Text(if (existing == null) "Add profile" else "Edit profile", color = Vanta.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
            }

            AvatarCircle(avatar, 120.dp, ring = true, modifier = if (first) Modifier else Modifier.padding(top = 4.dp))
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(20); error = null },
                label = { Text("Profile name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(Avatars.all) { i, _ ->
                    FocusCard(
                        onClick = { avatar = i }, selected = avatar == i,
                        padding = PaddingValues(4.dp), shape = androidx.compose.foundation.shape.CircleShape,
                        contentAlignment = Alignment.Center,
                    ) { AvatarCircle(i, 48.dp) }
                }
            }

            if (!first) {
                Text("PROFILE TYPE", color = Vanta.TextDim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FocusCard(onClick = { kids = false }, selected = !kids, modifier = Modifier.weight(1f)) {
                        Column {
                            Text("Standard", color = if (!kids) Vanta.Cyan else Vanta.Text, fontWeight = FontWeight.Bold)
                            Text("Everything available", color = Vanta.TextDim, fontSize = 13.sp)
                        }
                    }
                    FocusCard(onClick = { if (!isLastStandard) kids = true else error = "Keep at least one standard profile" }, selected = kids, modifier = Modifier.weight(1f)) {
                        Column {
                            Text("Kids", color = if (kids) Color_Yellow else Vanta.Text, fontWeight = FontWeight.Bold)
                            Text("Family content only · no Sources", color = Vanta.TextDim, fontSize = 13.sp)
                        }
                    }
                }
            }

            Column(
                Modifier.fillMaxWidth().background(Vanta.Surface, RoundedCornerShape(14.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!first && existing == null && copySource != null) {
                    ToggleRow(
                        "Copy playlists from ${copySource!!.name}",
                        "Playlists and add-ons are copied and belong to this profile from then on.",
                        copy,
                    ) { copy = it }
                }
                ToggleRow("Lock with a PIN", "Optional · ask for a 4-digit PIN when this profile opens", usePin) { usePin = it; pin = "" }
                if (usePin) {
                    PinField(
                        if (existing?.pinHash != null) "New PIN (leave empty to keep the current one)" else "Profile PIN (4 digits)",
                        pin,
                    ) { pin = it }
                }
                if (needsParentPin) {
                    Text(
                        "Kids profiles need a parent PIN. It's asked for when leaving a Kids profile or changing profiles.",
                        color = Vanta.TextDim, fontSize = 13.sp,
                    )
                    PinField("Parent PIN (4 digits)", parentPin) { parentPin = it }
                }
            }

            if (existing?.isKids == true && kids) {
                Text("Allowed Live TV categories", color = Vanta.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Adult categories are always hidden. Until you tick any for a playlist, every other category on it is shown.",
                    color = Vanta.TextDim, fontSize = 13.sp,
                )
                if (categories.isEmpty()) Text("This profile has no playlists yet.", color = Vanta.TextDim)
                categories.forEach { pc ->
                    Text(pc.provider.name, color = Vanta.Cyan, fontWeight = FontWeight.Bold)
                    if (pc.categories.isEmpty()) Text("No categories loaded yet.", color = Vanta.TextDim, fontSize = 13.sp)
                    pc.categories.forEach { c ->
                        val on = allowed.any { it.providerId == pc.provider.id && it.categoryRemoteId == c.remoteId }
                        FocusCard(
                            onClick = { scope.launch { vm.repo.setKidsCategory(profileId, pc.provider.id, c.remoteId, !on) } },
                            selected = on, modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text((if (on) "☑  " else "☐  ") + c.name, color = if (on) Vanta.Cyan else Vanta.Text)
                        }
                    }
                }
            }

            error?.let { Text(it, color = Vanta.Error) }
            GradientButton(
                when {
                    first -> "Continue"
                    existing == null -> "Create profile"
                    else -> "Save"
                },
                onClick = ::save,
                modifier = Modifier.fillMaxWidth(),
            )
            if (existing != null && profiles.size > 1 && !isLastStandard) {
                FocusCard(onClick = {
                    scope.launch {
                        try { vm.repo.delete(existing!!.id); onDone(0L) } catch (e: Exception) { error = e.message }
                    }
                }) { Text("Delete profile", color = Vanta.Error) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private val Color_Yellow = androidx.compose.ui.graphics.Color(0xFFFFC81A)

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Vanta.Text, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Vanta.TextDim, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Vanta.Accent, checkedThumbColor = androidx.compose.ui.graphics.Color.White),
        )
    }
}

@Composable
private fun PinField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(4)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
}
