package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicTaxEngineTest {
    @Test
    fun excludedTwelvePercentIsCalculatedOncePerRate() {
        val items = listOf(
            CartItem(customProduct("A", 400, 12, included = false), 1),
            CartItem(customProduct("B", 600, 12, included = false), 1),
        )

        val summary = TaxEngine.calculate(items)

        assertEquals(1_000L, summary.netAmount)
        assertEquals(120L, summary.taxAmount)
        assertEquals(1_120L, summary.grossAmount)
        assertEquals(setOf("CUSTOM_12"), summary.buckets.single().sourceTaxKeys)
    }

    @Test
    fun includedTwelvePercentKeepsGrossAmount() {
        val summary = TaxEngine.calculate(
            listOf(CartItem(customProduct("A", 1_120, 12, included = true), 1)),
        )

        assertEquals(1_000L, summary.netAmount)
        assertEquals(120L, summary.taxAmount)
        assertEquals(1_120L, summary.grossAmount)
    }

    @Test(expected = IllegalStateException::class)
    fun sameRateIncludedAndExcludedCanBeBlocked() {
        val items = listOf(
            CartItem(customProduct("A", 1_120, 12, included = true), 1),
            CartItem(customProduct("B", 1_000, 12, included = false), 1),
        )
        TaxEngine.validateMixedTax(items, MixedTaxPolicy.BLOCK)
    }

    @Test
    fun arbitraryTaxRuleValidationAcceptsFutureRate() {
        val validated = DynamicTaxValidation.validateRule(
            DynamicTaxRule(
                key = "future_12",
                label = "12%外税",
                ratePercent = 12,
                mode = DynamicTaxMode.EXCLUDED,
                reduced = false,
                enabled = true,
                symbol = "外12",
                validFrom = "2027-04-01",
                validTo = "",
            ),
        )

        assertEquals("FUTURE_12", validated.key)
        assertEquals(12, validated.ratePercent)
        assertTrue(validated.taxable)
    }

    @Test
    fun legacyCategoryReplacementRefreshesDynamicSnapshot() {
        val original = customProduct("A", 500, 12, included = false)
        val changed = original.withLegacyTaxCategory(TaxCategory.INCLUDED_8)

        assertEquals("INCLUDED_8", changed.taxKey)
        assertEquals(8, changed.taxRatePercent)
        assertEquals("内※", changed.taxSymbol)
        assertTrue(changed.taxIncluded)
    }

    private fun customProduct(id: String, price: Long, rate: Int, included: Boolean): Product = Product(
        id = id,
        name = id,
        unitPrice = price,
        taxCategory = TaxCategory.EXCLUDED_10,
        displayOrder = 1,
        taxKey = "CUSTOM_12",
        taxLabel = "12%${if (included) "内税" else "外税"}",
        taxSymbol = if (included) "内12" else "外12",
        taxRatePercent = rate,
        taxIncluded = included,
        taxable = true,
        reducedTax = false,
    )
}
