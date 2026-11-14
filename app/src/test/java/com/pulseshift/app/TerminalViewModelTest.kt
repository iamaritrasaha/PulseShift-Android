@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.pulseshift.app

import android.content.Context
import com.pulseshift.app.data.ble.BleConnectionState
import com.pulseshift.app.data.ble.PulseShiftBleManager
import com.pulseshift.app.data.repository.PulseShiftRepository
import com.pulseshift.app.domain.model.AuthState
import com.pulseshift.app.domain.model.BleDevice
import com.pulseshift.app.domain.model.CalibrationFitResult
import com.pulseshift.app.domain.model.CalibrationSessionStatus
import com.pulseshift.app.domain.model.CanHealthState
import com.pulseshift.app.domain.model.CaptureExportState
import com.pulseshift.app.domain.model.CaptureRecord
import com.pulseshift.app.domain.model.ConfigAckResponse
import com.pulseshift.app.domain.model.ConfigSnapshot
import com.pulseshift.app.domain.model.DeviceCommandOutcome
import com.pulseshift.app.domain.model.DiagnosticLogMessage
import com.pulseshift.app.domain.model.DiagnosticsReport
import com.pulseshift.app.domain.model.EffectType
import com.pulseshift.app.domain.model.HistoricalLogLoadingState
import com.pulseshift.app.domain.model.HistoricalPageEnd
import com.pulseshift.app.domain.model.LightingState
import com.pulseshift.app.domain.model.LogSeverity
import com.pulseshift.app.domain.model.OtaUpdateState
import com.pulseshift.app.domain.model.Preset
import com.pulseshift.app.domain.model.ProfileApplyResult
import com.pulseshift.app.domain.model.RgbColor
import com.pulseshift.app.domain.model.RpmTelemetryState
import com.pulseshift.app.domain.model.ShiftLightConfig
import com.pulseshift.app.domain.model.StartupAnimation
import com.pulseshift.app.domain.terminal.TerminalCommand
import com.pulseshift.app.domain.terminal.TerminalCommandParser
import com.pulseshift.app.ui.screens.terminal.TerminalLineType
import com.pulseshift.app.ui.screens.terminal.TerminalViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

class TerminalTestRepository : PulseShiftRepository {
    private val _presets = MutableStateFlow<List<Preset>>(Preset.DefaultPresets)
    override val presets: StateFlow<List<Preset>> = _presets.asStateFlow()

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _authState = MutableStateFlow(AuthState.NOT_ENROLLED)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _lightingState = MutableStateFlow(LightingState())
    override val lightingState: StateFlow<LightingState> = _lightingState.asStateFlow()

    private val _connectedDevice = MutableStateFlow<BleDevice?>(null)
    override val connectedDevice: StateFlow<BleDevice?> = _connectedDevice.asStateFlow()

    private val _shiftLightConfig = MutableStateFlow(ShiftLightConfig())
    override val shiftLightConfig: StateFlow<ShiftLightConfig> = _shiftLightConfig.asStateFlow()

    val _configSnapshot = MutableStateFlow<ConfigSnapshot?>(null)
    override val configSnapshot: StateFlow<ConfigSnapshot?> = _configSnapshot.asStateFlow()

    private val _hasUnsavedChanges = MutableStateFlow(false)
    override val hasUnsavedChanges: StateFlow<Boolean> = _hasUnsavedChanges.asStateFlow()

    private val _lastAckResponse = MutableSharedFlow<ConfigAckResponse>()
    override val lastAckResponse: SharedFlow<ConfigAckResponse> = _lastAckResponse.asSharedFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    override val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    private val _isReconnecting = MutableStateFlow(false)
    override val isReconnecting: StateFlow<Boolean> = _isReconnecting.asStateFlow()

    private val _lightingSyncError = MutableSharedFlow<String>()
    override val lightingSyncError: SharedFlow<String> = _lightingSyncError.asSharedFlow()

    private val _rpmTelemetryState = MutableStateFlow<RpmTelemetryState?>(null)
    override val rpmTelemetryState: StateFlow<RpmTelemetryState?> = _rpmTelemetryState.asStateFlow()

    private val _canHealthState = MutableStateFlow<CanHealthState?>(null)
    override val canHealthState: StateFlow<CanHealthState?> = _canHealthState.asStateFlow()

