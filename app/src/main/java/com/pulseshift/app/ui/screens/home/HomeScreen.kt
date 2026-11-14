package com.pulseshift.app.ui.screens.home

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import com.pulseshift.app.R
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

import com.pulseshift.app.ui.components.StatusPill
import com.pulseshift.app.ui.theme.PulseShiftSpacing
import com.pulseshift.app.ui.theme.PulseShiftMonoDisplay
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults

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
            contentPadding = PaddingValues(
                start = PulseShiftSpacing.ScreenHorizontal,
                end = PulseShiftSpacing.ScreenHorizontal,
                top = PulseShiftSpacing.ScreenVertical,
                bottom = PulseShiftSpacing.BottomScrollPadding
            ),
            verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.SectionSpacing)
        ) {
            item {
                ConnectionBanner(
                    device = device,
                    state = connectionState,
                    error = connectionError,
                    reconnecting = reconnecting,
                    onNavigateToDevices = onNavigateToDevices
                )
            }
            item {
                RpmGaugeCard(
                    rpm = rpm,
                    lighting = lighting,
                    onNavigateToRpm = onNavigateToRpm,
                    onNavigateToLighting = onNavigateToLighting
                )
            }
            item {
                LightControlCard(
                    lighting = lighting,
                    unsaved = unsaved,
                    viewModel = viewModel,
                    onNavigateToLighting = onNavigateToLighting
                )
            }
            item {
                QuickAccessRow(
                    onNavigateToTerminal = onNavigateToTerminal,
                    onNavigateToPresets = onNavigateToPresets,
                    onNavigateToSettings = onNavigateToSettings
                )
            }
            if (presets.isNotEmpty()) {
                item {
                    QuickPresetsRow(
                        presets = presets,
                        onApply = { viewModel.applyPreset(it) }
                    )
                }
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
        BleConnectionState.PERMISSION_REQUIRED -> HardwareRedError to "PERMISSIONS"
        BleConnectionState.BLUETOOTH_DISABLED -> HardwareAmberWarning to "BT OFF"
        BleConnectionState.UNSUPPORTED -> HardwareRedError to "UNSUPPORTED"
        else -> HardwareInactiveOff to "DISCONNECTED"
    }

    val isConnected = state == BleConnectionState.RECEIVING_TELEMETRY ||
                      state == BleConnectionState.READY ||
                      state == BleConnectionState.CONNECTED_WAITING_FOR_DATA

    SectionCard(
        title = "Device Connection",
        headerAction = {
            FilledTonalButton(
                onClick = onNavigateToDevices,
                modifier = Modifier.height(36.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
            ) {
                Text(
                    text = if (isConnected) "Manage" else "Connect",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF101014))
                    .border(1.5.dp, dotColor.copy(alpha = 0.6f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_pulseshift_logo_round),
                    contentDescription = "PulseShift Controller",
                    modifier = Modifier.size(34.dp)
                )
            }
            Spacer(Modifier.width(PulseShiftSpacing.RelatedControls))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (reconnecting) "Reconnecting…"
                           else device?.name ?: "PulseShift ESP32",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = device?.let { "${it.address} · fw ${it.firmwareVersion ?: "?"}" }
                        ?: "No active Bluetooth LE link",
                    style = PulseShiftMonoTiny,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StatusPill(text = label, color = dotColor)
        }
        if (error != null) {
            Spacer(Modifier.height(PulseShiftSpacing.SmallLabel))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = HardwareRedError,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(PulseShiftSpacing.SmallLabel))
                Text(error, style = MaterialTheme.typography.bodySmall, color = HardwareRedError)
            }
        }
    }
}

