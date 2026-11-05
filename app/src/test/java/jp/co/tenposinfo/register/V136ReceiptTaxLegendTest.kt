package jp.co.tenposinfo.register

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V136ReceiptTaxLegendTest {
    @Test
    fun legendsAppearOnlyForSymbolsActuallyUsedByItems() {
        val normal = item("N", TaxCategory.INCLUDED_10)
        val normalText = ReceiptRenderer.render(data(listOf(normal)), ReceiptPaper.MM58)
        assertTrue(normalText.contains("内/外は内税・外税区分です"))
        assertFalse(normalText.contains("※は軽減税率対象商品です"))
        assertFalse(normalText.contains("非は非課税商品です"))

        val reduced = item("R", TaxCategory.EXCLUDED_8)
        val reducedText = ReceiptRenderer.render(data(listOf(reduced)), ReceiptPaper.MM58)
        assertTrue(reducedText.contains("※は軽減税率対象商品です"))
        assertTrue(reducedText.contains("内/外は内税・外税区分です"))

        val exempt = item("E", TaxCategory.NON_TAXABLE)
        val exemptText = ReceiptRenderer.render(data(listOf(exempt)), ReceiptPaper.MM58)
        assertTrue(exemptText.contains("非は非課税商品です"))
        assertFalse(exemptText.contains("※は軽減税率対象商品です"))
        assertFalse(exemptText.contains("内/外は内税・外税区分です"))
    }

    private fun item(id: String, category: TaxCategory) = CartItem(
        product = Product(id, "商品$id", 100L, category, 1),
        quantity = 1,
    )

    private fun data(items: List<CartItem>) = ReceiptData(
        storeName = "店",
        registrationNumber = "",
        saleId = 1L,
        createdAt = 0L,
        operatorName = "担当",
        items = items,
        taxSummary = TaxEngine.calculate(items),
        payments = emptyList(),
        changeAmount = 0L,
    )
}
