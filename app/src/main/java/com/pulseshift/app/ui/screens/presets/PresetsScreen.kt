package com.pulseshift.app.ui.screens.presets

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import com.pulseshift.app.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pulseshift.app.domain.model.EffectType
import com.pulseshift.app.domain.model.Preset
import com.pulseshift.app.domain.model.RgbColor
import com.pulseshift.app.domain.model.ShiftLightConfig
import com.pulseshift.app.ui.components.ConfirmDialog
import com.pulseshift.app.ui.components.StatusPill
import com.pulseshift.app.ui.components.StepperControl
import com.pulseshift.app.ui.theme.DarkTextSecondary
import com.pulseshift.app.ui.theme.HardwareAmberWarning
import com.pulseshift.app.ui.theme.HardwareGreenVerified
import com.pulseshift.app.ui.theme.HardwareRedError
import com.pulseshift.app.ui.theme.PulseShiftMonoMedium
import com.pulseshift.app.ui.theme.PulseShiftMonoSmall
import com.pulseshift.app.ui.theme.PulseShiftMonoTiny
import com.pulseshift.app.ui.theme.PulseShiftSpacing

import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material3.FilledTonalButton
import com.pulseshift.app.ui.theme.PulseShiftRadius
import com.pulseshift.app.ui.theme.PulseShiftShapeTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetsScreen(
    viewModel: PresetsViewModel
) {
    val presets by viewModel.presets.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val lightingState by viewModel.lightingState.collectAsState()
    val isConnected = connectionState in setOf(
        com.pulseshift.app.data.ble.BleConnectionState.READY,
        com.pulseshift.app.data.ble.BleConnectionState.RECEIVING_TELEMETRY,
        com.pulseshift.app.data.ble.BleConnectionState.CONNECTED_WAITING_FOR_DATA
    )
    val snackbar = remember { SnackbarHostState() }

    var showCreateSheet by remember { mutableStateOf(false) }
    var presetToRename by remember { mutableStateOf<Preset?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var confirmDeletePreset by remember { mutableStateOf<Preset?>(null) }

    LaunchedEffect(Unit) {
        viewModel.userMessage.collect { snackbar.showSnackbar(it) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            if (isBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = PulseShiftSpacing.ScreenHorizontal,
                        end = PulseShiftSpacing.ScreenHorizontal,
                        top = PulseShiftSpacing.ScreenVertical,
                        bottom = PulseShiftSpacing.SmallGap
                    ),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Presets Library",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(PulseShiftSpacing.SmallGap),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = { showRestoreDialog = true },
                        shape = PulseShiftShapeTokens.Control,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.heightIn(min = 38.dp)
                    ) {
                        Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(PulseShiftSpacing.Micro))
                        Text("Defaults", style = MaterialTheme.typography.labelSmall)
                    }

                    Button(
                        onClick = { showCreateSheet = true },
                        shape = PulseShiftShapeTokens.Control,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.heightIn(min = 38.dp)
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(PulseShiftSpacing.Micro))
                        Text("New", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            if (presets.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(PulseShiftSpacing.ScreenHorizontal),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.RelatedControls)
                    ) {
                        Icon(
                            Icons.Rounded.Bookmarks,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        )
                        Text(
                            "No presets stored",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { viewModel.restoreDefaults() },
                            shape = PulseShiftShapeTokens.Control,
                            modifier = Modifier.heightIn(min = PulseShiftSpacing.MinTouchTarget)
                        ) {
                            Text("Load Shipped Defaults")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = PulseShiftSpacing.ScreenHorizontal,
                        end = PulseShiftSpacing.ScreenHorizontal,
                        top = PulseShiftSpacing.SmallGap,
                        bottom = PulseShiftSpacing.BottomScrollPadding
                    ),
                    verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.RelatedControls)
                ) {
                    items(presets, key = { it.id }) { preset ->
                        val isActive = isConnected && lightingState.matchesPreset(preset)
                        PresetListItem(
                            preset = preset,
                            isConnected = isConnected,
                            isActiveOnController = isActive,
                            isHardwareConfirmed = lightingState.isHardwareConfirmed,
                            isBusy = isBusy,
                            onApply = { viewModel.applyPreset(preset) },
                            onDuplicate = { viewModel.duplicatePreset(preset) },
                            onRename = if (preset.isCustom) {
                                {
                                    presetToRename = preset
                                    renameInput = preset.name
                                }
                            } else null,
                            onDelete = if (preset.isCustom) {
                                { confirmDeletePreset = preset }
                            } else null
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )
    }

    // Rename Dialog
    presetToRename?.let { target ->
        AlertDialog(
            onDismissRequest = { presetToRename = null },
            title = { Text("Rename Preset") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("Preset Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (renameInput.isNotBlank()) {
                            viewModel.renamePreset(target, renameInput.trim())
                            presetToRename = null
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { presetToRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Confirmation Dialog
    confirmDeletePreset?.let { target ->
        ConfirmDialog(
            title = "Delete \"${target.name}\"?",
            text = "This removes the preset from phone storage permanently. Active controller settings are not affected.",
            confirmLabel = "Delete",
            onConfirm = {
                viewModel.deletePreset(target.id)
                confirmDeletePreset = null
            },
            onDismiss = { confirmDeletePreset = null }
        )
    }

    // Restore Defaults Dialog
    if (showRestoreDialog) {
        ConfirmDialog(
            title = "Restore Default Presets?",
            text = "Reset the presets list back to factory defaults? Custom presets will be removed from local storage.",
            confirmLabel = "Restore",
            onConfirm = {
                viewModel.restoreDefaults()
                showRestoreDialog = false
            },
            onDismiss = { showRestoreDialog = false }
        )
    }

    // Modal Bottom Sheet for Creating Preset
    if (showCreateSheet) {
        CreatePresetSheet(
            onDismiss = { showCreateSheet = false },
            onCreate = { name, effect, colors, brightness, speed, shiftConfig ->
                viewModel.createPreset(
                    name = name,
                    effect = effect,
                    colors = colors,
                    brightness = brightness,
                    speed = speed,
                    shiftConfig = shiftConfig
                )
                showCreateSheet = false
            }
        )
    }
}

@Composable
fun PresetListItem(
    preset: Preset,
    isConnected: Boolean = false,
    isActiveOnController: Boolean = false,
    isHardwareConfirmed: Boolean = false,
    isBusy: Boolean = false,
    onApply: () -> Unit,
    onDuplicate: () -> Unit,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(PulseShiftSpacing.CardPadding),
            verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.RelatedControls)
        ) {
            // Header Row: Name & Tag
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = preset.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(PulseShiftSpacing.Micro))
                        // Single wrapping text: multiple Row siblings squeezed the
                        // RPM-range tail to zero width and wrapped it one
                        // character per line, inflating the card.
                        val subtitle = buildString {
                            append(preset.effect.displayName)
                            append("  ·  ${preset.brightness * 100 / 255}% Brightness")
                            preset.shiftLightConfig?.let { cfg ->
                                append("  ·  ${cfg.thresholds.first()}–${cfg.thresholds.last()} RPM")
                            }
                        }
                        Text(
                            text = subtitle,
                            style = PulseShiftMonoTiny,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }

                val statusText: String
                val statusColor: Color
                when {
                    isActiveOnController && isHardwareConfirmed -> {
                        statusText = "ACTIVE ON ESP32 (NVS)"
                        statusColor = HardwareGreenVerified
                    }
                    isActiveOnController -> {
                        statusText = "ACTIVE IN RAM (UNSAVED)"
                        statusColor = HardwareAmberWarning
                    }
                    preset.isCustom -> {
                        statusText = "STORED ON PHONE"
                        statusColor = MaterialTheme.colorScheme.primary
                    }
                    else -> {
                        statusText = "BUILT-IN"
                        statusColor = MaterialTheme.colorScheme.outline
                    }
                }
                StatusPill(
                    text = statusText,
                    color = statusColor
                )
            }

            // 6-LED Visual Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                preset.ledColors.take(6).forEach { c ->
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Color(c.red, c.green, c.blue))
                            .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                    )
                }
            }

            // Actions Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onApply,
                    enabled = isConnected && !isBusy,
                    modifier = Modifier.heightIn(min = PulseShiftSpacing.MinTouchTarget),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(PulseShiftSpacing.SmallLabel))
                    Text(if (isConnected) "Apply & Save to ESP32" else "Connect ESP32 to Apply")
                }

                Row(horizontalArrangement = Arrangement.spacedBy(PulseShiftSpacing.Micro)) {
                    IconButton(
                        onClick = onDuplicate,
                        modifier = Modifier.size(PulseShiftSpacing.MinTouchTarget)
                    ) {
                        Icon(
                            Icons.Rounded.ContentCopy,
                            contentDescription = "Duplicate Preset",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (onRename != null) {
                        IconButton(
                            onClick = onRename,
                            modifier = Modifier.size(PulseShiftSpacing.MinTouchTarget)
                        ) {
                            Icon(
                                Icons.Rounded.Edit,
                                contentDescription = "Rename Preset",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (onDelete != null) {
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(PulseShiftSpacing.MinTouchTarget)
                        ) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = "Delete Preset",
                                tint = HardwareRedError
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CreatePresetSheet(
    onDismiss: () -> Unit,
    onCreate: (String, EffectType, List<RgbColor>, Int, Int, ShiftLightConfig?) -> Unit
) {
    var name by remember { mutableStateOf("Custom Preset") }
    var selectedEffect by remember { mutableStateOf(EffectType.STATIC) }
    var brightness by remember { mutableIntStateOf(255) } // Standard 100% brightness default
    var speed by remember { mutableIntStateOf(50) }
    var selectedPaletteIndex by remember { mutableIntStateOf(0) }

    val samplePalettes = listOf(
        "Racing Amber" to List(6) { RgbColor(255, 140, 0) },
        "Electric Blue" to List(6) { RgbColor(0, 150, 255) },
        "Tachometer Gradient" to listOf(
            RgbColor(0, 255, 0),
            RgbColor(120, 255, 0),
            RgbColor(255, 255, 0),
            RgbColor(255, 160, 0),
            RgbColor(255, 80, 0),
            RgbColor(255, 0, 0)
        ),
        "Cyber Purple" to List(6) { RgbColor(160, 32, 240) },
        "Pure White" to List(6) { RgbColor(255, 255, 255) }
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = PulseShiftRadius.Modal, topEnd = PulseShiftRadius.Modal)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PulseShiftSpacing.ScreenHorizontal)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.RelatedControls)
        ) {
            Text("Create Custom Preset", style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Preset Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = PulseShiftShapeTokens.Control
            )

            Text("Effect Pattern", style = MaterialTheme.typography.labelMedium)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PulseShiftSpacing.SmallLabel)
            ) {
                EffectType.entries.take(3).forEach { effect ->
                    FilterChip(
                        selected = selectedEffect == effect,
                        onClick = { selectedEffect = effect },
                        label = {
                            Text(
                                effect.displayName,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                }
            }

            Text("Color Palette", style = MaterialTheme.typography.labelMedium)
            Column(verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.SmallLabel)) {
                samplePalettes.forEachIndexed { idx, (palName, colors) ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedPaletteIndex = idx },
                        shape = PulseShiftShapeTokens.Card,
                        colors = CardDefaults.cardColors(
                            containerColor = if (selectedPaletteIndex == idx)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(palName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                colors.forEach { c ->
                                    Box(
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clip(CircleShape)
                                            .background(Color(c.red, c.green, c.blue))
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Text("Brightness", style = MaterialTheme.typography.labelMedium)
            StepperControl(
                value = brightness,
                onValueChange = { brightness = it },
                min = 0,
                max = 255,
                step = 10,
                unit = "/ 255"
            )

            Button(
                onClick = {
                    onCreate(
                        name.ifBlank { "Custom Preset" },
                        selectedEffect,
                        samplePalettes[selectedPaletteIndex].second,
                        brightness,
                        speed,
                        null
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = PulseShiftSpacing.MinTouchTarget)
            ) {
                Text("Save Preset to Library")
            }
        }
    }
}
