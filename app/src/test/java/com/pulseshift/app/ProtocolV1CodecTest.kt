package com.pulseshift.app

import com.pulseshift.app.data.ble.DiagnosticLogReassembler
import com.pulseshift.app.data.ble.GattOp
import com.pulseshift.app.data.ble.PulseShiftBleProtocol
import com.pulseshift.app.domain.model.LogSeverity
import com.pulseshift.app.domain.model.RpmStatusFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ProtocolV1CodecTest {

    @Test
    fun testParseRpmTelemetryPacketValid() {
        val buffer = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0, 0x01.toByte()) // Version = 1
        buffer.put(1, 0x11.toByte()) // Bit 0 (Fresh) + Bit 4 (CAN healthy) = 0x11
        buffer.putShort(2, 42.toShort()) // Sequence = 42
        buffer.putInt(4, 123456) // Uptime = 123456 ms
        buffer.putShort(8, 1760.toShort()) // Provisional RPM = 1760
        buffer.put(10, 44.toByte()) // Raw candidate CAN value = 44
        buffer.put(11, 2.toByte()) // Capture stage = 2
        buffer.putInt(12, 98765) // Total CAN frames = 98765
        buffer.putShort(16, 15.toShort()) // RPM sample age = 15 ms
        buffer.putShort(18, 0.toShort()) // Reserved = 0

        val rpmState = PulseShiftBleProtocol.parseRpmTelemetry(buffer.array())
        assertNotNull(rpmState)
        assertEquals(1, rpmState!!.protocolVersion)
        assertTrue(rpmState.statusFlags.isCandidateFresh)
        assertFalse(rpmState.statusFlags.isCalibrationVerified)
        assertTrue(rpmState.statusFlags.isCanHealthy)
        assertEquals(42, rpmState.sequence)
        assertEquals(123456L, rpmState.uptimeMs)
        assertEquals(1760, rpmState.provisionalRpm)
        assertEquals(44, rpmState.rawCandidateCanValue)
        assertEquals(2, rpmState.captureStage)
        assertEquals(98765L, rpmState.totalCanFramesReceived)
        assertEquals(15, rpmState.rpmSampleAgeMs)

        assertTrue(rpmState.isRpmAvailable)
        assertFalse(rpmState.isStale)
        assertEquals("1760 RPM", rpmState.displayRpmValueText)
        assertEquals("Estimated RPM — Unverified", rpmState.displayLabelText)
    }

    @Test
    fun testParseRpmTelemetryUnavailableSentinel() {
        val buffer = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0, 0x01.toByte())
        buffer.put(1, 0x01.toByte()) // Fresh candidate, unverified
        buffer.putShort(2, 1.toShort())
        buffer.putInt(4, 5000)
        buffer.putShort(8, 65535.toShort()) // Sentinel 65535 = unavailable
        buffer.put(10, 0.toByte())
        buffer.put(11, 0.toByte())
        buffer.putInt(12, 10)
        buffer.putShort(16, 65535.toShort()) // Age unavailable
        buffer.putShort(18, 0.toShort())

        val rpmState = PulseShiftBleProtocol.parseRpmTelemetry(buffer.array())
        assertNotNull(rpmState)
        assertFalse(rpmState!!.isRpmAvailable)
        assertEquals("—", rpmState.displayRpmValueText)
        assertEquals("RPM Unavailable", rpmState.displayLabelText)
    }

    @Test
    fun testParseCanHealthPacket() {
        val buffer = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0, 0x01.toByte()) // Version = 1
        buffer.put(1, 0x0D.toByte()) // Bit 0 (Mounted) + Bit 2 (Degraded) + Bit 3 (BLE connected)
        buffer.putShort(2, 100.toShort()) // Sequence = 100
        buffer.putInt(4, 5) // Missed frames = 5
        buffer.putInt(8, 2) // FIFO overruns = 2
        buffer.putInt(12, 0) // Bus errors = 0
        buffer.putInt(16, 54321) // Recorded frames = 54321

        val health = PulseShiftBleProtocol.parseCanHealth(buffer.array())
        assertNotNull(health)
        assertEquals(1, health!!.version)
        assertTrue(health.healthFlags.isLittleFsMounted)
        assertFalse(health.healthFlags.isStorageFull)
        assertTrue(health.healthFlags.isReceivePerformanceDegraded)
        assertTrue(health.healthFlags.isBleClientConnected)
        assertEquals(5L, health.driverMissedFrames)
        assertEquals(2L, health.hardwareFifoOverruns)
        assertEquals(0L, health.canBusErrors)
        assertEquals(54321L, health.recordedFramesInCurrentSession)
        assertTrue(health.hasLossOrErrors)
    }

    @Test
    fun testBuildControlRequests() {
        val fetchCmd = PulseShiftBleProtocol.buildFetchHistoricalLogPage(1024L)
        assertEquals(6, fetchCmd.size)
        assertEquals(0x01.toByte(), fetchCmd[0])
        assertEquals(PulseShiftBleProtocol.CONTROL_OPCODE_FETCH_HISTORICAL_LOG, fetchCmd[1])
        // 1024 = 0x00000400 LE -> 0x00, 0x04, 0x00, 0x00
        assertEquals(0x00.toByte(), fetchCmd[2])
        assertEquals(0x04.toByte(), fetchCmd[3])
        assertEquals(0x00.toByte(), fetchCmd[4])
        assertEquals(0x00.toByte(), fetchCmd[5])

        val cancelCmd = PulseShiftBleProtocol.buildCancelHistoricalLogRetrieval()
        assertEquals(2, cancelCmd.size)
        assertEquals(0x01.toByte(), cancelCmd[0])
        assertEquals(PulseShiftBleProtocol.CONTROL_OPCODE_CANCEL_HISTORICAL_LOG, cancelCmd[1])
    }

    @Test
    fun testDiagnosticLogReassemblyMultiChunk() {
        val reassembler = DiagnosticLogReassembler()

        // Message: "CAN initialized" split across 2 chunks
        val msgId = 42
        val part1 = "CAN init".toByteArray(Charsets.UTF_8)
        val part2 = "ialized".toByteArray(Charsets.UTF_8)

        // Chunk 0 (First chunk, not final)
        val chunk0 = ByteArray(6 + part1.size)
        chunk0[0] = 1 // Version
        chunk0[1] = 0x01 // Bit 0: First chunk
        chunk0[2] = (msgId and 0xFF).toByte()
        chunk0[3] = ((msgId shr 8) and 0xFF).toByte()
        chunk0[4] = 0 // Chunk index = 0
        chunk0[5] = part1.size.toByte()
        System.arraycopy(part1, 0, chunk0, 6, part1.size)

        // Chunk 1 (Final chunk, not first)
        val chunk1 = ByteArray(6 + part2.size)
        chunk1[0] = 1
        chunk1[1] = 0x02 // Bit 1: Final chunk
        chunk1[2] = (msgId and 0xFF).toByte()
        chunk1[3] = ((msgId shr 8) and 0xFF).toByte()
        chunk1[4] = 1 // Chunk index = 1
        chunk1[5] = part2.size.toByte()
        System.arraycopy(part2, 0, chunk1, 6, part2.size)

        reassembler.processChunkBytes(chunk0)
        val parsedChunk1 = reassembler.processChunkBytes(chunk1)
        assertNotNull(parsedChunk1)
    }

    @Test
    fun testPageEndNotification() {
        val reassembler = DiagnosticLogReassembler()
        // Page End: payload len = 5, Bit 2 set (0x04)
        // Payload: nextOffset = 2048 (0x00000800 LE), finished = 1
        val payload = byteArrayOf(0x00, 0x08, 0x00, 0x00, 0x01)
        val packet = ByteArray(6 + 5)
        packet[0] = 1 // Version
        packet[1] = 0x04 // Bit 2: End of page
        packet[2] = 0 // MsgId
        packet[3] = 0
        packet[4] = 0 // Chunk index
        packet[5] = 5 // Payload length
        System.arraycopy(payload, 0, packet, 6, 5)

        val resultChunk = reassembler.processChunkBytes(packet)
        assertNotNull(resultChunk)
        assertTrue(resultChunk!!.flags.isEndOfHistoricalPage)
    }

    @Test
    fun testDiagnosticSeverityParsingErrorClassification() {
        val reassembler = DiagnosticLogReassembler()
        val text = "CAN bus ERROR detected"
        val payload = text.toByteArray(Charsets.UTF_8)
        val packet = ByteArray(6 + payload.size)
        packet[0] = 1
        packet[1] = 0x03 // Bit 0 (First) + Bit 1 (Final)
        packet[2] = 99
        packet[3] = 0
        packet[4] = 0
        packet[5] = payload.size.toByte()
        System.arraycopy(payload, 0, packet, 6, payload.size)

        reassembler.processChunkBytes(packet)
        // The completed message must actually arrive and classify as ERROR;
        // an empty replay cache is a failure, not a pass.
        val emitted = reassembler.completedMessages.replayCache.lastOrNull()
        assertNotNull("Reassembler must emit the completed single-chunk message", emitted)
        assertEquals(LogSeverity.ERROR, emitted!!.severity)
    }
}
