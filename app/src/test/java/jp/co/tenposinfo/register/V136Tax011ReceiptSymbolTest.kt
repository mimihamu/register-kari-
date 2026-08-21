package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V136Tax011ReceiptSymbolTest {
    @Test
    fun canonicalFiveSymbolsComeFromSnapshotAttributes() {
        assertEquals("内", ReceiptTaxSymbolV136.fromSnapshot(taxable = true, taxIncluded = true, reducedTax = false))
        assertEquals("外", ReceiptTaxSymbolV136.fromSnapshot(taxable = true, taxIncluded = false, reducedTax = false))
        assertEquals("内※", ReceiptTaxSymbolV136.fromSnapshot(taxable = true, taxIncluded = true, reducedTax = true))
        assertEquals("外※", ReceiptTaxSymbolV136.fromSnapshot(taxable = true, taxIncluded = false, reducedTax = true))
        assertEquals("非", ReceiptTaxSymbolV136.fromSnapshot(taxable = false, taxIncluded = false, reducedTax = false))
    }

    @Test
    fun eightPercentDoesNotImplicitlyMeanReducedRate() {
        val product = product(
            ratePercent = 8,
            included = true,
            reduced = false,
            configuredSymbol = "CUSTOM",
        )

        assertEquals("内", ReceiptTaxSymbolV136.fromProduct(product))
    }

    @Test
    fun nonTaxableAlwaysUsesNonTaxableSymbol() {
        assertEquals(
            "非",
            ReceiptTaxSymbolV136.fromSnapshot(
                taxable = false,
                taxIncluded = true,
                reducedTax = true,
            ),
        )
    }

    @Test
    fun rendererIgnoresConfigurableMasterSymbolAndPrintsCanonicalSnapshotSymbol() {
        val product = product(
            ratePercent = 8,
            included = false,
            reduced = true,
            configuredSymbol = "CUSTOM",
        )
        val item = CartItem(product = product, quantity = 1)
        val data = ReceiptData(
            storeName = "テスト店舗",
            registrationNumber = "",
            saleId = 136,
            createdAt = 0,
            operatorName = "担当",
            items = listOf(item),
            taxSummary = TaxEngine.calculate(listOf(item)),
            payments = emptyList(),
            changeAmount = 0,
        )

        val rendered = ReceiptRenderer.render(data, ReceiptPaper.MM58)

        assertTrue(rendered.contains("商品 [外※]"))
        assertFalse(rendered.contains("CUSTOM"))
    }

    @Test
    fun changingConfiguredSymbolAfterSaleAttributesDoesNotChangeReceiptSymbol() {
        val original = product(
            ratePercent = 10,
            included = false,
            reduced = false,
            configuredSymbol = "旧記号",
        )
        val masterEdited = original.copy(taxSymbol = "新記号")

        assertEquals("外", ReceiptTaxSymbolV136.fromProduct(original))
        assertEquals("外", ReceiptTaxSymbolV136.fromProduct(masterEdited))
    }

    private fun product(
        ratePercent: Int,
        included: Boolean,
        reduced: Boolean,
        configuredSymbol: String,
    ): Product = Product(
        id = "P1",
        name = "商品",
        unitPrice = 1_000,
        taxCategory = TaxCategory.EXCLUDED_10,
        displayOrder = 1,
        taxKey = "CUSTOM",
        taxLabel = "カスタム税",
        taxSymbol = configuredSymbol,
        taxRatePercent = ratePercent,
        taxIncluded = included,
        taxable = true,
        reducedTax = reduced,
    )
}
