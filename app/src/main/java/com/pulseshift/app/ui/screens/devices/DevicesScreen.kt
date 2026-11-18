package com.pulseshift.app.ui.screens.devices

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import com.pulseshift.app.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pulseshift.app.data.ble.BleConnectionState
import com.pulseshift.app.domain.model.BleDevice
import com.pulseshift.app.ui.theme.PulseShiftRadius
import com.pulseshift.app.ui.theme.PulseShiftShapeTokens
import com.pulseshift.app.ui.components.PulseShiftDetailTopBar
import com.pulseshift.app.ui.theme.PulseShiftSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    viewModel: DevicesViewModel,
    onNavigateBack: () -> Unit = {}
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val connectedDevice by viewModel.connectedDevice.collectAsState()
    // Auth state collected at screen scope, not inside LazyColumn items.
    val authState by viewModel.authState.collectAsState()
    val isAuthenticated by viewModel.isAuthenticated.collectAsState()
    val enrollmentMessage by viewModel.enrollmentMessage.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            PulseShiftDetailTopBar(
                title = { Text("Hardware & Bluetooth", style = MaterialTheme.typography.titleLarge) },
                onBack = onNavigateBack,
                actions = {
                    if (connectionState == BleConnectionState.SCANNING) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    } else {
                        IconButton(
                            onClick = { viewModel.startScan() },
                            modifier = Modifier.size(PulseShiftSpacing.MinTouchTarget)
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Scan for ESP32")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = PulseShiftSpacing.ScreenHorizontal,
                end = PulseShiftSpacing.ScreenHorizontal,
                top = PulseShiftSpacing.ScreenVertical,
                bottom = PulseShiftSpacing.BottomScrollPadding
            ),
            verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.SectionSpacing)
        ) {
            // Hardware Bluetooth Connection State Pipeline Banner
            item {
                Card(
                    shape = PulseShiftShapeTokens.Card,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(PulseShiftSpacing.CardPadding),
                        verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.RelatedControls)
                    ) {
                        Text(
                            text = "Physical Hardware Interface",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )

                        Text(
                            text = getPipelineDescription(connectionState),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )

                        StatePipelineProgressRow(currentState = connectionState)
                    }
                }
            }

            // Connected Device Section
            if (connectedDevice != null) {
                item {
                    Text("Connected Controller", style = MaterialTheme.typography.titleMedium)
                    ConnectedDeviceCard(
                        device = connectedDevice!!,
                        onDisconnect = { viewModel.disconnect() }
                    )
                }
                item {
                    OwnerEnrollmentCard(
                        authState = authState,
                        isAuthenticated = isAuthenticated,
                        enrollmentMessage = enrollmentMessage,
                        onEnroll = { viewModel.enrollOwner(it) },
                        onAuthenticate = { viewModel.authenticate() },
                        onClearCredential = { viewModel.clearStoredCredential() },
                        onDismissMessage = { viewModel.clearEnrollmentMessage() }
                    )
                }
            }

            // Discovered Devices Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Discovered Devices", style = MaterialTheme.typography.titleMedium)
                    if (connectionState != BleConnectionState.SCANNING) {
                        FilledTonalButton(
                            onClick = { viewModel.startScan() },
                            shape = PulseShiftShapeTokens.Control
                        ) {
                            Text("Scan Now")
                        }
                    }
                }
            }

            // Discovered Devices List
            if (discoveredDevices.isEmpty()) {
                item {
                    Card(
                        shape = PulseShiftShapeTokens.Card,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(PulseShiftSpacing.SectionSpacing),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.RelatedControls)
                        ) {
                            Icon(
                                Icons.Rounded.Bluetooth,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            )
                            Text(
                                text = if (connectionState == BleConnectionState.SCANNING) "Scanning for PulseShift controllers (UUID fe12d210-...)..." else "No PulseShift controllers found nearby. Tap 'Scan Now' to discover.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(discoveredDevices) { device ->
                    DiscoveredDeviceItem(
                        device = device,
                        onConnect = { viewModel.connectDevice(device.address) }
                    )
                }
            }
        }
    }
}

