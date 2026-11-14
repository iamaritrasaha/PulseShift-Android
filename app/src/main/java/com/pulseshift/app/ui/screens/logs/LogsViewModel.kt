package com.pulseshift.app.ui.screens.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pulseshift.app.data.repository.PulseShiftRepository
import com.pulseshift.app.domain.model.CaptureExportState
import com.pulseshift.app.domain.model.DeviceCommandOutcome
import com.pulseshift.app.domain.model.DiagnosticLogMessage
import com.pulseshift.app.domain.model.DiagnosticsReport
import com.pulseshift.app.domain.model.HistoricalLogLoadingState
import com.pulseshift.app.domain.model.LogSeverity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LogsViewModel(
    private val repository: PulseShiftRepository
) : ViewModel() {

    val rawLogs: StateFlow<List<DiagnosticLogMessage>> = repository.diagnosticLogMessages
    val historicalPageLoadingState: StateFlow<HistoricalLogLoadingState> = repository.historicalPageLoadingState
    val canHealthState: StateFlow<com.pulseshift.app.domain.model.CanHealthState?> = repository.canHealthState
    val rpmTelemetryState: StateFlow<com.pulseshift.app.domain.model.RpmTelemetryState?> = repository.rpmTelemetryState
    val connectedDevice: StateFlow<com.pulseshift.app.domain.model.BleDevice?> = repository.connectedDevice

    // Device command surface (Diagnostics / Advanced Commands)
    val diagnosticsReport: StateFlow<DiagnosticsReport?> = repository.diagnosticsReport
    val captureExportState: StateFlow<CaptureExportState> = repository.captureExportState
    val deviceCommandHistory: StateFlow<List<DeviceCommandOutcome>> = repository.deviceCommandHistory

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedSeverityFilter = MutableStateFlow<LogSeverity?>(null)
    val selectedSeverityFilter: StateFlow<LogSeverity?> = _selectedSeverityFilter.asStateFlow()

    val filteredLogs: StateFlow<List<DiagnosticLogMessage>> = combine(
        rawLogs,
        _searchQuery,
        _selectedSeverityFilter
    ) { logs, query, severity ->
        logs.filter { msg ->
            val matchesQuery = query.isBlank() ||
                    msg.messageText.contains(query, ignoreCase = true) ||
                    msg.messageId.toString().contains(query)
            val matchesSeverity = severity == null || msg.severity == severity
            matchesQuery && matchesSeverity
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSeverityFilter(severity: LogSeverity?) {
        _selectedSeverityFilter.value = severity
    }

    fun requestHistoricalLogPage(startOffset: Long) {
        viewModelScope.launch {
            repository.requestHistoricalLogPage(startOffset)
        }
    }

    fun cancelHistoricalLogRetrieval() {
        viewModelScope.launch {
            repository.cancelHistoricalLogRetrieval()
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            repository.clearDiagnosticLogs()
        }
    }

    fun exportAsText(): String {
        return repository.exportLogsAsText()
    }

    fun exportAsCsv(): String {
        return repository.exportLogsAsCsv()
    }

    // --- Device & Capture actions ---

    fun requestDiagnosticsReport() {
        viewModelScope.launch { repository.requestDiagnosticsReport() }
    }

    fun reloadConfigurationFromDevice() {
        viewModelScope.launch { repository.reloadConfigurationFromDevice() }
    }

    fun restartDevice() {
        viewModelScope.launch { repository.restartDevice() }
    }

    fun clearDeviceDiagnosticHistory() {
        viewModelScope.launch { repository.clearDeviceDiagnosticHistory() }
    }

    fun setCaptureRecording(enable: Boolean) {
        viewModelScope.launch { repository.setCaptureRecording(enable) }
    }

    fun startCaptureExport() {
        repository.startCaptureExport()
    }

    fun cancelCaptureExport() {
        repository.cancelCaptureExport()
    }
}
