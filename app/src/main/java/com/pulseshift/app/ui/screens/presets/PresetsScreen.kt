package com.pulseshift.app.ui.screens.presets

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pulseshift.app.domain.model.EffectType
import com.pulseshift.app.domain.model.Preset
import com.pulseshift.app.domain.model.RgbColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetsScreen(
    viewModel: PresetsViewModel
) {
    val presets by viewModel.presets.collectAsState()
    var showCreateSheet by remember { mutableStateOf(false) }
    var presetNameInput by remember { mutableStateOf("") }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var confirmDeletePreset by remember { mutableStateOf<Preset?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Presets Library", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = { showRestoreDialog = true }) {
                        Icon(Icons.Rounded.RestartAlt, contentDescription = "Restore Defaults")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    presetNameInput = "My Custom Preset"
                    showCreateSheet = true
                },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "Create Preset")
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(presets, key = { it.id }) { preset ->
                PresetListItem(
                    preset = preset,
                    onApply = { viewModel.applyPreset(preset) },
                    onDuplicate = { viewModel.duplicatePreset(preset) },
                    onDelete = if (preset.isCustom) { { confirmDeletePreset = preset } } else null
                )
            }
        }
    }

    // Delete confirmation: preset deletion is irreversible.
    confirmDeletePreset?.let { target ->
        com.pulseshift.app.ui.components.ConfirmDialog(
            title = "Delete \"${target.name}\"?",
            text = "This removes the phone-stored preset permanently. Device settings are not touched.",
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
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text("Restore Default Presets") },
            text = { Text("Reset presets list back to the factory defaults? Custom presets will be removed.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.restoreDefaults()
                        showRestoreDialog = false
                    }
                ) {
                    Text("Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Modal Bottom Sheet for Creating Preset
    if (showCreateSheet) {
        ModalBottomSheet(
            onDismissRequest = { showCreateSheet = false },
            sheetState = rememberModalBottomSheetState()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Create Custom Preset", style = MaterialTheme.typography.titleLarge)

                OutlinedTextField(
                    value = presetNameInput,
                    onValueChange = { presetNameInput = it },
                    label = { Text("Preset Name") },
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        if (presetNameInput.isNotBlank()) {
                            viewModel.createPreset(
                                name = presetNameInput,
                                effect = EffectType.STATIC,
                                colors = List(6) { RgbColor.BrandViolet },
                                brightness = 220,
                                speed = 50
                            )
                            showCreateSheet = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Preset")
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun PresetListItem(
    preset: Preset,
    onApply: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: (() -> Unit)?
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = preset.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${preset.effect.displayName} · ${preset.brightness * 100 / 255}% brightness",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row {
                    IconButton(onClick = onDuplicate) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "Duplicate")
                    }
                    if (onDelete != null) {
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    IconButton(onClick = onApply) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Apply Preset", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // 6-LED Color Preview Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .clip(RoundedCornerShape(8.dp)),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                preset.ledColors.forEach { c ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(20.dp)
                            .background(Color(c.red, c.green, c.blue))
                    )
                }
            }
        }
    }
}
