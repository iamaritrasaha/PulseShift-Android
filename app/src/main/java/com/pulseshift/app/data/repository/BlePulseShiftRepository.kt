package com.pulseshift.app.data.repository

import android.util.Log
import com.pulseshift.app.data.ble.PulseShiftBleManager
import com.pulseshift.app.data.ble.PulseShiftBleProtocol
import com.pulseshift.app.data.local.OwnerCredentialManager
import com.pulseshift.app.data.local.PulseShiftDataStore
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
import com.pulseshift.app.domain.model.DeviceCommandPhase
import com.pulseshift.app.domain.model.DiagnosticLogMessage
import com.pulseshift.app.domain.model.DiagnosticsReport
import com.pulseshift.app.domain.model.EffectType
import com.pulseshift.app.domain.model.HistoricalLogLoadingState
import com.pulseshift.app.domain.model.LightingState
import com.pulseshift.app.domain.model.Preset
import com.pulseshift.app.domain.model.RgbColor
import com.pulseshift.app.domain.model.RpmTelemetryState
import com.pulseshift.app.domain.model.ShiftLightConfig
import com.pulseshift.app.domain.model.StartupAnimation
import com.pulseshift.app.domain.model.WarningStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class BlePulseShiftRepository(
    private val bleManager: PulseShiftBleManager,
    private val dataStore: PulseShiftDataStore
) : PulseShiftRepository {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val transactionMutex = Mutex()
    private val credentialManager = OwnerCredentialManager(bleManager.context)

    private val _lightingState = MutableStateFlow(
        LightingState(
            isPowerOn = true,
            brightness = 200,
            effect = EffectType.STATIC,
            effectSpeed = 50,
            globalColor = RgbColor.BrandViolet,
            ledColors = List(6) { RgbColor.BrandViolet },
            isHardwareConfirmed = false,
            isPendingSync = false
        )
    )
    override val lightingState: StateFlow<LightingState> = _lightingState.asStateFlow()

    override val connectedDevice: StateFlow<BleDevice?> = bleManager.connectedDevice

    private val _presets = MutableStateFlow<List<Preset>>(Preset.DefaultPresets)
    override val presets: StateFlow<List<Preset>> = _presets.asStateFlow()

    private val _shiftLightConfig = MutableStateFlow(ShiftLightConfig())
    override val shiftLightConfig: StateFlow<ShiftLightConfig> = _shiftLightConfig.asStateFlow()

    override val configSnapshot: StateFlow<ConfigSnapshot?> = bleManager.configSnapshots

    private val _hasUnsavedChanges = MutableStateFlow(false)
    override val hasUnsavedChanges: StateFlow<Boolean> = _hasUnsavedChanges.asStateFlow()

    private val _lastAckResponse = MutableSharedFlow<ConfigAckResponse>(extraBufferCapacity = 16)
    override val lastAckResponse: SharedFlow<ConfigAckResponse> = _lastAckResponse.asSharedFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    override val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private val _authState = MutableStateFlow(AuthState.NOT_ENROLLED)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    // Telemetry & Diagnostic Flows
    override val rpmTelemetryState: StateFlow<RpmTelemetryState?> = bleManager.rpmTelemetryState
    override val canHealthState: StateFlow<CanHealthState?> = bleManager.canHealthState

    private val _diagnosticLogMessages = MutableStateFlow<List<DiagnosticLogMessage>>(emptyList())
    override val diagnosticLogMessages: StateFlow<List<DiagnosticLogMessage>> = _diagnosticLogMessages.asStateFlow()

    private val _historicalPageLoadingState = MutableStateFlow<HistoricalLogLoadingState>(HistoricalLogLoadingState.Idle)
    override val historicalPageLoadingState: StateFlow<HistoricalLogLoadingState> = _historicalPageLoadingState.asStateFlow()

    // Device command surface (Diagnostics / Advanced Commands)
    private val _diagnosticsReport = MutableStateFlow<DiagnosticsReport?>(null)
    override val diagnosticsReport: StateFlow<DiagnosticsReport?> = _diagnosticsReport.asStateFlow()

    private val _captureExportState = MutableStateFlow<CaptureExportState>(CaptureExportState.Idle)
    override val captureExportState: StateFlow<CaptureExportState> = _captureExportState.asStateFlow()

    private val _deviceCommandHistory = MutableStateFlow<List<DeviceCommandOutcome>>(emptyList())
    override val deviceCommandHistory: StateFlow<List<DeviceCommandOutcome>> = _deviceCommandHistory.asStateFlow()

    private val _isDeviceBusy = MutableStateFlow(false)
    val isDeviceBusy: StateFlow<Boolean> = _isDeviceBusy.asStateFlow()

    private val outcomeIdCounter = AtomicLong(1)
    private var captureAutoExportJob: Job? = null

    private var currentSeqCounter = 1

    private data class AckKey(val sequence: Int, val opcode: Byte)
    private val ackWaiters = ConcurrentHashMap<AckKey, CompletableDeferred<ConfigAckResponse>>()
    private val snapshotWaiters = ConcurrentHashMap<Int, CompletableDeferred<ConfigSnapshot>>()
    private val diagnosticsWaiters = ConcurrentHashMap<Int, CompletableDeferred<DiagnosticsReport>>()

    // Explicitly tracked draft intended values for ALL 16 configuration fields
    private var draftPower = true
    private var draftBrightness = 200
    private var draftEffect = EffectType.STATIC
    private var draftEffectSpeed = 50
    private var draftLedColors = MutableList(6) { RgbColor.BrandViolet }
    private var draftLedDirection = 0
    private var draftStartupAnimation = StartupAnimation.SWEEP
    private var draftStartupDurationMs = 2000
    private var draftThresholds = mutableListOf(3000, 4000, 5000, 6000, 7000, 8000)
    private var draftWarningRpm = 8500
    private var draftWarningStyleId = 0
    private var draftWarningColor = RgbColor.RacingRed
    private var draftRpmMultiplier = 40.0f
    private var draftCanCandidateId = 0x301L
    private var draftCandidateByteIndex = 0
    private var draftIsExtendedFrame = false

    @Synchronized
    private fun nextSeq(): Int {
        val s = currentSeqCounter
        currentSeqCounter = if (currentSeqCounter >= 65535) 1 else currentSeqCounter + 1
        return s
    }

    private fun updateDraftsFromSnapshot(snapshot: ConfigSnapshot) {
        draftPower = snapshot.isPowerOn
        draftBrightness = snapshot.brightness
        draftEffect = snapshot.effect
        draftEffectSpeed = snapshot.effectSpeed
        draftLedColors = snapshot.ledColors.toMutableList()
        draftLedDirection = snapshot.ledDirection
        draftStartupAnimation = snapshot.startupAnimation
        draftStartupDurationMs = snapshot.startupDurationMs
        draftThresholds = snapshot.rpmThresholds.toMutableList()
        draftWarningRpm = snapshot.warningRpm
        draftWarningStyleId = snapshot.warningPatternId
        draftWarningColor = snapshot.warningColor
        draftRpmMultiplier = snapshot.rpmMultiplier
        draftCanCandidateId = snapshot.canCandidateId
        draftCandidateByteIndex = snapshot.candidateByteIndex
        draftIsExtendedFrame = snapshot.isExtendedFrame
    }

    private suspend fun sendCommandAndWaitAck(
        commandBytes: ByteArray,
        sequence: Int,
        opcode: Byte,
        timeoutMs: Long = 5000L
    ): ConfigAckResponse? {
        if (!bleManager.hasConfigV2Support()) return null
        val key = AckKey(sequence, opcode)
        val deferred = CompletableDeferred<ConfigAckResponse>()
        ackWaiters[key] = deferred
        bleManager.enqueueConfigCommand(commandBytes)

        return try {
            withTimeout(timeoutMs) {
                deferred.await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("PulseShiftRepo", "Timeout waiting ACK for opcode 0x${opcode.toString(16)} seq $sequence")
            null
        } finally {
            ackWaiters.remove(key)
        }
    }

    private suspend fun requestAndWaitSnapshot(
        sequence: Int,
        timeoutMs: Long = 5000L
    ): ConfigSnapshot? {
        if (!bleManager.hasConfigV2Support()) return null
        val deferred = CompletableDeferred<ConfigSnapshot>()
        snapshotWaiters[sequence] = deferred
        bleManager.enqueueConfigCommand(PulseShiftBleProtocol.buildGetSnapshotV2(sequence))

        return try {
            withTimeout(timeoutMs) {
                deferred.await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("PulseShiftRepo", "Timeout waiting ConfigSnapshot for snapshotId/sequence $sequence")
            null
        } finally {
            snapshotWaiters.remove(sequence)
        }
    }

    init {
        // Collect state updates from V1 hardware notifications
        scope.launch {
            bleManager.deviceStateUpdates.collect { state ->
                _lightingState.value = state
            }
        }

        // Collect V1 command ACKs
        scope.launch {
            bleManager.commandAcks.collect { ack ->
                if (ack.isSuccess) {
                    _lightingState.value = _lightingState.value.copy(
                        isHardwareConfirmed = true,
                        isPendingSync = false
                    )
                }
            }
        }

        // Collect V2 Configuration ACKs & correlate sequence & opcode waiters
        scope.launch {
            bleManager.configAcksV2.collect { ack ->
                _lastAckResponse.emit(ack)
                val key = AckKey(ack.sequenceRef, ack.opcode)
                val waiter = ackWaiters.remove(key)
                waiter?.complete(ack)
            }
        }

        // Synchronize UI from ConfigSnapshot when snapshot notifications arrive
        scope.launch {
            bleManager.configSnapshots.collect { snapshot ->
                if (snapshot != null && snapshot.isValid) {
                    val waiter = snapshotWaiters.remove(snapshot.snapshotId)
                    waiter?.complete(snapshot)

                    // UNSOLICITED SNAPSHOT PROTECTION: Do NOT overwrite local draft controls if unsaved changes exist
                    if (!_hasUnsavedChanges.value) {
                        updateDraftsFromSnapshot(snapshot)
                        _lightingState.value = LightingState(
                            isPowerOn = snapshot.isPowerOn,
                            brightness = snapshot.brightness,
                            effect = snapshot.effect,
                            effectSpeed = snapshot.effectSpeed,
                            globalColor = snapshot.globalColor,
                            ledColors = snapshot.ledColors,
                            isHardwareConfirmed = true,
                            isPendingSync = false
                        )
                        _shiftLightConfig.value = ShiftLightConfig(
                            thresholds = snapshot.rpmThresholds,
                            warningRpm = snapshot.warningRpm,
                            warningStyle = when (snapshot.warningPatternId) {
                                1 -> WarningStyle.STROBE
                                2 -> WarningStyle.SOLID
                                else -> WarningStyle.FLASH
                            },
                            colors = listOf(
                                RgbColor.NeonGreen,
                                RgbColor.NeonGreen,
                                RgbColor.AmberWarning,
                                RgbColor.AmberWarning,
                                RgbColor.SunsetOrange,
                                snapshot.warningColor
                            )
                        )
                    }
                }
            }
        }

        // Collect reassembled diagnostic log messages
        scope.launch {
            bleManager.diagnosticLogMessages.collect { message ->
                val current = _diagnosticLogMessages.value.toMutableList()
                current.add(0, message)
                if (current.size > 500) {
                    current.removeAt(current.size - 1)
                }
                _diagnosticLogMessages.value = current
            }
        }

        // Collect historical log page-end events
        scope.launch {
            bleManager.pageEndEvents.collect { pageEnd ->
                _historicalPageLoadingState.value = HistoricalLogLoadingState.PageLoaded(
                    nextOffset = pageEnd.nextOffset,
                    isFinished = pageEnd.isFinished
                )
            }
        }

        // Collect parsed diagnostics TLV reports (GET_DIAGNOSTICS responses)
        scope.launch {
            bleManager.snapshotReassembler.diagnosticsReports.collect { report ->
                _diagnosticsReport.value = report
                val waiter = diagnosticsWaiters.remove(report.snapshotId)
                waiter?.complete(report)
            }
        }

        // CAN capture page streaming: records arrive on a dedicated flow; the
        // bounded-page export walks pages automatically until finished.
        scope.launch {
            bleManager.logReassembler.captureRecordEvents.collect { record ->
                val current = _captureExportState.value
                if (current is CaptureExportState.Exporting) {
                    _captureExportState.value = current.copy(records = current.records + record)
                }
            }
        }
        scope.launch {
            bleManager.logReassembler.capturePageEndEvents.collect { pageEnd ->
                val current = _captureExportState.value
                if (current is CaptureExportState.Exporting) {
                    _captureExportState.value = current.copy(
                        nextRecordIndex = pageEnd.nextOffset,
                        isFinished = pageEnd.isFinished
                    )
                    if (!pageEnd.isFinished && current.records.size + 12 < MAX_CAPTURE_EXPORT_RECORDS) {
                        bleManager.requestHistoricalCapturePage(pageEnd.nextOffset)
                    } else if (pageEnd.isFinished) {
                        bleManager.isCaptureRetrievalActive = false
                        recordDeviceCommand("Export CAN capture", DeviceCommandPhase.SAVED,
                            "${current.records.size}+ records exported")
                    }
                }
            }
        }

        // Auto-reconnect preference and preset persistence
        scope.launch {
            dataStore.autoReconnect.collect { enabled ->
                bleManager.setAutoReconnectEnabled(enabled)
            }
        }
        scope.launch {
            dataStore.customPresetsJson.collect { json ->
                val stored = parseCustomPresets(json)
                if (stored != null) {
                    val defaults = Preset.DefaultPresets
                    _presets.value = defaults + stored
                }
            }
        }

        // Owner authentication across reconnects: when a connection carries
        // telemetry, auto-prove the stored credential (challenge-response).
        scope.launch {
            bleManager.connectionState.collect { state ->
                if (state == com.pulseshift.app.data.ble.BleConnectionState.RECEIVING_TELEMETRY) {
                    checkAuthStateOnConnect()
                    if (_authState.value == AuthState.ENROLLED && !_isAuthenticated.value) {
                        val ok = authenticateChallengeResponse()
                        if (ok) recordDeviceCommand("Owner authentication", DeviceCommandPhase.APPLIED, "Session authorized")
                        else recordDeviceCommand("Owner authentication", DeviceCommandPhase.FAILED, "Challenge-response rejected")
                    }
                }
            }
        }
    }

    fun checkAuthStateOnConnect() {
        val deviceAddress = bleManager.connectedDevice.value?.address ?: run {
            _authState.value = AuthState.NOT_ENROLLED
            return
        }
        if (credentialManager.hasEnrolledSecret(deviceAddress)) {
            _authState.value = AuthState.ENROLLED
        } else {
            _authState.value = AuthState.NOT_ENROLLED
        }
    }

    override suspend fun enrollOwner(secret: ByteArray): Boolean {
        return transactionMutex.withLock {
            if (!bleManager.hasConfigV2Support()) return@withLock false
            val deviceAddress = bleManager.connectedDevice.value?.address ?: return@withLock false

            _authState.value = AuthState.ENROLLING
            val seq = nextSeq()
            val ack = sendCommandAndWaitAck(
                PulseShiftBleProtocol.buildEnrollOwnerV2(seq, secret),
                sequence = seq,
                opcode = PulseShiftBleProtocol.V2_OPCODE_ENROLL_OWNER
            )

            if (ack != null && ack.isSuccess && ack.isPersisted) {
                val saved = credentialManager.saveEnrolledSecret(deviceAddress, secret)
                if (saved) {
                    _authState.value = AuthState.AUTHENTICATED
                    _isAuthenticated.value = true
                    true
                } else {
                    Log.e("PulseShiftRepo", "ESP32 enrolled successfully but Android KeyStore credential persistence failed!")
                    _authState.value = AuthState.RECOVERY_REQUIRED
                    false
                }
            } else {
                Log.e("PulseShiftRepo", "ENROLL_OWNER failed: status=${ack?.statusCode}")
                _authState.value = if (ack?.statusCode == PulseShiftBleProtocol.STATUS_ERR_UNAUTHORIZED) AuthState.BOOT_AUTHORIZATION_REQUIRED else AuthState.AUTHENTICATION_REJECTED
                false
            }
        }
    }

    override suspend fun authenticateChallengeResponse(): Boolean {
        return transactionMutex.withLock {
            if (!bleManager.hasConfigV2Support()) return@withLock false
            val deviceAddress = bleManager.connectedDevice.value?.address ?: return@withLock false

            val enrolledSecret = credentialManager.getEnrolledSecret(deviceAddress)
            if (enrolledSecret == null) {
                Log.w("PulseShiftRepo", "No enrolled owner secret found for $deviceAddress")
                _authState.value = AuthState.NOT_ENROLLED
                _isAuthenticated.value = false
                return@withLock false
            }

            _authState.value = AuthState.AUTHENTICATING

            val seqChallenge = nextSeq()
            val ackChallenge = sendCommandAndWaitAck(
                PulseShiftBleProtocol.buildRequestChallengeV2(seqChallenge),
                sequence = seqChallenge,
                opcode = PulseShiftBleProtocol.V2_OPCODE_REQUEST_CHALLENGE
            )

            if (ackChallenge == null || !ackChallenge.isSuccess || ackChallenge.payload.size < 4) {
                Log.e("PulseShiftRepo", "REQUEST_CHALLENGE failed or invalid nonce payload")
                _authState.value = AuthState.AUTHENTICATION_REJECTED
                _isAuthenticated.value = false
                return@withLock false
            }

            val nonceBytes = ackChallenge.payload.copyOf(4)

            val seqAuth = nextSeq()
            val proof = PulseShiftBleProtocol.computeAuthProof(enrolledSecret, nonceBytes, seqAuth)

            val ackAuth = sendCommandAndWaitAck(
                PulseShiftBleProtocol.buildAuthenticateV2(seqAuth, proof),
                sequence = seqAuth,
                opcode = PulseShiftBleProtocol.V2_OPCODE_AUTHENTICATE
            )

            if (ackAuth != null && ackAuth.isSuccess) {
                _authState.value = AuthState.AUTHENTICATED
                _isAuthenticated.value = true
                true
            } else {
                Log.e("PulseShiftRepo", "AUTHENTICATE failed: status=${ackAuth?.statusCode}")
                _authState.value = if (ackAuth?.statusCode == PulseShiftBleProtocol.STATUS_ERR_UNAUTHORIZED) AuthState.SESSION_EXPIRED else AuthState.AUTHENTICATION_REJECTED
                _isAuthenticated.value = false
                false
            }
        }
    }

    override suspend fun setPower(power: Boolean) {
        transactionMutex.withLock {
            draftPower = power
            val hasV2 = bleManager.hasConfigV2Support()
            _lightingState.value = _lightingState.value.copy(
                isPowerOn = power,
                isPendingSync = hasV2,
                isHardwareConfirmed = if (hasV2) _lightingState.value.isHardwareConfirmed else false
            )
            _hasUnsavedChanges.value = true
            if (hasV2) {
                val seq = nextSeq()
                bleManager.enqueueConfigCommand(PulseShiftBleProtocol.buildSetPowerV2(seq, power))
            }
        }
    }

    override suspend fun setBrightness(brightness: Int) {
        transactionMutex.withLock {
            draftBrightness = brightness
            val hasV2 = bleManager.hasConfigV2Support()
            _lightingState.value = _lightingState.value.copy(
                brightness = brightness,
                isPendingSync = hasV2,
                isHardwareConfirmed = if (hasV2) _lightingState.value.isHardwareConfirmed else false
            )
            _hasUnsavedChanges.value = true
            if (hasV2) {
                val seq = nextSeq()
                bleManager.enqueueConfigCommand(PulseShiftBleProtocol.buildSetBrightnessV2(seq, brightness))
            }
        }
    }

    override suspend fun setEffect(effect: EffectType) {
        transactionMutex.withLock {
            draftEffect = effect
            val hasV2 = bleManager.hasConfigV2Support()
            _lightingState.value = _lightingState.value.copy(
                effect = effect,
                isPendingSync = hasV2,
                isHardwareConfirmed = if (hasV2) _lightingState.value.isHardwareConfirmed else false
            )
            _hasUnsavedChanges.value = true
            if (hasV2) {
                val seq = nextSeq()
                bleManager.enqueueConfigCommand(PulseShiftBleProtocol.buildSetEffectV2(seq, effect.id, _lightingState.value.effectSpeed))
            }
        }
    }

    override suspend fun setEffectSpeed(speed: Int) {
        transactionMutex.withLock {
            draftEffectSpeed = speed
            val hasV2 = bleManager.hasConfigV2Support()
            _lightingState.value = _lightingState.value.copy(
                effectSpeed = speed,
                isPendingSync = hasV2,
                isHardwareConfirmed = if (hasV2) _lightingState.value.isHardwareConfirmed else false
            )
            _hasUnsavedChanges.value = true
            if (hasV2) {
                val seq = nextSeq()
                bleManager.enqueueConfigCommand(PulseShiftBleProtocol.buildSetEffectV2(seq, _lightingState.value.effect.id, speed))
            }
        }
    }

    override suspend fun setGlobalColor(color: RgbColor) {
        transactionMutex.withLock {
            draftLedColors = MutableList(6) { color }
            val hasV2 = bleManager.hasConfigV2Support()
            _lightingState.value = _lightingState.value.copy(
                globalColor = color,
                ledColors = List(6) { color },
                isPendingSync = hasV2,
                isHardwareConfirmed = if (hasV2) _lightingState.value.isHardwareConfirmed else false
            )
            _hasUnsavedChanges.value = true
            if (hasV2) {
                val seq = nextSeq()
                bleManager.enqueueConfigCommand(PulseShiftBleProtocol.buildSetGlobalRgbV2(seq, color))
            }
        }
    }

    override suspend fun setIndividualLedColor(index: Int, color: RgbColor) {
        transactionMutex.withLock {
            if (index in 0..5) {
                draftLedColors[index] = color
                val updated = _lightingState.value.ledColors.toMutableList()
                updated[index] = color
                val hasV2 = bleManager.hasConfigV2Support()
                _lightingState.value = _lightingState.value.copy(
                    ledColors = updated,
                    isPendingSync = hasV2,
                    isHardwareConfirmed = if (hasV2) _lightingState.value.isHardwareConfirmed else false
                )
                _hasUnsavedChanges.value = true
                if (hasV2) {
                    val seq = nextSeq()
                    bleManager.enqueueConfigCommand(PulseShiftBleProtocol.buildSetLedRgbV2(seq, index, color))
                }
            }
        }
    }

    override suspend fun setAllLedColors(colors: List<RgbColor>) {
        transactionMutex.withLock {
            if (colors.size == 6) {
                draftLedColors = colors.toMutableList()
                _lightingState.value = _lightingState.value.copy(ledColors = colors, isPendingSync = true)
                _hasUnsavedChanges.value = true
                for (i in 0 until 6) {
                    val seq = nextSeq()
                    if (bleManager.hasConfigV2Support()) {
                        bleManager.enqueueConfigCommand(PulseShiftBleProtocol.buildSetLedRgbV2(seq, i, colors[i]))
                    } else {
                        bleManager.enqueueLightingCommand(PulseShiftBleProtocol.buildSetLedColor(i, colors[i], seq.toByte()))
                    }
                }
            }
        }
    }

    override suspend fun setStartupAnimation(animation: StartupAnimation, durationMs: Int) {
        transactionMutex.withLock {
            draftStartupAnimation = animation
            draftStartupDurationMs = durationMs
            _hasUnsavedChanges.value = true
            val seq = nextSeq()
            if (bleManager.hasConfigV2Support()) {
                bleManager.enqueueConfigCommand(PulseShiftBleProtocol.buildSetStartupV2(seq, animation.id, durationMs))
            } else {
                bleManager.enqueueLightingCommand(PulseShiftBleProtocol.buildSetStartupConfig(animation.id, (durationMs / 1000).coerceIn(1, 10), true, seq.toByte()))
            }
        }
    }

    override suspend fun setLedDirection(direction: Int) {
        transactionMutex.withLock {
            draftLedDirection = direction
            _hasUnsavedChanges.value = true
            val seq = nextSeq()
            if (bleManager.hasConfigV2Support()) {
                bleManager.enqueueConfigCommand(PulseShiftBleProtocol.buildSetLedDirectionV2(seq, direction))
            }
        }
    }

    override suspend fun setWarningRgb(color: RgbColor) {
        transactionMutex.withLock {
            draftWarningColor = color
            _hasUnsavedChanges.value = true
            val seq = nextSeq()
            if (bleManager.hasConfigV2Support()) {
                bleManager.enqueueConfigCommand(PulseShiftBleProtocol.buildSetWarningRgbV2(seq, color))
            }
        }
    }

    override suspend fun updateShiftLightConfig(config: ShiftLightConfig) {
        transactionMutex.withLock {
            draftThresholds = config.thresholds.toMutableList()
            draftWarningRpm = config.warningRpm
            draftWarningStyleId = config.warningStyle.id
            config.colors.getOrNull(5)?.let { draftWarningColor = it }

            _shiftLightConfig.value = config
            _hasUnsavedChanges.value = true
            if (bleManager.hasConfigV2Support()) {
                for (i in 0 until 6) {
                    val seq = nextSeq()
                    bleManager.enqueueConfigCommand(PulseShiftBleProtocol.buildSetLedThresholdV2(seq, i, config.thresholds[i]))
                }
                val seqWarn = nextSeq()
                bleManager.enqueueConfigCommand(PulseShiftBleProtocol.buildSetShiftWarningV2(seqWarn, config.warningRpm, config.warningStyle.id))
            } else {
                val seq1 = nextSeq().toByte()
                bleManager.enqueueLightingCommand(PulseShiftBleProtocol.buildSetRpmThresholds(config.thresholds, seq1))
                val seq2 = nextSeq().toByte()
                bleManager.enqueueLightingCommand(PulseShiftBleProtocol.buildSetShiftWarning(config.warningRpm, config.warningStyle, seq2))
            }
        }
    }

    override suspend fun applyDecoderConfiguration(canId: Long, byteIndex: Int, isExtended: Boolean, multiplier: Float) {
        transactionMutex.withLock {
            draftCanCandidateId = canId
            draftCandidateByteIndex = byteIndex
            draftIsExtendedFrame = isExtended
            draftRpmMultiplier = multiplier

            if (!bleManager.hasConfigV2Support()) return@withLock

            val seqTx = nextSeq()
            val ackBegin = sendCommandAndWaitAck(
                PulseShiftBleProtocol.buildBeginTransactionV2(seqTx),
                sequence = seqTx,
                opcode = PulseShiftBleProtocol.V2_OPCODE_BEGIN_TRANSACTION
            )
            if (ackBegin == null || !ackBegin.isSuccess) {
                Log.e("PulseShiftRepo", "BEGIN_TRANSACTION failed for applyDecoderConfiguration")
                return@withLock
            }

            var transactionFailed = false
            val stagedCmds = mutableListOf<Triple<Int, Byte, ByteArray>>()

            val seqC = nextSeq()
            stagedCmds.add(Triple(seqC, PulseShiftBleProtocol.V2_OPCODE_SET_CANDIDATE_SOURCE, PulseShiftBleProtocol.buildSetCandidateSourceV2(seqC, canId, byteIndex, isExtended, isStaged = true)))

            val seqM = nextSeq()
            stagedCmds.add(Triple(seqM, PulseShiftBleProtocol.V2_OPCODE_SET_RPM_MULTIPLIER, PulseShiftBleProtocol.buildSetRpmMultiplierV2(seqM, multiplier, isStaged = true)))

            for ((seq, op, cmd) in stagedCmds) {
                val ack = sendCommandAndWaitAck(cmd, sequence = seq, opcode = op)
                if (ack == null || !ack.isSuccess || !ack.isStagedOnly) {
                    Log.e("PulseShiftRepo", "Staged decoder command op 0x${op.toString(16)} failed")
                    transactionFailed = true
                    break
                }
            }

            if (transactionFailed) {
                val seqAbort = nextSeq()
                sendCommandAndWaitAck(
                    PulseShiftBleProtocol.buildAbortTransactionV2(seqAbort),
                    sequence = seqAbort,
                    opcode = PulseShiftBleProtocol.V2_OPCODE_ABORT_TRANSACTION
                )
                return@withLock
            }

            val seqCommit = nextSeq()
            val ackCommit = sendCommandAndWaitAck(
                PulseShiftBleProtocol.buildCommitTransactionV2(seqCommit),
                sequence = seqCommit,
                opcode = PulseShiftBleProtocol.V2_OPCODE_COMMIT_TRANSACTION
            )

            if (ackCommit == null || !ackCommit.isSuccess || !ackCommit.isApplied) {
                Log.e("PulseShiftRepo", "COMMIT_TRANSACTION failed for applyDecoderConfiguration")
                return@withLock
            }

            val seqSnap = nextSeq()
            val snapshot = requestAndWaitSnapshot(seqSnap)

            if (snapshot != null && snapshot.isValid && snapshot.activeRevision == ackCommit.activeRevision &&
                snapshot.canCandidateId == canId && snapshot.candidateByteIndex == byteIndex &&
                snapshot.isExtendedFrame == isExtended && abs(snapshot.rpmMultiplier - multiplier) < 0.05f) {
                _hasUnsavedChanges.value = true
                Log.d("PulseShiftRepo", "Decoder configuration applied successfully.")
            } else {
                Log.e("PulseShiftRepo", "Snapshot validation failed after applying decoder configuration.")
            }
        }
    }

    override suspend fun setRpmHysteresis(hysteresisRpm: Int) {
        transactionMutex.withLock {
            if (!bleManager.hasConfigV2Support()) return@withLock
            if (!requireAuthenticated()) return@withLock
            val seq = nextSeq()
            val ack = sendCommandAndWaitAck(
                PulseShiftBleProtocol.buildSetRpmHysteresisV2(seq, hysteresisRpm),
                sequence = seq,
                opcode = PulseShiftBleProtocol.V2_OPCODE_SET_RPM_HYSTERESIS
            )
            if (ack == null || !ack.isSuccess) {
                Log.e("PulseShiftRepo", "SET_RPM_HYSTERESIS failed: status=${ack?.statusCode}")
            }
        }
    }

    override suspend fun applyPreset(preset: Preset) {
        transactionMutex.withLock {
            draftBrightness = preset.brightness
            draftEffect = preset.effect
            draftEffectSpeed = preset.effectSpeed
            draftLedColors = preset.ledColors.toMutableList()
            preset.shiftLightConfig?.let { cfg ->
                draftThresholds = cfg.thresholds.toMutableList()
                draftWarningRpm = cfg.warningRpm
                draftWarningStyleId = cfg.warningStyle.id
                cfg.colors.getOrNull(5)?.let { draftWarningColor = it }
            }

            _lightingState.value = _lightingState.value.copy(
                effect = preset.effect,
                brightness = preset.brightness,
                effectSpeed = preset.effectSpeed,
                ledColors = preset.ledColors,
                globalColor = preset.ledColors.firstOrNull() ?: RgbColor.BrandViolet,
                isPendingSync = true
            )

            if (!bleManager.hasConfigV2Support()) {
                _lightingState.value = _lightingState.value.copy(isPendingSync = false, isHardwareConfirmed = false)
                return
            }

            // 1. Begin Staged Transaction
            val seqTx = nextSeq()
            val ackBegin = sendCommandAndWaitAck(
                PulseShiftBleProtocol.buildBeginTransactionV2(seqTx),
                sequence = seqTx,
                opcode = PulseShiftBleProtocol.V2_OPCODE_BEGIN_TRANSACTION
            )

            if (ackBegin == null || !ackBegin.isSuccess) {
                Log.e("PulseShiftRepo", "BEGIN_TRANSACTION failed")
                _lightingState.value = _lightingState.value.copy(isPendingSync = false, isHardwareConfirmed = false)
                return
            }

            var transactionFailed = false
            val stagedCmds = mutableListOf<Triple<Int, Byte, ByteArray>>()

            val seqB = nextSeq()
            stagedCmds.add(Triple(seqB, PulseShiftBleProtocol.V2_OPCODE_SET_BRIGHTNESS, PulseShiftBleProtocol.buildSetBrightnessV2(seqB, preset.brightness, isStaged = true)))

            val seqE = nextSeq()
            stagedCmds.add(Triple(seqE, PulseShiftBleProtocol.V2_OPCODE_SET_EFFECT, PulseShiftBleProtocol.buildSetEffectV2(seqE, preset.effect.id, preset.effectSpeed, isStaged = true)))

            for (i in 0 until 6) {
                val color = preset.ledColors.getOrElse(i) { RgbColor.BrandViolet }
                val seqC = nextSeq()
                stagedCmds.add(Triple(seqC, PulseShiftBleProtocol.V2_OPCODE_SET_LED_RGB, PulseShiftBleProtocol.buildSetLedRgbV2(seqC, i, color, isStaged = true)))
            }

            preset.shiftLightConfig?.let { cfg ->
                for (i in 0 until 6) {
                    val seqT = nextSeq()
                    stagedCmds.add(Triple(seqT, PulseShiftBleProtocol.V2_OPCODE_SET_LED_THRESHOLD, PulseShiftBleProtocol.buildSetLedThresholdV2(seqT, i, cfg.thresholds[i], isStaged = true)))
                }
                val seqW = nextSeq()
                stagedCmds.add(Triple(seqW, PulseShiftBleProtocol.V2_OPCODE_SET_SHIFT_WARNING, PulseShiftBleProtocol.buildSetShiftWarningV2(seqW, cfg.warningRpm, cfg.warningStyle.id, isStaged = true)))
            }

            // 2. Issue Staged Writes and Verify Each ACK (must be OK AND isStagedOnly)
            for ((seq, op, cmd) in stagedCmds) {
                val ack = sendCommandAndWaitAck(cmd, sequence = seq, opcode = op)
                if (ack == null || !ack.isSuccess || !ack.isStagedOnly) {
                    Log.e("PulseShiftRepo", "Staged command op 0x${op.toString(16)} seq $seq failed or not staged: status=${ack?.statusCode}")
                    transactionFailed = true
                    break
                }
            }

            if (transactionFailed) {
                val seqAbort = nextSeq()
                sendCommandAndWaitAck(
                    PulseShiftBleProtocol.buildAbortTransactionV2(seqAbort),
                    sequence = seqAbort,
                    opcode = PulseShiftBleProtocol.V2_OPCODE_ABORT_TRANSACTION
                )
                _lightingState.value = _lightingState.value.copy(isPendingSync = false, isHardwareConfirmed = false)
                return
            }

            // 3. Commit Transaction
            val seqCommit = nextSeq()
            val ackCommit = sendCommandAndWaitAck(
                PulseShiftBleProtocol.buildCommitTransactionV2(seqCommit),
                sequence = seqCommit,
                opcode = PulseShiftBleProtocol.V2_OPCODE_COMMIT_TRANSACTION
            )

            if (ackCommit == null || !ackCommit.isSuccess || !ackCommit.isApplied) {
                Log.e("PulseShiftRepo", "COMMIT_TRANSACTION failed")
                _lightingState.value = _lightingState.value.copy(isPendingSync = false, isHardwareConfirmed = false)
                return
            }

            // 4. Validate Snapshot Readback against ALL fields modified by preset
            val seqSnap = nextSeq()
            val snapshot = requestAndWaitSnapshot(seqSnap)

            if (snapshot != null && snapshot.isValid && snapshot.activeRevision == ackCommit.activeRevision &&
                snapshot.brightness == preset.brightness &&
                snapshot.effect == preset.effect &&
                snapshot.effectSpeed == preset.effectSpeed &&
                snapshot.ledColors == preset.ledColors) {
                _lightingState.value = _lightingState.value.copy(
                    isPendingSync = false,
                    isHardwareConfirmed = true
                )
                _hasUnsavedChanges.value = true
            } else {
                Log.e("PulseShiftRepo", "Snapshot readback validation failed after COMMIT_TRANSACTION")
                _lightingState.value = _lightingState.value.copy(isPendingSync = false, isHardwareConfirmed = false)
            }
        }
    }

    override suspend fun savePreset(preset: Preset) {
        val list = _presets.value.toMutableList()
        val index = list.indexOfFirst { it.id == preset.id }
        if (index >= 0) {
            list[index] = preset
        } else {
            list.add(preset.copy(id = UUID.randomUUID().toString(), isCustom = true))
        }
        _presets.value = list
        persistCustomPresets(list)
    }

    override suspend fun deletePreset(id: String) {
        val list = _presets.value.toMutableList()
        list.removeAll { it.id == id && it.isCustom }
        _presets.value = list
        persistCustomPresets(list)
    }

    override suspend fun restoreDefaultPresets() {
        _presets.value = Preset.DefaultPresets
        persistCustomPresets(emptyList())
    }

    private suspend fun persistCustomPresets(all: List<Preset>) {
        try {
            dataStore.setCustomPresetsJson(serializeCustomPresets(all.filter { it.isCustom }))
        } catch (e: Exception) {
            Log.w("PulseShiftRepo", "Failed to persist custom presets: ${e.message}")
        }
    }

    private fun ConfigSnapshot.matchesFullConfig(
        expectedPower: Boolean,
        expectedBrightness: Int,
        expectedEffect: EffectType,
        expectedSpeed: Int,
        expectedLedColors: List<RgbColor>,
        expectedDirection: Int,
        expectedStartupAnim: StartupAnimation,
        expectedStartupDurationMs: Int,
        expectedThresholds: List<Int>,
        expectedWarningRpm: Int,
        expectedWarningStyleId: Int,
        expectedWarningColor: RgbColor,
        expectedMultiplier: Float,
        expectedCanId: Long,
        expectedByteIndex: Int,
        expectedIsExtended: Boolean
    ): Boolean {
        if (isPowerOn != expectedPower) return false
        if (brightness != expectedBrightness) return false
        if (effect != expectedEffect) return false
        if (effectSpeed != expectedSpeed) return false
        if (ledDirection != expectedDirection) return false
        if (startupAnimation != expectedStartupAnim) return false
        if (startupDurationMs != expectedStartupDurationMs) return false
        if (warningRpm != expectedWarningRpm) return false
        if (warningPatternId != expectedWarningStyleId) return false
        if (warningColor != expectedWarningColor) return false
        if (abs(rpmMultiplier - expectedMultiplier) > 0.05f) return false
        if (canCandidateId != expectedCanId) return false
        if (candidateByteIndex != expectedByteIndex) return false
        if (isExtendedFrame != expectedIsExtended) return false

        if (ledColors.size != 6 || expectedLedColors.size != 6) return false
        for (i in 0 until 6) {
            if (ledColors[i] != expectedLedColors[i]) return false
        }

        if (rpmThresholds.size != 6 || expectedThresholds.size != 6) return false
        for (i in 0 until 6) {
            if (rpmThresholds[i] != expectedThresholds[i]) return false
        }

        return true
    }

    override suspend fun saveConfigurationToController(): Boolean {
        return transactionMutex.withLock {
            if (!bleManager.hasConfigV2Support()) {
                Log.w("PulseShiftRepo", "Cannot save config: V2 configuration protocol not supported by firmware")
                return@withLock false
            }

            val seqSave = nextSeq()
            val ackSave = sendCommandAndWaitAck(
                PulseShiftBleProtocol.buildSaveConfigV2(seqSave),
                sequence = seqSave,
                opcode = PulseShiftBleProtocol.V2_OPCODE_SAVE_CONFIG
            )

            if (ackSave == null || !ackSave.isSuccess || !ackSave.isPersisted) {
                Log.e("PulseShiftRepo", "SAVE_CONFIG failed: status=${ackSave?.statusCode}, persisted=${ackSave?.isPersisted}")
                return@withLock false
            }

            val seqSnap = nextSeq()
            val snapshot = requestAndWaitSnapshot(seqSnap)

            if (snapshot != null && snapshot.isValid &&
                snapshot.activeRevision == ackSave.activeRevision &&
                snapshot.matchesFullConfig(
                    expectedPower = draftPower,
                    expectedBrightness = draftBrightness,
                    expectedEffect = draftEffect,
                    expectedSpeed = draftEffectSpeed,
                    expectedLedColors = draftLedColors,
                    expectedDirection = draftLedDirection,
                    expectedStartupAnim = draftStartupAnimation,
                    expectedStartupDurationMs = draftStartupDurationMs,
                    expectedThresholds = draftThresholds,
                    expectedWarningRpm = draftWarningRpm,
                    expectedWarningStyleId = draftWarningStyleId,
                    expectedWarningColor = draftWarningColor,
                    expectedMultiplier = draftRpmMultiplier,
                    expectedCanId = draftCanCandidateId,
                    expectedByteIndex = draftCandidateByteIndex,
                    expectedIsExtended = draftIsExtendedFrame
                )) {
                _hasUnsavedChanges.value = false
                _lightingState.value = _lightingState.value.copy(
                    isHardwareConfirmed = true,
                    isPendingSync = false
                )
                true
            } else {
                Log.e("PulseShiftRepo", "Snapshot validation or setting comparison failed after SAVE_CONFIG")
                false
            }
        }
    }

    override suspend fun reloadConfigFromController() {
        transactionMutex.withLock {
            if (!bleManager.hasConfigV2Support()) return@withLock
            val seq = nextSeq()
            requestAndWaitSnapshot(seq)
        }
    }

    override suspend fun restoreDefaultControllerConfig() {
        transactionMutex.withLock {
            if (!bleManager.hasConfigV2Support()) {
                Log.w("PulseShiftRepo", "Cannot restore defaults: V2 configuration protocol not supported by firmware")
                return@withLock
            }

            val seqRestore = nextSeq()
            val ackRestore = sendCommandAndWaitAck(
                PulseShiftBleProtocol.buildRestoreDefaultsV2(seqRestore),
                sequence = seqRestore,
                opcode = PulseShiftBleProtocol.V2_OPCODE_RESTORE_DEFAULTS
            )

            if (ackRestore != null && ackRestore.isSuccess) {
                val seqSnap = nextSeq()
                val snapshot = requestAndWaitSnapshot(seqSnap)
                if (snapshot != null && snapshot.isValid) {
                    _hasUnsavedChanges.value = false
                }
            } else {
                Log.e("PulseShiftRepo", "RESTORE_DEFAULTS failed: status=${ackRestore?.statusCode}")
            }
        }
    }

    override suspend fun requestHistoricalLogPage(startOffset: Long) {
        _historicalPageLoadingState.value = HistoricalLogLoadingState.Loading(startOffset)
        bleManager.requestHistoricalLogPage(startOffset)
    }

    override suspend fun cancelHistoricalLogRetrieval() {
        _historicalPageLoadingState.value = HistoricalLogLoadingState.Idle
        bleManager.cancelHistoricalLogRetrieval()
    }

    override suspend fun clearDiagnosticLogs() {
        _diagnosticLogMessages.value = emptyList()
        bleManager.logReassembler.clear()
    }

    // ========================================================================
    // Device command surface (Diagnostics / Advanced Commands)
    // ========================================================================

    private fun recordDeviceCommand(label: String, phase: DeviceCommandPhase, detail: String = "") {
        val outcome = DeviceCommandOutcome(
            id = outcomeIdCounter.getAndIncrement(),
            label = label,
            phase = phase,
            detail = detail
        )
        val current = _deviceCommandHistory.value.toMutableList()
        current.add(0, outcome)
        if (current.size > 50) current.removeAt(current.size - 1)
        _deviceCommandHistory.value = current
    }

    private fun ackFailureDetail(ack: ConfigAckResponse?): String {
        if (ack == null) return "No acknowledgement received (timeout)"
        return when (ack.statusCode) {
            PulseShiftBleProtocol.STATUS_ERR_INVALID_PARAM -> "Rejected: invalid parameters"
            PulseShiftBleProtocol.STATUS_ERR_UNSUPPORTED -> "Rejected: unsupported by firmware"
            PulseShiftBleProtocol.STATUS_ERR_BUSY -> "Rejected: device busy"
            PulseShiftBleProtocol.STATUS_ERR_UNAUTHORIZED -> "Rejected: authentication required (press BOOT to authorize)"
            PulseShiftBleProtocol.STATUS_ERR_INVALID_STATE -> "Rejected: invalid device state"
            PulseShiftBleProtocol.STATUS_ERR_STORAGE_ERROR -> "Rejected: storage error"
            PulseShiftBleProtocol.STATUS_ERR_VERSION_MISMATCH -> "Rejected: protocol version mismatch"
            PulseShiftBleProtocol.STATUS_ERR_VALIDATION_FAILED -> "Rejected: configuration validation failed"
            else -> "Failed (status ${ack.statusCode})"
        }
    }

    private suspend fun requireAuthenticated(): Boolean {
        if (!bleManager.hasConfigV2Support()) {
            recordDeviceCommand("Device command", DeviceCommandPhase.FAILED, "Config V2 protocol unavailable")
            return false
        }
        if (!_isAuthenticated.value) {
            val ok = authenticateChallengeResponse()
            if (!ok) {
                recordDeviceCommand("Device command", DeviceCommandPhase.FAILED,
                    "Owner session required. Press BOOT on the ESP32 and re-enroll if credentials were lost.")
                return false
            }
        }
        return true
    }

    /** Request a fresh diagnostics TLV report from the device (read-only). */
    override suspend fun requestDiagnosticsReport(): DiagnosticsReport? {
        if (!bleManager.hasConfigV2Support()) return null
        recordDeviceCommand("Get device status", DeviceCommandPhase.SENDING)
        val seq = nextSeq()
        val deferred = CompletableDeferred<DiagnosticsReport>()
        diagnosticsWaiters[seq] = deferred
        bleManager.enqueueConfigCommand(PulseShiftBleProtocol.buildGetDiagnosticsV2(seq))
        val report = try {
            withTimeout(5000L) { deferred.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            diagnosticsWaiters.remove(seq)
        }
        if (report != null) {
            _diagnosticsReport.value = report
            recordDeviceCommand("Get device status", DeviceCommandPhase.ACCEPTED,
                "Firmware ${report.firmwareVersion}, uptime ${report.uptimeMs / 1000}s")
        } else {
            recordDeviceCommand("Get device status", DeviceCommandPhase.FAILED, "No diagnostics blob received")
        }
        return report
    }

    /** Re-read the persisted NVS configuration on the device and apply it. */
    override suspend fun reloadConfigurationFromDevice(): Boolean {
        if (!requireAuthenticated()) return false
        recordDeviceCommand("Reload configuration", DeviceCommandPhase.SENDING)
        val seq = nextSeq()
        val ack = sendCommandAndWaitAck(
            PulseShiftBleProtocol.buildReloadConfigV2(seq),
            sequence = seq,
            opcode = PulseShiftBleProtocol.V2_OPCODE_RELOAD_CONFIG
        )
        return if (ack != null && ack.isSuccess) {
            recordDeviceCommand("Reload configuration", DeviceCommandPhase.APPLIED)
            val seqSnap = nextSeq()
            requestAndWaitSnapshot(seqSnap)
            true
        } else {
            recordDeviceCommand("Reload configuration", DeviceCommandPhase.FAILED, ackFailureDetail(ack))
            false
        }
    }

    /** Restart the ESP32 after explicit confirmation (device self-resets). */
    override suspend fun restartDevice(): Boolean {
        if (!requireAuthenticated()) return false
        recordDeviceCommand("Restart ESP32", DeviceCommandPhase.SENDING)
        val seq = nextSeq()
        val ack = sendCommandAndWaitAck(
            PulseShiftBleProtocol.buildRestartDeviceV2(seq),
            sequence = seq,
            opcode = PulseShiftBleProtocol.V2_OPCODE_RESTART_DEVICE
        )
        return if (ack != null && ack.isSuccess) {
            recordDeviceCommand("Restart ESP32", DeviceCommandPhase.ACCEPTED, "Device is rebooting")
            true
        } else {
            recordDeviceCommand("Restart ESP32", DeviceCommandPhase.FAILED, ackFailureDetail(ack))
            false
        }
    }

    /**
     * Clear nonessential diagnostic history on the device after explicit
     * confirmation. Preserved CAN captures and owner credentials are never
     * touched by this command.
     */
    override suspend fun clearDeviceDiagnosticHistory(): Boolean {
        if (!requireAuthenticated()) return false
        recordDeviceCommand("Clear diagnostic history", DeviceCommandPhase.SENDING)
        val seq = nextSeq()
        val ack = sendCommandAndWaitAck(
            PulseShiftBleProtocol.buildClearDiagnosticsV2(seq),
            sequence = seq,
            opcode = PulseShiftBleProtocol.V2_OPCODE_CLEAR_DIAGNOSTICS
        )
        return if (ack != null && ack.isSuccess) {
            recordDeviceCommand("Clear diagnostic history", DeviceCommandPhase.APPLIED, "Event log cleared (captures preserved)")
            true
        } else {
            recordDeviceCommand("Clear diagnostic history", DeviceCommandPhase.FAILED, ackFailureDetail(ack))
            false
        }
    }

    /** Start or stop flash CAN recording (capture subsystem, runtime toggle). */
    override suspend fun setCaptureRecording(enable: Boolean): Boolean {
        if (!requireAuthenticated()) return false
        recordDeviceCommand(if (enable) "Start recording" else "Stop recording", DeviceCommandPhase.SENDING)
        val seq = nextSeq()
        val ack = sendCommandAndWaitAck(
            PulseShiftBleProtocol.buildSetCaptureRecordingV2(seq, enable),
            sequence = seq,
            opcode = PulseShiftBleProtocol.V2_OPCODE_SET_CAPTURE_RECORDING
        )
        return if (ack != null && ack.isSuccess && ack.isApplied) {
            recordDeviceCommand(if (enable) "Start recording" else "Stop recording", DeviceCommandPhase.APPLIED)
            true
        } else {
            recordDeviceCommand(if (enable) "Start recording" else "Stop recording", DeviceCommandPhase.FAILED, ackFailureDetail(ack))
            false
        }
    }

    /** Begin a bounded, cancellable capture export (12-record pages over BLE). */
    override fun startCaptureExport() {
        val state = _captureExportState.value
        if (state is CaptureExportState.Exporting && !state.isFinished) return
        recordDeviceCommand("Export CAN capture", DeviceCommandPhase.SENDING, "Streaming pages...")
        _captureExportState.value = CaptureExportState.Exporting()
        bleManager.requestHistoricalCapturePage(0)
    }

    override fun cancelCaptureExport() {
        captureAutoExportJob?.cancel()
        bleManager.cancelCaptureRetrieval()
        _captureExportState.value = CaptureExportState.Idle
        recordDeviceCommand("Export CAN capture", DeviceCommandPhase.FAILED, "Cancelled by user")
    }

    // --- Mobile-assisted calibration workflow (firmware-driven) ---

    /** Query the firmware calibration session state. */
    override suspend fun queryCalibrationStatus(): CalibrationSessionStatus? {
        if (!bleManager.hasConfigV2Support()) return null
        val seq = nextSeq()
        val ack = sendCommandAndWaitAck(
            PulseShiftBleProtocol.buildCalStatusV2(seq),
            sequence = seq,
            opcode = PulseShiftBleProtocol.V2_OPCODE_CAL_STATUS
        )
        if (ack == null || !ack.isSuccess || ack.payload.size < 5) return null
        return CalibrationSessionStatus(
            sessionActive = ack.payload[0].toInt() != 0,
            pointCount = ack.payload[1].toInt() and 0xFF,
            captureActive = ack.payload[2].toInt() != 0,
            captureSamples = (ack.payload[3].toInt() and 0xFF) or ((ack.payload[4].toInt() and 0xFF) shl 8)
        )
    }

    /** Start the firmware calibration session (must already be authenticated). */
    override suspend fun beginCalibrationSession(): Boolean {
        if (!requireAuthenticated()) return false
        recordDeviceCommand("Calibration session", DeviceCommandPhase.SENDING, "Starting...")
        val seq = nextSeq()
        val ack = sendCommandAndWaitAck(
            PulseShiftBleProtocol.buildCalBeginV2(seq),
            sequence = seq,
            opcode = PulseShiftBleProtocol.V2_OPCODE_CAL_BEGIN
        )
        return if (ack != null && ack.isSuccess) {
            recordDeviceCommand("Calibration session", DeviceCommandPhase.ACCEPTED, "Capture a point at idle, mid and high RPM")
            true
        } else {
            recordDeviceCommand("Calibration session", DeviceCommandPhase.FAILED, ackFailureDetail(ack))
            false
        }
    }

    /** Open the 2 s raw sampling window at the current operating point. */
    override suspend fun startCalibrationCapture(): Boolean {
        val seq = nextSeq()
        val ack = sendCommandAndWaitAck(
            PulseShiftBleProtocol.buildCalCaptureStartV2(seq),
            sequence = seq,
            opcode = PulseShiftBleProtocol.V2_OPCODE_CAL_CAPTURE_START
        )
        return ack != null && ack.isSuccess
    }

    /** Close the sampling window and store the operating point with its reference RPM. */
    override suspend fun commitCalibrationPoint(refRpm: Int): Boolean {
        val seq = nextSeq()
        val ack = sendCommandAndWaitAck(
            PulseShiftBleProtocol.buildCalPointCommitV2(seq, refRpm),
            sequence = seq,
            opcode = PulseShiftBleProtocol.V2_OPCODE_CAL_POINT_COMMIT
        )
        if (ack != null && ack.isSuccess) {
            recordDeviceCommand("Calibration point @ $refRpm RPM", DeviceCommandPhase.ACCEPTED)
            return true
        }
        recordDeviceCommand("Calibration point @ $refRpm RPM", DeviceCommandPhase.FAILED, ackFailureDetail(ack))
        return false
    }

    /** Fit scale/offset over the collected points and optionally bind verification. */
    override suspend fun computeCalibration(apply: Boolean): CalibrationFitResult? {
        val seq = nextSeq()
        val ack = sendCommandAndWaitAck(
            PulseShiftBleProtocol.buildCalComputeV2(seq, apply),
            sequence = seq,
            opcode = PulseShiftBleProtocol.V2_OPCODE_CAL_COMPUTE
        )
        if (ack == null) return null
        if (!ack.isSuccess) {
            val reason = if (ack.payload.isNotEmpty()) String(ack.payload, Charsets.UTF_8) else ackFailureDetail(ack)
            recordDeviceCommand("Calibration fit", DeviceCommandPhase.FAILED, reason)
            return CalibrationFitResult(success = false, reason = reason)
        }
        val result = if (ack.payload.size >= 9) {
            val multX1000 = (ack.payload[0].toInt() and 0xFF) or
                    ((ack.payload[1].toInt() and 0xFF) shl 8) or
                    ((ack.payload[2].toInt() and 0xFF) shl 16) or
                    ((ack.payload[3].toInt() and 0xFF) shl 24)
            val offX1000 = (ack.payload[4].toInt() and 0xFF) or
                    ((ack.payload[5].toInt() and 0xFF) shl 8) or
                    ((ack.payload[6].toInt() and 0xFF) shl 16) or
                    ((ack.payload[7].toInt() and 0xFF) shl 24)
            val info = ack.payload[8].toInt() and 0xFF
            val points = info and 0x0F
            val residual = ((info shr 4) and 0x0F) * 10 // 10-RPM steps, 0..150
            CalibrationFitResult(
                success = true,
                multiplier = multX1000 / 1000f,
                offset = offX1000 / 1000f,
                maxResidualRpm = residual,
                pointsUsed = points,
                reason = if (apply) "Verified and bound" else "Fit preview (not applied)"
            )
        } else {
            CalibrationFitResult(success = true, reason = "Applied")
        }
        if (apply && result.success) {
            recordDeviceCommand("Calibration fit", DeviceCommandPhase.SAVED,
                "Multiplier ${result.multiplier}, residual ${result.maxResidualRpm} RPM")
            val seqSnap = nextSeq()
            requestAndWaitSnapshot(seqSnap)
        }
        return result
    }

    /** Abort the firmware calibration session. */
    override suspend fun abortCalibrationSession(): Boolean {
        val seq = nextSeq()
        val ack = sendCommandAndWaitAck(
            PulseShiftBleProtocol.buildCalAbortV2(seq),
            sequence = seq,
            opcode = PulseShiftBleProtocol.V2_OPCODE_CAL_ABORT
        )
        return ack != null && ack.isSuccess
    }

    // --- Preset persistence (custom presets survive app restarts) ---

    private fun parseCustomPresets(json: String): List<Preset>? {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            val list = mutableListOf<Preset>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val colors = mutableListOf<RgbColor>()
                val colorArr = obj.getJSONArray("ledColors")
                for (c in 0 until colorArr.length()) {
                    colors.add(RgbColor.fromHex(colorArr.getString(c)))
                }
                val shift = if (obj.has("shiftLightConfig")) {
                    val s = obj.getJSONObject("shiftLightConfig")
                    val th = mutableListOf<Int>()
                    val thArr = s.getJSONArray("thresholds")
                    for (t in 0 until thArr.length()) th.add(thArr.getInt(t))
                    ShiftLightConfig(
                        thresholds = th,
                        warningRpm = s.getInt("warningRpm"),
                        warningStyle = when (s.getInt("warningStyleId")) {
                            1 -> WarningStyle.STROBE
                            2 -> WarningStyle.SOLID
                            else -> WarningStyle.FLASH
                        }
                    )
                } else null
                list.add(
                    Preset(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        effect = EffectType.entries.firstOrNull { it.id == obj.getInt("effectId") } ?: EffectType.STATIC,
                        ledColors = colors,
                        brightness = obj.getInt("brightness"),
                        effectSpeed = obj.getInt("effectSpeed"),
                        isCustom = true,
                        shiftLightConfig = shift
                    )
                )
            }
            list
        } catch (e: Exception) {
            Log.w("PulseShiftRepo", "Failed to parse stored custom presets: ${e.message}")
            null
        }
    }

    private fun serializeCustomPresets(custom: List<Preset>): String {
        return try {
            val arr = org.json.JSONArray()
            for (p in custom) {
                val obj = org.json.JSONObject()
                obj.put("id", p.id)
                obj.put("name", p.name)
                obj.put("effectId", p.effect.id)
                obj.put("brightness", p.brightness)
                obj.put("effectSpeed", p.effectSpeed)
                val colorArr = org.json.JSONArray()
                for (c in p.ledColors) colorArr.put(c.toHex())
                obj.put("ledColors", colorArr)
                p.shiftLightConfig?.let { cfg ->
                    val s = org.json.JSONObject()
                    val th = org.json.JSONArray()
                    for (t in cfg.thresholds) th.put(t)
                    s.put("thresholds", th)
                    s.put("warningRpm", cfg.warningRpm)
                    s.put("warningStyleId", cfg.warningStyle.id)
                    obj.put("shiftLightConfig", s)
                }
                arr.put(obj)
            }
            arr.toString()
        } catch (e: Exception) {
            ""
        }
    }

    companion object {
        private const val MAX_CAPTURE_EXPORT_RECORDS = 2000
    }

    override fun exportLogsAsText(): String {
        val sb = StringBuilder()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val dev = connectedDevice.value
        val rpm = rpmTelemetryState.value
        val health = canHealthState.value

        sb.appendLine("==========================================")
        sb.appendLine(" PulseShift Diagnostic Log Export")
        sb.appendLine("==========================================")
        sb.appendLine("Export Timestamp: ${dateFormat.format(Date())}")
        sb.appendLine("Protocol Version: ${if (bleManager.hasConfigV2Support()) "BLE Config V2" else "BLE V1"}")
        sb.appendLine("Target Vehicle: 2025 Bajaj Pulsar NS125 ABS")
        sb.appendLine("Device Name: ${dev?.name ?: "Unknown"}")
        sb.appendLine("Device Address: ${dev?.address ?: "N/A"}")
        sb.appendLine("Firmware Version: ${dev?.firmwareVersion ?: "N/A"}")
        if (rpm != null) {
            sb.appendLine("Last Live RPM: ${if (rpm.isRpmAvailable) "${rpm.provisionalRpm} RPM" else "Unavailable"}")
            sb.appendLine("Calibration Verified: ${rpm.statusFlags.isCalibrationVerified}")
            sb.appendLine("CAN Total Frames: ${rpm.totalCanFramesReceived}")
        }
        if (health != null) {
            sb.appendLine("Driver Missed Frames: ${health.driverMissedFrames}")
            sb.appendLine("Hardware FIFO Overruns: ${health.hardwareFifoOverruns}")
            sb.appendLine("CAN Bus Errors: ${health.canBusErrors}")
        }
        val hasGaps = _diagnosticLogMessages.value.any { it.isCorruptOrIncomplete }
        if (hasGaps) {
            sb.appendLine("[WARNING: Potential data gaps or incomplete log chunks detected during capture]")
        }
        sb.appendLine("------------------------------------------")
        sb.appendLine("LOG MESSAGES (${_diagnosticLogMessages.value.size} entries):")
        sb.appendLine("------------------------------------------")

        for (msg in _diagnosticLogMessages.value.reversed()) {
            val timestamp = dateFormat.format(Date(msg.timestampMs))
            val tag = if (msg.isHistorical) "[HISTORICAL]" else "[LIVE]"
            sb.appendLine("[$timestamp] $tag [${msg.severity}] Msg#${msg.messageId}: ${msg.messageText}")
        }

        return sb.toString()
    }

    override fun exportLogsAsCsv(): String {
        val sb = StringBuilder()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        sb.appendLine("Timestamp,Type,Severity,MessageId,MessageText,Incomplete")
        for (msg in _diagnosticLogMessages.value.reversed()) {
            val timestamp = dateFormat.format(Date(msg.timestampMs))
            val type = if (msg.isHistorical) "HISTORICAL" else "LIVE"
            val textEscaped = msg.messageText.replace("\"", "\"\"")
            sb.appendLine("\"$timestamp\",\"$type\",\"${msg.severity}\",${msg.messageId},\"$textEscaped\",${msg.isCorruptOrIncomplete}")
        }
        return sb.toString()
    }
}
