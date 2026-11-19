package com.pulseshift.app.ui.screens.lighting

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pulseshift.app.domain.model.RgbColor
import com.pulseshift.app.domain.model.ledZoneForPixel
import com.pulseshift.app.ui.screens.settings.SettingsViewModel
import java.util.Locale

@Composable
fun StripSetupPanel(viewModel: SettingsViewModel) {
    val device by viewModel.connectedDevice.collectAsState()
    val snapshot by viewModel.configSnapshot.collectAsState()
    val authenticated by viewModel.isAuthenticated.collectAsState()
    val save by viewModel.stripSave.collectAsState()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Strip Setup", style = MaterialTheme.typography.titleLarge)
            val config = snapshot
            val strip = config?.strip
            if (device?.isConnected != true || strip == null) {
                Text(if (device?.isConnected == true && device?.capabilities?.supportsStripConfig != true)
                    "This firmware uses a fixed strip size." else "Connect to an ESP32 to read the running strip size.")
                return@Column
            }
            val setup = config.lightingSetup
            val running = strip.runningCount
            val runningActive = setup?.mapping?.activeCount(minOf(running, strip.configuredCount)) ?: running
            val savedCount = config.savedInstalledLedCount
                ?: strip.configuredCount.takeIf { config.matchesFlash == true }
            val editable = authenticated && save != SettingsViewModel.StripSaveState.Saving
            var installedText by rememberSaveable(device?.address, strip.configuredCount) {
                mutableStateOf(strip.configuredCount.toString())
            }
            var activeText by rememberSaveable(device?.address, runningActive) { mutableStateOf(runningActive.toString()) }
            var useAll by rememberSaveable(device?.address, setup?.mapping?.activeLedCount) {
                mutableStateOf(setup?.mapping?.activeLedCount == 0)
            }
            val installed = installedText.toIntOrNull()
            val maximumActive = minOf(running, installed?.takeIf { it in strip.range } ?: strip.configuredCount)
            val active = activeText.toIntOrNull()
            val activeIsValid = useAll || (active != null && active in 1..maximumActive)
            val reversed = config.ledDirection == 1

            Text("Running ${strip.runningCount} LEDs · Last verified boot count ${savedCount ?: "unconfirmed"}")
            Text("Enabled now $runningActive of $running · GRB · ${if (reversed) "Reverse" else "Normal"}")
            Text(if (reversed) "The active segment starts at the far end of the strip."
                 else "The active segment starts at the GPIO27 data-input end.")
            val previewActive = if (useAll) maximumActive else active?.coerceIn(1, maximumActive.coerceAtLeast(1)) ?: runningActive
            val colors = List(running) { pixel ->
                val index = if (reversed) running - 1 - pixel else pixel
                if (index >= previewActive || !config.isPowerOn) RgbColor(0, 0, 0)
                else config.ledColors[ledZoneForPixel(index, previewActive)].atBrightness(config.brightness)
            }
            StripPixelRail(colors, "Strip layout preview with $previewActive active pixels")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RpmNumberField("Installed LEDs", installedText, { installedText = it },
                    Modifier.weight(1f), editable, 2)
                if (setup != null && !useAll) RpmNumberField("Active LEDs", activeText, { activeText = it },
                    Modifier.weight(1f), editable, 2)
            }
            if (installed != null && installed in strip.range) {
                Text(String.format(Locale.US, "Supply budget up to %.2f A at 5 V for full white.", installed * 0.06))
                if (installed != running || (savedCount != null && savedCount != running)) {
                    Text("The running count changes after a verified save and controller reboot.")
                }
            }
            if (setup != null) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Use all installed LEDs", Modifier.weight(1f))
                    Switch(checked = useAll, onCheckedChange = { useAll = it }, enabled = editable)
                }
                if (!useAll) Text("Active count range 1–$maximumActive with the running strip.")
            }
            if (!authenticated) Text("Authenticate as owner to save.")
            val valid = installed != null && installed in strip.range && (setup == null || activeIsValid)
            Button(
                enabled = editable && valid,
                onClick = {
                    val count = installed ?: return@Button
                    val override = if (useAll) 0 else active
                    viewModel.saveLedCount(count, override.takeIf { setup != null })
                }
            ) { Text(if (save == SettingsViewModel.StripSaveState.Saving) "Saving…" else "Save Strip Setup") }
            when (save) {
                SettingsViewModel.StripSaveState.Saved -> Text("Strip settings saved and verified.")
                SettingsViewModel.StripSaveState.Unconfirmed -> Text("Strip save unconfirmed.")
                else -> Unit
            }
        }
    }
}

@Composable
internal fun StripPixelRail(colors: List<RgbColor>, description: String) {
    val blank = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(28.dp).semantics { contentDescription = description }) {
        if (colors.isEmpty()) return@Canvas
        val step = size.width / colors.size
        val radius = minOf(step * 0.38f, size.height * 0.34f)
        colors.forEachIndexed { index, rgb ->
            val dark = (rgb.red or rgb.green or rgb.blue) == 0
            drawCircle(if (dark) blank else Color(rgb.red, rgb.green, rgb.blue),
                radius, Offset(step * (index + 0.5f), size.height / 2))
        }
    }
}

internal fun RgbColor.atBrightness(brightness: Int): RgbColor =
    RgbColor((red * brightness + 127) / 255, (green * brightness + 127) / 255, (blue * brightness + 127) / 255)

@Composable
internal fun RpmNumberField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    digits: Int = 5
) {
    OutlinedTextField(value, { if (it.length <= digits && it.all { digit -> digit in '0'..'9' }) onChange(it) },
        modifier = modifier, label = { Text(label) }, enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
}
