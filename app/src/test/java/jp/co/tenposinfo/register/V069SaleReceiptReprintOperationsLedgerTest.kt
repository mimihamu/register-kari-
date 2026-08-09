package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V069SaleReceiptReprintOperationsLedgerTest {
    private fun entry(
        id: Long,
        saleId: Long,
        operator: String,
        status: PrintJobStatus,
        error: String? = null,
    ) = SaleReceiptReprintLedgerEntry(
        auditId = id,
        requestId = "request-$id",
        saleId = saleId,
        saleAmount = 4_000,
        saleCreatedAt = id * 100,
        printJobId = id + 100,
        operatorName = operator,
        paperWidthMm = 80,
        requestedAt = id * 1_000,
        status = status,
        attemptCount = if (status == PrintJobStatus.FAILED) 3 else 1,
        lastError = error,
    )

    @Test
    fun filtersStatusAndSearchWithoutMutatingEntries() {
        val entries = listOf(
            entry(1, 100, "山田", PrintJobStatus.FAILED, "paper empty"),
            entry(2, 101, "佐藤", PrintJobStatus.RETRY, "timeout"),
            entry(3, 102, "山田", PrintJobStatus.COMPLETED),
            entry(4, 103, "鈴木", PrintJobStatus.DISCARDED),
            entry(5, 104, "田中", PrintJobStatus.PENDING),
        )

        assertEquals(
            listOf(1L, 2L),
            SaleReceiptReprintLedgerPolicy.filter(
                entries,
                SaleReceiptReprintLedgerCriteria(SaleReceiptReprintLedgerFilter.ACTION_REQUIRED),
            ).map { it.auditId },
        )
        assertEquals(
            listOf(1L, 2L, 5L),
            SaleReceiptReprintLedgerPolicy.filter(
                entries,
                SaleReceiptReprintLedgerCriteria(SaleReceiptReprintLedgerFilter.ACTIVE),
            ).map { it.auditId },
        )
        assertEquals(
            listOf(1L, 3L),
            SaleReceiptReprintLedgerPolicy.filter(
                entries,
                SaleReceiptReprintLedgerCriteria(query = "山田"),
            ).map { it.auditId },
        )
        assertEquals(listOf(100L), SaleReceiptReprintLedgerPolicy.filter(entries, SaleReceiptReprintLedgerCriteria(query = "paper")).map { it.saleId })
        assertEquals(5, entries.size)
    }

    @Test
    fun summarySeparatesActionRequiredActiveCompletedAndDiscarded() {
        val entries = listOf(
            entry(1, 100, "A", PrintJobStatus.FAILED),
            entry(2, 101, "B", PrintJobStatus.RETRY),
            entry(3, 102, "C", PrintJobStatus.PENDING),
            entry(4, 103, "D", PrintJobStatus.PRINTING),
            entry(5, 104, "E", PrintJobStatus.COMPLETED),
            entry(6, 105, "F", PrintJobStatus.DISCARDED),
        )
        val summary = SaleReceiptReprintLedgerSummary.from(entries)
        assertEquals(6, summary.total)
        assertEquals(2, summary.actionRequired)
        assertEquals(4, summary.active)
        assertEquals(1, summary.completed)
        assertEquals(1, summary.discarded)
    }

    @Test
    fun sourceKeepsLedgerReadOnlyAndRecoveryCentralized() {
        val root = File("..")
        val store = File("src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintOperations.kt").readText()
        val activity = File("src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt").readText()
        val reprint = File("src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintActivity.kt").readText()
        val hub = File("src/main/java/jp/co/tenposinfo/register/OperationsHubActivityV030.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        val docs = File(root, "docs/V0.69_SALE_RECEIPT_REPRINT_OPERATIONS_LEDGER.md")
        val notes = File(root, "docs/V0.69_RELEASE_NOTES.md")

        assertTrue(store.contains("INNER JOIN print_jobs"))
        assertTrue(store.contains("INNER JOIN sales"))
        assertTrue(store.contains("SaleReceiptReprintAuditStore.TABLE"))
        assertFalse(store.contains("UPDATE sale_receipt_reprint_requests"))
        assertFalse(store.contains("DELETE FROM sale_receipt_reprint_requests"))
        assertFalse(store.contains("retryPrintJob"))
        assertFalse(store.contains("discardPrintJob"))

        assertTrue(activity.contains("通常レシート再印字 運用台帳"))
        assertTrue(activity.contains("current.allows(RegisterPermission.VIEW_SALES)"))
        assertTrue(activity.contains("統合印刷キューで確認・対応"))
        assertTrue(activity.contains("この台帳では再試行・破棄・強制印刷・履歴削除を行いません"))
        assertTrue(activity.contains("SaleReceiptNavigation.intent(context, saleId)"))
        assertFalse(activity.contains("retrySaleJob"))
        assertFalse(activity.contains("discardSaleJob"))

        assertTrue(reprint.contains("SaleReceiptReprintLedgerActivity::class.java"))
        assertTrue(reprint.contains("運用台帳を開く"))
        assertTrue(hub.contains("SaleReceiptReprintLedgerActivity::class.java"))
        assertTrue(hub.contains("レシート再印字台帳"))
        assertTrue(manifest.contains(".SaleReceiptReprintLedgerActivity"))

        assertTrue(build.contains("versionCode = 107"))
        assertTrue(build.contains("versionName = \"0.77.0-dev.1\""))
        assertTrue(workflow.contains("V069SaleReceiptReprintOperationsLedgerTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_v0.77.0_dev1_settlement_history_sales_drilldown_debug.apk"))
        assertTrue(docs.isFile)
        assertTrue(notes.isFile)
    }
}
