@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.pulseshift.app

import com.pulseshift.app.data.ble.BleConnectionState
import com.pulseshift.app.data.ble.ConfigSnapshotReassembler
import com.pulseshift.app.data.ble.DiagnosticLogReassembler
import com.pulseshift.app.data.ble.PulseShiftBleManager
import com.pulseshift.app.data.ble.PulseShiftBleProtocol
import com.pulseshift.app.data.local.OwnerCredentialManager
import com.pulseshift.app.data.local.PulseShiftDataStore
import com.pulseshift.app.data.repository.BlePulseShiftRepository
import com.pulseshift.app.domain.model.AuthState
import com.pulseshift.app.domain.model.BleDevice
import com.pulseshift.app.domain.model.ConfigAckResponse
import com.pulseshift.app.domain.model.ConfigSnapshot
import com.pulseshift.app.domain.model.Preset
import com.pulseshift.app.domain.model.RgbColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

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
        configAcksFlow = MutableSharedFlow<ConfigAckResponse>(extraBufferCapacity = 16)
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

    private fun createRepository(): BlePulseShiftRepository {
        return BlePulseShiftRepository(
            bleManager = mockBleManager,
            dataStore = mockDataStore,
            credentialManager = mockCredentialManager
        )
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
            withTimeout(2000L) {
                while (repository.isAuthenticated.value) {
                    delay(20)
                }
            }
            assertFalse("isNvsConfirmed must be reset to FALSE on BLE disconnect", repository.isNvsConfirmed.value)
            assertFalse("isAuthenticated must be reset to FALSE on BLE disconnect", repository.isAuthenticated.value)
        }
    }
}
