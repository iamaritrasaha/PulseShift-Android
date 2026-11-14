package com.pulseshift.app.ui.screens.lighting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Warning
import com.pulseshift.app.domain.model.Preset
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pulseshift.app.domain.model.ConfigSnapshot
import com.pulseshift.app.domain.model.EffectType
import com.pulseshift.app.domain.model.LightingState
import com.pulseshift.app.domain.model.RgbColor
import com.pulseshift.app.domain.model.RpmTelemetryState
import com.pulseshift.app.domain.model.ShiftLightConfig
import com.pulseshift.app.domain.model.StartupAnimation
import com.pulseshift.app.domain.model.WarningStyle
import com.pulseshift.app.ui.components.HsvColorWheel
import com.pulseshift.app.ui.components.LedStripPreview
import com.pulseshift.app.ui.components.ShiftLightBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LightingScreen(
    viewModel: LightingViewModel
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Colors", "Effects", "Shift Thresholds", "Startup Animation", "Presets")

    val lightingState by viewModel.lightingState.collectAsState()
    val shiftLightConfig by viewModel.shiftLightConfig.collectAsState()
    val configSnapshot by viewModel.configSnapshot.collectAsState()
    val hasUnsavedChanges by viewModel.hasUnsavedChanges.collectAsState()
    val selectedLedIndex by viewModel.selectedLedIndex.collectAsState()
    val rpmTelemetry by viewModel.rpmTelemetryState.collectAsState()
    val connectedDevice by viewModel.connectedDevice.collectAsState()
    val presets by viewModel.presets.collectAsState()

    val isV2Supported = connectedDevice?.capabilities?.hasConfigV2Support == true

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Lighting & Hardware Config", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = if (connectedDevice != null && !isV2Supported) "Monitoring-Only Firmware (V1)"
                                   else if (lightingState.isHardwareConfirmed) "Confirmed on ESP32"
                                   else if (lightingState.isPendingSync) "Syncing..."
                                   else "Local Draft",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (lightingState.isHardwareConfirmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                    }
                },
                actions = {
                    if (hasUnsavedChanges && isV2Supported) {
                        IconButton(onClick = { viewModel.saveToController() }) {
                            Icon(Icons.Rounded.Save, contentDescription = "Save to ESP32 Flash")
                        }
                    }

                    if (isV2Supported) {
                        IconButton(onClick = { viewModel.reloadFromController() }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Reload Snapshot")
                        }

                        IconButton(onClick = { viewModel.restoreControllerDefaults() }) {
                            Icon(Icons.Rounded.Restore, contentDescription = "Restore Defaults")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary,
                edgePadding = 16.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }

            // Firmware Update Required Banner for BLE V1 Controllers
            if (connectedDevice != null && !isV2Supported) {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Column {
                            Text(
                                text = "Firmware update required for hardware configuration.",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "Connected controller is running BLE v1 monitoring firmware. Physical controls are disabled until updated to PulseShift BLE Config V2.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f)
                            )
                        }
                    }
                }
            }

            // Unsaved Changes Banner
            if (hasUnsavedChanges) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Unsaved Changes — Tap Save to write to ESP32 flash",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        FilledTonalButton(
                            onClick = { viewModel.saveToController() },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Save NVS", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // Interactive 6-LED Preview Strip
            Box(modifier = Modifier.padding(16.dp)) {
                LedStripPreview(
                    colors = lightingState.ledColors,
                    isPowerOn = lightingState.isPowerOn,
                    brightness = lightingState.brightness,
                    selectedIndex = if (selectedTab == 0) selectedLedIndex else null,
                    onSelectLed = { viewModel.selectLed(it) }
                )
            }

            // Tab Content
            when (selectedTab) {
                0 -> ColorsTabContent(
                    selectedLedIndex = selectedLedIndex,
                    lightingState = lightingState,
                    onSelectLed = { viewModel.selectLed(it) },
                    onColorChange = { viewModel.setColor(it) },
                    onBrightnessChange = { viewModel.setBrightness(it) },
                    onPowerChange = { viewModel.setPower(it) }
                )
                1 -> EffectsTabContent(
                    currentEffect = lightingState.effect,
                    speed = lightingState.effectSpeed,
                    direction = configSnapshot?.ledDirection ?: 0,
                    onSelectEffect = { viewModel.setEffect(it) },
                    onSpeedChange = { viewModel.setEffectSpeed(it) },
                    onDirectionChange = { viewModel.setLedDirection(it) }
                )
                2 -> ShiftThresholdsTabContent(
                    config = shiftLightConfig,
                    onThresholdChange = { idx, rpm -> viewModel.updateThreshold(idx, rpm) },
                    onWarningChange = { rpm, style, color -> viewModel.setShiftWarning(rpm, style, color) }
                )
                3 -> StartupAnimationTabContent(
                    currentAnimation = configSnapshot?.startupAnimation ?: StartupAnimation.SWEEP,
                    durationMs = configSnapshot?.startupDurationMs ?: 2000,
                    onSelectAnimation = { anim, dur -> viewModel.setStartupAnimation(anim, dur) }
                )
                4 -> PresetsTabContent(
                    presets = presets,
                    onApplyPreset = { viewModel.applyPreset(it) },
                    onDeletePreset = { viewModel.deletePreset(it) }
                )
            }
        }
    }
}

