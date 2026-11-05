package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.81: interrupted payments cannot be confused with another fractional cart/line. */
class V181PaymentDraftIntegrityTest {
    private val folder = File("src/main/java/jp/co/tenposinfo/register")
    private fun code(name: String) = File(folder, name).readText()

    private fun item(lineId: String = "line-a", hundredths: Long = 125L) = CartItem(
        product = Product("p1", "テスト商品", 600L, TaxCategory.INCLUDED_10, 1),
        quantity = 1,
        quantityHundredths = hundredths,
        lineId = lineId,
    )

    @Test fun fractionalAmountsWithSameLegacyIntegerAreDifferentDrafts() {
        val oneQuarter = listOf(item(hundredths = 125L))
        val oneHalf = listOf(item(hundredths = 150L))
        assertEquals(oneQuarter.single().quantity, oneHalf.single().quantity)
        assertNotEquals(PaymentDraftFingerprint.of(oneQuarter), PaymentDraftFingerprint.of(oneHalf))
        assertEquals(PaymentDraftFingerprint.legacyV180Of(oneQuarter), PaymentDraftFingerprint.legacyV180Of(oneHalf))
    }

    @Test fun replacingALineWithTheSameProductNeverReusesTheOldKey() {
        val original = listOf(item(lineId = "line-1"))
        val replacement = listOf(item(lineId = "line-2"))
        assertNotEquals(PaymentDraftFingerprint.of(original), PaymentDraftFingerprint.of(replacement))
        assertEquals(PaymentDraftFingerprint.legacyV180Of(original), PaymentDraftFingerprint.legacyV180Of(replacement))
        assertEquals(PaymentDraftFingerprint.of(original), PaymentDraftFingerprint.of(original.map { it.copy() }))
    }

    @Test fun validSplitAndCashChangeCanBeRestored() {
        val allocations = listOf(
            PaymentAllocation(PaymentMethod.CARD, 400L, 400L),
            PaymentAllocation(PaymentMethod.CASH, 600L, 1500L),
        )
        assertTrue(PaymentDraftIntegrityV181.validAllocations(allocations, 1000L))
        assertTrue(PaymentDraftIntegrityV181.validAllocations(emptyList(), 0L))
        assertFalse(PaymentDraftIntegrityV181.validAllocations(allocations, 999L))
        assertFalse(PaymentDraftIntegrityV181.validAllocations(emptyList(), -1L))
        assertFalse(PaymentDraftIntegrityV181.validAllocations(
            listOf(PaymentAllocation(PaymentMethod.CASH, 1L, 1L)), 0L,
        ))
    }

    @Test fun malformedOrOverflowingAllocationsCannotAppearPaidInFull() {
        val overflow = listOf(
            PaymentAllocation(PaymentMethod.CASH, Long.MAX_VALUE, Long.MAX_VALUE),
            PaymentAllocation(PaymentMethod.CASH, 1L, 1L),
        )
        assertFalse(PaymentDraftIntegrityV181.validAllocations(overflow, Long.MAX_VALUE))
        val changeOverflow = listOf(
            PaymentAllocation(PaymentMethod.CASH, 1L, Long.MAX_VALUE),
            PaymentAllocation(PaymentMethod.CASH, 1L, Long.MAX_VALUE),
        )
        assertFalse(PaymentDraftIntegrityV181.validAllocations(changeOverflow, 2L))
        assertFalse(PaymentDraftIntegrityV181.validAllocations(
            listOf(PaymentAllocation(PaymentMethod.CARD, 1001L, 1001L)), 1000L,
        ))
    }

    @Test fun corruptedLegacyOrOverpaidDraftIsBlockedWithoutErasingEvidence() {
        val store = code("PaymentDraftStore.kt")
        val main = code("MainActivity.kt")
        assertTrue(store.contains("item.quantityHundredths"))
        assertTrue(store.contains("item.lineId"))
        assertTrue(store.contains("PaymentDraftFingerprint.legacyV180Of(items)"))
        assertTrue(store.contains("allocations.isNotEmpty()"))
        assertTrue(store.contains("PaymentDraftIntegrityV181.REVIEW_MESSAGE"))
        assertTrue(store.contains("if (loaded.integrityIssue != null) return loaded"))
        assertTrue(store.contains("PaymentDraftIntegrityV181.validAllocations("))
        assertTrue(store.contains("PaymentDraftLoadResult(\n                PaymentState(), false, metadata.second, metadata.third,"))
        assertTrue(main.contains("paymentIntegrityWarningV181 = draft.integrityIssue"))
        assertTrue(main.contains("return@SalesScreen"))
        assertTrue(main.contains("RegisterModalV154("))
        assertTrue(main.contains("Text(paymentIntegrityWarningV181.orEmpty())"))
        assertTrue(main.contains("paymentDraftStore.loadOrCreate(cart.toList())"))
        assertTrue(main.contains("saleCommitGuard.tryBegin()"))
    }
}
