package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Test

class V013CustomerDisplayAccountingTest {
    private val product = Product(
        id = "drink",
        name = "ウーロン茶",
        unitPrice = 300,
        taxCategory = TaxCategory.INCLUDED_10,
        displayOrder = 1,
    )

    @Test
    fun accountingSnapshotShowsPaidRemainingAndMethods() {
        val items = listOf(CartItem(product = product, quantity = 2))
        val state = PaymentEngine.addPayment(
            state = PaymentState(),
            total = 600,
            method = PaymentMethod.CASH,
            inputAmount = 500,
        )

        val snapshot = CustomerDisplaySnapshotFactory.accounting(items, state, "テスト店")

        assertEquals(CustomerDisplayMode.ACCOUNTING, snapshot.mode)
        assertEquals(600L, snapshot.totalAmount)
        assertEquals(500L, snapshot.receivedAmount)
        assertEquals(100L, snapshot.shortageAmount)
        assertEquals(0L, snapshot.changeAmount)
        assertEquals("現金", snapshot.paymentMethod)
        assertEquals(2, snapshot.numberOfProducts)
    }

    @Test
    fun accountingSnapshotShowsChangeAfterOverpayment() {
        val items = listOf(CartItem(product = product, quantity = 1))
        val state = PaymentEngine.addPayment(
            state = PaymentState(),
            total = 300,
            method = PaymentMethod.CASH,
            inputAmount = 500,
        )

        val snapshot = CustomerDisplaySnapshotFactory.accounting(items, state, "テスト店")

        assertEquals(0L, snapshot.shortageAmount)
        assertEquals(200L, snapshot.changeAmount)
        assertEquals(500L, snapshot.receivedAmount)
    }
}
