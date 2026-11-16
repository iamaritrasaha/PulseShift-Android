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
import com.pulseshift.app.domain.model.HistoricalPageEnd
import com.pulseshift.app.domain.model.LogSeverity
import com.pulseshift.app.domain.terminal.TerminalCommand
import com.pulseshift.app.domain.terminal.TerminalCommandParser
import com.pulseshift.app.ui.screens.terminal.TerminalLineType.ACK
import com.pulseshift.app.ui.screens.terminal.TerminalLineType.ERROR
import com.pulseshift.app.ui.screens.terminal.TerminalLineType.INFO
import com.pulseshift.app.ui.screens.terminal.TerminalLineType.WARN
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** One transcript entry in the terminal. */
enum class TerminalLineType { COMMAND, ACK, ERROR, WARN, INFO, CAPTURE, SYSTEM }

data class TerminalLine(
    val timestampMs: Long,
    val type: TerminalLineType,
    val text: String
)

data class CollectPageResult(
    val records: List<CaptureRecord>,
    val pageEnd: HistoricalPageEnd?,
    val firmwareError: String?
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

        /** Grace window for the trailing end-of-page marker after a full page of records. */
        private const val PAGE_END_GRACE_MS = 400L

        /** DUMP ALL aborts when no new page data or state change occurs in this window. */
        private const val DUMP_ALL_STALL_TIMEOUT_MS = 6000L
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
    private var logsJob: Job? = null
    private var dumpCursor = 0L
    private var logCursor = 0L

    init {
        viewModelScope.launch {
            bleManager.connectionState.collect { state ->
                val connected = state == BleConnectionState.RECEIVING_TELEMETRY ||
                        state == BleConnectionState.CONNECTED_WAITING_FOR_DATA ||
                        state == BleConnectionState.READY
                if (connected != _isConnected.value) {
                    _isConnected.value = connected
                    if (connected) {
                        append(TerminalLineType.SYSTEM, "-- link up --")
                    } else {
                        _isAuthorized.value = false
                        monitorJob?.cancel()
                        monitorJob = null
                        if (dumpJob?.isActive == true) {
                            dumpJob?.cancel()
                            dumpJob = null
                            append(TerminalLineType.ERROR, "[DUMP] Request failed: BLE link disconnected.")
                        }
                        if (logsJob?.isActive == true) {
                            logsJob?.cancel()
                            logsJob = null
                            append(TerminalLineType.ERROR, "[LOGS] Request failed: BLE link disconnected.")
                        }
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
        // Incoming diagnostic log lines stream into the transcript.
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
                if (ok) {
                    repository.clearDiagnosticLogs()
                    ACK to "[ACK] Device event log cleared (captures untouched)."
                } else {
                    ERROR to "[ERR] CLEAR DIAGNOSTICS rejected or unauthorized."
                }
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
            TerminalCommand.Logs -> runLogs()
            TerminalCommand.DumpNext -> handleDumpNext()
            is TerminalCommand.DumpPage -> handleDumpPage(command.page)
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
            val canId = repository.configSnapshot.value?.canCandidateId
            val idStr = if (canId != null) "0x${canId.toString(16).uppercase()}" else "0x301"
            if (rpm == null) {
                INFO to "CAN ID $idStr (configured) - waiting for the first matching frame on this link."
            } else {
                INFO to TerminalCommandRegistry.formatRpmStatus(rpm, repository.connectedDevice.value, canId)
            }
        }
    }

    private fun printConfigStatus() {
        runCommand {
            val snapshot = repository.reloadConfigFromController()
            if (snapshot == null || !snapshot.isValid) {
                ERROR to "[ERR] No valid config snapshot received (timeout or disconnected)."
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
                val applied = after != null && abs(after.rpmMultiplier - multiplier) < 0.05
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
            val ok = repository.setEffect(effect)
            if (ok) {
                val note = when (effectId) {
                    5 -> " — UNVERIFIED live preview of estimated CAN RPM"
                    4 -> " — verified calibration active"
                    else -> ""
                }
                ACK to "[ACK] Effect ${effect.id}: ${effect.displayName}$note"
            } else {
                val snapshot = repository.configSnapshot.value
                val calVerified = snapshot?.calibrationVerified == true
                val reason = when {
                    effectId == 4 && !calVerified ->
                        "Effect 4 (RPM Bar) rejected: requires verified calibration record on device. Use Effect 5 for unverified preview."
                    !_isConnected.value ->
                        "Device disconnected."
                    !_isAuthorized.value ->
                        "Unauthorized: owner session required."
                    else ->
                        "SET_EFFECT rejected, unauthorized or timed out."
                }
                ERROR to "[ERR] $reason"
            }
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

    private fun runLogs() {
        if (!connected()) return
        if (!_isAuthorized.value) {
            append(TerminalLineType.ERROR, "[ERR] Unauthorized: owner session required for log export. Type AUTH for instructions.")
            return
        }
        if (_isBusy.value) {
            append(TerminalLineType.WARN, "Another operation is in flight; wait for it to complete.")
            return
        }
        _isBusy.value = true
        logsJob = viewModelScope.launch {
            try {
                val start = logCursor
                append(TerminalLineType.INFO, "[LOGS] Requested historical log page from byte offset $start...")

                val receivedMessages = mutableListOf<String>()
                var pageEnd: HistoricalPageEnd? = null
                var firmwareError: String? = null
                val done = CompletableDeferred<Unit>()
                val startedAtMs = System.currentTimeMillis()

                val collectJob = launch {
                    launch {
                        bleManager.diagnosticLogMessages.collect { msg ->
                            if (msg.severity == LogSeverity.ERROR && msg.timestampMs > startedAtMs) {
                                firmwareError = msg.messageText
                                done.complete(Unit)
                            } else if (msg.timestampMs >= startedAtMs) {
                                receivedMessages.add(msg.messageText)
                            }
                        }
                    }
                    launch {
                        pageEnd = bleManager.pageEndEvents.first()
                        done.complete(Unit)
                    }
                }

                bleManager.requestHistoricalLogPage(start)

                val completedInTime = withTimeoutOrNull(PAGE_COLLECT_TIMEOUT_MS) {
                    done.await()
                } != null

                collectJob.cancel()

                if (firmwareError != null) {
                    append(TerminalLineType.ERROR, "[LOGS] Request failed: firmware rejected ($firmwareError).")
                } else if (pageEnd != null) {
                    logCursor = pageEnd!!.nextOffset
                    receivedMessages.forEach { append(TerminalLineType.INFO, it) }
                    append(TerminalLineType.INFO, "[LOGS] Received ${receivedMessages.size} log messages in page.")
                    append(TerminalLineType.ACK, "[LOGS] Completed page: next offset $logCursor${if (pageEnd!!.isFinished) " (end of logs)" else ""}.")
                } else if (receivedMessages.isNotEmpty()) {
                    receivedMessages.forEach { append(TerminalLineType.INFO, it) }
                    append(TerminalLineType.INFO, "[LOGS] Received ${receivedMessages.size} log messages.")
                    append(TerminalLineType.WARN, "[LOGS] Completed with partial page (timed out waiting for end-of-page).")
                } else if (!completedInTime) {
                    append(TerminalLineType.ERROR, "[LOGS] Request failed: timeout waiting for device response.")
                }
            } catch (e: Exception) {
                append(TerminalLineType.ERROR, "[LOGS] Request failed: ${e.message ?: "unexpected error"}")
            } finally {
                _isBusy.value = false
                logsJob = null
            }
        }
    }

    private fun handleDumpNext() {
        val startRecord = dumpCursor
        val pageNumber = (dumpCursor / 12).toInt() + 1
        runDumpPage(startRecord = startRecord, pageNumber = pageNumber)
    }

    private fun handleDumpPage(page: Int) {
        val startRecord = (page - 1) * 12L
        runDumpPage(startRecord = startRecord, pageNumber = page)
    }

    /**
     * Single 12-record page from the current phone cursor (or an explicit
     * page number). DUMP NEXT resumes from the cursor; DUMP PAGE n jumps.
     * Preserves partial pages and uses end-of-page events instead of discarding records.
     */
    private fun runDumpPage(startRecord: Long, pageNumber: Int) {
        if (!connected()) return
        if (!_isAuthorized.value) {
            append(TerminalLineType.ERROR, "[ERR] Unauthorized: owner session required for capture export. Type AUTH for instructions.")
            return
        }
        if (dumpJob?.isActive == true) {
            append(TerminalLineType.WARN, "A DUMP stream is already running; DUMP CANCEL stops it.")
            return
        }
        dumpJob = viewModelScope.launch {
            try {
                _isBusy.value = true
                append(TerminalLineType.INFO, "[DUMP] Requested page $pageNumber from record $startRecord…")

                // collectPage subscribes first and only then sends the request,
                // so no early record or end-of-page marker can be missed.
                val collectDeferred = async {
                    collectPage(12) { bleManager.requestHistoricalCapturePage(startRecord) }
                }

                val (records, pageEnd, firmwareError) = collectDeferred.await()

                if (firmwareError != null) {
                    append(TerminalLineType.ERROR, "[DUMP] Request failed: firmware rejected ($firmwareError).")
                } else if (records.isNotEmpty()) {
                    records.forEach { append(TerminalLineType.CAPTURE, TerminalCommandRegistry.formatCaptureRecord(it)) }
                    append(TerminalLineType.INFO, "[DUMP] Received ${records.size} records.")
                    dumpCursor = pageEnd?.nextOffset ?: (startRecord + records.size)
                    if (pageEnd != null) {
                        append(TerminalLineType.ACK, "[DUMP] Completed page $pageNumber: ${records.size} records; phone cursor now $dumpCursor${if (pageEnd.isFinished) " (end of capture)" else ""}.")
                    } else {
                        append(TerminalLineType.WARN, "[DUMP] Completed with partial page (timed out waiting for end-of-page): ${records.size} records; phone cursor now $dumpCursor.")
                    }
                } else {
                    if (pageEnd != null) {
                        dumpCursor = pageEnd.nextOffset
                        append(TerminalLineType.INFO, "[DUMP] End of capture (0 records at record $startRecord).")
                    } else {
                        append(TerminalLineType.ERROR, "[DUMP] Request failed: timeout waiting for device response at record $startRecord.")
                    }
                }
            } catch (e: Exception) {
                append(TerminalLineType.ERROR, "[DUMP] Request failed: ${e.message ?: "error"}")
            } finally {
                _isBusy.value = false
                dumpJob = null
            }
        }
    }

    /** Bounded auto-paging walk over the whole capture (cancelable). */
    private fun runDumpAll() {
        if (!connected()) return
        if (!_isAuthorized.value) {
            append(TerminalLineType.ERROR, "[ERR] Unauthorized: owner session required for capture export. Type AUTH for instructions.")
            return
        }
        if (dumpJob?.isActive == true) {
            append(TerminalLineType.WARN, "A DUMP stream is already running.")
            return
        }
        append(TerminalLineType.INFO, "[DUMP ALL] Export requested from record $dumpCursor; DUMP CANCEL aborts.")
        repository.startCaptureExport(dumpCursor)
        dumpJob = viewModelScope.launch {
            try {
                _isBusy.value = true
                var printed = 0
                var lastProgressSignature = -1
                var lastProgressAt = System.currentTimeMillis()
                while (true) {
                    val state = repository.captureExportState.value
                    if (state is CaptureExportState.Idle) {
                        append(TerminalLineType.WARN, "[DUMP ALL] Export terminated or cancelled.")
                        return@launch
                    }
                    if (state is CaptureExportState.Failed) {
                        append(TerminalLineType.ERROR, "[DUMP ALL] Export failed: ${state.reason}")
                        return@launch
                    }
                    if (state is CaptureExportState.Exporting) {
                        val records = state.records
                        while (printed < records.size) {
                            append(TerminalLineType.CAPTURE, TerminalCommandRegistry.formatCaptureRecord(records[printed]))
                            printed++
                        }
                        if (state.isFinished) {
                            dumpCursor = state.nextRecordIndex
                            append(TerminalLineType.ACK, "[DUMP ALL] Export completed: $printed records received.")
                            return@launch
                        }
                    }
                    // Explicit stall timeout: abort when no new record, page-end
                    // or failure arrives within the window — never spin forever.
                    val signature = printed * 2 + if (state is CaptureExportState.Exporting && state.isFinished) 1 else 0
                    if (signature != lastProgressSignature) {
                        lastProgressSignature = signature
                        lastProgressAt = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - lastProgressAt > DUMP_ALL_STALL_TIMEOUT_MS) {
                        repository.cancelCaptureExport()
                        append(TerminalLineType.ERROR,
                            "[DUMP ALL] Export stalled: no page data for ${DUMP_ALL_STALL_TIMEOUT_MS / 1000}s. " +
                                "Phone cursor kept at $dumpCursor; DUMP NEXT resumes.")
                        return@launch
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
     * (independent of the repository's auto-paging export state). [sendRequest]
     * is invoked only AFTER the record/page-end/error collectors are subscribed,
     * so no early record or end-of-page marker can be missed.
     *
     * Terminates on end-of-page or firmware error; when a full page of records
     * arrives first, the trailing end-of-page marker gets a short grace window.
     * Partial pages are preserved on timeout; duplicate record notifications
     * beyond [max] are dropped. A replayed diagnostic error from before this
     * request is never treated as this request's failure.
     */
    private suspend fun collectPage(max: Int, sendRequest: () -> Unit): CollectPageResult {
        val records = mutableListOf<CaptureRecord>()
        var pageEnd: HistoricalPageEnd? = null
        var firmwareError: String? = null
        val done = CompletableDeferred<Unit>()
        val maxRecordsReached = CompletableDeferred<Unit>()
        // completedMessages replays its last message on subscribe; the replay
        // is consumed during subscription below. Only errors assembled after
        // this point may represent a firmware rejection of THIS request.
        val startedAtMs = System.currentTimeMillis()

        val job = viewModelScope.launch {
            launch {
                bleManager.logReassembler.captureRecordEvents.collect { rec ->
                    if (records.size < max) {
                        records.add(rec)
                        if (records.size >= max) maxRecordsReached.complete(Unit)
                    }
                }
            }
            launch {
                pageEnd = bleManager.logReassembler.capturePageEndEvents.first()
                done.complete(Unit)
            }
            launch {
                bleManager.diagnosticLogMessages.collect { msg ->
                    if (msg.severity == LogSeverity.ERROR && msg.timestampMs > startedAtMs) {
                        firmwareError = msg.messageText
                        done.complete(Unit)
                    }
                }
            }
        }

        try {
            sendRequest()
            val completed = withTimeoutOrNull(PAGE_COLLECT_TIMEOUT_MS) {
                select<Boolean> {
                    done.onAwait { true }
                    maxRecordsReached.onAwait { false }
                }
            }
            if (completed == false) {
                // Full page arrived before the end-of-page marker: wait briefly
                // for it so a complete page is not misreported as partial.
                withTimeoutOrNull(PAGE_END_GRACE_MS) { done.await() }
            }
        } finally {
            job.cancel()
        }

        return CollectPageResult(records, pageEnd, firmwareError)
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
