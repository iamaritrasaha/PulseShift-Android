package com.pulseshift.app

import com.pulseshift.app.data.ble.PulseShiftBleProtocol
import com.pulseshift.app.domain.model.EffectType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the firmware protocol additions the app must stay in
 * sync with: 0x54 SET_CAPTURE_STAGE, 0x55 SET_LED_TEST_RPM (firmware 2.2.0),
 * the effect 5 provisional preview id, capability feature bits 15/16, and
 * the strict V2 result-payload validation.
 */
class ProtocolCodecTest {

    private fun v2Ack(opcode: Byte, seq: Int, status: Byte, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(11 + payload.size)
        out[0] = 0x02
        out[1] = opcode
        out[2] = (seq and 0xFF).toByte()
        out[3] = ((seq shr 8) and 0xFF).toByte()
        out[4] = status
        out[5] = 0x04 // APPLIED
        out[6] = 7; out[7] = 0; out[8] = 0; out[9] = 0 // revision 7
        out[10] = payload.size.toByte()
        System.arraycopy(payload, 0, out, 11, payload.size)
        return out
    }

    @Test
    fun `set capture stage encodes opcode 0x54 with one payload byte`() {
        val frame = PulseShiftBleProtocol.buildSetCaptureStageV2(42, 2)
        assertEquals(0x02, frame[0].toInt() and 0xFF)
        assertEquals(0x54, frame[1].toInt() and 0xFF)
        assertEquals(42, (frame[2].toInt() and 0xFF) or ((frame[3].toInt() and 0xFF) shl 8))
        assertEquals(1, frame[4].toInt())
        assertEquals(2, frame[6].toInt() and 0xFF) // payload starts at byte 6
    }

    @Test
    fun `set capture stage clamps stage to the valid 1 to 3 range`() {
        assertEquals(1, PulseShiftBleProtocol.buildSetCaptureStageV2(1, 0)[6].toInt())
        assertEquals(3, PulseShiftBleProtocol.buildSetCaptureStageV2(1, 4)[6].toInt())
    }

    @Test
    fun `set led test rpm encodes opcode 0x55 with rpm u16 LE and enable`() {
        val frame = PulseShiftBleProtocol.buildSetLedTestRpmV2(7, 4500, true)
        assertEquals(0x55.toByte(), frame[1])
        assertEquals(3, frame[4].toInt())
        // 4500 = 0x1194
        assertEquals(0x94.toByte(), frame[6])
        assertEquals(0x11.toByte(), frame[7])
        assertEquals(1, frame[8].toInt())
    }

    @Test
    fun `set led test rpm disable sends zero rpm`() {
        val frame = PulseShiftBleProtocol.buildSetLedTestRpmV2(8, 0, false)
        assertEquals(0, frame[6].toInt())
        assertEquals(0, frame[7].toInt())
        assertEquals(0, frame[8].toInt())
    }

    @Test
    fun `set led test rpm clamps to firmware max 20000`() {
        val frame = PulseShiftBleProtocol.buildSetLedTestRpmV2(9, 25000, true)
        val rpm = (frame[6].toInt() and 0xFF) or ((frame[7].toInt() and 0xFF) shl 8)
        assertEquals(20000, rpm)
    }

    @Test
    fun `effect 5 is the provisional preview and round-trips from id`() {
        assertEquals(5, EffectType.PROVISIONAL_PREVIEW.id)
        assertEquals(EffectType.PROVISIONAL_PREVIEW, EffectType.fromId(5))
    }

    @Test
    fun `feature bits 15 and 16 map to capture stage and bench test`() {
        val caps = PulseShiftBleProtocol.parseCapabilitiesV2(
            byteArrayOf(
                0x02, 6,
                0x00, 0x80.toByte(), 0x00, 0x00, // featureMask = 0x8000 (bit 15)
                0, 0, 0, 0, 0, 2
            )
        )
        assertTrue(caps!!.supportsCaptureStage)
        assertFalse(caps.supportsLedBenchTest)

        val both = PulseShiftBleProtocol.parseCapabilitiesV2(
            byteArrayOf(
                0x02, 6,
                0x00, 0x80.toByte(), 0x01, 0x00, // featureMask = 0x18000 (bits 15+16)
                0, 0, 0, 0, 0, 2
            )
        )
        assertTrue(both!!.supportsCaptureStage)
        assertTrue(both.supportsLedBenchTest)
    }

    @Test
    fun `config ack with oversized payload is rejected as malformed`() {
        val ack = v2Ack(PulseShiftBleProtocol.V2_OPCODE_SAVE_CONFIG, 12, 0x00, ByteArray(10))
        assertNull(PulseShiftBleProtocol.parseConfigAck(ack))
    }

    @Test
    fun `config ack with maximum 9-byte payload parses`() {
        val payload = ByteArray(9) { it.toByte() }
        val ack = PulseShiftBleProtocol.parseConfigAck(
            v2Ack(PulseShiftBleProtocol.V2_OPCODE_CAL_COMPUTE, 13, 0x00, payload)
        )
        assertTrue(ack != null && ack.isSuccess)
        assertEquals(9, ack!!.payload.size)
        assertEquals(7L, ack.activeRevision)
    }

    @Test
    fun `restore defaults keeps the explicit A5 5A confirmation`() {
        val frame = PulseShiftBleProtocol.buildRestoreDefaultsV2(3)
        assertEquals(0x21.toByte(), frame[1])
        assertEquals(2, frame[4].toInt())
        assertEquals(0xA5.toByte(), frame[6])
        assertEquals(0x5A.toByte(), frame[7])
    }

    @Test
    fun `restart device keeps the explicit A5 5A confirmation`() {
        val frame = PulseShiftBleProtocol.buildRestartDeviceV2(4)
        assertEquals(0x51.toByte(), frame[1])
        assertEquals(0xA5.toByte(), frame[6])
        assertEquals(0x5A.toByte(), frame[7])
    }
}
