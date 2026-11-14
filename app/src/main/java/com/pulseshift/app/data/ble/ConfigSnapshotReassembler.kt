package com.pulseshift.app.data.ble

import android.util.Log
import com.pulseshift.app.domain.model.ConfigSnapshot
import com.pulseshift.app.domain.model.DiagnosticsReport
import com.pulseshift.app.domain.model.EffectType
import com.pulseshift.app.domain.model.RgbColor
import com.pulseshift.app.domain.model.StartupAnimation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

class ConfigSnapshotReassembler {
    private val activeSnapshots = ConcurrentHashMap<Int, MutableMap<Int, ByteArray>>()
    private val snapshotTotalChunks = ConcurrentHashMap<Int, Int>()
    private val snapshotFirstReceived = ConcurrentHashMap<Int, Long>()

    // Diagnostics blobs (GET_DIAGNOSTICS, schema byte 1) arrive with the same
    // chunk framing; they are reassembled here and routed to their own flow.
    private val _diagnosticsReports = MutableSharedFlow<DiagnosticsReport>(extraBufferCapacity = 8)
    val diagnosticsReports: SharedFlow<DiagnosticsReport> = _diagnosticsReports.asSharedFlow()

    private fun logE(tag: String, msg: String) {
        try {
            Log.e(tag, msg)
        } catch (t: Throwable) {
            println("[$tag ERROR] $msg")
        }
    }

    private fun logW(tag: String, msg: String) {
        try {
            Log.w(tag, msg)
        } catch (t: Throwable) {
            println("[$tag WARN] $msg")
        }
    }

    fun processChunkBytes(bytes: ByteArray): ConfigSnapshot? {
        try {
            if (bytes.size < 6) return null
            val version = bytes[0].toInt() and 0xFF
            if (version != 2) return null

            val snapshotId = (bytes[1].toInt() and 0xFF) or ((bytes[2].toInt() and 0xFF) shl 8)
            val chunkIndex = bytes[3].toInt() and 0xFF
            val totalChunks = bytes[4].toInt() and 0xFF
            val payloadLength = bytes[5].toInt() and 0xFF

            if (bytes.size < 6 + payloadLength || payloadLength > 14) return null
            if (chunkIndex >= totalChunks) return null

            val chunkPayload = bytes.copyOfRange(6, 6 + payloadLength)

            val chunksMap = activeSnapshots.computeIfAbsent(snapshotId) {
                snapshotTotalChunks[snapshotId] = totalChunks
                snapshotFirstReceived[snapshotId] = System.currentTimeMillis()
                ConcurrentHashMap()
            }

            if (chunksMap.containsKey(chunkIndex)) {
                logW("ConfigSnapshot", "Duplicate chunk $chunkIndex received for snapshot $snapshotId")
                return null
            }
            chunksMap[chunkIndex] = chunkPayload

            cleanStaleSnapshots()

            if (chunksMap.size == totalChunks) {
                val fullDataList = ArrayList<Byte>()
                for (i in 0 until totalChunks) {
                    val part = chunksMap[i] ?: return null
                    for (b in part) {
                        fullDataList.add(b)
                    }
                }
                activeSnapshots.remove(snapshotId)
                snapshotTotalChunks.remove(snapshotId)
                snapshotFirstReceived.remove(snapshotId)

                val fullBuffer = fullDataList.toByteArray()
                if ((fullBuffer.isNotEmpty()) && (fullBuffer[0].toInt() and 0xFF) == 1) {
                    // Diagnostics TLV blob (GET_DIAGNOSTICS), not a config snapshot.
                    val report = DiagnosticsReport.parse(snapshotId, fullBuffer)
                    if (report != null) {
                        _diagnosticsReports.tryEmit(report)
                    }
                    return null
                }
                return parseSnapshotBinary(snapshotId, fullBuffer)
            }
        } catch (e: Exception) {
            logE("ConfigSnapshot", "Error reassembling snapshot chunk: ${e.message}")
        }
        return null
    }

