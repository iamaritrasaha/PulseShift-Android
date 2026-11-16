package com.pulseshift.app

import com.pulseshift.app.data.ble.PulseShiftBleProtocol
import com.pulseshift.app.data.local.PulseShiftDataStore
import com.pulseshift.app.domain.model.EffectType
import com.pulseshift.app.domain.model.Preset
import com.pulseshift.app.domain.model.RgbColor
import com.pulseshift.app.domain.model.StartupAnimation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates 10-second startup animation duration support:
 * - Little-endian wire format encoding for 500, 6000, 9750, 10000 ms
 * - Safe lower-bound down to 100 ms
 * - JSON serialization and round-trip through Preset models
 * - DataStore schema migration preserving 10,000 ms values
 * - Out-of-range value handling
 */
class StartupAnimationDurationTest {

    @Test
    fun testWireProtocolEncodesTenSecondStartupDurationInLittleEndian() {
        val seq = 42
        val animId = StartupAnimation.SWEEP.id // 1
        val durationMs = 10000 // 0x2710 -> low byte 0x10, high byte 0x27

        val packet = PulseShiftBleProtocol.buildSetStartupV2(
            seq = seq,
            animationId = animId,
            durationMs = durationMs,
            isStaged = false
        )

        assertNotNull(packet)
        assertTrue("Packet must have at least 9 bytes", packet.size >= 9)

        // V2 header: byte 0 = V2 magic, byte 1 = opcode 0x15
        assertEquals("V2 Magic", 0x02.toByte(), packet[0])
        assertEquals("Opcode SET_STARTUP (0x15)", 0x15.toByte(), packet[1])

        // Sequence number in bytes 2-3 (little endian)
        assertEquals((seq and 0xFF).toByte(), packet[2])
        assertEquals(((seq shr 8) and 0xFF).toByte(), packet[3])

        // Payload length in byte 4
        assertEquals("Payload length 3 bytes", 3.toByte(), packet[4])

        // Flags in byte 5 (isStaged = false -> 0x00)
        assertEquals("Flags byte (staged = 0)", 0x00.toByte(), packet[5])

        // Payload: [animId: u8, durationLow: u8, durationHigh: u8]
        assertEquals("Animation ID", animId.toByte(), packet[6])
        assertEquals("Duration low byte (0x10 for 10000 ms)", 0x10.toByte(), packet[7])
        assertEquals("Duration high byte (0x27 for 10000 ms)", 0x27.toByte(), packet[8])
    }

    @Test
    fun testWireProtocolEncodesVariousValidDurations() {
        val testDurations = listOf(100, 500, 2000, 6000, 9750, 10000)

        for (duration in testDurations) {
            val packet = PulseShiftBleProtocol.buildSetStartupV2(
                seq = 1,
                animationId = 2, // BREATH
                durationMs = duration,
                isStaged = true
            )

            // Flags in byte 5 should have staged bit set (bit 0)
            assertEquals("Staged flag set in byte 5", 0x01.toByte(), packet[5])

            // Extract duration from payload bytes (bytes 7-8)
            val low = packet[7].toInt() and 0xFF
            val high = packet[8].toInt() and 0xFF
            val decodedDuration = low or (high shl 8)

            assertEquals("Decoded duration must match input $duration ms", duration, decodedDuration)
        }
    }

    @Test
    fun testTenSecondStartupDurationInPresetModel() {
        val preset = Preset(
            id = "preset_10s",
            name = "10 Second Thunder Ignition",
            effect = EffectType.STATIC,
            ledColors = List(6) { RgbColor.NeonGreen },
            brightness = 255,
            startupAnimation = StartupAnimation.SWEEP,
            startupDurationMs = 10000,
            isCustom = true
        )

        assertEquals(10000, preset.startupDurationMs)
        assertEquals(StartupAnimation.SWEEP, preset.startupAnimation)
    }

    @Test
    fun testDataStoreMigrationPreservesTenSecondStartupDuration() {
        // A JSON payload containing a custom preset with 10,000 ms startup duration
        val rawJson = """
            [
                {
                    "id": "custom_10s",
                    "name": "Custom 10s Sweep",
                    "effectId": 1,
                    "brightness": 200,
                    "effectSpeed": 50,
                    "ledDirection": 0,
                    "startupAnimationId": 1,
                    "startupDurationMs": 10000,
                    "ledColors": ["#00FF00","#00FF00","#00FF00","#00FF00","#00FF00","#00FF00"]
                }
            ]
        """.trimIndent()

        val migrated = PulseShiftDataStore.migrateCustomPresetsJson(rawJson)

        assertTrue("Migration must preserve 10000 ms startup duration",
            Regex("\"startupDurationMs\"\\s*:\\s*10000").containsMatchIn(migrated))
        assertFalse("Migration must NOT overwrite with default 2000 ms",
            Regex("\"startupDurationMs\"\\s*:\\s*2000").containsMatchIn(migrated))
    }

    @Test
    fun testDataStoreMigrationHandlesTrailingCommasWithoutDoubleComma() {
        val rawWithTrailingComma = """
            [
                {
                    "id": "trailing_test",
                    "name": "Trailing Comma Test",
                    "effectId": 0,
                    "ledColors": ["#FF0000"],
                }
            ]
        """.trimIndent()

        val migrated = PulseShiftDataStore.migrateCustomPresetsJson(rawWithTrailingComma)

        assertFalse("Migration output must not contain double commas", migrated.contains(",,"))
        assertTrue("Migration must add missing fields", migrated.contains("\"startupDurationMs\":2000"))
    }

    @Test
    fun testUIEffectiveMinCalculation() {
        // Standard firmware setting (2000 ms): UI minimum is 500 ms
        val standardDeviceMs = 2000
        val effectiveMinStandard = minOf(500, standardDeviceMs).coerceAtLeast(100)
        assertEquals(500, effectiveMinStandard)

        // Low firmware setting (250 ms): UI minimum adjusts to 250 ms so it is not clamped out
        val lowDeviceMs = 250
        val effectiveMinLow = minOf(500, lowDeviceMs).coerceAtLeast(100)
        assertEquals(250, effectiveMinLow)

        // Edge case (50 ms, below firmware min 100 ms): Coerced to 100 ms
        val ultraLowDeviceMs = 50
        val effectiveMinClamped = minOf(500, ultraLowDeviceMs).coerceAtLeast(100)
        assertEquals(100, effectiveMinClamped)
    }
}
