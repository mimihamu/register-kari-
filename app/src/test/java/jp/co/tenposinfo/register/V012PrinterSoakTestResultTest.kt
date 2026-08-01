package jp.co.tenposinfo.register

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V012PrinterSoakTestResultTest {
    @Test
    fun csvEscape_quotesCommaQuoteAndNewline() {
        assertTrue(PrinterSoakTestCsv.escape("a,b").startsWith("\""))
        assertTrue(PrinterSoakTestCsv.escape("a\"b").contains("\"\""))
        assertTrue(PrinterSoakTestCsv.escape("a\nb").startsWith("\""))
        assertFalse(PrinterSoakTestCsv.escape("abc").startsWith("\""))
    }

    @Test
    fun render_containsRunMetadataAndStepOutcome() {
        val run = PrinterSoakTestRunRecord(
            id = 12L,
            startedAt = 1_700_000_000_000L,
            finishedAt = 1_700_000_010_000L,
            totalPlanned = 20,
            completedCount = 3,
            intervalMillis = 5_000L,
            cutEachPrint = false,
            status = PrinterSoakTestRunStatus.STOPPED,
            printerName = "厨房,プリンター",
            host = "10.0.1.201",
            port = 9100,
            paperWidthMm = 80,
            profileName = "EPSON TM（日本語）",
            actorName = "責任者",
            summary = "紙切れで停止",
            csvPath = null,
        )
        val step = PrinterSoakTestStepRecord(
            sequence = 4,
            checkedAt = 1_700_000_009_000L,
            statusLevel = PrinterStatusLevel.OFFLINE.name,
            statusSummary = "ロール紙がありません",
            rawHex = "12 12 12 72",
            statusElapsedMillis = 21L,
            sentAt = null,
            outcome = PrinterSoakTestStepOutcome.STOPPED_BY_STATUS,
            detail = "送信前に停止",
        )

        val csv = PrinterSoakTestCsv.render(run, listOf(step))

        assertTrue(csv.contains("試験ID,12"))
        assertTrue(csv.contains("停止"))
        assertTrue(csv.contains("\"厨房,プリンター\""))
        assertTrue(csv.contains("状態異常で停止"))
        assertTrue(csv.contains("送信前に停止"))
    }
}
