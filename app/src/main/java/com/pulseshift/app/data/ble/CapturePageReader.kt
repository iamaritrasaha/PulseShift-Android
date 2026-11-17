package com.pulseshift.app.data.ble

import com.pulseshift.app.domain.model.CaptureRecord
import com.pulseshift.app.domain.model.HistoricalPageEnd
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface CapturePageEvent {
    data class Record(val messageId: Int, val record: CaptureRecord) : CapturePageEvent
    data class End(val messageId: Int, val cursor: HistoricalPageEnd) : CapturePageEvent
    data class Rejected(val reason: String) : CapturePageEvent
    data object Disconnected : CapturePageEvent
}

internal sealed interface CapturePageRead {
    data class Complete(val records: List<CaptureRecord>, val cursor: HistoricalPageEnd) : CapturePageRead
    data class Failed(val reason: String) : CapturePageRead
}

/** A page moves the cursor only after its EOP agrees with the received records. */
internal class CapturePageReader(
    private val log: DiagnosticLogReassembler,
    private val request: (Long) -> Unit,
    private val cancel: () -> Unit
) {
    private val transfer = Mutex()

    suspend fun read(startRecord: Long): CapturePageRead {
        if (!transfer.tryLock()) return CapturePageRead.Failed("Another capture page is being fetched.")
        var complete = false
        try {
            var failure = "Timed out waiting for end-of-page"
            repeat(2) {
                val records = LinkedHashMap<Int, CaptureRecord>(PAGE_RECORDS)
                var overflow = false
                val terminal = withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                    log.capturePageEvents.onSubscription { request(startRecord) }.first { event ->
                        if (event is CapturePageEvent.Record) {
                            if (event.messageId !in records) {
                                if (records.size < PAGE_RECORDS) records[event.messageId] = event.record
                                else overflow = true
                            }
                            false
                        } else {
                            true
                        }
                    }
                }
                when (terminal) {
                    is CapturePageEvent.End -> {
                        val end = terminal.cursor
                        val expected = end.nextOffset - startRecord
                        if (!overflow && expected == records.size.toLong() &&
                            (expected > 0 || end.isFinished)
                        ) {
                            // Firmware allocates a message ID in record order. Sorting by
                            // distance from EOP also handles uint16 wrap and interleaved logs.
                            val ordered = records.entries
                                .sortedByDescending { (terminal.messageId - it.key) and 0xffff }
                                .map { it.value }
                            complete = true
                            return CapturePageRead.Complete(ordered, end)
                        }
                        failure = "Incomplete capture page (received ${records.size}, cursor advanced $expected)"
                    }
                    is CapturePageEvent.Rejected -> return CapturePageRead.Failed(terminal.reason)
                    CapturePageEvent.Disconnected -> return CapturePageRead.Failed("Device disconnected.")
                    null -> failure = "Timed out waiting for end-of-page"
                    is CapturePageEvent.Record -> error("Record events are collected before EOP")
                }
                if (it == 0) cancel()
            }
            return CapturePageRead.Failed("$failure at record $startRecord.")
        } finally {
            if (!complete) cancel()
            transfer.unlock()
        }
    }

    private companion object {
        const val PAGE_RECORDS = 12
        const val PAGE_TIMEOUT_MS = 4_000L
    }
}
