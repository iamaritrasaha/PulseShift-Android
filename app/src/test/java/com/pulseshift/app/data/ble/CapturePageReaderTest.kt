package com.pulseshift.app.data.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CapturePageReaderTest {
    // Firmware's test_capture_retries_exact_wire_packet_and_eop compares these
    // same bytes against its real packed, paced export of one CAN record.
    private val fixture = listOf(
        "01 11 34 12 00 0e 43 41 50 2c 31 30 30 30 2c 31 2c 33 30 31",
        "01 10 34 12 01 0e 2c 30 2c 38 2c 30 30 30 30 30 30 30 30 30",
        "01 12 34 12 02 09 30 30 30 30 30 30 30 0d 0a 00 00 00 00 00",
        "01 16 35 12 00 05 01 00 00 00 01 00 00 00 00 00 00 00 00 00"
    )

    private fun packets(sequenceDelta: Int = 0) = fixture.map { line ->
        line.split(" ").map { it.toInt(16).toByte() }.toByteArray().also {
            it[2] = (it[2] + sequenceDelta).toByte()
        }
    }

    @Test
    fun reconstructsFirmwarePacketsWithOutOfOrderAndDuplicates() = runTest {
        val log = DiagnosticLogReassembler()
        val wire = packets()
        val reader = CapturePageReader(log, {
            for (i in listOf(2, 0, 0, 1, 1, 2, 3, 3)) log.processChunkBytes(wire[i])
        }, { error("The page should be complete") })

        val page = reader.read(0) as CapturePageRead.Complete
        assertEquals(1L, page.cursor.nextOffset)
        assertTrue(page.cursor.isFinished)
        val record = page.records.single()
        assertEquals(1000L, record.timestampMs)
        assertEquals(0x301L, record.canId)
        assertEquals(8, record.dlc)
        assertEquals("0000000000000000", record.dataHex)
    }

    @Test
    fun missingMiddleChunkRetriesSameCursorBeforePublishing() = runTest {
        val requests = mutableListOf<Long>()
        val log = DiagnosticLogReassembler()
        var cancellations = 0
        val reader = CapturePageReader(log, { cursor ->
            requests.add(cursor)
            if (requests.size == 1) {
                val wire = packets()
                for (i in listOf(0, 2, 3)) log.processChunkBytes(wire[i])
            } else {
                packets(sequenceDelta = 2).forEach { log.processChunkBytes(it) }
            }
        }, { cancellations++ })

        val page = reader.read(0) as CapturePageRead.Complete
        assertEquals(listOf(0L, 0L), requests)
        assertEquals(1, cancellations)
        assertEquals(1, page.records.size)
    }

    @Test
    fun missingEopFailsAfterTwoAttemptsWithoutReturningPartialRecords() = runTest {
        var attempts = 0
        var cancellations = 0
        val log = DiagnosticLogReassembler()
        val reader = CapturePageReader(log, {
            packets(sequenceDelta = attempts++ * 2).take(3).forEach { log.processChunkBytes(it) }
        }, { cancellations++ })

        val fail = reader.read(0) as CapturePageRead.Failed
        assertTrue(fail.reason.contains("Timed out"))
        assertEquals(2, attempts)
        assertEquals(2, cancellations)
    }

    @Test
    fun concurrentReadFailsBusyAndDisconnectDiscardsIncompletePage() = runTest {
        val log = DiagnosticLogReassembler()
        var requests = 0
        val reader = CapturePageReader(log, { requests++ }, {})
        val interrupted = async { reader.read(0) }
        runCurrent()
        assertEquals(1, requests)
        val busy = reader.read(12) as CapturePageRead.Failed
        assertTrue(busy.reason.contains("Another"))
        log.processChunkBytes(packets().first())
        log.clear()
        assertEquals("Device disconnected.", (interrupted.await() as CapturePageRead.Failed).reason)
    }
}
