package com.pulseshift.app.ui.screens.terminal

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.pulseshift.app.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pulseshift.app.domain.terminal.TerminalCommand
import com.pulseshift.app.ui.components.ConfirmDialog
import com.pulseshift.app.ui.components.StatusPill
import com.pulseshift.app.ui.theme.HardwareAmberWarning
import com.pulseshift.app.ui.theme.HardwareGreenVerified
import com.pulseshift.app.ui.theme.HardwareRedError
import com.pulseshift.app.ui.theme.HardwareTealActive
import com.pulseshift.app.ui.theme.PulseShiftMonoSmall
import com.pulseshift.app.ui.theme.PulseShiftMonoTiny
import com.pulseshift.app.ui.theme.PulseShiftSpacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PulseShift Terminal: a scrollable monospace command console over the real
 * BLE protocol. Every response line comes from the device — nothing is
 * fabricated (firmware docs/ANDROID-TERMINAL.md).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    onNavigateBack: () -> Unit = {}
) {
    val lines by viewModel.lines.collectAsState()
    val history by viewModel.inputHistory.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val isAuthorized by viewModel.isAuthorized.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()
    val pending by viewModel.pendingConfirmation.collectAsState()
    val device by viewModel.device.collectAsState()

    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    val quickCommands = listOf(
        "HELP",
        "STATUS",
        "RPM STATUS",
        "CONFIG STATUS",
        "CONFIG SAVE",
        "STAGE 1000 2000 3000 4000 5000 6000",
        "EFFECT 4",
        "EFFECT 5",
        "CAPTURE 10"
    )

    // Auto-scroll to the newest line.
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    pending?.let { command ->
        ConfirmDialog(
            title = "Confirm ${command.kind.name.lowercase().replaceFirstChar { it.uppercase() }} action",
            text = when (command) {
                TerminalCommand.ConfigReset ->
                    "Restores lighting/decoder defaults on the device. Owner credentials are preserved. Not persisted until CONFIG SAVE."
                TerminalCommand.Restart ->
                    "The ESP32 will soft-restart. BLE drops for a few seconds and the app re-authenticates."
                TerminalCommand.ClearDiagnostics ->
                    "Clears the device diagnostic event log. CAN captures and owner credentials are NOT touched."
                else -> "Run this action?"
            },
            confirmLabel = "Run it",
            onConfirm = { viewModel.onConfirmDestructive() },
            onDismiss = { viewModel.dismissConfirmation() }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF101014))
                                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_pulseshift_logo_round),
                                contentDescription = "PulseShift",
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Column {
                            Text("Hardware Terminal", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = device?.let { "${it.name ?: "PulseShift"} · fw ${it.firmwareVersion ?: "?"}" } ?: "ESP32 Console",
                                style = PulseShiftMonoTiny,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.size(PulseShiftSpacing.MinTouchTarget)
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.submit("HELP") },
                        modifier = Modifier.size(PulseShiftSpacing.MinTouchTarget)
                    ) {
                        Icon(Icons.Rounded.HelpOutline, contentDescription = "Show help")
                    }
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(lines.joinToString("\n") { l ->
                                "${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(l.timestampMs))} [${l.type}] ${l.text}"
                            }))
                        },
                        modifier = Modifier.size(PulseShiftSpacing.MinTouchTarget)
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy session")
                    }
                    IconButton(
                        onClick = { viewModel.clearTranscript() },
                        modifier = Modifier.size(PulseShiftSpacing.MinTouchTarget)
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Clear terminal")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .imePadding()
        ) {
            // Status bar
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PulseShiftSpacing.ScreenHorizontal, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusPill(
                        text = when {
                            !isConnected -> "DISCONNECTED"
                            isAuthorized -> "OWNER AUTHENTICATED"
                            else -> "UNAUTHORIZED"
                        },
                        color = when {
                            !isConnected -> HardwareRedError
                            isAuthorized -> HardwareGreenVerified
                            else -> HardwareAmberWarning
                        }
                    )

                    Text(
                        text = if (isBusy) "TRANSMITTING..." else "READY",
                        style = PulseShiftMonoTiny,
                        color = if (isBusy) HardwareAmberWarning else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Monospace Transcript
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(lines) { line ->
                    TerminalLineRow(line)
                }
            }

            // Quick command chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                quickCommands.forEach { cmd ->
                    AssistChip(
                        onClick = {
                            viewModel.submit(cmd)
                        },
                        label = {
                            Text(cmd, style = PulseShiftMonoTiny)
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                }
            }

            // Input Row
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Enter command (or tap chip above)", style = PulseShiftMonoSmall) },
                            textStyle = PulseShiftMonoSmall,
                            singleLine = true,
                            enabled = !isBusy,
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                if (input.isNotBlank()) {
                                    viewModel.submit(input.trim())
                                    input = ""
                                }
                            },
                            enabled = input.isNotBlank() && !isBusy,
                            modifier = Modifier.size(PulseShiftSpacing.MinTouchTarget)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.Send,
                                contentDescription = "Send command",
                                tint = if (input.isNotBlank() && !isBusy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalLineRow(line: TerminalLine) {
    val color = when (line.type) {
        TerminalLineType.COMMAND -> MaterialTheme.colorScheme.primary
        TerminalLineType.ACK -> HardwareGreenVerified
        TerminalLineType.ERROR -> HardwareRedError
        TerminalLineType.WARN -> HardwareAmberWarning
        TerminalLineType.CAPTURE -> HardwareTealActive
        TerminalLineType.INFO -> MaterialTheme.colorScheme.onSurface
        TerminalLineType.SYSTEM -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(line.timestampMs)),
            style = PulseShiftMonoTiny,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp)
        )
        Text(
            text = line.text,
            style = PulseShiftMonoSmall,
            color = color,
            modifier = Modifier.weight(1f)
        )
    }
}
