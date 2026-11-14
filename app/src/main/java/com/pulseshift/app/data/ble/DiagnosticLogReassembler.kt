package com.pulseshift.app.data.ble

import com.pulseshift.app.domain.model.CaptureRecord
import com.pulseshift.app.domain.model.DiagnosticLogChunk
import com.pulseshift.app.domain.model.DiagnosticLogChunkFlags
import com.pulseshift.app.domain.model.DiagnosticLogMessage
import com.pulseshift.app.domain.model.HistoricalPageEnd
import com.pulseshift.app.domain.model.LogSeverity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DiagnosticLogReassembler {

    private val _completedMessages = MutableSharedFlow<DiagnosticLogMessage>(replay = 1, extraBufferCapacity = 64)
    val completedMessages: SharedFlow<DiagnosticLogMessage> = _completedMessages.asSharedFlow()

    fun emitDirectMessage(message: DiagnosticLogMessage) {
        _completedMessages.tryEmit(message)
    }

    private val _pageEndEvents = MutableSharedFlow<HistoricalPageEnd>(extraBufferCapacity = 16)
    val pageEndEvents: SharedFlow<HistoricalPageEnd> = _pageEndEvents.asSharedFlow()

    // CAN capture page streaming (BLE_LOG_FLAG_CAPTURE_LINE): capture record
    // lines and their end-of-page markers are routed to dedicated flows so
    // they never mix with diagnostic messages.
    private val _captureRecordEvents = MutableSharedFlow<CaptureRecord>(extraBufferCapacity = 64)
    val captureRecordEvents: SharedFlow<CaptureRecord> = _captureRecordEvents.asSharedFlow()

    private val _capturePageEndEvents = MutableSharedFlow<HistoricalPageEnd>(extraBufferCapacity = 16)
    val capturePageEndEvents: SharedFlow<HistoricalPageEnd> = _capturePageEndEvents.asSharedFlow()

    private val activeMessages = ConcurrentHashMap<Int, MutableMap<Int, DiagnosticLogChunk>>()
    private val messageFirstReceived = ConcurrentHashMap<Int, Long>()
    private val messageIsHistorical = ConcurrentHashMap<Int, Boolean>()
    private val messageIsCaptureLine = ConcurrentHashMap<Int, Boolean>()

    fun processChunkBytes(bytes: ByteArray, isHistorical: Boolean = false): DiagnosticLogChunk? {
        try {
            if (bytes.size < 6) return null
            val version = bytes[0].toInt() and 0xFF
            if (version != 1) return null

            val flags = DiagnosticLogChunkFlags.fromByte(bytes[1])
            val messageId = ((bytes[2].toInt() and 0xFF) or ((bytes[3].toInt() and 0xFF) shl 8))
            val chunkIndex = bytes[4].toInt() and 0xFF
            val payloadLength = bytes[5].toInt() and 0xFF

            if (bytes.size < 6 + payloadLength || payloadLength > 14) return null

            val payload = bytes.copyOfRange(6, 6 + payloadLength)

            val chunk = DiagnosticLogChunk(
                version = version,
                flags = flags,
                messageId = messageId,
                chunkIndex = chunkIndex,
                payloadLength = payloadLength,
                data = payload
            )

            // Check if page-end notification
            if (flags.isEndOfHistoricalPage) {
                if (payloadLength == 5) {
                    val nextOffset = (payload[0].toLong() and 0xFF) or
                            ((payload[1].toLong() and 0xFF) shl 8) or
                            ((payload[2].toLong() and 0xFF) shl 16) or
                            ((payload[3].toLong() and 0xFF) shl 24)
                    val finished = payload[4].toInt() != 0
                    if (flags.isCaptureLine) {
                        _capturePageEndEvents.tryEmit(HistoricalPageEnd(nextOffset, finished))
                    } else {
                        _pageEndEvents.tryEmit(HistoricalPageEnd(nextOffset, finished))
                    }
                }
                return chunk
            }

            // Store chunk in message map
            val chunksMap = activeMessages.computeIfAbsent(messageId) {
                messageFirstReceived[messageId] = System.currentTimeMillis()
                messageIsHistorical[messageId] = isHistorical || flags.isError
                messageIsCaptureLine[messageId] = flags.isCaptureLine
                ConcurrentHashMap()
            }
            chunksMap[chunkIndex] = chunk

            // Try reassembling if we have both first and final chunks
            checkAndAssemble(messageId)

            // Clean up stale message assemblies (>5s)
            cleanStaleAssemblies()

            return chunk
        } catch (e: Exception) {
            // Guard against malformed BLE notification packets
            return null
        }
    }

    private fun checkAndAssemble(messageId: Int) {
        val chunksMap = activeMessages[messageId] ?: return
        val hasFirst = chunksMap.values.any { it.flags.isFirstChunk || it.chunkIndex == 0 }
        val finalChunk = chunksMap.values.firstOrNull { it.flags.isFinalChunk }

        if (hasFirst && finalChunk != null) {
            val maxIndex = finalChunk.chunkIndex
            var isContiguous = true
            val orderedChunks = ArrayList<DiagnosticLogChunk>(maxIndex + 1)

            for (i in 0..maxIndex) {
                val c = chunksMap[i]
                if (c == null) {
                    isContiguous = false
                    break
                }
                orderedChunks.add(c)
            }

            if (isContiguous) {
                val totalBytes = orderedChunks.sumOf { it.payloadLength }
                val buffer = ByteBuffer.allocate(totalBytes)
                for (c in orderedChunks) {
                    buffer.put(c.data)
                }

                val decodedText = String(buffer.array(), StandardCharsets.UTF_8).trim()

                if (messageIsCaptureLine[messageId] == true) {
                    // CAN capture record line: parse and emit on the capture flow.
                    parseCaptureLine(decodedText)
                    activeMessages.remove(messageId)
                    messageFirstReceived.remove(messageId)
                    messageIsHistorical.remove(messageId)
                    messageIsCaptureLine.remove(messageId)
                    return
                }

                val isErrorFlag = orderedChunks.any { it.flags.isError }

                val severity = when {
                    isErrorFlag || decodedText.contains("ERROR", ignoreCase = true) || decodedText.contains("FATAL", ignoreCase = true) || decodedText.contains("FAIL", ignoreCase = true) -> LogSeverity.ERROR
                    decodedText.contains("WARN", ignoreCase = true) || decodedText.contains("DEGRADED", ignoreCase = true) -> LogSeverity.WARNING
                    else -> LogSeverity.INFO
                }

                val isHist = messageIsHistorical[messageId] ?: false

                val logMessage = DiagnosticLogMessage(
                    id = UUID.randomUUID().toString(),
                    messageId = messageId,
                    timestampMs = System.currentTimeMillis(),
                    messageText = decodedText,
                    severity = severity,
                    isHistorical = isHist,
                    isCorruptOrIncomplete = false
                )

                _completedMessages.tryEmit(logMessage)

                activeMessages.remove(messageId)
                messageFirstReceived.remove(messageId)
                messageIsHistorical.remove(messageId)
                messageIsCaptureLine.remove(messageId)
            }
        }
    }

    fun cleanStaleAssemblies() {
        val now = System.currentTimeMillis()
        val staleIds = messageFirstReceived.filter { (now - it.value) > 5000 }.keys
        for (id in staleIds) {
            val chunks = activeMessages.remove(id)
            val isHist = messageIsHistorical.remove(id) ?: false
            val isCapture = messageIsCaptureLine.remove(id) == true
            messageFirstReceived.remove(id)

            // Incomplete capture lines are dropped silently: a partial CAN
            // record must never be presented as data.
            if (isCapture || chunks == null || chunks.isEmpty()) continue

            val ordered = chunks.values.sortedBy { it.chunkIndex }
            val totalBytes = ordered.sumOf { it.payloadLength }
            val buffer = ByteBuffer.allocate(totalBytes)
            for (c in ordered) {
                buffer.put(c.data)
            }
            val partialText = String(buffer.array(), StandardCharsets.UTF_8).trim()
            val logMessage = DiagnosticLogMessage(
                id = UUID.randomUUID().toString(),
                messageId = id,
                timestampMs = now,
                messageText = if (partialText.isNotEmpty()) "$partialText [INCOMPLETE]" else "[CORRUPTED CHUNKS]",
                severity = LogSeverity.WARNING,
                isHistorical = isHist,
                isCorruptOrIncomplete = true
            )
            _completedMessages.tryEmit(logMessage)
        }
    }

    private fun parseCaptureLine(text: String) {
        // Format from the firmware: CAP,<timestampMs>,<stage>,<idHex>,<ext>,<dlc>,<dataHex>
        val parts = text.trim().removeSuffix("\r").removeSuffix("\n").split(",")
        if (parts.size < 7 || parts[0] != "CAP") return
        try {
            val record = CaptureRecord(
                timestampMs = parts[1].toLong(),
                stage = parts[2].toInt(),
                canId = parts[3].toLong(16),
                isExtended = parts[4] != "0",
                dlc = parts[5].toInt(),
                dataHex = parts[6]
            )
            _captureRecordEvents.tryEmit(record)
        } catch (e: Exception) {
            // Malformed capture line: drop rather than fabricate data.
        }
    }

    fun clear() {
        activeMessages.clear()
        messageFirstReceived.clear()
        messageIsHistorical.clear()
        messageIsCaptureLine.clear()
    }
}
