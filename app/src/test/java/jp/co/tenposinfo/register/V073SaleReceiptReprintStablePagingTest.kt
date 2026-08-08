package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V073SaleReceiptReprintStablePagingTest {
    @Test
    fun snapshotBoundIncludesSnapshotRowAndExcludesNewerSameMillisecondIds() {
        val base = SaleReceiptReprintLedgerSqlQuery("WHERE j.status = ?", listOf("COMPLETED"))
        val snapshot = SaleReceiptReprintLedgerSnapshot(
            SaleReceiptReprintLedgerCursor(requestedAt = 1_000L, auditId = 50L),
        )
        val spec = SaleReceiptReprintStablePagingPolicy.appendSnapshotBound(base, snapshot)

        assertTrue(spec.whereSql.contains("r.requested_at < ?"))
        assertTrue(spec.whereSql.contains("r.requested_at = ? AND r.id <= ?"))
        assertEquals(listOf("COMPLETED", "1000", "1000", "50"), spec.args)
    }

    @Test
    fun afterCursorUsesStrictIdBoundarySoLastRowIsNotRepeated() {
        val base = SaleReceiptReprintLedgerSqlQuery("", emptyList())
        val cursor = SaleReceiptReprintLedgerCursor(requestedAt = 2_000L, auditId = 77L)
        val spec = SaleReceiptReprintStablePagingPolicy.appendAfterCursor(base, cursor)

        assertTrue(spec.whereSql.startsWith("WHERE"))
        assertTrue(spec.whereSql.contains("r.requested_at = ? AND r.id < ?"))
        assertFalse(spec.whereSql.contains("r.id <= ?"))
        assertEquals(listOf("2000", "2000", "77"), spec.args)
    }

    @Test
    fun cursorComesFromVisibleLedgerRowOrderingKey() {
        val entry = SaleReceiptReprintLedgerEntry(
            auditId = 88L,
            requestId = "req",
            saleId = 12L,
            saleAmount = 3_000L,
            saleCreatedAt = 1L,
            printJobId = 21L,
            operatorName = "担当",
            paperWidthMm = 80,
            requestedAt = 9_999L,
            status = PrintJobStatus.COMPLETED,
            attemptCount = 1,
            lastError = null,
        )
        assertEquals(
            SaleReceiptReprintLedgerCursor(9_999L, 88L),
            SaleReceiptReprintStablePagingPolicy.cursorOf(entry),
        )
    }

    @Test
    fun sourceUsesSnapshotKeysetForScr648AndKeepsLegacyOffsetCompatibilityOnly() {
        val root = File("..")
        val stable = File("src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintStablePaging.kt").readText()
        val legacy = File("src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintOperations.kt").readText()
        val activity = File("src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        val docs = File(root, "docs/V0.73_REPRINT_LEDGER_STABLE_PAGING.md")
        val notes = File(root, "docs/V0.73_RELEASE_NOTES.md")

        assertTrue(stable.contains("captureSnapshot"))
        assertTrue(stable.contains("searchStable"))
        assertTrue(stable.contains("r.id <= ?"))
        assertTrue(stable.contains("r.id < ?"))
        assertTrue(stable.contains("ORDER BY r.requested_at DESC, r.id DESC"))
        assertTrue(stable.contains("LIMIT ?"))
        assertFalse(stable.contains("OFFSET ?"))
        assertTrue(stable.contains("newerAuditCount"))
        assertFalse(stable.contains("UPDATE sale_receipt_reprint_requests"))
        assertFalse(stable.contains("DELETE FROM sale_receipt_reprint_requests"))

        // v0.70 API remains available for compatibility, but SCR-648 no longer uses it.
        assertTrue(legacy.contains("LIMIT ? OFFSET ?"))
        assertTrue(activity.contains("SaleReceiptReprintStablePagingStore"))
        assertTrue(activity.contains("store.captureSnapshot(criteria)"))
        assertTrue(activity.contains("store.searchStable(appliedCriteria, snapshot, pageCursor)"))
        assertTrue(activity.contains("cursorHistory"))
        assertTrue(activity.contains("検索時点固定"))
        assertTrue(activity.contains("page.newerAuditCount > 0"))
        assertFalse(activity.contains("pageOffset"))
        assertFalse(activity.contains("store.search(appliedCriteria"))

        assertTrue(build.contains("versionCode = 105"))
        assertTrue(build.contains("versionName = \"0.75.0-dev.1\""))
        assertTrue(workflow.contains("V073SaleReceiptReprintStablePagingTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_v0.75.0_dev1_sale_receipt_reprint_csv_export_debug.apk"))
        assertTrue(docs.isFile)
        assertTrue(notes.isFile)
    }
}
