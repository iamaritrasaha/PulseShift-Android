@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.pulseshift.app

import com.pulseshift.app.data.ble.BleConnectionState
import com.pulseshift.app.data.ble.ConfigSnapshotReassembler
import com.pulseshift.app.data.ble.DiagnosticLogReassembler
import com.pulseshift.app.data.ble.PulseShiftBleManager
import com.pulseshift.app.data.ble.PulseShiftBleProtocol
import com.pulseshift.app.data.ble.SignedOtaImage
import com.pulseshift.app.data.local.OwnerCredentialManager
import com.pulseshift.app.data.local.PulseShiftDataStore
import com.pulseshift.app.data.repository.BlePulseShiftRepository
import com.pulseshift.app.domain.model.AuthState
import com.pulseshift.app.domain.model.BleDevice
import com.pulseshift.app.domain.model.ConfigAckResponse
import com.pulseshift.app.domain.model.ConfigSnapshot
import com.pulseshift.app.domain.model.DeviceCommandPhase
import com.pulseshift.app.domain.model.DeviceCapabilities
import com.pulseshift.app.domain.model.OtaUpdateState
import com.pulseshift.app.domain.model.Preset
import com.pulseshift.app.domain.model.PresetApplyResult
import com.pulseshift.app.domain.model.RgbColor
import com.pulseshift.app.domain.model.WarningStyle
import com.pulseshift.app.ui.screens.settings.SettingsViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.zip.CRC32

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BleAuthAndLightingRegressionTest {

    private lateinit var mockBleManager: PulseShiftBleManager
    private lateinit var mockDataStore: PulseShiftDataStore
    private lateinit var mockCredentialManager: OwnerCredentialManager

    private lateinit var connectionStateFlow: MutableStateFlow<BleConnectionState>
    private lateinit var connectedDeviceFlow: MutableStateFlow<BleDevice?>
    private lateinit var configAcksFlow: MutableSharedFlow<ConfigAckResponse>
    private lateinit var configSnapshotsFlow: MutableStateFlow<ConfigSnapshot?>

    private val testDevice = BleDevice("PulseShift-B4E2", "AA:BB:CC:DD:EE:FF", -55)

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)

        mockBleManager = mock()
        mockDataStore = mock()
        mockCredentialManager = mock()

        connectionStateFlow = MutableStateFlow(BleConnectionState.CONNECTED_WAITING_FOR_DATA)
        connectedDeviceFlow = MutableStateFlow(null)
        configAcksFlow = MutableSharedFlow<ConfigAckResponse>(replay = 32, extraBufferCapacity = 32)
        configSnapshotsFlow = MutableStateFlow<ConfigSnapshot?>(null)

        val logReassembler = DiagnosticLogReassembler()
        val snapshotReassembler = ConfigSnapshotReassembler()

        whenever(mockBleManager.logReassembler).thenReturn(logReassembler)
        whenever(mockBleManager.snapshotReassembler).thenReturn(snapshotReassembler)
        whenever(mockBleManager.diagnosticLogMessages).thenReturn(logReassembler.completedMessages)
        whenever(mockBleManager.pageEndEvents).thenReturn(logReassembler.pageEndEvents)

        whenever(mockBleManager.connectionState).thenReturn(connectionStateFlow)
        whenever(mockBleManager.connectedDevice).thenReturn(connectedDeviceFlow)
        whenever(mockBleManager.configAcksV2).thenReturn(configAcksFlow)
        whenever(mockBleManager.configSnapshots).thenReturn(configSnapshotsFlow)
        whenever(mockBleManager.rpmTelemetryState).thenReturn(MutableStateFlow(null))
        whenever(mockBleManager.canHealthState).thenReturn(MutableStateFlow(null))
        whenever(mockBleManager.connectionError).thenReturn(MutableStateFlow(null))
        whenever(mockBleManager.isReconnecting).thenReturn(MutableStateFlow(false))
        whenever(mockBleManager.hasConfigV2Support()).thenReturn(true)
        whenever(mockBleManager.enqueueConfigCommand(any())).thenReturn(true)

        whenever(mockDataStore.autoReconnect).thenReturn(emptyFlow())
        whenever(mockDataStore.customPresetsJson).thenReturn(emptyFlow())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val otaFixtureKey by lazy {
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
    }

    private fun createRepository(): BlePulseShiftRepository {
        return BlePulseShiftRepository(
            bleManager = mockBleManager,
            dataStore = mockDataStore,
            credentialManager = mockCredentialManager,
            validateOtaImage = { SignedOtaImage.validate(it, otaFixtureKey.public) }
        )
    }

    private fun otaPackage(): ByteArray {
        val payload = ByteArray(65_536)
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            .put(0, 0xE9.toByte()).putInt(32, 0xABCD5432.toInt())
        val rawSignature = Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initSign(otaFixtureKey.private)
            update(payload)
            sign()
        }
        val crc = CRC32().apply { update(payload) }
        val trailer = ByteBuffer.allocate(76).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x544F5350).put(1).put(0).putInt(crc.value.toInt()).putShort(64).put(rawSignature)
        return payload + trailer.array()
    }

    private fun prepareOtaDevice() {
        connectedDeviceFlow.value = testDevice.copy(
            isConnected = true,
            capabilities = DeviceCapabilities(
                hasConfigV2Support = true, supportsOtaUpdate = true, otaActiveSlot = 0, otaFlags = 0x06
            )
        )
        val rpmPacket = ByteArray(20).apply {
            this[0] = 1
            this[1] = 0x10 // CAN controller operational, inactive, recording stopped.
            this[8] = 0xFF.toByte()
            this[9] = 0xFF.toByte()
            this[16] = 0xFF.toByte()
            this[17] = 0xFF.toByte()
        }
        whenever(mockBleManager.rpmTelemetryState)
            .thenAnswer { MutableStateFlow(PulseShiftBleProtocol.parseRpmTelemetry(rpmPacket)) }
        whenever(mockBleManager.currentMtu).thenReturn(MutableStateFlow(512))
    }

    private val preflightStatus = byteArrayOf(0, 0, 0, 0, 0, 0, 2, 1)

    private fun otaAck(frame: ByteArray, payload: ByteArray = byteArrayOf()) {
        val sequence = (frame[2].toInt() and 0xff) or ((frame[3].toInt() and 0xff) shl 8)
        check(configAcksFlow.tryEmit(ConfigAckResponse(
            opcode = frame[1], sequenceRef = sequence, statusCode = 0,
            resultFlags = 0, activeRevision = 0, payload = payload
        )))
    }

    @Test
    fun otaRepositoryCancelStopsAnActiveTransfer(): Unit = runBlocking {
        prepareOtaDevice()
        val dataStarted = CompletableDeferred<Unit>()
        val abortSent = CompletableDeferred<Unit>()
        whenever(mockBleManager.enqueueConfigCommand(any())).thenAnswer { invocation ->
            val frame = invocation.getArgument<ByteArray>(0)
            when (frame[1]) {
                PulseShiftBleProtocol.V2_OPCODE_OTA_BEGIN -> otaAck(frame, byteArrayOf(0, 0, 0, 0, 1, 11))
                PulseShiftBleProtocol.V2_OPCODE_OTA_STATUS -> otaAck(frame, preflightStatus)
                PulseShiftBleProtocol.V2_OPCODE_OTA_DATA -> dataStarted.complete(Unit)
                PulseShiftBleProtocol.V2_OPCODE_OTA_ABORT -> {
                    abortSent.complete(Unit)
                    otaAck(frame)
                }
            }
            true
        }
        val repository = createRepository()
        repository.setAuthenticatedForTesting(true)
        val updating = async { repository.startOtaUpdate(otaPackage()) }
        withTimeout(5000) { dataStarted.await() }
        assertTrue("Cancel should succeed while OTA_DATA is waiting for its ACK", repository.cancelOtaUpdate())
        assertTrue(abortSent.isCompleted)
        assertFalse(updating.await())
        assertEquals(OtaUpdateState.Cancelled, repository.otaUpdateState.value)
        verify(mockBleManager, never()).enqueueConfigCommand(argThat { get(1) == PulseShiftBleProtocol.V2_OPCODE_OTA_END })
    }

    @Test
    fun settingsCancelStopsChunksAndShowsAcknowledgedCancellation() = settingsCancelDuringTransfer(true)

    @Test
    fun settingsCancelShowsUnconfirmedOutcomeAfterMissingAbortAck() = settingsCancelDuringTransfer(false)

    private fun settingsCancelDuringTransfer(acknowledged: Boolean): Unit = runBlocking {
        prepareOtaDevice()
        whenever(mockDataStore.themeMode).thenReturn(MutableStateFlow(0))
        whenever(mockDataStore.dynamicColor).thenReturn(MutableStateFlow(false))
        val dataStarted = CompletableDeferred<Unit>()
        val abortFrame = CompletableDeferred<ByteArray>()
        whenever(mockBleManager.enqueueConfigCommand(any())).thenAnswer { invocation ->
            val frame = invocation.getArgument<ByteArray>(0)
            when (frame[1]) {
                PulseShiftBleProtocol.V2_OPCODE_OTA_BEGIN -> otaAck(frame, byteArrayOf(0, 0, 0, 0, 1, 11))
                PulseShiftBleProtocol.V2_OPCODE_OTA_STATUS -> otaAck(frame, preflightStatus)
                PulseShiftBleProtocol.V2_OPCODE_OTA_DATA -> dataStarted.complete(Unit)
                PulseShiftBleProtocol.V2_OPCODE_OTA_ABORT -> abortFrame.complete(frame)
            }
            true
        }
        val repository = createRepository()
        repository.setAuthenticatedForTesting(true)
        val settings = SettingsViewModel(mockDataStore, repository)
        settings.startOtaUpdate(otaPackage())
        withTimeout(5000) { dataStarted.await() }
        assertTrue(settings.otaUpdateState.value is OtaUpdateState.Transferring)

        // SettingsScreen's Cancel button invokes this action.
        settings.cancelOtaUpdate()
        val frame = withTimeout(5000) { abortFrame.await() }
        assertEquals(OtaUpdateState.Cancelling, settings.otaUpdateState.value)
        if (acknowledged) otaAck(frame)
        val outcome = withTimeout(5000) {
            settings.otaUpdateState.first { it is OtaUpdateState.Cancelled || it is OtaUpdateState.Failed }
        }
        if (acknowledged) {
            assertEquals(OtaUpdateState.Cancelled, outcome)
        } else {
            assertTrue((outcome as OtaUpdateState.Failed).reason.contains("did not acknowledge"))
        }
        verify(mockBleManager, times(1)).enqueueConfigCommand(argThat { get(1) == PulseShiftBleProtocol.V2_OPCODE_OTA_DATA })
        verify(mockBleManager, times(1)).enqueueConfigCommand(argThat { get(1) == PulseShiftBleProtocol.V2_OPCODE_OTA_ABORT })
        verify(mockBleManager, never()).enqueueConfigCommand(argThat { get(1) == PulseShiftBleProtocol.V2_OPCODE_OTA_END })
    }

    @Test
    fun cancelAfterEndIsWithheldAndImageWithoutAppendedHashIsConfirmed(): Unit = runBlocking {
        prepareOtaDevice()
        val image = otaPackage()
        val digest = MessageDigest.getInstance("SHA-256").digest(image.copyOf(image.size - 76))
        var bytesWritten = 0
        var confirmed = false
        val endFrame = CompletableDeferred<ByteArray>()
        whenever(mockBleManager.enqueueConfigCommand(any())).thenAnswer { invocation ->
            val frame = invocation.getArgument<ByteArray>(0)
            when (frame[1]) {
                PulseShiftBleProtocol.V2_OPCODE_OTA_BEGIN -> otaAck(frame, byteArrayOf(0, 0, 0, 0, 1, 11))
                PulseShiftBleProtocol.V2_OPCODE_OTA_DATA -> {
                    bytesWritten += frame[8].toInt() and 0xff
                    val progress = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
                        .put(frame[6]).put(frame[7]).putInt(bytesWritten)
                    otaAck(frame, progress.array())
                }
                PulseShiftBleProtocol.V2_OPCODE_OTA_END -> endFrame.complete(frame)
                PulseShiftBleProtocol.V2_OPCODE_OTA_STATUS -> {
                    if (frame[4] == 0.toByte()) {
                        otaAck(frame, if (connectedDeviceFlow.value?.capabilities?.otaActiveSlot == 0)
                            preflightStatus else byteArrayOf(0, 1, 0, 0, 0, 0, if (confirmed) 2 else 1, 1))
                    } else {
                        val part = frame[6].toInt()
                        otaAck(frame, byteArrayOf(part.toByte()) + digest.copyOfRange(part * 8, part * 8 + 8))
                    }
                }
                PulseShiftBleProtocol.V2_OPCODE_OTA_CONFIRM -> {
                    confirmed = true
                    otaAck(frame, byteArrayOf(1))
                }
            }
            true
        }
        val repository = createRepository()
        repository.setAuthenticatedForTesting(true)
        doAnswer {
            val device = checkNotNull(connectedDeviceFlow.value)
            connectedDeviceFlow.value = device.copy(capabilities = device.capabilities.copy(
                firmwareMajor = 2, firmwareMinor = 3, otaActiveSlot = 1, otaFlags = 0x06
            ))
            repository.setAuthenticatedForTesting(true)
            null
        }.whenever(mockBleManager).prepareForOtaReboot(2500L)
        val updating = async { repository.startOtaUpdate(image) }
        val frame = withTimeout(10000) { endFrame.await() }
        assertEquals(image.size, bytesWritten)
        assertTrue(repository.otaUpdateState.value is OtaUpdateState.Verifying)
        assertFalse(repository.cancelOtaUpdate())
        otaAck(frame, byteArrayOf(1))
        assertTrue(withTimeout(15000) { updating.await() })
        assertTrue((repository.otaUpdateState.value as OtaUpdateState.Success).imageIdentityVerified)
        verify(mockBleManager, never()).enqueueConfigCommand(argThat { get(1) == PulseShiftBleProtocol.V2_OPCODE_OTA_ABORT })
    }

    @Test
    fun testUnenrolledEsp32RejectsChallengeWithoutDeletingAndroidKeyStoreCredential() {
        runBlocking {
            // Setup: Android has an enrolled secret for this device MAC
            val dummySecret = ByteArray(12) { 0x42.toByte() }
            whenever(mockCredentialManager.hasEnrolledSecret(testDevice.address)).thenReturn(true)
            whenever(mockCredentialManager.getEnrolledSecret(testDevice.address)).thenReturn(dummySecret)
            connectedDeviceFlow.value = testDevice
            connectionStateFlow.value = BleConnectionState.CONNECTED_WAITING_FOR_DATA

            // Mock enqueueConfigCommand BEFORE repository initialization to avoid concurrent stubbing race
            whenever(mockBleManager.enqueueConfigCommand(any())).thenAnswer { invocation ->
                val cmdBytes = invocation.getArgument<ByteArray>(0)
                val opcode = cmdBytes[1]
                val seq = (cmdBytes[2].toInt() and 0xFF) or ((cmdBytes[3].toInt() and 0xFF) shl 8)

                if (opcode == PulseShiftBleProtocol.V2_OPCODE_REQUEST_CHALLENGE) {
                    val ack = ConfigAckResponse(
                        version = 2,
                        opcode = PulseShiftBleProtocol.V2_OPCODE_REQUEST_CHALLENGE,
                        sequenceRef = seq,
                        statusCode = PulseShiftBleProtocol.STATUS_ERR_INVALID_STATE,
                        resultFlags = 0,
                        activeRevision = 0,
                        payload = byteArrayOf()
                    )
                    configAcksFlow.tryEmit(ack)
                }
                true
            }

            val repository = createRepository()

            val authSuccess = repository.authenticateChallengeResponse()

            // 1. Must return false (authentication not established)
            assertFalse("Authentication must fail when ESP32 has no enrolled owner", authSuccess)

            // 2. KeyStore credential must NOT be automatically deleted without user confirmation
            verify(mockCredentialManager, never()).clearEnrolledSecret(any())

            // 3. AuthState must transition to AUTHENTICATION_REJECTED
            assertEquals(AuthState.AUTHENTICATION_REJECTED, repository.authState.value)
            assertFalse(repository.isAuthenticated.value)
        }
    }

    @Test
    fun testExplicitCredentialClearRequiresUserActionAndClearsKeyStore() {
        runBlocking {
            whenever(mockCredentialManager.hasEnrolledSecret(testDevice.address)).thenReturn(true)
            connectedDeviceFlow.value = testDevice
            connectionStateFlow.value = BleConnectionState.CONNECTED_WAITING_FOR_DATA

            val repository = createRepository()
            repository.setAuthenticatedForTesting(true)
            repository.setNvsConfirmedForTesting(true)

            val cleared = repository.clearStoredOwnerCredential()
            assertTrue("Explicit credential clear must succeed", cleared)

            verify(mockCredentialManager).clearEnrolledSecret(testDevice.address)
            assertEquals(AuthState.NOT_ENROLLED, repository.authState.value)
            assertFalse(repository.isAuthenticated.value)
            assertFalse(repository.isNvsConfirmed.value)
        }
    }

    @Test
    fun testAckTimeoutPreservesStateIfSnapshotConfirmsApplied() {
        runBlocking {
            val dummySecret = ByteArray(12) { 0x55.toByte() }
            whenever(mockCredentialManager.hasEnrolledSecret(testDevice.address)).thenReturn(true)
            whenever(mockCredentialManager.getEnrolledSecret(testDevice.address)).thenReturn(dummySecret)
            connectedDeviceFlow.value = testDevice
            connectionStateFlow.value = BleConnectionState.CONNECTED_WAITING_FOR_DATA

            whenever(mockBleManager.enqueueConfigCommand(any())).thenAnswer { invocation ->
                val cmdBytes = invocation.getArgument<ByteArray>(0)
                val opcode = cmdBytes[1]
                val seq = (cmdBytes[2].toInt() and 0xFF) or ((cmdBytes[3].toInt() and 0xFF) shl 8)

                when (opcode) {
                    PulseShiftBleProtocol.V2_OPCODE_REQUEST_CHALLENGE -> {
                        val nonce = byteArrayOf(1, 2, 3, 4)
                        val ack = ConfigAckResponse(
                            version = 2,
                            opcode = PulseShiftBleProtocol.V2_OPCODE_REQUEST_CHALLENGE,
                            sequenceRef = seq,
                            statusCode = PulseShiftBleProtocol.STATUS_SUCCESS,
                            resultFlags = 0,
                            activeRevision = 0,
                            payload = nonce
                        )
                        configAcksFlow.tryEmit(ack)
                    }
                    PulseShiftBleProtocol.V2_OPCODE_AUTHENTICATE -> {
                        val ack = ConfigAckResponse(
                            version = 2,
                            opcode = PulseShiftBleProtocol.V2_OPCODE_AUTHENTICATE,
                            sequenceRef = seq,
                            statusCode = PulseShiftBleProtocol.STATUS_SUCCESS,
                            resultFlags = 0x01,
                            activeRevision = 1,
                            payload = byteArrayOf()
                        )
                        configAcksFlow.tryEmit(ack)
                    }
                    PulseShiftBleProtocol.V2_OPCODE_SET_POWER -> {
                        // Simulate timeout by dropping ACK response
                    }
                    PulseShiftBleProtocol.V2_OPCODE_GET_SNAPSHOT -> {
                        // Return snapshot showing ESP32 successfully applied power = false
                        val snapshot = ConfigSnapshot(
                            isValid = true,
                            snapshotId = seq,
                            activeRevision = 2,
                            isPowerOn = false,
                            brightness = 200,
                            effect = com.pulseshift.app.domain.model.EffectType.STATIC,
                            effectSpeed = 50,
                            ledColors = List(6) { RgbColor.BrandViolet },
                            ledDirection = 0,
                            startupAnimation = com.pulseshift.app.domain.model.StartupAnimation.SWEEP,
                            startupDurationMs = 2000,
                            rpmThresholds = listOf(3000, 4000, 5000, 6000, 7000, 8000),
                            warningRpm = 8500,
                            warningPatternId = 0,
                            warningColor = RgbColor.RacingRed,
                            rpmMultiplier = 40.0f,
                            canCandidateId = 0x301L,
                            candidateByteIndex = 0,
                            isExtendedFrame = false,
                            calibrationVerified = true
                        )
                        configSnapshotsFlow.value = snapshot
                    }
                }
                true
            }

            val repository = createRepository()
            repository.setAuthenticatedForTesting(true)
            assertTrue(repository.isAuthenticated.value)

            // Power is initially true; we set to false. ACK will time out, but snapshot confirms power = false.
            repository.setPower(false)

            assertFalse("Power state must remain false because snapshot confirmed it was applied", repository.lightingState.value.isPowerOn)
            assertTrue("hasUnsavedChanges must be true since change is in RAM only", repository.hasUnsavedChanges.value)
            assertFalse("isNvsConfirmed must be false until explicit save", repository.isNvsConfirmed.value)
            assertTrue("isHardwareConfirmed must be true from confirmed snapshot", repository.lightingState.value.isHardwareConfirmed)
        }
    }

    @Test
    fun testBleCommandTimeoutWithContradictingSnapshotReconcilesAuthoritativeStateAndReturnsFailure() {
        runBlocking {
            val dummySecret = ByteArray(12) { 0x55.toByte() }
            whenever(mockCredentialManager.hasEnrolledSecret(testDevice.address)).thenReturn(true)
            whenever(mockCredentialManager.getEnrolledSecret(testDevice.address)).thenReturn(dummySecret)
            connectedDeviceFlow.value = testDevice
            connectionStateFlow.value = BleConnectionState.CONNECTED_WAITING_FOR_DATA

            whenever(mockBleManager.enqueueConfigCommand(any())).thenAnswer { invocation ->
                val cmdBytes = invocation.getArgument<ByteArray>(0)
                val opcode = cmdBytes[1]
                val seq = (cmdBytes[2].toInt() and 0xFF) or ((cmdBytes[3].toInt() and 0xFF) shl 8)

                when (opcode) {
                    PulseShiftBleProtocol.V2_OPCODE_REQUEST_CHALLENGE -> {
                        val nonce = byteArrayOf(1, 2, 3, 4)
                        val ack = ConfigAckResponse(
                            version = 2,
                            opcode = PulseShiftBleProtocol.V2_OPCODE_REQUEST_CHALLENGE,
                            sequenceRef = seq,
                            statusCode = PulseShiftBleProtocol.STATUS_SUCCESS,
                            resultFlags = 0,
                            activeRevision = 0,
                            payload = nonce
                        )
                        configAcksFlow.tryEmit(ack)
                    }
                    PulseShiftBleProtocol.V2_OPCODE_AUTHENTICATE -> {
                        val ack = ConfigAckResponse(
                            version = 2,
                            opcode = PulseShiftBleProtocol.V2_OPCODE_AUTHENTICATE,
                            sequenceRef = seq,
                            statusCode = PulseShiftBleProtocol.STATUS_SUCCESS,
                            resultFlags = 0x01,
                            activeRevision = 1,
                            payload = byteArrayOf()
                        )
                        configAcksFlow.tryEmit(ack)
                    }
                    PulseShiftBleProtocol.V2_OPCODE_SET_EFFECT -> {
                        // Drop ACK to simulate timeout and trigger subsequent snapshot query
                    }
                    PulseShiftBleProtocol.V2_OPCODE_GET_SNAPSHOT -> {
                        // Return snapshot showing ESP32 has STATIC effect, contradicting requested BREATHING
                        val snapshot = ConfigSnapshot(
                            isValid = true,
                            snapshotId = seq,
                            activeRevision = 3,
                            isPowerOn = true,
                            brightness = 200,
                            effect = com.pulseshift.app.domain.model.EffectType.STATIC,
                            effectSpeed = 50,
                            ledColors = List(6) { RgbColor.BrandViolet },
                            ledDirection = 0,
                            startupAnimation = com.pulseshift.app.domain.model.StartupAnimation.SWEEP,
                            startupDurationMs = 2000,
                            rpmThresholds = listOf(3000, 4000, 5000, 6000, 7000, 8000),
                            warningRpm = 8500,
                            warningPatternId = 0,
                            warningColor = RgbColor.RacingRed,
                            rpmMultiplier = 40.0f,
                            canCandidateId = 0x301L,
                            candidateByteIndex = 0,
                            isExtendedFrame = false,
                            calibrationVerified = true
                        )
                        configSnapshotsFlow.value = null
                        configSnapshotsFlow.value = snapshot
                    }
                }
                true
            }

            val repository = createRepository()
            repository.setAuthenticatedForTesting(true)
            assertFalse("Precondition: hasUnsavedChanges initially false", repository.hasUnsavedChanges.value)

            // Request BREATHING effect. ACK times out, snapshot returns STATIC (contradicting requested change).
            val success = repository.setEffect(com.pulseshift.app.domain.model.EffectType.BREATHING)

            // CRITICAL VERIFICATIONS:
            // 1. Operation must return FALSE, NOT true!
            assertFalse("setEffect must return FALSE when controller snapshot contradicts requested setting", success)

            // 2. UI state must be reconciled to the controller's actual authoritative state (STATIC)
            assertEquals("Effect must be reconciled to controller snapshot (STATIC)", com.pulseshift.app.domain.model.EffectType.STATIC, repository.lightingState.value.effect)
            assertTrue("State must be hardware confirmed from authoritative snapshot", repository.lightingState.value.isHardwareConfirmed)
            assertFalse("isSyncUnknown must be false since snapshot succeeded", repository.lightingState.value.isSyncUnknown)
            assertFalse("isPendingSync must be cleared", repository.lightingState.value.isPendingSync)

            // 3. hasUnsavedChanges must NOT be marked true because the unapplied mutation was rejected
            assertFalse("hasUnsavedChanges must remain FALSE when change was not applied by controller", repository.hasUnsavedChanges.value)
            assertFalse("isNvsConfirmed must be false", repository.isNvsConfirmed.value)
        }
    }

    @Test
    fun testUnauthenticatedLightingWriteDoesNotMutateStateOrSetUnsaved() {
        runBlocking {
            whenever(mockCredentialManager.hasEnrolledSecret(testDevice.address)).thenReturn(false)
            connectedDeviceFlow.value = testDevice
            connectionStateFlow.value = BleConnectionState.CONNECTED_WAITING_FOR_DATA

            val repository = createRepository()

            // Verify pre-conditions
            assertFalse("Repository must start unauthenticated", repository.isAuthenticated.value)
            assertFalse("Unsaved changes must start false", repository.hasUnsavedChanges.value)
            val initialPower = repository.lightingState.value.isPowerOn
            val initialBrightness = repository.lightingState.value.brightness

            // Attempt mutating commands when unauthenticated
            repository.setPower(!initialPower)
            repository.setBrightness(initialBrightness + 10)
            repository.setGlobalColor(RgbColor(255, 0, 0))

            // State must remain unmutated (no premature optimistic update)
            assertEquals("Power must remain unmodified", initialPower, repository.lightingState.value.isPowerOn)
            assertEquals("Brightness must remain unmodified", initialBrightness, repository.lightingState.value.brightness)
            assertFalse("hasUnsavedChanges must NEVER be set when unauthenticated", repository.hasUnsavedChanges.value)

            // Protected lighting commands must NOT be enqueued to BLE stack
            verify(mockBleManager, never()).enqueueConfigCommand(argThat {
                this.size >= 2 && this[1] == PulseShiftBleProtocol.V2_OPCODE_SET_POWER
            })
            verify(mockBleManager, never()).enqueueConfigCommand(argThat {
                this.size >= 2 && this[1] == PulseShiftBleProtocol.V2_OPCODE_SET_BRIGHTNESS
            })
            verify(mockBleManager, never()).enqueueConfigCommand(argThat {
                this.size >= 2 && this[1] == PulseShiftBleProtocol.V2_OPCODE_SET_GLOBAL_RGB
            })
        }
    }

    @Test
    fun testUnauthenticatedApplyPresetRejectedWithoutStateCorruption() {
        runBlocking {
            whenever(mockCredentialManager.hasEnrolledSecret(testDevice.address)).thenReturn(false)
            connectedDeviceFlow.value = testDevice
            connectionStateFlow.value = BleConnectionState.CONNECTED_WAITING_FOR_DATA

            val repository = createRepository()
            assertFalse(repository.isAuthenticated.value)

            val targetPreset = Preset.DefaultPresets.first()
            val initialEffect = repository.lightingState.value.effect

            // Attempt applyPreset without authentication - must fail honestly
            try {
                repository.applyPreset(targetPreset)
                fail("applyPreset must throw IllegalStateException when unauthenticated")
            } catch (e: IllegalStateException) {
                assertTrue("Error message must mention owner authentication", e.message?.contains("Owner authentication required") == true)
            }

            assertEquals("Effect must remain unmodified", initialEffect, repository.lightingState.value.effect)
            assertFalse("hasUnsavedChanges must remain false", repository.hasUnsavedChanges.value)
        }
    }

    @Test
    fun testUnauthenticatedSaveLightingRejectedImmediately() {
        runBlocking {
            whenever(mockCredentialManager.hasEnrolledSecret(testDevice.address)).thenReturn(false)
            connectedDeviceFlow.value = testDevice
            connectionStateFlow.value = BleConnectionState.CONNECTED_WAITING_FOR_DATA

            val repository = createRepository()
            assertFalse(repository.isAuthenticated.value)

            val saveResult = repository.saveConfigurationToController()

            assertFalse("Save must fail when unauthenticated", saveResult)
            // Verify no transaction commands were ever enqueued
            verify(mockBleManager, never()).enqueueConfigCommand(argThat {
                this.size >= 2 && (this[1] == PulseShiftBleProtocol.V2_OPCODE_BEGIN_TRANSACTION || this[1] == PulseShiftBleProtocol.V2_OPCODE_SAVE_CONFIG)
            })
        }
    }

    @Test
    fun testAuthenticatedCommandFailureTriggersAtomicStateRollback() {
        runBlocking {
            val dummySecret = ByteArray(12) { 0x55.toByte() }
            whenever(mockCredentialManager.hasEnrolledSecret(testDevice.address)).thenReturn(true)
            whenever(mockCredentialManager.getEnrolledSecret(testDevice.address)).thenReturn(dummySecret)
            connectedDeviceFlow.value = testDevice
            connectionStateFlow.value = BleConnectionState.CONNECTED_WAITING_FOR_DATA

            var rejectNextCommand = false

            whenever(mockBleManager.enqueueConfigCommand(any())).thenAnswer { invocation ->
                val cmdBytes = invocation.getArgument<ByteArray>(0)
                val opcode = cmdBytes[1]
                val seq = (cmdBytes[2].toInt() and 0xFF) or ((cmdBytes[3].toInt() and 0xFF) shl 8)

                when (opcode) {
                    PulseShiftBleProtocol.V2_OPCODE_REQUEST_CHALLENGE -> {
                        val nonce = byteArrayOf(1, 2, 3, 4)
                        val ack = ConfigAckResponse(
                            version = 2,
                            opcode = PulseShiftBleProtocol.V2_OPCODE_REQUEST_CHALLENGE,
                            sequenceRef = seq,
                            statusCode = PulseShiftBleProtocol.STATUS_SUCCESS,
                            resultFlags = 0,
                            activeRevision = 0,
                            payload = nonce
                        )
                        configAcksFlow.tryEmit(ack)
                    }
                    PulseShiftBleProtocol.V2_OPCODE_AUTHENTICATE -> {
                        val ack = ConfigAckResponse(
                            version = 2,
                            opcode = PulseShiftBleProtocol.V2_OPCODE_AUTHENTICATE,
                            sequenceRef = seq,
                            statusCode = PulseShiftBleProtocol.STATUS_SUCCESS,
                            resultFlags = 0x01,
                            activeRevision = 1,
                            payload = byteArrayOf()
                        )
                        configAcksFlow.tryEmit(ack)
                    }
                    PulseShiftBleProtocol.V2_OPCODE_SET_POWER -> {
                        if (rejectNextCommand) {
                            // Controller explicitly rejects command with STATUS_ERR_INVALID_PARAM
                            val ack = ConfigAckResponse(
                                version = 2,
                                opcode = PulseShiftBleProtocol.V2_OPCODE_SET_POWER,
                                sequenceRef = seq,
                                statusCode = PulseShiftBleProtocol.STATUS_ERR_INVALID_PARAM,
                                resultFlags = 0,
                                activeRevision = 1,
                                payload = byteArrayOf()
                            )
                            configAcksFlow.tryEmit(ack)
                        } else {
                            val ack = ConfigAckResponse(
                                version = 2,
                                opcode = PulseShiftBleProtocol.V2_OPCODE_SET_POWER,
                                sequenceRef = seq,
                                statusCode = PulseShiftBleProtocol.STATUS_SUCCESS,
                                resultFlags = 0x01,
                                activeRevision = 2,
                                payload = byteArrayOf()
                            )
                            configAcksFlow.tryEmit(ack)
                        }
                    }
                }
                true
            }

            val repository = createRepository()
            repository.setAuthenticatedForTesting(true)
            assertTrue(repository.isAuthenticated.value)

            // Simulate explicit command rejection by the ESP32 controller
            rejectNextCommand = true

            val previousPower = repository.lightingState.value.isPowerOn

            // Mutate power
            repository.setPower(!previousPower)

            // Power must atomically roll back to previous state on explicit command rejection
            assertEquals("Power must atomically roll back on command failure", previousPower, repository.lightingState.value.isPowerOn)
            assertFalse("hasUnsavedChanges must roll back to false", repository.hasUnsavedChanges.value)
            assertFalse("isSyncUnknown must be false on explicit rejection", repository.lightingState.value.isSyncUnknown)
        }
    }

    @Test
    fun testEnrollOwnerDoesNotMarkLightingNvsConfirmed() {
        runBlocking {
            val secret = ByteArray(12) { 0x33.toByte() }
            whenever(mockCredentialManager.saveEnrolledSecret(testDevice.address, secret)).thenReturn(true)
            connectedDeviceFlow.value = testDevice
            connectionStateFlow.value = BleConnectionState.CONNECTED_WAITING_FOR_DATA

            whenever(mockBleManager.enqueueConfigCommand(any())).thenAnswer { invocation ->
                val cmdBytes = invocation.getArgument<ByteArray>(0)
                val opcode = cmdBytes[1]
                val seq = (cmdBytes[2].toInt() and 0xFF) or ((cmdBytes[3].toInt() and 0xFF) shl 8)

                if (opcode == PulseShiftBleProtocol.V2_OPCODE_ENROLL_OWNER) {
                    val ack = ConfigAckResponse(
                        version = 2,
                        opcode = PulseShiftBleProtocol.V2_OPCODE_ENROLL_OWNER,
                        sequenceRef = seq,
                        statusCode = PulseShiftBleProtocol.STATUS_SUCCESS,
                        resultFlags = 0x03, // success + persisted in ps_sec
                        activeRevision = 1,
                        payload = byteArrayOf()
                    )
                    configAcksFlow.tryEmit(ack)
                }
                true
            }

            val repository = createRepository()
            val enrolled = repository.enrollOwner(secret)
            assertTrue("Enrollment must succeed", enrolled)
            assertTrue("Owner session must be authenticated", repository.isAuthenticated.value)

            // CRITICAL VERIFICATION: Enrolling owner credentials must NOT mark lighting NVS confirmed!
            assertFalse("Lighting NVS confirmed must remain FALSE after owner enrollment", repository.isNvsConfirmed.value)
        }
    }

    @Test
    fun testReloadConfigFromControllerVerifiesHardwareRamWithoutMarkingNvsConfirmed() {
        runBlocking {
            connectedDeviceFlow.value = testDevice
            connectionStateFlow.value = BleConnectionState.CONNECTED_WAITING_FOR_DATA

            whenever(mockBleManager.enqueueConfigCommand(any())).thenAnswer { invocation ->
                val cmdBytes = invocation.getArgument<ByteArray>(0)
                val opcode = cmdBytes[1]
                val seq = (cmdBytes[2].toInt() and 0xFF) or ((cmdBytes[3].toInt() and 0xFF) shl 8)

                if (opcode == PulseShiftBleProtocol.V2_OPCODE_GET_SNAPSHOT) {
                    val snapshot = ConfigSnapshot(
                        isValid = true,
                        snapshotId = seq,
                        activeRevision = 5,
                        isPowerOn = true,
                        brightness = 175,
                        effect = com.pulseshift.app.domain.model.EffectType.BREATHING,
                        effectSpeed = 60,
                        ledColors = List(6) { RgbColor.NeonGreen },
                        ledDirection = 0,
                        startupAnimation = com.pulseshift.app.domain.model.StartupAnimation.PULSE,
                        startupDurationMs = 1500,
                        rpmThresholds = listOf(2000, 3000, 4000, 5000, 6000, 7000),
                        warningRpm = 7500,
                        warningPatternId = 1,
                        warningColor = RgbColor.RacingRed,
                        rpmMultiplier = 40.0f,
                        canCandidateId = 0x301L,
                        candidateByteIndex = 0,
                        isExtendedFrame = false,
                        calibrationVerified = true
                    )
                    configSnapshotsFlow.value = snapshot
                }
                true
            }

            val repository = createRepository()
            repository.setAuthenticatedForTesting(true)

            val snapshot = repository.reloadConfigFromController()
            assertNotNull("Snapshot must be returned", snapshot)
            assertEquals(175, repository.lightingState.value.brightness)
            assertEquals(com.pulseshift.app.domain.model.EffectType.BREATHING, repository.lightingState.value.effect)
            assertTrue("RAM state must be hardware confirmed", repository.lightingState.value.isHardwareConfirmed)
            assertFalse("Sync state must not be unknown", repository.lightingState.value.isSyncUnknown)

            // CRITICAL VERIFICATION: RAM snapshot from controller must NOT set isNvsConfirmed!
            assertFalse("RAM snapshot must NOT establish NVS persistence proof", repository.isNvsConfirmed.value)
        }
    }

    @Test
    fun testSaveConfigurationToControllerCorrectlyEstablishesNvsPersistenceAndMutationsClearIt() {
        runBlocking {
            connectedDeviceFlow.value = testDevice
            connectionStateFlow.value = BleConnectionState.CONNECTED_WAITING_FOR_DATA

            whenever(mockBleManager.enqueueConfigCommand(any())).thenAnswer { invocation ->
                val cmdBytes = invocation.getArgument<ByteArray>(0)
                val opcode = cmdBytes[1]
                val seq = (cmdBytes[2].toInt() and 0xFF) or ((cmdBytes[3].toInt() and 0xFF) shl 8)

                when (opcode) {
                    PulseShiftBleProtocol.V2_OPCODE_SAVE_CONFIG -> {
                        val ack = ConfigAckResponse(
                            version = 2,
                            opcode = PulseShiftBleProtocol.V2_OPCODE_SAVE_CONFIG,
                            sequenceRef = seq,
                            statusCode = PulseShiftBleProtocol.STATUS_SUCCESS,
                            resultFlags = 0x03, // success + persisted
                            activeRevision = 6,
                            payload = byteArrayOf()
                        )
                        configAcksFlow.tryEmit(ack)
                    }
                    PulseShiftBleProtocol.V2_OPCODE_SET_POWER -> {
                        val ack = ConfigAckResponse(
                            version = 2,
                            opcode = PulseShiftBleProtocol.V2_OPCODE_SET_POWER,
                            sequenceRef = seq,
                            statusCode = PulseShiftBleProtocol.STATUS_SUCCESS,
                            resultFlags = 0x01, // success (in RAM)
                            activeRevision = 7,
                            payload = byteArrayOf()
                        )
                        configAcksFlow.tryEmit(ack)
                    }
                    PulseShiftBleProtocol.V2_OPCODE_GET_SNAPSHOT -> {
                        val snapshot = ConfigSnapshot(
                            isValid = true,
                            snapshotId = seq,
                            activeRevision = 6,
                            isPowerOn = true,
                            brightness = 200,
                            effect = com.pulseshift.app.domain.model.EffectType.STATIC,
                            effectSpeed = 50,
                            ledColors = List(6) { RgbColor.BrandViolet },
                            ledDirection = 0,
                            startupAnimation = com.pulseshift.app.domain.model.StartupAnimation.SWEEP,
                            startupDurationMs = 2000,
                            rpmThresholds = listOf(3000, 4000, 5000, 6000, 7000, 8000),
                            warningRpm = 8500,
                            warningPatternId = 0,
                            warningColor = RgbColor.RacingRed,
                            rpmMultiplier = 40.0f,
                            canCandidateId = 0x301L,
                            candidateByteIndex = 0,
                            isExtendedFrame = false,
                            calibrationVerified = true
                        )
                        configSnapshotsFlow.value = null
                        configSnapshotsFlow.value = snapshot
                    }
                }
                true
            }

            val repository = createRepository()
            repository.setAuthenticatedForTesting(true)

            // Initially false
            assertFalse(repository.isNvsConfirmed.value)

            // Execute explicit save to flash
            val saved = repository.saveConfigurationToController()
            assertTrue("Save must succeed", saved)
            assertTrue("isNvsConfirmed must be TRUE after explicit successful SAVE_CONFIG", repository.isNvsConfirmed.value)
            assertFalse("hasUnsavedChanges must be FALSE after save", repository.hasUnsavedChanges.value)

            // Mutate power in volatile RAM
            repository.setPower(false)
            assertFalse("isNvsConfirmed must transition back to FALSE after live volatile mutation", repository.isNvsConfirmed.value)
            assertTrue("hasUnsavedChanges must transition to TRUE after mutation", repository.hasUnsavedChanges.value)
        }
    }

    @Test
    fun testBleCommandTimeoutWithFailedSnapshotTransitionsToSyncUnknownWithoutRestoringOldState() {
        runBlocking {
            val dummySecret = ByteArray(12) { 0x55.toByte() }
            whenever(mockCredentialManager.hasEnrolledSecret(testDevice.address)).thenReturn(true)
            whenever(mockCredentialManager.getEnrolledSecret(testDevice.address)).thenReturn(dummySecret)
            connectedDeviceFlow.value = testDevice
            connectionStateFlow.value = BleConnectionState.CONNECTED_WAITING_FOR_DATA

            whenever(mockBleManager.enqueueConfigCommand(any())).thenAnswer { invocation ->
                val cmdBytes = invocation.getArgument<ByteArray>(0)
                val opcode = cmdBytes[1]
                val seq = (cmdBytes[2].toInt() and 0xFF) or ((cmdBytes[3].toInt() and 0xFF) shl 8)

                when (opcode) {
                    PulseShiftBleProtocol.V2_OPCODE_REQUEST_CHALLENGE -> {
                        val nonce = byteArrayOf(1, 2, 3, 4)
                        val ack = ConfigAckResponse(
                            version = 2,
                            opcode = PulseShiftBleProtocol.V2_OPCODE_REQUEST_CHALLENGE,
                            sequenceRef = seq,
                            statusCode = PulseShiftBleProtocol.STATUS_SUCCESS,
                            resultFlags = 0,
                            activeRevision = 0,
                            payload = nonce
                        )
                        configAcksFlow.tryEmit(ack)
                    }
                    PulseShiftBleProtocol.V2_OPCODE_AUTHENTICATE -> {
                        val ack = ConfigAckResponse(
                            version = 2,
                            opcode = PulseShiftBleProtocol.V2_OPCODE_AUTHENTICATE,
                            sequenceRef = seq,
                            statusCode = PulseShiftBleProtocol.STATUS_SUCCESS,
                            resultFlags = 0x01,
                            activeRevision = 1,
                            payload = byteArrayOf()
                        )
                        configAcksFlow.tryEmit(ack)
                    }
                    PulseShiftBleProtocol.V2_OPCODE_SET_POWER,
                    PulseShiftBleProtocol.V2_OPCODE_GET_SNAPSHOT -> {
                        // Drop both SET_POWER and subsequent GET_SNAPSHOT commands, simulating RF blackout
                    }
                }
                true
            }

            val repository = createRepository()
            repository.setAuthenticatedForTesting(true)
            val initialPower = repository.lightingState.value.isPowerOn // true

            // Execute setPower(false)
            repository.setPower(!initialPower)

            // MUST transition to isSyncUnknown = true
            assertTrue("isSyncUnknown must be TRUE when command times out and snapshot query fails", repository.lightingState.value.isSyncUnknown)
            assertFalse("isHardwareConfirmed must be FALSE", repository.lightingState.value.isHardwareConfirmed)
            assertFalse("isPendingSync must be cleared", repository.lightingState.value.isPendingSync)

            // MUST NOT have restored old state!
            assertEquals("Power state must NOT roll back to old state because controller may have applied it", !initialPower, repository.lightingState.value.isPowerOn)
            assertTrue("hasUnsavedChanges must remain true", repository.hasUnsavedChanges.value)
            assertFalse("isNvsConfirmed must be false", repository.isNvsConfirmed.value)
        }
    }

    @Test
    fun testMultiCommandColorPartialFailureReconcilesControllerStateFromSnapshot() {
        runBlocking {
            val dummySecret = ByteArray(12) { 0x55.toByte() }
            whenever(mockCredentialManager.hasEnrolledSecret(testDevice.address)).thenReturn(true)
            whenever(mockCredentialManager.getEnrolledSecret(testDevice.address)).thenReturn(dummySecret)
            connectedDeviceFlow.value = testDevice
            connectionStateFlow.value = BleConnectionState.CONNECTED_WAITING_FOR_DATA

            val targetColors = listOf(
                RgbColor(255, 0, 0),
                RgbColor(0, 255, 0),
                RgbColor(0, 0, 255),
                RgbColor(255, 255, 0),
                RgbColor(255, 0, 255),
                RgbColor(0, 255, 255)
            )

            // Controller state reflecting that only LED 0 and 1 were updated before connection stalled
            val controllerAppliedColors = listOf(
                targetColors[0],
                targetColors[1],
                RgbColor.BrandViolet,
                RgbColor.BrandViolet,
                RgbColor.BrandViolet,
                RgbColor.BrandViolet
            )

            whenever(mockBleManager.enqueueConfigCommand(any())).thenAnswer { invocation ->
                val cmdBytes = invocation.getArgument<ByteArray>(0)
                val opcode = cmdBytes[1]
                val seq = (cmdBytes[2].toInt() and 0xFF) or ((cmdBytes[3].toInt() and 0xFF) shl 8)

                when (opcode) {
                    PulseShiftBleProtocol.V2_OPCODE_REQUEST_CHALLENGE -> {
                        val nonce = byteArrayOf(1, 2, 3, 4)
                        val ack = ConfigAckResponse(
                            version = 2,
                            opcode = PulseShiftBleProtocol.V2_OPCODE_REQUEST_CHALLENGE,
                            sequenceRef = seq,
                            statusCode = PulseShiftBleProtocol.STATUS_SUCCESS,
                            resultFlags = 0,
                            activeRevision = 0,
                            payload = nonce
                        )
                        configAcksFlow.tryEmit(ack)
                    }
                    PulseShiftBleProtocol.V2_OPCODE_AUTHENTICATE -> {
                        val ack = ConfigAckResponse(
                            version = 2,
                            opcode = PulseShiftBleProtocol.V2_OPCODE_AUTHENTICATE,
                            sequenceRef = seq,
                            statusCode = PulseShiftBleProtocol.STATUS_SUCCESS,
                            resultFlags = 0x01,
                            activeRevision = 1,
                            payload = byteArrayOf()
                        )
                        configAcksFlow.tryEmit(ack)
                    }
                    PulseShiftBleProtocol.V2_OPCODE_SET_LED_RGB -> {
                        val ledIndex = cmdBytes[6].toInt() and 0xFF
                        if (ledIndex < 2) {
                            // LEDs 0 and 1 succeed
                            val ack = ConfigAckResponse(
                                version = 2,
                                opcode = PulseShiftBleProtocol.V2_OPCODE_SET_LED_RGB,
                                sequenceRef = seq,
                                statusCode = PulseShiftBleProtocol.STATUS_SUCCESS,
                                resultFlags = 0x01,
                                activeRevision = (3 + ledIndex).toLong(),
                                payload = byteArrayOf()
                            )
                            configAcksFlow.tryEmit(ack)
                        } else {
                            // LEDs 2..5 fail
                            val ack = ConfigAckResponse(
                                version = 2,
                                opcode = PulseShiftBleProtocol.V2_OPCODE_SET_LED_RGB,
                                sequenceRef = seq,
                                statusCode = PulseShiftBleProtocol.STATUS_ERR_INVALID_PARAM,
                                resultFlags = 0,
                                activeRevision = 4,
                                payload = byteArrayOf()
                            )
                            configAcksFlow.tryEmit(ack)
                        }
                    }
                    PulseShiftBleProtocol.V2_OPCODE_GET_SNAPSHOT -> {
                        val snapshot = ConfigSnapshot(
                            isValid = true,
                            snapshotId = seq,
                            activeRevision = 4,
                            isPowerOn = true,
                            brightness = 200,
                            effect = com.pulseshift.app.domain.model.EffectType.STATIC,
                            effectSpeed = 50,
                            ledColors = controllerAppliedColors,
                            ledDirection = 0,
                            startupAnimation = com.pulseshift.app.domain.model.StartupAnimation.SWEEP,
                            startupDurationMs = 2000,
                            rpmThresholds = listOf(3000, 4000, 5000, 6000, 7000, 8000),
                            warningRpm = 8500,
                            warningPatternId = 0,
                            warningColor = RgbColor.RacingRed,
                            rpmMultiplier = 40.0f,
                            canCandidateId = 0x301L,
                            candidateByteIndex = 0,
                            isExtendedFrame = false,
                            calibrationVerified = true
                        )
                        configSnapshotsFlow.value = snapshot
                    }
                }
                true
            }

            val repository = createRepository()
            repository.setAuthenticatedForTesting(true)

            repository.setAllLedColors(targetColors)

            // CRITICAL VERIFICATION: Controller state is reconciled from snapshot, not a blanket rollback!
            assertEquals("LED 0 must match controller applied color", targetColors[0], repository.lightingState.value.ledColors[0])
            assertEquals("LED 1 must match controller applied color", targetColors[1], repository.lightingState.value.ledColors[1])
            assertEquals("LED 2 must match controller actual color", RgbColor.BrandViolet, repository.lightingState.value.ledColors[2])
            assertTrue("State must be hardware confirmed from authoritative snapshot", repository.lightingState.value.isHardwareConfirmed)
            assertFalse("isSyncUnknown must be false since snapshot succeeded", repository.lightingState.value.isSyncUnknown)
            assertTrue("hasUnsavedChanges must be true", repository.hasUnsavedChanges.value)
            assertFalse("isNvsConfirmed must be false", repository.isNvsConfirmed.value)
        }
    }

    @Test
    fun testMultiCommandColorPartialFailureWithFailedSnapshotPreservesAcknowledgedLedsAndSetsSyncUnknown() {
        runBlocking {
            val dummySecret = ByteArray(12) { 0x55.toByte() }
            whenever(mockCredentialManager.hasEnrolledSecret(testDevice.address)).thenReturn(true)
            whenever(mockCredentialManager.getEnrolledSecret(testDevice.address)).thenReturn(dummySecret)
            connectedDeviceFlow.value = testDevice
            connectionStateFlow.value = BleConnectionState.CONNECTED_WAITING_FOR_DATA

            val targetColors = listOf(
                RgbColor(11, 11, 11),
                RgbColor(22, 22, 22),
                RgbColor(33, 33, 33),
                RgbColor(44, 44, 44),
                RgbColor(55, 55, 55),
                RgbColor(66, 66, 66)
            )

            whenever(mockBleManager.enqueueConfigCommand(any())).thenAnswer { invocation ->
                val cmdBytes = invocation.getArgument<ByteArray>(0)
                val opcode = cmdBytes[1]
                val seq = (cmdBytes[2].toInt() and 0xFF) or ((cmdBytes[3].toInt() and 0xFF) shl 8)

                when (opcode) {
                    PulseShiftBleProtocol.V2_OPCODE_REQUEST_CHALLENGE -> {
                        val nonce = byteArrayOf(1, 2, 3, 4)
                        val ack = ConfigAckResponse(
                            version = 2,
                            opcode = PulseShiftBleProtocol.V2_OPCODE_REQUEST_CHALLENGE,
                            sequenceRef = seq,
                            statusCode = PulseShiftBleProtocol.STATUS_SUCCESS,
                            resultFlags = 0,
                            activeRevision = 0,
                            payload = nonce
                        )
                        configAcksFlow.tryEmit(ack)
                    }
                    PulseShiftBleProtocol.V2_OPCODE_AUTHENTICATE -> {
                        val ack = ConfigAckResponse(
                            version = 2,
                            opcode = PulseShiftBleProtocol.V2_OPCODE_AUTHENTICATE,
                            sequenceRef = seq,
                            statusCode = PulseShiftBleProtocol.STATUS_SUCCESS,
                            resultFlags = 0x01,
                            activeRevision = 1,
                            payload = byteArrayOf()
                        )
                        configAcksFlow.tryEmit(ack)
                    }
                    PulseShiftBleProtocol.V2_OPCODE_SET_LED_RGB -> {
                        val ledIndex = cmdBytes[6].toInt() and 0xFF
                        if (ledIndex < 2) {
                            // Only LEDs 0 and 1 succeed
                            val ack = ConfigAckResponse(
                                version = 2,
                                opcode = PulseShiftBleProtocol.V2_OPCODE_SET_LED_RGB,
                                sequenceRef = seq,
                                statusCode = PulseShiftBleProtocol.STATUS_SUCCESS,
                                resultFlags = 0x01,
                                activeRevision = 5,
                                payload = byteArrayOf()
                            )
                            configAcksFlow.tryEmit(ack)
                        } else {
                            // LEDs 2..5 fail
                            val ack = ConfigAckResponse(
                                version = 2,
                                opcode = PulseShiftBleProtocol.V2_OPCODE_SET_LED_RGB,
                                sequenceRef = seq,
                                statusCode = PulseShiftBleProtocol.STATUS_ERR_INVALID_PARAM,
                                resultFlags = 0,
                                activeRevision = 5,
                                payload = byteArrayOf()
                            )
                            configAcksFlow.tryEmit(ack)
                        }
                    }
                }
                // GET_SNAPSHOT fails/times out (no response)
                true
            }

            val repository = createRepository()
            repository.setAuthenticatedForTesting(true)

            repository.setAllLedColors(targetColors)

            // CRITICAL VERIFICATION: Acknowledged LEDs are retained, not blanket rolled back, and sync is unknown
            assertEquals("Acknowledged LED 0 must be preserved", targetColors[0], repository.lightingState.value.ledColors[0])
            assertEquals("Acknowledged LED 1 must be preserved", targetColors[1], repository.lightingState.value.ledColors[1])
            assertEquals("Unacknowledged LED 2 must retain previous color", RgbColor.BrandViolet, repository.lightingState.value.ledColors[2])
            assertTrue("isSyncUnknown must be TRUE when partial failure occurs and snapshot is unavailable", repository.lightingState.value.isSyncUnknown)
            assertFalse("isHardwareConfirmed must be FALSE", repository.lightingState.value.isHardwareConfirmed)
            assertTrue("hasUnsavedChanges must be TRUE", repository.hasUnsavedChanges.value)
            assertFalse("isNvsConfirmed must be FALSE", repository.isNvsConfirmed.value)
        }
    }

    @Test
    fun testRepositoryNvsPersistenceLifecycleUnderAuthSnapshotsAndMutations() {
        runBlocking {
            val dummySecret = ByteArray(12) { 0x55.toByte() }
            whenever(mockCredentialManager.hasEnrolledSecret(testDevice.address)).thenReturn(true)
            whenever(mockCredentialManager.getEnrolledSecret(testDevice.address)).thenReturn(dummySecret)
            connectedDeviceFlow.value = testDevice
            connectionStateFlow.value = BleConnectionState.CONNECTED_WAITING_FOR_DATA

            whenever(mockBleManager.enqueueConfigCommand(any())).thenAnswer { invocation ->
                val cmdBytes = invocation.getArgument<ByteArray>(0)
                val opcode = cmdBytes[1]
                val seq = (cmdBytes[2].toInt() and 0xFF) or ((cmdBytes[3].toInt() and 0xFF) shl 8)

                when (opcode) {
                    PulseShiftBleProtocol.V2_OPCODE_REQUEST_CHALLENGE -> {
                        val nonce = byteArrayOf(1, 2, 3, 4)
                        val ack = ConfigAckResponse(
                            version = 2,
                            opcode = PulseShiftBleProtocol.V2_OPCODE_REQUEST_CHALLENGE,
                            sequenceRef = seq,
                            statusCode = PulseShiftBleProtocol.STATUS_SUCCESS,
                            resultFlags = 0,
                            activeRevision = 0,
                            payload = nonce
                        )
                        configAcksFlow.tryEmit(ack)
                    }
                    PulseShiftBleProtocol.V2_OPCODE_AUTHENTICATE -> {
                        val ack = ConfigAckResponse(
                            version = 2,
                            opcode = PulseShiftBleProtocol.V2_OPCODE_AUTHENTICATE,
                            sequenceRef = seq,
                            statusCode = PulseShiftBleProtocol.STATUS_SUCCESS,
                            resultFlags = 0x01,
                            activeRevision = 1,
                            payload = byteArrayOf()
                        )
                        configAcksFlow.tryEmit(ack)
                    }
                    PulseShiftBleProtocol.V2_OPCODE_GET_SNAPSHOT -> {
                        val snapshot = ConfigSnapshot(
                            isValid = true,
                            snapshotId = seq,
                            activeRevision = 2,
                            isPowerOn = true,
                            brightness = 200,
                            effect = com.pulseshift.app.domain.model.EffectType.STATIC,
                            effectSpeed = 50,
                            ledColors = List(6) { RgbColor.BrandViolet },
                            ledDirection = 0,
                            startupAnimation = com.pulseshift.app.domain.model.StartupAnimation.SWEEP,
                            startupDurationMs = 2000,
                            rpmThresholds = listOf(3000, 4000, 5000, 6000, 7000, 8000),
                            warningRpm = 8500,
                            warningPatternId = 0,
                            warningColor = RgbColor.RacingRed,
                            rpmMultiplier = 40.0f,
                            canCandidateId = 0x301L,
                            candidateByteIndex = 0,
                            isExtendedFrame = false,
                            calibrationVerified = true
                        )
                        configSnapshotsFlow.value = snapshot
                    }
                    PulseShiftBleProtocol.V2_OPCODE_SET_BRIGHTNESS -> {
                        val ack = ConfigAckResponse(
                            version = 2,
                            opcode = PulseShiftBleProtocol.V2_OPCODE_SET_BRIGHTNESS,
                            sequenceRef = seq,
                            statusCode = PulseShiftBleProtocol.STATUS_SUCCESS,
                            resultFlags = 0x01, // RAM only
                            activeRevision = 3,
                            payload = byteArrayOf()
                        )
                        configAcksFlow.tryEmit(ack)
                    }
                    PulseShiftBleProtocol.V2_OPCODE_SAVE_CONFIG -> {
                        val ack = ConfigAckResponse(
                            version = 2,
                            opcode = PulseShiftBleProtocol.V2_OPCODE_SAVE_CONFIG,
                            sequenceRef = seq,
                            statusCode = PulseShiftBleProtocol.STATUS_SUCCESS,
                            resultFlags = 0x03, // success + persisted in flash
                            activeRevision = 4,
                            payload = byteArrayOf()
                        )
                        configAcksFlow.tryEmit(ack)
                    }
                }
                true
            }

            val repository = createRepository()

            // 1. Initial unauthenticated state
            assertFalse("isNvsConfirmed must be false initially", repository.isNvsConfirmed.value)
            assertFalse("isAuthenticated must be false initially", repository.isAuthenticated.value)

            // 2. Authenticate
            val authOk = repository.authenticateChallengeResponse()
            assertTrue(authOk)
            assertTrue("isAuthenticated must be true after auth", repository.isAuthenticated.value)
            assertFalse("Authentication alone must NOT mark isNvsConfirmed", repository.isNvsConfirmed.value)

            // 3. Reload snapshot from controller RAM
            val snapshot = repository.reloadConfigFromController()
            assertNotNull(snapshot)
            assertTrue("Snapshot establishes hardware RAM confirmation", repository.lightingState.value.isHardwareConfirmed)
            assertFalse("RAM snapshot must NOT mark isNvsConfirmed", repository.isNvsConfirmed.value)

            // 4. Mutate brightness in volatile RAM
            repository.setBrightness(180)
            assertTrue("hasUnsavedChanges must be true after volatile mutation", repository.hasUnsavedChanges.value)
            assertFalse("isNvsConfirmed must be false while changes are only in RAM", repository.isNvsConfirmed.value)

            // 5. Persist to flash with SAVE_CONFIG
            val saved = repository.saveConfigurationToController()
            assertTrue("SAVE_CONFIG must succeed", saved)
            assertTrue("isNvsConfirmed must be TRUE ONLY after explicit SAVE_CONFIG ACK with persistence flag", repository.isNvsConfirmed.value)
            assertFalse("hasUnsavedChanges must be false after successful persistence", repository.hasUnsavedChanges.value)

            // 6. Volatile mutation clears NVS persistence confirmation
            repository.setBrightness(120)
            assertTrue("hasUnsavedChanges must be true after new mutation", repository.hasUnsavedChanges.value)
            assertFalse("isNvsConfirmed must immediately reset to FALSE after any subsequent volatile mutation", repository.isNvsConfirmed.value)

            // 7. BLE Disconnect resets persistence confirmation
            connectionStateFlow.value = BleConnectionState.DISCONNECTED
            withTimeout(5000L) {
                while (repository.isAuthenticated.value) {
                    delay(20)
                }
            }
            assertFalse("isNvsConfirmed must be reset to FALSE on BLE disconnect", repository.isNvsConfirmed.value)
            assertFalse("isAuthenticated must be reset to FALSE on BLE disconnect", repository.isAuthenticated.value)
        }
    }

    @Test
    fun testPostCommitSnapshotMismatchReconcilesFromDeviceWithoutRollback() {
        runBlocking {
            val dummySecret = ByteArray(12) { 0x55.toByte() }
            whenever(mockCredentialManager.hasEnrolledSecret(testDevice.address)).thenReturn(true)
            whenever(mockCredentialManager.getEnrolledSecret(testDevice.address)).thenReturn(dummySecret)
            connectedDeviceFlow.value = testDevice
            connectionStateFlow.value = BleConnectionState.CONNECTED_WAITING_FOR_DATA

            whenever(mockBleManager.enqueueConfigCommand(any())).thenAnswer { invocation ->
                val cmdBytes = invocation.getArgument<ByteArray>(0)
                val opcode = cmdBytes[1]
                val seq = (cmdBytes[2].toInt() and 0xFF) or ((cmdBytes[3].toInt() and 0xFF) shl 8)

                when (opcode) {
                    PulseShiftBleProtocol.V2_OPCODE_REQUEST_CHALLENGE -> {
                        val nonce = byteArrayOf(1, 2, 3, 4)
                        val ack = ConfigAckResponse(
                            version = 2,
                            opcode = PulseShiftBleProtocol.V2_OPCODE_REQUEST_CHALLENGE,
                            sequenceRef = seq,
                            statusCode = PulseShiftBleProtocol.STATUS_SUCCESS,
                            resultFlags = 0,
                            activeRevision = 0,
                            payload = nonce
                        )
                        configAcksFlow.tryEmit(ack)
                    }
                    PulseShiftBleProtocol.V2_OPCODE_AUTHENTICATE -> {
                        val ack = ConfigAckResponse(
                            version = 2,
                            opcode = PulseShiftBleProtocol.V2_OPCODE_AUTHENTICATE,
                            sequenceRef = seq,
                            statusCode = PulseShiftBleProtocol.STATUS_SUCCESS,
                            resultFlags = 0x01,
                            activeRevision = 1,
                            payload = byteArrayOf()
                        )
                        configAcksFlow.tryEmit(ack)
                    }
                    PulseShiftBleProtocol.V2_OPCODE_BEGIN_TRANSACTION,
                    PulseShiftBleProtocol.V2_OPCODE_SET_BRIGHTNESS,
                    PulseShiftBleProtocol.V2_OPCODE_SET_EFFECT,
                    PulseShiftBleProtocol.V2_OPCODE_SET_LED_DIRECTION,
                    PulseShiftBleProtocol.V2_OPCODE_SET_STARTUP,
                    PulseShiftBleProtocol.V2_OPCODE_SET_LED_RGB,
                    PulseShiftBleProtocol.V2_OPCODE_SET_LED_THRESHOLD,
                    PulseShiftBleProtocol.V2_OPCODE_SET_SHIFT_WARNING -> {
                        // Staged operations return success with staged-only flag (0x02)
                        val ack = ConfigAckResponse(
                            version = 2,
                            opcode = opcode,
                            sequenceRef = seq,
                            statusCode = PulseShiftBleProtocol.STATUS_SUCCESS,
                            resultFlags = 0x02, // staged only
                            activeRevision = 1,
                            payload = byteArrayOf()
                        )
                        configAcksFlow.tryEmit(ack)
                    }
                    PulseShiftBleProtocol.V2_OPCODE_COMMIT_TRANSACTION -> {
                        // Hardware successfully commits to RAM with activeRevision = 10
                        val ack = ConfigAckResponse(
                            version = 2,
                            opcode = PulseShiftBleProtocol.V2_OPCODE_COMMIT_TRANSACTION,
                            sequenceRef = seq,
                            statusCode = PulseShiftBleProtocol.STATUS_SUCCESS,
                            resultFlags = 0x05, // applied in RAM (0x01 | 0x04)
                            activeRevision = 10,
                            payload = byteArrayOf()
                        )
                        configAcksFlow.tryEmit(ack)
                    }
                    PulseShiftBleProtocol.V2_OPCODE_GET_SNAPSHOT -> {
                        // Return device snapshot where hardware applied a clamped brightness of 180
                        val snapshot = ConfigSnapshot(
                            snapshotId = seq,
                            activeRevision = 10,
                            isPowerOn = true,
                            brightness = 180, // Controller clamped or diverged from preset's 255
                            effect = com.pulseshift.app.domain.model.EffectType.STATIC,
                            effectSpeed = 50,
                            ledColors = List(6) { RgbColor.BrandViolet },
                            ledDirection = 0,
                            startupAnimation = com.pulseshift.app.domain.model.StartupAnimation.SWEEP,
                            startupDurationMs = 2000,
                            rpmThresholds = listOf(3000, 4000, 5000, 6000, 7000, 8000),
                            warningRpm = 8500,
                            warningPatternId = 0,
                            warningColor = RgbColor.RacingRed,
                            rpmMultiplier = 40.0f,
                            canCandidateId = 0x301L,
                            candidateByteIndex = 0,
                            isExtendedFrame = false,
                            calibrationVerified = true
                        )
                        configSnapshotsFlow.value = snapshot
                    }
                }
                true
            }

            val repository = createRepository()
            assertTrue(repository.authenticateChallengeResponse())

            val targetPreset = Preset(
                id = "test_preset",
                name = "Test Preset",
                brightness = 255, // Note: preset requested 255, but controller snapshot has 180
                effect = com.pulseshift.app.domain.model.EffectType.STATIC,
                effectSpeed = 50,
                ledColors = List(6) { RgbColor.BrandViolet },
                ledDirection = 0,
                startupAnimation = com.pulseshift.app.domain.model.StartupAnimation.SWEEP,
                startupDurationMs = 2000
            )

            // Applying preset must NOT throw even though post-commit snapshot has mismatched brightness
            val result = repository.applyPreset(targetPreset)

            // Crucial: UI state must NOT have rolled back to initial state, but reconciled with device
            assertEquals(PresetApplyResult.APPLIED_NEEDS_READBACK, result)
            assertEquals("Brightness must reconcile from device snapshot (180)", 180, repository.lightingState.value.brightness)
            assertTrue("isPendingSync must be true indicating unverified snapshot validation", repository.lightingState.value.isPendingSync)
            assertFalse("isHardwareConfirmed must be false when verification failed", repository.lightingState.value.isHardwareConfirmed)
            assertTrue("isSyncUnknown must be true for unverified state", repository.lightingState.value.isSyncUnknown)
            assertTrue("hasUnsavedChanges must remain true", repository.hasUnsavedChanges.value)
        }
    }

    private val shiftedPreset = Preset.DefaultPresets.first().let { preset ->
        val config = checkNotNull(preset.shiftLightConfig)
        preset.copy(
            shiftLightConfig = config.copy(
                warningStyle = WarningStyle.STROBE,
                colors = config.colors.dropLast(1) + RgbColor.DeepBlue
            )
        )
    }

    private fun presetSnapshot(sequence: Int, revision: Long): ConfigSnapshot {
        val preset = shiftedPreset
        val config = checkNotNull(preset.shiftLightConfig)
        return ConfigSnapshot(
            snapshotId = sequence,
            activeRevision = revision,
            brightness = preset.brightness,
            effect = preset.effect,
            effectSpeed = preset.effectSpeed,
            ledColors = preset.ledColors,
            rpmThresholds = config.thresholds,
            warningRpm = config.warningRpm,
            warningPatternId = config.warningStyle.id,
            warningColor = config.colors.last()
        )
    }

    private fun fakePresetController(
        baseRevision: Long = 41L,
        dropCommitAck: Boolean = false,
        onCommit: () -> Unit = {},
        readback: (attempt: Int, sequence: Int) -> ConfigSnapshot?
    ): List<ByteArray> {
        connectedDeviceFlow.value = testDevice
        val commands = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        var snapshotRequests = 0
        whenever(mockBleManager.enqueueConfigCommand(any())).thenAnswer { invocation ->
            val frame = invocation.getArgument<ByteArray>(0)
            commands.add(frame)
            val opcode = frame[1]
            val seq = (frame[2].toInt() and 0xFF) or ((frame[3].toInt() and 0xFF) shl 8)
            when (opcode) {
                PulseShiftBleProtocol.V2_OPCODE_GET_SNAPSHOT -> {
                    val snapshot = readback(++snapshotRequests, seq)
                    if (snapshot != null) configSnapshotsFlow.value = snapshot
                }
                else -> {
                    val commit = opcode == PulseShiftBleProtocol.V2_OPCODE_COMMIT_TRANSACTION
                    val staged = opcode == PulseShiftBleProtocol.V2_OPCODE_BEGIN_TRANSACTION || frame[5] == 1.toByte()
                    if (commit) onCommit()
                    if (!commit || !dropCommitAck) {
                        configAcksFlow.tryEmit(
                            ConfigAckResponse(
                                opcode = opcode,
                                sequenceRef = seq,
                                statusCode = PulseShiftBleProtocol.STATUS_SUCCESS,
                                resultFlags = if (staged) 0x02 else 0x04,
                                activeRevision = if (commit) (baseRevision + 1) and 0xFFFF_FFFFL else baseRevision
                            )
                        )
                    }
                }
            }
            true
        }
        return commands
    }

    @Test
    fun testPresetRetriesOlderReadbackAndPublishesExactWarningConfiguration() = runBlocking {
        val commands = fakePresetController { attempt, sequence ->
            if (attempt == 1) ConfigSnapshot(snapshotId = sequence, activeRevision = 41L)
            else presetSnapshot(sequence, 42L)
        }
        val repository = createRepository()
        repository.setAuthenticatedForTesting(true)
        repository.setBrightness(155)
        assertTrue(repository.hasUnsavedChanges.value)

        assertEquals(PresetApplyResult.READBACK_CONFIRMED, repository.applyPreset(shiftedPreset))
        val warningWrite = commands.single { it[1] == PulseShiftBleProtocol.V2_OPCODE_SET_WARNING_RGB }
        assertEquals(listOf(1, 59, 130, 246), warningWrite.drop(5).map { it.toInt() and 0xFF })
        assertEquals(shiftedPreset.shiftLightConfig, repository.shiftLightConfig.value)
        assertEquals(42L, repository.configSnapshot.value?.activeRevision)
        assertFalse(repository.lightingState.value.isPendingSync)
        assertTrue(repository.lightingState.value.isHardwareConfirmed)
        assertTrue(repository.hasUnsavedChanges.value)
        assertFalse(repository.isNvsConfirmed.value)
        assertEquals(2, commands.count { it[1] == PulseShiftBleProtocol.V2_OPCODE_GET_SNAPSHOT })
    }

    @Test
    fun testStaleOnlyReadbackPreservesAcknowledgedPresetAndResyncKeepsRamUnsaved() = runBlocking {
        fakePresetController { attempt, sequence ->
            if (attempt <= 4) ConfigSnapshot(snapshotId = sequence, activeRevision = 41L)
            else presetSnapshot(sequence, 42L)
        }
        val repository = createRepository()
        repository.setAuthenticatedForTesting(true)

        assertEquals(PresetApplyResult.APPLIED_NEEDS_READBACK, repository.applyPreset(shiftedPreset))
        assertEquals(shiftedPreset.brightness, repository.lightingState.value.brightness)
        assertTrue(repository.lightingState.value.isSyncUnknown)
        assertEquals(null, repository.reloadConfigFromController())
        assertTrue(repository.lightingState.value.isSyncUnknown)
        assertEquals(42L, repository.reloadConfigFromController()?.activeRevision)
        assertFalse(repository.lightingState.value.isSyncUnknown)
        assertFalse(repository.lightingState.value.isPendingSync)
        assertTrue(repository.hasUnsavedChanges.value)
        assertFalse(repository.isNvsConfirmed.value)
    }

    @Test
    fun testMissingCommitAckRecoveredByExactSnapshotAtRevisionWrap() = runBlocking {
        val commands = fakePresetController(baseRevision = 0xFFFF_FFFFL, dropCommitAck = true) { _, sequence ->
            presetSnapshot(sequence, 0L)
        }
        val repository = createRepository()
        repository.setAuthenticatedForTesting(true)

        assertEquals(PresetApplyResult.READBACK_CONFIRMED, repository.applyPreset(shiftedPreset))
        assertTrue(repository.lightingState.value.isHardwareConfirmed)
        assertEquals(DeviceCommandPhase.APPLIED, repository.deviceCommandHistory.value.first().phase)
        assertFalse(commands.any { it[1] == PulseShiftBleProtocol.V2_OPCODE_ABORT_TRANSACTION })
    }

    @Test
    fun testCommitDisconnectRemainsUnverifiedAndReconnectReconciles() = runBlocking {
        whenever(mockCredentialManager.hasEnrolledSecret(testDevice.address)).thenReturn(false)
        val commands = fakePresetController(dropCommitAck = true, onCommit = {
            connectionStateFlow.value = BleConnectionState.DISCONNECTED
        }) { _, sequence ->
            presetSnapshot(sequence, 42L)
        }
        val repository = createRepository()
        repository.setAuthenticatedForTesting(true)

        assertEquals(PresetApplyResult.COMMIT_UNKNOWN, repository.applyPreset(shiftedPreset))
        assertEquals(DeviceCommandPhase.UNVERIFIED, repository.deviceCommandHistory.value.first().phase)
        assertTrue(repository.lightingState.value.isSyncUnknown)
        assertFalse(commands.any { it[1] == PulseShiftBleProtocol.V2_OPCODE_ABORT_TRANSACTION })

        connectionStateFlow.value = BleConnectionState.READY
        withTimeout(5000L) {
            while (repository.lightingState.value.isSyncUnknown) delay(10)
        }
        assertEquals(shiftedPreset.brightness, repository.lightingState.value.brightness)
        assertTrue(repository.lightingState.value.isHardwareConfirmed)
        assertFalse(repository.isNvsConfirmed.value)
    }

    @Test
    fun testWarningOnlyMismatchDoesNotConfirmPresetReadback() = runBlocking {
        fakePresetController { _, sequence ->
            presetSnapshot(sequence, 42L).copy(warningPatternId = 0, warningColor = RgbColor.RacingRed)
        }
        val repository = createRepository()
        repository.setAuthenticatedForTesting(true)

        assertEquals(PresetApplyResult.APPLIED_NEEDS_READBACK, repository.applyPreset(shiftedPreset))
        assertEquals(WarningStyle.FLASH, repository.shiftLightConfig.value.warningStyle)
        assertEquals(RgbColor.RacingRed, repository.shiftLightConfig.value.colors.last())
        assertTrue(repository.deviceCommandHistory.value.first().detail.contains("warning pattern, warning color"))
    }

    @Test
    fun testNewerSnapshotReconcilesCurrentSettingsWithoutClaimingPresetReadback() = runBlocking {
        fakePresetController { _, sequence -> presetSnapshot(sequence, 43L).copy(brightness = 133) }
        val repository = createRepository()
        repository.setAuthenticatedForTesting(true)

        assertEquals(PresetApplyResult.APPLIED_NEEDS_READBACK, repository.applyPreset(shiftedPreset))
        assertEquals(133, repository.lightingState.value.brightness)
        assertEquals(43L, repository.configSnapshot.value?.activeRevision)
        assertFalse(repository.lightingState.value.isHardwareConfirmed)
        assertEquals(DeviceCommandPhase.APPLIED, repository.deviceCommandHistory.value.first().phase)
    }

    @Test
    fun testPersistedAckWithoutRamReadbackDoesNotConfirmDisplayedConfiguration() = runBlocking {
        connectedDeviceFlow.value = testDevice
        whenever(mockBleManager.enqueueConfigCommand(any())).thenAnswer { invocation ->
            val command = invocation.getArgument<ByteArray>(0)
            val opcode = command[1]
            if (opcode == PulseShiftBleProtocol.V2_OPCODE_SAVE_CONFIG) {
                val seq = (command[2].toInt() and 0xFF) or ((command[3].toInt() and 0xFF) shl 8)
                configAcksFlow.tryEmit(
                    ConfigAckResponse(
                        opcode = opcode,
                        sequenceRef = seq,
                        statusCode = PulseShiftBleProtocol.STATUS_SUCCESS,
                        resultFlags = 0x05,
                        activeRevision = 42L
                    )
                )
            }
            true
        }
        val repository = createRepository()
        repository.setAuthenticatedForTesting(true)

        assertTrue(repository.saveConfigurationToController())
        assertEquals(DeviceCommandPhase.SAVED, repository.deviceCommandHistory.value.first().phase)
        assertTrue(repository.isNvsConfirmed.value)
        assertFalse(repository.hasUnsavedChanges.value)
        assertTrue(repository.lightingState.value.isSyncUnknown)
        assertFalse(repository.lightingState.value.isHardwareConfirmed)
    }
}