@Composable
private fun RpmGaugeCard(
    rpm: RpmTelemetryState?,
    lighting: LightingState,
    onNavigateToRpm: () -> Unit,
    onNavigateToLighting: () -> Unit
) {
    SectionCard(
        title = "Live Engine Speed",
        subtitle = "CAN 0x301 Byte 0 · ×40 provisional decoder",
        headerAction = {
            StatusPill(
                text = "UNVERIFIED",
                color = HardwareAmberWarning
            )
        }
    ) {
        val available = rpm != null && rpm.isRpmAvailable && !rpm.isStale
        val lightsRendering = rpm?.statusFlags?.isPhysicalShiftLightsEnabled == true

        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (available) "${rpm!!.provisionalRpm}" else "———",
                style = PulseShiftMonoDisplay,
                color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(PulseShiftSpacing.SmallLabel))
            Text(
                "RPM",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                StatusPill(
                    text = when {
                        rpm == null -> "NO SIGNAL"
                        !rpm.statusFlags.isCandidateFresh -> "CAN STALE"
                        else -> "SIGNAL FRESH"
                    },
                    color = when {
                        rpm == null || !rpm.statusFlags.isCandidateFresh -> HardwareRedError
                        else -> HardwareGreenVerified
                    }
                )
                Spacer(Modifier.height(PulseShiftSpacing.Micro))
                Text(
                    text = if (rpm == null) "CAN RX: —" else "CAN RX: ${rpm.totalCanFramesReceived}",
                    style = PulseShiftMonoTiny,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(PulseShiftSpacing.RelatedControls))

        // Prominent 6-LED live preview
        LedStripPreview(
            colors = lighting.ledColors,
            isPowerOn = lighting.isPowerOn,
            brightness = lighting.brightness
        )

        Spacer(Modifier.height(PulseShiftSpacing.RelatedControls))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Profile: ${lighting.effect.displayName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (lightsRendering) "Physical LEDs actively driven" else "LED strip in idle/standby",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = onNavigateToLighting,
                modifier = Modifier.height(38.dp)
            ) {
                Text("Configure")
            }
        }
    }
}

@Composable
private fun LightControlCard(
    lighting: LightingState,
    unsaved: Boolean,
    viewModel: HomeViewModel,
    onNavigateToLighting: () -> Unit
) {
    SectionCard(
        title = "Lighting Status",
        subtitle = "Master switch and global intensity",
        headerAction = {
            if (unsaved) {
                StatusPill(text = "UNSAVED EDITS", color = HardwareAmberWarning)
            } else {
                StatusPill(text = "SYNCED", color = HardwareGreenVerified)
            }
        }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Master Power", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (lighting.isPowerOn) "LEDs enabled" else "LED strip powered off",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = lighting.isPowerOn,
                onCheckedChange = { viewModel.togglePower() }
            )
        }
        Spacer(Modifier.height(PulseShiftSpacing.RelatedControls))
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Master Brightness",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "${(lighting.brightness * 100 / 255)}%",
                style = PulseShiftMonoSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Slider(
            value = lighting.brightness.toFloat(),
            onValueChange = { viewModel.updateBrightness(it.toInt()) },
            valueRange = 0f..255f
        )
        Spacer(Modifier.height(PulseShiftSpacing.SmallLabel))
        Button(
            onClick = onNavigateToLighting,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
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
    SectionCard(title = "Quick Tools") {
        Row(
            horizontalArrangement = Arrangement.spacedBy(PulseShiftSpacing.RelatedControls),
            modifier = Modifier.fillMaxWidth()
        ) {
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
        modifier = modifier
            .height(72.dp)
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(PulseShiftSpacing.SmallLabel)
        ) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(PulseShiftSpacing.Micro))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun QuickPresetsRow(
    presets: List<Preset>,
    onApply: (Preset) -> Unit
) {
    SectionCard(title = "Saved Profiles") {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(PulseShiftSpacing.SmallLabel)) {
            items(presets) { preset ->
                AssistChip(
                    onClick = { onApply(preset) },
                    label = { Text(preset.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.height(PulseShiftSpacing.MinTouchTarget)
                )
            }
        }
    }
}
