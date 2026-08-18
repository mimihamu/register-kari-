package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V135SubtotalUc07Test {
    private fun product(id: String, price: Long, category: TaxCategory) = Product(
        id = id,
        name = id,
        unitPrice = price,
        taxCategory = category,
        displayOrder = 1,
    )

    @Test
    fun subtotalSnapshotHasDedicatedModeAndCalculatedTotal() {
        val items = listOf(
            CartItem(product("内税", 1_100, TaxCategory.INCLUDED_10), 1),
            CartItem(product("非課税", 500, TaxCategory.NON_TAXABLE), 2),
        )

        val snapshot = CustomerDisplaySnapshotFactory.subtotal(items, "つぐレジ")

        assertEquals(CustomerDisplayMode.SUBTOTAL, snapshot.mode)
        assertEquals(3, snapshot.numberOfProducts)
        assertEquals(2_100L, snapshot.subtotalAmount)
        assertEquals(2_100L, snapshot.totalAmount)
        assertEquals("小計をご確認ください", snapshot.message)
    }

    @Test
    fun paymentScreenShowsDiscountTaxableAmountNonTaxableAmountAndTotals() {
        val source = File("src/main/java/jp/co/tenposinfo/register/MainActivity.kt").readText()

        assertTrue(source.contains("yen(item.amountBeforeDiscount)"))
        assertTrue(source.contains("AmountRow(\"  値引\", \"-${'$'}{yen(item.discountAmount)}\")"))
        assertTrue(source.contains("AmountRow(\"商品計\", yen(items.sumOf { it.baseAmount }))"))
        assertTrue(source.contains("\"${'$'}{bucket.ratePercent}%対象\""))
        assertTrue(source.contains("\"${'$'}{yen(bucket.grossAmount)} / 税 ${'$'}{yen(bucket.taxAmount)}\""))
        assertTrue(source.contains("AmountRow(\"非課税\", yen(bucket.grossAmount))"))
        assertTrue(source.contains("AmountRow(\"合計\", yen(summary.grossAmount), emphasized = true)"))
    }

    @Test
    fun subtotalActionIsDisabledWhenEmptyAndPublishesBeforePaymentScreen() {
        val source = File("src/main/java/jp/co/tenposinfo/register/MainActivity.kt").readText()

        assertTrue(source.contains("\"小計／会計  ${'$'}{yen(summary.grossAmount)}\""))
        assertTrue(source.contains("cart.isNotEmpty(),"))
        val subtotalIndex = source.indexOf("CustomerDisplaySnapshotFactory.subtotal(")
        val paymentIndex = source.indexOf("screen = AppScreen.PAYMENT", startIndex = subtotalIndex)
        assertTrue(subtotalIndex >= 0)
        assertTrue(paymentIndex > subtotalIndex)
    }

    @Test
    fun guestCountAndHeldTicketCompatibilityRemainConnected() {
        val guestSource = File("src/main/java/jp/co/tenposinfo/register/SaleGuestCountV135.kt").readText()
        val heldSource = File("src/main/java/jp/co/tenposinfo/register/HeldTicketSafety.kt").readText()

        assertTrue(guestSource.contains("const val MIN = 1"))
        assertTrue(guestSource.contains("const val MAX = 999"))
        assertTrue(guestSource.contains("held_ticket_guest_count_v135"))
        assertTrue(heldSource.contains("restoreHeldGuestCountToPending"))
    }
}
