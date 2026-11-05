package jp.co.tenposinfo.register

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class V137CustomerDisplayCanonicalModeTest {
    private val product = Product(
        id = "drink",
        name = "飲み物",
        unitPrice = 300L,
        taxCategory = TaxCategory.INCLUDED_10,
        displayOrder = 1,
    )

    @Test
    fun cartAndSubtotalExposeFormalModesWithLegacyAliases() {
        val items = listOf(CartItem(product = product, quantity = 1, lineId = "line-1"))
        val cart = JSONObject(CustomerDisplaySnapshotFactory.sales(items, "店").toJson())
        val subtotal = JSONObject(CustomerDisplaySnapshotFactory.subtotal(items, "店").toJson())
        assertEquals("SALES", cart.getString("mode"))
        assertEquals("CART", cart.getString("canonicalMode"))
        assertEquals("SUBTOTAL", subtotal.getString("canonicalMode"))
    }

    @Test
    fun cashTenderNeverExposesProvisionalChange() {
        val items = listOf(CartItem(product = product, quantity = 1))
        val payment = PaymentState(listOf(PaymentAllocation(PaymentMethod.CASH, 300L, 1000L)))
        val provisional = CustomerDisplaySnapshotFactory.accounting(items, payment, "店")
        val wire = JSONObject(provisional.toJson())
        assertEquals("ACCOUNTING", wire.getString("mode"))
        assertEquals("CASH_RECEIVED", wire.getString("canonicalMode"))
        assertEquals(0L, wire.getLong("changeAmount"))
        assertEquals(1000L, wire.getLong("receivedAmount"))
        assertEquals(0L, wire.getLong("remainingAmount"))
    }

    @Test
    fun changeVisibleOnlyFromPersistedCompleteSaleThenThankYou() {
        val items = listOf(CartItem(product = product, quantity = 1))
        val detail = SaleDetailRecord(
            summary = SaleSummaryRecord(
                id = 99L,
                operatorName = "test",
                paymentLabel = "現金",
                totalAmount = 300L,
                taxAmount = 27L,
                changeAmount = 700L,
                createdAt = 1L,
                printCount = 0,
            ),
            items = items,
            payments = listOf(PaymentAllocation(PaymentMethod.CASH, 300L, 1000L)),
            taxSummary = TaxEngine.calculate(items),
        )
        val change = CustomerDisplaySnapshotFactory.complete(detail, "店")
        assertEquals("CHANGE", JSONObject(change.toJson()).getString("canonicalMode"))
        assertEquals(700L, change.changeAmount)
        val thanks = change.copy(changeAmount = 0L, message = "ありがとうございました")
        assertEquals("COMPLETE", JSONObject(thanks.toJson()).getString("canonicalMode"))
        assertEquals(0L, thanks.changeAmount)
    }
}