    val _diagnosticLogMessages = MutableStateFlow<List<DiagnosticLogMessage>>(emptyList())
    override val diagnosticLogMessages: StateFlow<List<DiagnosticLogMessage>> = _diagnosticLogMessages.asStateFlow()

    private val _historicalPageLoadingState = MutableStateFlow<HistoricalLogLoadingState>(HistoricalLogLoadingState.Idle)
    override val historicalPageLoadingState: StateFlow<HistoricalLogLoadingState> = _historicalPageLoadingState.asStateFlow()

    val _isAuthenticated = MutableStateFlow(false)
    override val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private val _diagnosticsReport = MutableStateFlow<DiagnosticsReport?>(null)
    override val diagnosticsReport: StateFlow<DiagnosticsReport?> = _diagnosticsReport.asStateFlow()

    val _captureExportState = MutableStateFlow<CaptureExportState>(CaptureExportState.Idle)
    override val captureExportState: StateFlow<CaptureExportState> = _captureExportState.asStateFlow()

    private val _deviceCommandHistory = MutableStateFlow<List<DeviceCommandOutcome>>(emptyList())
    override val deviceCommandHistory: StateFlow<List<DeviceCommandOutcome>> = _deviceCommandHistory.asStateFlow()

    var setEffectOutcome: (EffectType) -> Boolean = { true }
    var reloadConfigFromControllerOutcome: () -> ConfigSnapshot? = { _configSnapshot.value }
    var clearDeviceDiagnosticHistoryOutcome = true
    var saveConfigurationToControllerOutcome = true
    var restartDeviceOutcome = true
    var lastExportStartRecord: Long? = null

    /** When true, startCaptureExport finishes immediately (dispatch-audit convenience). */
    var exportFinishesImmediately = true

    override suspend fun enrollOwner(secret: ByteArray): Boolean = false
    override suspend fun authenticateChallengeResponse(): Boolean = false
    override suspend fun setPower(power: Boolean) {}
    override suspend fun setBrightness(brightness: Int) {}
    override suspend fun setEffect(effect: EffectType): Boolean = setEffectOutcome(effect)
    override suspend fun setEffectSpeed(speed: Int) {}
    override suspend fun setGlobalColor(color: RgbColor) {}
    override suspend fun setIndividualLedColor(index: Int, color: RgbColor) {}
    override suspend fun setAllLedColors(colors: List<RgbColor>) {}
    override suspend fun setStartupAnimation(animation: StartupAnimation, durationMs: Int) {}
    override suspend fun setLedDirection(direction: Int) {}
    override suspend fun setWarningRgb(color: RgbColor) {}
    override suspend fun updateShiftLightConfig(config: ShiftLightConfig) {}
    override suspend fun setLedThreshold(index: Int, rpm: Int) {}
    override suspend fun applyDecoderConfiguration(canId: Long, byteIndex: Int, isExtended: Boolean, multiplier: Float) {}
    override suspend fun setRpmHysteresis(hysteresisRpm: Int) {}

    override suspend fun applyPreset(preset: Preset) {}
    override suspend fun savePreset(preset: Preset) {}
    override suspend fun updatePreset(preset: Preset) {}
    override suspend fun duplicatePreset(preset: Preset): Preset? = null
    override suspend fun saveCurrentSetupAsPreset(name: String): Preset? = null
    override suspend fun deletePreset(id: String) {}
    override suspend fun restoreDefaultPresets() {}

