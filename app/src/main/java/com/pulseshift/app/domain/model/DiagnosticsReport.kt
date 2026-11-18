package com.pulseshift.app.domain.model

/**
 * Full device report parsed from the firmware GET_DIAGNOSTICS (0x50) TLV blob
 * streamed over the config snapshot characteristic. Mirrors the TLV IDs
 * documented in docs/BLE-PROTOCOL.md (BLE_DIAG_TLV_* 0x40-0x4A).
 */
data class DiagnosticsReport(
    val snapshotId: Int,
    val uptimeMs: Long = 0,
    val firmwareVersion: String = "unknown",
    val resetReason: Int = -1,
    // CAN controller (TLV 0x43)
    val canBitrate: Long = 0,
    val canTotalRx: Long = 0,
    val canAccepted: Long = 0,
    val canMissed: Long = 0,
    val canOverrun: Long = 0,
    val canBusErrors: Long = 0,
    val canHealthy: Boolean = false,
    val canTrafficActive: Boolean = false,
    // Unified RPM decoder (TLV 0x44)
    val candidateCanId: Long = 0,
    val candidateByteIndex: Int = 0,
    val candidateByteWidth: Int = 1,
    val candidateExtended: Boolean = false,
    val candidateMultiplier: Float = 0f,
    val candidateOffset: Float = 0f,
    val candidateMaxValid: Int = 0,
    val candidateRaw: Int = 0,
    val candidateEstimatedRpm: Int = 0,
    val candidateFresh: Boolean = false,
    val candidateValid: Boolean = false,
    val candidateCalVerified: Boolean = false,
    val candidateAgeMs: Int = 0,
    val candidateReceived: Long = 0,
    val candidateRejected: Long = 0,
    // Storage (TLV 0x45)
    val storageTotal: Long = 0,
    val storageUsed: Long = 0,
    val storageFree: Long = 0,
    val storageMounted: Boolean = false,
    val storageFull: Boolean = false,
    val capturePreserved: Boolean = false,
    val captureDamaged: Boolean = false,
    val captureRecording: Boolean = false,
    val captureRecords: Long = 0,
    val captureSizeBytes: Long = 0,
    val diagLogSizeBytes: Long = 0,
    // BLE/security (TLV 0x46)
    val bleConnected: Boolean = false,
    val bleAdvertising: Boolean = false,
    val securityMode: Int = 0,
    val ownerEnrolled: Boolean = false,
    val sessionAuthorized: Boolean = false,
    // Capture session (TLV 0x47)
    val captureStage: Int = 0,
    val sessionId: Long = 0,
    val bootCount: Long = 0,
    // Last failure (TLV 0x48)
    val lastError: String = "",
    // Calibration (TLV 0x49)
    val calSessionActive: Boolean = false,
    val calPointCount: Int = 0,
    val calCaptureActive: Boolean = false,
    val calCaptureSamples: Int = 0,
    val calVerified: Boolean = false,
    val calPointsUsed: Int = 0,
    val calMaxResidualRpm: Int = 0,
    val calFingerprint: Long = 0,
    val experimentalCanBytes: List<Int?>? = null
) {
    val isValid: Boolean get() = uptimeMs > 0 || canTotalRx > 0 || storageTotal > 0

    companion object {
        /**
         * Parse the diagnostics TLV blob (schema byte 1 + revision u32 + TLVs
         * + trailing CRC32). Returns null when framing or CRC is invalid.
         */
        fun parse(snapshotId: Int, bytes: ByteArray): DiagnosticsReport? {
            if (bytes.size < 9) return null
            if ((bytes[0].toInt() and 0xFF) != 1) return null // diagnostics blob schema
            val totalLen = bytes.size
            val dataLen = totalLen - 4
            val expectedCrc = (bytes[dataLen].toLong() and 0xFF) or
                    ((bytes[dataLen + 1].toLong() and 0xFF) shl 8) or
                    ((bytes[dataLen + 2].toLong() and 0xFF) shl 16) or
                    ((bytes[dataLen + 3].toLong() and 0xFF) shl 24)
            val crc = java.util.zip.CRC32()
            crc.update(bytes, 0, dataLen)
            if (crc.value != expectedCrc) return null

            var r = DiagnosticsReport(snapshotId = snapshotId)
            var offset = 5
            fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
            fun s32(b: ByteArray, o: Int): Int {
                val v = (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
                        ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)
                return v.toInt()
            }
            fun u32(b: ByteArray, o: Int) = (b[o].toLong() and 0xFF) or
                    ((b[o + 1].toLong() and 0xFF) shl 8) or
                    ((b[o + 2].toLong() and 0xFF) shl 16) or
                    ((b[o + 3].toLong() and 0xFF) shl 24)

            while (offset + 2 <= dataLen) {
                val id = bytes[offset].toInt() and 0xFF
                val len = bytes[offset + 1].toInt() and 0xFF
                if (offset + 2 + len > dataLen) return null
                val v = bytes.copyOfRange(offset + 2, offset + 2 + len)
                when (id) {
                    0x40 -> if (len == 4) r = r.copy(uptimeMs = u32(v, 0))
                    0x41 -> if (len == 3) r = r.copy(
                        firmwareVersion = "${v[0].toInt() and 0xFF}.${v[1].toInt() and 0xFF}.${v[2].toInt() and 0xFF}"
                    )
                    0x42 -> if (len == 1) r = r.copy(resetReason = v[0].toInt() and 0xFF)
                    0x43 -> if (len == 26) r = r.copy(
                        canBitrate = u32(v, 0),
                        canTotalRx = u32(v, 4),
                        canAccepted = u32(v, 8),
                        canMissed = u32(v, 12),
                        canOverrun = u32(v, 16),
                        canBusErrors = u32(v, 20),
                        canHealthy = (v[24].toInt() and 0xFF) != 0,
                        canTrafficActive = (v[25].toInt() and 0xFF) != 0
                    )
                    0x44 -> if (len == 34) r = r.copy(
                        candidateCanId = u32(v, 0),
                        candidateByteIndex = v[4].toInt() and 0xFF,
                        candidateByteWidth = v[5].toInt() and 0xFF,
                        candidateExtended = (v[6].toInt() and 0xFF) != 0,
                        candidateMultiplier = s32(v, 7) / 1000f,
                        candidateOffset = s32(v, 11) / 1000f,
                        candidateMaxValid = u16(v, 15),
                        candidateRaw = u16(v, 17),
                        candidateEstimatedRpm = u16(v, 19),
                        candidateFresh = (v[21].toInt() and 0xFF) != 0,
                        candidateValid = (v[22].toInt() and 0xFF) != 0,
                        candidateCalVerified = (v[23].toInt() and 0xFF) != 0,
                        candidateAgeMs = u16(v, 24),
                        candidateReceived = u32(v, 26),
                        candidateRejected = u32(v, 30)
                    )
                    0x45 -> if (len == 29) r = r.copy(
                        storageTotal = u32(v, 0),
                        storageUsed = u32(v, 4),
                        storageFree = u32(v, 8),
                        storageMounted = (v[12].toInt() and 0xFF) != 0,
                        storageFull = (v[13].toInt() and 0xFF) != 0,
                        capturePreserved = (v[14].toInt() and 0xFF) != 0,
                        captureDamaged = (v[15].toInt() and 0xFF) != 0,
                        captureRecording = (v[16].toInt() and 0xFF) != 0,
                        captureRecords = u32(v, 17),
                        captureSizeBytes = u32(v, 21),
                        diagLogSizeBytes = u32(v, 25)
                    )
                    0x46 -> if (len == 5) r = r.copy(
                        bleConnected = (v[0].toInt() and 0xFF) != 0,
                        bleAdvertising = (v[1].toInt() and 0xFF) != 0,
                        securityMode = v[2].toInt() and 0xFF,
                        ownerEnrolled = (v[3].toInt() and 0xFF) != 0,
                        sessionAuthorized = (v[4].toInt() and 0xFF) != 0
                    )
                    0x47 -> if (len == 9) r = r.copy(
                        captureStage = v[0].toInt() and 0xFF,
                        sessionId = u32(v, 1),
                        bootCount = u32(v, 5)
                    )
                    0x48 -> r = r.copy(lastError = String(v, Charsets.UTF_8).trim())
                    0x49 -> if (len == 13) r = r.copy(
                        calSessionActive = (v[0].toInt() and 0xFF) != 0,
                        calPointCount = v[1].toInt() and 0xFF,
                        calCaptureActive = (v[2].toInt() and 0xFF) != 0,
                        calCaptureSamples = u16(v, 3),
                        calVerified = (v[5].toInt() and 0xFF) != 0,
                        calPointsUsed = v[6].toInt() and 0xFF,
                        calMaxResidualRpm = v[7].toInt() and 0xFF,
                        calFingerprint = u32(v, 8)
                    )
                    0x4A -> if (len == 5) r = r.copy(
                        experimentalCanBytes = List(4) { index ->
                            (v[1 + index].toInt() and 0xFF)
                                .takeIf { (v[0].toInt() and (1 shl index)) != 0 }
                        }
                    )
                    // Unknown TLVs are tolerated (forward compatibility).
                }
                offset += 2 + len
            }
            return r
        }
    }
}
