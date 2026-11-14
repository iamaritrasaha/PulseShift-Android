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

private val LIGHTING_TABS = listOf("Colors", "Effects", "RPM Bar", "Startup")

/**
 * Lighting Studio: six-LED preview with per-tab controls. Local edits are
 * clearly marked as pending until the device ACKs and a save persists them.
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
    val syncErrors = viewModel.lightingSyncError

    var tab by remember { mutableIntStateOf(0) }
    var confirmReset by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { syncErrors.collect { snackbar.showSnackbar(it) } }

    if (confirmReset) {
        ConfirmDialog(
            title = "Restore device defaults?",
            text = "Lighting and decoder settings return to firmware defaults. Owner credentials are preserved. " +
                "The change applies live on the device.",
            confirmLabel = "Restore",
            onConfirm = {
                confirmReset = false
                viewModel.restoreControllerDefaults()
            },
            onDismiss = { confirmReset = false }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Lighting Studio", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = if (unsaved) "UNSAVED (session only)" else "SAVED TO DEVICE",
                            style = PulseShiftMonoSmall,
                            color = if (unsaved) HardwareAmberWarning else HardwareGreenVerified
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = snapshot?.let {
                            "Device revision ${it.activeRevision} · calibration " +
                                if (it.calibrationVerified) "VERIFIED" else "UNVERIFIED"
                        } ?: "Waiting for config snapshot…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                LedStripPreview(
                    colors = lighting.ledColors,
                    isPowerOn = lighting.isPowerOn,
                    brightness = lighting.brightness,
                    selectedIndex = selectedLed,
                    onSelectLed = { viewModel.selectLed(it) }
                )
            }

            item {
                TabRow(selectedTabIndex = tab) {
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.saveToController() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (unsaved) "Save to ESP32 flash" else "Saved — tap to re-save") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onNavigateToPresets,
                            modifier = Modifier.weight(1f)
                        ) { Text("Presets") }
                        OutlinedButton(
                            onClick = { viewModel.reloadFromController() },
                            modifier = Modifier.weight(1f)
                        ) { Text("Reload") }
                        OutlinedButton(
                            onClick = { confirmReset = true },
                            modifier = Modifier.weight(1f)
                        ) { Text("Defaults") }
                    }
                }
            }
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}
