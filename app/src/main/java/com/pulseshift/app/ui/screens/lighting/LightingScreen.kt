package com.pulseshift.app.ui.screens.lighting

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.pulseshift.app.ui.components.ConfirmDialog
import com.pulseshift.app.ui.components.LedStripPreview
import com.pulseshift.app.ui.theme.HardwareAmberWarning
import com.pulseshift.app.ui.theme.HardwareGreenVerified
import com.pulseshift.app.ui.theme.PulseShiftMonoSmall

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
import com.pulseshift.app.ui.components.StatusPill
import com.pulseshift.app.ui.theme.PulseShiftMonoTiny
import com.pulseshift.app.ui.theme.PulseShiftRadius
import com.pulseshift.app.ui.theme.PulseShiftShapeTokens
import com.pulseshift.app.ui.theme.PulseShiftSpacing

private val LIGHTING_TABS = listOf("Colors", "Effects", "RPM Bar", "Startup")

/**
 * Lighting Studio: six-LED preview with per-tab controls.
 * Provides distinct actions: Apply (Live RAM), Save to ESP32 (NVS), Save as Preset (Local), Restore Last Saved.
 */
@Composable
fun LightingScreen(
    viewModel: LightingViewModel,
    onNavigateToPresets: () -> Unit
) {
    val lighting by viewModel.lightingState.collectAsState()
    val shiftConfig by viewModel.shiftLightConfig.collectAsState()
    val snapshot by viewModel.configSnapshot.collectAsState()
    val unsaved by viewModel.hasUnsavedChanges.collectAsState()
    val selectedLed by viewModel.selectedLedIndex.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()
    val isAuthenticated by viewModel.isAuthenticated.collectAsState()
    val syncErrors = viewModel.lightingSyncError
    val feedbackMessages = viewModel.actionFeedback

    var tab by remember { mutableIntStateOf(0) }
    var confirmReset by remember { mutableStateOf(false) }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var newPresetName by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        syncErrors.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        feedbackMessages.collect { snackbar.showSnackbar(it) }
    }

    if (confirmReset) {
        ConfirmDialog(
            title = "Restore firmware defaults?",
            text = "Lighting preferences return to firmware defaults (1,000–6,000 RPM profile). Owner credentials and CAN logs are strictly preserved. The change applies live on the device.",
            confirmLabel = "Restore Defaults",
            onConfirm = {
                confirmReset = false
                viewModel.restoreControllerDefaults()
            },
            onDismiss = { confirmReset = false }
        )
    }

    if (showSavePresetDialog) {
        AlertDialog(
            onDismissRequest = { showSavePresetDialog = false },
            shape = PulseShiftShapeTokens.Modal,
            title = { Text("Save Current Setup as Preset") },
            text = {
                Column {
                    Text(
                        "Saves all current colors, effect, speed, brightness and RPM thresholds into your local Preset library.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(PulseShiftSpacing.RelatedControls))
                    OutlinedTextField(
                        value = newPresetName,
                        onValueChange = { newPresetName = it },
                        label = { Text("Preset Name") },
                        singleLine = true,
                        shape = PulseShiftShapeTokens.Control,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPresetName.isNotBlank()) {
                            viewModel.saveCurrentSetupAsPreset(newPresetName.trim())
                            showSavePresetDialog = false
                            newPresetName = ""
                        }
                    },
                    enabled = newPresetName.isNotBlank()
                ) {
                    Text("Save Preset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSavePresetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = PulseShiftSpacing.ScreenHorizontal,
                end = PulseShiftSpacing.ScreenHorizontal,
                top = PulseShiftSpacing.ScreenVertical,
                bottom = PulseShiftSpacing.BottomScrollPadding
            ),
            verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.SectionSpacing)
        ) {
            item {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Lighting Studio", style = MaterialTheme.typography.headlineMedium)
                        StatusPill(
                            text = if (unsaved) "UNSAVED (RAM ONLY)" else "PERSISTED (NVS)",
                            color = if (unsaved) HardwareAmberWarning else HardwareGreenVerified
                        )
                    }
                    Spacer(Modifier.height(PulseShiftSpacing.SmallLabel))
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = snapshot?.let {
                                "Active Rev ${it.activeRevision} · Calibration " +
                                    if (it.calibrationVerified) "VERIFIED" else "UNVERIFIED"
                            } ?: "Live hardware synchronized",
                            style = PulseShiftMonoTiny,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (isAuthenticated) {
                            StatusPill(text = "AUTHENTICATED", color = HardwareGreenVerified)
                        } else {
                            StatusPill(text = "AUTH REQUIRED", color = HardwareAmberWarning)
                        }
                    }
                }
            }

            item {
                Column {
                    LedStripPreview(
                        colors = lighting.ledColors,
                        isPowerOn = lighting.isPowerOn,
                        brightness = lighting.brightness,
                        selectedIndex = selectedLed,
                        onSelectLed = { index ->
                            if (selectedLed == index) {
                                viewModel.selectLed(-1) // Toggle back to all LEDs
                            } else {
                                viewModel.selectLed(index)
                            }
                        }
                    )
                    Spacer(Modifier.height(PulseShiftSpacing.Micro))
                    Text(
                        text = if (selectedLed == -1) "Editing all 6 LEDs simultaneously · Tap an individual LED to isolate"
                               else "Editing LED ${selectedLed + 1} individually · Tap again to select all",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }

            item {
                TabRow(
                    selectedTabIndex = tab,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    LIGHTING_TABS.forEachIndexed { index, title ->
                        Tab(
                            selected = tab == index,
                            onClick = { tab = index },
                            text = { Text(title) }
                        )
                    }
                }
            }

            item {
                when (tab) {
                    0 -> ColorsPanel(viewModel, lighting, selectedLed)
                    1 -> EffectsPanel(viewModel, lighting)
                    2 -> RpmBarPanel(viewModel, shiftConfig)
                    else -> StartupPanel(viewModel, lighting)
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.RelatedControls)) {
                    // Primary Action: Save to ESP32 Flash (NVS)
                    Button(
                        onClick = { viewModel.saveToController() },
                        enabled = !isBusy,
                        shape = PulseShiftShapeTokens.Control,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(PulseShiftSpacing.MinTouchTarget)
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(PulseShiftSpacing.SmallLabel))
                            Text("Saving to ESP32…")
                        } else {
                            Text(
                                if (unsaved) "Save Changes to ESP32 Flash (NVS)"
                                else "All Changes Saved to Flash — Tap to Re-Save"
                            )
                        }
                    }

                    // Secondary Actions: Presets, Save as Preset, Reload, Factory Defaults
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(PulseShiftSpacing.RelatedControls),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = {
                                newPresetName = "Custom Preset ${System.currentTimeMillis() % 1000}"
                                showSavePresetDialog = true
                            },
                            shape = PulseShiftShapeTokens.Control,
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                        ) {
                            Text("Save as Preset", maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = onNavigateToPresets,
                            shape = PulseShiftShapeTokens.Control,
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                        ) {
                            Text("Presets Library", maxLines = 1)
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(PulseShiftSpacing.RelatedControls),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.reloadFromController() },
                            shape = PulseShiftShapeTokens.Control,
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                        ) {
                            Text("Reload from ESP32", maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = { confirmReset = true },
                            shape = PulseShiftShapeTokens.Control,
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                        ) {
                            Text("Firmware Defaults", maxLines = 1)
                        }
                    }
                }
            }
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}
