package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V015SalesProfileSnapshotTest {
    private val lunch = SalesProfileRecord(1, "LUNCH", "ランチ", true, 660, 900, 20, false)
    private val dinner = SalesProfileRecord(2, "DINNER", "ディナー", true, 1020, 120, 30, false)
    private val default = SalesProfileRecord(3, "DEFAULT", "通常営業", true, 0, 0, 0, true)

    @Test
    fun selectsLunchDinnerAndOvernightDinnerByConfiguredTime() {
        val profiles = listOf(lunch, dinner, default)

        assertEquals("LUNCH", SalesProfileSelector.select(profiles, 12 * 60)?.code)
        assertEquals("DINNER", SalesProfileSelector.select(profiles, 18 * 60)?.code)
        assertEquals("DINNER", SalesProfileSelector.select(profiles, 60)?.code)
        assertEquals("DEFAULT", SalesProfileSelector.select(profiles, 15 * 60 + 30)?.code)
        assertTrue(SalesProfileSelector.matches(dinner, 23 * 60))
        assertTrue(SalesProfileSelector.matches(dinner, 90))
        assertFalse(SalesProfileSelector.matches(dinner, 12 * 60))
    }

    @Test
    fun cartAndHeldSnapshotKeepLunchPriceAndIncludedTaxAfterProfileChanges() {
        val lunchProduct = Product(
            id = "P1",
            name = "定食",
            unitPrice = 1_100,
            taxCategory = TaxCategory.INCLUDED_10,
            displayOrder = 1,
        )
        val snapshot = CartItem(lunchProduct, quantity = 2)
        val dinnerProduct = lunchProduct.copy(
            unitPrice = 1_000,
            taxCategory = TaxCategory.EXCLUDED_10,
            taxKey = TaxCategory.EXCLUDED_10.name,
            taxLabel = TaxCategory.EXCLUDED_10.displayName,
            taxSymbol = TaxCategory.EXCLUDED_10.symbol,
            taxRatePercent = 10,
            taxIncluded = false,
            taxable = true,
        )

        assertEquals(1_100L, snapshot.unitPrice)
        assertTrue(snapshot.product.taxIncluded)
        assertEquals("内", snapshot.product.taxSymbol)
        assertEquals(2_200L, TaxEngine.calculate(listOf(snapshot)).grossAmount)
        assertEquals(1_000L, dinnerProduct.unitPrice)
        assertFalse(dinnerProduct.taxIncluded)
    }
}
