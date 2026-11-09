package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** New sale INSERT cannot trust an in-memory payment alone. */
class V190SaleCommitDraftFenceTest {
    private fun code(name: String) =
        File("src/main/java/jp/co/tenposinfo/register/$name").readText()
    private val key = "11111111-1111-4111-8111-111111111111"
    private val fp = "cart-fingerprint"
    private val split = listOf(
        PaymentAllocation(PaymentMethod.CARD, 400L, 400L),
        PaymentAllocation(PaymentMethod.CASH, 600L, 1000L),
    )
    private fun ok(
        savedFingerprint: String? = fp,
        savedKey: String? = key,
        sequence: List<Int> = listOf(1, 2),
        saved: List<PaymentAllocation> = split,
        requested: List<PaymentAllocation> = split,
        amount: Long = 1000L,
    ) = PaymentCommitDraftFenceV190.matches(
        savedFingerprint, savedKey, sequence, saved, fp, key, requested, amount,
    )

    @Test fun matchingSplitAndCashChangeIsPermitted() {
        assertTrue(ok())
        assertTrue(ok(sequence = emptyList(), saved = emptyList(),
            requested = emptyList(), amount = 0L))
    }

    @Test fun changedCartKeyMethodAmountsOrOrderFailClosed() {
        assertFalse(ok(savedFingerprint = "different-cart"))
        assertFalse(ok(savedFingerprint = null))
        assertFalse(ok(savedKey = "different-key"))
        assertFalse(ok(savedKey = null))
        assertFalse(ok(sequence = listOf(2, 3)))
        assertFalse(ok(sequence = listOf(1, 1)))
        assertFalse(ok(requested = split.reversed()))
        assertFalse(ok(requested = listOf(PaymentAllocation(PaymentMethod.CASH, 1000L, 1000L))))
        assertFalse(ok(requested = listOf(
            split.first(), split.last().copy(receivedAmount = 1500L),
        )))
        assertFalse(ok(amount = 999L))
        assertFalse(ok(saved = emptyList(), sequence = emptyList()))
    }

    @Test fun invalidOrOverpaidTendersAreNeverAccepted() {
        assertFalse(ok(requested = listOf(
            PaymentAllocation(PaymentMethod.CARD, 400L, 400L),
            PaymentAllocation(PaymentMethod.CASH, 600L, 599L),
        )))
        assertFalse(ok(requested = split + PaymentAllocation(PaymentMethod.CARD, 1L, 1L)))
        assertFalse(ok(saved = listOf(split.first().copy(appliedAmount = -1L), split.last())))
    }

    @Test fun realSaleInsertMustVerifyDraftInsideTheSameTransaction() {
        val source = code("RegisterDatabase.kt")
        val save = source.substringAfter("    fun saveSale(").substringBefore("    fun listSales(")
        assertTrue(save.contains("PaymentDraftIntegrityV181.validAllocations("))
        assertTrue(save.contains("ExternalTenderLedgerV188.requireAbsent(this)"))
        assertTrue(save.contains("PaymentCommitDraftFenceV190.requireMatches("))
        assertTrue(save.contains("PaymentCommitDraftFenceV190.requireNoActiveDraftForLegacy(this)"))
        assertTrue(save.indexOf("runInTransactionWithResult") <
            save.indexOf("PaymentCommitDraftFenceV190.requireMatches("))
        assertTrue(save.indexOf("PaymentCommitDraftFenceV190.requireMatches(") <
            save.indexOf("val saleId = insertOrThrow("))
        // The existing confirmed key is checked FIRST, so a crash after sale
        // commit but before draft cleanup is still idempotently recoverable.
        assertTrue(save.indexOf("SaleCommitIdempotencySchema.find(this, normalizedCommitKey)") <
            save.indexOf("PaymentCommitDraftFenceV190.requireMatches("))
    }

    @Test fun pendingAndUnkeyedLegacyPathsCannotBypassTheDurableFence() {
        val policy = code("PaymentCommitDraftFenceV190.kt")
        assertTrue(policy.contains("check(db.inTransaction())"))
        assertTrue(policy.contains("payment_draft_allocations"))
        assertTrue(policy.contains("payment_draft_meta"))
        assertTrue(policy.contains("require(!active)"))
        assertTrue(policy.contains("require(!anyAllocations)"))
        assertFalse(policy.contains("CREATE TABLE"))
        assertFalse(policy.contains("insertOrThrow"))
    }
}
