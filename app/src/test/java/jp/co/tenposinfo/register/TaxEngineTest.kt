package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaxEngineTest {
    @Test
    fun included10_calculatesTaxFromGross() {
        val item = CartItem(
            Product("A", "内税商品", 1100, TaxCategory.INCLUDED_10, 1),
            quantity = 1,
        )

        val summary = TaxEngine.calculate(listOf(item))

        assertEquals(1000, summary.netAmount)
        assertEquals(100, summary.taxAmount)
        assertEquals(1100, summary.grossAmount)
    }

    @Test
    fun excluded8_addsTaxToNet() {
        val item = CartItem(
            Product("B", "軽減外税", 1000, TaxCategory.EXCLUDED_8, 1),
            quantity = 2,
        )

        val summary = TaxEngine.calculate(listOf(item))

        assertEquals(2000, summary.netAmount)
        assertEquals(160, summary.taxAmount)
        assertEquals(2160, summary.grossAmount)
    }

    @Test
    fun taxIsRoundedOncePerCategory() {
        val products = listOf(
            CartItem(Product("A", "商品A", 157, TaxCategory.EXCLUDED_10, 1), 5),
            CartItem(Product("B", "商品B", 157, TaxCategory.EXCLUDED_10, 2), 5),
        )

        val summary = TaxEngine.calculate(products)

        assertEquals(1570, summary.netAmount)
        assertEquals(157, summary.taxAmount)
        assertEquals(1727, summary.grossAmount)
    }

    @Test
    fun mixedTaxPolicyWarn_reportsMixing() {
        val items = listOf(
            CartItem(Product("A", "内税", 110, TaxCategory.INCLUDED_10, 1), 1),
            CartItem(Product("B", "外税", 100, TaxCategory.EXCLUDED_10, 2), 1),
        )

        val result = TaxEngine.validateMixedTax(items, MixedTaxPolicy.WARN)

        assertTrue(result.hasMixedTax)
        assertTrue(result.message?.isNotBlank() == true)
    }
}
