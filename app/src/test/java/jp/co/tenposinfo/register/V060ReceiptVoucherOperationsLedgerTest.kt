package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V060ReceiptVoucherOperationsLedgerTest {
    private fun receipt(id: Long, saleId: Long = 10L, addressee: String = "株式会社テスト") =
        ReceiptVoucherRecord(
            id = id,
            batchId = 1,
            saleId = saleId,
            sequenceNo = 1,
            sequenceCount = 1,
            amount = 4_000,
            addressee = addressee,
            purpose = "ご飲食代",
            operatorName = "担当A",
            createdAt = 1_700_000_000_000,
        )

    private fun event(
        jobId: Long,
        issuanceId: Long,
        status: PrintJobStatus,
        kind: ReceiptVoucherPrintKind = ReceiptVoucherPrintKind.ORIGINAL,
        error: String? = null,
    ) = ReceiptVoucherPrintEventRecord(
        jobId = jobId,
        issuanceId = issuanceId,
        kind = kind,
        paperWidthMm = 80,
        status = status,
        attemptCount = 1,
        lastError = error,
        createdAt = 1_700_000_000_000 + jobId,
        updatedAt = 1_700_000_000_000 + jobId,
        reprintEventId = if (kind == ReceiptVoucherPrintKind.REPRINT) jobId + 100 else null,
        reprintedBy = if (kind == ReceiptVoucherPrintKind.REPRINT) "責任者B" else null,
        reprintedAt = if (kind == ReceiptVoucherPrintKind.REPRINT) 1_700_000_000_000 + jobId else null,
    )

    @Test
    fun ledgerSummarySeparatesActionRequiredReprintsAndMissingJobs() {
        val completed = ReceiptVoucherLedgerEntry(
            receipt(1),
            listOf(event(11, 1, PrintJobStatus.COMPLETED)),
        )
        val failedReprint = ReceiptVoucherLedgerEntry(
            receipt(2),
            listOf(
                event(21, 2, PrintJobStatus.COMPLETED),
                event(22, 2, PrintJobStatus.FAILED, ReceiptVoucherPrintKind.REPRINT, "送信結果が不明です"),
            ),
        )
        val missing = ReceiptVoucherLedgerEntry(receipt(3), emptyList())

        val summary = ReceiptVoucherLedgerSummary.from(listOf(completed, failedReprint, missing))
        assertEquals(3, summary.receiptCount)
        assertEquals(3, summary.printJobCount)
        assertEquals(1, summary.actionRequiredReceipts)
        assertEquals(1, summary.activeReceipts)
        assertEquals(2, summary.completedPrintJobs)
        assertEquals(1, summary.reprintEvents)
        assertEquals(1, summary.missingPrintJobs)
    }

    @Test
    fun filtersFindActionRequiredReprintedAndSearchableReceipt() {
        val completed = ReceiptVoucherLedgerEntry(
            receipt(1, addressee = "株式会社青空"),
            listOf(event(11, 1, PrintJobStatus.COMPLETED)),
        )
        val failedReprint = ReceiptVoucherLedgerEntry(
            receipt(2, saleId = 99, addressee = "株式会社赤空"),
            listOf(
                event(21, 2, PrintJobStatus.COMPLETED),
                event(22, 2, PrintJobStatus.FAILED, ReceiptVoucherPrintKind.REPRINT, "紙切れ"),
            ),
        )
        val entries = listOf(completed, failedReprint)

        assertEquals(
            listOf(2L),
            ReceiptVoucherLedgerPolicy.filter(
                entries,
                ReceiptVoucherLedgerCriteria(filter = ReceiptVoucherLedgerFilter.ACTION_REQUIRED),
            ).map { it.receipt.id },
        )
        assertEquals(
            listOf(2L),
            ReceiptVoucherLedgerPolicy.filter(
                entries,
                ReceiptVoucherLedgerCriteria(filter = ReceiptVoucherLedgerFilter.REPRINTED),
            ).map { it.receipt.id },
        )
        assertEquals(
            listOf(2L),
            ReceiptVoucherLedgerPolicy.filter(
                entries,
                ReceiptVoucherLedgerCriteria(query = "紙切れ"),
            ).map { it.receipt.id },
        )
        assertEquals(
            listOf(1L),
            ReceiptVoucherLedgerPolicy.filter(
                entries,
                ReceiptVoucherLedgerCriteria(query = "青空"),
            ).map { it.receipt.id },
        )
    }

    @Test
    fun statusPolicyKeepsRecoveryInsideUnifiedQueue() {
        val failed = ReceiptVoucherLedgerEntry(
            receipt(1),
            listOf(event(10, 1, PrintJobStatus.FAILED, error = "connection refused")),
        )
        assertEquals("要対応", ReceiptVoucherLedgerPolicy.latestStatusLabel(failed))
        assertTrue(ReceiptVoucherLedgerPolicy.needsUnifiedQueue(failed))
        assertEquals("完了", ReceiptVoucherLedgerPolicy.printStatusLabel(PrintJobStatus.COMPLETED))
        assertEquals("再試行待ち", ReceiptVoucherLedgerPolicy.printStatusLabel(PrintJobStatus.RETRY))
    }

    @Test
    fun sourceIsReadOnlyAndLedgerLinksToExistingUnifiedQueue() {
        val root = File("..")
        val operations = File("src/main/java/jp/co/tenposinfo/register/ReceiptVoucherOperations.kt").readText()
        val ledger = File("src/main/java/jp/co/tenposinfo/register/ReceiptVoucherLedgerActivity.kt").readText()
        val issuance = File("src/main/java/jp/co/tenposinfo/register/ReceiptVoucherActivity.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()

        assertTrue(operations.contains("LEFT JOIN receipt_voucher_reprints"))
        assertTrue(operations.contains("document_print_jobs"))
        assertTrue(operations.contains("OperationDocumentType.RECEIPT_VOUCHER.name"))
        assertFalse(operations.contains("db.delete("))
        assertFalse(operations.contains("db.update("))
        assertTrue(ledger.contains("統合印刷キューで確認・対応"))
        assertTrue(ledger.contains("UnifiedPrintQueueActivity::class.java"))
        assertTrue(ledger.contains("再試行・破棄・強制印刷は二重印刷防止のため統合印刷キュー"))
        assertTrue(issuance.contains("ReceiptVoucherLedgerActivity::class.java"))
        assertTrue(issuance.contains("運用台帳・印刷状態"))
        assertTrue(manifest.contains(".ReceiptVoucherLedgerActivity"))
        assertTrue(workflow.contains("V060ReceiptVoucherOperationsLedgerTest.kt"))
    }
}
