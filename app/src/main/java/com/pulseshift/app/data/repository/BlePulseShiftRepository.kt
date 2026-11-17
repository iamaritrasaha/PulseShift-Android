package com.pulseshift.app.data.repository

import android.util.Log
import com.pulseshift.app.data.ble.CapturePageRead
import com.pulseshift.app.data.ble.BleConnectionState
import com.pulseshift.app.data.ble.PulseShiftBleManager
import com.pulseshift.app.data.ble.PulseShiftBleProtocol
import com.pulseshift.app.data.ble.SignedOtaImage
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
import com.pulseshift.app.domain.model.OtaUpdateState
import com.pulseshift.app.domain.model.Preset
import com.pulseshift.app.domain.model.PresetApplyResult
import com.pulseshift.app.domain.model.ProfileApplyResult
import com.pulseshift.app.domain.model.RgbColor
import com.pulseshift.app.domain.model.RpmTelemetryState
import com.pulseshift.app.domain.model.ShiftLightConfig
import com.pulseshift.app.domain.model.StartupAnimation
import com.pulseshift.app.domain.model.WarningStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

class BlePulseShiftRepository internal constructor(
    private val bleManager: PulseShiftBleManager,
    private val dataStore: PulseShiftDataStore,
    private val credentialManager: OwnerCredentialManager = OwnerCredentialManager(bleManager.context),
    private val validateOtaImage: (ByteArray) -> SignedOtaImage = SignedOtaImage::validate
) : PulseShiftRepository {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val transactionMutex = Mutex()
    private val configConnectionStates = setOf(
        BleConnectionState.CONNECTED_WAITING_FOR_DATA,
        BleConnectionState.RECEIVING_TELEMETRY,
        BleConnectionState.READY
    )
    private val configLinkGeneration = AtomicLong()
    private var connectionSetupJob: Job? = null
    private var lastSetupDeviceAddress: String? = null
    @Volatile private var nvsConfirmedRevision: Long? = null
    @Volatile private var pendingPresetRevision: Long? = null

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

    override val connectionState: StateFlow<BleConnectionState> = bleManager.connectionState
    override val connectionError: StateFlow<String?> = bleManager.connectionError
    override val isReconnecting: StateFlow<Boolean> = bleManager.isReconnecting

    private val _presets = MutableStateFlow<List<Preset>>(Preset.DefaultPresets)
    override val presets: StateFlow<List<Preset>> = _presets.asStateFlow()

    private val _shiftLightConfig = MutableStateFlow(ShiftLightConfig())
    override val shiftLightConfig: StateFlow<ShiftLightConfig> = _shiftLightConfig.asStateFlow()

    private val _configSnapshot = MutableStateFlow<ConfigSnapshot?>(null)
    override val configSnapshot: StateFlow<ConfigSnapshot?> = _configSnapshot.asStateFlow()

    private val _hasUnsavedChanges = MutableStateFlow(false)
    override val hasUnsavedChanges: StateFlow<Boolean> = _hasUnsavedChanges.asStateFlow()

    private val _lastAckResponse = MutableSharedFlow<ConfigAckResponse>(extraBufferCapacity = 16)
    override val lastAckResponse: SharedFlow<ConfigAckResponse> = _lastAckResponse.asSharedFlow()

    /** Failures of live lighting writes, surfaced to the UI honestly. */
    private val _lightingError = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val lightingSyncError: SharedFlow<String> = _lightingError.asSharedFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    override val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private val _isNvsConfirmed = MutableStateFlow(false)
    override val isNvsConfirmed: StateFlow<Boolean> = _isNvsConfirmed.asStateFlow()

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

    private val _otaUpdateState = MutableStateFlow<OtaUpdateState>(OtaUpdateState.Idle)
    private val otaUpdateMutex = Mutex()
    private class ActiveOtaTransfer(val sender: Deferred<String?>) {
        val abortResult = CompletableDeferred<Boolean>()
    }
    private val otaDataTransfer = AtomicReference<ActiveOtaTransfer?>()
    override val otaUpdateState: StateFlow<OtaUpdateState> = _otaUpdateState.asStateFlow()
    private var lastOtaConfirmError: String? = null

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
        currentSeqCounter = if (currentSeqCounter >= 0xFEFF) 1 else currentSeqCounter + 1
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

    private fun applyAuthoritativeSnapshot(snapshot: ConfigSnapshot) {
        updateDraftsFromSnapshot(snapshot)
        if (_isNvsConfirmed.value && nvsConfirmedRevision != null && nvsConfirmedRevision != snapshot.activeRevision) {
            _isNvsConfirmed.value = false
            _hasUnsavedChanges.value = true
        }
        _lightingState.value = LightingState(
            isPowerOn = snapshot.isPowerOn,
            brightness = snapshot.brightness,
            effect = snapshot.effect,
            effectSpeed = snapshot.effectSpeed,
            globalColor = snapshot.globalColor,
            ledColors = snapshot.ledColors,
            isHardwareConfirmed = true,
            isPendingSync = false,
            ledDirection = snapshot.ledDirection,
            startupAnimation = snapshot.startupAnimation,
            startupDurationMs = snapshot.startupDurationMs
        )
        _shiftLightConfig.value = ShiftLightConfig(
            thresholds = snapshot.rpmThresholds,
            warningRpm = snapshot.warningRpm,
            warningStyle = warningStyleFromId(snapshot.warningPatternId),
            colors = listOf(
                RgbColor.NeonGreen,
                RgbColor.NeonGreen,
                RgbColor.AmberWarning,
                RgbColor.AmberWarning,
                RgbColor.SunsetOrange,
                snapshot.warningColor
            )
        )
        _configSnapshot.value = snapshot
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

        return try {
            if (!bleManager.enqueueConfigCommand(commandBytes)) return null
            withTimeout(timeoutMs) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            Log.w("PulseShiftRepo", "Timeout waiting ACK for opcode 0x${opcode.toString(16)} seq $sequence")
            null
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        } catch (e: Exception) {
            Log.w("PulseShiftRepo", "Exception waiting ACK for opcode 0x${opcode.toString(16)} seq $sequence: ${e.message}")
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

        return try {
            if (!bleManager.enqueueConfigCommand(PulseShiftBleProtocol.buildGetSnapshotV2(sequence))) return null
            withTimeout(timeoutMs) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            Log.w("PulseShiftRepo", "Timeout waiting ConfigSnapshot for snapshotId/sequence $sequence")
            null
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        } catch (e: Exception) {
            Log.w("PulseShiftRepo", "Exception waiting ConfigSnapshot for snapshotId/sequence $sequence: ${e.message}")
            null
        } finally {
            snapshotWaiters.remove(sequence)
        }
    }

    init {
        // Collect V2 Configuration ACKs & correlate sequence & opcode waiters
        scope.launch {
            bleManager.configAcksV2.collect { ack ->
                if (ack.isAuthRequired && _isAuthenticated.value) {
                    _isAuthenticated.value = false
                    _authState.value = AuthState.SESSION_EXPIRED
                }
                if (ack.opcode == PulseShiftBleProtocol.V2_OPCODE_GET_SNAPSHOT && !ack.isSuccess) {
                    snapshotWaiters.remove(ack.sequenceRef)
                        ?.completeExceptionally(IllegalStateException(ackFailureDetail(ack)))
                }
                val key = AckKey(ack.sequenceRef, ack.opcode)
                val waiter = ackWaiters.remove(key)
                waiter?.complete(ack)
                _lastAckResponse.tryEmit(ack)
            }
        }

        // Synchronize UI from ConfigSnapshot when snapshot notifications arrive
        scope.launch {
            bleManager.configSnapshots.collect { snapshot ->
                if (snapshot != null && snapshot.isValid) {
                    val waiter = snapshotWaiters.remove(snapshot.snapshotId)
                    waiter?.complete(snapshot)

                    // Correlated readers decide whether their response is fresh enough.
                    if (waiter == null && snapshot.snapshotId == PulseShiftBleManager.SNAPSHOT_REQUEST_SEQ &&
                        connectionState.value in configConnectionStates &&
                        !_hasUnsavedChanges.value && !_lightingState.value.isPendingSync) {
                        applyAuthoritativeSnapshot(snapshot)
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
                _historicalPageLoadingState.update { current ->
                    if (current is HistoricalLogLoadingState.Loading) {
                        HistoricalLogLoadingState.PageLoaded(pageEnd.nextOffset, pageEnd.isFinished)
                    } else {
                        current
                    }
                }
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

        // Auto-reconnect preference and preset persistence
        scope.launch {
            dataStore.autoReconnect?.collect { enabled ->
                bleManager.setAutoReconnectEnabled(enabled)
            }
        }
        scope.launch {
            try {
                dataStore.migrateCustomPresetsIfNeeded()
            } catch (e: Exception) {
                Log.w("PulseShiftRepo", "Failed to run presets migration: ${e.message}")
            }
            dataStore.customPresetsJson?.collect { json ->
                val stored = parseCustomPresets(json)
                if (stored != null) {
                    val defaults = Preset.DefaultPresets
                    _presets.value = defaults + stored
                }
            }
        }

        // Owner authentication and authoritative snapshot fetch across reconnects:
        // when a connection carries telemetry or becomes ready, auto-prove
        // the stored credential (challenge-response) and reload configuration.
        // The firmware kills the session when the link drops, so the phone
        // must reflect that immediately instead of showing a stale session.
        scope.launch {
            bleManager.connectionState.collect { state ->
                when (state) {
                    BleConnectionState.READY -> {
                        if (connectionSetupJob?.isActive != true) {
                            val address = connectedDevice.value?.address
                            if (lastSetupDeviceAddress != address) {
                                _hasUnsavedChanges.value = false
                                lastSetupDeviceAddress = address
                            }
                            // Keep observing disconnects while auth/readback are awaiting responses.
                            connectionSetupJob = scope.launch { handleConnectionEstablished() }
                        }
                    }
                    BleConnectionState.DISCONNECTED, BleConnectionState.ERROR,
                    BleConnectionState.PERMISSION_REQUIRED, BleConnectionState.BLUETOOTH_DISABLED -> {
                        configLinkGeneration.incrementAndGet()
                        connectionSetupJob?.cancel()
                        connectionSetupJob = null
                        if (_isAuthenticated.value || _authState.value == AuthState.AUTHENTICATED ||
                            _authState.value == AuthState.AUTHENTICATING) {
                            _isAuthenticated.value = false
                            _authState.value = AuthState.SESSION_EXPIRED
                        }
                        _isNvsConfirmed.value = false
                        pendingPresetRevision = null
                        _configSnapshot.value = null
                        _diagnosticsReport.value = null
                        _lightingState.value = _lightingState.value.copy(isHardwareConfirmed = false)
                        _historicalPageLoadingState.value = HistoricalLogLoadingState.Idle
                        cancelPendingWaiters()
                    }
                    else -> {}
                }
            }
        }
    }

    private suspend fun handleConnectionEstablished() {
        checkAuthStateOnConnect()
        if (_authState.value == AuthState.ENROLLED && !_isAuthenticated.value) {
            val ok = authenticateChallengeResponse()
            if (ok) {
                recordDeviceCommand("Owner authentication", DeviceCommandPhase.APPLIED, "Session authorized")
                reloadConfigFromController()
            } else {
                recordDeviceCommand("Owner authentication", DeviceCommandPhase.FAILED, "Challenge-response rejected")
            }
        } else if (_authState.value == AuthState.NOT_ENROLLED && bleManager.hasConfigV2Support()) {
            reloadConfigFromController()
        }
    }

    /**
     * Cancel all in-flight deferred waiters when the BLE link drops,
     * so callers do not hang waiting for timeouts.
     */
    fun cancelPendingWaiters(reason: Throwable = CancellationException("BLE link disconnected")) {
        val ackCount = ackWaiters.size
        val snapCount = snapshotWaiters.size
        val diagCount = diagnosticsWaiters.size
        if (ackCount > 0 || snapCount > 0 || diagCount > 0) {
            Log.d("PulseShiftRepo", "Canceling pending waiters (ack=$ackCount, snap=$snapCount, diag=$diagCount): ${reason.message}")
        }
        for ((_, waiter) in ackWaiters) {
            waiter.completeExceptionally(reason)
        }
        ackWaiters.clear()
        for ((_, waiter) in snapshotWaiters) {
            waiter.completeExceptionally(reason)
        }
        snapshotWaiters.clear()
        for ((_, waiter) in diagnosticsWaiters) {
            waiter.completeExceptionally(reason)
        }
        diagnosticsWaiters.clear()
    }

    @androidx.annotation.VisibleForTesting
    internal fun getPendingAckWaiterCount(): Int = ackWaiters.size

    @androidx.annotation.VisibleForTesting
    internal fun getPendingSnapshotWaiterCount(): Int = snapshotWaiters.size

    @androidx.annotation.VisibleForTesting
    internal fun getPendingDiagnosticsWaiterCount(): Int = diagnosticsWaiters.size

    @androidx.annotation.VisibleForTesting
    internal fun putPendingAckWaiterForTesting(sequence: Int, opcode: Byte, deferred: CompletableDeferred<ConfigAckResponse>) {
        ackWaiters[AckKey(sequence, opcode)] = deferred
    }

    @androidx.annotation.VisibleForTesting
    internal fun putPendingSnapshotWaiterForTesting(snapshotId: Int, deferred: CompletableDeferred<ConfigSnapshot>) {
        snapshotWaiters[snapshotId] = deferred
    }

    @androidx.annotation.VisibleForTesting
    internal fun putPendingDiagnosticsWaiterForTesting(snapshotId: Int, deferred: CompletableDeferred<DiagnosticsReport>) {
        diagnosticsWaiters[snapshotId] = deferred
    }

    @androidx.annotation.VisibleForTesting
    internal fun setAuthenticatedForTesting(authenticated: Boolean) {
        _isAuthenticated.value = authenticated
    }

    @androidx.annotation.VisibleForTesting
    internal fun setNvsConfirmedForTesting(confirmed: Boolean) {
        _isNvsConfirmed.value = confirmed
    }

    @androidx.annotation.VisibleForTesting
    internal var commandSenderForTesting: (suspend (ByteArray, Int, Byte, Long) -> ConfigAckResponse?)? = null

    internal suspend fun sendCommand(
        commandBytes: ByteArray,
        sequence: Int,
        opcode: Byte,
        timeoutMs: Long = 5000L
    ): ConfigAckResponse? {
        commandSenderForTesting?.let { return it(commandBytes, sequence, opcode, timeoutMs) }
        return sendCommandAndWaitAck(commandBytes, sequence, opcode, timeoutMs)
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

    /**
     * Internal challenge-response implementation.
     * MUST NOT acquire transactionMutex itself, allowing it to be safely called
     * from within transactionMutex.withLock (such as requireAuthenticated()) without deadlocking.
     */
    private suspend fun internalAuthenticate(deviceAddress: String): Boolean {
        if (!bleManager.hasConfigV2Support()) return false

        val enrolledSecret = credentialManager.getEnrolledSecret(deviceAddress)
        if (enrolledSecret == null) {
            Log.w("PulseShiftRepo", "No enrolled owner secret found for $deviceAddress")
            _authState.value = AuthState.NOT_ENROLLED
            _isAuthenticated.value = false
            return false
        }

        _authState.value = AuthState.AUTHENTICATING

        val seqChallenge = nextSeq()
        val ackChallenge = sendCommandAndWaitAck(
            PulseShiftBleProtocol.buildRequestChallengeV2(seqChallenge),
            sequence = seqChallenge,
            opcode = PulseShiftBleProtocol.V2_OPCODE_REQUEST_CHALLENGE
        )

        if (ackChallenge == null || !ackChallenge.isSuccess || ackChallenge.payload.size < 4) {
            Log.e("PulseShiftRepo", "REQUEST_CHALLENGE failed: status=${ackChallenge?.statusCode}")
            // Do NOT automatically delete stored credentials on error; preserve local key in Keystore
            // and let the user re-authenticate, enroll to recover, or explicitly reset via confirmation.
            _authState.value = AuthState.AUTHENTICATION_REJECTED
            _isAuthenticated.value = false
            return false
        }

        val nonceBytes = ackChallenge.payload.copyOf(4)

        val seqAuth = nextSeq()
        val proof = PulseShiftBleProtocol.computeAuthProof(enrolledSecret, nonceBytes, seqAuth)

        val ackAuth = sendCommandAndWaitAck(
            PulseShiftBleProtocol.buildAuthenticateV2(seqAuth, proof),
            sequence = seqAuth,
            opcode = PulseShiftBleProtocol.V2_OPCODE_AUTHENTICATE
        )

        return if (ackAuth != null && ackAuth.isSuccess) {
            _authState.value = AuthState.AUTHENTICATED
            _isAuthenticated.value = true
            true
        } else {
            Log.e("PulseShiftRepo", "AUTHENTICATE failed: status=${ackAuth?.statusCode}")
            _authState.value = AuthState.AUTHENTICATION_REJECTED
            _isAuthenticated.value = false
            false
        }
    }

    override suspend fun authenticateChallengeResponse(): Boolean {
        val deviceAddress = bleManager.connectedDevice.value?.address ?: return false
        return transactionMutex.withLock {
            internalAuthenticate(deviceAddress)
        }
    }

    override suspend fun clearStoredOwnerCredential(): Boolean {
        val deviceAddress = bleManager.connectedDevice.value?.address ?: return false
        return transactionMutex.withLock {
            credentialManager.clearEnrolledSecret(deviceAddress)
            _authState.value = AuthState.NOT_ENROLLED
            _isAuthenticated.value = false
            _isNvsConfirmed.value = false
            true
        }
    }

    private fun isChangeAppliedInSnapshot(snapshot: ConfigSnapshot, opcode: Byte): Boolean {
        return when (opcode) {
            PulseShiftBleProtocol.V2_OPCODE_SET_POWER -> snapshot.isPowerOn == draftPower
            PulseShiftBleProtocol.V2_OPCODE_SET_BRIGHTNESS -> snapshot.brightness == draftBrightness
            PulseShiftBleProtocol.V2_OPCODE_SET_EFFECT ->
                snapshot.effect == draftEffect && snapshot.effectSpeed == draftEffectSpeed
            PulseShiftBleProtocol.V2_OPCODE_SET_GLOBAL_RGB ->
                snapshot.globalColor == draftLedColors.firstOrNull() || snapshot.ledColors.all { it == draftLedColors.firstOrNull() }
            PulseShiftBleProtocol.V2_OPCODE_SET_LED_RGB -> snapshot.ledColors == draftLedColors
            PulseShiftBleProtocol.V2_OPCODE_SET_STARTUP ->
                snapshot.startupAnimation == draftStartupAnimation && snapshot.startupDurationMs == draftStartupDurationMs
            PulseShiftBleProtocol.V2_OPCODE_SET_LED_DIRECTION -> snapshot.ledDirection == draftLedDirection
            PulseShiftBleProtocol.V2_OPCODE_SET_WARNING_RGB -> snapshot.warningColor == draftWarningColor
            PulseShiftBleProtocol.V2_OPCODE_SET_LED_THRESHOLD -> snapshot.rpmThresholds == draftThresholds
            PulseShiftBleProtocol.V2_OPCODE_SET_SHIFT_WARNING ->
                snapshot.warningRpm == draftWarningRpm && snapshot.warningPatternId == draftWarningStyleId
            else -> false
        }
    }

    /**
     * Shared path for live lighting writes: optimistic local update, device
     * write, ACK wait. Failures are surfaced through [lightingSyncError]
     * instead of being swallowed; the optimistic value stays as the pending
     * intent until a fresh snapshot (or CONFIG SAVE) reconciles it.
     */
    private suspend fun writeLightingCommand(
        label: String,
        opcode: Byte,
        buildCommand: (seq: Int) -> ByteArray,
        optimistic: () -> Unit,
    ): Boolean {
        return transactionMutex.withLock {
            if (!bleManager.hasConfigV2Support()) {
                _lightingError.tryEmit("Device is not running Config V2 firmware; cannot apply $label.")
                return@withLock false
            }
            if (!requireAuthenticated()) {
                _lightingError.tryEmit("Owner authentication required before modifying $label. Press BOOT on ESP32 to pair.")
                return@withLock false
            }
            val previousState = _lightingState.value
            val prevHasUnsaved = _hasUnsavedChanges.value
            val prevDraftPower = draftPower
            val prevDraftBrightness = draftBrightness
            val prevDraftEffect = draftEffect
            val prevDraftSpeed = draftEffectSpeed
            val prevDraftDirection = draftLedDirection
            val prevDraftAnim = draftStartupAnimation
            val prevDraftAnimDur = draftStartupDurationMs
            val prevDraftColors = draftLedColors.toList()
            val prevDraftWarningColor = draftWarningColor

            optimistic()
            _hasUnsavedChanges.value = true
            val seq = nextSeq()
            var ack = sendCommandAndWaitAck(buildCommand(seq), sequence = seq, opcode = opcode)
            if (ack != null && (ack.statusCode == PulseShiftBleProtocol.STATUS_ERR_UNAUTHORIZED || ack.isAuthRequired)) {
                Log.w("PulseShiftRepo", "Session expired on device during $label; attempting transparent re-authentication...")
                _isAuthenticated.value = false
                val devAddr = bleManager.connectedDevice.value?.address
                if (devAddr != null && internalAuthenticate(devAddr)) {
                    val retrySeq = nextSeq()
                    ack = sendCommandAndWaitAck(buildCommand(retrySeq), sequence = retrySeq, opcode = opcode)
                }
            }
            if (ack == null) {
                Log.w("PulseShiftRepo", "Timeout waiting ACK for $label (opcode 0x${opcode.toString(16)}). Querying fresh device snapshot to verify...")
                val seqSnap = nextSeq()
                val snapshot = requestAndWaitSnapshot(seqSnap, timeoutMs = 2500L)
                if (snapshot != null && snapshot.isValid) {
                    val applied = isChangeAppliedInSnapshot(snapshot, opcode)
                    applyAuthoritativeSnapshot(snapshot)
                    if (applied) {
                        Log.i("PulseShiftRepo", "Fresh device snapshot confirmed change for $label was applied by ESP32 despite ACK timeout.")
                        _hasUnsavedChanges.value = true
                        _isNvsConfirmed.value = false
                        return@withLock true
                    } else {
                        Log.w("PulseShiftRepo", "Fresh device snapshot shows change for $label was not applied by ESP32.")
                        _lightingError.tryEmit("Command for $label timed out and controller state confirms change was not applied.")
                        _hasUnsavedChanges.value = prevHasUnsaved
                        _isNvsConfirmed.value = false
                        return@withLock false
                    }
                }
                // If a BLE command times out and the snapshot request also fails,
                // the app must NOT restore the old UI state, because the controller may
                // already have applied the command. Show SYNC UNKNOWN until authoritative state is obtained.
                Log.w("PulseShiftRepo", "Snapshot query failed after timeout for $label. Marking SYNC UNKNOWN.")
                _lightingError.tryEmit("No acknowledgement from device for $label (timeout) and snapshot query failed. Controller sync unknown.")
                _lightingState.value = _lightingState.value.copy(
                    isPendingSync = false,
                    isHardwareConfirmed = false,
                    isSyncUnknown = true
                )
                _hasUnsavedChanges.value = true
                _isNvsConfirmed.value = false
                false
            } else if (!ack.isSuccess) {
                _lightingError.tryEmit(ackFailureDetail(ack))
                _lightingState.value = previousState.copy(isPendingSync = false, isHardwareConfirmed = false, isSyncUnknown = false)
                _hasUnsavedChanges.value = prevHasUnsaved
                draftPower = prevDraftPower
                draftBrightness = prevDraftBrightness
                draftEffect = prevDraftEffect
                draftEffectSpeed = prevDraftSpeed
                draftLedDirection = prevDraftDirection
                draftStartupAnimation = prevDraftAnim
                draftStartupDurationMs = prevDraftAnimDur
                draftLedColors = prevDraftColors.toMutableList()
                draftWarningColor = prevDraftWarningColor
                false
            } else {
                _hasUnsavedChanges.value = true
                _isNvsConfirmed.value = false
                _lightingState.value = _lightingState.value.copy(
                    isPendingSync = false,
                    isHardwareConfirmed = true,
                    isSyncUnknown = false
                )
                true
            }
        }
    }

    override suspend fun setPower(power: Boolean) {
        writeLightingCommand(
            "power", PulseShiftBleProtocol.V2_OPCODE_SET_POWER,
            { seq -> PulseShiftBleProtocol.buildSetPowerV2(seq, power) },
            {
                draftPower = power
                _lightingState.value = _lightingState.value.copy(isPowerOn = power, isPendingSync = true)
            },
        )
    }

    override suspend fun setBrightness(brightness: Int) {
        writeLightingCommand(
            "brightness", PulseShiftBleProtocol.V2_OPCODE_SET_BRIGHTNESS,
            { seq -> PulseShiftBleProtocol.buildSetBrightnessV2(seq, brightness) },
            {
                draftBrightness = brightness
                _lightingState.value = _lightingState.value.copy(brightness = brightness, isPendingSync = true)
            },
        )
    }

    override suspend fun setEffect(effect: EffectType): Boolean = writeLightingCommand(
        "effect ${effect.displayName}", PulseShiftBleProtocol.V2_OPCODE_SET_EFFECT,
        { seq -> PulseShiftBleProtocol.buildSetEffectV2(seq, effect.id, _lightingState.value.effectSpeed) },
        {
            draftEffect = effect
            _lightingState.value = _lightingState.value.copy(effect = effect, isPendingSync = true)
        },
    )

    override suspend fun setEffectSpeed(speed: Int) {
        writeLightingCommand(
            "effect speed", PulseShiftBleProtocol.V2_OPCODE_SET_EFFECT,
            { seq -> PulseShiftBleProtocol.buildSetEffectV2(seq, _lightingState.value.effect.id, speed) },
            {
                draftEffectSpeed = speed
                _lightingState.value = _lightingState.value.copy(effectSpeed = speed, isPendingSync = true)
            },
        )
    }

    override suspend fun setGlobalColor(color: RgbColor) {
        writeLightingCommand(
            "global color", PulseShiftBleProtocol.V2_OPCODE_SET_GLOBAL_RGB,
            { seq -> PulseShiftBleProtocol.buildSetGlobalRgbV2(seq, color) },
            {
                draftLedColors = MutableList(6) { color }
                _lightingState.value = _lightingState.value.copy(
                    globalColor = color,
                    ledColors = List(6) { color },
                    isPendingSync = true
                )
            },
        )
    }

    override suspend fun setIndividualLedColor(index: Int, color: RgbColor) {
        if (index !in 0..5) return
        writeLightingCommand(
            "LED ${index + 1} color", PulseShiftBleProtocol.V2_OPCODE_SET_LED_RGB,
            { seq -> PulseShiftBleProtocol.buildSetLedRgbV2(seq, index, color) },
            {
                draftLedColors[index] = color
                val updated = _lightingState.value.ledColors.toMutableList()
                updated[index] = color
                _lightingState.value = _lightingState.value.copy(ledColors = updated, isPendingSync = true)
            },
        )
    }

    override suspend fun setAllLedColors(colors: List<RgbColor>) {
        if (colors.size != 6) return
        transactionMutex.withLock {
            if (!bleManager.hasConfigV2Support()) {
                _lightingError.tryEmit("Device is not running Config V2 firmware; cannot apply colors.")
                return@withLock
            }
            if (!requireAuthenticated()) {
                _lightingError.tryEmit("Owner authentication required before modifying colors. Press BOOT on ESP32 to pair.")
                return@withLock
            }
            val previousState = _lightingState.value
            val prevDraftColors = draftLedColors.toList()
            draftLedColors = colors.toMutableList()
            _lightingState.value = _lightingState.value.copy(ledColors = colors, isPendingSync = true)
            _hasUnsavedChanges.value = true

            val succeededIndices = mutableSetOf<Int>()
            for (i in 0 until 6) {
                val seq = nextSeq()
                val ack = sendCommandAndWaitAck(
                    PulseShiftBleProtocol.buildSetLedRgbV2(seq, i, colors[i]),
                    sequence = seq,
                    opcode = PulseShiftBleProtocol.V2_OPCODE_SET_LED_RGB
                )
                if (ack != null && ack.isSuccess) {
                    succeededIndices.add(i)
                }
            }

            if (succeededIndices.size < 6) {
                Log.w("PulseShiftRepo", "setAllLedColors: ${succeededIndices.size}/6 LED writes acknowledged. Querying controller snapshot to reconcile...")
                val seqSnap = nextSeq()
                val snapshot = requestAndWaitSnapshot(seqSnap, timeoutMs = 2500L)
                if (snapshot != null && snapshot.isValid) {
                    Log.i("PulseShiftRepo", "Reconciled controller state from snapshot following partial color write (${succeededIndices.size}/6 succeeded).")
                    applyAuthoritativeSnapshot(snapshot)
                    _hasUnsavedChanges.value = true
                    _isNvsConfirmed.value = false
                    _lightingError.tryEmit("LED colors partially updated (${succeededIndices.size}/6). Reconciled with controller state.")
                    return@withLock
                }

                // If snapshot is unavailable, reconcile based on acknowledged commands rather than blanket rollback
                Log.w("PulseShiftRepo", "Snapshot query failed after partial color write. Reconciling with acknowledged LED writes and marking sync unknown.")
                val reconciledColors = prevDraftColors.toMutableList()
                for (idx in succeededIndices) {
                    reconciledColors[idx] = colors[idx]
                }
                draftLedColors = reconciledColors
                _lightingState.value = _lightingState.value.copy(
                    ledColors = reconciledColors,
                    isPendingSync = false,
                    isHardwareConfirmed = false,
                    isSyncUnknown = true
                )
                _hasUnsavedChanges.value = true
                _isNvsConfirmed.value = false
                _lightingError.tryEmit("LED colors partially updated (${succeededIndices.size}/6) and snapshot unavailable. Controller sync unknown.")
            } else {
                _hasUnsavedChanges.value = true
                _isNvsConfirmed.value = false
                _lightingState.value = _lightingState.value.copy(
                    isPendingSync = false,
                    isHardwareConfirmed = true,
                    isSyncUnknown = false
                )
            }
        }
    }

    override suspend fun setStartupAnimation(animation: StartupAnimation, durationMs: Int) {
        val caps = bleManager.connectedDevice.value?.capabilities
        val effectiveAnim = if (caps != null && animation !in caps.supportedStartupAnimations) {
            Log.w("BleRepo", "Animation $animation unsupported by firmware (maxId=${caps.maxStartupAnimId}); clamping to SWEEP")
            StartupAnimation.SWEEP
        } else {
            animation
        }
        writeLightingCommand(
            "startup animation", PulseShiftBleProtocol.V2_OPCODE_SET_STARTUP,
            { seq -> PulseShiftBleProtocol.buildSetStartupV2(seq, effectiveAnim.id, durationMs) },
            {
                draftStartupAnimation = effectiveAnim
                draftStartupDurationMs = durationMs
                _lightingState.value = _lightingState.value.copy(
                    startupAnimation = effectiveAnim,
                    startupDurationMs = durationMs,
                    isPendingSync = true
                )
            },
        )
    }

    override suspend fun setLedDirection(direction: Int) {
        writeLightingCommand(
            "LED direction", PulseShiftBleProtocol.V2_OPCODE_SET_LED_DIRECTION,
            { seq -> PulseShiftBleProtocol.buildSetLedDirectionV2(seq, direction) },
            {
                draftLedDirection = direction
                _lightingState.value = _lightingState.value.copy(ledDirection = direction, isPendingSync = true)
            },
        )
    }

    override suspend fun setWarningRgb(color: RgbColor) {
        writeLightingCommand(
            "warning color", PulseShiftBleProtocol.V2_OPCODE_SET_WARNING_RGB,
            { seq -> PulseShiftBleProtocol.buildSetWarningRgbV2(seq, color) },
            { draftWarningColor = color },
        )
    }

    override suspend fun updateShiftLightConfig(config: ShiftLightConfig) {
        transactionMutex.withLock {
            if (!bleManager.hasConfigV2Support()) {
                _lightingError.tryEmit("Device is not running Config V2 firmware; cannot apply thresholds.")
                return@withLock
            }
            if (!requireAuthenticated()) {
                _lightingError.tryEmit("Owner authentication required before modifying thresholds. Press BOOT on ESP32 to pair.")
                return@withLock
            }
            val previousShiftLight = _shiftLightConfig.value
            val prevDraftThresholds = draftThresholds.toList()
            val prevDraftWarningRpm = draftWarningRpm
            val prevDraftWarningStyleId = draftWarningStyleId
            val prevDraftWarningColor = draftWarningColor

            draftThresholds = config.thresholds.toMutableList()
            draftWarningRpm = config.warningRpm
            draftWarningStyleId = config.warningStyle.id
            config.colors.getOrNull(5)?.let { draftWarningColor = it }

            _shiftLightConfig.value = config
            _hasUnsavedChanges.value = true
            val succeededIndices = mutableSetOf<Int>()
            for (i in 0 until 6) {
                val seq = nextSeq()
                val ack = sendCommandAndWaitAck(
                    PulseShiftBleProtocol.buildSetLedThresholdV2(seq, i, config.thresholds[i]),
                    sequence = seq,
                    opcode = PulseShiftBleProtocol.V2_OPCODE_SET_LED_THRESHOLD
                )
                if (ack != null && ack.isSuccess) succeededIndices.add(i)
            }
            val seqWarn = nextSeq()
            val ackWarn = sendCommandAndWaitAck(
                PulseShiftBleProtocol.buildSetShiftWarningV2(seqWarn, config.warningRpm, config.warningStyle.id),
                sequence = seqWarn,
                opcode = PulseShiftBleProtocol.V2_OPCODE_SET_SHIFT_WARNING
            )
            val warnOk = ackWarn != null && ackWarn.isSuccess

            if (succeededIndices.size < 6 || !warnOk) {
                val seqSnap = nextSeq()
                val snapshot = requestAndWaitSnapshot(seqSnap, timeoutMs = 2500L)
                if (snapshot != null && snapshot.isValid) {
                    applyAuthoritativeSnapshot(snapshot)
                    _hasUnsavedChanges.value = true
                    _isNvsConfirmed.value = false
                    _lightingError.tryEmit("Shift light configuration partially applied. Reconciled with controller state.")
                    return@withLock
                }
                val reconciledThresholds = prevDraftThresholds.toMutableList()
                for (idx in succeededIndices) {
                    reconciledThresholds[idx] = config.thresholds[idx]
                }
                draftThresholds = reconciledThresholds
                if (warnOk) {
                    draftWarningRpm = config.warningRpm
                    draftWarningStyleId = config.warningStyle.id
                } else {
                    draftWarningRpm = prevDraftWarningRpm
                    draftWarningStyleId = prevDraftWarningStyleId
                }
                _shiftLightConfig.value = _shiftLightConfig.value.copy(
                    thresholds = draftThresholds.toList(),
                    warningRpm = draftWarningRpm,
                    warningStyle = warningStyleFromId(draftWarningStyleId)
                )
                _lightingState.value = _lightingState.value.copy(
                    isPendingSync = false,
                    isHardwareConfirmed = false,
                    isSyncUnknown = true
                )
                _hasUnsavedChanges.value = true
                _isNvsConfirmed.value = false
                _lightingError.tryEmit("Shift light update partially failed and snapshot unavailable. Controller sync unknown.")
            } else {
                _hasUnsavedChanges.value = true
                _isNvsConfirmed.value = false
                _lightingState.value = _lightingState.value.copy(
                    isPendingSync = false,
                    isHardwareConfirmed = true,
                    isSyncUnknown = false
                )
            }
        }
    }

    /** Single LED threshold write (opcode 0x16): avoids the 7-command flood
     *  of updateShiftLightConfig when the user moves one slider. */
    override suspend fun setLedThreshold(index: Int, rpm: Int) {
        if (index !in 0..5) return
        transactionMutex.withLock {
            if (!bleManager.hasConfigV2Support()) return@withLock
            if (!requireAuthenticated()) return@withLock
            val prevHasUnsaved = _hasUnsavedChanges.value
            draftThresholds = draftThresholds.toMutableList().also { it[index] = rpm }
            _shiftLightConfig.value = _shiftLightConfig.value.copy(thresholds = draftThresholds.toList())
            _hasUnsavedChanges.value = true
            val seq = nextSeq()
            val ack = sendCommandAndWaitAck(
                PulseShiftBleProtocol.buildSetLedThresholdV2(seq, index, rpm),
                sequence = seq,
                opcode = PulseShiftBleProtocol.V2_OPCODE_SET_LED_THRESHOLD
            )
            if (ack == null || !ack.isSuccess) {
                val seqSnap = nextSeq()
                val snapshot = requestAndWaitSnapshot(seqSnap, timeoutMs = 2500L)
                if (snapshot != null && snapshot.isValid) {
                    val applied = index < snapshot.rpmThresholds.size && snapshot.rpmThresholds[index] == rpm
                    applyAuthoritativeSnapshot(snapshot)
                    _hasUnsavedChanges.value = if (applied) true else prevHasUnsaved
                    _isNvsConfirmed.value = false
                    if (!applied) {
                        Log.w("PulseShiftRepo", "Fresh device snapshot shows threshold for LED ${index + 1} was not applied by ESP32.")
                        _lightingError.tryEmit("Threshold write for LED ${index + 1} timed out and controller snapshot confirms change was not applied.")
                    }
                    return@withLock
                }
                _lightingError.tryEmit("Threshold write for LED ${index + 1} was not acknowledged and snapshot query failed. Controller sync unknown.")
                _lightingState.value = _lightingState.value.copy(
                    isPendingSync = false,
                    isHardwareConfirmed = false,
                    isSyncUnknown = true
                )
                _hasUnsavedChanges.value = true
                _isNvsConfirmed.value = false
            } else {
                _hasUnsavedChanges.value = true
                _isNvsConfirmed.value = false
                _lightingState.value = _lightingState.value.copy(
                    isPendingSync = false,
                    isHardwareConfirmed = true,
                    isSyncUnknown = false
                )
            }
        }
    }

    /** Firmware-driven capture stage label (opcode 0x54). */
    override suspend fun setCaptureStage(stage: Int): Boolean {
        if (!requireAuthenticated()) return false
        recordDeviceCommand("Capture stage $stage", DeviceCommandPhase.SENDING)
        val seq = nextSeq()
        val ack = sendCommandAndWaitAck(
            PulseShiftBleProtocol.buildSetCaptureStageV2(seq, stage),
            sequence = seq,
            opcode = PulseShiftBleProtocol.V2_OPCODE_SET_CAPTURE_STAGE
        )
        return if (ack != null && ack.isSuccess && ack.isApplied) {
            recordDeviceCommand("Capture stage $stage", DeviceCommandPhase.APPLIED)
            true
        } else {
            recordDeviceCommand("Capture stage $stage", DeviceCommandPhase.FAILED, ackFailureDetail(ack))
            false
        }
    }

    /**
     * 10-second synthetic LED bench test (opcode 0x55). Drives LEDs from a
     * synthetic RPM value on the device; never touches CAN data or
     * calibration state.
     */
    override suspend fun setLedTestRpm(rpm: Int, enable: Boolean): Boolean {
        if (!requireAuthenticated()) return false
        recordDeviceCommand(
            if (enable) "LED bench test @ $rpm RPM" else "LED bench test off",
            DeviceCommandPhase.SENDING
        )
        val seq = nextSeq()
        val ack = sendCommandAndWaitAck(
            PulseShiftBleProtocol.buildSetLedTestRpmV2(seq, rpm, enable),
            sequence = seq,
            opcode = PulseShiftBleProtocol.V2_OPCODE_SET_LED_TEST_RPM
        )
        return if (ack != null && ack.isSuccess && ack.isApplied) {
            recordDeviceCommand(
                if (enable) "LED bench test @ $rpm RPM" else "LED bench test off",
                DeviceCommandPhase.ACCEPTED, "Expires after 10 s"
            )
            true
        } else {
            recordDeviceCommand(
                if (enable) "LED bench test @ $rpm RPM" else "LED bench test off",
                DeviceCommandPhase.FAILED, ackFailureDetail(ack)
            )
            false
        }
    }

    override suspend fun applyDecoderConfiguration(canId: Long, byteIndex: Int, isExtended: Boolean, multiplier: Float) {
        transactionMutex.withLock {
            draftCanCandidateId = canId
            draftCandidateByteIndex = byteIndex
            draftIsExtendedFrame = isExtended
            draftRpmMultiplier = multiplier

            if (!bleManager.hasConfigV2Support()) return@withLock
            if (!requireAuthenticated()) return@withLock

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

            var transactionCommitted = false
            try {
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

                transactionCommitted = true
                _hasUnsavedChanges.value = true
                _isNvsConfirmed.value = false

                val seqSnap = nextSeq()
                val snapshot = requestAndWaitSnapshot(seqSnap)
                    ?.takeIf { revisionAtLeast(it.activeRevision, ackCommit.activeRevision) }
                if (snapshot != null) applyAuthoritativeSnapshot(snapshot)

                if (snapshot != null && snapshot.isValid && snapshot.activeRevision == ackCommit.activeRevision &&
                    snapshot.canCandidateId == canId && snapshot.candidateByteIndex == byteIndex &&
                    snapshot.isExtendedFrame == isExtended && abs(snapshot.rpmMultiplier - multiplier) < 0.05f) {
                    Log.d("PulseShiftRepo", "Decoder configuration applied successfully.")
                } else {
                    Log.e("PulseShiftRepo", "Snapshot validation failed after applying decoder configuration.")
                }
            } finally {
                if (!transactionCommitted) {
                    withContext(NonCancellable) {
                        val seqAbort = nextSeq()
                        sendCommandAndWaitAck(
                            PulseShiftBleProtocol.buildAbortTransactionV2(seqAbort),
                            sequence = seqAbort,
                            opcode = PulseShiftBleProtocol.V2_OPCODE_ABORT_TRANSACTION
                        )
                    }
                }
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

    override suspend fun applyPreset(preset: Preset): PresetApplyResult {
        return transactionMutex.withLock {
            fun failPreset(message: String): Nothing {
                _lightingError.tryEmit(message)
                recordDeviceCommand("Apply preset", DeviceCommandPhase.FAILED, message)
                throw IllegalStateException(message)
            }

            if (!bleManager.hasConfigV2Support()) {
                failPreset("Device does not support Config V2 protocol.")
            }
            if (!requireAuthenticated()) {
                failPreset("Owner authentication required to apply presets.")
            }

            val previousState = _lightingState.value
            val wasUnsaved = _hasUnsavedChanges.value
            val colors = List(6) { preset.ledColors.getOrElse(it) { RgbColor.BrandViolet } }
            val brightness = preset.brightness.coerceIn(0, 255)
            val speed = preset.effectSpeed.coerceIn(0, 100)
            val shiftConfig = preset.shiftLightConfig
            val deviceAddress = connectedDevice.value?.address
            val linkGeneration = configLinkGeneration.get()
            fun linkAvailable(): Boolean =
                connectionState.value in configConnectionStates && connectedDevice.value?.address == deviceAddress &&
                    configLinkGeneration.get() == linkGeneration

            _lightingState.value = previousState.copy(isPendingSync = true, isHardwareConfirmed = false)
            recordDeviceCommand("Apply preset", DeviceCommandPhase.SENDING, preset.name)
            // A sent COMMIT can take effect even when the acknowledgement is lost.
            // Only abort staging if COMMIT was never sent or was explicitly rejected.
            var abortStaging = true
            var commitAttempted = false
            try {
                val seqBegin = nextSeq()
                val ackBegin = sendCommandAndWaitAck(
                    PulseShiftBleProtocol.buildBeginTransactionV2(seqBegin),
                    seqBegin,
                    PulseShiftBleProtocol.V2_OPCODE_BEGIN_TRANSACTION
                )
                if (ackBegin == null || !ackBegin.isSuccess || !ackBegin.isStagedOnly) {
                    abortStaging = ackBegin == null
                    failPreset("Couldn't begin preset transaction. ${ackFailureDetail(ackBegin)}")
                }

                suspend fun stage(opcode: Byte, build: (Int) -> ByteArray) {
                    val sequence = nextSeq()
                    val ack = sendCommandAndWaitAck(build(sequence), sequence, opcode)
                    if (ack == null || !ack.isSuccess || !ack.isStagedOnly) {
                        failPreset("Couldn't stage preset configuration. ${ackFailureDetail(ack)}")
                    }
                }

                stage(PulseShiftBleProtocol.V2_OPCODE_SET_BRIGHTNESS) {
                    PulseShiftBleProtocol.buildSetBrightnessV2(it, brightness, isStaged = true)
                }
                stage(PulseShiftBleProtocol.V2_OPCODE_SET_EFFECT) {
                    PulseShiftBleProtocol.buildSetEffectV2(it, preset.effect.id, speed, isStaged = true)
                }
                stage(PulseShiftBleProtocol.V2_OPCODE_SET_LED_DIRECTION) {
                    PulseShiftBleProtocol.buildSetLedDirectionV2(it, preset.ledDirection, isStaged = true)
                }
                stage(PulseShiftBleProtocol.V2_OPCODE_SET_STARTUP) {
                    PulseShiftBleProtocol.buildSetStartupV2(it, preset.startupAnimation.id, preset.startupDurationMs, isStaged = true)
                }
                colors.forEachIndexed { index, color ->
                    stage(PulseShiftBleProtocol.V2_OPCODE_SET_LED_RGB) {
                        PulseShiftBleProtocol.buildSetLedRgbV2(it, index, color, isStaged = true)
                    }
                }
                if (shiftConfig != null) {
                    shiftConfig.thresholds.forEachIndexed { index, rpm ->
                        stage(PulseShiftBleProtocol.V2_OPCODE_SET_LED_THRESHOLD) {
                            PulseShiftBleProtocol.buildSetLedThresholdV2(it, index, rpm, isStaged = true)
                        }
                    }
                    stage(PulseShiftBleProtocol.V2_OPCODE_SET_SHIFT_WARNING) {
                        PulseShiftBleProtocol.buildSetShiftWarningV2(it, shiftConfig.warningRpm, shiftConfig.warningStyle.id, isStaged = true)
                    }
                    stage(PulseShiftBleProtocol.V2_OPCODE_SET_WARNING_RGB) {
                        PulseShiftBleProtocol.buildSetWarningRgbV2(it, shiftConfig.colors.last(), isStaged = true)
                    }
                }

                val seqCommit = nextSeq()
                abortStaging = false
                commitAttempted = true
                _hasUnsavedChanges.value = true
                val wasNvsConfirmed = _isNvsConfirmed.value
                _isNvsConfirmed.value = false
                val ackCommit = sendCommandAndWaitAck(
                    PulseShiftBleProtocol.buildCommitTransactionV2(seqCommit),
                    seqCommit,
                    PulseShiftBleProtocol.V2_OPCODE_COMMIT_TRANSACTION
                )
                if (ackCommit != null && !ackCommit.isSuccess) {
                    abortStaging = true
                    commitAttempted = false
                    _hasUnsavedChanges.value = wasUnsaved
                    _isNvsConfirmed.value = wasNvsConfirmed && linkAvailable()
                    failPreset("Couldn't commit preset configuration. ${ackFailureDetail(ackCommit)}")
                }

                val applied = ackCommit?.isApplied == true
                val commitRevision = if (applied) {
                    checkNotNull(ackCommit).activeRevision
                } else {
                    (ackBegin.activeRevision + 1) and 0xFFFF_FFFFL
                }
                if (linkAvailable()) pendingPresetRevision = commitRevision.takeIf { applied }

                // Retry dropped, corrupt or older readback. Each request has
                // its own sequence, so a delayed response cannot satisfy a later attempt.
                var snapshot: ConfigSnapshot? = null
                repeat(3) { attempt ->
                    if (snapshot == null && linkAvailable()) {
                        if (attempt > 0) delay(150L)
                        snapshot = requestAndWaitSnapshot(nextSeq(), timeoutMs = 3000L)
                            ?.takeIf { revisionAtLeast(it.activeRevision, commitRevision) }
                    }
                }

                val readback = snapshot?.takeIf { linkAvailable() }
                val mismatches = readback?.let {
                    presetMismatchFields(it, preset.copy(brightness = brightness, effectSpeed = speed, ledColors = colors))
                }.orEmpty()
                val confirmed = readback?.activeRevision == commitRevision && mismatches.isEmpty()

                if (readback != null) {
                    applyAuthoritativeSnapshot(readback)
                    pendingPresetRevision = null
                } else if (applied && linkAvailable()) {
                    // The ACK proves these writes are active in RAM. Keep the
                    // last-known values for every field the preset did not write.
                    draftBrightness = brightness
                    draftEffect = preset.effect
                    draftEffectSpeed = speed
                    draftLedColors = colors.toMutableList()
                    draftLedDirection = preset.ledDirection
                    draftStartupAnimation = preset.startupAnimation
                    draftStartupDurationMs = preset.startupDurationMs
                    _lightingState.value = _lightingState.value.copy(
                        brightness = brightness,
                        effect = preset.effect,
                        effectSpeed = speed,
                        ledColors = colors,
                        ledDirection = preset.ledDirection,
                        startupAnimation = preset.startupAnimation,
                        startupDurationMs = preset.startupDurationMs
                    )
                    if (shiftConfig != null) {
                        draftThresholds = shiftConfig.thresholds.toMutableList()
                        draftWarningRpm = shiftConfig.warningRpm
                        draftWarningStyleId = shiftConfig.warningStyle.id
                        draftWarningColor = shiftConfig.colors.last()
                        _shiftLightConfig.value = _shiftLightConfig.value.copy(
                            thresholds = draftThresholds.toList(),
                            warningRpm = draftWarningRpm,
                            warningStyle = shiftConfig.warningStyle,
                            colors = _shiftLightConfig.value.colors.dropLast(1) + draftWarningColor
                        )
                    }
                }

                val result = when {
                    confirmed -> PresetApplyResult.READBACK_CONFIRMED
                    applied -> PresetApplyResult.APPLIED_NEEDS_READBACK
                    else -> PresetApplyResult.COMMIT_UNKNOWN
                }
                val detail = when {
                    readback == null -> "Fresh configuration readback unavailable."
                    readback.activeRevision != commitRevision ->
                        "Controller returned revision ${readback.activeRevision}; expected $commitRevision."
                    mismatches.isNotEmpty() -> "Readback differs in ${mismatches.joinToString()}."
                    else -> ""
                }
                if (!confirmed) {
                    _lightingState.value = _lightingState.value.copy(
                        isPendingSync = true, isHardwareConfirmed = false, isSyncUnknown = true
                    )
                    _lightingError.tryEmit("${result.message(preset.name)} $detail")
                }
                val phase = if (result == PresetApplyResult.COMMIT_UNKNOWN) {
                    DeviceCommandPhase.UNVERIFIED
                } else {
                    DeviceCommandPhase.APPLIED
                }
                recordDeviceCommand("Apply preset", phase, "${result.message(preset.name)} $detail".trimEnd())
                result
            } catch (e: CancellationException) {
                if (commitAttempted) {
                    _lightingState.value = _lightingState.value.copy(
                        isPendingSync = true, isHardwareConfirmed = false, isSyncUnknown = true
                    )
                    recordDeviceCommand("Apply preset", DeviceCommandPhase.UNVERIFIED, "Readback interrupted. Resync with the ESP32.")
                }
                throw e
            } finally {
                if (!commitAttempted) {
                    _lightingState.value = previousState.copy(
                        isHardwareConfirmed = previousState.isHardwareConfirmed && linkAvailable()
                    )
                }
                if (abortStaging && linkAvailable() && _isAuthenticated.value) {
                    withContext(NonCancellable) {
                        val seqAbort = nextSeq()
                        sendCommandAndWaitAck(
                            PulseShiftBleProtocol.buildAbortTransactionV2(seqAbort),
                            seqAbort,
                            PulseShiftBleProtocol.V2_OPCODE_ABORT_TRANSACTION,
                            timeoutMs = 1000L
                        )
                    }
                }
            }
        }
    }

    private fun presetMismatchFields(
        snapshot: ConfigSnapshot,
        preset: Preset
    ): List<String> = buildList {
        if (snapshot.brightness != preset.brightness) add("brightness")
        if (snapshot.effect != preset.effect) add("effect")
        if (snapshot.effectSpeed != preset.effectSpeed) add("effect speed")
        if (snapshot.ledColors != preset.ledColors) add("LED colors")
        if (snapshot.ledDirection != preset.ledDirection) add("LED direction")
        if (snapshot.startupAnimation != preset.startupAnimation) add("startup animation")
        if (snapshot.startupDurationMs != preset.startupDurationMs) add("startup duration")
        preset.shiftLightConfig?.let { config ->
            if (snapshot.rpmThresholds != config.thresholds) add("RPM thresholds")
            if (snapshot.warningRpm != config.warningRpm) add("warning RPM")
            if (snapshot.warningPatternId != config.warningStyle.id) add("warning pattern")
            if (snapshot.warningColor != config.colors.last()) add("warning color")
        }
    }

    // Firmware revisions are unsigned 32-bit serial numbers.
    private fun revisionAtLeast(actual: Long, expected: Long): Boolean =
        (actual - expected).toInt() >= 0

    override suspend fun savePreset(preset: Preset) {
        val list = _presets.value.toMutableList()
        val index = list.indexOfFirst { it.id == preset.id }
        if (index >= 0) {
            list[index] = preset.copy(isCustom = true)
        } else {
            val assignedId = if (preset.id.isNotBlank()) preset.id else UUID.randomUUID().toString()
            list.add(preset.copy(id = assignedId, isCustom = true))
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

    override suspend fun updatePreset(preset: Preset) {
        val list = _presets.value.toMutableList()
        val index = list.indexOfFirst { it.id == preset.id }
        if (index >= 0) {
            list[index] = preset.copy(isCustom = true)
            _presets.value = list
            persistCustomPresets(list)
        }
    }

    override suspend fun duplicatePreset(preset: Preset): Preset {
        val newPreset = preset.copy(
            id = UUID.randomUUID().toString(),
            name = "${preset.name} (Copy)",
            isCustom = true,
            createdAt = System.currentTimeMillis()
        )
        val list = _presets.value.toMutableList()
        list.add(newPreset)
        _presets.value = list
        persistCustomPresets(list)
        return newPreset
    }

    override suspend fun saveCurrentSetupAsPreset(name: String): Preset {
        val currentLighting = _lightingState.value
        val currentShift = _shiftLightConfig.value
        val newPreset = Preset(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "Custom Setup" },
            effect = currentLighting.effect,
            ledColors = currentLighting.ledColors,
            brightness = currentLighting.brightness,
            effectSpeed = currentLighting.effectSpeed,
            ledDirection = currentLighting.ledDirection,
            startupAnimation = currentLighting.startupAnimation,
            startupDurationMs = currentLighting.startupDurationMs,
            isCustom = true,
            shiftLightConfig = currentShift,
            createdAt = System.currentTimeMillis()
        )
        val list = _presets.value.toMutableList()
        list.add(newPreset)
        _presets.value = list
        persistCustomPresets(list)
        return newPreset
    }

    override suspend fun apply1000To6000Profile(): ProfileApplyResult {
        return transactionMutex.withLock {
            if (!bleManager.hasConfigV2Support()) {
                _lightingError.tryEmit("Device does not support Config V2 protocol.")
                return@withLock ProfileApplyResult.FAILED
            }
            if (!requireAuthenticated()) {
                _lightingError.tryEmit("Owner authentication required to apply profile.")
                return@withLock ProfileApplyResult.FAILED
            }

            val targetThresholds = listOf(1000, 2000, 3000, 4000, 5000, 6000)
            val targetWarningRpm = 6500

            // 1. Begin Staged Transaction
            val seqTx = nextSeq()
            val ackBegin = sendCommandAndWaitAck(
                PulseShiftBleProtocol.buildBeginTransactionV2(seqTx),
                sequence = seqTx,
                opcode = PulseShiftBleProtocol.V2_OPCODE_BEGIN_TRANSACTION
            )
            if (ackBegin == null || !ackBegin.isSuccess) {
                Log.e("PulseShiftRepo", "apply1000To6000Profile: BEGIN_TRANSACTION failed")
                _lightingError.tryEmit("Failed to begin configuration transaction on ESP32.")
                return@withLock ProfileApplyResult.FAILED
            }

            var transactionCommitted = false
            try {
                var transactionFailed = false

                // 2. Stage thresholds 0..5
                for (i in 0 until 6) {
                    val seqT = nextSeq()
                    val ack = sendCommandAndWaitAck(
                        PulseShiftBleProtocol.buildSetLedThresholdV2(seqT, i, targetThresholds[i], isStaged = true),
                        sequence = seqT,
                        opcode = PulseShiftBleProtocol.V2_OPCODE_SET_LED_THRESHOLD
                    )
                    if (ack == null || !ack.isSuccess) {
                        Log.e("PulseShiftRepo", "apply1000To6000Profile: Staging threshold $i failed")
                        transactionFailed = true
                        break
                    }
                }

                // 3. Stage warning RPM
                if (!transactionFailed) {
                    val seqW = nextSeq()
                    val ackW = sendCommandAndWaitAck(
                        PulseShiftBleProtocol.buildSetShiftWarningV2(seqW, targetWarningRpm, draftWarningStyleId, isStaged = true),
                        sequence = seqW,
                        opcode = PulseShiftBleProtocol.V2_OPCODE_SET_SHIFT_WARNING
                    )
                    if (ackW == null || !ackW.isSuccess) {
                        Log.e("PulseShiftRepo", "apply1000To6000Profile: Staging warning RPM failed")
                        transactionFailed = true
                    }
                }

                if (transactionFailed) {
                    _lightingError.tryEmit("Failed to stage 1,000–6,000 RPM profile on ESP32.")
                    return@withLock ProfileApplyResult.FAILED
                }

                // 4. Commit Staged Transaction
                val seqCommit = nextSeq()
                val ackCommit = sendCommandAndWaitAck(
                    PulseShiftBleProtocol.buildCommitTransactionV2(seqCommit),
                    sequence = seqCommit,
                    opcode = PulseShiftBleProtocol.V2_OPCODE_COMMIT_TRANSACTION
                )
                if (ackCommit == null || !ackCommit.isSuccess) {
                    Log.e("PulseShiftRepo", "apply1000To6000Profile: COMMIT_TRANSACTION failed")
                    _lightingError.tryEmit("Failed to commit 1,000–6,000 RPM profile transaction.")
                    return@withLock ProfileApplyResult.FAILED
                }

                transactionCommitted = true

                // 5. Save committed configuration to NVS
                val seqSave = nextSeq()
                val ackSave = sendCommandAndWaitAck(
                    PulseShiftBleProtocol.buildSaveConfigV2(seqSave),
                    sequence = seqSave,
                    opcode = PulseShiftBleProtocol.V2_OPCODE_SAVE_CONFIG
                )

                // 6. Update local drafts and states
                draftThresholds = targetThresholds.toMutableList()
                draftWarningRpm = targetWarningRpm
                _shiftLightConfig.value = _shiftLightConfig.value.copy(
                    thresholds = targetThresholds,
                    warningRpm = targetWarningRpm
                )

                // Successful persistence strictly requires ACK.isSuccess && ACK.isPersisted
                val isPersisted = ackSave != null && ackSave.isSuccess && ackSave.isPersisted
                _hasUnsavedChanges.value = !isPersisted
                nvsConfirmedRevision = ackSave?.activeRevision?.takeIf { isPersisted }
                _isNvsConfirmed.value = isPersisted

                // 7. Request authoritative snapshot to synchronize UI
                val seqSnap = nextSeq()
                requestAndWaitSnapshot(seqSnap)
                    ?.takeIf { revisionAtLeast(it.activeRevision, ackCommit.activeRevision) }
                    ?.let { applyAuthoritativeSnapshot(it) }

                if (isPersisted) {
                    recordDeviceCommand("Apply 1k-6k profile", DeviceCommandPhase.SAVED, "1,000–6,000 RPM profile applied and saved to NVS")
                    ProfileApplyResult.PERSISTED
                } else {
                    recordDeviceCommand("Apply 1k-6k profile", DeviceCommandPhase.APPLIED, "1,000–6,000 RPM profile active in RAM only (NVS save unconfirmed)")
                    ProfileApplyResult.APPLIED_ONLY
                }
            } finally {
                if (!transactionCommitted) {
                    withContext(NonCancellable) {
                        val seqAbort = nextSeq()
                        sendCommandAndWaitAck(
                            PulseShiftBleProtocol.buildAbortTransactionV2(seqAbort),
                            sequence = seqAbort,
                            opcode = PulseShiftBleProtocol.V2_OPCODE_ABORT_TRANSACTION
                        )
                    }
                }
            }
        }
    }

    private suspend fun persistCustomPresets(all: List<Preset>) {
        try {
            dataStore.setCustomPresetsJson(serializeCustomPresets(all.filter { it.isCustom }))
        } catch (e: Exception) {
            Log.w("PulseShiftRepo", "Failed to persist custom presets: ${e.message}")
            throw e
        }
    }

    /** Firmware warning pattern id (0..3) → UI style; unknown ids map to FLASH. */
    private fun warningStyleFromId(id: Int): WarningStyle = when (id) {
        1 -> WarningStyle.STROBE
        2 -> WarningStyle.SOLID
        3 -> WarningStyle.OFF
        else -> WarningStyle.FLASH
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
            if (!requireAuthenticated()) {
                _lightingError.tryEmit("Owner authentication required to save configuration to ESP32.")
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
                _lightingError.tryEmit(ackSave?.let { ackFailureDetail(it) } ?: "Save config timeout.")
                return@withLock false
            }

            // Persistence and current RAM readback are independent confirmations.
            _hasUnsavedChanges.value = false
            nvsConfirmedRevision = ackSave.activeRevision
            _isNvsConfirmed.value = true
            recordDeviceCommand("Save to ESP32", DeviceCommandPhase.SAVED, "Persisted revision ${ackSave.activeRevision} to NVS")

            val snapshot = requestAndWaitSnapshot(nextSeq())
                ?.takeIf { revisionAtLeast(it.activeRevision, ackSave.activeRevision) }
            if (snapshot != null) {
                applyAuthoritativeSnapshot(snapshot)
            } else {
                _lightingState.value = _lightingState.value.copy(
                    isHardwareConfirmed = false, isPendingSync = true, isSyncUnknown = true
                )
                _lightingError.tryEmit("ESP32 acknowledged the NVS save. Resync to verify the current configuration.")
            }
            true
        }
    }

    override suspend fun reloadConfigFromController(): ConfigSnapshot? {
        return transactionMutex.withLock {
            if (!bleManager.hasConfigV2Support()) return@withLock null
            val seq = nextSeq()
            val snapshot = requestAndWaitSnapshot(seq)
                ?.takeIf { snapshot ->
                    pendingPresetRevision?.let { revisionAtLeast(snapshot.activeRevision, it) } ?: true
                }
            if (snapshot != null && snapshot.isValid) {
                applyAuthoritativeSnapshot(snapshot)
                pendingPresetRevision = null
            }
            snapshot
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
                    applyAuthoritativeSnapshot(snapshot)
                    _isNvsConfirmed.value = false
                    _hasUnsavedChanges.value = true
                }
            } else {
                Log.e("PulseShiftRepo", "RESTORE_DEFAULTS failed: status=${ackRestore?.statusCode}")
            }
        }
    }

    override suspend fun requestHistoricalLogPage(startOffset: Long) {
        _historicalPageLoadingState.value = HistoricalLogLoadingState.Loading(startOffset)
        bleManager.requestHistoricalLogPage(startOffset)
        // A dropped page request must not spin a loader forever: the firmware
        // answer arrives via pageEndEvents, or this transitions to Error.
        scope.launch {
            delay(PAGE_REQUEST_TIMEOUT_MS)
            val current = _historicalPageLoadingState.value
            if (current is HistoricalLogLoadingState.Loading && current.requestedOffset == startOffset) {
                _historicalPageLoadingState.value =
                    HistoricalLogLoadingState.Error("No page response from device (timeout).")
            }
        }
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
        if (_isAuthenticated.value) return true

        val deviceAddress = bleManager.connectedDevice.value?.address
        if (deviceAddress == null) {
            recordDeviceCommand("Device command", DeviceCommandPhase.FAILED, "No connected device")
            return false
        }

        // Invoke internalAuthenticate directly without re-locking transactionMutex
        val ok = internalAuthenticate(deviceAddress)
        if (!ok) {
            recordDeviceCommand("Device command", DeviceCommandPhase.FAILED,
                "Owner session required. Press BOOT on the ESP32 and re-enroll if credentials were lost.")
            return false
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
        } catch (e: TimeoutCancellationException) {
            Log.w("PulseShiftRepo", "Timeout waiting for DiagnosticsReport seq $seq")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("PulseShiftRepo", "Exception waiting for DiagnosticsReport seq $seq: ${e.message}")
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
            _hasUnsavedChanges.value = false
            _isNvsConfirmed.value = false
            val seqSnap = nextSeq()
            val snapshot = requestAndWaitSnapshot(seqSnap)
            if (snapshot != null && snapshot.isValid) {
                applyAuthoritativeSnapshot(snapshot)
            }
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

    /** Begin a bounded, cancellable capture export from [startRecord] (12-record pages over BLE). */
    @Synchronized
    override fun startCaptureExport(startRecord: Long) {
        if (captureAutoExportJob?.isActive == true) return
        recordDeviceCommand("Export CAN capture", DeviceCommandPhase.SENDING, "Streaming pages from record $startRecord...")
        _captureExportState.value = CaptureExportState.Exporting(nextRecordIndex = startRecord)
        captureAutoExportJob = scope.launch(start = CoroutineStart.LAZY) {
            val job = coroutineContext.job
            val records = ArrayList<CaptureRecord>()
            var cursor = startRecord
            var failure = ""
            while (records.size + 12 <= MAX_CAPTURE_EXPORT_RECORDS) {
                when (val page = bleManager.readCapturePage(cursor)) {
                    is CapturePageRead.Complete -> {
                        cursor = page.cursor.nextOffset
                        records.addAll(page.records)
                        synchronized(this@BlePulseShiftRepository) {
                            if (captureAutoExportJob !== job) return@launch
                            _captureExportState.value = CaptureExportState.Exporting(
                                cursor, records.toList(), isFinished = page.cursor.isFinished
                            )
                            if (page.cursor.isFinished) {
                                recordDeviceCommand("Export CAN capture", DeviceCommandPhase.APPLIED,
                                    "${records.size} records received")
                                return@launch
                            }
                        }
                    }
                    is CapturePageRead.Failed -> {
                        failure = page.reason
                        break
                    }
                }
            }
            synchronized(this@BlePulseShiftRepository) {
                if (captureAutoExportJob !== job) return@launch
                val reason = failure.ifEmpty { "Stopped at the $MAX_CAPTURE_EXPORT_RECORDS-record export limit." }
                _captureExportState.value = CaptureExportState.Failed(reason, cursor, records.toList())
                recordDeviceCommand("Export CAN capture", DeviceCommandPhase.FAILED, "$reason Resume at record $cursor.")
            }
        }
        captureAutoExportJob?.start()
    }

    @Synchronized
    override fun cancelCaptureExport() {
        val progress = _captureExportState.value as? CaptureExportState.Exporting ?: return
        if (progress.isFinished) return
        captureAutoExportJob?.cancel()
        captureAutoExportJob = null
        _captureExportState.value = CaptureExportState.Failed(
            "Cancelled by user.", progress.nextRecordIndex, progress.records
        )
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
            _hasUnsavedChanges.value = true
            _isNvsConfirmed.value = false
            recordDeviceCommand("Calibration fit", DeviceCommandPhase.APPLIED,
                "Multiplier ${result.multiplier}, residual ${result.maxResidualRpm} RPM")
            val seqSnap = nextSeq()
            requestAndWaitSnapshot(seqSnap)
                ?.takeIf { revisionAtLeast(it.activeRevision, ack.activeRevision) }
                ?.let { applyAuthoritativeSnapshot(it) }
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
                try {
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
                            warningStyle = warningStyleFromId(s.getInt("warningStyleId")),
                            colors = if (s.has("warningColor")) {
                                // Default palette with the persisted warning color in slot 5.
                                listOf(
                                    RgbColor.NeonGreen, RgbColor.NeonGreen, RgbColor.AmberWarning,
                                    RgbColor.AmberWarning, RgbColor.SunsetOrange,
                                    RgbColor.fromHex(s.getString("warningColor"))
                                )
                            } else ShiftLightConfig().colors
                        )
                    } else null
                    list.add(
                        Preset(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            effect = EffectType.entries.firstOrNull { it.id == obj.getInt("effectId") } ?: EffectType.STATIC,
                            ledColors = colors,
                            brightness = obj.optInt("brightness", 255),
                            effectSpeed = obj.getInt("effectSpeed"),
                            ledDirection = obj.optInt("ledDirection", 0),
                            startupAnimation = StartupAnimation.entries.firstOrNull { it.id == obj.optInt("startupAnimationId", 1) } ?: StartupAnimation.SWEEP,
                            startupDurationMs = obj.optInt("startupDurationMs", 2000),
                            isCustom = true,
                            shiftLightConfig = shift,
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                } catch (e: Exception) {
                    Log.w("PulseShiftRepo", "Skipping corrupted preset entry at index $i: ${e.message}")
                }
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
                obj.put("ledDirection", p.ledDirection)
                obj.put("startupAnimationId", p.startupAnimation.id)
                obj.put("startupDurationMs", p.startupDurationMs)
                obj.put("createdAt", p.createdAt)
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
                    cfg.colors.getOrNull(5)?.let { s.put("warningColor", it.toHex()) }
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
        private const val PAGE_REQUEST_TIMEOUT_MS = 8000L
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

    override suspend fun startOtaUpdate(imageBytes: ByteArray): Boolean {
        if (!otaUpdateMutex.tryLock()) return false
        try {
            return runOtaUpdate(imageBytes)
        } catch (cancelled: CancellationException) {
            val state = _otaUpdateState.value
            if (state is OtaUpdateState.Validating) {
                _otaUpdateState.value = OtaUpdateState.Failed(
                    "Interrupted while waiting for the ESP32 to confirm OTA start."
                )
            } else if (state is OtaUpdateState.Verifying || state is OtaUpdateState.Rebooting) {
                _otaUpdateState.value = OtaUpdateState.Failed(
                    "Post-update verification was interrupted. The ESP32 may have switched slots.",
                    false
                )
            }
            throw cancelled
        } finally {
            otaUpdateMutex.unlock()
        }
    }

    private suspend fun runOtaUpdate(imageBytes: ByteArray): Boolean {
        if (!bleManager.hasConfigV2Support()) {
            _otaUpdateState.value = OtaUpdateState.Failed("Device does not support PulseShift Configuration Protocol V2.", false)
            return false
        }
        val dev = connectedDevice.value
        if (dev == null || !dev.isConnected) {
            _otaUpdateState.value = OtaUpdateState.Failed("No PulseShift device connected via BLE.", false)
            return false
        }
        if (!_isAuthenticated.value) {
            _otaUpdateState.value = OtaUpdateState.Failed("Device session is not authorized. Complete Owner Authentication first.", false)
            return false
        }
        val caps = dev.capabilities
        if (!caps.supportsOtaUpdate || !caps.hasDualOtaSlots || caps.otaActiveSlot !in 0..1) {
            _otaUpdateState.value = OtaUpdateState.Failed("Connected device (${dev.firmwareVersion ?: "unknown"}) does not report a compatible dual-slot OTA layout.", false)
            return false
        }
        val rpm = rpmTelemetryState.value
        if (rpm == null || !rpm.isPacketFresh || !rpm.statusFlags.isCanHealthy ||
            rpm.statusFlags.isFlashRecordingActive || rpm.statusFlags.isRecentCanTrafficDetected ||
            (rpm.isRpmAvailable && rpm.provisionalRpm > 0)) {
            _otaUpdateState.value = OtaUpdateState.Failed(
                "OTA requires fresh controller telemetry, an operational and idle CAN receiver, and stopped recording. Switch off the engine and ignition.",
                false
            )
            return false
        }

        val linkGeneration = configLinkGeneration.get()
        val size = imageBytes.size.toLong()
        val previousVersion = dev.firmwareVersion ?: "unknown"
        val expectedSlot = 1 - caps.otaActiveSlot
        val image = try {
            withContext(kotlinx.coroutines.Dispatchers.Default) { validateOtaImage(imageBytes) }
        } catch (error: IllegalArgumentException) {
            _otaUpdateState.value = OtaUpdateState.Failed(error.message ?: "Invalid signed firmware package.", false)
            return false
        }
        val shaBytes = image.sha256
        val shaHex = shaBytes.joinToString("") { "%02x".format(it) }
        _otaUpdateState.value = OtaUpdateState.Validating(size, shaHex, expectedSlot)

        val statusSeq = nextSeq()
        val status = sendCommandAndWaitAck(
            PulseShiftBleProtocol.buildOtaStatusV2(statusSeq), statusSeq,
            PulseShiftBleProtocol.V2_OPCODE_OTA_STATUS
        )
        if (status?.statusCode != PulseShiftBleProtocol.STATUS_SUCCESS || status.payload.size < 8 ||
            status.payload[0] != 0.toByte() || status.payload[1].toInt() != caps.otaActiveSlot ||
            status.payload[6] != 2.toByte()) {
            _otaUpdateState.value = OtaUpdateState.Failed(
                "OTA requires an idle controller with the current boot slot marked VALID as the fallback.", false
            )
            return false
        }
        if (configLinkGeneration.get() != linkGeneration || connectedDevice.value?.address != dev.address ||
            !_isAuthenticated.value) {
            _otaUpdateState.value = OtaUpdateState.Failed("The authenticated BLE connection changed. Reconnect and select the file again.")
            return false
        }

        // 1. Send OTA BEGIN
        val seqBegin = nextSeq()
        val shaPrefix = shaBytes.copyOfRange(0, 4)
        val beginPacket = PulseShiftBleProtocol.buildOtaBeginV2(seqBegin, size, shaPrefix)
        val beginAck = sendCommandAndWaitAck(beginPacket, seqBegin, PulseShiftBleProtocol.V2_OPCODE_OTA_BEGIN, 6000L)
        if (beginAck == null || beginAck.statusCode != PulseShiftBleProtocol.STATUS_SUCCESS) {
            val reason = when (beginAck?.statusCode) {
                PulseShiftBleProtocol.STATUS_ERR_INVALID_STATE -> "OTA requires zero engine RPM and at least three seconds without CAN traffic."
                PulseShiftBleProtocol.STATUS_ERR_BUSY -> "An OTA transfer or restart is already pending."
                PulseShiftBleProtocol.STATUS_ERR_UNAUTHORIZED -> "Owner authorization expired. Reconnect and authenticate."
                PulseShiftBleProtocol.STATUS_ERR_STORAGE_ERROR -> "The ESP32 could not open the inactive OTA partition."
                null -> "The ESP32 did not acknowledge the start of the transfer."
                else -> "The ESP32 rejected the start of the transfer (status=${beginAck.statusCode})."
            }
            if (beginAck == null) abortOtaTransfer(dev.address, linkGeneration)
            _otaUpdateState.value = OtaUpdateState.Failed(reason, true)
            return false
        }
        val targetSlot = beginAck.payload.getOrNull(4)?.toInt()
        if (targetSlot == null || targetSlot != expectedSlot) {
            abortOtaTransfer(dev.address, linkGeneration)
            _otaUpdateState.value = OtaUpdateState.Failed("The ESP32 did not identify the expected inactive OTA slot.", false)
            return false
        }

        if (!transferOtaData(imageBytes, targetSlot, dev.address, linkGeneration)) return false

        if (configLinkGeneration.get() != linkGeneration || connectedDevice.value?.address != dev.address ||
            !_isAuthenticated.value) {
            _otaUpdateState.value = OtaUpdateState.Failed("The authenticated BLE connection changed before commit.")
            return false
        }

        // 3. Send OTA END
        val seqEnd = nextSeq()
        val shaTail = shaBytes.copyOfRange(28, 32)
        val endPacket = PulseShiftBleProtocol.buildOtaEndV2(seqEnd, shaTail, image.crc32)
        val endAck = sendCommandAndWaitAck(endPacket, seqEnd, PulseShiftBleProtocol.V2_OPCODE_OTA_END, 10000L)
        if (endAck != null && endAck.statusCode != PulseShiftBleProtocol.STATUS_SUCCESS) {
            val errMsg = when (endAck.statusCode) {
                PulseShiftBleProtocol.STATUS_ERR_OTA_CHECKSUM_ERROR -> "Cryptographic checksum or digital signature mismatch. Image rejected by ESP32."
                PulseShiftBleProtocol.STATUS_ERR_OTA_FLASH_ERROR -> "ESP32 flash partition write error."
                PulseShiftBleProtocol.STATUS_ERR_INVALID_STATE -> "OTA verification stopped. Check that the engine and CAN bus are inactive."
                else -> "OTA End verification rejected (status=${endAck.statusCode})."
            }
            _otaUpdateState.value = OtaUpdateState.Failed(errMsg, true)
            return false
        }
        // A lost END ACK may occur after the ESP32 has selected the boot slot.
        if (endAck != null && endAck.payload.getOrNull(0)?.toInt() != targetSlot) {
            _otaUpdateState.value = OtaUpdateState.Failed("The ESP32 reported an unexpected boot slot. Confirmation withheld.", false)
            return false
        }

        // A reboot destroys the ESP32's connection-bound owner session.
        // Invalidate the phone's cached status even if its disconnect callback
        // arrives late, so a stale "authenticated" flag cannot authorize CONFIRM.
        _isAuthenticated.value = false
        _authState.value = AuthState.SESSION_EXPIRED

        // Prepare BLE manager for reboot: clean up GATT to prevent zombie handles across reboot,
        // and schedule reconnect to pick up the new firmware advertisement.
        bleManager.setAutoReconnectEnabled(true)
        bleManager.prepareForOtaReboot(reconnectDelayMs = 2500L)

        // 4. Device reboot and countdown
        for (sec in 6 downTo 1) {
            _otaUpdateState.value = OtaUpdateState.Rebooting(targetSlot, sec)
            delay(1000L)
        }

        // 5. Await reconnect with new firmware
        var reconnectedDev: BleDevice? = null
        val reconnected = withTimeoutOrNull(35000L) {
            while (true) {
                val currentDev = connectedDevice.value
                if (currentDev != null && currentDev.address == dev.address && currentDev.isConnected &&
                    currentDev.capabilities.hasConfigV2Support) {
                    reconnectedDev = currentDev
                    break
                }
                delay(600L)
            }
            true
        }

        if (reconnected == null || reconnectedDev == null) {
            _otaUpdateState.value = OtaUpdateState.Failed(
                "Device failed to reconnect within 35s after firmware reboot. Verify device power and status.",
                false
            )
            return false
        }

        // 6. Confirm and verify new slot/version
        val postDev = connectedDevice.value
        if (postDev == null || postDev.address != dev.address || !postDev.isConnected) {
            _otaUpdateState.value = OtaUpdateState.Failed("Device disconnected during post-update verification.", false)
            return false
        }

        val postCaps = postDev.capabilities
        if (!postCaps.hasConfigV2Support || !postCaps.hasDualOtaSlots) {
            _otaUpdateState.value = OtaUpdateState.Failed("Reconnected controller does not report dual-slot Protocol V2 capabilities.", false)
            return false
        }

        val runningSlot = postCaps.otaActiveSlot
        if (runningSlot != targetSlot) {
            _otaUpdateState.value = OtaUpdateState.Failed(
                "The ESP32 is running app$runningSlot instead of app$targetSlot. The update did not activate or the controller rolled back.",
                false
            )
            return false
        }

        if (!postCaps.supportsOtaImageDigest) {
            _otaUpdateState.value = OtaUpdateState.Failed(
                "The new firmware cannot report its running image digest. Confirmation withheld.", false
            )
            return false
        }

        val newVer = postCaps.firmwareVersion
        if (newVer.isBlank() || newVer == "unknown") {
            _otaUpdateState.value = OtaUpdateState.Failed("Device did not report a valid firmware version after reboot.", false)
            return false
        }

        // 7. Reauthorize THIS new BLE connection. Connection readiness and
        // capability discovery are read-only; they do not prove owner access.
        // The background reconnect handler also authenticates, and the shared
        // mutex makes an explicit retry safe without concurrent auth commands.
        val postLinkGeneration = configLinkGeneration.get()
        val authorized = withTimeoutOrNull(20000L) {
            if (_isAuthenticated.value) true else authenticateChallengeResponse()
        } == true && _isAuthenticated.value
        if (!authorized) {
            _otaUpdateState.value = OtaUpdateState.Failed(
                "Reconnected, but owner reauthentication failed (${_authState.value}). " +
                    "The update cannot be confirmed; rollback may occur.",
                false
            )
            return false
        }

        // 8-10. Verify bootloader OTA state, confirm, and verify transition to VALID
        return performOtaConfirmationWorkflow(
            previousVersion = previousVersion,
            newVer = newVer,
            runningSlot = runningSlot,
            requireImageDigest = true,
            expectedDigest = image.runningDigest,
            expectedLinkGeneration = postLinkGeneration
        )
    }

    /**
     * Executes the post-reboot bootloader verification and confirmation handshake.
     *
     * 1. Queries initial OTA_STATUS: The image MUST report PENDING_VERIFY (1).
     *    An image already reporting VALID (2) before OTA_CONFIRM was sent is rejected.
     * 2. Sends OTA_CONFIRM.
     * 3. Queries final OTA_STATUS: Requires a successful response reporting VALID (2).
     *    If the OTA_CONFIRM acknowledgment was lost over the air, but final OTA_STATUS
     *    reports VALID (2), this recovers from the lost ACK.
     */
    internal suspend fun performOtaConfirmationWorkflow(
        previousVersion: String,
        newVer: String,
        runningSlot: Int,
        requireImageDigest: Boolean = false,
        expectedDigest: ByteArray? = null,
        expectedLinkGeneration: Long = configLinkGeneration.get()
    ): Boolean {
        // Step 8: Ask the firmware what the bootloader actually thinks.
        val statusSeq = nextSeq()
        val statusAck = sendCommand(
            PulseShiftBleProtocol.buildOtaStatusV2(statusSeq),
            statusSeq,
            PulseShiftBleProtocol.V2_OPCODE_OTA_STATUS,
            5000L
        )
        if (statusAck == null || statusAck.statusCode != PulseShiftBleProtocol.STATUS_SUCCESS ||
            statusAck.payload.size < 8 || statusAck.payload[1].toInt() != runningSlot) {
            _otaUpdateState.value = OtaUpdateState.Failed(
                "Cannot verify bootloader OTA state after reconnect (missing OTA_STATUS response). " +
                    "Refusing to report a successful update.",
                false
            )
            return false
        }
        val initialImageState = statusAck.payload[6].toInt() and 0xFF
        val hasRollbackCandidate = (statusAck.payload[7].toInt() and 0xFF) == 1

        if (initialImageState != 1 || !hasRollbackCandidate) {
            val reason = if (initialImageState == 2) {
                "Unsafe OTA state: image already marked VALID (state=2) before confirmation handshake; expected PENDING_VERIFY=1."
            } else {
                "Unsafe OTA state: image state=$initialImageState (expected PENDING_VERIFY=1), rollback candidate=$hasRollbackCandidate."
            }
            _otaUpdateState.value = OtaUpdateState.Failed(
                "$reason Firmware confirmation withheld.",
                false
            )
            return false
        }

        if (requireImageDigest && (expectedDigest == null || !verifyRunningOtaImage(expectedDigest))) {
            _otaUpdateState.value = OtaUpdateState.Failed(
                "The running ESP32 image did not match the selected file or its digest was unavailable. Confirmation withheld.",
                false
            )
            return false
        }

        // Step 9: Explicitly confirm only after authentication and initial PENDING_VERIFY verification.
        if (configLinkGeneration.get() != expectedLinkGeneration || !_isAuthenticated.value) {
            _otaUpdateState.value = OtaUpdateState.Failed("The owner connection changed. Confirmation withheld.", false)
            return false
        }
        val confirmed = confirmOtaUpdate()

        // Step 10: Mandatory final readback: Query OTA_STATUS to verify the bootloader transitioned to VALID (2).
        // This is strictly required before declaring success, and enables recovery if the OTA_CONFIRM ACK was lost.
        val verifySeq = nextSeq()
        val verifyAck = sendCommand(
            PulseShiftBleProtocol.buildOtaStatusV2(verifySeq),
            verifySeq,
            PulseShiftBleProtocol.V2_OPCODE_OTA_STATUS,
            5000L
        )
        if (verifyAck == null || verifyAck.statusCode != PulseShiftBleProtocol.STATUS_SUCCESS ||
            verifyAck.payload.size < 8 || verifyAck.payload[1].toInt() != runningSlot ||
            configLinkGeneration.get() != expectedLinkGeneration) {
            _otaUpdateState.value = OtaUpdateState.Failed(
                if (!confirmed) {
                    "Post-update confirmation failed: ${lastOtaConfirmError ?: "unknown error"} and final status readback failed. " +
                        "Rollback may occur on a subsequent reboot."
                } else {
                    "Failed to read back final OTA status after confirmation. Refusing to report a successful update."
                },
                false
            )
            return false
        }

        val finalImageState = verifyAck.payload[6].toInt() and 0xFF
        if (finalImageState != 2) {
            _otaUpdateState.value = OtaUpdateState.Failed(
                "OTA confirmation sent but firmware reported image state=$finalImageState (expected VALID=2). " +
                    "Rollback may occur on a subsequent reboot.",
                false
            )
            return false
        }

        _otaUpdateState.value = OtaUpdateState.Success(
            previousVersion = previousVersion,
            newVersion = newVer,
            activeSlot = runningSlot,
            imageIdentityVerified = requireImageDigest
        )
        return true
    }

    private suspend fun verifyRunningOtaImage(expectedDigest: ByteArray): Boolean =
        withTimeoutOrNull(8500L) {
            val actual = ByteArray(32)
            for (index in 0..3) {
                val seq = nextSeq()
                val ack = sendCommand(
                    PulseShiftBleProtocol.buildOtaImageDigestV2(seq, index),
                    seq,
                    PulseShiftBleProtocol.V2_OPCODE_OTA_STATUS,
                    2500L
                )
                if (ack == null || ack.statusCode != PulseShiftBleProtocol.STATUS_SUCCESS ||
                    ack.payload.size != 9 || ack.payload[0].toInt() != index) {
                    return@withTimeoutOrNull false
                }
                ack.payload.copyInto(actual, index * 8, 1)
            }
            expectedDigest.contentEquals(actual)
        } == true

    private suspend fun transferOtaData(
        imageBytes: ByteArray, targetSlot: Int, address: String, linkGeneration: Long
    ): Boolean = coroutineScope {
        val transfer = ActiveOtaTransfer(async(start = CoroutineStart.LAZY) {
            sendOtaData(imageBytes, targetSlot, linkGeneration)
        })
        otaDataTransfer.set(transfer)
        _otaUpdateState.value = OtaUpdateState.Transferring(0L, imageBytes.size.toLong(), 0.0f, targetSlot)
        try {
            val failure = try {
                transfer.sender.await()
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (otaDataTransfer.get() === transfer) throw cancelled
                null
            }

            // Whoever removes the transfer first owns the transition. Once this
            // succeeds here, a late Cancel cannot send ABORT as END is starting.
            if (!otaDataTransfer.compareAndSet(transfer, null)) {
                finishOtaCancellation(transfer, address, linkGeneration)
                return@coroutineScope false
            }
            if (failure != null) {
                withContext(NonCancellable) {
                    _otaUpdateState.value = OtaUpdateState.Cancelling
                    abortOtaTransfer(address, linkGeneration)
                    _otaUpdateState.value = OtaUpdateState.Failed(failure)
                }
                return@coroutineScope false
            }
            _otaUpdateState.value = OtaUpdateState.Verifying(targetSlot)
            true
        } catch (cancelled: CancellationException) {
            otaDataTransfer.compareAndSet(transfer, null)
            if (_otaUpdateState.value is OtaUpdateState.Transferring) {
                finishOtaCancellation(transfer, address, linkGeneration)
            }
            throw cancelled
        } finally {
            otaDataTransfer.compareAndSet(transfer, null)
            transfer.abortResult.complete(false)
        }
    }

    private suspend fun sendOtaData(imageBytes: ByteArray, targetSlot: Int, linkGeneration: Long): String? {
        val mtu = bleManager.currentMtu.value
        val chunkSize = if (mtu >= 64) minOf(mtu - 15, 230) else 11
        val totalChunks = (imageBytes.size + chunkSize - 1) / chunkSize
        var bytesSent = 0L
        for (chunkIdx in 0 until totalChunks) {
            currentCoroutineContext().ensureActive()
            if (configLinkGeneration.get() != linkGeneration || !_isAuthenticated.value) {
                return "The owner connection was lost during transfer."
            }
            val offset = chunkIdx * chunkSize
            val len = minOf(chunkSize, imageBytes.size - offset)
            val chunk = imageBytes.copyOfRange(offset, offset + len)
            val seq = nextSeq()
            val packet = PulseShiftBleProtocol.buildOtaDataV2(seq, chunkIdx, chunk)
            val ack = sendCommandAndWaitAck(packet, seq, PulseShiftBleProtocol.V2_OPCODE_OTA_DATA, 3000L)
            val progress = ack?.payload?.takeIf { it.size == 6 }?.let {
                java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            }
            if (ack?.statusCode != PulseShiftBleProtocol.STATUS_SUCCESS || progress == null ||
                (progress.short.toInt() and 0xFFFF) != (chunkIdx and 0xFFFF) ||
                progress.int.toLong() != bytesSent + len) {
                return if (ack?.statusCode == PulseShiftBleProtocol.STATUS_ERR_INVALID_STATE) {
                    "OTA transfer stopped at chunk $chunkIdx. Check that the engine and CAN bus are inactive."
                } else {
                    "The ESP32 did not confirm the expected write progress at chunk $chunkIdx."
                }
            }
            bytesSent += len
            if (chunkIdx % 25 == 0 || chunkIdx == totalChunks - 1) {
                val size = imageBytes.size.toLong()
                _otaUpdateState.value = OtaUpdateState.Transferring(bytesSent, size, bytesSent * 100f / size, targetSlot)
            }
        }
        return null
    }

    private suspend fun finishOtaCancellation(transfer: ActiveOtaTransfer, address: String, linkGeneration: Long) {
        withContext(NonCancellable) {
            transfer.sender.cancelAndJoin()
            _otaUpdateState.value = OtaUpdateState.Cancelling
            val acknowledged = abortOtaTransfer(address, linkGeneration)
            _otaUpdateState.value = if (acknowledged) OtaUpdateState.Cancelled else
                OtaUpdateState.Failed("Transfer stopped. The ESP32 did not acknowledge cancellation.")
            transfer.abortResult.complete(acknowledged)
        }
    }

    private suspend fun abortOtaTransfer(address: String, linkGeneration: Long): Boolean {
        val device = connectedDevice.value
        if (device?.address != address || !device.isConnected || configLinkGeneration.get() != linkGeneration ||
            connectionState.value !in configConnectionStates) return false
        val seq = nextSeq()
        val abortPkt = PulseShiftBleProtocol.buildOtaAbortV2(seq)
        return sendCommandAndWaitAck(abortPkt, seq, PulseShiftBleProtocol.V2_OPCODE_OTA_ABORT, 2000L)
            ?.statusCode == PulseShiftBleProtocol.STATUS_SUCCESS
    }

    override suspend fun cancelOtaUpdate(): Boolean {
        val transfer = otaDataTransfer.getAndSet(null)
        if (transfer != null) {
            transfer.sender.cancel()
            return transfer.abortResult.await()
        }
        if (!otaUpdateMutex.tryLock()) return false
        try {
            _otaUpdateState.value = OtaUpdateState.Idle
            return true
        } finally {
            otaUpdateMutex.unlock()
        }
    }

    override suspend fun confirmOtaUpdate(): Boolean {
        lastOtaConfirmError = null
        if (!_isAuthenticated.value) {
            lastOtaConfirmError = "owner session is not authorized"
            return false
        }
        // Retry at most once: the first confirmation may have succeeded while
        // its BLE ACK was lost. Firmware confirmation is idempotent for VALID.
        repeat(2) { attempt ->
            val seq = nextSeq()
            val confPkt = PulseShiftBleProtocol.buildOtaConfirmV2(seq)
            val ack = sendCommand(confPkt, seq, PulseShiftBleProtocol.V2_OPCODE_OTA_CONFIRM, 5000L)
            if (ack?.statusCode == PulseShiftBleProtocol.STATUS_SUCCESS) return true
            lastOtaConfirmError = if (ack == null) {
                "no OTA_CONFIRM acknowledgment (attempt ${attempt + 1}/2)"
            } else {
                "ESP32 rejected OTA_CONFIRM with status ${ack.statusCode} (attempt ${attempt + 1}/2)"
            }
        }
        return false
    }
}
