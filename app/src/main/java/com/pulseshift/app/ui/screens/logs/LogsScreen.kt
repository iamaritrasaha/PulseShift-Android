package com.pulseshift.app.ui.screens.logs

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseshift.app.domain.model.BleDevice
import com.pulseshift.app.domain.model.CanHealthState
import com.pulseshift.app.domain.model.DiagnosticLogMessage
import com.pulseshift.app.domain.model.HistoricalLogLoadingState
import com.pulseshift.app.domain.model.LogSeverity
import com.pulseshift.app.domain.model.RpmTelemetryState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    viewModel: LogsViewModel,
    onNavigateToTerminal: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val logs by viewModel.filteredLogs.collectAsState()
    val loadingState by viewModel.historicalPageLoadingState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedSeverity by viewModel.selectedSeverityFilter.collectAsState()
    val canHealth by viewModel.canHealthState.collectAsState()
    val rpmTelemetry by viewModel.rpmTelemetryState.collectAsState()
    val connectedDevice by viewModel.connectedDevice.collectAsState()
    val diagnosticsReport by viewModel.diagnosticsReport.collectAsState()
    val captureExportState by viewModel.captureExportState.collectAsState()
    val commandHistory by viewModel.deviceCommandHistory.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var showSearchField by remember { mutableStateOf(false) }
    var showExportMenu by remember { mutableStateOf(false) }
    var showRestartConfirm by remember { mutableStateOf(false) }
    var showClearDiagConfirm by remember { mutableStateOf(false) }

    val dateFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics & System Health", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = onNavigateToTerminal) {
                        Icon(Icons.Rounded.Terminal, contentDescription = "Open Hardware Terminal")
                    }
                    if (selectedTab == 1) {
                        IconButton(onClick = { showSearchField = !showSearchField }) {
                            Icon(Icons.Rounded.Search, contentDescription = "Search Logs")
                        }

                        IconButton(onClick = { showExportMenu = true }) {
                            Icon(Icons.Rounded.Share, contentDescription = "Export Logs")
                        }

                        DropdownMenu(
                            expanded = showExportMenu,
                            onDismissRequest = { showExportMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Copy Text to Clipboard") },
                                onClick = {
                                    showExportMenu = false
                                    val text = viewModel.exportAsText()
                                    clipboardManager.setText(AnnotatedString(text))
                                    Toast.makeText(context, "Diagnostic logs copied to clipboard", Toast.LENGTH_SHORT).show()
                                },
                                leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) }
                            )

                            DropdownMenuItem(
                                text = { Text("Share as Text File") },
                                onClick = {
                                    showExportMenu = false
                                    val text = viewModel.exportAsText()
                                    shareLogContent(context, text, "text/plain", "PulseShift_Diagnostic_Logs.txt")
                                },
                                leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) }
                            )

                            DropdownMenuItem(
                                text = { Text("Share as CSV") },
                                onClick = {
                                    showExportMenu = false
                                    val csv = viewModel.exportAsCsv()
                                    shareLogContent(context, csv, "text/csv", "PulseShift_Diagnostic_Logs.csv")
                                },
                                leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) }
                            )
                        }

                        IconButton(onClick = { viewModel.clearLogs() }) {
                            Icon(Icons.Rounded.ClearAll, contentDescription = "Clear Logs")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
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
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Health Overview") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Event Logs & Export") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Device & Capture") }
                )
            }

            when (selectedTab) {
                0 -> DiagnosticsOverviewTab(
                    canHealth = canHealth,
                    rpmTelemetry = rpmTelemetry,
                    connectedDevice = connectedDevice
                )
                1 -> EventLogsTab(
                    logs = logs,
                    loadingState = loadingState,
                    searchQuery = searchQuery,
                    showSearchField = showSearchField,
                    selectedSeverity = selectedSeverity,
                    nextHistoricalOffset = 0L,
                    dateFormat = dateFormat,
                    onSearchQueryChange = { viewModel.setSearchQuery(it) },
                    onSeverityFilterChange = { viewModel.setSeverityFilter(it) },
                    onRequestPage = { viewModel.requestHistoricalLogPage(it) },
                    onCancelRetrieval = { viewModel.cancelHistoricalLogRetrieval() }
                )
                2 -> DeviceCaptureTab(
                    report = diagnosticsReport,
                    captureExportState = captureExportState,
                    commandHistory = commandHistory,
                    connectedDevice = connectedDevice,
                    dateFormat = dateFormat,
                    onRefreshReport = { viewModel.requestDiagnosticsReport() },
                    onReloadConfig = { viewModel.reloadConfigurationFromDevice() },
                    onRestart = { showRestartConfirm = true },
                    onClearDiagnostics = { showClearDiagConfirm = true },
                    onStartRecording = { viewModel.setCaptureRecording(true) },
                    onStopRecording = { viewModel.setCaptureRecording(false) },
                    onStartCaptureExport = { viewModel.startCaptureExport() },
                    onCancelCaptureExport = { viewModel.cancelCaptureExport() }
                )
            }
        }
    }

    if (showRestartConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showRestartConfirm = false },
            title = { Text("Restart ESP32?") },
            text = { Text("The device will perform a soft restart. Saved configuration and captures are preserved; the BLE link will drop and reconnect automatically if auto-reconnect is enabled.") },
            confirmButton = {
                Button(onClick = {
                    showRestartConfirm = false
                    viewModel.restartDevice()
                }) { Text("Restart") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showRestartConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showClearDiagConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showClearDiagConfirm = false },
            title = { Text("Clear diagnostic history?") },
            text = { Text("Erases the device event log. Stored CAN captures and owner credentials are never touched by this operation.") },
            confirmButton = {
                Button(onClick = {
                    showClearDiagConfirm = false
                    viewModel.clearDeviceDiagnosticHistory()
                }) { Text("Clear") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showClearDiagConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun DeviceReportRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = valueColor, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun DeviceCaptureTab(
    report: com.pulseshift.app.domain.model.DiagnosticsReport?,
    captureExportState: com.pulseshift.app.domain.model.CaptureExportState,
    commandHistory: List<com.pulseshift.app.domain.model.DeviceCommandOutcome>,
    connectedDevice: BleDevice?,
    dateFormat: SimpleDateFormat,
    onRefreshReport: () -> Unit,
    onReloadConfig: () -> Unit,
    onRestart: () -> Unit,
    onClearDiagnostics: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onStartCaptureExport: () -> Unit,
    onCancelCaptureExport: () -> Unit
) {
    val connected = connectedDevice != null
    val exporting = captureExportState is com.pulseshift.app.domain.model.CaptureExportState.Exporting
    val recording = report?.captureRecording ?: false

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Device report card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Device Status", style = MaterialTheme.typography.titleMedium)
                    FilledTonalButton(onClick = onRefreshReport, enabled = connected) {
                        Text("Refresh")
                    }
                }
                if (report == null) {
                    Text(
                        if (connected) "No report loaded yet. Tap Refresh to request a diagnostics report."
                        else "Connect to the device to request diagnostics.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    DeviceReportRow("Firmware", report.firmwareVersion)
                    DeviceReportRow("Uptime", "${report.uptimeMs / 1000}s (boot ${report.bootCount})")
                    DeviceReportRow("CAN controller", if (report.canHealthy) "Healthy" else "HALTED",
                        if (report.canHealthy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    DeviceReportRow("CAN bitrate / frames", "${report.canBitrate} bps / ${report.canTotalRx} rx")
                    DeviceReportRow("Candidate", "0x${java.lang.Long.toHexString(report.candidateCanId).uppercase()} byte ${report.candidateByteIndex} (x${report.candidateMultiplier})")
                    DeviceReportRow("Estimated RPM", if (report.candidateFresh) "${report.candidateEstimatedRpm} RPM" else "stale/waiting")
                    DeviceReportRow("Calibration", if (report.candidateCalVerified) "VERIFIED" else "UNVERIFIED",
                        if (report.candidateCalVerified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    DeviceReportRow("Recording", if (report.captureRecording) "ACTIVE (stage ${report.captureStage})" else "paused")
                    DeviceReportRow("Capture storage", "${report.captureRecords} records, ${report.captureSizeBytes / 1024} KB")
                    DeviceReportRow("Free flash", "${report.storageFree / 1024} KB")
                    if (report.lastError.isNotBlank()) {
                        DeviceReportRow("Last error", report.lastError, MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        // Capture recording control
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("CAN Flash Recording", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onStartRecording, enabled = connected && !recording) {
                        Text("Start")
                    }
                    OutlinedButton(onClick = onStopRecording, enabled = connected && recording) {
                        Text("Stop")
                    }
                }

                Text("Capture Export (bounded pages over BLE)", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onStartCaptureExport, enabled = connected && !exporting) {
                        Text("Export to phone")
                    }
                    OutlinedButton(onClick = onCancelCaptureExport, enabled = exporting) {
                        Text("Cancel")
                    }
                }
                if (exporting) {
                    val state = captureExportState as com.pulseshift.app.domain.model.CaptureExportState.Exporting
                    Text(
                        "Record ${state.nextRecordIndex} | ${state.records.size} received${if (state.isFinished) " | finished" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    val preview = state.records.takeLast(5).reversed()
                    for (r in preview) {
                        Text(
                            "t=${r.timestampMs} id=0x${java.lang.Long.toHexString(r.canId).uppercase()} dlc=${r.dlc} ${r.dataHex}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Device commands
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Device Commands", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onReloadConfig, enabled = connected) {
                        Text("Reload config")
                    }
                    OutlinedButton(onClick = onClearDiagnostics, enabled = connected) {
                        Text("Clear diag history")
                    }
                }
                OutlinedButton(onClick = onRestart, enabled = connected) {
                    Text("Restart ESP32...")
                }
                Text(
                    "Writes require an authenticated owner session. Press the BOOT button on the ESP32 to open the enrollment window if authentication fails.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Command history with lifecycle phases
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Command History", style = MaterialTheme.typography.titleMedium)
                if (commandHistory.isEmpty()) {
                    Text("No device commands issued yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    for (outcome in commandHistory.take(20)) {
                        val (phaseColor, phaseText) = when (outcome.phase) {
                            com.pulseshift.app.domain.model.DeviceCommandPhase.SENDING -> MaterialTheme.colorScheme.onSurfaceVariant to "Sending"
                            com.pulseshift.app.domain.model.DeviceCommandPhase.ACCEPTED -> MaterialTheme.colorScheme.primary to "Accepted"
                            com.pulseshift.app.domain.model.DeviceCommandPhase.APPLIED -> MaterialTheme.colorScheme.primary to "Applied"
                            com.pulseshift.app.domain.model.DeviceCommandPhase.SAVED -> MaterialTheme.colorScheme.primary to "Saved"
                            com.pulseshift.app.domain.model.DeviceCommandPhase.FAILED -> MaterialTheme.colorScheme.error to "Failed"
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(outcome.label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                if (outcome.detail.isNotBlank()) {
                                    Text(outcome.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text(
                                phaseText + " " + dateFormat.format(Date(outcome.timestampMs)),
                                style = MaterialTheme.typography.labelSmall,
                                color = phaseColor,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DiagnosticsOverviewTab(
    canHealth: CanHealthState?,
    rpmTelemetry: RpmTelemetryState?,
    connectedDevice: BleDevice?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. CAN Bus & TWAI Controller Status Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("CAN Bus & TWAI Health", style = MaterialTheme.typography.titleMedium)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Total CAN RX Frames", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${rpmTelemetry?.totalCanFramesReceived ?: 0}", style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace), fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Driver Missed Frames", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${canHealth?.driverMissedFrames ?: 0}", style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace), color = if ((canHealth?.driverMissedFrames ?: 0) > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Hardware FIFO Overruns", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${canHealth?.hardwareFifoOverruns ?: 0}", style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace))
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("CAN Bus Error Counter", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${canHealth?.canBusErrors ?: 0}", style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace))
                    }
                }
            }
        }

        // 2. BLE Interface & Target Device Info Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("BLE Interface & Controller Info", style = MaterialTheme.typography.titleMedium)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Connected Controller", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(connectedDevice?.name ?: "Disconnected", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Device MAC Address", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(connectedDevice?.address ?: "N/A", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Firmware Version", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(connectedDevice?.firmwareVersion ?: "N/A", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Signal Strength (RSSI)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${connectedDevice?.rssi ?: 0} dBm", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 3. ESP32 Flash Storage Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("ESP32 Flash Storage & Recording", style = MaterialTheme.typography.titleMedium)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("LittleFS File System", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (canHealth?.healthFlags?.isLittleFsMounted == true) "Mounted (Healthy)" else "Unmounted / Error", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Session Recorded Frames", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${canHealth?.recordedFramesInCurrentSession ?: 0}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
fun EventLogsTab(
    logs: List<DiagnosticLogMessage>,
    loadingState: HistoricalLogLoadingState,
    searchQuery: String,
    showSearchField: Boolean,
    selectedSeverity: LogSeverity?,
    nextHistoricalOffset: Long,
    dateFormat: SimpleDateFormat,
    onSearchQueryChange: (String) -> Unit,
    onSeverityFilterChange: (LogSeverity?) -> Unit,
    onRequestPage: (Long) -> Unit,
    onCancelRetrieval: () -> Unit
) {
    // Single source of truth: the loading state machine carries the cursor.
    val nextOffset = when (val s = loadingState) {
        is HistoricalLogLoadingState.PageLoaded -> s.nextOffset
        else -> nextHistoricalOffset
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (showSearchField) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Search message text or ID...") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                singleLine = true,
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Rounded.Cancel, contentDescription = "Clear search")
                        }
                    }
                }
            )
        }

        // Severity Filter Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Filter:", style = MaterialTheme.typography.labelMedium)

            SeverityChip("All", selectedSeverity == null) {
                onSeverityFilterChange(null)
            }

            SeverityChip("Info", selectedSeverity == LogSeverity.INFO, color = MaterialTheme.colorScheme.primary) {
                onSeverityFilterChange(LogSeverity.INFO)
            }

            SeverityChip("Warn", selectedSeverity == LogSeverity.WARNING, color = Color(0xFFFFB74D)) {
                onSeverityFilterChange(LogSeverity.WARNING)
            }

            SeverityChip("Error", selectedSeverity == LogSeverity.ERROR, color = MaterialTheme.colorScheme.error) {
                onSeverityFilterChange(LogSeverity.ERROR)
            }
        }

        // Historical Log Pagination Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Flash Diagnostic Log Retrieval", style = MaterialTheme.typography.titleSmall)
                    when (loadingState) {
                        is HistoricalLogLoadingState.Idle -> {
                            Text("Load historical log pages from ESP32 flash", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        is HistoricalLogLoadingState.Loading -> {
                            Text("Fetching page at offset ${loadingState.requestedOffset} bytes...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        is HistoricalLogLoadingState.PageLoaded -> {
                            if (loadingState.isFinished) {
                                Text("All historical flash log pages retrieved (End of log)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            } else {
                                Text("Next available page: ${loadingState.nextOffset} bytes offset", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        is HistoricalLogLoadingState.Error -> {
                            Text("Retrieval error: ${loadingState.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (loadingState is HistoricalLogLoadingState.Loading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        OutlinedButton(
                            onClick = onCancelRetrieval,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Cancel", style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        val isFinished = (loadingState as? HistoricalLogLoadingState.PageLoaded)?.isFinished ?: false
                        if (!isFinished) {
                            FilledTonalButton(
                                onClick = { onRequestPage(nextOffset) },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(if (nextOffset == 0L) "Fetch Historical" else "Load Next Page", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }

        Text(
            text = "${logs.size} Events Displayed",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No diagnostic events match current filter",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(logs, key = { it.id }) { item ->
                    LogMessageCard(item = item, dateFormat = dateFormat)
                }
            }
        }
    }
}

@Composable
fun SeverityChip(
    label: String,
    selected: Boolean,
    color: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) color.copy(alpha = 0.2f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
            .border(
                width = 1.dp,
                color = if (selected) color else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun LogMessageCard(
    item: DiagnosticLogMessage,
    dateFormat: SimpleDateFormat
) {
    val severityColor = when (item.severity) {
        LogSeverity.INFO -> MaterialTheme.colorScheme.primary
        LogSeverity.WARNING -> Color(0xFFFFB74D)
        LogSeverity.ERROR -> MaterialTheme.colorScheme.error
    }

    val icon = when (item.severity) {
        LogSeverity.INFO -> Icons.Rounded.Info
        LogSeverity.WARNING -> Icons.Rounded.Warning
        LogSeverity.ERROR -> Icons.Rounded.ErrorOutline
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = item.severity.name,
                tint = severityColor,
                modifier = Modifier.size(20.dp).padding(top = 2.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = dateFormat.format(Date(item.timestampMs)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        if (item.isHistorical) {
                            Text(
                                text = "HISTORICAL",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }

                    Text(
                        text = "Msg #${item.messageId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    text = item.messageText,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = if (item.isCorruptOrIncomplete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                )

                if (item.isCorruptOrIncomplete) {
                    Text(
                        text = "[Warning: Log payload chunk incomplete or corrupt]",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

private fun shareLogContent(context: Context, content: String, mimeType: String, filename: String) {
    try {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, content)
            type = mimeType
        }
        val shareIntent = Intent.createChooser(sendIntent, "Share $filename")
        context.startActivity(shareIntent)
    } catch (e: Exception) {
        Toast.makeText(context, "Error sharing file: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