    override suspend fun apply1000To6000Profile(): ProfileApplyResult = ProfileApplyResult.PERSISTED
    override suspend fun saveConfigurationToController(): Boolean = saveConfigurationToControllerOutcome
    override suspend fun reloadConfigFromController(): ConfigSnapshot? = reloadConfigFromControllerOutcome()
    override suspend fun restoreDefaultControllerConfig() {}
    override suspend fun requestHistoricalLogPage(startOffset: Long) {}
    override suspend fun cancelHistoricalLogRetrieval() {}
    override suspend fun clearDiagnosticLogs() {
        _diagnosticLogMessages.value = emptyList()
    }
    override fun exportLogsAsText(): String = ""
    override fun exportLogsAsCsv(): String = ""
    override suspend fun requestDiagnosticsReport(): DiagnosticsReport? = _diagnosticsReport.value
    override suspend fun reloadConfigurationFromDevice(): Boolean = true
    override suspend fun restartDevice(): Boolean = restartDeviceOutcome
    override suspend fun clearDeviceDiagnosticHistory(): Boolean = clearDeviceDiagnosticHistoryOutcome
    override suspend fun setCaptureRecording(enable: Boolean): Boolean = true
    override suspend fun setCaptureStage(stage: Int): Boolean = true
    override suspend fun setLedTestRpm(rpm: Int, enable: Boolean): Boolean = true
    override fun startCaptureExport(startRecord: Long) {
        lastExportStartRecord = startRecord
        // Finish immediately by default so dispatch-audit runs terminate
        // synchronously; dedicated DUMP ALL tests drive the state progressively.
        if (exportFinishesImmediately) {
            _captureExportState.value = CaptureExportState.Exporting(
                nextRecordIndex = startRecord,
                records = emptyList(),
                isFinished = true
            )
        }
    }
    override fun cancelCaptureExport() {
        _captureExportState.value = CaptureExportState.Idle
    }
    override suspend fun queryCalibrationStatus(): CalibrationSessionStatus? = null
    override suspend fun beginCalibrationSession(): Boolean = false
    override suspend fun startCalibrationCapture(): Boolean = false
    override suspend fun commitCalibrationPoint(refRpm: Int): Boolean = false
    override suspend fun computeCalibration(apply: Boolean): CalibrationFitResult? = null
    override suspend fun abortCalibrationSession(): Boolean = false

    override val otaUpdateState: StateFlow<OtaUpdateState> = MutableStateFlow<OtaUpdateState>(OtaUpdateState.Idle).asStateFlow()
    override suspend fun startOtaUpdate(imageBytes: ByteArray): Boolean = true
    override suspend fun cancelOtaUpdate(): Boolean = true
    override suspend fun confirmOtaUpdate(): Boolean = true
}

class TerminalViewModelTest {

    private val testScope = CoroutineScope(Dispatchers.Unconfined + Job())
    private lateinit var bleManager: PulseShiftBleManager
    private lateinit var repository: TerminalTestRepository
    private lateinit var viewModel: TerminalViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val mockContext = mock(Context::class.java)
        bleManager = PulseShiftBleManager(
            context = mockContext,
            scope = testScope,
            adapterProvider = { null }
        )
        repository = TerminalTestRepository()
        viewModel = TerminalViewModel(bleManager, repository)

