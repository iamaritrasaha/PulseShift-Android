package com.pulseshift.app.ui.screens.lighting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pulseshift.app.domain.model.AnimationSettings
import com.pulseshift.app.domain.model.AnimationStatus
import com.pulseshift.app.domain.model.ConfigSnapshot
import com.pulseshift.app.domain.model.StartupAnimation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun AnimationSetupPanel(
    snapshot: ConfigSnapshot,
    address: String?,
    authenticated: Boolean,
    queryStatus: suspend () -> AnimationStatus?,
    onSave: suspend (AnimationSettings, AnimationSettings) -> Boolean,
    onPreview: suspend (AnimationSettings) -> Boolean
) {
    val setup = snapshot.lightingSetup ?: return
    var bootEnabled by rememberSaveable(address, setup.boot.enabled) { mutableStateOf(setup.boot.enabled) }
    var bootId by rememberSaveable(address, setup.boot.pattern.id) { mutableStateOf(setup.boot.pattern.id) }
    var bootDuration by rememberSaveable(address, setup.boot.durationMs) { mutableStateOf(setup.boot.durationMs.toString()) }
    var ambientEnabled by rememberSaveable(address, setup.ambient.enabled) { mutableStateOf(setup.ambient.enabled) }
    var ambientId by rememberSaveable(address, setup.ambient.pattern.id) { mutableStateOf(setup.ambient.pattern.id) }
    var ambientDuration by rememberSaveable(address, setup.ambient.durationMs) { mutableStateOf(setup.ambient.durationMs.toString()) }
    var status by remember(address) { mutableStateOf<AnimationStatus?>(null) }
    var busy by remember(address) { mutableStateOf(false) }
    var feedback by remember(address) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(address, busy) {
        if (address == null || busy) return@LaunchedEffect
        while (true) {
            status = queryStatus()
            delay(1000)
        }
    }
    val boot = AnimationSettings(bootEnabled, StartupAnimation.entries.first { it.id == bootId }, bootDuration.toIntOrNull() ?: 0)
    val ambient = AnimationSettings(ambientEnabled, StartupAnimation.entries.first { it.id == ambientId }, ambientDuration.toIntOrNull() ?: 0)
    val editable = authenticated && !busy
    val canPreview = editable && status?.canPreview == true

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
        AnimationSettingsCard("Boot Animation", setup.boot, boot, bootDuration, "Duration (ms)", editable,
            canPreview, { bootEnabled = it }, { bootId = it.id }, { bootDuration = it },
            onPreview = {
                scope.launch {
                    feedback = if (onPreview(boot)) "Boot preview requested." else "Controller preview unavailable."
                }
            })
        AnimationSettingsCard("Continuous Ambient Animation", setup.ambient, ambient, ambientDuration,
            "Cycle duration (ms)", editable, canPreview,
            { ambientEnabled = it }, { ambientId = it.id }, { ambientDuration = it },
            onPreview = {
                scope.launch {
                    feedback = if (onPreview(ambient)) "Ambient preview requested." else "Controller preview unavailable."
                }
            }) {
            Text("Controller status · ${status?.playback?.label ?: "Waiting for controller"}")
            Text("Plays after boot on a healthy, quiet controller before any CAN traffic, or while fresh RPM confirms zero. Engine RPM pauses playback. CAN faults and lost or invalid RPM after CAN activity suppress it.",
                style = MaterialTheme.typography.bodySmall)
        }
        if (snapshot.matchesFlash != true) Text("Current settings are awaiting flash verification.")
        if (!canPreview) Text("Controller preview requires owner access, lighting power, and safe idle conditions.",
            style = MaterialTheme.typography.bodySmall)
        Button(enabled = editable && boot.valid && ambient.valid && (snapshot.matchesFlash != true || boot != setup.boot || ambient != setup.ambient),
            onClick = {
                busy = true
                scope.launch {
                    try {
                        feedback = if (onSave(boot, ambient)) "Animation settings saved and verified."
                                   else "Animation save unconfirmed."
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "Saving…" else "Save Animations to ESP32") }
        if (feedback.isNotEmpty()) Text(feedback)
    }
}

@Composable
private fun AnimationSettingsCard(
    title: String,
    saved: AnimationSettings,
    draft: AnimationSettings,
    duration: String,
    durationLabel: String,
    editable: Boolean,
    canPreview: Boolean,
    onToggle: (Boolean) -> Unit,
    onPattern: (StartupAnimation) -> Unit,
    onDuration: (String) -> Unit,
    onPreview: () -> Unit,
    footer: @Composable () -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text("Controller setting · ${if (saved.enabled) "On" else "Off"}", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (draft == saved) "Enabled" else "Enabled · unsaved edit", Modifier.weight(1f))
                Switch(checked = draft.enabled, onCheckedChange = onToggle, enabled = editable)
            }
            OutlinedButton(onClick = { expanded = true }, enabled = editable) { Text(draft.pattern.displayName) }
            DropdownMenu(expanded, { expanded = false }) {
                StartupAnimation.entries.filter { it != StartupAnimation.NONE }.forEach { pattern ->
                    DropdownMenuItem(text = { Text(pattern.displayName) }, onClick = { onPattern(pattern); expanded = false })
                }
            }
            RpmNumberField(durationLabel, duration, onDuration, enabled = editable)
            if (!draft.valid) Text("Enter 100–10,000 ms.", color = MaterialTheme.colorScheme.error)
            FilledTonalButton(onClick = onPreview, enabled = canPreview && draft.valid) { Text("Preview Animation") }
            footer()
        }
    }
}
