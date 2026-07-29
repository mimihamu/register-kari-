package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscountPaymentEngineTest {
    private val included = Product("A", "内税商品", 1000, TaxCategory.INCLUDED_10, 1)
    private val excluded = Product("B", "外税商品", 1000, TaxCategory.EXCLUDED_10, 2)

    @Test
    fun itemPercentDiscount_usesBasisPoints() {
        val item = CartItem(included, quantity = 2)

        val discounted = DiscountEngine.applyToItem(item, DiscountType.PERCENT, 1_250)

        assertEquals(250, discounted.discountAmount)
        assertEquals(1750, discounted.baseAmount)
    }

    @Test
    fun transactionFixedDiscount_isAllocatedWithoutLosingRemainder() {
        val items = listOf(
            CartItem(included, quantity = 1),
            CartItem(excluded, quantity = 1),
        )

        val discounted = DiscountEngine.applyToTransaction(items, DiscountType.FIXED, 101)

        assertEquals(101, discounted.sumOf { it.discountAmount })
        assertEquals(1899, discounted.sumOf { it.baseAmount })
    }

    @Test
    fun mixedTax_isUnifiedByRate() {
        val items = listOf(
            CartItem(Product("I", "内税", 110, TaxCategory.INCLUDED_10, 1), 1),
            CartItem(Product("E", "外税", 100, TaxCategory.EXCLUDED_10, 2), 1),
        )

        val summary = TaxEngine.calculate(items)

        assertEquals(200, summary.netAmount)
        assertEquals(20, summary.taxAmount)
        assertEquals(220, summary.grossAmount)
        assertTrue(summary.buckets.single().sourceCategories.size == 2)
    }

    @Test
    fun splitPayment_calculatesRemainingAndCashChange() {
        var state = PaymentState()
        state = PaymentEngine.addPayment(state, 1000, PaymentMethod.CARD, 400)
        state = PaymentEngine.addPayment(state, 1000, PaymentMethod.CASH, 1000)

        assertEquals(1000, state.paidAmount)
        assertEquals(0, state.remaining(1000))
        assertEquals(400, state.changeAmount)
        assertEquals(2, state.allocations.size)
    }

    @Test
    fun paymentCancellation_restoresRemainingAmount() {
        var state = PaymentState()
        state = PaymentEngine.addPayment(state, 1000, PaymentMethod.GIFT_CERTIFICATE, 300)
        state = PaymentEngine.addPayment(state, 1000, PaymentMethod.CARD, 700)

        state = PaymentEngine.removeAt(state, 0)

        assertEquals(700, state.paidAmount)
        assertEquals(300, state.remaining(1000))
    }
}
