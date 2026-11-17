package com.pulseshift.app

import com.pulseshift.app.data.ble.PulseShiftBleProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentalVoltageProtocolTest {
    private fun packet(voltageByte: Int, marker: Int): ByteArray = ByteArray(20).apply {
        this[0] = 1 // v1 20-byte RPM telemetry packet
        this[8] = 0xFF.toByte()
        this[9] = 0xFF.toByte()
        this[16] = 0xFF.toByte()
        this[17] = 0xFF.toByte()
        this[18] = voltageByte.toByte()
        this[19] = marker.toByte()
    }

    @Test
    fun freshProbeFrameDecodesFromOnlyReservedBytes() {
        val decoded = PulseShiftBleProtocol.parseRpmTelemetry(packet(122, 0xA5))!!
        assertEquals(0xA57A, decoded.reserved)
        assertTrue(decoded.experimentalVoltageProbeSupported)
        assertEquals(122, decoded.experimentalVoltageRaw)
        assertEquals(12.2, decoded.experimentalVoltageVolts!!, 0.00001)
        assertEquals(65535, decoded.provisionalRpm) // RPM fields unaffected
    }

    @Test
    fun oldFirmwareAndStaleSignalHaveNoVoltageEstimate() {
        val old = PulseShiftBleProtocol.parseRpmTelemetry(packet(0, 0))!!
        assertFalse(old.experimentalVoltageProbeSupported)
        assertNull(old.experimentalVoltageRaw)
        assertNull(old.experimentalVoltageVolts)

        val stale = PulseShiftBleProtocol.parseRpmTelemetry(packet(0, 0x5A))!!
        assertTrue(stale.experimentalVoltageProbeSupported)
        assertNull(stale.experimentalVoltageRaw)
        assertNull(stale.experimentalVoltageVolts)
    }

    @Test
    fun invalidPacketsCannotCreateExperimentalVolts() {
        assertNull(PulseShiftBleProtocol.parseRpmTelemetry(ByteArray(19)))
        assertNull(PulseShiftBleProtocol.parseRpmTelemetry(packet(122, 0xA5).also { it[0] = 2 }))
    }

    @Test
    fun valueZeroIsReportedOnlyWhenFirmwareExplicitlyMarksTheActualByteFresh() {
        val state = PulseShiftBleProtocol.parseRpmTelemetry(packet(0, 0xA5))!!
        assertEquals(0.0, state.experimentalVoltageVolts!!, 0.00001)
    }
}