@Composable
fun ColorsTabContent(
    selectedLedIndex: Int,
    lightingState: LightingState,
    onSelectLed: (Int) -> Unit,
    onColorChange: (RgbColor) -> Unit,
    onBrightnessChange: (Int) -> Unit,
    onPowerChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Master Power Toggle
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Master LED Power", style = MaterialTheme.typography.titleMedium)
                    Text(if (lightingState.isPowerOn) "LEDs Active" else "LEDs Off", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Switch(
                    checked = lightingState.isPowerOn,
                    onCheckedChange = onPowerChange
                )
            }
        }

        // LED Selector Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Target LEDs:", style = MaterialTheme.typography.labelMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (selectedLedIndex == -1) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable { onSelectLed(-1) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "All (6)",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selectedLedIndex == -1) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    )
                }

                (0..5).forEach { i ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (selectedLedIndex == i) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable { onSelectLed(i) }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "#${i + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selectedLedIndex == i) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // Color Picker
        val activeColor = if (selectedLedIndex == -1) lightingState.globalColor else lightingState.ledColors.getOrElse(selectedLedIndex) { RgbColor.BrandViolet }
        HsvColorWheel(
            selectedColor = activeColor,
            onColorChanged = onColorChange
        )

        // Master Brightness Slider
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Master Brightness", style = MaterialTheme.typography.labelMedium)
                    Text("${lightingState.brightnessPercent}%", style = MaterialTheme.typography.labelMedium)
                }
                Slider(
                    value = lightingState.brightness.toFloat(),
                    onValueChange = { onBrightnessChange(it.toInt()) },
                    valueRange = 0f..255f
                )
            }
        }
    }
}

