package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V136MixedTaxReceiptAggregationTest {
    @Test
    fun sameRateIncludedAndExcludedKeepItemSymbolsButUseOneTaxBucket() {
        val included = CartItem(Product("I10", "内税商品", 1_100L, TaxCategory.INCLUDED_10, 1), 1)
        val excluded = CartItem(Product("E10", "外税商品", 1_000L, TaxCategory.EXCLUDED_10, 2), 1)
        val items = listOf(included, excluded)
        val summary = TaxEngine.calculate(items)

        assertEquals(1, summary.buckets.count { it.taxable && it.ratePercent == 10 })
        val rendered = ReceiptRenderer.render(
            ReceiptData(
                storeName = "店",
                registrationNumber = "",
                saleId = 1L,
                createdAt = 0L,
                operatorName = "担当",
                items = items,
                taxSummary = summary,
                payments = emptyList(),
                changeAmount = 0L,
            ),
            ReceiptPaper.MM58,
        )
        assertTrue(rendered.contains("内税商品 [内]"))
        assertTrue(rendered.contains("外税商品 [外]"))
        assertEquals(1, Regex("10%対象額").findAll(rendered).count())
    }
}
