package com.pulseshift.app.ui.screens.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pulseshift.app.data.ble.BleConnectionState
import com.pulseshift.app.domain.model.BleDevice
import com.pulseshift.app.domain.model.LiveTelemetrySnapshot
import com.pulseshift.app.domain.model.telemetryClockMs
import com.pulseshift.app.domain.model.LightingState
import com.pulseshift.app.domain.model.Preset
import com.pulseshift.app.domain.model.RpmTelemetryState
import com.pulseshift.app.domain.model.ShiftLightConfig
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import java.util.Locale
import kotlinx.coroutines.delay
import com.pulseshift.app.ui.components.LedStripPreview
import com.pulseshift.app.ui.components.PulseShiftSlider
import com.pulseshift.app.ui.components.SectionCard
import com.pulseshift.app.ui.components.StatusDot
import com.pulseshift.app.ui.theme.DarkTextSecondary
import com.pulseshift.app.ui.theme.HardwareAmberWarning
import com.pulseshift.app.ui.theme.HardwareGreenVerified
import com.pulseshift.app.ui.theme.HardwareInactiveOff
import com.pulseshift.app.ui.theme.HardwareRedError
import com.pulseshift.app.ui.theme.HardwareTealActive
import com.pulseshift.app.ui.theme.PulseShiftMonoLarge
import com.pulseshift.app.ui.theme.PulseShiftMonoSmall
import com.pulseshift.app.ui.theme.PulseShiftMonoTiny

