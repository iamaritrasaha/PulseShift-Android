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

class DiagnosticLogReassembler(private val onHistoricalPageEnd: () -> Unit = {}) {

    private val _completedMessages = MutableSharedFlow<DiagnosticLogMessage>(replay = 1, extraBufferCapacity = 64)
    val completedMessages: SharedFlow<DiagnosticLogMessage> = _completedMessages.asSharedFlow()

    fun emitDirectMessage(message: DiagnosticLogMessage) {
        emitMessage(message)
    }

    @Synchronized
    fun emitDirectCaptureRecord(record: CaptureRecord) {
        _capturePageEvents.tryEmit(CapturePageEvent.Record(directCaptureMessageId++, record))
    }

    @Synchronized
    fun emitDirectCapturePageEnd(pageEnd: HistoricalPageEnd) {
        _capturePageEvents.tryEmit(CapturePageEvent.End(directCaptureMessageId++, pageEnd))
    }

    fun emitDirectLogPageEnd(pageEnd: HistoricalPageEnd) {
        onHistoricalPageEnd()
        _pageEndEvents.tryEmit(pageEnd)
    }

    private val _pageEndEvents = MutableSharedFlow<HistoricalPageEnd>(extraBufferCapacity = 16)
    val pageEndEvents: SharedFlow<HistoricalPageEnd> = _pageEndEvents.asSharedFlow()

    // Record and EOP events share one ordered stream, including the final record.
    private val _capturePageEvents = MutableSharedFlow<CapturePageEvent>(extraBufferCapacity = 64)
    internal val capturePageEvents: SharedFlow<CapturePageEvent> = _capturePageEvents.asSharedFlow()
    private val completedCaptureMessages = LinkedHashSet<Int>()
    private var directCaptureMessageId = 0

    private val activeMessages = ConcurrentHashMap<Int, MutableMap<Int, DiagnosticLogChunk>>()
    private val messageFirstReceived = ConcurrentHashMap<Int, Long>()
    private val messageIsHistorical = ConcurrentHashMap<Int, Boolean>()
    private val messageIsCaptureLine = ConcurrentHashMap<Int, Boolean>()

    @Synchronized
    fun processChunkBytes(bytes: ByteArray, isHistorical: Boolean = false): DiagnosticLogChunk? {
        try {
            if (bytes.size < 6) return null
            val version = bytes[0].toInt() and 0xFF
            if (version != 1) return null

            val flags = DiagnosticLogChunkFlags.fromByte(bytes[1])
            val messageId = ((bytes[2].toInt() and 0xFF) or ((bytes[3].toInt() and 0xFF) shl 8))
            val chunkIndex = bytes[4].toInt() and 0xFF
            val payloadLength = bytes[5].toInt() and 0xFF

            if (bytes.size < 6 + payloadLength || payloadLength !in 1..14 || chunkIndex >= 10) return null

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
                if (payloadLength == 5 && chunkIndex == 0 && flags.isFinalChunk && payload[4].toInt() in 0..1) {
                    val nextOffset = (payload[0].toLong() and 0xFF) or
                            ((payload[1].toLong() and 0xFF) shl 8) or
                            ((payload[2].toLong() and 0xFF) shl 16) or
                            ((payload[3].toLong() and 0xFF) shl 24)
                    val finished = payload[4].toInt() != 0
                    if (flags.isCaptureLine) {
                        if (rememberCaptureMessage(messageId)) {
                            _capturePageEvents.tryEmit(
                                CapturePageEvent.End(messageId, HistoricalPageEnd(nextOffset, finished))
                            )
                        }
                    } else {
                        emitDirectLogPageEnd(HistoricalPageEnd(nextOffset, finished))
                    }
                }
                return chunk
            }

            if (flags.isCaptureLine && messageId in completedCaptureMessages) return chunk
            cleanStaleAssemblies()
            if (activeMessages.size >= 128 && !activeMessages.containsKey(messageId)) return chunk

            val chunksMap = activeMessages.computeIfAbsent(messageId) {
                messageFirstReceived[messageId] = System.currentTimeMillis()
                messageIsHistorical[messageId] = isHistorical || flags.isError
                messageIsCaptureLine[messageId] = flags.isCaptureLine
                ConcurrentHashMap()
            }
            if (chunksMap.containsKey(chunkIndex)) return chunk
            chunksMap[chunkIndex] = chunk

            // Try reassembling if we have both first and final chunks
            checkAndAssemble(messageId)

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
                    parseCaptureLine(decodedText)?.let { record ->
                        if (rememberCaptureMessage(messageId)) {
                            _capturePageEvents.tryEmit(CapturePageEvent.Record(messageId, record))
                        }
                    }
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

                emitMessage(logMessage)

                activeMessages.remove(messageId)
                messageFirstReceived.remove(messageId)
                messageIsHistorical.remove(messageId)
                messageIsCaptureLine.remove(messageId)
            }
        }
    }

    @Synchronized
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

    private fun parseCaptureLine(text: String): CaptureRecord? {
        // Format from the firmware: CAP,<timestampMs>,<stage>,<idHex>,<ext>,<dlc>,<dataHex>
        val parts = text.trim().removeSuffix("\r").removeSuffix("\n").split(",")
        if (parts.size != 7 || parts[0] != "CAP" || parts[4] !in setOf("0", "1")) return null
        return try {
            val dlc = parts[5].toInt()
            if (dlc !in 0..8 || parts[6].length != 2 * dlc || parts[6].any { it.digitToIntOrNull(16) == null }) {
                return null
            }
            CaptureRecord(
                timestampMs = parts[1].toLong(),
                stage = parts[2].toInt(),
                canId = parts[3].toLong(16),
                isExtended = parts[4] != "0",
                dlc = dlc,
                dataHex = parts[6]
            )
        } catch (e: Exception) {
            // Malformed capture line: drop rather than fabricate data.
            null
        }
    }

    private fun rememberCaptureMessage(messageId: Int): Boolean {
        if (!completedCaptureMessages.add(messageId)) return false
        if (completedCaptureMessages.size > 32) completedCaptureMessages.remove(completedCaptureMessages.first())
        return true
    }

    private fun emitMessage(message: DiagnosticLogMessage) {
        _completedMessages.tryEmit(message)
        if (message.severity == LogSeverity.ERROR &&
            (message.messageText.startsWith("AUTH REQUIRED: owner session needed for log/capture export") ||
                message.messageText.startsWith("CAPTURE EXPORT FAILED:"))
        ) {
            _capturePageEvents.tryEmit(CapturePageEvent.Rejected(message.messageText))
        }
    }

    @Synchronized
    fun clear() {
        activeMessages.clear()
        messageFirstReceived.clear()
        messageIsHistorical.clear()
        messageIsCaptureLine.clear()
        completedCaptureMessages.clear()
        _capturePageEvents.tryEmit(CapturePageEvent.Disconnected)
    }
}
