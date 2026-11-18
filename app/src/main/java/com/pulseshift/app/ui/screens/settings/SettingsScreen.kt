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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pulseshift.app.domain.model.OtaUpdateState
import com.pulseshift.app.domain.model.OTA_MAX_PACKAGE_BYTES
import com.pulseshift.app.ui.theme.PulseShiftRadius
import com.pulseshift.app.ui.theme.PulseShiftShapeTokens
import com.pulseshift.app.ui.components.PulseShiftDetailTopBar
import com.pulseshift.app.ui.theme.PulseShiftSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

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
    val authenticated by viewModel.isAuthenticated.collectAsState()
    val otaState by viewModel.otaUpdateState.collectAsState()

    var showOtaPreflight by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > OTA_MAX_PACKAGE_BYTES) {
                                throw IOException("Firmware file exceeds the 1.25 MiB OTA limit.")
                            }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: throw IOException("Unable to open the selected firmware file.")
                }
                viewModel.startOtaUpdate(bytes)
            } catch (e: IOException) {
                snackbar.showSnackbar(e.message ?: "Unable to read the selected firmware file.")
            } catch (e: SecurityException) {
                snackbar.showSnackbar("The app cannot read the selected firmware file.")
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            PulseShiftDetailTopBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                onBack = onNavigateBack
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
                                val slotStr = if (slot != null && slot in 0..1) "Active app$slot · target app${1 - slot}" else "OTA slots unavailable"
                                Text("$fwVer • $slotStr", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Icon(Icons.Rounded.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }

                        val caps = connectedDevice?.capabilities
                        val canOta = connectedDevice?.isConnected == true && caps?.supportsOtaUpdate == true &&
                            caps.hasDualOtaSlots && caps.otaActiveSlot in 0..1
                        Text(
                            when {
                                !canOta -> "Connect to a controller with dual-slot OTA support."
                                !authenticated -> "Authenticate as the controller owner to update."
                                else -> "Choose a signed PulseShift firmware package."
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(
                            onClick = { showOtaPreflight = true },
                            enabled = canOta && authenticated && otaState is OtaUpdateState.Idle
                        ) {
                            Text("Select signed firmware")
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
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
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

        if (showOtaPreflight) {
            AlertDialog(
                onDismissRequest = { showOtaPreflight = false },
                title = { Text("Bench update only") },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Park, switch the engine and ignition off, and use stable bench power. Keep USB recovery available.")
                        Text("Physical OTA and rollback are unverified. Interrupted power may require recovery. Keep the ESP32 and phone powered until verification finishes.")
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showOtaPreflight = false
                        filePicker.launch("*/*")
                    }) { Text("Parked · Select file") }
                },
                dismissButton = {
                    TextButton(onClick = { showOtaPreflight = false }) { Text("Cancel") }
                }
            )
        }

        if (otaState !is OtaUpdateState.Idle) {
            AlertDialog(
                onDismissRequest = {
                    if (otaState is OtaUpdateState.Success || otaState is OtaUpdateState.Failed ||
                        otaState is OtaUpdateState.Cancelled) {
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
                            when (val state = otaState) {
                                is OtaUpdateState.Validating -> "Validating Firmware..."
                                is OtaUpdateState.Transferring -> "Streaming Firmware..."
                                OtaUpdateState.Cancelling -> "Cancelling Update..."
                                OtaUpdateState.Cancelled -> "Update Cancelled"
                                is OtaUpdateState.Verifying -> "Verifying Checksum..."
                                is OtaUpdateState.Rebooting -> "ESP32 rebooting"
                                is OtaUpdateState.Reconnecting -> "Reconnecting over Bluetooth"
                                is OtaUpdateState.Confirming -> "Checking the running firmware"
                                is OtaUpdateState.Success -> if (state.imageIdentityVerified) {
                                    "Firmware Image Verified"
                                } else {
                                    "Boot Slot Confirmed"
                                }
                                is OtaUpdateState.Failed -> "Update Interrupted"
                                else -> "Firmware Update"
                            }
                        )
                    }
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    ) {
                        when (val state = otaState) {
                            is OtaUpdateState.Validating -> {
                                Text("Checking image size (${state.totalBytes} bytes) and SHA-256 integrity.")
                                if (state.targetSlot >= 0) {
                                    Text("Expected target: Slot ${state.targetSlot}", style = MaterialTheme.typography.bodySmall)
                                }
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            is OtaUpdateState.Transferring -> {
                                Text("Transferring to app${state.currentSlot}. Keep the ESP32 and phone powered.")
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
                            OtaUpdateState.Cancelling -> {
                                Text("Waiting for the ESP32 to acknowledge cancellation.")
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            OtaUpdateState.Cancelled -> Text("The ESP32 acknowledged cancellation.")
                            is OtaUpdateState.Verifying -> {
                                Text("Waiting for the ESP32 to check the image and signature.")
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            is OtaUpdateState.Rebooting -> {
                                Text("The ESP32 is switching to app${state.targetSlot}. Waiting ${state.secondsRemaining} s for reboot.")
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            is OtaUpdateState.Reconnecting -> {
                                Text("Waiting for the controller to return on app${state.targetSlot}.")
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            is OtaUpdateState.Confirming -> {
                                Text("Checking the digest and bootloader status for app${state.targetSlot}.")
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            is OtaUpdateState.Success -> {
                                Text("The ESP32 reports ${state.newVersion} on slot ${state.activeSlot} and marked it valid.")
                                Text(
                                    if (state.imageIdentityVerified) "The running image digest matches the selected file."
                                    else "Image identity is unverified. This firmware reports its slot and version only.",
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            is OtaUpdateState.Failed -> {
                                Text("Failure: ${state.reason}", color = MaterialTheme.colorScheme.error)
                            }
                            else -> {}
                        }
                    }
                },
                confirmButton = {
                    when (otaState) {
                        is OtaUpdateState.Success, is OtaUpdateState.Failed, OtaUpdateState.Cancelled -> {
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
