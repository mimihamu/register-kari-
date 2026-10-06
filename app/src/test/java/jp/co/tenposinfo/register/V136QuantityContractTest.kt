package jp.co.tenposinfo.register

import java.io.File
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
    fun exactQuantityIsPersistedAcrossCartHeldAndSalePaths() {
        val database = File("src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt").readText()
        val held = File("src/main/java/jp/co/tenposinfo/register/HeldTicketSafety.kt").readText()

        assertEquals(true, database.contains("put(\"quantity_hundredths\", item.quantityHundredths)"))
        assertEquals(true, database.contains("quantityHundredths = cursor.getLong(7)"))
        assertEquals(true, database.contains("quantityHundredths = getLong(9)"))
        assertEquals(true, database.contains("put(\"quantity_hundredths\", quantityHundredths)"))
        assertEquals(true, held.contains("put(\"quantity_hundredths\", quantityHundredths)"))
    }

    @Test
    fun integerCartEditsResetExactQuantityToWholeUnits() {
        val main = File("src/main/java/jp/co/tenposinfo/register/MainActivity.kt").readText()
        assertEquals(true, main.contains("quantityHundredths = Math.multiplyExact(updatedQuantity.toLong(), QuantityV136.SCALE)"))
        assertEquals(true, main.contains("quantityHundredths = Math.multiplyExact(quantity.toLong(), QuantityV136.SCALE)"))
    }

    @Test
    fun customerDisplayJsonContractCarriesExactQuantityFields() {
        val source = File("src/main/java/jp/co/tenposinfo/register/CustomerDisplayProtocol.kt").readText()
        assertEquals(true, source.contains("put(\"quantityHundredths\", item.quantityHundredths)"))
        assertEquals(true, source.contains("put(\"quantityText\", item.quantityText)"))
    }

    @Test
    fun reversalPersistenceCarriesExactQuantityColumns() {
        val source = File("src/main/java/jp/co/tenposinfo/register/AdvancedOperationsStore.kt").readText()
        assertEquals(true, source.contains("original_quantity_hundredths"))
        assertEquals(true, source.contains("return_quantity_hundredths"))
        assertEquals(true, source.contains("put(\"original_quantity_hundredths\", line.originalQuantityHundredths)"))
        assertEquals(true, source.contains("put(\"return_quantity_hundredths\", item.quantityHundredths)"))
        assertEquals(true, source.contains("returnedQuantityHundredths = cursor.getLong(17)"))
    }

    @Test
    fun reversalReceiptUsesExactQuantityText() {
        val source = File("src/main/java/jp/co/tenposinfo/register/OperationDocuments.kt").readText()
        assertEquals(true, source.contains("item.quantityText"))
        assertEquals(false, source.contains("-\${item.quantity} ×"))
    }

    @Test
    fun productionReversalStoreLoadsAndWritesExactQuantity() {
        val source = File("src/main/java/jp/co/tenposinfo/register/OperationsStore.kt").readText()
        assertEquals(true, source.contains("COALESCE(si.quantity_hundredths, si.quantity * 100)"))
        assertEquals(true, source.contains("returnedQuantityHundredths = cursor.getLong(18)"))
        assertEquals(true, source.contains("put(\"original_quantity_hundredths\", line.originalQuantityHundredths)"))
        assertEquals(true, source.contains("put(\"return_quantity_hundredths\", item.quantityHundredths)"))
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

