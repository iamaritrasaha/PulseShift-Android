package com.pulseshift.app.domain.model

enum class LogSeverity {
    INFO,
    WARNING,
    ERROR
}

data class DiagnosticLogChunkFlags(
    val isFirstChunk: Boolean,
    val isFinalChunk: Boolean,
    val isEndOfHistoricalPage: Boolean,
    val isError: Boolean,
    val isCaptureLine: Boolean = false
) {
    companion object {
        fun fromByte(flags: Byte): DiagnosticLogChunkFlags {
            val b = flags.toInt() and 0xFF
            return DiagnosticLogChunkFlags(
                isFirstChunk = (b and 0x01) != 0,
                isFinalChunk = (b and 0x02) != 0,
                isEndOfHistoricalPage = (b and 0x04) != 0,
                isError = (b and 0x08) != 0,
                isCaptureLine = (b and 0x10) != 0
            )
        }
    }
}

data class DiagnosticLogChunk(
    val version: Int,
    val flags: DiagnosticLogChunkFlags,
    val messageId: Int,
    val chunkIndex: Int,
    val payloadLength: Int,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DiagnosticLogChunk

        if (version != other.version) return false
        if (flags != other.flags) return false
        if (messageId != other.messageId) return false
        if (chunkIndex != other.chunkIndex) return false
        if (payloadLength != other.payloadLength) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + flags.hashCode()
        result = 31 * result + messageId
        result = 31 * result + chunkIndex
        result = 31 * result + payloadLength
        result = 31 * result + data.contentHashCode()
        return result
    }
}

data class DiagnosticLogMessage(
    val id: String,
    val messageId: Int,
    val timestampMs: Long,
    val messageText: String,
    val severity: LogSeverity,
    val isHistorical: Boolean = false,
    val isCorruptOrIncomplete: Boolean = false
)

data class HistoricalPageEnd(
    val nextOffset: Long,
    val isFinished: Boolean
)

sealed class HistoricalLogLoadingState {
    object Idle : HistoricalLogLoadingState()
    data class Loading(val requestedOffset: Long) : HistoricalLogLoadingState()
    data class PageLoaded(val nextOffset: Long, val isFinished: Boolean) : HistoricalLogLoadingState()
    data class Error(val message: String) : HistoricalLogLoadingState()
}

// One parsed CAN capture record streamed over BLE (CSV-like "CAP" line).
data class CaptureRecord(
    val timestampMs: Long,
    val stage: Int,
    val canId: Long,
    val isExtended: Boolean,
    val dlc: Int,
    val dataHex: String
)

sealed class CaptureExportState {
    object Idle : CaptureExportState()
    data class Exporting(
        val nextRecordIndex: Long = 0,
        val records: List<CaptureRecord> = emptyList(),
        val isFinished: Boolean = false
    ) : CaptureExportState()
    data class Failed(val reason: String) : CaptureExportState()
}