        // Set to connected and authorized state for command tests
        bleManager.setConnectionStateForTesting(BleConnectionState.RECEIVING_TELEMETRY)
        repository._isAuthenticated.value = true
    }

    @After
    fun tearDown() {
        bleManager.close()
        testScope.cancel()
        Dispatchers.resetMain()
    }

    private fun captureRecord(i: Int, timestamp: Long = 1000L + i): CaptureRecord =
        CaptureRecord(
            timestampMs = timestamp,
            stage = 1,
            canId = 0x301,
            isExtended = false,
            dlc = 8,
            dataHex = "11223344"
        )

    private fun lastLineOfType(type: TerminalLineType) =
        viewModel.lines.value.last { it.type == type }

    // --- Defect 1: EFFECT must verify firmware ACK before announcing success ---

    @Test
    fun testEffectVerifiesFirmwareAck_successOnProvisionalPreview() {
        repository.setEffectOutcome = { true }
        viewModel.submit("EFFECT 5")

        val lastLine = viewModel.lines.value.last()
        assertEquals(TerminalLineType.ACK, lastLine.type)
        assertTrue(lastLine.text.contains("[ACK] Effect 5: Provisional Preview"))
        assertTrue(lastLine.text.contains("UNVERIFIED live preview"))
    }

    @Test
    fun testEffectVerifiesFirmwareAck_preservesVerifiedCalibrationRestrictionOnEffect4() {
        // Calibration is unverified; firmware rejects effect 4 with invalid state
        repository._configSnapshot.value = ConfigSnapshot(
            snapshotId = 1,
            activeRevision = 1,
            isPowerOn = true,
            brightness = 255,
            effect = EffectType.STATIC,
            calibrationVerified = false
        )
        repository.setEffectOutcome = { effect ->
            // Simulate firmware rejection on effect 4 when calibration is unverified
            !(effect == EffectType.RPM_BAR && !repository._configSnapshot.value!!.calibrationVerified)
        }

        viewModel.submit("EFFECT 4")

        val lastLine = viewModel.lines.value.last()
        assertEquals(TerminalLineType.ERROR, lastLine.type)
        assertTrue("Effect 4 must be rejected when unverified", lastLine.text.contains("Effect 4 (RPM Bar) rejected"))
        assertTrue("Error must advise using Effect 5", lastLine.text.contains("Use Effect 5 for unverified preview"))
        assertFalse("Must NOT announce success before firmware ACK", viewModel.lines.value.any { it.text.contains("[ACK] Effect 4") })
    }

    @Test
    fun testEffectVerifiesFirmwareAck_allowsEffect4WhenCalibrated() {
        repository._configSnapshot.value = ConfigSnapshot(
            snapshotId = 1,
            activeRevision = 1,
            isPowerOn = true,
            brightness = 255,
            effect = EffectType.STATIC,
            calibrationVerified = true
        )
        repository.setEffectOutcome = { true }

        viewModel.submit("EFFECT 4")

        val lastLine = viewModel.lines.value.last()
        assertEquals(TerminalLineType.ACK, lastLine.type)
        assertTrue(lastLine.text.contains("[ACK] Effect 4: RPM Bar"))
    }

    @Test
    fun testEffectFirmwareRejectionReportsExplicitError() {
        // ACK timeout / firmware rejection path: setEffect returns false while
        // connected and authorized, so the failure must be reported, not faked.
        repository.setEffectOutcome = { false }

        viewModel.submit("EFFECT 2")

        val lastLine = viewModel.lines.value.last()
        assertEquals(TerminalLineType.ERROR, lastLine.type)
        assertTrue(lastLine.text.contains("[ERR]"))
        assertFalse(viewModel.lines.value.any { it.text.contains("[ACK] Effect 2") })
    }

    // --- Defect 2: CONFIG STATUS must await matching fresh snapshot ---

    @Test
    fun testConfigStatusAwaitsMatchingFreshSnapshotWithoutFixedDelay() {
        val freshSnapshot = ConfigSnapshot(
            snapshotId = 42,
            activeRevision = 12,
            isPowerOn = true,
            brightness = 255,
            effect = EffectType.PROVISIONAL_PREVIEW,
            calibrationVerified = false,
            rpmMultiplier = 40.0f
        )
        repository.reloadConfigFromControllerOutcome = { freshSnapshot }

        viewModel.submit("CONFIG STATUS")

        val output = lastLineOfType(TerminalLineType.INFO)
        assertTrue("Snapshot output must contain active revision", output.text.contains("Revision     12"))
        assertTrue("Snapshot output must contain multiplier", output.text.contains("Multiplier   40.0"))
        assertTrue("Snapshot output must contain UNVERIFIED", output.text.contains("Cal=UNVERIFIED"))
    }

    @Test
    fun testConfigStatusHandlesTimeoutOrFailure() {
        repository.reloadConfigFromControllerOutcome = { null }

        viewModel.submit("CONFIG STATUS")

        val lastLine = viewModel.lines.value.last()
        assertEquals(TerminalLineType.ERROR, lastLine.type)
        assertTrue(lastLine.text.contains("[ERR] No valid config snapshot received"))
    }

    // --- Defect 3: DUMP PAGE 1 starts at record 0, DUMP NEXT resumes without duplicates ---

    @Test
    fun testDumpPage1StartsAtRecordZeroEvenWhenCursorIsNonZero() {
        viewModel.submit("DUMP PAGE 1")

        val requestLine = viewModel.lines.value.first { it.text.contains("[DUMP] Requested page 1") }
        assertTrue("DUMP PAGE 1 must request from record 0", requestLine.text.contains("from record 0"))
    }

    @Test
    fun testDumpPageNCalculatesCorrectOffset() {
        viewModel.submit("DUMP PAGE 3")

        val requestLine = viewModel.lines.value.first { it.text.contains("[DUMP] Requested page 3") }
        // Page 3: (3 - 1) * 12 = 24
        assertTrue("DUMP PAGE 3 must request from record 24", requestLine.text.contains("from record 24"))
    }

    @Test
    fun testDumpNextResumesAtExactCursorWithoutDuplicates() {
        // Complete page 1 with a partial (5-record) page so the cursor lands on 5
        viewModel.submit("DUMP PAGE 1")
        for (i in 0 until 5) {
            bleManager.logReassembler.emitDirectCaptureRecord(captureRecord(i))
        }
        bleManager.logReassembler.emitDirectCapturePageEnd(HistoricalPageEnd(nextOffset = 5, isFinished = false))

        // Page 1 completed; the transcript must show exactly the 5 records once
        assertEquals(5, viewModel.lines.value.count { it.type == TerminalLineType.CAPTURE })

        viewModel.submit("DUMP NEXT")
        // Resolve the DUMP NEXT request so the test does not wait for its timeout
        bleManager.logReassembler.emitDirectCapturePageEnd(HistoricalPageEnd(nextOffset = 5, isFinished = false))

        val nextRequestLine = viewModel.lines.value.last { it.text.contains("[DUMP] Requested page") }
        assertTrue("DUMP NEXT must resume at exact cursor 5", nextRequestLine.text.contains("from record 5"))
    }

    // --- Defect 4: Preserve partial pages and use end-of-page events ---

    @Test
    fun testCapturePageRetrievalPreservesPartialPagesWithEndOfPage() {
        viewModel.submit("DUMP PAGE 1")
        // Emit 4 records and end-of-page with finished = true while collecting
        for (i in 0 until 4) {
            bleManager.logReassembler.emitDirectCaptureRecord(captureRecord(i, 2000L + i))
        }
        bleManager.logReassembler.emitDirectCapturePageEnd(HistoricalPageEnd(nextOffset = 4, isFinished = true))

        val captureLines = viewModel.lines.value.filter { it.type == TerminalLineType.CAPTURE }
        assertEquals("All 4 partial records must be preserved", 4, captureLines.size)

        val ackLine = lastLineOfType(TerminalLineType.ACK)
        assertTrue(ackLine.text.contains("Completed page 1: 4 records"))
        assertTrue(ackLine.text.contains("(end of capture)"))
    }

    @Test
    fun testFullPageCompletesViaEndOfPageWithoutPartialWarning() {
        viewModel.submit("DUMP PAGE 1")
        for (i in 0 until 12) {
            bleManager.logReassembler.emitDirectCaptureRecord(captureRecord(i))
        }
        bleManager.logReassembler.emitDirectCapturePageEnd(HistoricalPageEnd(nextOffset = 12, isFinished = false))

        assertTrue(viewModel.lines.value.none { it.text.contains("partial page") })
        val ackLine = lastLineOfType(TerminalLineType.ACK)
        assertTrue(ackLine.text.contains("Completed page 1: 12 records"))
        assertTrue(ackLine.text.contains("phone cursor now 12"))
    }

    @Test
    fun testDuplicateNotificationsBeyondPageSizeAreDropped() {
        viewModel.submit("DUMP PAGE 1")
        // Contract violation (page > 12 records): extras beyond the page size
        // must never be displayed as if they belonged to this page.
        for (i in 0 until 14) {
            bleManager.logReassembler.emitDirectCaptureRecord(captureRecord(i))
        }
        bleManager.logReassembler.emitDirectCapturePageEnd(HistoricalPageEnd(nextOffset = 14, isFinished = false))

        assertEquals(
            "Records beyond the 12-record page contract must be dropped",
            12,
            viewModel.lines.value.count { it.type == TerminalLineType.CAPTURE }
        )
    }

    // --- Defect 5: LOGS & DUMP check owner auth and handle all outcomes ---

    @Test
    fun testLogsAndDumpRequireOwnerAuthentication() {
        repository._isAuthenticated.value = false

        viewModel.submit("LOGS")
        assertTrue(viewModel.lines.value.last().text.contains("[ERR] Unauthorized: owner session required"))

        viewModel.submit("DUMP")
        assertTrue(viewModel.lines.value.last().text.contains("[ERR] Unauthorized: owner session required"))

        viewModel.submit("DUMP PAGE 2")
        assertTrue(viewModel.lines.value.last().text.contains("[ERR] Unauthorized: owner session required"))

        viewModel.submit("DUMP ALL")
        assertTrue(viewModel.lines.value.last().text.contains("[ERR] Unauthorized: owner session required"))
    }

    @Test
    fun testLogsHandlesFirmwareRejection() {
        viewModel.submit("LOGS")

        // Simulate firmware rejecting with AUTH REQUIRED error chunk, assembled
        // strictly after the request was sent (timestamp ordering matters).
        bleManager.logReassembler.emitDirectMessage(
            DiagnosticLogMessage(
                id = "err-1",
                messageId = 999,
                timestampMs = System.currentTimeMillis() + 1,
                messageText = "AUTH REQUIRED: owner session needed for log/capture export",
                severity = LogSeverity.ERROR
            )
        )

        val errLine = lastLineOfType(TerminalLineType.ERROR)
        assertTrue(errLine.text.contains("firmware rejected"))
        assertTrue(errLine.text.contains("AUTH REQUIRED"))
    }

    @Test
    fun testLogsReplayOfStaleErrorDoesNotFailFreshRequest() {
        // A pre-existing error message (replayed by completedMessages on
        // subscribe) must never be reported as the new request's failure.
        bleManager.logReassembler.emitDirectMessage(
            DiagnosticLogMessage(
                id = "stale",
                messageId = 500,
                timestampMs = System.currentTimeMillis() - 60_000,
                messageText = "Old unrelated error",
                severity = LogSeverity.ERROR
            )
        )

        viewModel.submit("LOGS")
        // End-of-page arrives instead: the request succeeded.
        bleManager.logReassembler.emitDirectLogPageEnd(HistoricalPageEnd(nextOffset = 20, isFinished = true))

        assertTrue(
            "Stale error must not poison the fresh LOGS request",
            viewModel.lines.value.none { it.text.contains("firmware rejected") }
        )
        val ackLine = lastLineOfType(TerminalLineType.ACK)
        assertTrue(ackLine.text.contains("next offset 20"))
        assertTrue(ackLine.text.contains("(end of logs)"))
    }

    @Test
    fun testLogsAdvancesCursorToPageEndNextOffset() {
        viewModel.submit("LOGS")
        bleManager.logReassembler.emitDirectLogPageEnd(HistoricalPageEnd(nextOffset = 400, isFinished = false))

        val ackLine = lastLineOfType(TerminalLineType.ACK)
        assertTrue(ackLine.text.contains("next offset 400"))

        // Next LOGS request must resume from the advanced offset
        viewModel.submit("LOGS")
        bleManager.logReassembler.emitDirectLogPageEnd(HistoricalPageEnd(nextOffset = 800, isFinished = true))
        val requestLine = viewModel.lines.value.last { it.text.contains("[LOGS] Requested historical log page") }
        assertTrue("LOGS must resume from byte offset 400", requestLine.text.contains("offset 400"))
    }

    @Test
    fun testLogsTimeoutReportsErrorWithoutFabricatedSuccess() {
        viewModel.submit("LOGS")
        // No response within the 4 s bounded timeout (real-time test) — the
        // transcript must show a timeout error, never an ACK.
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline &&
            viewModel.lines.value.lastOrNull()?.type != TerminalLineType.ERROR
        ) {
            Thread.sleep(50)
        }
        val lastLine = viewModel.lines.value.last()
        assertEquals(TerminalLineType.ERROR, lastLine.type)
        assertTrue(lastLine.text.contains("timeout waiting for device response"))
        assertFalse(viewModel.lines.value.any { it.type == TerminalLineType.ACK && it.text.contains("[LOGS]") })
    }

    // --- DUMP ALL: bounded, resumable, cancelable ---

    @Test
    fun testDumpAllResumesFromPhoneCursorAndTerminatesOnFinish() {
        repository.exportFinishesImmediately = false
        val records = (0 until 5).map { captureRecord(it) }
        repository._captureExportState.value = CaptureExportState.Exporting(
            nextRecordIndex = 0, records = records, isFinished = false
        )

        viewModel.submit("DUMP ALL")
        assertEquals("DUMP ALL must resume from the phone cursor", 0L, repository.lastExportStartRecord)

        // Pages complete: same records, finished marker with device offset 5
        repository._captureExportState.value = CaptureExportState.Exporting(
            nextRecordIndex = 5, records = records, isFinished = true
        )

        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline &&
            viewModel.lines.value.none { it.type == TerminalLineType.ACK && it.text.contains("[DUMP ALL] Export completed") }
        ) {
            Thread.sleep(20)
        }

        val ackLine = lastLineOfType(TerminalLineType.ACK)
        assertTrue(ackLine.text.contains("Export completed: 5 records received"))
    }

    @Test
    fun testDumpCancelStopsStreamAndKeepsCursor() {
        repository.exportFinishesImmediately = false
        repository._captureExportState.value = CaptureExportState.Exporting(
            nextRecordIndex = 0, records = emptyList(), isFinished = false
        )

        viewModel.submit("DUMP ALL")
        viewModel.submit("DUMP CANCEL")

        val ackLine = lastLineOfType(TerminalLineType.ACK)
        assertTrue(ackLine.text.contains("Cancelled; phone cursor kept at 0"))
        assertEquals(CaptureExportState.Idle, repository._captureExportState.value)
    }

    @Test
    fun testDisconnectDuringDumpReportsErrorAndClearsAuth() {
        repository.exportFinishesImmediately = false
        repository._captureExportState.value = CaptureExportState.Exporting(
            nextRecordIndex = 0, records = emptyList(), isFinished = false
        )
        viewModel.submit("DUMP ALL")

        bleManager.setConnectionStateForTesting(BleConnectionState.DISCONNECTED)

        assertTrue(
            "Disconnect must abort the dump with an explicit error",
            viewModel.lines.value.any { it.text.contains("BLE link disconnected") }
        )
        assertFalse("Authorization must reset on disconnect", viewModel.isAuthorized.value)
    }

    // --- Defect 6: CLEAR DIAGNOSTICS must not clear local logs on failure ---

    @Test
    fun testClearDiagnosticsDoesNotClearLocalLogsOrTranscriptOnDeviceFailure() {
        repository.clearDeviceDiagnosticHistoryOutcome = false

        val sampleLogs = listOf(
            DiagnosticLogMessage(
                id = "a", messageId = 1, timestampMs = 1000L,
                messageText = "Boot completed", severity = LogSeverity.INFO
            ),
            DiagnosticLogMessage(
                id = "b", messageId = 2, timestampMs = 2000L,
                messageText = "CAN bus noise", severity = LogSeverity.WARNING
            )
        )
        repository._diagnosticLogMessages.value = sampleLogs

        viewModel.submit("CLEAR DIAGNOSTICS")
        assertNotNull("Confirmation dialog must be requested", viewModel.pendingConfirmation.value)
        assertEquals(TerminalCommand.ClearDiagnostics, viewModel.pendingConfirmation.value)

        // User confirms
        viewModel.onConfirmDestructive()

        val lastLine = viewModel.lines.value.last()
        assertEquals(TerminalLineType.ERROR, lastLine.type)
        assertTrue(lastLine.text.contains("[ERR] CLEAR DIAGNOSTICS rejected or unauthorized."))

        // CRUCIAL: repository diagnostic logs must NOT be cleared!
        assertEquals("Local diagnostic logs must remain untouched on device failure", 2, repository.diagnosticLogMessages.value.size)

        // Local terminal transcript must NOT be cleared!
        assertTrue("Transcript must retain lines", viewModel.lines.value.isNotEmpty())
    }

    @Test
    fun testClearDiagnosticsClearsOnlyWhenDeviceRequestSucceeds() {
        repository.clearDeviceDiagnosticHistoryOutcome = true
        repository._diagnosticLogMessages.value = listOf(
            DiagnosticLogMessage(
                id = "a", messageId = 1, timestampMs = 1000L,
                messageText = "Log line", severity = LogSeverity.INFO
            )
        )

        viewModel.submit("CLEAR DIAGNOSTICS")
        viewModel.onConfirmDestructive()

        val lastLine = viewModel.lines.value.last()
        assertEquals(TerminalLineType.ACK, lastLine.type)
        assertTrue(lastLine.text.contains("[ACK] Device event log cleared"))
        assertTrue("Diagnostic logs must be cleared on success", repository.diagnosticLogMessages.value.isEmpty())
    }

    // --- Defect 7: Audit every command advertised by HELP ---

    @Test
    fun testHelpCatalogCommandsParseAndDispatchWithoutUnknownCommand() {
        // Streaming commands are resolved right after submit so the audit does
        // not sit through their bounded timeouts; their full behavior has
        // dedicated tests above.
        val resolvers: Map<String, () -> Unit> = mapOf(
            "LOGS" to {
                bleManager.logReassembler.emitDirectLogPageEnd(HistoricalPageEnd(nextOffset = 0, isFinished = true))
            },
            "DUMP" to {
                bleManager.logReassembler.emitDirectCapturePageEnd(HistoricalPageEnd(nextOffset = 0, isFinished = false))
            },
            "DUMP NEXT" to {
                bleManager.logReassembler.emitDirectCapturePageEnd(HistoricalPageEnd(nextOffset = 0, isFinished = false))
            },
            "DUMP PAGE 1" to {
                bleManager.logReassembler.emitDirectCapturePageEnd(HistoricalPageEnd(nextOffset = 0, isFinished = false))
            }
        )

        val testInputs = listOf(
            "HELP", "?", "STATUS", "CAN STATUS", "BLE STATUS", "RPM STATUS",
            "RPM MONITOR", "RPM MONITOR ON", "RPM MONITOR OFF",
            "RPM MULTIPLIER 40.0", "CONFIG STATUS", "CONFIG SAVE", "CONFIG RESET",
            "EFFECT 5", "RPM TEST 4000", "RPM TEST OFF", "STAGE 2",
            "CAPTURE STOP", "CAPTURE RESUME", "LOGS",
            "DUMP", "DUMP NEXT", "DUMP PAGE 1", "DUMP ALL", "DUMP CANCEL",
            "RESTART", "CLEAR DIAGNOSTICS", "AUTH",
            "STREAM ON", "STREAM OFF", "CLEAR", "CLEAR YES"
        )

        for (cmd in testInputs) {
            val parseResult = TerminalCommandParser.parse(cmd)
            assertTrue("Command '$cmd' must parse successfully", parseResult is TerminalCommandParser.ParseResult.Success)
            viewModel.submit(cmd)
            resolvers[cmd]?.invoke()
            assertFalse("Command '$cmd' must not produce UnknownCommand error",
                viewModel.lines.value.last().text.contains("Unknown command"))
        }
    }

    @Test
    fun testHelpCatalogCommandSyntaxMatchesParser() {
        // Every explicit syntax documented in HELP must parse with valid args.
        assertTrue(TerminalCommandParser.parse("EFFECT 0") is TerminalCommandParser.ParseResult.Success)
        assertTrue(TerminalCommandParser.parse("EFFECT 5") is TerminalCommandParser.ParseResult.Success)
        assertTrue(TerminalCommandParser.parse("EFFECT 6") is TerminalCommandParser.ParseResult.Failure)
        assertTrue(TerminalCommandParser.parse("RPM TEST 20000") is TerminalCommandParser.ParseResult.Success)
        assertTrue(TerminalCommandParser.parse("RPM TEST 20001") is TerminalCommandParser.ParseResult.Failure)
        assertTrue(TerminalCommandParser.parse("STAGE 3") is TerminalCommandParser.ParseResult.Success)
        assertTrue(TerminalCommandParser.parse("STAGE 0") is TerminalCommandParser.ParseResult.Failure)
        assertTrue(TerminalCommandParser.parse("RPM MULTIPLIER 1000.0") is TerminalCommandParser.ParseResult.Success)
        assertTrue(TerminalCommandParser.parse("RPM MULTIPLIER 0.05") is TerminalCommandParser.ParseResult.Failure)
        assertTrue(TerminalCommandParser.parse("DUMP PAGE 0") is TerminalCommandParser.ParseResult.Failure)
    }

    @Test
    fun testStreamAndClearYesShowUnsupportedMessage() {
        viewModel.submit("STREAM ON")
        assertTrue(viewModel.lines.value.last().text.contains("UNSUPPORTED OVER BLE"))

        viewModel.submit("CLEAR YES")
        assertTrue(viewModel.lines.value.last().text.contains("UNSUPPORTED OVER BLE"))
    }

    // --- Disconnect handling ---

    @Test
    fun testDisconnectDropsLinkAndReportsError() {
        bleManager.setConnectionStateForTesting(BleConnectionState.DISCONNECTED)
        val lastLine = viewModel.lines.value.last()
        assertTrue("Link down must be reported", lastLine.text.contains("-- link down --"))
        assertFalse("Authorized state must reset", viewModel.isAuthorized.value)
    }

    @Test
    fun testCommandsRefusedWhileDisconnected() {
        bleManager.setConnectionStateForTesting(BleConnectionState.DISCONNECTED)
        viewModel.submit("CONFIG SAVE")
        assertTrue(
            "Disconnected commands must explain why",
            viewModel.lines.value.last().text.contains("Disconnected")
        )
    }
}
