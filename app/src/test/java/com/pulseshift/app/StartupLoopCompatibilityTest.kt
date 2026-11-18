package com.pulseshift.app

import com.pulseshift.app.data.ble.PulseShiftBleProtocol
import com.pulseshift.app.domain.model.DeviceCapabilities
import com.pulseshift.app.domain.model.LightingState
import com.pulseshift.app.domain.model.PresetApplyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupLoopCompatibilityTest {
    private val loopFlag = 0x8000

    @Test
    fun loopPeriodIsUnchangedAndSeparateFromFlag() {
        val once = LightingState(startupDurationMs = 2000)
        assertFalse(once.isStartupLooping)
        assertEquals(2000, once.startupCycleDurationMs)

        val repeat = LightingState(startupDurationMs = loopFlag or 2000)
        assertTrue(repeat.isStartupLooping)
        assertEquals(2000, repeat.startupCycleDurationMs)
    }

    @Test
    fun firmwareCapabilityGatesLoopControl() {
        val legacy = DeviceCapabilities.fromV2(
            physicalLedCount = 6, featureMask = 0x0004, configRevision = 1,
            hardwarePresetCapacity = 0, securityMode = 3
        )
        val updated = DeviceCapabilities.fromV2(
            physicalLedCount = 6, featureMask = 0x100004, configRevision = 1,
            hardwarePresetCapacity = 0, securityMode = 3
        )
        assertFalse(legacy.supportsLoopingStartup)
        assertTrue(updated.supportsLoopingStartup)
    }

    @Test
    fun currentWirePacketSizeAndOffsetsAreUnchanged() {
        val packet = PulseShiftBleProtocol.buildSetStartupV2(42, 2, loopFlag or 2000)
        assertEquals(9, packet.size)
        assertEquals(0x02, packet[0].toInt() and 0xFF)
        assertEquals(0x15, packet[1].toInt() and 0xFF)
        assertEquals(3, packet[4].toInt() and 0xFF)
        assertEquals(2, packet[6].toInt() and 0xFF)
        assertEquals(0xD0, packet[7].toInt() and 0xFF)
        assertEquals(0x87, packet[8].toInt() and 0xFF)
    }

    @Test
    fun presetFeedbackDistinguishesSavedFromRamOnly() {
        assertTrue(PresetApplyResult.PERSISTED.message("Sample").contains("saved to ESP32 NVS"))
        assertTrue(PresetApplyResult.READBACK_CONFIRMED.message("Sample").contains("flash save unconfirmed"))
    }
}
