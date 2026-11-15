package com.pulseshift.app.data.ble

import android.content.Context
import com.pulseshift.app.data.local.PulseShiftDataStore
import com.pulseshift.app.data.repository.BlePulseShiftRepository
import com.pulseshift.app.domain.model.ConfigAckResponse
import com.pulseshift.app.domain.model.OtaUpdateState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class OtaConfirmationWorkflowTest {

    private fun createRepository(): Pair<BlePulseShiftRepository, PulseShiftBleManager> {
        val mockContext = mock(Context::class.java)
        val bleManager = PulseShiftBleManager(
            context = mockContext,
            adapterProvider = { null }
        )
        bleManager.setConnectionStateForTesting(BleConnectionState.READY)
        val mockDataStore = mock(PulseShiftDataStore::class.java)
        val repository = BlePulseShiftRepository(bleManager, mockDataStore)
        repository.setAuthenticatedForTesting(true)
        return Pair(repository, bleManager)
    }

    private fun createOtaStatusAck(sequence: Int, imageState: Int, hasRollback: Boolean): ConfigAckResponse {
        val payload = byteArrayOf(
            0, // state: Idle
            1, // active slot: 1
            0, 0, 0, 0, // bytesWritten: 0
            imageState.toByte(), // byte 6: ESP-IDF image state (1=PENDING_VERIFY, 2=VALID)
            if (hasRollback) 1.toByte() else 0.toByte() // byte 7: rollback candidate present
        )
        return ConfigAckResponse(
            version = 2,
            opcode = PulseShiftBleProtocol.V2_OPCODE_OTA_STATUS,
            sequenceRef = sequence,
            statusCode = PulseShiftBleProtocol.STATUS_SUCCESS,
            resultFlags = 0,
            activeRevision = 1L,
            payload = payload
        )
    }

    private fun createOtaConfirmAck(sequence: Int, statusCode: Byte = PulseShiftBleProtocol.STATUS_SUCCESS): ConfigAckResponse {
        return ConfigAckResponse(
            version = 2,
            opcode = PulseShiftBleProtocol.V2_OPCODE_OTA_CONFIRM,
            sequenceRef = sequence,
            statusCode = statusCode,
            resultFlags = 0,
            activeRevision = 1L,
            payload = byteArrayOf(1)
        )
    }

    @Test
    fun testInitialValidImageStateIsRejectedBeforeConfirmation() = runBlocking {
        val (repository, _) = createRepository()
        var confirmSent = false

        repository.commandSenderForTesting = { _, sequence, opcode, _ ->
            when (opcode) {
                PulseShiftBleProtocol.V2_OPCODE_OTA_STATUS -> {
                    // Pre-confirmation check reports VALID (2), which must NOT be trusted
                    createOtaStatusAck(sequence, imageState = 2, hasRollback = true)
                }
                PulseShiftBleProtocol.V2_OPCODE_OTA_CONFIRM -> {
                    confirmSent = true
                    createOtaConfirmAck(sequence)
                }
                else -> null
            }
        }

        repository.setAuthenticatedForTesting(true)
        val success = repository.performOtaConfirmationWorkflow(
            previousVersion = "2.2.0",
            newVer = "2.3.0",
            runningSlot = 1
        )

        assertFalse("Update must fail when initial image state is already VALID before confirmation", success)
        assertFalse("OTA_CONFIRM must not be sent when initial state is already VALID", confirmSent)
        val state = repository.otaUpdateState.value
        assertTrue("State must be Failed", state is OtaUpdateState.Failed)
        val fail = state as OtaUpdateState.Failed
        assertTrue(
            "Error must explicitly mention image was already marked VALID before confirmation",
            fail.reason.contains("already marked VALID")
        )
    }

    @Test
    fun testSuccessfulConfirmationFlow() = runBlocking {
        val (repository, _) = createRepository()
        var statusQueryCount = 0
        var confirmSent = false

        repository.commandSenderForTesting = { _, sequence, opcode, _ ->
            when (opcode) {
                PulseShiftBleProtocol.V2_OPCODE_OTA_STATUS -> {
                    statusQueryCount++
                    if (statusQueryCount == 1) {
                        // Initial query: PENDING_VERIFY (1)
                        createOtaStatusAck(sequence, imageState = 1, hasRollback = true)
                    } else {
                        // Final query after confirmation: VALID (2)
                        createOtaStatusAck(sequence, imageState = 2, hasRollback = true)
                    }
                }
                PulseShiftBleProtocol.V2_OPCODE_OTA_CONFIRM -> {
                    confirmSent = true
                    createOtaConfirmAck(sequence)
                }
                else -> null
            }
        }

        repository.setAuthenticatedForTesting(true)
        val success = repository.performOtaConfirmationWorkflow(
            previousVersion = "2.2.0",
            newVer = "2.3.0",
            runningSlot = 1
        )

        assertTrue("Update must succeed when confirmation succeeds and final status is VALID", success)
        assertTrue("OTA_CONFIRM must be sent", confirmSent)
        assertEquals("Must query status twice (initial check and final verification)", 2, statusQueryCount)
        val state = repository.otaUpdateState.value
        assertTrue("State must be Success", state is OtaUpdateState.Success)
        val succ = state as OtaUpdateState.Success
        assertEquals("2.2.0", succ.previousVersion)
        assertEquals("2.3.0", succ.newVersion)
        assertEquals(1, succ.activeSlot)
    }

    @Test
    fun testMissingFinalAcknowledgmentWithRecovery() = runBlocking {
        val (repository, _) = createRepository()
        var statusQueryCount = 0
        var confirmAttempts = 0

        repository.commandSenderForTesting = { _, sequence, opcode, _ ->
            when (opcode) {
                PulseShiftBleProtocol.V2_OPCODE_OTA_STATUS -> {
                    statusQueryCount++
                    if (statusQueryCount == 1) {
                        createOtaStatusAck(sequence, imageState = 1, hasRollback = true)
                    } else {
                        // Final query proves ESP32 processed the confirmation and is now VALID!
                        createOtaStatusAck(sequence, imageState = 2, hasRollback = true)
                    }
                }
                PulseShiftBleProtocol.V2_OPCODE_OTA_CONFIRM -> {
                    confirmAttempts++
                    // Both confirmation attempts time out (ACKs lost over the air)
                    null
                }
                else -> null
            }
        }

        repository.setAuthenticatedForTesting(true)
        val success = repository.performOtaConfirmationWorkflow(
            previousVersion = "2.2.0",
            newVer = "2.3.0",
            runningSlot = 1
        )

        assertTrue("Lost confirmation ACK must recover if final readback proves VALID", success)
        assertEquals(2, confirmAttempts)
        assertEquals(2, statusQueryCount)
        assertTrue("State must be Success", repository.otaUpdateState.value is OtaUpdateState.Success)
    }

    @Test
    fun testMissingFinalAcknowledgmentWithoutRecoveryFails() = runBlocking {
        val (repository, _) = createRepository()
        var statusQueryCount = 0

        repository.commandSenderForTesting = { _, sequence, opcode, _ ->
            when (opcode) {
                PulseShiftBleProtocol.V2_OPCODE_OTA_STATUS -> {
                    statusQueryCount++
                    // ESP32 remains in PENDING_VERIFY (1); never marked valid
                    createOtaStatusAck(sequence, imageState = 1, hasRollback = true)
                }
                PulseShiftBleProtocol.V2_OPCODE_OTA_CONFIRM -> {
                    // Confirmation attempts time out
                    null
                }
                else -> null
            }
        }

        repository.setAuthenticatedForTesting(true)
        val success = repository.performOtaConfirmationWorkflow(
            previousVersion = "2.2.0",
            newVer = "2.3.0",
            runningSlot = 1
        )

        assertFalse("Must fail if confirmation ACK is lost and final state is NOT VALID", success)
        val state = repository.otaUpdateState.value
        assertTrue("State must be Failed", state is OtaUpdateState.Failed)
        val fail = state as OtaUpdateState.Failed
        assertTrue(
            "Error must mention expected VALID=2",
            fail.reason.contains("expected VALID=2")
        )
    }

    @Test
    fun testMissingFinalStatusResponseFails() = runBlocking {
        val (repository, _) = createRepository()
        var statusQueryCount = 0

        repository.commandSenderForTesting = { _, sequence, opcode, _ ->
            when (opcode) {
                PulseShiftBleProtocol.V2_OPCODE_OTA_STATUS -> {
                    statusQueryCount++
                    if (statusQueryCount == 1) {
                        createOtaStatusAck(sequence, imageState = 1, hasRollback = true)
                    } else {
                        // Final status query times out / missing response
                        null
                    }
                }
                PulseShiftBleProtocol.V2_OPCODE_OTA_CONFIRM -> {
                    createOtaConfirmAck(sequence)
                }
                else -> null
            }
        }

        repository.setAuthenticatedForTesting(true)
        val success = repository.performOtaConfirmationWorkflow(
            previousVersion = "2.2.0",
            newVer = "2.3.0",
            runningSlot = 1
        )

        assertFalse("Must fail when final status response is missing, even if OTA_CONFIRM received ACK", success)
        val state = repository.otaUpdateState.value
        assertTrue("State must be Failed", state is OtaUpdateState.Failed)
        val fail = state as OtaUpdateState.Failed
        assertTrue(
            "Error must indicate final status readback failed",
            fail.reason.contains("Failed to read back final OTA status")
        )
    }

    @Test
    fun testFinalStatusReportsNonValidStateFails() = runBlocking {
        val (repository, _) = createRepository()
        var statusQueryCount = 0

        repository.commandSenderForTesting = { _, sequence, opcode, _ ->
            when (opcode) {
                PulseShiftBleProtocol.V2_OPCODE_OTA_STATUS -> {
                    statusQueryCount++
                    if (statusQueryCount == 1) {
                        createOtaStatusAck(sequence, imageState = 1, hasRollback = true)
                    } else {
                        // Final status query reports ABORTED (4) or INVALID (3)
                        createOtaStatusAck(sequence, imageState = 3, hasRollback = true)
                    }
                }
                PulseShiftBleProtocol.V2_OPCODE_OTA_CONFIRM -> {
                    createOtaConfirmAck(sequence)
                }
                else -> null
            }
        }

        repository.setAuthenticatedForTesting(true)
        val success = repository.performOtaConfirmationWorkflow(
            previousVersion = "2.2.0",
            newVer = "2.3.0",
            runningSlot = 1
        )

        assertFalse("Must fail when final status reports non-valid image state", success)
        val state = repository.otaUpdateState.value
        assertTrue("State must be Failed", state is OtaUpdateState.Failed)
        val fail = state as OtaUpdateState.Failed
        assertTrue(
            "Error must report unexpected image state",
            fail.reason.contains("expected VALID=2")
        )
    }
}
