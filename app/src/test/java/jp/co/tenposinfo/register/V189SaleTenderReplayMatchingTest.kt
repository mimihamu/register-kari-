package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.89: no automatic recovery or sale idempotency on totals alone. */
class V189SaleTenderReplayMatchingTest {
    private fun code(name: String): String =
        File("src/main/java/jp/co/tenposinfo/register/$name").readText()
    private val cash = PaymentAllocation(PaymentMethod.CASH, 600L, 1000L)
    private val card = PaymentAllocation(PaymentMethod.CARD, 400L, 400L)
    private val approvedSplit = listOf(card, cash)

    @Test fun identicalSavedSaleTenderAllowsAnIdempotentRetry() {
        assertTrue(SaleCommitPaymentMatchV189.sameSequence(approvedSplit, approvedSplit.toList()))
        assertTrue(SaleCommitPaymentMatchV189.sameSequence(emptyList(), emptyList()))
        assertTrue(SaleCommitPaymentMatchV189.contiguousSequenceNumbers(emptyList()))
        assertTrue(SaleCommitPaymentMatchV189.contiguousSequenceNumbers(listOf(1, 2, 3)))
    }

    @Test fun sameTotalButDifferentMethodOrCashChangeOrOrderMustBlock() {
        val changedMethod = listOf(PaymentAllocation(PaymentMethod.QR, 400L, 400L), cash)
        val changedReceived = listOf(card, PaymentAllocation(PaymentMethod.CASH, 600L, 1200L))
        val sameTotalButOtherSplit = listOf(
            PaymentAllocation(PaymentMethod.CARD, 500L, 500L),
            PaymentAllocation(PaymentMethod.CASH, 500L, 1000L),
        )
        for (candidate in listOf(changedMethod, changedReceived, sameTotalButOtherSplit,
                approvedSplit.reversed(), listOf(PaymentAllocation(PaymentMethod.CASH, 1000L, 1400L)),
                emptyList())) {
            assertFalse(SaleCommitPaymentMatchV189.sameSequence(approvedSplit, candidate))
        }
    }

    @Test fun missingDuplicateOrNonContiguousSalePaymentSequenceMustBlock() {
        for (bad in listOf(listOf(0), listOf(2), listOf(1, 3),
                listOf(1, 1), listOf(1, 2, 2), listOf(2, 1))) {
            assertFalse(SaleCommitPaymentMatchV189.contiguousSequenceNumbers(bad))
        }
    }

    @Test fun staleDraftCleanupChecksRecordedSalePaymentsBeforeDeletion() {
        val schema = code("SaleCommitIdempotencySchema.kt")
        assertTrue(schema.contains("fun recordedPaymentsMatch("))
        assertTrue(schema.contains("\"sale_payments\""))
        assertTrue(schema.contains("SaleCommitPaymentMatchV189.contiguousSequenceNumbers(sequences)"))
        assertTrue(schema.contains("SaleCommitPaymentMatchV189.sameSequence(recorded, expected)"))
        val store = code("PaymentDraftStore.kt")
        val recovery = store.substringAfter("val confirmedSale = metadata.third")
            .substringBefore("if (metadata.first != PaymentDraftFingerprint.of(items))")
        assertTrue(recovery.contains("SaleCommitIdempotencySchema.recordedPaymentsMatch("))
        assertTrue(recovery.indexOf("SaleCommitIdempotencySchema.recordedPaymentsMatch(") <
            recovery.indexOf("clear()"))
        val clear = store.substringAfter("    fun clear() {")
            .substringBefore("    private fun ensureSchema()")
        assertTrue(clear.contains("SaleCommitIdempotencySchema.recordedPaymentsMatch("))
        assertTrue(clear.indexOf("SaleCommitIdempotencySchema.recordedPaymentsMatch(") <
            clear.indexOf("db.delete(TABLE_ALLOCATIONS, null, null)"))
    }

    @Test fun existingSaleRetryChecksTenderBeforeClearingTheCart() {
        val db = code("RegisterDatabase.kt")
        val transaction = db.substringAfter("if (normalizedCommitKey != null) {\n                val existing")
            .substringBefore("val businessLink = BusinessSessionSchema.currentOpen(this)")
        assertTrue(transaction.contains("SaleCommitIdempotencySchema.requireCompatible("))
        assertTrue(transaction.contains("SaleCommitIdempotencySchema.recordedPaymentsMatch("))
        assertTrue(transaction.contains("SaleCommitPaymentMatchV189.REVIEW_MESSAGE"))
        assertTrue(transaction.indexOf("SaleCommitIdempotencySchema.recordedPaymentsMatch(") <
            transaction.indexOf("delete(\"cart_items\", null, null)"))
    }
}
