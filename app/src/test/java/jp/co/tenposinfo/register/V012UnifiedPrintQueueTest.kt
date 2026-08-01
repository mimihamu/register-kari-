package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V012UnifiedPrintQueueTest {
    @Test
    fun summaryCountsAllStatuses() {
        val jobs = listOf(
            job("1", PrintJobStatus.PENDING),
            job("2", PrintJobStatus.RETRY),
            job("3", PrintJobStatus.FAILED),
            job("4", PrintJobStatus.COMPLETED),
            job("5", PrintJobStatus.PRINTING),
            job("6", PrintJobStatus.COMPLETED),
        )

        val summary = UnifiedPrintQueueSummary.from(jobs)

        assertEquals(6, summary.total)
        assertEquals(1, summary.pending)
        assertEquals(1, summary.retry)
        assertEquals(1, summary.failed)
        assertEquals(2, summary.completed)
        assertEquals(1, summary.printing)
    }

    @Test
    fun preflightAllowsReadyAndWarning() {
        assertTrue(PrinterPreflightPolicy.mayPrint(status(PrinterStatusLevel.READY)))
        assertTrue(PrinterPreflightPolicy.mayPrint(status(PrinterStatusLevel.WARNING)))
    }

    @Test
    fun preflightBlocksOfflineAndError() {
        assertFalse(PrinterPreflightPolicy.mayPrint(status(PrinterStatusLevel.OFFLINE)))
        assertFalse(PrinterPreflightPolicy.mayPrint(status(PrinterStatusLevel.ERROR)))
    }

    @Test
    fun rejectionMessageContainsReasonAndRawBytes() {
        val status = PrinterRealtimeStatusProtocol.parse(byteArrayOf(0x1A, 0x16, 0x1A, 0x72))
        val message = PrinterPreflightPolicy.rejectionMessage(status)

        assertTrue(message.contains("安全印刷を停止"))
        assertTrue(message.contains(status.summary))
        assertTrue(message.contains(status.rawHex))
    }

    private fun job(key: String, status: PrintJobStatus) = UnifiedPrintJob(
        key = key,
        sourceId = key.toLong(),
        type = UnifiedPrintJobType.SALE_RECEIPT,
        referenceId = 1,
        paperWidthMm = 80,
        status = status,
        attemptCount = 0,
        lastError = null,
        previewText = "preview",
        createdAt = 1,
        updatedAt = 1,
    )

    private fun status(level: PrinterStatusLevel): PrinterRealtimeStatus = when (level) {
        PrinterStatusLevel.READY -> PrinterRealtimeStatusProtocol.parse(byteArrayOf(0x12, 0x12, 0x12, 0x12))
        PrinterStatusLevel.WARNING -> PrinterRealtimeStatusProtocol.parse(byteArrayOf(0x12, 0x12, 0x12, 0x1E))
        PrinterStatusLevel.OFFLINE -> PrinterRealtimeStatusProtocol.parse(byteArrayOf(0x1A, 0x12, 0x12, 0x12))
        PrinterStatusLevel.ERROR -> PrinterRealtimeStatusProtocol.parse(byteArrayOf(0x1A, 0x52, 0x1A, 0x12))
    }
}
