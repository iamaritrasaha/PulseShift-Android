package com.pulseshift.app

import com.pulseshift.app.data.ble.PulseShiftBleProtocol
import com.pulseshift.app.domain.model.EffectType
import com.pulseshift.app.domain.model.RgbColor
import com.pulseshift.app.domain.model.WarningStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolCodecTest {

    @Test
    fun testBuildSetPowerCommand() {
        val frameOn = PulseShiftBleProtocol.buildSetPower(true, 5.toByte())
        assertEquals(0x01.toByte(), frameOn[0]) // Version
        assertEquals(PulseShiftBleProtocol.CMD_SET_POWER, frameOn[1]) // Opcode
        assertEquals(5.toByte(), frameOn[2]) // Sequence
        assertEquals(1.toByte(), frameOn[3]) // Length
        assertEquals(1.toByte(), frameOn[4]) // Payload

        val frameOff = PulseShiftBleProtocol.buildSetPower(false, 6.toByte())
        assertEquals(0.toByte(), frameOff[4])
    }

    @Test
    fun testBuildSetBrightnessCommand() {
        val frame = PulseShiftBleProtocol.buildSetBrightness(204, 7.toByte())
        assertEquals(PulseShiftBleProtocol.CMD_SET_BRIGHTNESS, frame[1])
        assertEquals(7.toByte(), frame[2])
        assertEquals(1.toByte(), frame[3])
        assertEquals(204.toByte(), frame[4])
    }

    @Test
    fun testBuildSetRpmThresholds12BytesLittleEndian() {
        val thresholds = listOf(3000, 4000, 5000, 6000, 7000, 8000)
        val frame = PulseShiftBleProtocol.buildSetRpmThresholds(thresholds, 8.toByte())
        assertEquals(PulseShiftBleProtocol.CMD_SET_RPM_THRESHOLDS, frame[1])
        assertEquals(12.toByte(), frame[3])

        // 3000 = 0x0BB8 -> Low: 0xB8, High: 0x0B
        assertEquals(0xB8.toByte(), frame[4])
        assertEquals(0x0B.toByte(), frame[5])

        // 8000 = 0x1F40 -> Low: 0x40, High: 0x1F
        assertEquals(0x40.toByte(), frame[14])
        assertEquals(0x1F.toByte(), frame[15])
    }

    @Test
    fun testBuildSetShiftWarning() {
        val frame = PulseShiftBleProtocol.buildSetShiftWarning(8500, WarningStyle.FLASH, 9.toByte())
        assertEquals(PulseShiftBleProtocol.CMD_SET_SHIFT_WARNING, frame[1])
        assertEquals(3.toByte(), frame[3])
        // 8500 = 0x2134 -> Low: 0x34, High: 0x21
        assertEquals(0x34.toByte(), frame[4])
        assertEquals(0x21.toByte(), frame[5])
        assertEquals(WarningStyle.FLASH.id.toByte(), frame[6])
    }

    @Test
    fun testBuildResetDefaults() {
        val frame = PulseShiftBleProtocol.buildResetDefaults(10.toByte())
        assertEquals(PulseShiftBleProtocol.CMD_RESET_DEFAULTS, frame[1])
        assertEquals(1.toByte(), frame[3])
        assertEquals(0xA5.toByte(), frame[4])
    }

    @Test
    fun testParseAckResponse() {
        val ackBytes = byteArrayOf(0x01, 0x80.toByte(), 0x0C, 0x00, 0x00)
        val ack = PulseShiftBleProtocol.parseAck(ackBytes)
        assertNotNull(ack)
        assertEquals(0x0C.toByte(), ack!!.sequenceRef)
        assertEquals(PulseShiftBleProtocol.STATUS_SUCCESS, ack.statusCode)
        assertTrue(ack.isSuccess)
    }

    @Test
    fun testParseStateNotificationValidChecksum() {
        // Compute checksum for bytes 0..12:
        val raw = byteArrayOf(
            0x01, 0x81.toByte(), 0x00, 1, 200.toByte(), 2, 60,
            139.toByte(), 92, 246.toByte(), 0, 0, 0
        )
        var csum: Byte = 0
        for (b in raw) {
            csum = (csum.toInt() xor b.toInt()).toByte()
        }
        val stateBytes = raw + byteArrayOf(csum)

        val state = PulseShiftBleProtocol.parseState(stateBytes)
        assertNotNull(state)
        assertTrue(state!!.isPowerOn)
        assertEquals(200, state.brightness)
        assertEquals(EffectType.RAINBOW, state.effect)
        assertEquals(60, state.effectSpeed)
        assertEquals(139, state.globalColor.red)
        assertEquals(92, state.globalColor.green)
        assertEquals(246, state.globalColor.blue)
    }

    @Test
    fun testParseStateNotificationInvalidChecksum() {
        val stateBytes = byteArrayOf(
            0x01, 0x81.toByte(), 0x00, 1, 200.toByte(), 2, 60,
            139.toByte(), 92, 246.toByte(), 0, 0, 0, 0x7F.toByte() // Wrong checksum
        )
        val state = PulseShiftBleProtocol.parseState(stateBytes)
        assertNull(state)
    }
}
