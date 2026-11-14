package com.pulseshift.app.ui.screens.devices

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pulseshift.app.data.ble.BleConnectionState
import com.pulseshift.app.domain.model.BleDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    viewModel: DevicesViewModel
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val connectedDevice by viewModel.connectedDevice.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Hardware & Bluetooth", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    if (connectionState == BleConnectionState.SCANNING) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    } else {
                        IconButton(onClick = { viewModel.startScan() }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Scan for ESP32")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Hardware Bluetooth Connection State Pipeline Banner
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
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
                            Text(
                                text = "Physical Hardware Interface",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        // Detailed Connection State Pipeline
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
                        authState = viewModel.authState.collectAsState().value,
                        isAuthenticated = viewModel.isAuthenticated.collectAsState().value,
                        enrollmentMessage = viewModel.enrollmentMessage.collectAsState().value,
                        onEnroll = { viewModel.enrollOwner(it) },
                        onAuthenticate = { viewModel.authenticate() },
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
                            shape = RoundedCornerShape(12.dp)
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
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Rounded.Bluetooth, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.outline)
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
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
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
    onDismissMessage: () -> Unit
) {
    val secureRandom = java.security.SecureRandom()

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Owner Authentication", style = MaterialTheme.typography.titleMedium)
            Text(
                text = when {
                    isAuthenticated -> "Authenticated owner session active. Protected commands are available."
                    authState == com.pulseshift.app.domain.model.AuthState.NOT_ENROLLED ->
                        "This device has no owner yet. Press the BOOT button on the ESP32, then enroll within 60 seconds."
                    else -> "Enrolled. The device is authenticated automatically on connect; use the button below to retry."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        // Fresh 12-byte secret per enrollment; never hard-coded.
                        val secret = ByteArray(12)
                        secureRandom.nextBytes(secret)
                        onEnroll(secret)
                    }
                ) { Text("Enroll as Owner") }
                OutlinedButton(onClick = onAuthenticate) { Text("Re-authenticate") }
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
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.BluetoothConnected, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text(device.name ?: "PulseShift Controller", style = MaterialTheme.typography.titleMedium)
                        Text(device.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                IconButton(onClick = onDisconnect) {
                    Icon(Icons.Rounded.Close, contentDescription = "Disconnect")
                }
            }

            Text("Verified Protocol & Capabilities", style = MaterialTheme.typography.labelMedium)

            CapabilityRow("PulseShift Protocol Version", device.firmwareVersion ?: "v1.0 (PulseShift BLE Monitoring)", isSupported = true)
            CapabilityRow("Hardware Configuration V2", if (device.capabilities.hasConfigV2Support) "V2 Config Active" else "Update Required", isSupported = device.capabilities.hasConfigV2Support)
            CapabilityRow("6-LED WS2812B Engine", "6 Addressable LEDs", isSupported = device.capabilities.supportsIndividualRgb)
            CapabilityRow("NVS Flash Storage", "Persistent", isSupported = device.capabilities.supportsNvsPersistence)
            CapabilityRow("Crankshaft Calibration", "Unverified on NS125", isSupported = device.capabilities.supportsLiveRpmVerified)
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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(device.name ?: "PulseShift Controller", style = MaterialTheme.typography.titleMedium)
                Text("${device.address} · ${device.rssi} dBm", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Button(
                onClick = onConnect,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Connect")
            }
        }
    }
}
