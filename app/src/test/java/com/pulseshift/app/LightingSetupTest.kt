package com.pulseshift.app

import com.pulseshift.app.data.ble.LightingSetupCodec
import com.pulseshift.app.data.ble.PulseShiftBleProtocol
import com.pulseshift.app.domain.model.AnimationSettings
import com.pulseshift.app.domain.model.ConfigAckResponse
import com.pulseshift.app.domain.model.ConfigSnapshot
import com.pulseshift.app.domain.model.LightingSetup
import com.pulseshift.app.domain.model.LightingState
import com.pulseshift.app.domain.model.RgbColor
import com.pulseshift.app.domain.model.RpmMapping
import com.pulseshift.app.domain.model.RpmMappingMode
import com.pulseshift.app.domain.model.StartupAnimation
import com.pulseshift.app.domain.model.StripConfiguration
import com.pulseshift.app.domain.model.distributedThresholds
import com.pulseshift.app.ui.screens.rpm.RpmSimulation
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class LightingSetupTest {
    private val boot = AnimationSettings(false, StartupAnimation.CENTER_OUT, 3000)
    private val ambient = AnimationSettings(true, StartupAnimation.WAVE, 10_000)
    private val setup = LightingSetup(64, RpmMapping(RpmMappingMode.INDIVIDUAL, 64,
        List(64) { 1200 + 100 * it }), boot, ambient, 9000)

    @Test
    fun encodesAll64ThresholdsWithIndependentBootAndAmbientFields() {
        val bytes = LightingSetupCodec.encode(setup)
        assertEquals(143, bytes.size)
        assertArrayEquals(byteArrayOf(1, 64, 64, 2, 64, 0, 4, -72, 11, 1, 2, 16, 39, 40, 35),
            bytes.copyOfRange(0, 15))
        assertEquals(setup, LightingSetupCodec.decode(bytes))
        assertNull(LightingSetupCodec.decode(bytes.copyOf(bytes.size - 1)))
        assertNull(LightingSetupCodec.decode(bytes.clone().also { it[3] = 4 }))
        assertNull(LightingSetupCodec.decode(bytes.clone().also { it[1] = 63 }))
        val begin = ByteBuffer.wrap(LightingSetupCodec.begin(bytes, 91)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, begin.get().toInt())
        assertEquals(143, begin.short.toInt())
        assertEquals(CRC32().apply { update(bytes) }.value.toInt(), begin.int)
        assertEquals(91, begin.int)
        for (offset in bytes.indices step LightingSetupCodec.CHUNK) {
            val chunk = LightingSetupCodec.chunk(0x1001, offset, bytes.copyOfRange(offset, minOf(offset + 10, bytes.size)))
            assertTrue(chunk.size <= 14)
            assertEquals(offset, ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).getShort(2).toInt())
        }
    }

    @Test
    fun automaticGroupedAndLegacySemanticsMatchFirmware() {
        assertEquals(listOf(2000, 2545, 3091, 3636, 4182, 4727, 5273, 5818, 6364, 6909, 7455, 8000),
            distributedThresholds(12, 2000, 8000))
        assertEquals(listOf(2000), distributedThresholds(1, 2000, 8000))
        val grouped = RpmMapping(RpmMappingMode.GROUPED, 12, (1..6).map { it * 1000 }, listOf(3, 3, 2, 2, 1, 1))
        val groupedSetup = setup.copy(installedLedCount = 24, mapping = grouped)
        assertEquals(groupedSetup, LightingSetupCodec.decode(LightingSetupCodec.encode(groupedSetup)))
        assertEquals(listOf(1000, 1000, 1000, 2000, 2000, 2000, 3000, 3000, 4000, 4000, 5000, 6000),
            grouped.expand(12, emptyList()))
        assertFalse(grouped.copy(groupSizes = listOf(3, 3, 2, 2, 1, 2)).valid(24, 9000))
        assertEquals(listOf(3, 3, 1), groupedSetup.resized(12, 7).mapping.groupSizes)
        assertEquals(listOf(1000, 3000, 5000),
            RpmMapping(RpmMappingMode.LEGACY_GROUPED, 0, emptyList()).expand(3, grouped.thresholds))
        assertEquals(0, PulseShiftBleProtocol.buildGetSnapshotV2(42)[4].toInt())
        assertEquals(1, PulseShiftBleProtocol.buildGetSnapshotV2(42, includeLighting = true)[6].toInt())
    }

    @Test
    fun negotiatesExtensionOnlyWhenBothVersionAndCapabilityArePresent() {
        val packet = ByteArray(20).apply {
            this[0] = 2
            this[1] = 6
            this[4] = 0x80.toByte()
            this[12] = 2
            this[13] = 5
            this[18] = 4
        }
        assertFalse(PulseShiftBleProtocol.parseCapabilitiesV2(packet)!!.supportsLightingSetup)
        packet[18] = 5
        assertTrue(PulseShiftBleProtocol.parseCapabilitiesV2(packet)!!.supportsLightingSetup)
        packet[4] = 0
        assertFalse(PulseShiftBleProtocol.parseCapabilitiesV2(packet)!!.supportsLightingSetup)
    }

    @Test
    fun inactivePixelsDoNotSelectAColorZone() {
        val state = LightingState(physicalLedCount = 12, activeLedCount = 3, ledDirection = 1)
        assertEquals(List(9) { RgbColor(0, 0, 0) }, state.physicalLedColors.take(9))
        assertEquals(listOf(4, 2, 0), (9..11).map(state::zoneAtPixel))
        assertEquals(-1, state.zoneAtPixel(8))
    }

    @Test
    fun onlyMatchingPersistedReadbackConfirmsSuccess() {
        val snapshot = ConfigSnapshot(
            activeRevision = 92, lightingSetup = setup, matchesFlash = true,
            savedInstalledLedCount = 64, strip = StripConfiguration(64, 6, 1..64)
        )
        val ack = ConfigAckResponse(opcode = PulseShiftBleProtocol.V2_OPCODE_LIGHTING_SAVE,
            sequenceRef = 2, statusCode = 0, resultFlags = 5, activeRevision = 92)
        assertTrue(LightingSetupCodec.verifiesSave(ack, snapshot, setup, 92))
        assertEquals(6, snapshot.strip?.runningCount)
        assertFalse(LightingSetupCodec.verifiesSave(ack.copy(resultFlags = 4), snapshot, setup, 92))
        assertFalse(LightingSetupCodec.verifiesSave(ack.copy(resultFlags = 13), snapshot, setup, 92))
        assertFalse(LightingSetupCodec.verifiesSave(ack, snapshot.copy(matchesFlash = false), setup, 92))
        assertFalse(LightingSetupCodec.verifiesSave(ack, snapshot.copy(activeRevision = 93), setup, 92))
        assertFalse(LightingSetupCodec.verifiesSave(ack, snapshot.copy(savedInstalledLedCount = 6), setup, 92))
        assertFalse(LightingSetupCodec.verifiesSave(ack, snapshot.copy(lightingSetup = setup.copy(ambient = boot)), setup, 92))
    }

    @Test
    fun syntheticRpmPreservesDirectionHysteresisWarningsAndInactivePixels() {
        val color = RgbColor(210, 50, 10)
        val warning = RgbColor(255, 8, 0)
        val black = RgbColor(0, 0, 0)
        val snapshot = ConfigSnapshot(ledDirection = 1, rpmHysteresis = 150, brightness = 255,
            ledColors = List(6) { color }, warningColor = warning, warningPatternId = 1)
        val preview = RpmSimulation(snapshot, 5, listOf(1000, 1500, 1500), 2000)
        assertEquals(listOf(black, black, black, black, color), preview.frame(1200, 0))
        assertEquals(listOf(black, black, color, color, color), preview.frame(1500, 100))
        assertEquals(listOf(black, black, color, color, color), preview.frame(1350, 110))
        assertEquals(listOf(black, black, black, black, color), preview.frame(1349, 120))
        assertEquals(listOf(black, black, warning, warning, warning), preview.frame(2000, 130))
        assertEquals(List(5) { black }, preview.frame(1870, 180))
        assertEquals(listOf(black, black, warning, warning, warning), preview.frame(1870, 230))
        assertEquals(List(5) { black }, preview.frame(0, 240))
    }
}