    private fun parseSnapshotBinary(snapshotId: Int, bytes: ByteArray): ConfigSnapshot? {
        if (bytes.size < 9 || bytes.size > 512) {
            logE("ConfigSnapshot", "Invalid snapshot size: ${bytes.size}")
            return null
        }
        val totalLen = bytes.size
        val dataLen = totalLen - 4
        val expectedCrc = (bytes[dataLen].toLong() and 0xFF) or
                ((bytes[dataLen + 1].toLong() and 0xFF) shl 8) or
                ((bytes[dataLen + 2].toLong() and 0xFF) shl 16) or
                ((bytes[dataLen + 3].toLong() and 0xFF) shl 24)

        val crc = CRC32()
        crc.update(bytes, 0, dataLen)
        val calculatedCrc = crc.value

        if (calculatedCrc != expectedCrc) {
            logE("ConfigSnapshot", "CRC Mismatch! calc=$calculatedCrc exp=$expectedCrc")
            return null
        }

        val schemaVersion = bytes[0].toInt() and 0xFF
        if (schemaVersion < 2 || schemaVersion > 3) {
            logE("ConfigSnapshot", "Unsupported snapshot schema version: $schemaVersion")
            return null
        }

        val activeRevision = (bytes[1].toLong() and 0xFF) or
                ((bytes[2].toLong() and 0xFF) shl 8) or
                ((bytes[3].toLong() and 0xFF) shl 16) or
                ((bytes[4].toLong() and 0xFF) shl 24)

        var offset = 5
        val seenSettingIds = HashSet<Int>()

        var isPowerOn = false
        var brightness = 0
        var globalColor = RgbColor(0, 0, 0)
        var ledColors: List<RgbColor>? = null
        var effect: EffectType? = null
        var effectSpeed = 0
        var ledDirection = 0
        var startupAnimation: StartupAnimation? = null
        var startupDurationMs = 0
        var rpmThresholds: List<Int>? = null
        var warningRpm = 0
        var warningPatternId = 0
        var warningColor = RgbColor(0, 0, 0)
        var rpmMultiplier = 0.0f
        var canCandidateId = 0L
        var candidateByteIndex = 0
        var isExtendedFrame = false
        // Schema-3 additions
        var rpmHysteresis = 150
        var candidateByteWidth = 1
        var candidateLittleEndian = true
        var rpmOffset = 0.0f
        var rpmMaxValid = 12000
        var calibrationVerified = false

        while (offset + 2 <= dataLen) {
            val settingId = bytes[offset].toInt() and 0xFF
            val len = bytes[offset + 1].toInt() and 0xFF
            if (offset + 2 + len > dataLen) {
                logE("ConfigSnapshot", "Truncated TLV for settingId 0x${settingId.toString(16)}")
                return null
            }

            if (seenSettingIds.contains(settingId)) {
                logE("ConfigSnapshot", "Duplicate settingId 0x${settingId.toString(16)}")
                return null
            }
            seenSettingIds.add(settingId)

            val valBytes = bytes.copyOfRange(offset + 2, offset + 2 + len)

            when (settingId) {
                0x10 -> { // Power
                    if (len != 1) return null
                    val valInt = valBytes[0].toInt() and 0xFF
                    if (valInt > 1) return null
                    isPowerOn = valInt != 0
                }
                0x11 -> { // Brightness
                    if (len != 1) return null
                    brightness = valBytes[0].toInt() and 0xFF
                }
                0x12 -> { // Global RGB
                    if (len != 3) return null
                    globalColor = RgbColor(valBytes[0].toInt() and 0xFF, valBytes[1].toInt() and 0xFF, valBytes[2].toInt() and 0xFF)
                }
                0x13 -> { // LED RGBs
                    if (len != 18 && len != 24) return null
                    val colors = MutableList(6) { RgbColor(0, 0, 0) }
                    if (len == 24) { // Indexed: 6x [index: 0..5, R, G, B]
                        val seenLedIndices = HashSet<Int>()
                        for (i in 0 until 6) {
                            val idx = valBytes[i * 4].toInt() and 0xFF
                            if (idx !in 0..5) return null
                            if (seenLedIndices.contains(idx)) {
                                logE("ConfigSnapshot", "Duplicate LED index $idx in TLV 0x13")
                                return null
                            }
                            seenLedIndices.add(idx)
                            colors[idx] = RgbColor(valBytes[i * 4 + 1].toInt() and 0xFF, valBytes[i * 4 + 2].toInt() and 0xFF, valBytes[i * 4 + 3].toInt() and 0xFF)
                        }
                        if (seenLedIndices.size != 6) return null
                    } else { // Sequential: 6x [R, G, B]
                        for (i in 0 until 6) {
                            colors[i] = RgbColor(valBytes[i * 3].toInt() and 0xFF, valBytes[i * 3 + 1].toInt() and 0xFF, valBytes[i * 3 + 2].toInt() and 0xFF)
                        }
                    }
                    ledColors = colors
                }
                0x14 -> { // Effect & Speed
                    if (len != 2) return null
                    val rawEffectId = valBytes[0].toInt() and 0xFF
                    val parsedEffect = EffectType.entries.find { it.id == rawEffectId }
                        ?: run {
                            logE("ConfigSnapshot", "Unsupported Effect ID: $rawEffectId, safely falling back to STATIC")
                            EffectType.STATIC
                        }
                    effect = parsedEffect
                    effectSpeed = (valBytes[1].toInt() and 0xFF).coerceIn(0, 100)
                }
                0x15 -> { // Startup Anim & Duration
                    if (len != 3) return null
                    val rawAnimId = valBytes[0].toInt() and 0xFF
                    val parsedAnim = StartupAnimation.entries.find { it.id == rawAnimId }
                        ?: run {
                            logE("ConfigSnapshot", "Unsupported Startup Animation ID: $rawAnimId, safely falling back to SWEEP")
                            StartupAnimation.SWEEP
                        }
                    startupAnimation = parsedAnim
                    startupDurationMs = (valBytes[1].toInt() and 0xFF) or ((valBytes[2].toInt() and 0xFF) shl 8)
                }
                0x16 -> { // LED Thresholds
                    if (len != 12) return null
                    val list = mutableListOf<Int>()
                    for (i in 0 until 6) {
                        val rpm = (valBytes[i * 2].toInt() and 0xFF) or ((valBytes[i * 2 + 1].toInt() and 0xFF) shl 8)
                        list.add(rpm)
                    }
                    rpmThresholds = list
                }
                0x17 -> { // Shift Warning
                    if (len != 3) return null
                    warningRpm = (valBytes[0].toInt() and 0xFF) or ((valBytes[1].toInt() and 0xFF) shl 8)
                    val rawPatternId = valBytes[2].toInt() and 0xFF
                    warningPatternId = if (rawPatternId in 0..3) {
                        rawPatternId
                    } else {
                        logE("ConfigSnapshot", "Unsupported Shift Warning Pattern ID: $rawPatternId, safely falling back to 0")
                        0
                    }
                }
                0x18 -> { // Multiplier
                    if (len != 4) return null
                    val multX1000 = (valBytes[0].toLong() and 0xFF) or
                            ((valBytes[1].toLong() and 0xFF) shl 8) or
                            ((valBytes[2].toLong() and 0xFF) shl 16) or
                            ((valBytes[3].toLong() and 0xFF) shl 24)
                    rpmMultiplier = multX1000 / 1000.0f
                }
                0x19 -> { // Candidate Source
                    if (len != 6) return null
                    canCandidateId = (valBytes[0].toLong() and 0xFF) or
                            ((valBytes[1].toLong() and 0xFF) shl 8) or
                            ((valBytes[2].toLong() and 0xFF) shl 16) or
                            ((valBytes[3].toLong() and 0xFF) shl 24)
                    candidateByteIndex = valBytes[4].toInt() and 0xFF
                    if (candidateByteIndex !in 0..7) return null
                    val extInt = valBytes[5].toInt() and 0xFF
                    if (extInt > 1) return null
                    isExtendedFrame = extInt != 0
                }
                0x1A -> { // LED Direction
                    if (len != 1) return null
                    val dir = valBytes[0].toInt() and 0xFF
                    if (dir > 1) return null
                    ledDirection = dir
                }
                0x1B -> { // Warning RGB
                    if (len != 3) return null
                    warningColor = RgbColor(valBytes[0].toInt() and 0xFF, valBytes[1].toInt() and 0xFF, valBytes[2].toInt() and 0xFF)
                }
                0x1C -> { // Decoder extension (schema 3)
                    if (len != 8) return null
                    val width = valBytes[0].toInt() and 0xFF
                    if (width != 1 && width != 2) return null
                    candidateByteWidth = width
                    candidateLittleEndian = (valBytes[1].toInt() and 0xFF) != 0
                    val offX1000 = (valBytes[2].toInt() and 0xFF) or
                            ((valBytes[3].toInt() and 0xFF) shl 8) or
                            ((valBytes[4].toInt() and 0xFF) shl 16) or
                            ((valBytes[5].toInt() and 0xFF) shl 24)
                    rpmOffset = offX1000 / 1000.0f
                    rpmMaxValid = (valBytes[6].toInt() and 0xFF) or ((valBytes[7].toInt() and 0xFF) shl 8)
                }
                0x1D -> { // Calibration verification record (schema 3)
                    if (len != 11) return null
                    calibrationVerified = (valBytes[0].toInt() and 0xFF) != 0
                }
                0x1E -> { // Threshold hysteresis (schema 3)
                    if (len != 2) return null
                    rpmHysteresis = (valBytes[0].toInt() and 0xFF) or ((valBytes[1].toInt() and 0xFF) shl 8)
                }
                else -> {
                    // Forward compatibility: tolerate unknown TLVs (log and skip).
                    logW("ConfigSnapshot", "Unknown TLV 0x${settingId.toString(16)} (len $len) skipped")
                }
            }
            offset += 2 + len
        }

        // Require mandatory fields to be present
        val mandatorySettingIds = setOf(0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x1B)
        if (!seenSettingIds.containsAll(mandatorySettingIds)) {
            val missing = mandatorySettingIds - seenSettingIds
            logE("ConfigSnapshot", "Missing mandatory setting IDs: ${missing.map { "0x" + it.toString(16) }}")
            return null
        }

        return ConfigSnapshot(
            snapshotId = snapshotId,
            schemaVersion = schemaVersion,
            activeRevision = activeRevision,
            isPowerOn = isPowerOn,
            brightness = brightness,
            globalColor = globalColor,
            ledColors = ledColors!!,
            effect = effect!!,
            effectSpeed = effectSpeed,
            ledDirection = ledDirection,
            startupAnimation = startupAnimation!!,
            startupDurationMs = startupDurationMs,
            rpmThresholds = rpmThresholds!!,
            warningRpm = warningRpm,
            warningPatternId = warningPatternId,
            warningColor = warningColor,
            rpmMultiplier = rpmMultiplier,
            canCandidateId = canCandidateId,
            candidateByteIndex = candidateByteIndex,
            isExtendedFrame = isExtendedFrame,
            rpmHysteresis = rpmHysteresis,
            candidateByteWidth = candidateByteWidth,
            candidateLittleEndian = candidateLittleEndian,
            rpmOffset = rpmOffset,
            rpmMaxValid = rpmMaxValid,
            calibrationVerified = calibrationVerified,
            isValid = true
        )
    }

    private fun cleanStaleSnapshots() {
        val now = System.currentTimeMillis()
        val stale = snapshotFirstReceived.filter { (now - it.value) > 5000 }.keys
        for (id in stale) {
            activeSnapshots.remove(id)
            snapshotTotalChunks.remove(id)
            snapshotFirstReceived.remove(id)
        }
    }

    fun clear() {
        activeSnapshots.clear()
        snapshotTotalChunks.clear()
        snapshotFirstReceived.clear()
    }
}
