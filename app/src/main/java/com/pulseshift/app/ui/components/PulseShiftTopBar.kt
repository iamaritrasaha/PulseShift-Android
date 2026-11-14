package com.pulseshift.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseshift.app.R
import com.pulseshift.app.data.ble.BleConnectionState
import com.pulseshift.app.ui.theme.HardwareAmberWarning
import com.pulseshift.app.ui.theme.HardwareGreenVerified
import com.pulseshift.app.ui.theme.HardwareRedError
import com.pulseshift.app.ui.theme.PulseShiftMonoSmall
import com.pulseshift.app.ui.theme.PulseShiftMonoTiny
import com.pulseshift.app.ui.theme.PulseShiftSpacing

/**
 * World-class technical motorcycle cockpit header.
 * Displays brand emblem, connection status capsule, and quick access actions.
 */
@Composable
fun PulseShiftTopBar(
    connectionState: BleConnectionState,
    isAuthenticated: Boolean,
    deviceName: String? = null,
    onNavigateToDevices: () -> Unit,
    onNavigateToTerminal: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isConnected = connectionState == BleConnectionState.READY ||
            connectionState == BleConnectionState.RECEIVING_TELEMETRY ||
            connectionState == BleConnectionState.CONNECTED_WAITING_FOR_DATA

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = PulseShiftSpacing.ScreenHorizontal, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Brand Logo & Wordmark
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onNavigateToDevices() }
                    .padding(vertical = 4.dp, horizontal = 4.dp)
            ) {
                // High-resolution logo emblem
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF121216))
                        .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_pulseshift_logo_round),
                        contentDescription = "PulseShift Brand Logo",
                        modifier = Modifier.size(34.dp)
                    )
                }

                Spacer(Modifier.width(10.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "PULSE",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "SHIFT",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Light,
                            letterSpacing = 1.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = "NS125 CAN • ESP32",
                        style = PulseShiftMonoTiny,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Right side: Status Capsule & Quick Actions
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Interactive Status Capsule (tap opens Bluetooth screen)
                Surface(
                    onClick = onNavigateToDevices,
                    shape = RoundedCornerShape(20.dp),
                    color = when {
                        isConnected -> if (isAuthenticated) HardwareGreenVerified.copy(alpha = 0.12f)
                                       else HardwareAmberWarning.copy(alpha = 0.15f)
                        connectionState == BleConnectionState.CONNECTING || connectionState == BleConnectionState.SCANNING -> HardwareAmberWarning.copy(alpha = 0.12f)
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    },
                    border = BorderStroke(
                        1.dp,
                        when {
                            isConnected -> if (isAuthenticated) HardwareGreenVerified.copy(alpha = 0.4f)
                                           else HardwareAmberWarning.copy(alpha = 0.5f)
                            connectionState == BleConnectionState.CONNECTING || connectionState == BleConnectionState.SCANNING -> HardwareAmberWarning.copy(alpha = 0.35f)
                            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        when {
                            connectionState == BleConnectionState.SCANNING -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(10.dp),
                                    strokeWidth = 1.5.dp,
                                    color = HardwareAmberWarning
                                )
                                Text("SCAN", style = PulseShiftMonoTiny, color = HardwareAmberWarning)
                            }
                            connectionState == BleConnectionState.CONNECTING -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(10.dp),
                                    strokeWidth = 1.5.dp,
                                    color = HardwareAmberWarning
                                )
                                Text("LINKING", style = PulseShiftMonoTiny, color = HardwareAmberWarning)
                            }
                            isConnected -> {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (isAuthenticated) HardwareGreenVerified else HardwareAmberWarning)
                                )
                                Text(
                                    text = if (isAuthenticated) "LIVE" else "AUTH",
                                    style = PulseShiftMonoTiny,
                                    color = if (isAuthenticated) HardwareGreenVerified else HardwareAmberWarning
                                )
                            }
                            else -> {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(HardwareRedError.copy(alpha = 0.8f))
                                )
                                Text("OFFLINE", style = PulseShiftMonoTiny, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                // Quick Terminal Action
                IconButton(
                    onClick = onNavigateToTerminal,
                    modifier = Modifier.size(PulseShiftSpacing.MinTouchTarget)
                ) {
                    Icon(
                        Icons.Rounded.Terminal,
                        contentDescription = "Terminal",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Quick Settings Action
                IconButton(
                    onClick = onNavigateToSettings,
                    modifier = Modifier.size(PulseShiftSpacing.MinTouchTarget)
                ) {
                    Icon(
                        Icons.Rounded.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
