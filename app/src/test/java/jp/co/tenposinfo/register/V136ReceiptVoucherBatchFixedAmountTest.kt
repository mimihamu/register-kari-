package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class V136ReceiptVoucherBatchFixedAmountTest {
    @Test
    fun fourThousandTimesThirtyPassesFormalLimitAndRemainingAmount() {
        val request = ReceiptVoucherBatchRequest(
            requestId = UUID.randomUUID().toString(),
            saleId = 33,
            unitAmount = 4_000,
            copies = 30,
            addressee = "",
            purpose = "お食事代",
            operatorName = "担当A",
        )
        val plan = ReceiptVoucherPolicy.plan(
            request,
            ReceiptVoucherAvailability(saleTotal = 120_000, allocatedAmount = 0),
            maxCopies = 30,
        )
        assertEquals(120_000L, plan.totalAmount)
        assertEquals(30, plan.copies)
    }

    @Test
    fun configuredLimitAndRemainingAmountAreBothHardGates() {
        val base = ReceiptVoucherBatchRequest(
            requestId = UUID.randomUUID().toString(),
            saleId = 33,
            unitAmount = 4_000,
            copies = 30,
            addressee = "",
            purpose = "お食事代",
            operatorName = "担当A",
        )
        assertTrue(
            runCatching {
                ReceiptVoucherPolicy.plan(base, ReceiptVoucherAvailability(120_000, 0), maxCopies = 29)
            }.isFailure,
        )
        assertTrue(
            runCatching {
                ReceiptVoucherPolicy.plan(base, ReceiptVoucherAvailability(119_999, 0), maxCopies = 30)
            }.isFailure,
        )
    }

    @Test
    fun representativePreviewNeverExpandsLargeBatch() {
        val preview = ReceiptVoucherBatchPreviewPolicyV136.representatives(999)
        assertEquals(listOf(1, 999), preview.map { it.sequenceNo })
        assertEquals(listOf("1枚目", "最終票"), preview.map { it.label })
        assertTrue(preview.size <= 3)
    }

    @Test
    fun remainderDifferenceTicketCanBeRepresentedWithoutRenderingEverything() {
        val preview = ReceiptVoucherBatchPreviewPolicyV136.representatives(
            copies = 30,
            remainderDifferenceSequence = 17,
        )
        assertEquals(listOf(1, 17, 30), preview.map { it.sequenceNo })
        assertEquals("端数差票", preview[1].label)
    }

    @Test
    fun singleCopyPreviewIsNotDuplicated() {
        val preview = ReceiptVoucherBatchPreviewPolicyV136.representatives(1)
        assertEquals(1, preview.size)
        assertEquals(1, preview.single().sequenceNo)
        assertEquals("1枚目・最終票", preview.single().label)
    }

    @Test
    fun issuanceCommitIsAuditedAndPhysicalDispatchStartsAfterDbTransaction() {
        val foundation = File("src/main/java/jp/co/tenposinfo/register/ReceiptVoucher.kt").readText()
        val auditIndex = foundation.indexOf("RECEIPT_VOUCHER_BATCH_COMMIT")
        val endTransactionIndex = foundation.indexOf("db.endTransaction()")
        val schedulerIndex = foundation.indexOf("AutomaticPrintScheduler.enqueueNow(appContext)")

        assertTrue(foundation.contains("put(\"status\", \"DRAFT\")"))
        assertTrue(foundation.contains("put(\"status\", \"COMMITTED\")"))
        assertTrue(foundation.contains("committed_at"))
        assertTrue(auditIndex >= 0)
        assertTrue(endTransactionIndex > auditIndex)
        assertTrue(schedulerIndex > endTransactionIndex)
    }

    @Test
    fun batchRecoveryDoesNotAutoResumeUncertainSendingJobs() {
        val recovery = File("src/main/java/jp/co/tenposinfo/register/ReceiptVoucherBatchRecoveryV135.kt").readText()
        assertTrue(recovery.contains("PrintJobStatus.SENDING"))
        assertTrue(recovery.contains("印刷済みの可能性"))

        val items = listOf(
            ReceiptVoucherBatchPrintItemV135(
                sequenceNo = 1,
                issuanceId = 1,
                jobId = 10,
                status = PrintJobStatus.SENDING,
                attemptCount = 1,
                lastError = null,
                updatedAt = 1,
            ),
        )
        val progress = ReceiptVoucherBatchRecoveryPolicyV135.summarize(1, 33, 1, items)
        assertFalse(progress.resumable)
        assertEquals(ReceiptVoucherBatchPrintStatus.PRINTING, progress.status)
    }

    @Test
    fun confirmationUiUsesRepresentativePreviewAndFormalConfiguredLimit() {
        val activity = File("src/main/java/jp/co/tenposinfo/register/ReceiptVoucherActivity.kt").readText()
        val recovery = File("src/main/java/jp/co/tenposinfo/register/ReceiptVoucherBatchRecoveryV135.kt").readText()
        assertTrue(activity.contains("代表票プレビュー（全票は描画しません）"))
        assertTrue(activity.contains("ReceiptVoucherBatchPreviewPolicyV136.representatives(calc.copies)"))
        assertTrue(activity.contains("voucherStore.maxBatchCopies()"))
        assertEquals(100, ReceiptVoucherBatchSettingsV135.DEFAULT_MAX_BATCH_COPIES)
        assertEquals(999, ReceiptVoucherBatchSettingsV135.MAX_BATCH_COPIES)
        assertTrue(recovery.contains("receipt.maxBatchReceiptCopies"))
    }
}
