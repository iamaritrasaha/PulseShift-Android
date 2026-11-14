package com.pulseshift.app.ui.screens.presets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.pulseshift.app.ui.theme.HardwareGreenVerified
import com.pulseshift.app.ui.theme.HardwareRedError
import com.pulseshift.app.ui.theme.PulseShiftMonoMedium
import com.pulseshift.app.ui.theme.PulseShiftMonoSmall
import com.pulseshift.app.ui.theme.PulseShiftMonoTiny
import com.pulseshift.app.ui.theme.PulseShiftSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetsScreen(
    viewModel: PresetsViewModel,
    onNavigateBack: () -> Unit = {}
) {
    val presets by viewModel.presets.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    var showCreateSheet by remember { mutableStateOf(false) }
    var presetToRename by remember { mutableStateOf<Preset?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var confirmDeletePreset by remember { mutableStateOf<Preset?>(null) }

    LaunchedEffect(Unit) {
        viewModel.userMessage.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Presets Library", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.size(PulseShiftSpacing.MinTouchTarget)
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Navigate Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showRestoreDialog = true },
                        modifier = Modifier.size(PulseShiftSpacing.MinTouchTarget)
                    ) {
                        Icon(Icons.Rounded.RestartAlt, contentDescription = "Restore Factory Defaults")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateSheet = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "Create Custom Preset")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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
                        Text(
                            "No presets stored",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = { viewModel.restoreDefaults() }) {
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
                        top = PulseShiftSpacing.CardPadding,
                        bottom = 88.dp // Space for FAB
                    ),
                    verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.RelatedControls)
                ) {
                    items(presets, key = { it.id }) { preset ->
                        PresetListItem(
                            preset = preset,
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(PulseShiftSpacing.SmallLabel),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = preset.effect.displayName,
                            style = PulseShiftMonoTiny,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text("•", style = PulseShiftMonoTiny, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = "${preset.brightness * 100 / 255}% Brightness",
                            style = PulseShiftMonoTiny,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        preset.shiftLightConfig?.let { cfg ->
                            Text("•", style = PulseShiftMonoTiny, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = "${cfg.thresholds.first()}–${cfg.thresholds.last()} RPM",
                                style = PulseShiftMonoTiny,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                StatusPill(
                    text = if (preset.isCustom) "CUSTOM" else "BUILT-IN",
                    color = if (preset.isCustom) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
            }

            // 6-LED Visual Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
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
                    modifier = Modifier.height(PulseShiftSpacing.MinTouchTarget),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(PulseShiftSpacing.SmallLabel))
                    Text("Apply to ESP32")
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreatePresetSheet(
    onDismiss: () -> Unit,
    onCreate: (String, EffectType, List<RgbColor>, Int, Int, ShiftLightConfig?) -> Unit
) {
    var name by remember { mutableStateOf("Custom Preset") }
    var selectedEffect by remember { mutableStateOf(EffectType.STATIC) }
    var brightness by remember { mutableIntStateOf(220) }
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
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PulseShiftSpacing.ScreenHorizontal)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.RelatedControls)
        ) {
            Text("Create Custom Preset", style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Preset Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text("Effect Pattern", style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PulseShiftSpacing.SmallLabel)
            ) {
                EffectType.entries.take(3).forEach { effect ->
                    FilterChip(
                        selected = selectedEffect == effect,
                        onClick = { selectedEffect = effect },
                        label = { Text(effect.displayName) },
                        modifier = Modifier.weight(1f)
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
                            Text(palName, style = MaterialTheme.typography.bodyMedium)
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
                    .height(PulseShiftSpacing.MinTouchTarget)
            ) {
                Text("Save Preset to Library")
            }
        }
    }
}