@Composable
fun EffectsTabContent(
    currentEffect: EffectType,
    speed: Int,
    direction: Int,
    onSelectEffect: (EffectType) -> Unit,
    onSpeedChange: (Int) -> Unit,
    onDirectionChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Animation Speed & Direction Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Animation Speed", style = MaterialTheme.typography.labelMedium)
                    Text("$speed%", style = MaterialTheme.typography.labelMedium)
                }
                Slider(
                    value = speed.toFloat(),
                    onValueChange = { onSpeedChange(it.toInt()) },
                    valueRange = 0f..100f
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("LED Flow Direction", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = direction == 0,
                            onClick = { onDirectionChange(0) },
                            label = { Text("Normal (1->6)") }
                        )
                        FilterChip(
                            selected = direction == 1,
                            onClick = { onDirectionChange(1) },
                            label = { Text("Reverse (6->1)") }
                        )
                    }
                }
            }
        }

        Text("Physical Lighting Effects", style = MaterialTheme.typography.titleMedium)

        EffectType.entries.forEach { effect ->
            val isSelected = currentEffect == effect
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectEffect(effect) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = effect.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = effect.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ShiftThresholdsTabContent(
    config: ShiftLightConfig,
    onThresholdChange: (Int, Int) -> Unit,
    onWarningChange: (Int, WarningStyle, RgbColor) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("6-LED Progressive Thresholds", style = MaterialTheme.typography.titleMedium)

        config.thresholds.forEachIndexed { index, rpm ->
            ThresholdEditorItem(
                index = index,
                rpm = rpm,
                onThresholdChange = onThresholdChange
            )
        }

        Text("Shift Warning & Over-rev", style = MaterialTheme.typography.titleMedium)

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Warning Threshold", style = MaterialTheme.typography.bodyMedium)
                    Text("${config.warningRpm} RPM", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                }

                Slider(
                    value = config.warningRpm.toFloat(),
                    onValueChange = { onWarningChange(it.toInt(), config.warningStyle, config.colors.getOrElse(5) { RgbColor.RacingRed }) },
                    valueRange = 5000f..14000f
                )

                Text("Warning Pattern", style = MaterialTheme.typography.labelMedium)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WarningStyle.entries.forEach { style ->
                        FilterChip(
                            selected = config.warningStyle == style,
                            onClick = { onWarningChange(config.warningRpm, style, config.colors.getOrElse(5) { RgbColor.RacingRed }) },
                            label = { Text(style.label) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ThresholdEditorItem(
    index: Int,
    rpm: Int,
    onThresholdChange: (Int, Int) -> Unit
) {
    var localRpm by remember(rpm) { mutableFloatStateOf(rpm.toFloat()) }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("LED #${index + 1} Threshold", style = MaterialTheme.typography.bodyMedium)
                Text("${localRpm.toInt()} RPM", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = localRpm,
                onValueChange = { localRpm = it },
                onValueChangeFinished = { onThresholdChange(index, localRpm.toInt()) },
                valueRange = 1000f..12000f
            )
        }
    }
}

@Composable
fun StartupAnimationTabContent(
    currentAnimation: StartupAnimation,
    durationMs: Int,
    onSelectAnimation: (StartupAnimation, Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Startup Ignition Greeting", style = MaterialTheme.typography.titleMedium)
                Text("Plays once when the ESP32 powers on with key ACC ignition", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Duration: ${durationMs / 1000f}s", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = durationMs.toFloat(),
                        onValueChange = { onSelectAnimation(currentAnimation, it.toInt()) },
                        valueRange = 500f..5000f,
                        modifier = Modifier.weight(1f).padding(start = 16.dp)
                    )
                }

                Text("Choose Startup Animation", style = MaterialTheme.typography.labelLarge)

                StartupAnimation.entries.forEach { animation ->
                    val isChosen = currentAnimation == animation
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isChosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectAnimation(animation, durationMs) }
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = animation.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (isChosen) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = animation.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isChosen) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (isChosen) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PresetsTabContent(
    presets: List<Preset>,
    onApplyPreset: (Preset) -> Unit,
    onDeletePreset: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Saved Lighting & Shift-Light Presets", style = MaterialTheme.typography.titleMedium)

        presets.forEach { preset ->
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(preset.name, style = MaterialTheme.typography.titleMedium)
                            Text("Effect: ${preset.effect.displayName} · Brightness: ${preset.brightness}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (preset.isCustom) {
                            IconButton(onClick = { onDeletePreset(preset.id) }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Delete Preset", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Effect: ${preset.effect.displayName} · ${preset.brightness}% Brightness", style = MaterialTheme.typography.labelSmall)
                        Button(onClick = { onApplyPreset(preset) }) {
                            Text("Apply Preset")
                        }
                    }
                }
            }
        }
    }
}
