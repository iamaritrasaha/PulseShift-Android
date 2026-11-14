package com.pulseshift.app.ui.screens.home

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pulseshift.app.data.ble.BleConnectionState
import com.pulseshift.app.domain.model.BleDevice
import com.pulseshift.app.domain.model.LightingState
import com.pulseshift.app.domain.model.Preset
import com.pulseshift.app.domain.model.RpmTelemetryState
import com.pulseshift.app.ui.components.LedStripPreview
import com.pulseshift.app.ui.components.SectionCard
import com.pulseshift.app.ui.components.StatusDot
import com.pulseshift.app.ui.theme.HardwareAmberWarning
import com.pulseshift.app.ui.theme.HardwareGreenVerified
import com.pulseshift.app.ui.theme.HardwareInactiveOff
import com.pulseshift.app.ui.theme.HardwareRedError
import com.pulseshift.app.ui.theme.HardwareTealActive
import com.pulseshift.app.ui.theme.PulseShiftMonoLarge
import com.pulseshift.app.ui.theme.PulseShiftMonoSmall
import com.pulseshift.app.ui.theme.PulseShiftMonoTiny

/**
 * Dashboard: the primary operating screen. Every value shown comes from real
 * device telemetry — unavailable data renders as an explicit dash, never a
 * fabricated number.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateToLighting: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToDevices: () -> Unit,
    onNavigateToTerminal: () -> Unit,
    onNavigateToPresets: () -> Unit,
    onNavigateToRpm: () -> Unit
) {
    val lighting by viewModel.lightingState.collectAsState()
    val device by viewModel.connectedDevice.collectAsState()
    val rpm by viewModel.rpmTelemetryState.collectAsState()
    val presets by viewModel.presets.collectAsState()
    val unsaved by viewModel.hasUnsavedChanges.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val connectionError by viewModel.connectionError.collectAsState()
    val reconnecting by viewModel.isReconnecting.collectAsState()
    val syncErrors = viewModel.lightingSyncError

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        syncErrors.collect { snackbar.showSnackbar(it) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { ConnectionBanner(device, connectionState, connectionError, reconnecting, onNavigateToDevices) }
            item { RpmGaugeCard(rpm, lighting, onNavigateToRpm) }
            item { LightControlCard(lighting, unsaved, viewModel, onNavigateToLighting) }
            item {
                QuickAccessRow(onNavigateToTerminal, onNavigateToPresets, onNavigateToSettings)
            }
            if (presets.isNotEmpty()) {
                item { QuickPresetsRow(presets) { viewModel.applyPreset(it) } }
            }
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ConnectionBanner(
    device: BleDevice?,
    state: BleConnectionState,
    error: String?,
    reconnecting: Boolean,
    onNavigateToDevices: () -> Unit
) {
    val (dotColor, label) = when (state) {
        BleConnectionState.RECEIVING_TELEMETRY,
        BleConnectionState.READY,
        BleConnectionState.CONNECTED_WAITING_FOR_DATA -> HardwareGreenVerified to "CONNECTED"
        BleConnectionState.CONNECTING,
        BleConnectionState.DISCOVERING_SERVICES,
        BleConnectionState.SUBSCRIBING -> HardwareAmberWarning to "CONNECTING"
        BleConnectionState.SCANNING,
        BleConnectionState.DEVICE_DISCOVERED -> HardwareTealActive to "SCANNING"
        BleConnectionState.PERMISSION_REQUIRED -> HardwareRedError to "PERMISSIONS NEEDED"
        BleConnectionState.BLUETOOTH_DISABLED -> HardwareAmberWarning to "BLUETOOTH OFF"
        BleConnectionState.UNSUPPORTED -> HardwareRedError to "BLE UNSUPPORTED"
        else -> HardwareInactiveOff to "DISCONNECTED"
    }

    SectionCard(
        title = "Device",
        modifier = Modifier.clickable { onNavigateToDevices() }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color = dotColor, size = 12.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (reconnecting) "Reconnecting…"
                           else device?.name ?: "No device connected",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    device?.let { "${it.address} · fw ${it.firmwareVersion ?: "?"}" } ?: "Tap to scan and connect",
                    style = PulseShiftMonoTiny,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(label, style = PulseShiftMonoSmall, color = dotColor)
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = HardwareRedError,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(error, style = MaterialTheme.typography.bodySmall, color = HardwareRedError)
            }
        }
    }
}

@Composable
private fun RpmGaugeCard(
    rpm: RpmTelemetryState?,
    lighting: LightingState,
    onNavigateToRpm: () -> Unit
) {
    SectionCard(
        title = "Live Engine Speed",
        subtitle = "Estimated from CAN 0x301 · provisional ×40 mapping",
        modifier = Modifier.clickable { onNavigateToRpm() }
    ) {
        val available = rpm != null && rpm.isRpmAvailable && !rpm.isStale
        val verified = rpm?.statusFlags?.isCalibrationVerified == true
        val lightsRendering = rpm?.statusFlags?.isPhysicalShiftLightsEnabled == true

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = if (available) "${rpm!!.provisionalRpm}" else "———",
                style = PulseShiftMonoLarge,
                color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "RPM",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                StatusBadge(
                    text = if (verified) "VERIFIED" else "UNVERIFIED",
                    color = if (verified) HardwareGreenVerified else HardwareAmberWarning
                )
                Spacer(Modifier.height(4.dp))
                StatusBadge(
                    text = when {
                        rpm == null -> "NO TELEMETRY"
                        !rpm.statusFlags.isCandidateFresh -> "CAN STALE"
                        lightsRendering -> "LED LIVE"
                        else -> "LED IDLE"
                    },
                    color = when {
                        rpm == null || !rpm.statusFlags.isCandidateFresh -> HardwareRedError
                        lightsRendering -> HardwareTealActive
                        else -> HardwareInactiveOff
                    }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        LedStripPreview(
            colors = lighting.ledColors,
            isPowerOn = lighting.isPowerOn,
            brightness = lighting.brightness
        )

        Spacer(Modifier.height(10.dp))
        Row {
            Text(
                "Effect: ${lighting.effect.displayName}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (rpm == null) "CAN RX: —" else "CAN RX: ${rpm.totalCanFramesReceived}",
                style = PulseShiftMonoTiny,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.16f),
        contentColor = color,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text,
            style = PulseShiftMonoTiny,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun LightControlCard(
    lighting: LightingState,
    unsaved: Boolean,
    viewModel: HomeViewModel,
    onNavigateToLighting: () -> Unit
) {
    SectionCard(title = "Lighting", subtitle = "Current active configuration") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Master power", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (unsaved) "Unsaved changes — save from Lighting Studio" else "Synced with device",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (unsaved) HardwareAmberWarning else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = lighting.isPowerOn,
                onCheckedChange = { viewModel.togglePower() }
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Brightness · ${lighting.brightness}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = lighting.brightness.toFloat(),
            onValueChange = { viewModel.updateBrightness(it.toInt()) },
            valueRange = 0f..255f
        )
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onNavigateToLighting, modifier = Modifier.fillMaxWidth()) {
            Text("Open Lighting Studio")
        }
    }
}

@Composable
private fun QuickAccessRow(
    onNavigateToTerminal: () -> Unit,
    onNavigateToPresets: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    SectionCard(title = "Quick Access") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction(Icons.Rounded.Terminal, "Terminal", Modifier.weight(1f), onNavigateToTerminal)
            QuickAction(Icons.Rounded.Bookmarks, "Presets", Modifier.weight(1f), onNavigateToPresets)
            QuickAction(Icons.Rounded.Settings, "Settings", Modifier.weight(1f), onNavigateToSettings)
        }
    }
}

@Composable
private fun QuickAction(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable { onClick() },
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 14.dp)
        ) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun QuickPresetsRow(
    presets: List<Preset>,
    onApply: (Preset) -> Unit
) {
    SectionCard(title = "Quick Presets") {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(presets) { preset ->
                AssistChip(
                    onClick = { onApply(preset) },
                    label = { Text(preset.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }
    }
}