@Composable
fun StatePipelineProgressRow(currentState: BleConnectionState) {
    val states = listOf(
        BleConnectionState.SCANNING,
        BleConnectionState.CONNECTING,
        BleConnectionState.DISCOVERING_SERVICES,
        BleConnectionState.SUBSCRIBING,
        BleConnectionState.RECEIVING_TELEMETRY
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        states.forEach { state ->
            val isActive = stateOrdinal(currentState) >= stateOrdinal(state)
            val label = when (state) {
                BleConnectionState.SCANNING -> "Scan"
                BleConnectionState.CONNECTING -> "Connect"
                BleConnectionState.DISCOVERING_SERVICES -> "Services"
                BleConnectionState.SUBSCRIBING -> "Subscribe"
                BleConnectionState.RECEIVING_TELEMETRY -> "Telemetry"
                else -> ""
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun stateOrdinal(state: BleConnectionState): Int {
    return when (state) {
        BleConnectionState.SCANNING, BleConnectionState.DEVICE_DISCOVERED -> 1
        BleConnectionState.CONNECTING -> 2
        BleConnectionState.DISCOVERING_SERVICES -> 3
        BleConnectionState.SUBSCRIBING, BleConnectionState.CONNECTED_WAITING_FOR_DATA -> 4
        BleConnectionState.RECEIVING_TELEMETRY, BleConnectionState.READY -> 5
        else -> 0
    }
}

private fun getPipelineDescription(state: BleConnectionState): String {
    return when (state) {
        BleConnectionState.UNSUPPORTED -> "Bluetooth Low Energy is unsupported on this mobile device."
        BleConnectionState.BLUETOOTH_DISABLED -> "Bluetooth is disabled. Please turn on Bluetooth in Android Settings."
        BleConnectionState.PERMISSION_REQUIRED -> "Location or Nearby Devices permission required for BLE scan."
        BleConnectionState.DISCONNECTED -> "Disconnected. Tap 'Scan Now' to search for ESP32 advertising Service fe12d210-..."
        BleConnectionState.SCANNING -> "Scanning for nearby PulseShift controllers..."
        BleConnectionState.DEVICE_DISCOVERED -> "PulseShift device discovered! Ready to connect."
        BleConnectionState.CONNECTING -> "Establishing GATT connection to ESP32..."
        BleConnectionState.DISCOVERING_SERVICES -> "Discovering GATT Services and Characteristics..."
        BleConnectionState.SUBSCRIBING -> "Subscribing to notifications (RPM: fe12d211, Health: fe12d212, Config: fe12d216/fe12d217)..."
        BleConnectionState.CONNECTED_WAITING_FOR_DATA -> "Connected! Waiting for initial telemetry snapshot..."
        BleConnectionState.RECEIVING_TELEMETRY, BleConnectionState.READY -> "Connected & receiving live PulseShift BLE telemetry!"
        BleConnectionState.ERROR -> "Connection failed or interrupted. Please re-scan and try again."
    }
}

@Composable
fun OwnerEnrollmentCard(
    authState: com.pulseshift.app.domain.model.AuthState,
    isAuthenticated: Boolean,
    enrollmentMessage: String?,
    onEnroll: (ByteArray) -> Unit,
    onAuthenticate: () -> Unit,
    onClearCredential: () -> Unit = {},
    onDismissMessage: () -> Unit
) {
    val secureRandom = java.security.SecureRandom()
    var confirmEnrollment by remember { mutableStateOf(false) }
    var confirmClearKey by remember { mutableStateOf(false) }

    if (confirmEnrollment) {
        com.pulseshift.app.ui.components.ConfirmDialog(
            title = "Enroll as Owner?",
            text = "Re-enrollment overwrites the credential previously stored on the ESP32 " +
                "(this is the documented credential-recovery path). If another phone enrolled " +
                "this device, that phone will lose access. Continue only while the 60 s BOOT " +
                "window is open (cyan pulse on the strip).",
            confirmLabel = "Enroll",
            onConfirm = {
                confirmEnrollment = false
                val secret = ByteArray(12)
                secureRandom.nextBytes(secret)
                onEnroll(secret)
            },
            onDismiss = { confirmEnrollment = false }
        )
    }

    if (confirmClearKey) {
        com.pulseshift.app.ui.components.ConfirmDialog(
            title = "Reset Stored Credential?",
            text = "This removes the owner credential stored on this phone for this device. " +
                "You will need to press the physical BOOT button on the ESP32 and enroll again to perform protected operations.",
            confirmLabel = "Reset Credential",
            onConfirm = {
                confirmClearKey = false
                onClearCredential()
            },
            onDismiss = { confirmClearKey = false }
        )
    }

    Card(
        shape = PulseShiftShapeTokens.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(PulseShiftSpacing.CardPadding),
            verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.RelatedControls)
        ) {
            Text("Owner Authentication", style = MaterialTheme.typography.titleMedium)
            Text(
                text = when {
                    isAuthenticated -> "Authenticated owner session active. Protected commands are available."
                    authState == com.pulseshift.app.domain.model.AuthState.NOT_ENROLLED ->
                        "This device has no owner yet (or was re-flashed). Press the physical BOOT button on the ESP32, then tap 'Enroll as Owner' within 60 seconds."
                    authState == com.pulseshift.app.domain.model.AuthState.AUTHENTICATION_REJECTED ->
                        "Authentication rejected: ESP32 credentials do not match this phone (e.g. after re-flashing). Press the physical BOOT button on the ESP32, then tap 'Enroll as Owner' to recover ownership."
                    authState == com.pulseshift.app.domain.model.AuthState.SESSION_EXPIRED ->
                        "Session expired. Tap 'Re-authenticate' to re-authorize the link."
                    authState == com.pulseshift.app.domain.model.AuthState.BOOT_AUTHORIZATION_REQUIRED ->
                        "Physical BOOT authorization required on ESP32. Press BOOT button and try again."
                    authState == com.pulseshift.app.domain.model.AuthState.AUTHENTICATING ->
                        "Authenticating owner session with ESP32…"
                    authState == com.pulseshift.app.domain.model.AuthState.ENROLLING ->
                        "Enrolling owner credentials on ESP32…"
                    else -> "Enrolled. The device is authenticated automatically on connect; use 'Re-authenticate' to retry."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(PulseShiftSpacing.SmallGap)) {
                Button(
                    onClick = { confirmEnrollment = true },
                    shape = PulseShiftShapeTokens.Control,
                    modifier = Modifier.heightIn(min = 38.dp)
                ) { Text("Enroll as Owner") }
                OutlinedButton(
                    onClick = onAuthenticate,
                    shape = PulseShiftShapeTokens.Control,
                    modifier = Modifier.heightIn(min = 38.dp)
                ) { Text("Re-authenticate") }
                OutlinedButton(
                    onClick = { confirmClearKey = true },
                    shape = PulseShiftShapeTokens.Control,
                    modifier = Modifier.heightIn(min = 38.dp)
                ) { Text("Reset Key") }
            }

            enrollmentMessage?.let { message ->
                Text(message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = onDismissMessage) { Text("Dismiss") }
            }
        }
    }
}

@Composable
fun ConnectedDeviceCard(
    device: BleDevice,
    onDisconnect: () -> Unit
) {
    Card(
        shape = PulseShiftShapeTokens.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PulseShiftSpacing.RelatedControls)) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF101014))
                            .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_pulseshift_logo_round),
                            contentDescription = "PulseShift Controller",
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Column {
                        Text(device.name ?: "PulseShift Controller", style = MaterialTheme.typography.titleMedium)
                        Text(device.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                IconButton(
                    onClick = onDisconnect,
                    modifier = Modifier.size(PulseShiftSpacing.MinTouchTarget)
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Disconnect")
                }
            }

            Text("Verified Protocol & Capabilities", style = MaterialTheme.typography.labelMedium)

            CapabilityRow(
                "Protocol / Firmware",
                device.firmwareVersion?.let { "v$it" } ?: "unknown (V1 telemetry only)",
                isSupported = device.capabilities.hasConfigV2Support
            )
            CapabilityRow(
                "Config V2 (writes, snapshots, auth)",
                if (device.capabilities.hasConfigV2Support) "Active" else "Not supported by this firmware",
                isSupported = device.capabilities.hasConfigV2Support
            )
            CapabilityRow(
                "CAN RPM Multiplier",
                "×40.0 — Field verified",
                isSupported = true
            )
            CapabilityRow(
                "Firmware Calibration (Effect 4)",
                if (device.capabilities.supportsLiveRpmVerified) "Cryptographically Verified" else "Not calibrated (Effect 5 live preview active)",
                isSupported = device.capabilities.supportsLiveRpmVerified
            )
            CapabilityRow(
                "Capture stage / LED bench test",
                listOfNotNull(
                    if (device.capabilities.supportsCaptureStage) "stage" else null,
                    if (device.capabilities.supportsLedBenchTest) "bench" else null
                ).takeIf { it.isNotEmpty() }?.joinToString(" + ") ?: "not advertised",
                isSupported = device.capabilities.supportsCaptureStage || device.capabilities.supportsLedBenchTest
            )
        }
    }
}

@Composable
fun CapabilityRow(label: String, detail: String, isSupported: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(
                imageVector = if (isSupported) Icons.Rounded.CheckCircle else Icons.Rounded.Close,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (isSupported) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
fun DiscoveredDeviceItem(
    device: BleDevice,
    onConnect: () -> Unit
) {
    Card(
        shape = PulseShiftShapeTokens.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PulseShiftSpacing.CardPaddingCompact),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(device.name ?: "PulseShift Controller", style = MaterialTheme.typography.titleMedium)
                Text("${device.address} · ${device.rssi} dBm", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Button(
                onClick = onConnect,
                shape = PulseShiftShapeTokens.Control,
                modifier = Modifier.heightIn(min = 38.dp)
            ) {
                Text("Connect")
            }
        }
    }
}
