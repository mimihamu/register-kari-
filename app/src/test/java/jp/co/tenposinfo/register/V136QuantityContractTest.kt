package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class V136QuantityContractTest {
    @Test
    fun acceptsWholeOneAndTwoDecimalQuantitiesExactly() {
        assertEquals("2", QuantityV136.parse("2").format())
        assertEquals("1.5", QuantityV136.parse("1.5").format())
        assertEquals("0.25", QuantityV136.parse("0.25").format())
        assertEquals(25L, QuantityV136.parse("0.25").hundredths)
    }

    @Test
    fun rejectsMoreThanTwoDecimalPlacesAndZero() {
        assertThrows(IllegalArgumentException::class.java) { QuantityV136.parse("1.234") }
        assertThrows(IllegalArgumentException::class.java) { QuantityV136.parse("0") }
    }

    @Test
    fun unitPriceMeansPricePerOnePointZeroZero() {
        assertEquals(375L, QuantityV136.parse("1.5").multiplyYen(250L))
        assertEquals(62L, QuantityV136.parse("0.25").multiplyYen(250L))
    }

    @Test
    fun cartLineUsesExactQuantityForAmountAndReceiptText() {
        val product = Product("P1", "量り売り", 250L, TaxCategory.INCLUDED_10, 1)
        val item = CartItem(
            product = product,
            quantity = 1,
            quantityHundredths = 150L,
        )
        assertEquals("1.5", item.quantityText)
        assertEquals(375L, item.amountBeforeDiscount)
        val rendered = ReceiptRenderer.render(
            ReceiptData(
                storeName = "店",
                registrationNumber = "",
                saleId = 1L,
                createdAt = 0L,
                operatorName = "担当",
                items = listOf(item),
                taxSummary = TaxEngine.calculate(listOf(item)),
                payments = emptyList(),
                changeAmount = 0L,
            ),
            ReceiptPaper.MM58,
        )
        assertEquals(true, rendered.contains("1.5 ×"))
    }
}

