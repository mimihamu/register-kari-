package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class V014PaymentDraftTest {
    private fun item(
        quantity: Int = 1,
        unitPrice: Long = 600L,
        discount: Long = 0L,
        taxKey: String = "INCLUDED_10",
    ): CartItem {
        val product = Product(
            id = "beer",
            name = "生ビール",
            unitPrice = unitPrice,
            taxCategory = TaxCategory.INCLUDED_10,
            displayOrder = 1,
            taxKey = taxKey,
        )
        return CartItem(
            product = product,
            quantity = quantity,
            unitPrice = unitPrice,
            discountAmount = discount,
        )
    }

    @Test
    fun sameCartProducesStableFingerprint() {
        val first = listOf(item(), item(quantity = 2, unitPrice = 300L))
        val second = first.map { it.copy() }

        assertEquals(
            PaymentDraftFingerprint.of(first),
            PaymentDraftFingerprint.of(second),
        )
    }

    @Test
    fun quantityPriceDiscountAndTaxChangesInvalidateDraft() {
        val base = PaymentDraftFingerprint.of(listOf(item()))

        assertNotEquals(base, PaymentDraftFingerprint.of(listOf(item(quantity = 2))))
        assertNotEquals(base, PaymentDraftFingerprint.of(listOf(item(unitPrice = 650L))))
        assertNotEquals(base, PaymentDraftFingerprint.of(listOf(item(discount = 50L))))
        assertNotEquals(base, PaymentDraftFingerprint.of(listOf(item(taxKey = "EXCLUDED_10"))))
    }

    @Test
    fun lineOrderIsPartOfFingerprint() {
        val first = item(unitPrice = 600L)
        val second = CartItem(
            product = first.product.copy(id = "food", name = "枝豆", unitPrice = 420L, displayOrder = 2),
            quantity = 1,
            unitPrice = 420L,
        )

        assertNotEquals(
            PaymentDraftFingerprint.of(listOf(first, second)),
            PaymentDraftFingerprint.of(listOf(second, first)),
        )
    }

    @Test
    fun restoredPaymentStateRecalculatesPaidRemainingAndChange() {
        val restored = PaymentState(
            listOf(
                PaymentAllocation(PaymentMethod.CARD, appliedAmount = 400L, receivedAmount = 400L),
                PaymentAllocation(PaymentMethod.CASH, appliedAmount = 200L, receivedAmount = 1_000L),
            ),
        )

        assertEquals(600L, restored.paidAmount)
        assertEquals(0L, restored.remaining(600L))
        assertEquals(800L, restored.changeAmount)
    }

    @Test
    fun paymentCommitKeysAreValidAndUnique() {
        val first = PaymentCommitKey.newKey()
        val second = PaymentCommitKey.newKey()

        assertTrue(PaymentCommitKey.isValid(first))
        assertTrue(PaymentCommitKey.isValid(second))
        assertNotEquals(first, second)
    }

    @Test
    fun existingCommitMustMatchCartAndTotal() {
        val existing = SaleCommitIdempotencySchema.ExistingCommit(
            saleId = 42L,
            cartFingerprint = "fingerprint-a",
            totalAmount = 1_000L,
        )

        SaleCommitIdempotencySchema.requireCompatible(
            existing = existing,
            cartFingerprint = "fingerprint-a",
            totalAmount = 1_000L,
        )

        assertThrows(IllegalArgumentException::class.java) {
            SaleCommitIdempotencySchema.requireCompatible(
                existing = existing,
                cartFingerprint = "fingerprint-b",
                totalAmount = 1_000L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SaleCommitIdempotencySchema.requireCompatible(
                existing = existing,
                cartFingerprint = "fingerprint-a",
                totalAmount = 1_001L,
            )
        }
    }
}
