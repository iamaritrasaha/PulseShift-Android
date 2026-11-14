package com.pulseshift.app.ui.screens.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pulseshift.app.data.ble.BleConnectionState
import com.pulseshift.app.data.ble.PulseShiftBleManager
import com.pulseshift.app.data.repository.PulseShiftRepository
import com.pulseshift.app.domain.model.BleDevice
import com.pulseshift.app.domain.model.CaptureExportState
import com.pulseshift.app.domain.model.CaptureRecord
import com.pulseshift.app.domain.model.ConfigSnapshot
import com.pulseshift.app.domain.model.EffectType
import com.pulseshift.app.domain.model.LogSeverity
import com.pulseshift.app.domain.terminal.TerminalCommand
import com.pulseshift.app.domain.terminal.TerminalCommandParser
import com.pulseshift.app.ui.screens.terminal.TerminalLineType.ACK
import com.pulseshift.app.ui.screens.terminal.TerminalLineType.ERROR
import com.pulseshift.app.ui.screens.terminal.TerminalLineType.INFO
import com.pulseshift.app.ui.screens.terminal.TerminalLineType.WARN
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One transcript entry in the terminal. */
enum class TerminalLineType { COMMAND, ACK, ERROR, WARN, INFO, CAPTURE, SYSTEM }

data class TerminalLine(
    val timestampMs: Long,
    val type: TerminalLineType,
    val text: String
)

/**
 * Terminal dispatcher: parses user input, maps it to the real BLE operations
 * and prints the DEVICE's actual response — never a fabricated success.
 * Mapping contract: firmware docs/ANDROID-TERMINAL.md.
 */
