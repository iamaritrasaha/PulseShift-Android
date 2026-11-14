package com.pulseshift.app

import com.pulseshift.app.domain.model.EffectType
import com.pulseshift.app.domain.model.Preset
import com.pulseshift.app.domain.model.RgbColor
import com.pulseshift.app.domain.model.ShiftLightConfig
import com.pulseshift.app.domain.model.StartupAnimation
import com.pulseshift.app.domain.model.WarningStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PresetPersistenceTest {

    @Test
    fun testDefaultPresetsContain1kTo6kTachometer() {
        val defaults = Preset.DefaultPresets
        assertTrue("Default presets should contain at least 5 profiles", defaults.size >= 5)

        val tachometerPreset = defaults.firstOrNull { it.id == "preset_brand" }
        assertNotNull("Tachometer preset 'preset_brand' must exist in defaults", tachometerPreset)
        assertEquals("PulseShift Tachometer (1k-6k)", tachometerPreset!!.name)
        assertEquals(EffectType.RPM_BAR, tachometerPreset.effect)
        assertEquals(6, tachometerPreset.ledColors.size)

        val shiftConfig = tachometerPreset.shiftLightConfig
        assertNotNull("Tachometer preset must include ShiftLightConfig", shiftConfig)
        assertEquals(listOf(1000, 2000, 3000, 4000, 5000, 6000), shiftConfig!!.thresholds)
        assertEquals(6500, shiftConfig.warningRpm)
        assertEquals(WarningStyle.FLASH, shiftConfig.warningStyle)
        assertTrue(shiftConfig.isStrictlyAscending())
    }

    @Test
    fun testAllDefaultPresetsHaveValidConfiguration() {
        Preset.DefaultPresets.forEach { preset ->
            assertNotNull(preset.id)
            assertTrue(preset.id.isNotBlank())
            assertNotNull(preset.name)
            assertTrue(preset.name.isNotBlank())
            assertEquals(6, preset.ledColors.size)
            assertTrue(preset.brightness in 0..255)
            assertTrue(preset.effectSpeed in 0..100)
            assertFalse(preset.isCustom)
            preset.shiftLightConfig?.let { cfg ->
                assertEquals(6, cfg.thresholds.size)
                assertTrue(cfg.isStrictlyAscending())
            }
        }
    }

    @Test
    fun testDuplicatePreset() {
        val original = Preset(
            id = "preset_test",
            name = "Track Mode",
            effect = EffectType.STATIC,
            ledColors = List(6) { RgbColor.RacingRed },
            brightness = 250,
            effectSpeed = 70,
            isCustom = true,
            shiftLightConfig = ShiftLightConfig(
                thresholds = listOf(2000, 3000, 4000, 5000, 6000, 7000),
                warningRpm = 7500
            )
        )

        val duplicate = original.copy(
            id = UUID.randomUUID().toString(),
            name = "${original.name} (Copy)",
            isCustom = true,
            createdAt = System.currentTimeMillis()
        )

        assertEquals("Track Mode (Copy)", duplicate.name)
        assertFalse(duplicate.id == original.id)
        assertTrue(duplicate.isCustom)
        assertEquals(original.effect, duplicate.effect)
        assertEquals(original.brightness, duplicate.brightness)
        assertEquals(original.shiftLightConfig, duplicate.shiftLightConfig)
    }

    @Test
    fun testPresetThresholdStrictAscendingValidation() {
        // Valid 1k-6k profile
        val validConfig = ShiftLightConfig(
            thresholds = listOf(1000, 2000, 3000, 4000, 5000, 6000),
            warningRpm = 6500
        )
        assertTrue(validConfig.isStrictlyAscending())

        // Warning lower than LED 6 threshold
        val invalidWarning = ShiftLightConfig(
            thresholds = listOf(1000, 2000, 3000, 4000, 5000, 6000),
            warningRpm = 5900
        )
        assertFalse(invalidWarning.isStrictlyAscending())

        // Non-monotonic thresholds (e.g. inverted step)
        val invertedStep = ShiftLightConfig(
            thresholds = listOf(1000, 3000, 2000, 4000, 5000, 6000),
            warningRpm = 6500
        )
        assertFalse(invertedStep.isStrictlyAscending())

        // Equal thresholds
        val equalStep = ShiftLightConfig(
            thresholds = listOf(1000, 2000, 3000, 3000, 5000, 6000),
            warningRpm = 6500
        )
        assertFalse(equalStep.isStrictlyAscending())
    }

    @Test
    fun testPresetDirectionAndStartupAnimationDefaults() {
        val preset = Preset(
            id = "preset_simple",
            name = "Simple Preset",
            effect = EffectType.BREATHING,
            ledColors = List(6) { RgbColor.AmberWarning }
        )

        assertEquals(0, preset.ledDirection)
        assertEquals(StartupAnimation.SWEEP, preset.startupAnimation)
        assertEquals(2000, preset.startupDurationMs)
    }
}
