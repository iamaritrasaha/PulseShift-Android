package com.pulseshift.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pulseshift.app.domain.model.CanHealthState
import com.pulseshift.app.domain.model.Preset
import com.pulseshift.app.domain.model.RpmTelemetryState
import com.pulseshift.app.ui.components.LedStripPreview

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateToLighting: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToDevices: () -> Unit
) {
    val lightingState by viewModel.lightingState.collectAsState()
    val connectedDevice by viewModel.connectedDevice.collectAsState()
    val presets by viewModel.presets.collectAsState()
    val hasUnsavedChanges by viewModel.hasUnsavedChanges.collectAsState()
    val rpmTelemetry by viewModel.rpmTelemetryState.collectAsState()
    val canHealth by viewModel.canHealthState.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "PulseShift",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = connectedDevice?.name ?: "Disconnected",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (hasUnsavedChanges) {
                        FilledTonalButton(
                            onClick = { viewModel.saveToController() },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Text("Save to ESP32", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    // Status Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (connectedDevice != null) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable { onNavigateToDevices() }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (connectedDevice != null) Icons.Rounded.BluetoothConnected else Icons.Rounded.BluetoothDisabled,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (connectedDevice != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (connectedDevice != null) "Connected" else "Offline",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }

                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Settings")
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Primary Live Engine RPM Candidate Display Card
            PrimaryRpmCard(rpmTelemetry = rpmTelemetry)

            // 2. Real-time CAN Controller Health & Performance Card
            CanHealthCard(canHealth = canHealth, rpmTelemetry = rpmTelemetry)

            // 3. Flash Storage & Recording State Card
            FlashRecordingCard(canHealth = canHealth, rpmTelemetry = rpmTelemetry)

            // 4. 6-LED WS2812B Quick Studio Card
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "WS2812B Light Strip",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "${lightingState.effect.displayName} · 6 Addressable LEDs",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Switch(
                            checked = lightingState.isPowerOn,
                            onCheckedChange = { viewModel.togglePower() },
                            thumbContent = {
                                Icon(
                                    imageVector = Icons.Rounded.PowerSettingsNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    }

                    LedStripPreview(
                        colors = lightingState.ledColors,
                        isPowerOn = lightingState.isPowerOn,
                        brightness = lightingState.brightness,
                        selectedIndex = null
                    )

                    Column {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Brightness", style = MaterialTheme.typography.labelMedium)
                            Text("${lightingState.brightnessPercent}%", style = MaterialTheme.typography.labelMedium)
                        }
                        Slider(
                            value = lightingState.brightness.toFloat(),
                            onValueChange = { viewModel.updateBrightness(it.toInt()) },
                            valueRange = 0f..255f,
                            enabled = lightingState.isPowerOn
                        )
                    }

                    FilledTonalButton(
                        onClick = onNavigateToLighting,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open Full Lighting Studio")
                    }
                }
            }

            // Quick Presets Row
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Quick Presets",
                    style = MaterialTheme.typography.titleMedium
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    presets.take(6).forEach { preset ->
                        PresetChip(
                            preset = preset,
                            onClick = { viewModel.applyPreset(preset) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PrimaryRpmCard(rpmTelemetry: RpmTelemetryState?) {
    val isAvailable = rpmTelemetry?.isRpmAvailable == true && !rpmTelemetry.isStale
    val isVerified = rpmTelemetry?.statusFlags?.isCalibrationVerified == true
    val rpmText = rpmTelemetry?.displayRpmValueText ?: "—"

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isVerified) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Speed,
                        contentDescription = null,
                        tint = if (isVerified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("Engine RPM Candidate", style = MaterialTheme.typography.titleMedium)
                }

                // Calibration Status Tag
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            when {
                                !isAvailable -> MaterialTheme.colorScheme.surfaceVariant
                                isVerified -> MaterialTheme.colorScheme.primary
                                else -> Color(0xFFFFB74D) // Amber for unverified
                            }
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = rpmTelemetry?.displayLabelText ?: "RPM Unavailable",
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            !isAvailable -> MaterialTheme.colorScheme.onSurfaceVariant
                            isVerified -> MaterialTheme.colorScheme.onPrimary
                            else -> Color.Black
                        },
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Big RPM Display
            Text(
                text = rpmText,
                style = MaterialTheme.typography.displayLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                color = if (isAvailable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
            )

            // Secondary Info Text
            if (rpmTelemetry != null && isAvailable) {
                Text(
                    text = rpmTelemetry.displaySecondaryDetails,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "Awaiting valid CAN crankshaft telemetry packet from ESP32...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
fun CanHealthCard(canHealth: CanHealthState?, rpmTelemetry: RpmTelemetryState?) {
    val isCanHealthy = rpmTelemetry?.statusFlags?.isCanHealthy ?: (canHealth != null)
    val isTrafficActive = rpmTelemetry?.statusFlags?.isRecentCanTrafficDetected ?: false
    val totalFrames = rpmTelemetry?.totalCanFramesReceived ?: 0L
    val missed = canHealth?.driverMissedFrames ?: 0L
    val fifoOverruns = canHealth?.hardwareFifoOverruns ?: 0L
    val busErrors = canHealth?.canBusErrors ?: 0L
    val hasWarnings = missed > 0 || fifoOverruns > 0 || busErrors > 0

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("CAN Controller Status", style = MaterialTheme.typography.titleMedium)

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isCanHealthy) Color(0xFF10B981) else Color(0xFFEF4444))
                    )
                    Text(
                        text = if (isCanHealthy) "Running" else "Halted/Error",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (hasWarnings) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Text(
                            text = "CAN Degradation: Missed: $missed • FIFO Overruns: $fifoOverruns • Bus Errors: $busErrors",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Metric Counters Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricColumn("Total CAN RX", "$totalFrames")
                MetricColumn("Traffic", if (isTrafficActive) "Active" else "Idle")
                MetricColumn("Missed", "$missed", isWarning = missed > 0)
                MetricColumn("FIFO Overruns", "$fifoOverruns", isWarning = fifoOverruns > 0)
            }
        }
    }
}

@Composable
fun FlashRecordingCard(canHealth: CanHealthState?, rpmTelemetry: RpmTelemetryState?) {
    val isRecordingActive = rpmTelemetry?.statusFlags?.isFlashRecordingActive ?: false
    val isPreserved = rpmTelemetry?.statusFlags?.isPreviousCapturePreserved ?: false
    val isFsMounted = canHealth?.healthFlags?.isLittleFsMounted ?: true
    val isFull = canHealth?.healthFlags?.isStorageFull ?: false
    val recordedSessionFrames = canHealth?.recordedFramesInCurrentSession ?: 0L

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Flash Recording & Storage", style = MaterialTheme.typography.titleMedium)
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            when {
                                !isFsMounted -> MaterialTheme.colorScheme.errorContainer
                                isRecordingActive -> MaterialTheme.colorScheme.primaryContainer
                                isPreserved -> MaterialTheme.colorScheme.secondaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = when {
                            !isFsMounted -> "Storage Unavailable"
                            isFull -> "Storage Full"
                            isRecordingActive -> "Recording Active"
                            isPreserved -> "Previous Capture Preserved"
                            else -> "Recording Paused"
                        },
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricColumn("Session Frames", "$recordedSessionFrames")
                MetricColumn("LittleFS", if (isFsMounted) "Mounted" else "Error", isWarning = !isFsMounted)
                MetricColumn("Capacity", if (isFull) "FULL" else "Available", isWarning = isFull)
            }
        }
    }
}

@Composable
fun MetricColumn(label: String, value: String, isWarning: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
            color = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun PresetChip(
    preset: Preset,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                preset.ledColors.take(3).forEach { color ->
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(color.red, color.green, color.blue))
                    )
                }
            }
            Text(
                text = preset.name,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}
