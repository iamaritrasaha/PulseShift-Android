package com.pulseshift.app.ui.screens.terminal

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.pulseshift.app.ui.theme.HardwareAmberWarning
import com.pulseshift.app.ui.theme.HardwareGreenVerified
import com.pulseshift.app.ui.theme.HardwareRedError
import com.pulseshift.app.ui.theme.HardwareTealActive
import com.pulseshift.app.ui.theme.PulseShiftMonoSmall
import com.pulseshift.app.ui.theme.PulseShiftMonoTiny
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PulseShift Terminal: a scrollable monospace command console over the real
 * BLE protocol. Every response line comes from the device — nothing is
 * fabricated (firmware docs/ANDROID-TERMINAL.md).
 */
@Composable
fun TerminalScreen(viewModel: TerminalViewModel) {
    val lines by viewModel.lines.collectAsState()
    val history by viewModel.inputHistory.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val isAuthorized by viewModel.isAuthorized.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()
    val pending by viewModel.pendingConfirmation.collectAsState()
    val device by viewModel.device.collectAsState()

    var input by remember { mutableStateOf("") }
    var historyIndex by remember { mutableStateOf(-1) }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
    ) {
        // Status header
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                com.pulseshift.app.ui.components.StatusDot(
                    color = when {
                        !isConnected -> HardwareRedError
                        isAuthorized -> HardwareGreenVerified
                        else -> HardwareAmberWarning
                    }
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            !isConnected -> "DISCONNECTED"
                            isAuthorized -> "CONNECTED · OWNER"
                            else -> "CONNECTED · UNAUTHORIZED"
                        },
                        style = PulseShiftMonoSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = device?.let { "${it.name ?: "PulseShift"} · fw ${it.firmwareVersion ?: "?"}" } ?: "no device",
                        style = PulseShiftMonoTiny,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { viewModel.submit("HELP") }) {
                    Icon(Icons.Rounded.HelpOutline, contentDescription = "Show help")
                }
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(lines.joinToString("\n") { l ->
                        "${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(l.timestampMs))} [${l.type}] ${l.text}"
                    }))
                }) {
                    Icon(Icons.Rounded.History, contentDescription = "Copy session")
                }
                IconButton(onClick = { viewModel.clearTranscript() }) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Clear terminal")
                }
            }
        }

        // Transcript
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(lines) { line ->
                TerminalLineRow(line)
            }
        }

        // Input row
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (history.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        history.take(3).forEach { cmd ->
                            AssistChip(
                                onClick = { input = cmd },
                                label = {
                                    Text(cmd, style = PulseShiftMonoTiny, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Type a command (HELP)", style = PulseShiftMonoSmall) },
                        textStyle = PulseShiftMonoSmall,
                        singleLine = true,
                        enabled = !isBusy,
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            viewModel.submit(input)
                            input = ""
                            historyIndex = -1
                        },
                        enabled = input.isNotBlank() && !isBusy
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send command")
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
