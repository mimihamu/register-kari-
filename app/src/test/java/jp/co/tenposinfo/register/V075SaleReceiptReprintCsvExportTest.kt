package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

class V075SaleReceiptReprintCsvExportTest {
    @Test
    fun csvPolicyQuotesCellsNormalizesLineBreaksAndNeutralizesSpreadsheetFormulas() {
        assertEquals("' =2+2", SaleReceiptReprintCsvPolicy.safeText(" =2+2"))
        assertEquals("'+SUM(A1:A2)", SaleReceiptReprintCsvPolicy.safeText("+SUM(A1:A2)"))
        assertEquals("'-1", SaleReceiptReprintCsvPolicy.safeText("-1"))
        assertEquals("'@cmd", SaleReceiptReprintCsvPolicy.safeText("@cmd"))
        assertEquals("normal\ntext", SaleReceiptReprintCsvPolicy.safeText("normal\r\ntext"))
        assertEquals("\"a\"\"b\"", SaleReceiptReprintCsvPolicy.csvCell("a\"b"))
    }

    @Test
    fun csvRowIsDeterministicAndUsesIsoOffsetTimestamps() {
        val entry = SaleReceiptReprintLedgerEntry(
            auditId = 9L,
            requestId = "req-9",
            saleId = 123L,
            saleAmount = 4_500L,
            saleCreatedAt = 1_786_150_800_000L,
            printJobId = 77L,
            operatorName = "=danger",
            paperWidthMm = 80,
            requestedAt = 1_786_154_400_000L,
            status = PrintJobStatus.COMPLETED,
            attemptCount = 2,
            lastError = "a\"b",
        )
        val row = SaleReceiptReprintCsvPolicy.row(entry, ZoneId.of("Asia/Tokyo"))
        assertTrue(row.contains("\"req-9\""))
        assertTrue(row.contains("\"123\""))
        assertTrue(row.contains("\"4500\""))
        assertTrue(row.contains("\"'=danger\""))
        assertTrue(row.contains("\"a\"\"b\""))
        assertTrue(row.contains("+09:00"))
    }

    @Test
    fun sourceExportsAppliedCriteriaAndFrozenSnapshotReadOnlyViaSaf() {
        val root = File("..")
        val exporter = File("src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintCsvExport.kt").readText()
        val activity = File("src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        val docs = File(root, "docs/V0.75_REPRINT_LEDGER_CSV_EXPORT.md")
        val notes = File(root, "docs/V0.75_RELEASE_NOTES.md")

        assertTrue(exporter.contains("SaleReceiptReprintLedgerPolicy.buildDatabaseQuery(criteria)"))
        assertTrue(exporter.contains("appendSnapshotBound(base, snapshot)"))
        assertTrue(exporter.contains("ORDER BY r.requested_at DESC, r.id DESC"))
        assertFalse(exporter.contains("LIMIT ?"))
        assertFalse(exporter.contains("OFFSET ?"))
        assertTrue(exporter.contains("writer.write('\\uFEFF'.code)"))
        assertTrue(exporter.contains("safeText"))
        assertFalse(exporter.contains("UPDATE sale_receipt_reprint_requests"))
        assertFalse(exporter.contains("DELETE FROM sale_receipt_reprint_requests"))

        assertTrue(activity.contains("ActivityResultContracts.CreateDocument(\"text/csv\")"))
        assertTrue(activity.contains("pendingExportCriteria = appliedCriteria"))
        assertTrue(activity.contains("pendingExportSnapshot = snapshot"))
        assertTrue(activity.contains("RegisterPermission.VIEW_SALES"))
        assertTrue(activity.contains("SaleReceiptReprintCsvExporter"))
        assertTrue(activity.contains("Dispatchers.IO"))
        assertTrue(activity.contains("CSV出力"))

        assertTrue(build.contains("versionCode = 107"))
        assertTrue(build.contains("versionName = \"0.77.0-dev.1\""))
        assertTrue(workflow.contains("V075SaleReceiptReprintCsvExportTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_v0.77.0_dev1_settlement_history_sales_drilldown_debug.apk"))
        assertTrue(docs.isFile)
        assertTrue(notes.isFile)
    }
}
