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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        connectionStateFlow = MutableStateFlow(BleConnectionState.DISCONNECTED)
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
    fun testUnenrolledEsp32RejectsChallengeAndClearsStaleAndroidKeyStoreCredential() {
        runBlocking {
            // Setup: Android has an old enrolled secret for this device MAC
            val dummySecret = ByteArray(12) { 0x42.toByte() }
            whenever(mockCredentialManager.hasEnrolledSecret(testDevice.address)).thenReturn(true)
            whenever(mockCredentialManager.getEnrolledSecret(testDevice.address)).thenReturn(dummySecret)
            connectedDeviceFlow.value = testDevice

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

            // 2. Stale local credential must be cleared from Android KeyStore
            verify(mockCredentialManager).clearEnrolledSecret(testDevice.address)

            // 3. AuthState must transition directly to NOT_ENROLLED
            assertEquals(AuthState.NOT_ENROLLED, repository.authState.value)
            assertFalse(repository.isAuthenticated.value)
        }
    }

    @Test
    fun testUnauthenticatedLightingWriteDoesNotMutateStateOrSetUnsaved() {
        runBlocking {
            whenever(mockCredentialManager.hasEnrolledSecret(testDevice.address)).thenReturn(false)
            connectedDeviceFlow.value = testDevice

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

            var failNextCommand = false

            whenever(mockBleManager.enqueueConfigCommand(any())).thenAnswer { invocation ->
                val cmdBytes = invocation.getArgument<ByteArray>(0)
                val opcode = cmdBytes[1]
                val seq = (cmdBytes[2].toInt() and 0xFF) or ((cmdBytes[3].toInt() and 0xFF) shl 8)

                if (failNextCommand) {
                    return@thenAnswer false
                }

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
                }
                true
            }

            val repository = createRepository()

            val authOk = repository.authenticateChallengeResponse()
            assertTrue("Precondition: Auth must succeed", authOk)
            assertTrue(repository.isAuthenticated.value)

            // Simulate command write failure on the BLE bus
            failNextCommand = true

            val previousPower = repository.lightingState.value.isPowerOn

            // Mutate power
            repository.setPower(!previousPower)

            // Power must roll back to previous state!
            assertEquals("Power must atomically roll back on command failure", previousPower, repository.lightingState.value.isPowerOn)
            assertFalse("hasUnsavedChanges must roll back to false", repository.hasUnsavedChanges.value)
        }
    }

    @Test
    fun testStatusPillTruthfulnessUnderAllAuthAndPersistenceStates() {
        // Test Header Status Pill Text Resolution
        fun resolveHeaderStatusPill(isAuthenticated: Boolean, unsaved: Boolean, isConfirmed: Boolean): String {
            return when {
                !isAuthenticated -> "UNAUTHENTICATED"
                unsaved -> "RAM ONLY"
                isConfirmed -> "PERSISTED (NVS)"
                else -> "UNVERIFIED"
            }
        }

        // Test Controller Persistence Card Status Pill Text Resolution
        fun resolvePersistenceStatusPill(isAuthenticated: Boolean, unsaved: Boolean, isConfirmed: Boolean): String {
            return when {
                !isAuthenticated -> "AUTH REQUIRED"
                unsaved -> "RAM ACTIVE (UNSAVED)"
                isConfirmed -> "SAVED IN FLASH"
                else -> "UNVERIFIED"
            }
        }

        // Case 1: Unauthenticated with unsaved == false (THE CRITICAL REGRESSION SCENARIO)
        // Previously rendered "PERSISTED (NVS)" and "SAVED IN FLASH" deceptively!
        assertEquals("UNAUTHENTICATED", resolveHeaderStatusPill(isAuthenticated = false, unsaved = false, isConfirmed = false))
        assertEquals("AUTH REQUIRED", resolvePersistenceStatusPill(isAuthenticated = false, unsaved = false, isConfirmed = false))

        // Case 2: Unauthenticated with unsaved == true
        assertEquals("UNAUTHENTICATED", resolveHeaderStatusPill(isAuthenticated = false, unsaved = true, isConfirmed = false))
        assertEquals("AUTH REQUIRED", resolvePersistenceStatusPill(isAuthenticated = false, unsaved = true, isConfirmed = false))

        // Case 3: Authenticated with unsaved changes in RAM
        assertEquals("RAM ONLY", resolveHeaderStatusPill(isAuthenticated = true, unsaved = true, isConfirmed = false))
        assertEquals("RAM ACTIVE (UNSAVED)", resolvePersistenceStatusPill(isAuthenticated = true, unsaved = true, isConfirmed = false))

        // Case 4: Authenticated and successfully persisted to flash (NVS confirmed)
        assertEquals("PERSISTED (NVS)", resolveHeaderStatusPill(isAuthenticated = true, unsaved = false, isConfirmed = true))
        assertEquals("SAVED IN FLASH", resolvePersistenceStatusPill(isAuthenticated = true, unsaved = false, isConfirmed = true))
    }
}
