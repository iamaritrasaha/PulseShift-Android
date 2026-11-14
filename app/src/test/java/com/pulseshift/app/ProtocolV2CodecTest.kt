package com.pulseshift.app

import com.pulseshift.app.data.ble.ConfigSnapshotReassembler
import com.pulseshift.app.data.ble.PulseShiftBleProtocol
import com.pulseshift.app.domain.model.ConfigSnapshot
import com.pulseshift.app.domain.model.DeviceCapabilities
import com.pulseshift.app.domain.model.EffectType
import com.pulseshift.app.domain.model.StartupAnimation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32

class ProtocolV2CodecTest {

    @Test
    fun testBuildSetPowerV2() {
        val frame = PulseShiftBleProtocol.buildSetPowerV2(seq = 101, power = true, isStaged = false)
        assertEquals(7, frame.size)
        assertEquals(0x02.toByte(), frame[0]) // Version 2
        assertEquals(PulseShiftBleProtocol.V2_OPCODE_SET_POWER, frame[1])
        val seq = (frame[2].toInt() and 0xFF) or ((frame[3].toInt() and 0xFF) shl 8)
        assertEquals(101, seq)
        assertEquals(1.toByte(), frame[4]) // Payload len = 1
        assertEquals(0.toByte(), frame[5]) // Flags = 0 (Unstaged)
        assertEquals(1.toByte(), frame[6]) // Power ON = 1
    }

    @Test
    fun testBuildSetRpmMultiplierV2() {
        val frame = PulseShiftBleProtocol.buildSetRpmMultiplierV2(seq = 500, multiplier = 40.0f, isStaged = true)
        assertEquals(10, frame.size)
        assertEquals(0x02.toByte(), frame[0])
        assertEquals(PulseShiftBleProtocol.V2_OPCODE_SET_RPM_MULTIPLIER, frame[1])
        assertEquals(4.toByte(), frame[4]) // Payload len = 4
        assertEquals(1.toByte(), frame[5]) // Flags = 1 (Staged)

        val multX1000 = (frame[6].toLong() and 0xFF) or
                ((frame[7].toLong() and 0xFF) shl 8) or
                ((frame[8].toLong() and 0xFF) shl 16) or
                ((frame[9].toLong() and 0xFF) shl 24)
        assertEquals(40000L, multX1000)
    }

    @Test
    fun testBuildCandidateSourceV2() {
        val frame = PulseShiftBleProtocol.buildSetCandidateSourceV2(
            seq = 777,
            canId = 0x301L,
            byteIndex = 0,
            isExtended = false,
            isStaged = false
        )
        assertEquals(12, frame.size)
        assertEquals(6.toByte(), frame[4]) // Payload len = 6
        val canId = (frame[6].toLong() and 0xFF) or
                ((frame[7].toLong() and 0xFF) shl 8) or
                ((frame[8].toLong() and 0xFF) shl 16) or
                ((frame[9].toLong() and 0xFF) shl 24)
        assertEquals(0x301L, canId)
        assertEquals(0.toByte(), frame[10]) // Byte index = 0
        assertEquals(0.toByte(), frame[11]) // Extended frame = 0
    }

    @Test
    fun testEnrollOwnerFrame() {
        val secret = ByteArray(12) { (it + 1).toByte() }
        val frame = PulseShiftBleProtocol.buildEnrollOwnerV2(seq = 100, ownerSecret = secret)
        assertEquals(18, frame.size)
        assertEquals(0x02.toByte(), frame[0])
        assertEquals(PulseShiftBleProtocol.V2_OPCODE_ENROLL_OWNER, frame[1])
        assertEquals(12.toByte(), frame[4])
        for (i in 0 until 12) {
            assertEquals((i + 1).toByte(), frame[6 + i])
        }
    }

    @Test
    fun testChallengeResponseProof() {
        val secret = ByteArray(12) { (it * 2).toByte() }
        val nonce = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val seq = 500

        val proof = PulseShiftBleProtocol.computeAuthProof(secret, nonce, seq)
        assertEquals(8, proof.size)

        val frame = PulseShiftBleProtocol.buildAuthenticateV2(seq, proof)
        assertEquals(14, frame.size)
        assertEquals(PulseShiftBleProtocol.V2_OPCODE_AUTHENTICATE, frame[1])
        assertEquals(8.toByte(), frame[4])
    }