import com.pulseshift.app.ui.components.StatusPill
import com.pulseshift.app.ui.theme.PulseShiftMonoDisplay
import com.pulseshift.app.ui.theme.PulseShiftRadius
import com.pulseshift.app.ui.theme.PulseShiftShapeTokens
import com.pulseshift.app.ui.theme.PulseShiftSpacing
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
    val canHealth by viewModel.canHealthState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val telemetryConnected = connectionState == BleConnectionState.READY ||
        connectionState == BleConnectionState.RECEIVING_TELEMETRY ||
        connectionState == BleConnectionState.CONNECTED_WAITING_FOR_DATA
    var clockMs by remember { mutableLongStateOf(telemetryClockMs()) }
    LaunchedEffect(telemetryConnected) {
        while (telemetryConnected) {
            clockMs = telemetryClockMs()
            delay(250)
        }
    }
    val live = LiveTelemetrySnapshot.from(rpm, canHealth, telemetryConnected, maxOf(clockMs, telemetryClockMs()))
    val shiftConfig by viewModel.shiftLightConfig.collectAsState()
    val presets by viewModel.presets.collectAsState()
    val unsaved by viewModel.hasUnsavedChanges.collectAsState()
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
            item { BatteryVoltageCard(live, telemetryConnected) }
            item {
                RpmGaugeCard(
                    rpm = rpm.takeIf { live.hasFreshRpmPacket },
                    shiftConfig = shiftConfig,
                    lighting = lighting,
                    onNavigateToRpm = onNavigateToRpm,
                    onNavigateToLighting = onNavigateToLighting
                )
            }
            item { ControllerHealthCard(live, telemetryConnected) }
            item { CaptureStorageCard(live) }
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
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )
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
                modifier = Modifier.heightIn(min = 36.dp),
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RpmGaugeCard(
    rpm: RpmTelemetryState?,
    shiftConfig: ShiftLightConfig,
    lighting: LightingState,
    onNavigateToRpm: () -> Unit,
    onNavigateToLighting: () -> Unit
) {
    SectionCard(
        title = "Live Engine Speed",
        subtitle = "CAN 0x301 Byte 0 · ×40.0 — Field verified",
        headerAction = {
            StatusPill(
                text = if (rpm?.statusFlags?.isCalibrationVerified == true) "CALIBRATED" else "FIELD VERIFIED",
                color = HardwareGreenVerified
            )
        }
    ) {
        val available = rpm != null && rpm.isRpmAvailable && !rpm.isStale
        val currentRpm = if (available) rpm!!.provisionalRpm else 0
        val lightsRendering = rpm?.statusFlags?.isPhysicalShiftLightsEnabled == true

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(PulseShiftSpacing.SmallLabel),
            verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.SmallLabel),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (available) String.format(Locale.US, "%,d", currentRpm) else "—",
                style = PulseShiftMonoDisplay,
                color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
            Text("RPM", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.CenterVertically))
            StatusPill(
                text = when {
                    rpm == null -> "No signal"
                    available -> "Signal fresh"
                    else -> "RPM unavailable"
                },
                color = if (available) HardwareGreenVerified else HardwareAmberWarning,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
        }

        Spacer(Modifier.height(10.dp))

        // 2. High-Tech Visual Tachometer Bar
        val maxScaleRpm = maxOf(8000, ((shiftConfig.warningRpm + 1500) / 1000) * 1000)
        val animatedProgress by animateFloatAsState(
            targetValue = (currentRpm.toFloat() / maxScaleRpm).coerceIn(0f, 1f),
            animationSpec = tween(durationMillis = 100),
            label = "tachometerProgress"
        )
        val warningProgress = (shiftConfig.warningRpm.toFloat() / maxScaleRpm).coerceIn(0f, 1f)

        Column(modifier = Modifier.fillMaxWidth()) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF14141E))
            ) {
                val barWidth = size.width
                val barHeight = size.height

                // Draw background track with subtle segment lines
                val numTicks = maxScaleRpm / 1000
                for (i in 1 until numTicks) {
                    val tickX = barWidth * (i.toFloat() / numTicks)
                    drawLine(
                        color = Color(0xFF2A2A38),
                        start = Offset(tickX, 0f),
                        end = Offset(tickX, barHeight),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                // Draw Warning / Redline zone background
                val redlineStartX = barWidth * warningProgress
                if (redlineStartX < barWidth) {
                    drawRect(
                        color = HardwareRedError.copy(alpha = 0.25f),
                        topLeft = Offset(redlineStartX, 0f),
                        size = Size(barWidth - redlineStartX, barHeight)
                    )
                    // Redline marker
                    drawLine(
                        color = HardwareRedError,
                        start = Offset(redlineStartX, 0f),
                        end = Offset(redlineStartX, barHeight),
                        strokeWidth = 2.dp.toPx()
                    )
                }

                // Draw Active Live Progress Bar with Gradient
                if (animatedProgress > 0.005f) {
                    val activeWidth = barWidth * animatedProgress
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF00E676), // Bright Green
                                Color(0xFFFFD600), // Amber / Yellow
                                Color(0xFFFF1744)  // Shift Red
                            ),
                            startX = 0f,
                            endX = barWidth
                        ),
                        topLeft = Offset.Zero,
                        size = Size(activeWidth, barHeight),
                        cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // Tachometer scale labels (0 to max)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("0", style = PulseShiftMonoTiny, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${maxScaleRpm / 2000}k", style = PulseShiftMonoTiny, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = "SHIFT ${shiftConfig.warningRpm}",
                    style = PulseShiftMonoTiny,
                    fontWeight = FontWeight.Bold,
                    color = if (available && currentRpm >= shiftConfig.warningRpm) HardwareRedError else MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                )
                Text("${maxScaleRpm / 1000}k", style = PulseShiftMonoTiny, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(12.dp))

        // 3. Progressive 6-LED Shift Indicators (Dynamic Threshold Visualization)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val thresholds = shiftConfig.thresholds.ifEmpty { listOf(1000, 2000, 3000, 4000, 5000, 6000) }
            for (i in 0 until 6) {
                val thresh = thresholds.getOrElse(i) { (i + 1) * 1000 }
                val isThresholdReached = available && (currentRpm >= thresh)
                val isRedline = available && (currentRpm >= shiftConfig.warningRpm)

                val ledColor = when {
                    isRedline -> HardwareRedError
                    isThresholdReached -> when (i) {
                        0, 1 -> Color(0xFF00E676) // Green
                        2, 3 -> Color(0xFFFFD600) // Amber
                        4 -> Color(0xFFFF9100)    // Orange
                        else -> HardwareRedError   // Red
                    }
                    else -> Color(0xFF1E1E28)
                }

                Surface(
                    modifier = Modifier.weight(1f).heightIn(min = 30.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = if (isThresholdReached) ledColor.copy(alpha = 0.25f) else Color(0xFF13131A),
                    border = BorderStroke(
                        1.5.dp,
                        if (isThresholdReached) ledColor else Color(0xFF262634)
                    )
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = if (thresh % 1000 == 0) "${thresh / 1000}k" else String.format(Locale.US, "%.1fk", thresh / 1000f),
                            style = PulseShiftMonoTiny,
                            fontWeight = if (isThresholdReached) FontWeight.Bold else FontWeight.Normal,
                            color = if (isThresholdReached) ledColor else DarkTextSecondary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(PulseShiftSpacing.RelatedControls))

        // 4. Prominent 6-LED live preview
        LedStripPreview(
            colors = lighting.ledColors,
            isPowerOn = lighting.isPowerOn,
            brightness = lighting.brightness
        )

        Spacer(Modifier.height(PulseShiftSpacing.RelatedControls))

        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column {
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onNavigateToRpm,
                    modifier = Modifier.heightIn(min = 38.dp)
                ) {
                    Text("Tuning")
                }
                Button(
                    onClick = onNavigateToLighting,
                    modifier = Modifier.heightIn(min = 38.dp)
                ) {
                    Text("Studio")
                }
            }
        }
    }
}

