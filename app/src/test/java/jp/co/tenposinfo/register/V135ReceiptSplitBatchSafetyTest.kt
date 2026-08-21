package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class V135ReceiptSplitBatchSafetyTest {
    @Test
    fun defaultLimitAndAbsoluteRangeFollowV25() {
        assertEquals(100, ReceiptVoucherBatchSettingsV135.DEFAULT_MAX_BATCH_COPIES)
        assertEquals(999, ReceiptVoucherBatchSettingsV135.MAX_BATCH_COPIES)
        assertEquals(999, ReceiptVoucherPolicy.MAX_COPIES)

        val request = ReceiptVoucherBatchRequest(
            requestId = UUID.randomUUID().toString(),
            saleId = 1,
            unitAmount = 1,
            copies = 101,
            addressee = "",
            purpose = "飲食代",
            operatorName = "担当",
        )
        assertTrue(
            runCatching {
                ReceiptVoucherPolicy.plan(request, ReceiptVoucherAvailability(1_000, 0))
            }.isFailure,
        )
        assertEquals(
            101,
            ReceiptVoucherPolicy.plan(
                request,
                ReceiptVoucherAvailability(1_000, 0),
                maxCopies = 999,
            ).copies,
        )
    }

    @Test
    fun fourThousandTimesThirtyIsSupplementaryAndTraceable() {
        val plan = ReceiptVoucherPolicy.plan(
            ReceiptVoucherBatchRequest(
                requestId = UUID.randomUUID().toString(),
                saleId = 880,
                unitAmount = 4_000,
                copies = 30,
                addressee = "",
                purpose = "お食事代",
                operatorName = "担当A",
            ),
            ReceiptVoucherAvailability(120_000, 0),
        )
        assertEquals(120_000L, plan.totalAmount)

        val text = ReceiptVoucherRenderer.render(
            ReceiptVoucherDocumentData(
                issuanceId = 501,
                saleId = 880,
                sequenceNo = 13,
                sequenceCount = 30,
                amount = 4_000,
                addressee = "",
                purpose = "お食事代",
                operatorName = "担当A",
                issuedAt = 1_700_000_000_000,
                issuer = InvoiceIssuerProfile(storeName = "つぐ食堂", registrationNumber = "T1234567890123"),
                batchId = 12,
            ),
            ReceiptPaper.MM80,
        )
        assertTrue(text.contains(ReceiptVoucherRenderer.NOT_QUALIFIED_LABEL))
        assertTrue(text.contains("元売上No.880"))
        assertTrue(text.contains("発行グループ RG-12"))
        assertTrue(text.contains("枝番 13/30"))
        assertFalse(text.contains("消費税(10%)"))
    }

    @Test
    fun partialFailureResumesOnlyUnprintedSequences() {
        val items = (1..30).map { sequence ->
            ReceiptVoucherBatchPrintItemV135(
                sequenceNo = sequence,
                issuanceId = sequence.toLong(),
                jobId = (1_000 + sequence).toLong(),
                status = when {
                    sequence <= 12 -> PrintJobStatus.COMPLETED
                    sequence == 13 -> PrintJobStatus.FAILED
                    else -> PrintJobStatus.PENDING
                },
                attemptCount = if (sequence <= 13) 1 else 0,
                lastError = if (sequence == 13) "paper out" else null,
                updatedAt = 1_700_000_000_000 + sequence,
            )
        }
        val progress = ReceiptVoucherBatchRecoveryPolicyV135.summarize(
            batchId = 12,
            saleId = 880,
            copyCount = 30,
            items = items,
        )
        assertEquals(ReceiptVoucherBatchPrintStatus.PARTIAL, progress.status)
        assertEquals(12, progress.printedCount)
        assertEquals(18, progress.remainingCount)
        assertEquals(13, progress.firstUnprintedSequence)
        assertEquals((13..30).toList(), ReceiptVoucherBatchRecoveryPolicyV135.sequencesToResume(progress))
        assertTrue(progress.resumable)
    }

    @Test
    fun completedJobsAndReprintsAreProtectedFromBatchResume() {
        val root = File("..")
        val recovery = File("src/main/java/jp/co/tenposinfo/register/ReceiptVoucherBatchRecoveryV135.kt").readText()
        val foundation = File("src/main/java/jp/co/tenposinfo/register/ReceiptVoucher.kt").readText()
        val ui = File("src/main/java/jp/co/tenposinfo/register/ReceiptVoucherActivity.kt").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()

        assertTrue(recovery.contains("it.status != PrintJobStatus.COMPLETED"))
        assertTrue(recovery.contains("WHERE r.print_job_id = j.id"))
        assertTrue(recovery.contains("id = ? AND status = ?"))
        assertTrue(recovery.contains("PrintJobStatus.FAILED.name"))
        assertTrue(recovery.contains("PrintJobStatus.RETRY.name"))
        assertTrue(recovery.contains("RECEIPT_BATCH_RESUME"))
        assertFalse(recovery.contains("INSERT INTO receipt_voucher_issuances"))
        assertTrue(foundation.contains("NOT_QUALIFIED_LABEL"))
        assertTrue(foundation.contains("発行グループ RG-"))
        assertTrue(foundation.contains("batchSettings.maxBatchReceiptCopies()"))
        assertTrue(ui.contains("未印刷分を再開"))
        assertTrue(ui.contains("一括上限"))
        assertTrue(ui.contains("宛名（空欄可）"))
        assertTrue(workflow.contains(":app:testDebugUnitTest"))
        assertFalse(File(root, "tools/v135_receipt_batch_apply.py").exists())
    }
}