    @Test
    fun testCrossLanguageAuthProofSha256Fixture() {
        // Test exact SHA-256 fixture for: secret (12 bytes), nonce (4 bytes), sequence (2 bytes LE)
        val secret = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        val nonce = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val sequence = 0x1234 // 4660 -> LE [0x34, 0x12]

        val expectedBuffer = byteArrayOf(
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
            0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(),
            0x34.toByte(), 0x12.toByte()
        )
        val expectedSha = MessageDigest.getInstance("SHA-256").digest(expectedBuffer)
        val expectedProof = expectedSha.copyOf(8)

        val computedProof = PulseShiftBleProtocol.computeAuthProof(secret, nonce, sequence)
        assertEquals(8, computedProof.size)
        for (i in 0 until 8) {
            assertEquals(expectedProof[i], computedProof[i])
        }
    }

    @Test
    fun testExhaustiveFirmwareStatusCodesMapping() {
        // Exact 9 status codes from ble_protocol.h: 0x00..0x08
        assertEquals(0x00.toByte(), PulseShiftBleProtocol.STATUS_SUCCESS)
        assertEquals(0x01.toByte(), PulseShiftBleProtocol.STATUS_ERR_INVALID_PARAM)
        assertEquals(0x02.toByte(), PulseShiftBleProtocol.STATUS_ERR_UNSUPPORTED)
        assertEquals(0x03.toByte(), PulseShiftBleProtocol.STATUS_ERR_BUSY)
        assertEquals(0x04.toByte(), PulseShiftBleProtocol.STATUS_ERR_UNAUTHORIZED)
        assertEquals(0x05.toByte(), PulseShiftBleProtocol.STATUS_ERR_INVALID_STATE)
        assertEquals(0x06.toByte(), PulseShiftBleProtocol.STATUS_ERR_STORAGE_ERROR)
        assertEquals(0x07.toByte(), PulseShiftBleProtocol.STATUS_ERR_VERSION_MISMATCH)
        assertEquals(0x08.toByte(), PulseShiftBleProtocol.STATUS_ERR_VALIDATION_FAILED)
    }

    @Test
    fun testParseConfigAckSuccess() {
        val buffer = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0, 0x02.toByte()) // Version 2
        buffer.put(1, PulseShiftBleProtocol.V2_OPCODE_SAVE_CONFIG) // Opcode
        buffer.putShort(2, 123.toShort()) // Seq = 123
        buffer.put(4, 0.toByte()) // Status = 0 (OK)
        buffer.put(5, 0x05.toByte()) // Bit 0 (Persisted) + Bit 2 (Applied) = 0x05
        buffer.putInt(6, 4242) // Revision = 4242
        buffer.put(10, 0.toByte()) // Response payload len = 0

