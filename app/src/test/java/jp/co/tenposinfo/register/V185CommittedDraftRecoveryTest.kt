package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.85: crash after a successful sale must not require a duplicate payment. */
class V185CommittedDraftRecoveryTest {
    private fun code(name: String): String =
        File("src/main/java/jp/co/tenposinfo/register/$name").readText()
    private fun committed(fingerprint: String = "cart-A", total: Long = 1000L, id: Long = 12L) =
        SaleCommitIdempotencySchema.ExistingCommit(id, fingerprint, total)
    private fun tender(amount: Long = 1000L) =
        listOf(PaymentAllocation(PaymentMethod.CASH, amount, amount))

    @Test fun exactConfirmedAndFullyPaidSaleCanReleaseTheStaleDraft() {
        assertTrue(PaymentDraftCommittedRecoveryV185.canRelease("cart-A", tender(), committed()))
        val split = listOf(
            PaymentAllocation(PaymentMethod.CARD, 400L, 400L),
            PaymentAllocation(PaymentMethod.CASH, 600L, 1000L),
        )
        assertTrue(PaymentDraftCommittedRecoveryV185.canRelease("cart-A", split, committed()))
    }

    @Test fun uncertainPartialAndMismatchedSalesCanNeverBeAutomaticallyCleared() {
        assertFalse(PaymentDraftCommittedRecoveryV185.canRelease("cart-A", tender(), null))
        assertFalse(PaymentDraftCommittedRecoveryV185.canRelease("cart-A", tender(), committed(id = 0L)))
        assertFalse(PaymentDraftCommittedRecoveryV185.canRelease("cart-B", tender(), committed()))
        assertFalse(PaymentDraftCommittedRecoveryV185.canRelease("cart-A", tender(300L), committed()))
        assertFalse(PaymentDraftCommittedRecoveryV185.canRelease("cart-A", tender(1200L), committed()))
        assertFalse(PaymentDraftCommittedRecoveryV185.canRelease(
            "cart-A", tender(), committed(total = 900L),
        ))
    }

    @Test fun emptyZeroYenSaleIsSafeButOrdinaryUnallocatedSaleIsNot() {
        assertTrue(PaymentDraftCommittedRecoveryV185.canRelease(
            "zero", emptyList(), committed("zero", total = 0L),
        ))
        assertFalse(PaymentDraftCommittedRecoveryV185.canRelease(
            "cart-A", emptyList(), committed(),
        ))
    }

    @Test fun recoveryReadsActualSavedSaleBeforeClearingTheDraft() {
        val schema = code("SaleCommitIdempotencySchema.kt")
        assertTrue(schema.contains("INNER JOIN sales AS s"))
        assertTrue(schema.contains("s.id = k.sale_id AND s.total_amount = k.total_amount"))
        val store = code("PaymentDraftStore.kt")
        val recovered = store.substringAfter("val confirmedSale = metadata.third")
            .substringBefore("if (metadata.first != PaymentDraftFingerprint.of(items))")
        assertTrue(recovered.contains("SaleCommitIdempotencySchema.findConfirmed(db, it)"))
        assertTrue(recovered.contains("PaymentDraftCommittedRecoveryV185.canRelease("))
        assertTrue(recovered.contains("clear()"))
        assertTrue(recovered.indexOf("PaymentDraftCommittedRecoveryV185.canRelease(") <
            recovered.indexOf("clear()"))
        assertTrue(recovered.contains("recoveredConfirmedSaleId = confirmedSale.saleId"))
    }

    @Test fun allOtherClearPathsMustPreserveUnconfirmedTenderEvidence() {
        val store = code("PaymentDraftStore.kt")
        val clear = store.substringAfter("    fun clear() {")
            .substringBefore("    private fun ensureSchema()")
        assertTrue(clear.contains("if (hasAllocations)"))
        assertTrue(clear.contains("PaymentDraftCommittedRecoveryV185.canRelease("))
        assertTrue(clear.contains("require(identity != null)"))
        assertTrue(clear.indexOf("PaymentDraftCommittedRecoveryV185.canRelease(") <
            clear.indexOf("db.delete(TABLE_ALLOCATIONS, null, null)"))
        val main = code("MainActivity.kt")
        assertTrue(main.contains("draft.recoveredConfirmedSaleId != null"))
        assertTrue(main.contains("今回は別の会計です"))
        assertTrue(main.contains("AppScreen.COMPLETE -> moveToNextSaleV184()"))
    }
}
