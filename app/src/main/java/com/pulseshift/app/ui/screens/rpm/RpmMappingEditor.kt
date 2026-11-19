package com.pulseshift.app.ui.screens.rpm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pulseshift.app.domain.model.ConfigSnapshot
import com.pulseshift.app.domain.model.RgbColor
import com.pulseshift.app.domain.model.RpmMapping
import com.pulseshift.app.domain.model.RpmMappingMode
import com.pulseshift.app.domain.model.distributedThresholds
import com.pulseshift.app.domain.model.ledZoneForPixel
import com.pulseshift.app.ui.screens.lighting.RpmNumberField
import com.pulseshift.app.ui.screens.lighting.StripPixelRail
import com.pulseshift.app.ui.screens.lighting.atBrightness
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun RpmMappingEditor(
    snapshot: ConfigSnapshot,
    authenticated: Boolean,
    onSave: suspend (RpmMapping, Int) -> Boolean,
    onTest: suspend (Int) -> Boolean
) {
    val setup = snapshot.lightingSetup ?: return
    val physical = snapshot.strip?.runningCount ?: return
    val active = setup.mapping.activeCount(minOf(physical, setup.installedLedCount))
    val configuredActive = setup.mapping.activeCount(setup.installedLedCount)
    val mappedActive = if (active == configuredActive) setup.mapping.activeLedCount else active
    if (configuredActive > active && setup.mapping.mode != RpmMappingMode.LEGACY_GROUPED &&
        setup.mapping.mode != RpmMappingMode.AUTOMATIC) {
        Text("Restart the ESP32 to edit the saved $configuredActive-pixel mapping. The running strip currently controls $active active LEDs.")
        return
    }
    val startingPerLed = setup.mapping.expand(active, snapshot.rpmThresholds)
    val original = if (setup.mapping.mode == RpmMappingMode.LEGACY_GROUPED) {
        val groups = (0..5).map { stage -> (0 until active).count { ledZoneForPixel(it, active) == stage } }
        RpmMapping(RpmMappingMode.GROUPED, active,
            snapshot.rpmThresholds.filterIndexed { index, _ -> groups[index] > 0 }, groups.filter { it > 0 })
    } else setup.mapping
    val source = listOf(original, snapshot.warningRpm, physical)
    var modeIndex by rememberSaveable(source) { mutableIntStateOf(original.mode.ordinal) }
    var values by rememberSaveable(source) { mutableStateOf(original.thresholds.joinToString(",")) }
    var groupInput by rememberSaveable(source) { mutableStateOf(original.groupSizes.joinToString(",")) }
    var minimum by rememberSaveable(source) { mutableStateOf(original.thresholds.first().toString()) }
    var maximum by rememberSaveable(source) { mutableStateOf(original.thresholds.last().toString()) }
    var warning by rememberSaveable(source) { mutableStateOf(setup.warningRpm.toString()) }
    var selected by rememberSaveable(source) { mutableStateOf("1") }
    var lastSelected by rememberSaveable(source) { mutableStateOf("1") }
    var batchValue by rememberSaveable(source) { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var sample by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val mode = RpmMappingMode.entries[modeIndex]
    val editable = authenticated && !busy
    val min = minimum.toIntOrNull()
    val max = maximum.toIntOrNull()
    val warningRpm = warning.toIntOrNull()
    val rangeValid = min != null && max != null && min <= max && max <= (warningRpm ?: -1)
    val levels = values.decimalList()
    val groups = groupInput.decimalList()
    val selectedIndex = (selected.toIntOrNull() ?: 0) - 1
    val endIndex = (lastSelected.toIntOrNull() ?: 0) - 1
    val mapping = when (mode) {
        RpmMappingMode.AUTOMATIC -> RpmMapping(mode, setup.mapping.activeLedCount, listOf(min ?: -1, max ?: -1))
        RpmMappingMode.INDIVIDUAL -> RpmMapping(mode, mappedActive, levels)
        else -> RpmMapping(mode, mappedActive, levels, groups)
    }
    val draftValid = warningRpm != null && mapping.valid(setup.installedLedCount, warningRpm)

    fun changeMode(next: RpmMappingMode) {
        val expanded = if (draftValid) mapping.expand(active, snapshot.rpmThresholds) else startingPerLed
        modeIndex = next.ordinal
        minimum = expanded.first().toString()
        maximum = expanded.last().toString()
        selected = "1"
        lastSelected = "1"
        when (next) {
            RpmMappingMode.INDIVIDUAL -> values = expanded.joinToString(",")
            RpmMappingMode.GROUPED -> {
                val sizes = defaultGroups(active)
                applyGroupLayout(sizes, expanded) { values = it.joinToString(",") }
                groupInput = sizes.joinToString(",")
            }
            else -> Unit
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("RPM Mapping", style = MaterialTheme.typography.titleLarge)
            Text("$active active LEDs · ${original.mode.label} on controller")
            OutlinedButton(onClick = { menu = true }, enabled = editable) { Text(mode.label) }
            DropdownMenu(menu, { menu = false }) {
                RpmMappingMode.entries.filter { it != RpmMappingMode.LEGACY_GROUPED }.forEach { option ->
                    DropdownMenuItem(text = { Text(option.label) }, onClick = {
                        if (mode != option) changeMode(option)
                        menu = false
                    })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RpmNumberField("Minimum RPM", minimum, { minimum = it }, Modifier.weight(1f), editable)
                RpmNumberField("Maximum RPM", maximum, { maximum = it }, Modifier.weight(1f), editable)
            }
            RpmNumberField("Warning RPM", warning, { warning = it }, enabled = editable)
            if (mode != RpmMappingMode.AUTOMATIC) {
                if (mode == RpmMappingMode.GROUPED) {
                    OutlinedTextField(groupInput, { candidate ->
                        if (candidate.length <= 192 && candidate.all { it in '0'..'9' || it == ',' || it == ' ' }) {
                            groupInput = candidate
                            val layout = candidate.decimalList()
                            if (layout.isNotEmpty() && layout.all { it in 1..active } && layout.sum() == active) {
                                val current = if (draftValid) mapping.expand(active, snapshot.rpmThresholds) else startingPerLed
                                applyGroupLayout(layout, current) { values = it.joinToString(",") }
                            }
                        }
                    }, label = { Text("LEDs per group, comma separated") }, singleLine = true, enabled = editable,
                        supportingText = { Text("Group sizes must add up to $active.") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
                }
                Button(enabled = editable && rangeValid && (mode == RpmMappingMode.INDIVIDUAL || groups.isNotEmpty()),
                    onClick = {
                        val count = if (mode == RpmMappingMode.INDIVIDUAL) active else groups.size
                        values = distributedThresholds(count, min!!, max!!).joinToString(",")
                    }) { Text("Evenly Distribute Thresholds") }
                val selectedRpm = levels.getOrNull(selectedIndex)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val unit = if (mode == RpmMappingMode.GROUPED) "Group" else "LED"
                    RpmNumberField("$unit (1–${levels.size})", selected, { selected = it },
                        Modifier.weight(1f), editable, 2)
                    RpmNumberField("$unit threshold", selectedRpm?.takeUnless { it < 0 }?.toString().orEmpty(),
                        { number ->
                            values = levels.mapIndexed { index, value ->
                                if (index == selectedIndex) number.toIntOrNull() ?: -1 else value
                            }.joinToString(",")
                        }, Modifier.weight(1f), editable && selectedRpm != null)
                }
                if (mode == RpmMappingMode.INDIVIDUAL) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RpmNumberField("Through LED", lastSelected, { lastSelected = it }, Modifier.weight(1f), editable, 2)
                        RpmNumberField("Batch RPM", batchValue, { batchValue = it }, Modifier.weight(1f), editable)
                    }
                    TextButton(enabled = editable && selectedIndex in levels.indices &&
                        endIndex in selectedIndex until levels.size && (batchValue.toIntOrNull() ?: -1) in 0..(warningRpm ?: -1),
                        onClick = { values = levels.mapIndexed { index, rpm ->
                            if (index in selectedIndex..endIndex) batchValue.toInt() else rpm
                        }.joinToString(",") }) { Text("Apply Threshold to Selected LEDs") }
                }
            }
            if (!draftValid) Text("Use nondecreasing thresholds up to the warning RPM (0–20,000).",
                color = MaterialTheme.colorScheme.error)
            TextButton(enabled = editable, onClick = {
                modeIndex = RpmMappingMode.AUTOMATIC.ordinal
                minimum = "1000"
                maximum = "6000"
                warning = "6500"
            }) { Text("Reset Mapping") }
            Button(enabled = editable && draftValid, onClick = {
                busy = true
                scope.launch {
                    try {
                        message = if (onSave(mapping, warningRpm!!)) "RPM mapping saved and verified." else "RPM mapping save unconfirmed."
                    } finally { busy = false }
                }
            }) { Text(if (busy) "Saving…" else "Save Mapping to ESP32") }
            if (message.isNotEmpty()) Text(message)
        }
    }

    val previewMapping = if (draftValid) mapping else setup.mapping
    val previewWarning = if (draftValid) warningRpm!! else setup.warningRpm
    val engine = remember(snapshot, previewMapping, previewWarning) {
        RpmSimulation(snapshot, physical, previewMapping.expand(active, snapshot.rpmThresholds), previewWarning)
    }
    var pixels by remember(engine) { mutableStateOf(List(physical) { RgbColor(0, 0, 0) }) }
    LaunchedEffect(engine, sample) {
        do {
            pixels = engine.frame(sample, android.os.SystemClock.elapsedRealtime())
            if (!engine.warningLatched || snapshot.warningPatternId !in 0..1) break
            delay(25)
        } while (true)
    }
    Text("Simulated RPM · $sample", style = MaterialTheme.typography.titleMedium)
    Text("Phone preview uses the draft mapping. The controller bench test uses saved or currently applied settings.")
    StripPixelRail(pixels, "Simulated RPM strip preview")
    Slider(value = sample.toFloat(), onValueChange = { sample = it.toInt() }, valueRange = 0f..20_000f)
    OutlinedButton(enabled = authenticated && sample > 0 && !busy, onClick = {
        scope.launch { message = if (onTest(sample)) "Synthetic RPM test requested for ten seconds." else "Controller test unavailable." }
    }) { Text("Test Current Controller Mapping") }
}

private fun String.decimalList(): List<Int> =
    if (isBlank()) emptyList() else split(',').map { it.trim().toIntOrNull() ?: -1 }

private fun defaultGroups(count: Int): List<Int> =
    (0..5).map { zone -> (0 until count).count { ledZoneForPixel(it, count) == zone } }.filter { it > 0 }

private fun applyGroupLayout(sizes: List<Int>, thresholds: List<Int>, apply: (List<Int>) -> Unit) {
    var start = 0
    apply(sizes.map { size -> thresholds[start].also { start += size } })
}

internal class RpmSimulation(
    private val snapshot: ConfigSnapshot,
    private val physicalCount: Int,
    private val thresholds: List<Int>,
    private val warningRpm: Int
) {
    private val latches = BooleanArray(thresholds.size)
    var warningLatched = false
        private set
    private var warningStartMs = 0L

    fun frame(rpm: Int, now: Long): List<RgbColor> {
        val black = RgbColor(0, 0, 0)
        if (rpm == 0) {
            latches.fill(false)
            warningLatched = false
        }
        if (!snapshot.isPowerOn || rpm == 0) return List(physicalCount) { black }
        val hysteresis = snapshot.rpmHysteresis
        thresholds.forEachIndexed { index, threshold ->
            val off = (threshold - hysteresis).coerceAtLeast(0)
            latches[index] = rpm >= if (latches[index]) off else threshold
        }
        val warn = snapshot.warningPatternId != 3 &&
            rpm >= if (warningLatched) (warningRpm - hysteresis).coerceAtLeast(0) else warningRpm
        if (warn && !warningLatched) warningStartMs = now
        warningLatched = warn
        val phase = now - warningStartMs
        val warningOn = when (snapshot.warningPatternId) {
            0 -> (phase / 100) % 2 == 0L
            1 -> (phase / 50) % 2 == 0L
            else -> true
        }
        return List(physicalCount) { index ->
            val i = if (snapshot.ledDirection == 1) physicalCount - 1 - index else index
            when {
                i !in thresholds.indices -> black
                warn -> if (warningOn) snapshot.warningColor else black
                latches[i] -> snapshot.ledColors[ledZoneForPixel(i, thresholds.size)]
                else -> black
            }.atBrightness(snapshot.brightness)
        }
    }
}