        val ack = PulseShiftBleProtocol.parseConfigAck(buffer.array())
        assertNotNull(ack)
        assertEquals(2, ack!!.version)
        assertEquals(123, ack.sequenceRef)
        assertTrue(ack.isSuccess)
        assertTrue(ack.isPersisted)
        assertTrue(ack.isApplied)
        assertFalse(ack.isStagedOnly)
        assertEquals(4242L, ack.activeRevision)
    }

    @Test
    fun testParseUnauthorizedAckStatus0x04() {
        val buffer = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0, 0x02.toByte())
        buffer.put(1, PulseShiftBleProtocol.V2_OPCODE_SAVE_CONFIG)
        buffer.putShort(2, 88.toShort())
        buffer.put(4, PulseShiftBleProtocol.STATUS_ERR_UNAUTHORIZED) // Status = 0x04
        buffer.put(5, 0.toByte())
        buffer.putInt(6, 0)
        buffer.put(10, 0.toByte())

        val ack = PulseShiftBleProtocol.parseConfigAck(buffer.array())
        assertNotNull(ack)
        assertEquals(PulseShiftBleProtocol.STATUS_ERR_UNAUTHORIZED, ack!!.statusCode)
        assertFalse(ack.isSuccess)
    }

    @Test
    fun testStagedAckFlagsNotApplied() {
        val buffer = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0, 0x02.toByte()) // Version 2
        buffer.put(1, PulseShiftBleProtocol.V2_OPCODE_SET_BRIGHTNESS)
        buffer.putShort(2, 200.toShort())
        buffer.put(4, 0.toByte()) // Status = 0 (OK)
        buffer.put(5, 0x02.toByte()) // Bit 1 (Staged Only)
        buffer.putInt(6, 100)
        buffer.put(10, 0.toByte())

        val ack = PulseShiftBleProtocol.parseConfigAck(buffer.array())
        assertNotNull(ack)
        assertTrue(ack!!.isSuccess)
        assertTrue(ack.isStagedOnly)
        assertFalse(ack.isApplied)
        assertFalse(ack.isPersisted)
    }

    @Test
    fun testParseCapabilitiesV2() {
        val buffer = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0, 0x02.toByte()) // Version 2
        buffer.put(1, 6.toByte()) // 6 LEDs
        buffer.putInt(2, 0x03FF) // Feature mask (bits 0..9)
        buffer.putInt(6, 9999) // Config revision
        buffer.put(10, 5.toByte()) // Preset capacity = 5
        buffer.put(11, 0.toByte()) // Security mode = 0

        val caps = PulseShiftBleProtocol.parseCapabilitiesV2(buffer.array())
        assertNotNull(caps)
        assertEquals(6, caps!!.physicalLedCount)
        assertTrue(caps.supportsIndividualRgb)
        assertTrue(caps.supportsEffects)
        assertTrue(caps.supportsStartupAnimation)
        assertTrue(caps.supportsRpmThresholds)
        assertTrue(caps.supportsShiftWarning)
        assertTrue(caps.supportsMultiplier)
        assertTrue(caps.supportsCandidateSource)
        assertTrue(caps.supportsNvsPersistence)
        assertEquals(9999L, caps.configRevision)
        assertEquals(5, caps.hardwarePresetCapacity)
    }

    @Test
    fun testDeviceCapabilitiesDefaultAllFalse() {
        val defaultCaps = DeviceCapabilities()
        assertFalse(defaultCaps.hasConfigV2Support)
        assertFalse(defaultCaps.supportsIndividualRgb)
        assertFalse(defaultCaps.supportsEffects)
        assertFalse(defaultCaps.supportsStartupAnimation)
        assertFalse(defaultCaps.supportsRpmThresholds)
        assertFalse(defaultCaps.supportsShiftWarning)
        assertFalse(defaultCaps.supportsMultiplier)
        assertFalse(defaultCaps.supportsCandidateSource)
        assertFalse(defaultCaps.supportsNvsPersistence)
        assertEquals(0, defaultCaps.physicalLedCount)
    }

    @Test
    fun testTransportOnlyCapabilitiesAreNotV2() {
        // A pre-snapshot placeholder (no capabilities characteristic read yet)
        // must never claim V2 support or feature flags.
        val placeholder = DeviceCapabilities(hasConfigV2Support = false)
        assertFalse(placeholder.hasConfigV2Support)
        assertFalse(placeholder.supportsMultiplier)
        assertFalse(placeholder.supportsCandidateSource)
        assertFalse(placeholder.supportsCaptureStage)
        assertFalse(placeholder.supportsLedBenchTest)
    }

    @Test
    fun testConfigSnapshotReassemblyAndCrc() {
        val reassembler = ConfigSnapshotReassembler()

        // Create complete TLV payload with all mandatory fields 0x10..0x1B
        val tlvBuffer = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        tlvBuffer.put(0x02.toByte()) // Schema version = 2
        tlvBuffer.putInt(1001) // Active revision = 1001

        // 0x10: Power = 1
        tlvBuffer.put(0x10.toByte()); tlvBuffer.put(1.toByte()); tlvBuffer.put(1.toByte())
        // 0x11: Brightness = 230
        tlvBuffer.put(0x11.toByte()); tlvBuffer.put(1.toByte()); tlvBuffer.put(230.toByte())
        // 0x12: Global RGB = (139, 92, 246)
        tlvBuffer.put(0x12.toByte()); tlvBuffer.put(3.toByte()); tlvBuffer.put(139.toByte()); tlvBuffer.put(92.toByte()); tlvBuffer.put(246.toByte())
        // 0x13: LED RGBs = 18 bytes (6x3)
        tlvBuffer.put(0x13.toByte()); tlvBuffer.put(18.toByte())
        repeat(6) {
            tlvBuffer.put(100.toByte()); tlvBuffer.put(150.toByte()); tlvBuffer.put(200.toByte())
        }
        // 0x14: Effect = Rainbow (2), speed = 75
        tlvBuffer.put(0x14.toByte()); tlvBuffer.put(2.toByte()); tlvBuffer.put(2.toByte()); tlvBuffer.put(75.toByte())
        // 0x15: Startup = Sweep (1), duration = 2000 ms
        tlvBuffer.put(0x15.toByte()); tlvBuffer.put(3.toByte()); tlvBuffer.put(1.toByte()); tlvBuffer.putShort(2000.toShort())
        // 0x16: Thresholds = 6x uint16 LE
        tlvBuffer.put(0x16.toByte()); tlvBuffer.put(12.toByte())
        val thresholds = listOf(3000, 4000, 5000, 6000, 7000, 8000)
        thresholds.forEach { tlvBuffer.putShort(it.toShort()) }
        // 0x17: Shift warning = 8500 RPM, Flash pattern (0)
        tlvBuffer.put(0x17.toByte()); tlvBuffer.put(3.toByte()); tlvBuffer.putShort(8500.toShort()); tlvBuffer.put(0.toByte())
        // 0x18: Multiplier = 40.0 (40000)
        tlvBuffer.put(0x18.toByte()); tlvBuffer.put(4.toByte()); tlvBuffer.putInt(40000)
        // 0x19: Candidate source = CAN ID 0x301, byteIndex 0, standard frame
        tlvBuffer.put(0x19.toByte()); tlvBuffer.put(6.toByte()); tlvBuffer.putInt(0x301); tlvBuffer.put(0.toByte()); tlvBuffer.put(0.toByte())
        // 0x1A: LED Direction = Normal (0)
        tlvBuffer.put(0x1A.toByte()); tlvBuffer.put(1.toByte()); tlvBuffer.put(0.toByte())
        // 0x1B: Warning RGB = Red (255, 0, 0)
        tlvBuffer.put(0x1B.toByte()); tlvBuffer.put(3.toByte()); tlvBuffer.put(255.toByte()); tlvBuffer.put(0.toByte()); tlvBuffer.put(0.toByte())

        val payloadSize = tlvBuffer.position()
        val rawDataWithoutCrc = ByteArray(payloadSize)
        System.arraycopy(tlvBuffer.array(), 0, rawDataWithoutCrc, 0, payloadSize)

        // Calculate CRC32
        val crc = CRC32()
        crc.update(rawDataWithoutCrc)
        val calculatedCrc = crc.value

        val fullSnapshotBytes = ByteArray(payloadSize + 4)
        System.arraycopy(rawDataWithoutCrc, 0, fullSnapshotBytes, 0, payloadSize)
        fullSnapshotBytes[payloadSize] = (calculatedCrc and 0xFF).toByte()
        fullSnapshotBytes[payloadSize + 1] = ((calculatedCrc shr 8) and 0xFF).toByte()
        fullSnapshotBytes[payloadSize + 2] = ((calculatedCrc shr 16) and 0xFF).toByte()
        fullSnapshotBytes[payloadSize + 3] = ((calculatedCrc shr 24) and 0xFF).toByte()

        // Split into chunks of max 12 bytes
        val totalLen = fullSnapshotBytes.size
        val maxChunkPayload = 12
        val totalChunks = (totalLen + maxChunkPayload - 1) / maxChunkPayload
        val chunks = mutableListOf<ByteArray>()

        for (i in 0 until totalChunks) {
            val start = i * maxChunkPayload
            val end = (start + maxChunkPayload).coerceAtMost(totalLen)
            chunks.add(fullSnapshotBytes.copyOfRange(start, end))
        }

        fun makePkt(chunkIdx: Int, total: Int, data: ByteArray): ByteArray {
            val pkt = ByteArray(6 + data.size)
            pkt[0] = 2 // Version 2
            pkt[1] = 0x34; pkt[2] = 0x12 // Snapshot ID = 0x1234 (4660)
            pkt[3] = chunkIdx.toByte()
            pkt[4] = total.toByte()
            pkt[5] = data.size.toByte()
            System.arraycopy(data, 0, pkt, 6, data.size)
            return pkt
        }

        var snapshot: ConfigSnapshot? = null
        for (i in 0 until totalChunks) {
            snapshot = reassembler.processChunkBytes(makePkt(i, totalChunks, chunks[i]))
        }

        assertNotNull(snapshot)
        assertTrue(snapshot!!.isValid)
        assertEquals(4660, snapshot.snapshotId)
        assertEquals(1001L, snapshot.activeRevision)
        assertTrue(snapshot.isPowerOn)
        assertEquals(230, snapshot.brightness)
        assertEquals(EffectType.RAINBOW, snapshot.effect)
        assertEquals(75, snapshot.effectSpeed)
        assertEquals(40.0f, snapshot.rpmMultiplier, 0.01f)
        assertEquals(0x301L, snapshot.canCandidateId)
    }

    @Test
    fun testConfigSnapshotRejectsDuplicateLedIndices() {
        val reassembler = ConfigSnapshotReassembler()

        val tlvBuffer = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        tlvBuffer.put(0x02.toByte()) // Schema version = 2
        tlvBuffer.putInt(1001)

        // Add 0x10..0x12
        tlvBuffer.put(0x10.toByte()); tlvBuffer.put(1.toByte()); tlvBuffer.put(1.toByte())
        tlvBuffer.put(0x11.toByte()); tlvBuffer.put(1.toByte()); tlvBuffer.put(230.toByte())
        tlvBuffer.put(0x12.toByte()); tlvBuffer.put(3.toByte()); tlvBuffer.put(255.toByte()); tlvBuffer.put(0.toByte()); tlvBuffer.put(0.toByte())

        // Add 0x13 with duplicate LED index 0 (24 bytes)
        tlvBuffer.put(0x13.toByte()); tlvBuffer.put(24.toByte())
        // LED 0 duplicate!
        tlvBuffer.put(0.toByte()); tlvBuffer.put(10.toByte()); tlvBuffer.put(20.toByte()); tlvBuffer.put(30.toByte())
        tlvBuffer.put(0.toByte()); tlvBuffer.put(10.toByte()); tlvBuffer.put(20.toByte()); tlvBuffer.put(30.toByte())
        for (i in 2 until 6) {
            tlvBuffer.put(i.toByte()); tlvBuffer.put(10.toByte()); tlvBuffer.put(20.toByte()); tlvBuffer.put(30.toByte())
        }

        // Add 0x14..0x1B
        tlvBuffer.put(0x14.toByte()); tlvBuffer.put(2.toByte()); tlvBuffer.put(0.toByte()); tlvBuffer.put(50.toByte())
        tlvBuffer.put(0x15.toByte()); tlvBuffer.put(3.toByte()); tlvBuffer.put(1.toByte()); tlvBuffer.putShort(2000.toShort())
        tlvBuffer.put(0x16.toByte()); tlvBuffer.put(12.toByte())
        repeat(6) { tlvBuffer.putShort(3000.toShort()) }
        tlvBuffer.put(0x17.toByte()); tlvBuffer.put(3.toByte()); tlvBuffer.putShort(8500.toShort()); tlvBuffer.put(0.toByte())
        tlvBuffer.put(0x18.toByte()); tlvBuffer.put(4.toByte()); tlvBuffer.putInt(40000)
        tlvBuffer.put(0x19.toByte()); tlvBuffer.put(6.toByte()); tlvBuffer.putInt(0x301); tlvBuffer.put(0.toByte()); tlvBuffer.put(0.toByte())
        tlvBuffer.put(0x1A.toByte()); tlvBuffer.put(1.toByte()); tlvBuffer.put(0.toByte())
        tlvBuffer.put(0x1B.toByte()); tlvBuffer.put(3.toByte()); tlvBuffer.put(255.toByte()); tlvBuffer.put(0.toByte()); tlvBuffer.put(0.toByte())

        val payloadSize = tlvBuffer.position()
        val rawDataWithoutCrc = ByteArray(payloadSize)
        System.arraycopy(tlvBuffer.array(), 0, rawDataWithoutCrc, 0, payloadSize)

        val crc = CRC32()
        crc.update(rawDataWithoutCrc)
        val calculatedCrc = crc.value

        val fullSnapshotBytes = ByteArray(payloadSize + 4)
        System.arraycopy(rawDataWithoutCrc, 0, fullSnapshotBytes, 0, payloadSize)
        fullSnapshotBytes[payloadSize] = (calculatedCrc and 0xFF).toByte()
        fullSnapshotBytes[payloadSize + 1] = ((calculatedCrc shr 8) and 0xFF).toByte()
        fullSnapshotBytes[payloadSize + 2] = ((calculatedCrc shr 16) and 0xFF).toByte()
        fullSnapshotBytes[payloadSize + 3] = ((calculatedCrc shr 24) and 0xFF).toByte()

        // Split into chunks of max 12 bytes
        val totalLen = fullSnapshotBytes.size
        val maxChunkPayload = 12
        val totalChunks = (totalLen + maxChunkPayload - 1) / maxChunkPayload
        val chunks = mutableListOf<ByteArray>()

        for (i in 0 until totalChunks) {
            val start = i * maxChunkPayload
            val end = (start + maxChunkPayload).coerceAtMost(totalLen)
            chunks.add(fullSnapshotBytes.copyOfRange(start, end))
        }

        fun makePkt(chunkIdx: Int, total: Int, data: ByteArray): ByteArray {
            val pkt = ByteArray(6 + data.size)
            pkt[0] = 2 // Version 2
            pkt[1] = 0x34; pkt[2] = 0x12 // Snapshot ID
            pkt[3] = chunkIdx.toByte()
            pkt[4] = total.toByte()
            pkt[5] = data.size.toByte()
            System.arraycopy(data, 0, pkt, 6, data.size)
            return pkt
        }

        var result: ConfigSnapshot? = null
        for (i in 0 until totalChunks) {
            result = reassembler.processChunkBytes(makePkt(i, totalChunks, chunks[i]))
        }
        assertNull(result) // Duplicate LED index 0 -> must be rejected during binary parsing
    }

    @Test
    fun testConfigSnapshotRejectsIncompleteOrCorrupt() {
        val reassembler = ConfigSnapshotReassembler()

        // Create TLV missing mandatory fields
        val tlvBuffer = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        tlvBuffer.put(0x02.toByte()) // Schema version = 2
        tlvBuffer.putInt(1001) // Revision
        tlvBuffer.put(0x10.toByte()); tlvBuffer.put(1.toByte()); tlvBuffer.put(1.toByte()) // Only power

        val payloadSize = tlvBuffer.position()
        val rawDataWithoutCrc = ByteArray(payloadSize)
        System.arraycopy(tlvBuffer.array(), 0, rawDataWithoutCrc, 0, payloadSize)

        val crc = CRC32()
        crc.update(rawDataWithoutCrc)
        val calculatedCrc = crc.value

        val fullSnapshotBytes = ByteArray(payloadSize + 4)
        System.arraycopy(rawDataWithoutCrc, 0, fullSnapshotBytes, 0, payloadSize)
        fullSnapshotBytes[payloadSize] = (calculatedCrc and 0xFF).toByte()
        fullSnapshotBytes[payloadSize + 1] = ((calculatedCrc shr 8) and 0xFF).toByte()
        fullSnapshotBytes[payloadSize + 2] = ((calculatedCrc shr 16) and 0xFF).toByte()
        fullSnapshotBytes[payloadSize + 3] = ((calculatedCrc shr 24) and 0xFF).toByte()

        val totalLen = fullSnapshotBytes.size
        val maxChunkPayload = 12
        val totalChunks = (totalLen + maxChunkPayload - 1) / maxChunkPayload
        val chunks = mutableListOf<ByteArray>()

        for (i in 0 until totalChunks) {
            val start = i * maxChunkPayload
            val end = (start + maxChunkPayload).coerceAtMost(totalLen)
            chunks.add(fullSnapshotBytes.copyOfRange(start, end))
        }

        fun makePkt(chunkIdx: Int, total: Int, data: ByteArray): ByteArray {
            val pkt = ByteArray(6 + data.size)
            pkt[0] = 2; pkt[1] = 1; pkt[2] = 0; pkt[3] = chunkIdx.toByte(); pkt[4] = total.toByte(); pkt[5] = data.size.toByte()
            System.arraycopy(data, 0, pkt, 6, data.size)
            return pkt
        }

        var result: ConfigSnapshot? = null
        for (i in 0 until totalChunks) {
            result = reassembler.processChunkBytes(makePkt(i, totalChunks, chunks[i]))
        }
        assertNull(result) // Should be rejected because mandatory TLVs missing
    }

    @Test
    fun testCapabilitiesV2WithRangeDiscoveryAndOta() {
        // Wire packet format (20 bytes):
        // [0]: version = 2
        // [1]: physicalLedCount = 6
        // [2..5]: featureMask (with bit 17 ext anim, bit 18 range discovery, bit 19 ota)
        // [6..9]: configRevision = 42
        // [10]: hardwarePresetCapacity = 0
        // [11]: securityMode = 1
        // [12..14]: fwMajor=2, fwMinor=3, fwPatch=0
        // [15]: maxStartupAnimId = 5
        // [16]: maxEffectId = 5
        // [17]: otaActiveSlot = 0
        // [18]: protocolMinor = 3
        // [19]: otaFlags = 3
        val pkt = ByteArray(20)
        pkt[0] = 2
        pkt[1] = 6
        // Feature mask: 0x000E0007 (bits 0,1,2 + 17,18,19)
        val mask = (1 shl 0) or (1 shl 1) or (1 shl 2) or (1 shl 17) or (1 shl 18) or (1 shl 19)
        pkt[2] = (mask and 0xFF).toByte()
        pkt[3] = ((mask shr 8) and 0xFF).toByte()
        pkt[4] = ((mask shr 16) and 0xFF).toByte()
        pkt[5] = ((mask shr 24) and 0xFF).toByte()
        pkt[6] = 42; pkt[7] = 0; pkt[8] = 0; pkt[9] = 0 // Rev 42
        pkt[10] = 0
        pkt[11] = 1
        pkt[12] = 2; pkt[13] = 3; pkt[14] = 0 // 2.3.0
        pkt[15] = 5 // maxStartupAnimId
        pkt[16] = 5 // maxEffectId
        pkt[17] = 0 // otaActiveSlot = app0
        pkt[18] = 3 // protocolMinor = 3
        pkt[19] = 3 // otaFlags

        val caps = PulseShiftBleProtocol.parseCapabilitiesV2(pkt)
        assertNotNull(caps)
        assertEquals("2.3.0", caps!!.firmwareVersion)
        assertEquals(6, caps.physicalLedCount)
        assertEquals(5, caps.maxStartupAnimId)
        assertTrue(caps.supportsExtendedStartupAnimation)
        assertTrue(caps.supportsRangeDiscovery)
        assertTrue(caps.supportsOtaUpdate)
        assertEquals(0, caps.otaActiveSlot)
        assertEquals(6, caps.supportedStartupAnimations.size)
        assertTrue(caps.supportedStartupAnimations.contains(StartupAnimation.CENTER_OUT))
        assertTrue(caps.supportedStartupAnimations.contains(StartupAnimation.STROBE))
    }

    @Test
    fun testCapabilitiesV2BackwardCompatibilityLegacyFirmware() {
        // Legacy 15-byte packet without bytes 15..19
        val pkt = ByteArray(15)
        pkt[0] = 2
        pkt[1] = 6
        val mask = (1 shl 0) or (1 shl 1) or (1 shl 2) // only basic anim bit 2
        pkt[2] = (mask and 0xFF).toByte()
        pkt[3] = ((mask shr 8) and 0xFF).toByte()
        pkt[4] = 0; pkt[5] = 0
        pkt[6] = 10; pkt[7] = 0; pkt[8] = 0; pkt[9] = 0
        pkt[10] = 0; pkt[11] = 1
        pkt[12] = 2; pkt[13] = 2; pkt[14] = 0 // 2.2.0

        val caps = PulseShiftBleProtocol.parseCapabilitiesV2(pkt)
        assertNotNull(caps)
        assertEquals("2.2.0", caps!!.firmwareVersion)
        assertEquals(3, caps.maxStartupAnimId) // safely defaults to 3
        assertFalse(caps.supportsExtendedStartupAnimation)
        assertFalse(caps.supportsRangeDiscovery)
        assertFalse(caps.supportsOtaUpdate)
        assertEquals(4, caps.supportedStartupAnimations.size) // NONE, SWEEP, WAVE, PULSE
        assertFalse(caps.supportedStartupAnimations.contains(StartupAnimation.CENTER_OUT))
        assertFalse(caps.supportedStartupAnimations.contains(StartupAnimation.STROBE))
    }

    @Test
    fun testBuildOtaCommands() {
        // Test GET_ARG_RANGES
        val getRanges = PulseShiftBleProtocol.buildGetArgRangesV2(101)
        assertEquals(6, getRanges.size)
        assertEquals(PulseShiftBleProtocol.V2_OPCODE_GET_ARG_RANGES, getRanges[1])

        // Test OTA_BEGIN
        val shaPrefix = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        val otaBegin = PulseShiftBleProtocol.buildOtaBeginV2(102, 65536L, shaPrefix)
        assertEquals(16, otaBegin.size) // 6 header + 10 payload
        assertEquals(PulseShiftBleProtocol.V2_OPCODE_OTA_BEGIN, otaBegin[1])
        assertEquals(10.toByte(), otaBegin[4]) // payload length
        assertEquals(0xA5.toByte(), otaBegin[6])
        assertEquals(0x5C.toByte(), otaBegin[7])

        // Test OTA_DATA
        val chunkData = byteArrayOf(0xE7.toByte(), 0x01, 0x02, 0x03)
        val otaData = PulseShiftBleProtocol.buildOtaDataV2(103, 0, chunkData)
        assertEquals(6 + 7, otaData.size) // 6 header + (3 + 4) payload
        assertEquals(PulseShiftBleProtocol.V2_OPCODE_OTA_DATA, otaData[1])
        assertEquals(7.toByte(), otaData[4])
        assertEquals(0.toByte(), otaData[6]) // chunkIdx low
        assertEquals(0.toByte(), otaData[7]) // chunkIdx high
        assertEquals(4.toByte(), otaData[8]) // dataLen
        assertEquals(0xE7.toByte(), otaData[9])

        // Test OTA_END
        val shaTail = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
        val otaEnd = PulseShiftBleProtocol.buildOtaEndV2(104, shaTail, 0x11223344L)
        assertEquals(6 + 8, otaEnd.size)
        assertEquals(PulseShiftBleProtocol.V2_OPCODE_OTA_END, otaEnd[1])
        assertEquals(8.toByte(), otaEnd[4])

        // Test OTA_ABORT
        val otaAbort = PulseShiftBleProtocol.buildOtaAbortV2(105)
        assertEquals(6, otaAbort.size)
        assertEquals(PulseShiftBleProtocol.V2_OPCODE_OTA_ABORT, otaAbort[1])

        // Test OTA_CONFIRM
        val otaConfirm = PulseShiftBleProtocol.buildOtaConfirmV2(106)
        assertEquals(8, otaConfirm.size)
        assertEquals(PulseShiftBleProtocol.V2_OPCODE_OTA_CONFIRM, otaConfirm[1])
        assertEquals(0xA5.toByte(), otaConfirm[6])
        assertEquals(0x5C.toByte(), otaConfirm[7])

        // Test OTA_STATUS
        val otaStatus = PulseShiftBleProtocol.buildOtaStatusV2(107)
        assertEquals(6, otaStatus.size)
        assertEquals(PulseShiftBleProtocol.V2_OPCODE_OTA_STATUS, otaStatus[1])
    }
}
