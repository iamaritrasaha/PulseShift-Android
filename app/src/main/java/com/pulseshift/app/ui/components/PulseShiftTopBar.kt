package com.pulseshift.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pulseshift.app.R
import com.pulseshift.app.data.ble.BleConnectionState
import com.pulseshift.app.ui.theme.HardwareAmberWarning
import com.pulseshift.app.ui.theme.HardwareGreenVerified
import com.pulseshift.app.ui.theme.PulseShiftMonoSmall

@Composable
fun PulseShiftTopBar(
    connectionState: BleConnectionState,
    isAuthenticated: Boolean,
    onNavigateToDashboard: () -> Unit,
    onNavigateToDevices: () -> Unit,
    onNavigateToTerminal: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isConnected = connectionState == BleConnectionState.READY ||
        connectionState == BleConnectionState.RECEIVING_TELEMETRY ||
        connectionState == BleConnectionState.CONNECTED_WAITING_FOR_DATA
    val isBusy = connectionState == BleConnectionState.CONNECTING ||
        connectionState == BleConnectionState.SCANNING
    val status = when {
        connectionState == BleConnectionState.SCANNING -> "Scanning"
        connectionState == BleConnectionState.CONNECTING -> "Connecting"
        isConnected && isAuthenticated -> "Live"
        isConnected -> "Authentication required"
        else -> "Offline"
    }
    val statusColor = when {
        isConnected && isAuthenticated -> HardwareGreenVerified
        isConnected || isBusy -> HardwareAmberWarning
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
        modifier = modifier.fillMaxWidth()
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            val fontScale = LocalDensity.current.fontScale
            val showWordmark = (maxWidth - 144.dp) >= 150.dp * fontScale
            val showStatusLabel = maxWidth >= 400.dp && fontScale <= 1.2f

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onNavigateToDashboard) {
                        Image(
                            painter = painterResource(R.drawable.ic_pulseshift_logo_round),
                            contentDescription = "Go to Dashboard",
                            modifier = Modifier.size(36.dp).clip(CircleShape)
                        )
                    }
                    if (showWordmark) {
                        Text(
                            text = "PulseShift",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (showStatusLabel) {
                    Surface(
                        onClick = onNavigateToDevices,
                        shape = CircleShape,
                        color = statusColor.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.4f)),
                        modifier = Modifier.semantics {
                            contentDescription = "Bluetooth devices"
                            stateDescription = status
                        }
                    ) {
                        Text(
                            text = if (isConnected && !isAuthenticated) "Auth" else status,
                            style = PulseShiftMonoSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                } else {
                    IconButton(
                        onClick = onNavigateToDevices,
                        modifier = Modifier.semantics {
                            contentDescription = "Bluetooth devices"
                            stateDescription = status
                        }
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = statusColor
                            )
                        } else {
                            Icon(
                                if (isConnected) Icons.Rounded.BluetoothConnected else Icons.Rounded.Bluetooth,
                                contentDescription = null,
                                tint = statusColor
                            )
                        }
                    }
                }
                IconButton(onClick = onNavigateToTerminal) {
                    Icon(Icons.Rounded.Terminal, contentDescription = "Open Terminal")
                }
                IconButton(onClick = onNavigateToSettings) {
                    Icon(Icons.Rounded.Settings, contentDescription = "Open Settings")
                }
            }
        }
    }
}
