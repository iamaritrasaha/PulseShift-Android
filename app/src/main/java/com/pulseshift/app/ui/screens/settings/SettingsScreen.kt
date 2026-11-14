package com.pulseshift.app.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.pulseshift.app.R
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pulseshift.app.domain.model.OtaUpdateState
import com.pulseshift.app.ui.theme.PulseShiftRadius
import com.pulseshift.app.ui.theme.PulseShiftShapeTokens
import com.pulseshift.app.ui.theme.PulseShiftSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val themeMode by viewModel.themeMode.collectAsState()
    val dynamicColor by viewModel.dynamicColor.collectAsState()
    val autoReconnect by viewModel.autoReconnect.collectAsState()
    val connectedDevice by viewModel.connectedDevice.collectAsState()
    val otaState by viewModel.otaUpdateState.collectAsState()

    var showAboutDialog by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null && bytes.isNotEmpty()) {
                    viewModel.startOtaUpdate(bytes)
                }
            } catch (e: Exception) {
                // Read error handled by view model
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.size(PulseShiftSpacing.MinTouchTarget)
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = PulseShiftSpacing.ScreenHorizontal,
                    end = PulseShiftSpacing.ScreenHorizontal,
                    top = PulseShiftSpacing.ScreenVertical,
                    bottom = PulseShiftSpacing.BottomScrollPadding
                ),
            verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.SectionSpacing)
        ) {
            // Appearance Section
            Column(verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.SmallGap)) {
                Text("Appearance", style = MaterialTheme.typography.titleMedium)
                Card(
                    shape = PulseShiftShapeTokens.Card,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(PulseShiftSpacing.CardPadding), verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.RelatedControls)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Material You Dynamic Color", style = MaterialTheme.typography.bodyLarge)
                                Text("Match Pixel wallpaper tones (Android 12+)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = dynamicColor,
                                onCheckedChange = { viewModel.setDynamicColor(it) }
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.SmallGap)) {
                            Text("Theme Preference", style = MaterialTheme.typography.bodyMedium)
                            listOf("System Default" to 0, "Light" to 1, "Dark" to 2).forEach { (label, mode) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(PulseShiftShapeTokens.Control)
                                        .clickable { viewModel.setThemeMode(mode) }
                                        .padding(vertical = PulseShiftSpacing.Micro),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = themeMode == mode,
                                        onClick = { viewModel.setThemeMode(mode) }
                                    )
                                    Text(label, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }

            // Hardware & Controller Preferences
            Column(verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.SmallGap)) {
                Text("Hardware & Controller", style = MaterialTheme.typography.titleMedium)
                Card(
                    shape = PulseShiftShapeTokens.Card,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(PulseShiftSpacing.CardPadding), verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.RelatedControls)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Auto-reconnect BLE", style = MaterialTheme.typography.bodyLarge)
                                Text("Automatically reconnect to known ESP32", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = autoReconnect,
                                onCheckedChange = { viewModel.setAutoReconnect(it) }
                            )
                        }
                    }
                }
            }

            // Firmware & OTA Updates Section
            Column(verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.SmallGap)) {
                Text("Firmware & Updates", style = MaterialTheme.typography.titleMedium)
                Card(
                    shape = PulseShiftShapeTokens.Card,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(PulseShiftSpacing.CardPadding),
                        verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.RelatedControls)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Running Firmware", style = MaterialTheme.typography.bodyLarge)
                                val fwVer = connectedDevice?.firmwareVersion ?: "Not connected"
                                val slot = connectedDevice?.capabilities?.otaActiveSlot
                                val slotStr = if (slot != null && slot != 0xFF) "Slot $slot (app$slot)" else "Single Slot"
                                Text("$fwVer • $slotStr", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Icon(Icons.Rounded.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }

                        val canOta = connectedDevice?.capabilities?.supportsOtaUpdate == true ||
                                (connectedDevice?.capabilities?.firmwareMajor ?: 0) >= 2
                        val isConnected = connectedDevice?.isConnected == true

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Over-The-Air Update", style = MaterialTheme.typography.bodyMedium)
                                Text("Dual-slot fail-safe BLE transfer with SHA-256 verification", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Button(
                                onClick = { filePicker.launch("*/*") },
                                enabled = isConnected && canOta && otaState is OtaUpdateState.Idle
                            ) {
                                Text("Flash .bin")
                            }
                        }
                    }
                }
            }

            // About & System Info
            Column(verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.SmallGap)) {
                Text("About & Verification", style = MaterialTheme.typography.titleMedium)
                Card(
                    shape = PulseShiftShapeTokens.Card,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAboutDialog = true }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(PulseShiftSpacing.CardPadding),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(PulseShiftSpacing.RelatedControls),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF101014))
                                    .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_pulseshift_logo_round),
                                    contentDescription = "PulseShift",
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                            Column {
                                Text(
                                    "PulseShift v${com.pulseshift.app.BuildConfig.VERSION_NAME}",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    "Designed & Developed by Aritra Saha",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "© 2026 Aritra Saha. All rights reserved.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        if (showAboutDialog) {
            AlertDialog(
                onDismissRequest = { showAboutDialog = false },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_pulseshift_logo_round),
                                contentDescription = "PulseShift Logo",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Text("About PulseShift")
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("PulseShift is a native Android companion application for physical motorcycle smart lighting and shift-light configuration.")
                        Text("• Author: Designed & Engineered by Aritra Saha")
                        Text("• Copyright: © 2026 Aritra Saha. All rights reserved.")
                        Text("• Target: 2025 Indian Bajaj Pulsar NS125 ABS")
                        Text("• Hardware: ESP32 DevKit V1 + 6x WS2812B Addressable RGB Strip")
                        Text("• Protocol: PulseShift BLE Config V2 (Staged transactions & NVS flash persistence)")
                        Text("• Privacy: Fully offline, no ads, no trackers, no accounts.")
                        Text("• Engine RPM: Displays Estimated RPM — Unverified until independent calibration establishes scientific correctness.")
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAboutDialog = false }) {
                        Text("Close")
                    }
                }
            )
        }

        if (otaState !is OtaUpdateState.Idle) {
            AlertDialog(
                onDismissRequest = {
                    if (otaState is OtaUpdateState.Success || otaState is OtaUpdateState.Failed) {
                        viewModel.cancelOtaUpdate()
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            when (otaState) {
                                is OtaUpdateState.Validating -> "Validating Firmware..."
                                is OtaUpdateState.Transferring -> "Streaming Firmware..."
                                is OtaUpdateState.Verifying -> "Verifying Checksum..."
                                is OtaUpdateState.Rebooting -> "ESP32 Rebooting..."
                                is OtaUpdateState.Success -> "Update Completed"
                                is OtaUpdateState.Failed -> "Update Interrupted"
                                else -> "Firmware Update"
                            }
                        )
                    }
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        when (val state = otaState) {
                            is OtaUpdateState.Validating -> {
                                Text("Checking image size (${state.totalBytes} bytes) and SHA-256 integrity.")
                                Text("Target Partition: Slot ${state.targetSlot} (app${state.targetSlot})", style = MaterialTheme.typography.bodySmall)
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            is OtaUpdateState.Transferring -> {
                                Text("Sending chunked binary packets over BLE. Do not power off motorcycle or phone.")
                                LinearProgressIndicator(
                                    progress = { state.progressPercent / 100.0f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    "${"%.1f".format(state.progressPercent)}% • ${state.bytesSent} / ${state.totalBytes} bytes",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            is OtaUpdateState.Verifying -> {
                                Text("FIPS 180-2 SHA-256 hardware hash calculation on ESP32 target partition...")
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            is OtaUpdateState.Rebooting -> {
                                Text("Switching active boot partition and restarting device. Waiting for BLE telemetry reconnection (${state.secondsRemaining}s)...")
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            is OtaUpdateState.Success -> {
                                Text("Firmware upgraded successfully from ${state.previousVersion} to ${state.newVersion} on Slot ${state.activeSlot}.")
                                Text("ESP32 self-test passed and bootloader rollback cancelled.", color = MaterialTheme.colorScheme.primary)
                            }
                            is OtaUpdateState.Failed -> {
                                Text("Failure: ${state.reason}", color = MaterialTheme.colorScheme.error)
                                Text("Safety Guarantee: Existing running slot was untouched. Device will boot normally.", style = MaterialTheme.typography.bodySmall)
                            }
                            else -> {}
                        }
                    }
                },
                confirmButton = {
                    when (otaState) {
                        is OtaUpdateState.Success, is OtaUpdateState.Failed -> {
                            TextButton(onClick = { viewModel.cancelOtaUpdate() }) {
                                Text("Done")
                            }
                        }
                        is OtaUpdateState.Transferring -> {
                            TextButton(onClick = { viewModel.cancelOtaUpdate() }) {
                                Text("Cancel", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        else -> {}
                    }
                }
            )
        }
    }
}
