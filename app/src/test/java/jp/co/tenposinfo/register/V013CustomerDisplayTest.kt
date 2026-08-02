package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V013CustomerDisplayTest {
    private fun product(id: String, name: String, price: Long) = Product(
        id = id,
        name = name,
        unitPrice = price,
        taxCategory = TaxCategory.INCLUDED_10,
        displayOrder = id.hashCode().absoluteValue.coerceAtLeast(1),
    )

    @Test
    fun salesSnapshotContainsItemsQuantityAndTotal() {
        val beer = product("beer", "生ビール", 550)
        val food = product("food", "枝豆", 330)
        val items = listOf(
            CartItem(beer, quantity = 2),
            CartItem(food, quantity = 1),
        )

        val snapshot = CustomerDisplaySnapshotFactory.sales(
            items = items,
            storeName = "テンポス食堂",
            latestProductId = "food",
        )

        assertEquals(CustomerDisplayMode.SALES, snapshot.mode)
        assertEquals(3, snapshot.numberOfProducts)
        assertEquals(1_430L, snapshot.totalAmount)
        assertEquals(2, snapshot.orderItems.size)
        assertFalse(snapshot.orderItems[0].latest)
        assertTrue(snapshot.orderItems[1].latest)
    }

    @Test
    fun emptyCartReturnsStandby() {
        val snapshot = CustomerDisplaySnapshotFactory.sales(emptyList(), "テンポス食堂")
        assertEquals(CustomerDisplayMode.STANDBY, snapshot.mode)
        assertEquals(0L, snapshot.totalAmount)
        assertTrue(snapshot.orderItems.isEmpty())
    }

    @Test
    fun completedSaleShowsPaymentAndChange() {
        val item = CartItem(product("course", "宴会コース", 4_000), quantity = 2)
        val payments = listOf(PaymentAllocation(PaymentMethod.CASH, appliedAmount = 8_000, receivedAmount = 10_000))
        val detail = SaleDetailRecord(
            summary = SaleSummaryRecord(
                id = 12L,
                operatorName = "福島",
                paymentLabel = "現金",
                totalAmount = 8_000,
                taxAmount = 727,
                changeAmount = 2_000,
                createdAt = 1L,
                printCount = 0,
            ),
            items = listOf(item),
            payments = payments,
            taxSummary = TaxEngine.calculate(listOf(item)),
        )

        val snapshot = CustomerDisplaySnapshotFactory.complete(detail, "テンポス食堂")

        assertEquals(CustomerDisplayMode.COMPLETE, snapshot.mode)
        assertEquals("12", snapshot.transactionId)
        assertEquals("現金", snapshot.paymentMethod)
        assertEquals(10_000L, snapshot.receivedAmount)
        assertEquals(2_000L, snapshot.changeAmount)
        assertEquals(0L, snapshot.shortageAmount)
    }

    @Test
    fun websocketHandshakeMatchesRfc6455Example() {
        assertEquals(
            "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
            CustomerDisplayWebSocketHandshake.accept("dGhlIHNhbXBsZSBub25jZQ=="),
        )
    }

    private val Int.absoluteValue: Int get() = if (this == Int.MIN_VALUE) Int.MAX_VALUE else kotlin.math.abs(this)
}