@Composable
internal fun BatteryVoltageCard(state: LiveTelemetrySnapshot, connected: Boolean) {
    SectionCard(title = "Live Vehicle Data") {
        Text("Battery Voltage", style = MaterialTheme.typography.labelLarge)
        val volts = state.batteryVolts
        Text(
            text = volts?.let { String.format(Locale.US, "%.1f V", it) } ?: "— V",
            style = PulseShiftMonoLarge,
            color = if (volts != null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
        )
        val raw = state.voltageCandidateRaw
        Text(
            text = if (raw != null) "CAN 0x303 · Byte 1 · Raw 0x%02X".format(Locale.US, raw)
                else when {
                    !connected -> "Connect to the ESP32 for live voltage."
                    state.hasFreshRpmPacket && !state.voltageProbeSupported ->
                        "The installed firmware does not report this signal."
                    else -> "Waiting for a supported, fresh CAN signal."
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (raw != null) {
            Text("Live engine battery telemetry", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ControllerHealthCard(state: LiveTelemetrySnapshot, connected: Boolean) {
    SectionCard(title = "CAN & Controller") {
        Text(if (connected) "BLE connected" else "BLE disconnected")
        LiveMetricRow("CAN frames received", state.receivedFrames?.toString() ?: "—")
        LiveMetricRow("CAN traffic", state.trafficActive?.let { if (it) "Recent traffic" else "Idle" } ?: "—")
        LiveMetricRow("CAN bus", state.canHealthy?.let { if (it) "Operational" else "Unavailable" } ?: "—")
        LiveMetricRow("ESP32 uptime", state.uptimeSeconds?.let { "$it s" } ?: "—")
    }
}

@Composable
private fun CaptureStorageCard(state: LiveTelemetrySnapshot) {
    SectionCard(title = "Recording & Storage") {
        LiveMetricRow("Recording", state.recordingActive?.let { if (it) "Active" else "Stopped" } ?: "—")
        LiveMetricRow("Session frames", state.recordedThisSession?.toString() ?: "—")
        LiveMetricRow("Prior capture", state.capturePreserved?.let { if (it) "Preserved" else "None reported" } ?: "—")
        LiveMetricRow("LittleFS", when {
            state.storageMounted == null -> "—"
            !state.storageMounted -> "Unavailable"
            state.storageFull == true -> "Full"
            else -> "Mounted"
        })
    }
}

@Composable
private fun LiveMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
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
        PulseShiftSlider(
            value = lighting.brightness.toFloat(),
            onValueChange = { viewModel.updateBrightness(it.toInt()) },
            valueRange = 0f..255f
        )
        Spacer(Modifier.height(PulseShiftSpacing.SmallLabel))
        Button(
            onClick = onNavigateToLighting,
            shape = PulseShiftShapeTokens.Control,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = PulseShiftSpacing.MinTouchTarget)
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
            .heightIn(min = 72.dp)
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = PulseShiftShapeTokens.Control
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
                    modifier = Modifier.heightIn(min = PulseShiftSpacing.MinTouchTarget)
                )
            }
        }
    }
}
