package com.pulseshift.app

import com.pulseshift.app.domain.model.RgbColor
import com.pulseshift.app.domain.model.ShiftLightConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorConversionTest {

    @Test
    fun testRgbToHsvAndBack() {
        val original = RgbColor(255, 0, 0) // Pure Red
        val hsv = original.toHsv()
        assertEquals(0f, hsv[0], 0.1f)
        assertEquals(1f, hsv[1], 0.01f)
        assertEquals(1f, hsv[2], 0.01f)

        val restored = RgbColor.fromHsv(hsv[0], hsv[1], hsv[2])
        assertEquals(255, restored.red)
        assertEquals(0, restored.green)
        assertEquals(0, restored.blue)
    }

    @Test
    fun testHexConversion() {
        val color = RgbColor.fromHex("#8B5CF6")
        assertEquals(139, color.red)
        assertEquals(92, color.green)
        assertEquals(246, color.blue)
        assertEquals("#8B5CF6", color.hexString)
    }
}

class RpmThresholdValidationTest {

    @Test
    fun testValidAscendingThresholds() {
        val config = ShiftLightConfig(
            thresholds = listOf(3000, 4000, 5000, 6000, 7000, 8000),
            warningRpm = 8500
        )
        assertTrue(config.isStrictlyAscending())
    }

    @Test
    fun testInvalidNonAscendingThresholds() {
        val config = ShiftLightConfig(
            thresholds = listOf(3000, 4000, 5500, 5000, 7000, 8000),
            warningRpm = 8500
        )
        assertFalse(config.isStrictlyAscending())
    }

    @Test
    fun testWarningRpmLowerThanFinalThresholdIsInvalid() {
        val config = ShiftLightConfig(
            thresholds = listOf(3000, 4000, 5000, 6000, 7000, 8000),
            warningRpm = 7500 // Lower than LED 6
        )
        assertFalse(config.isStrictlyAscending())
    }
}