class TerminalViewModel(
    private val bleManager: PulseShiftBleManager,
    private val repository: PulseShiftRepository
) : ViewModel() {

    companion object {
        private const val MAX_TRANSCRIPT = 500
        private const val PAGE_COLLECT_TIMEOUT_MS = 4000L
        private const val RPM_MONITOR_INTERVAL_MS = 150L
    }

    private val _lines = MutableStateFlow<List<TerminalLine>>(emptyList())
    val lines: StateFlow<List<TerminalLine>> = _lines.asStateFlow()

    private val _inputHistory = MutableStateFlow<List<String>>(emptyList())
    val inputHistory: StateFlow<List<String>> = _inputHistory.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isAuthorized = MutableStateFlow(false)
    val isAuthorized: StateFlow<Boolean> = _isAuthorized.asStateFlow()

    private val _device = MutableStateFlow<BleDevice?>(null)
    val device: StateFlow<BleDevice?> = _device.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    /** Set when a parsed command requires the confirmation dialog. */
    private val _pendingConfirmation = MutableStateFlow<TerminalCommand?>(null)
    val pendingConfirmation: StateFlow<TerminalCommand?> = _pendingConfirmation.asStateFlow()

    private var lastLoggedMessageId = -1
    private var monitorJob: Job? = null
    private var dumpJob: Job? = null
    private var dumpCursor = 0L

    init {
        viewModelScope.launch {
            bleManager.connectionState.collect { state ->
                val connected = state == BleConnectionState.RECEIVING_TELEMETRY ||
                        state == BleConnectionState.CONNECTED_WAITING_FOR_DATA ||
                        state == BleConnectionState.READY
                if (connected != _isConnected.value) {
                    _isConnected.value = connected
                    if (connected) append(TerminalLineType.SYSTEM, "-- link up --")
                    else {
                        _isAuthorized.value = false
                        monitorJob?.cancel()
                        append(TerminalLineType.SYSTEM, "-- link down --")
                    }
                }
            }
        }
        viewModelScope.launch {
            repository.isAuthenticated.collect { auth ->
                if (auth && !_isAuthorized.value) append(TerminalLineType.SYSTEM, "-- owner session bound --")
                _isAuthorized.value = auth
            }
        }
        viewModelScope.launch {
            bleManager.connectedDevice.collect { _device.value = it }
        }
        // Incoming diagnostic log lines (LOGS command) stream into the transcript.
        viewModelScope.launch {
            repository.diagnosticLogMessages.collect { list ->
                list.firstOrNull()?.takeIf { it.messageId != lastLoggedMessageId }?.let { msg ->
                    lastLoggedMessageId = msg.messageId
                    val type = when (msg.severity) {
                        LogSeverity.ERROR -> TerminalLineType.ERROR
                        LogSeverity.WARNING -> TerminalLineType.WARN
                        else -> TerminalLineType.INFO
                    }
                    append(type, msg.messageText)
                }
            }
        }
    }

    /** Parse + dispatch one input line. */
    fun submit(rawInput: String) {
        val input = rawInput.trim()
        if (input.isEmpty()) return
        append(TerminalLineType.COMMAND, "> $input")
        rememberInput(input)

        when (val result = TerminalCommandParser.parse(input)) {
            is TerminalCommandParser.ParseResult.Failure ->
                append(TerminalLineType.ERROR, result.error.message)
            is TerminalCommandParser.ParseResult.Success -> dispatch(result.command)
        }
    }

    fun clearTranscript() {
        _lines.value = emptyList()
        lastLoggedMessageId = -1
    }

    fun dismissConfirmation() {
        _pendingConfirmation.value = null
    }

    /** Called by the screen when the user accepts a destructive command. */
    fun onConfirmDestructive() {
        val command = _pendingConfirmation.value ?: return
        _pendingConfirmation.value = null
        when (command) {
            TerminalCommand.ConfigReset -> runCommand {
                repository.restoreDefaultControllerConfig()
                val synced = repository.configSnapshot.value
                if (synced != null && synced.isValid) {
                    ACK to "[ACK] Defaults restored (owner credentials preserved; 'CONFIG SAVE' persists)."
                } else {
                    WARN to "[SEND] Restore issued; no snapshot readback — verify on the device."
                }
            }
            TerminalCommand.Restart -> runCommand {
                val ok = repository.restartDevice()
                if (ok) ACK to "[ACK] Restart accepted — device is rebooting."
                else ERROR to "[ERR] RESTART rejected, unauthorized or no Config V2."
            }
            TerminalCommand.ClearDiagnostics -> runCommand {
                val ok = repository.clearDeviceDiagnosticHistory()
                repository.clearDiagnosticLogs()
                if (ok) ACK to "[ACK] Device event log cleared (captures untouched)."
                else ERROR to "[ERR] CLEAR DIAGNOSTICS rejected or unauthorized."
            }
            else -> {}
        }
    }

    fun clearInputHistory() {
        _inputHistory.value = emptyList()
    }

    private fun append(type: TerminalLineType, text: String) {
        val next = _lines.value + TerminalLine(System.currentTimeMillis(), type, text)
        _lines.value = if (next.size > MAX_TRANSCRIPT) next.takeLast(MAX_TRANSCRIPT) else next
    }

    private fun rememberInput(input: String) {
        val next = _inputHistory.value - input
        _inputHistory.value = (listOf(input) + next).take(30)
    }

    private fun connected(): Boolean {
        if (!_isConnected.value) {
            append(TerminalLineType.ERROR, "Disconnected — connect the device first (Devices tab).")
            return false
        }
        return true
    }

    private fun dispatch(command: TerminalCommand) {
        if (command.requiresConfirmation) {
            _pendingConfirmation.value = command
            return
        }
        when (command) {
            TerminalCommand.Help -> append(TerminalLineType.SYSTEM, TerminalCommandRegistry.helpText())
            TerminalCommand.Auth -> append(TerminalLineType.SYSTEM, TerminalCommandRegistry.authInstructions())
            TerminalCommand.StreamUnsupported -> append(
                TerminalLineType.WARN,
                "UNSUPPORTED OVER BLE: STREAM ON/OFF drives the USB-serial live feed only."
            )
            TerminalCommand.ClearWipeUnsupported -> append(
                TerminalLineType.WARN,
                "UNSUPPORTED OVER BLE: 'CLEAR YES' irreversibly wipes flash captures (USB-serial only). " +
                    "Use 'CLEAR DIAGNOSTICS' to clear only the event log."
            )
            TerminalCommand.RpmMonitorSample -> printRpmSample()
            TerminalCommand.RpmMonitorOn -> startRpmMonitor()
            TerminalCommand.RpmMonitorOff -> stopRpmMonitor()
            TerminalCommand.Status -> printDiagnostics("CAN")
            TerminalCommand.BleStatus -> printDiagnostics("BLE")
            TerminalCommand.RpmStatus -> printRpmStatus()
            TerminalCommand.ConfigStatus -> printConfigStatus()
            TerminalCommand.ConfigSave -> runCommand {
                val ok = repository.saveConfigurationToController()
                if (ok) ACK to "[ACK] Configuration PERSISTED to NVS (readback-verified)."
                else ERROR to "[ERR] CONFIG SAVE failed (timeout, rejection or readback mismatch)."
            }
            is TerminalCommand.RpmMultiplier -> runRpmMultiplier(command.multiplier)
            is TerminalCommand.Effect -> runEffect(command.effectId)
            is TerminalCommand.RpmTest -> runLedTest(command.targetRpm, true)
            TerminalCommand.RpmTestOff -> runLedTest(0, false)
            is TerminalCommand.CaptureStage -> runCaptureStage(command.stage)
            TerminalCommand.CaptureStop -> runCaptureRecording(false)
            TerminalCommand.CaptureResume -> runCaptureRecording(true)
            TerminalCommand.Logs -> runCommand {
                repository.requestHistoricalLogPage(0)
                INFO to "[LOGS] Historical page requested from offset 0; lines stream as they arrive."
            }
            TerminalCommand.DumpNext -> runDumpPage(1 + (dumpCursor / 12).toInt() + if (dumpCursor % 12 == 0L && dumpCursor > 0) 0 else 0)
            is TerminalCommand.DumpPage -> runDumpPage(command.page)
            TerminalCommand.DumpAll -> runDumpAll()
            TerminalCommand.DumpCancel -> runDumpCancel()
            else -> {}
        }
    }

    private fun printRpmSample() {
        val rpm = repository.rpmTelemetryState.value
        if (rpm == null) {
            append(TerminalLineType.INFO, "No RPM telemetry yet on this link.")
        } else {
            append(TerminalLineType.INFO, TerminalCommandRegistry.formatRpmSample(rpm))
        }
    }

    private fun startRpmMonitor() {
        if (monitorJob?.isActive == true) {
            append(TerminalLineType.WARN, "RPM monitor already running.")
            return
        }
        append(TerminalLineType.ACK, "[ACK] RPM monitor ON (≤ ${1000 / RPM_MONITOR_INTERVAL_MS} Hz).")
        monitorJob = viewModelScope.launch {
            while (true) {
                repository.rpmTelemetryState.value?.let { rpm ->
                    append(TerminalLineType.INFO, TerminalCommandRegistry.formatRpmSample(rpm))
                }
                delay(RPM_MONITOR_INTERVAL_MS)
            }
        }
    }

    private fun stopRpmMonitor() {
        monitorJob?.cancel()
        monitorJob = null
        append(TerminalLineType.ACK, "[ACK] RPM monitor OFF.")
    }

    private fun printDiagnostics(scope: String) {
        runCommand {
            val report = repository.requestDiagnosticsReport()
            if (report == null) {
                ERROR to "[ERR] No diagnostics blob received (timeout or disconnected)."
            } else {
                INFO to TerminalCommandRegistry.formatDiagnostics(report, scope)
            }
        }
    }

    private fun printRpmStatus() {
        runCommand {
            val rpm = repository.rpmTelemetryState.value
            if (rpm == null) {
                INFO to "CAN ID 0x301 (configured) — waiting for the first matching frame on this link."
            } else {
                INFO to TerminalCommandRegistry.formatRpmStatus(rpm, repository.connectedDevice.value)
            }
        }
    }

    private fun printConfigStatus() {
        runCommand {
            repository.reloadConfigFromController()
            delay(400)
            val snapshot = repository.configSnapshot.value
            if (snapshot == null || !snapshot.isValid) {
                ERROR to "[ERR] No valid config snapshot received."
            } else {
                INFO to TerminalCommandRegistry.formatSnapshot(snapshot)
            }
        }
    }

    private fun runRpmMultiplier(multiplier: Double) {
        runCommand {
            val snapshot = repository.configSnapshot.value
            if (snapshot == null) {
                ERROR to "[ERR] Need a config snapshot first — run CONFIG STATUS."
            } else {
                repository.applyDecoderConfiguration(
                    canId = snapshot.canCandidateId,
                    byteIndex = snapshot.candidateByteIndex,
                    isExtended = snapshot.isExtendedFrame,
                    multiplier = multiplier.toFloat()
                )
                val after = repository.configSnapshot.value
                val applied = after != null && kotlin.math.abs(after.rpmMultiplier - multiplier) < 0.05
                if (applied) {
                    ACK to "[ACK] Multiplier set to $multiplier (live). Calibration invalidated — CONFIG SAVE persists."
                } else {
                    ERROR to "[ERR] Multiplier change did not verify against device readback."
                }
            }
        }
    }

    private fun runEffect(effectId: Int) {
        runCommand {
            val effect = EffectType.fromId(effectId)
            repository.setEffect(effect)
            val note = when (effectId) {
                5 -> " — UNVERIFIED live preview of estimated CAN RPM"
                4 -> " — requires a bound calibration record on the device"
                else -> ""
            }
            ACK to "[ACK] Effect ${effect.id}: ${effect.displayName}$note"
        }
    }

    private fun runLedTest(rpm: Int, enable: Boolean) {
        runCommand {
            val caps = repository.connectedDevice.value?.capabilities
            if (caps != null && !caps.supportsLedBenchTest) {
                ERROR to "[UNSUPPORTED] Firmware ${caps.firmwareVersion} lacks the LED bench-test feature bit."
            } else {
                val ok = repository.setLedTestRpm(rpm, enable)
                if (ok && enable) ACK to "[ACK] 10 s LED bench test at $rpm RPM (synthetic, not CAN data)."
                else if (ok) ACK to "[ACK] Bench test stopped."
                else ERROR to "[ERR] SET_LED_TEST_RPM rejected or unauthorized."
            }
        }
    }

    private fun runCaptureStage(stage: Int) {
        runCommand {
            val caps = repository.connectedDevice.value?.capabilities
            if (caps != null && !caps.supportsCaptureStage) {
                ERROR to "[UNSUPPORTED] Firmware ${caps.firmwareVersion} lacks the capture-stage feature bit."
            } else {
                val ok = repository.setCaptureStage(stage)
                if (ok) ACK to "[ACK] Capture stage set to $stage (labels subsequent records)."
                else ERROR to "[ERR] SET_CAPTURE_STAGE rejected or unauthorized."
            }
        }
    }

    private fun runCaptureRecording(enable: Boolean) {
        runCommand {
            val ok = repository.setCaptureRecording(enable)
            if (ok && enable) ACK to "[ACK] Flash recording resumed."
            else if (ok) ACK to "[ACK] Recording paused; buffers flushed to flash."
            else ERROR to "[ERR] CAPTURE command rejected — flash may be preserved, damaged or full."
        }
    }

    /**
     * Single 12-record page from the current phone cursor (or an explicit
     * page number). DUMP NEXT resumes from the cursor; DUMP PAGE n jumps.
     */
    private fun runDumpPage(page: Int) {
        if (dumpJob?.isActive == true) {
            append(TerminalLineType.WARN, "A DUMP stream is already running; DUMP CANCEL stops it.")
            return
        }
        if (!connected()) return
        val start = if (page == 1) dumpCursor else (page - 1) * 12L
        dumpJob = viewModelScope.launch {
            try {
                _isBusy.value = true
                append(TerminalLineType.INFO, "[DUMP] Requesting page $page from record $start…")
                // Start collecting BEFORE the request: the record flow has no
                // replay, so subscribing first avoids missing early records.
                val collectDeferred = async { collectPage(12) }
                bleManager.requestHistoricalCapturePage(start)
                val records = collectDeferred.await()
                if (records.isEmpty()) {
                    append(TerminalLineType.INFO, "[DUMP] No records at offset $start (end of capture or empty).")
                } else {
                    dumpCursor = start + records.size
                    records.forEach { append(TerminalLineType.CAPTURE, TerminalCommandRegistry.formatCaptureRecord(it)) }
                    append(TerminalLineType.ACK, "[DUMP] ${records.size} records; phone cursor now $dumpCursor.")
                }
            } catch (e: Exception) {
                append(TerminalLineType.ERROR, "[DUMP] Failed: ${e.message ?: "error"}")
            } finally {
                _isBusy.value = false
                dumpJob = null
            }
        }
    }

    /** Bounded auto-paging walk over the whole capture (cancelable). */
    private fun runDumpAll() {
        if (dumpJob?.isActive == true) {
            append(TerminalLineType.WARN, "A DUMP stream is already running.")
            return
        }
        if (!connected()) return
        append(TerminalLineType.INFO, "[DUMP ALL] Bounded walk from record $dumpCursor; DUMP CANCEL aborts.")
        repository.startCaptureExport()
        dumpJob = viewModelScope.launch {
            try {
                _isBusy.value = true
                var printed = 0
                while (true) {
                    val state = repository.captureExportState.value
                    if (state is CaptureExportState.Failed) {
                        append(TerminalLineType.ERROR, "[DUMP] Export failed: ${state.reason}")
                        return@launch
                    }
                    if (state is CaptureExportState.Exporting) {
                        val records = state.records
                        while (printed < records.size) {
                            append(TerminalLineType.CAPTURE, TerminalCommandRegistry.formatCaptureRecord(records[printed]))
                            printed++
                        }
                        if (state.isFinished) {
                            append(TerminalLineType.ACK, "[DUMP] End of capture after $printed records.")
                            return@launch
                        }
                    }
                    delay(120)
                }
            } finally {
                _isBusy.value = false
                dumpJob = null
            }
        }
    }

    private fun runDumpCancel() {
        dumpJob?.cancel()
        dumpJob = null
        repository.cancelCaptureExport()
        append(TerminalLineType.ACK, "[DUMP] Cancelled; phone cursor kept at $dumpCursor for resume.")
    }

    /**
     * Collect up to [max] capture records straight from the reassembler flow
     * (independent of the repository's auto-paging export state). The
     * timeout rescues short final pages.
     */
    private suspend fun collectPage(max: Int): List<CaptureRecord> {
        return withTimeoutOrNull(PAGE_COLLECT_TIMEOUT_MS) {
            bleManager.logReassembler.captureRecordEvents
                .take(max)
                .toList()
        } ?: emptyList()
    }

    private fun runCommand(block: suspend () -> Pair<TerminalLineType, String>) {
        if (!connected()) return
        if (_isBusy.value) {
            append(TerminalLineType.WARN, "Another command is in flight; wait for its result.")
            return
        }
        _isBusy.value = true
        viewModelScope.launch {
            try {
                val (type, text) = block()
                append(type, text)
            } catch (e: Exception) {
                append(TerminalLineType.ERROR, "[ERR] Command failed: ${e.message ?: "unexpected error"}")
            } finally {
                _isBusy.value = false
            }
        }
    }
}
