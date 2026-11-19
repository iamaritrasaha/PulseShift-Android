package com.pulseshift.app.ui.screens.rpm

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.pulseshift.app.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import com.pulseshift.app.data.ble.BleConnectionState
import com.pulseshift.app.domain.model.LiveTelemetrySnapshot
import com.pulseshift.app.domain.model.telemetryClockMs
import kotlinx.coroutines.delay
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pulseshift.app.domain.model.ConfigSnapshot
import com.pulseshift.app.domain.model.RpmTelemetryState
import com.pulseshift.app.domain.model.ShiftLightConfig
import com.pulseshift.app.ui.theme.PulseShiftMonoTiny
import com.pulseshift.app.ui.theme.PulseShiftRadius
import com.pulseshift.app.ui.theme.PulseShiftShapeTokens
import com.pulseshift.app.ui.components.PulseShiftDetailTopBar
import com.pulseshift.app.ui.theme.PulseShiftSpacing
import java.util.Locale

data class CalibrationPoint(
    val label: String,
    val rawByte: Int,
    val refRpm: Int,
    val ratio: Float
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RpmScreen(
    viewModel: RpmViewModel,
    onNavigateBack: () -> Unit = {}
) {
    val telemetry by viewModel.rpmTelemetryState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val connected = connectionState == BleConnectionState.READY ||
        connectionState == BleConnectionState.RECEIVING_TELEMETRY ||
        connectionState == BleConnectionState.CONNECTED_WAITING_FOR_DATA
    var clockMs by remember { mutableLongStateOf(telemetryClockMs()) }
    LaunchedEffect(connected) {
        while (connected) {
            clockMs = telemetryClockMs()
            delay(250)
        }
    }
    val live = LiveTelemetrySnapshot.from(telemetry, null, connected, maxOf(clockMs, telemetryClockMs()))
    val currentTelemetry = telemetry.takeIf { live.hasFreshRpmPacket }
    val snapshot by viewModel.configSnapshot.collectAsState()
    val config by viewModel.shiftLightConfig.collectAsState()
    val hasUnsavedChanges by viewModel.hasUnsavedChanges.collectAsState()
    val authenticated by viewModel.isAuthenticated.collectAsState()
    val device by viewModel.connectedDevice.collectAsState()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            PulseShiftDetailTopBar(
                title = {
                    Column {
                        Text("RPM & Calibration", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "RPM mapping and CAN decoder",
                            style = PulseShiftMonoTiny,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                onBack = onNavigateBack,
                actions = {
                    if (hasUnsavedChanges) {
                        FilledTonalButton(
                            onClick = { viewModel.saveToController() },
                            shape = PulseShiftShapeTokens.Control,
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text("Save to ESP32", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = PulseShiftSpacing.ScreenHorizontal)
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Basic View") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Advanced Calibration") }
                )
            }

            when (selectedTab) {
                0 -> BasicRpmView(
                    telemetry = currentTelemetry,
                    snapshot = snapshot,
                    config = config,
                    onThresholdChange = { idx, rpm -> viewModel.updateThreshold(idx, rpm) },
                    mappingEditor = {
                        if (connected && snapshot?.lightingSetup != null) {
                            androidx.compose.runtime.key(device?.address) {
                                RpmMappingEditor(snapshot!!, authenticated, viewModel::saveMapping, viewModel::testMapping)
                            }
                        } else if (!connected || snapshot == null) {
                            Text("Connect to the controller to read supported RPM mapping.")
                        } else {
                            Text("This firmware supports grouped RPM stages. Update to edit per-LED thresholds.")
                            val running = (snapshot?.strip?.runningCount ?: device?.capabilities?.physicalLedCount ?: 6).coerceIn(1, 64)
                            val groups = (0 until running).map { it * 6 / running }.toSet()
                            config.thresholds.forEachIndexed { index, rpm ->
                                if (index in groups) ThresholdSliderCard(index, rpm, viewModel::updateThreshold)
                            }
                        }
                    }
                )
                1 -> AdvancedCalibrationView(
                    telemetry = currentTelemetry,
                    snapshot = snapshot,
                    calibrationStatus = viewModel.calibrationStatus.collectAsState().value,
                    calibrationFitResult = viewModel.calibrationFitResult.collectAsState().value,
                    hysteresis = snapshot?.rpmHysteresis ?: 150,
                    onApplyDecoder = { id, byteIdx, ext, mult -> viewModel.applyDecoderConfiguration(id, byteIdx, ext, mult) },
                    onSetHysteresis = { viewModel.setRpmHysteresis(it) },
                    onRefreshCalibration = { viewModel.refreshCalibrationStatus() },
                    onBeginCalibration = { viewModel.beginCalibrationSession() },
                    onCapturePoint = { viewModel.captureCalibrationPoint(it) },
                    onComputeCalibration = { viewModel.computeCalibration(it) },
                    onAbortCalibration = { viewModel.abortCalibrationSession() }
                )
            }
        }
    }
}

@Composable
fun BasicRpmView(
    telemetry: RpmTelemetryState?,
    snapshot: ConfigSnapshot?,
    config: ShiftLightConfig,
    onThresholdChange: (Int, Int) -> Unit,
    mappingEditor: (@Composable () -> Unit)? = null
) {
    val isVerified = telemetry?.statusFlags?.isCalibrationVerified == true
    val isRpmAvailable = telemetry?.isRpmAvailable == true
    val currentRpm = telemetry?.provisionalRpm ?: 0
    val rawByte = telemetry?.rawCandidateCanValue ?: 0

    val sampleAgeText = when {
        telemetry == null || !isRpmAvailable -> "Waiting for CAN data"
        telemetry.rpmSampleAgeMs >= 65535 -> "Stale Data (Age > 65s)"
        else -> "${telemetry.rpmSampleAgeMs} ms"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PulseShiftSpacing.ScreenHorizontal, vertical = PulseShiftSpacing.ScreenVertical)
            .padding(bottom = PulseShiftSpacing.BottomScrollPadding),
        verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.SectionSpacing)
    ) {
        // Primary Estimated RPM Display Card
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
                    Text("Engine RPM Candidate", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (isRpmAvailable) "Live Signal" else "Waiting for CAN data",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isRpmAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                }

                Text(
                    text = if (isRpmAvailable) "$currentRpm RPM" else "—",
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    ),
                    color = if (isRpmAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Raw CAN Byte: ${if (isRpmAvailable) rawByte.toString() else "No Signal"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Sample Age: $sampleAgeText", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Calibration Verification Badge Card
        Card(
            shape = PulseShiftShapeTokens.Card,
            colors = CardDefaults.cardColors(
                containerColor = if (isVerified) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(PulseShiftSpacing.CardPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PulseShiftSpacing.RelatedControls)
            ) {
                Icon(
                    imageVector = if (isVerified) Icons.Rounded.CheckCircle else Icons.Rounded.Info,
                    contentDescription = null,
                    tint = if (isVerified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column {
                    Text(
                        text = if (isVerified) "Firmware Calibration Verified (Cryptographic)" else "CAN Multiplier: ×40.0 — Field verified",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (isVerified)
                            "Live engine RPM verified and cryptographically bound on controller."
                        else
                            "CAN ID 0x301 Byte 0 ×40.0 field verified. Note: Firmware Effect 4 (calibrated shift cues) requires on-device cryptographic calibration binding; Effect 5 provides direct live engine RPM tachometer rendering.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Active Candidate Summary
        Card(
            shape = PulseShiftShapeTokens.Card,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(PulseShiftSpacing.CardPadding), verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.SmallGap)) {
                Text("Current Active Decoder Parameters", style = MaterialTheme.typography.titleSmall)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("CAN Candidate ID", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        snapshot?.let { "0x${it.canCandidateId.toString(16).uppercase()}" } ?: "—",
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Byte Index", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        snapshot?.let { "${it.candidateByteIndex}" } ?: "—",
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("RPM Multiplier", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        snapshot?.let { "${it.rpmMultiplier}" } ?: "—",
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (mappingEditor != null) mappingEditor()
        else config.thresholds.forEachIndexed { index, rpm ->
            ThresholdSliderCard(index, rpm, onThresholdChange)
        }
    }
}

@Composable
fun ThresholdSliderCard(
    index: Int,
    rpm: Int,
    onThresholdChange: (Int, Int) -> Unit
) {
    var localRpm by remember { mutableFloatStateOf(rpm.toFloat()) }
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(rpm) {
        if (!isDragging) {
            localRpm = rpm.toFloat()
        }
    }

    Card(
        shape = PulseShiftShapeTokens.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(PulseShiftSpacing.RelatedControls), verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.SmallGap)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Stage ${index + 1} Threshold", style = MaterialTheme.typography.bodyMedium)
                Text("${localRpm.toInt()} RPM", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = localRpm,
                onValueChange = {
                    isDragging = true
                    localRpm = it
                },
                onValueChangeFinished = {
                    isDragging = false
                    onThresholdChange(index, localRpm.toInt())
                },
                valueRange = 1000f..12000f
            )
        }
    }
}

@Composable
fun AdvancedCalibrationView(
    telemetry: RpmTelemetryState?,
    snapshot: ConfigSnapshot?,
    calibrationStatus: com.pulseshift.app.domain.model.CalibrationSessionStatus?,
    calibrationFitResult: com.pulseshift.app.domain.model.CalibrationFitResult?,
    hysteresis: Int,
    onApplyDecoder: (Long, Int, Boolean, Float) -> Unit,
    onSetHysteresis: (Int) -> Unit,
    onRefreshCalibration: () -> Unit,
    onBeginCalibration: () -> Unit,
    onCapturePoint: (Int) -> Unit,
    onComputeCalibration: (Boolean) -> Unit,
    onAbortCalibration: () -> Unit
) {
    var canIdText by remember(snapshot?.canCandidateId) { mutableStateOf(snapshot?.canCandidateId?.toString(16)?.uppercase() ?: "301") }
    var byteIdxText by remember(snapshot?.candidateByteIndex) { mutableStateOf(snapshot?.candidateByteIndex?.toString() ?: "0") }
    var multText by remember(snapshot?.rpmMultiplier) { mutableStateOf(snapshot?.rpmMultiplier?.toString() ?: "40.0") }
    var isExtFrame by remember(snapshot?.isExtendedFrame) { mutableStateOf(snapshot?.isExtendedFrame ?: false) }

    var canIdError by remember { mutableStateOf<String?>(null) }
    var byteIdxError by remember { mutableStateOf<String?>(null) }
    var multError by remember { mutableStateOf<String?>(null) }

    val rawByte = telemetry?.rawCandidateCanValue ?: 0

    // Multi-Point Calibration Table State
    val calibrationPoints = remember { mutableStateListOf<CalibrationPoint>() }
    var pointLabelInput by remember { mutableStateOf("Idle Point 1") }
    var pointRefRpmInput by remember { mutableStateOf("1500") }

    val calculatedMeanMultiplier = if (calibrationPoints.isNotEmpty()) {
        calibrationPoints.map { it.ratio }.average().toFloat()
    } else null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PulseShiftSpacing.ScreenHorizontal, vertical = PulseShiftSpacing.ScreenVertical)
            .padding(bottom = PulseShiftSpacing.BottomScrollPadding),
        verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.SectionSpacing)
    ) {
        Card(
            shape = PulseShiftShapeTokens.Card,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Decoder Parameters & Target Signal", style = MaterialTheme.typography.titleMedium)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = canIdText,
                        onValueChange = {
                            canIdText = it
                            canIdError = if (it.toLongOrNull(16) == null) "Invalid Hex ID" else null
                        },
                        label = { Text("CAN ID (Hex)") },
                        isError = canIdError != null,
                        supportingText = canIdError?.let { { Text(it) } },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = byteIdxText,
                        onValueChange = {
                            byteIdxText = it
                            val parsed = it.toIntOrNull()
                            byteIdxError = if (parsed == null || parsed !in 0..7) "0-7 only" else null
                        },
                        label = { Text("Byte (0-7)") },
                        isError = byteIdxError != null,
                        supportingText = byteIdxError?.let { { Text(it) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("29-Bit Extended Frame", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = isExtFrame,
                        onCheckedChange = { isExtFrame = it }
                    )
                }

                OutlinedTextField(
                    value = multText,
                    onValueChange = {
                        multText = it
                        val parsed = it.toFloatOrNull()
                        multError = if (parsed == null || parsed <= 0f) "Must be > 0" else null
                    },
                    label = { Text("RPM Multiplier (e.g. 40.0)") },
                    isError = multError != null,
                    supportingText = multError?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        val parsedId = canIdText.toLongOrNull(16)
                        val parsedIdx = byteIdxText.toIntOrNull()
                        val parsedMult = multText.toFloatOrNull()

                        if (parsedId == null) {
                            canIdError = "Invalid Hex ID"
                            return@Button
                        }
                        if (parsedIdx == null || parsedIdx !in 0..7) {
                            byteIdxError = "Must be 0-7"
                            return@Button
                        }
                        if (parsedMult == null || parsedMult <= 0f) {
                            multError = "Must be > 0"
                            return@Button
                        }

                        canIdError = null
                        byteIdxError = null
                        multError = null

                        onApplyDecoder(parsedId, parsedIdx, isExtFrame, parsedMult)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Apply Decoder Parameters")
                }
            }
        }

        // Multi-Point Measurement Workflow Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Experimental: Phone-Side Ratio Calculator", style = MaterialTheme.typography.titleMedium)
                Text(
                    "A notebook helper for comparing raw readings against an external reference. It computes a mean " +
                        "ratio for the decoder fields above — applying it is a decoder change and INVALIDATES any verified " +
                        "calibration. Prefer the firmware-driven workflow below, which binds verification on the device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Current Live Raw CAN Byte:", style = MaterialTheme.typography.bodyMedium)
                    Text("$rawByte", style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.primary)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pointLabelInput,
                        onValueChange = { pointLabelInput = it },
                        label = { Text("Point Name") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = pointRefRpmInput,
                        onValueChange = { pointRefRpmInput = it },
                        label = { Text("Ref RPM") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedButton(
                    onClick = {
                        val refRpm = pointRefRpmInput.toIntOrNull()
                        if (refRpm != null && refRpm > 0 && rawByte > 0) {
                            val ratio = refRpm.toFloat() / rawByte.toFloat()
                            calibrationPoints.add(
                                CalibrationPoint(
                                    label = pointLabelInput.ifBlank { "Point ${calibrationPoints.size + 1}" },
                                    rawByte = rawByte,
                                    refRpm = refRpm,
                                    ratio = ratio
                                )
                            )
                            pointLabelInput = "Point ${calibrationPoints.size + 1}"
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Text("Capture Point (Raw $rawByte)")
                }

                if (calibrationPoints.isNotEmpty()) {
                    Text("Captured Calibration Data Points (${calibrationPoints.size})", style = MaterialTheme.typography.titleSmall)

                    calibrationPoints.forEachIndexed { idx, pt ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${pt.label}: Raw ${pt.rawByte} -> ${pt.refRpm} RPM (Ratio: ${String.format(Locale.US, "%.2f", pt.ratio)})", style = MaterialTheme.typography.bodySmall)
                            IconButton(onClick = { calibrationPoints.removeAt(idx) }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Delete Point", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    calculatedMeanMultiplier?.let { meanMult ->
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Mean Calculated Multiplier: ${String.format(Locale.US, "%.2f", meanMult)}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                Button(
                                    onClick = {
                                        multText = String.format(Locale.US, "%.2f", meanMult)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Apply Calculated Multiplier")
                                }
                            }
                        }
                    }
                }
            }
        }

        // Firmware-driven calibration session (single authoritative pipeline)
        androidx.compose.runtime.LaunchedEffect(Unit) { onRefreshCalibration() }
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Device Calibration Session", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = onRefreshCalibration) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh calibration status")
                    }
                }
                Text(
                    "The ESP32 samples the live decoder for 2 seconds at each operating point, then fits and verifies scale/offset on-device. Points must span at least 1500 RPM (e.g. idle, mid, high).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val session = calibrationStatus
                Text(
                    when {
                        session == null -> "Session status unknown. Tap refresh."
                        session.sessionActive -> "Session ACTIVE - ${session.pointCount} point(s) captured${if (session.captureActive) ", sampling (${session.captureSamples} samples)" else ""}"
                        else -> "No active session."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onBeginCalibration,
                        enabled = session == null || !session.sessionActive
                    ) { Text("Start Session") }
                    OutlinedButton(
                        onClick = onAbortCalibration,
                        enabled = session != null && session.sessionActive
                    ) { Text("Abort") }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = pointRefRpmInput,
                        onValueChange = { pointRefRpmInput = it },
                        label = { Text("Reference RPM") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val ref = pointRefRpmInput.toIntOrNull()
                            if (ref != null && ref in 1..20000) onCapturePoint(ref)
                        },
                        enabled = session != null && session.sessionActive
                    ) { Text("Capture Point") }
                }
                Text(
                    "Set a stable engine RPM with an independent trustworthy tachometer reference, enter that RPM, then tap Capture Point.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onComputeCalibration(false) },
                        enabled = session != null && session.pointCount >= 3
                    ) { Text("Fit Preview") }
                    Button(
                        onClick = { onComputeCalibration(true) },
                        enabled = session != null && session.pointCount >= 3
                    ) { Text("Verify & Apply") }
                }

                calibrationFitResult?.let { fit ->
                    Text(
                        if (fit.success) {
                            "Fit OK: multiplier ${String.format(Locale.US, "%.3f", fit.multiplier)}, offset ${String.format(Locale.US, "%.0f", fit.offset)}, residual ${fit.maxResidualRpm} RPM over ${fit.pointsUsed} points - ${fit.reason}"
                        } else {
                            "Fit rejected: ${fit.reason}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (fit.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (snapshot?.calibrationVerified == true) {
                    Text(
                        "Calibration VERIFIED and bound to the current decoder. RPM bar lighting is enabled.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Threshold hysteresis
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Threshold Hysteresis", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Falling-RPM dead band that prevents LED flicker around a threshold. Currently ${hysteresis} RPM.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                var hystSlider by remember { mutableStateOf(hysteresis.toFloat()) }
                var isDraggingHyst by remember { mutableStateOf(false) }
                LaunchedEffect(hysteresis) {
                    if (!isDraggingHyst) {
                        hystSlider = hysteresis.toFloat()
                    }
                }
                Slider(
                    value = hystSlider,
                    onValueChange = {
                        isDraggingHyst = true
                        hystSlider = it
                    },
                    onValueChangeFinished = {
                        isDraggingHyst = false
                        onSetHysteresis(hystSlider.toInt())
                    },
                    valueRange = 0f..2000f
                )
                Text("${hystSlider.toInt()} RPM", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}
