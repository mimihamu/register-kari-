package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class V136FractionalSalesEntryTest {
    @Test
    fun salesScreenUsesExactQuantityCallbacksAndDecimalKeypad() {
        val source = File("src/main/java/jp/co/tenposinfo/register/MainActivity.kt").readText()
        val sales = source.substringAfter("private fun SalesScreen(").substringBefore("@Composable\nprivate fun LineEditScreen(")
        assertTrue(sales.contains("onAddProduct: (Product, Long) -> Unit"))
        assertTrue(sales.contains("pendingQuantityHundredths"))
        assertTrue(sales.contains("onDecimal ="))
        assertTrue(sales.contains("QuantityV136.parse(numericInput).hundredths"))
        assertTrue(sales.contains("product.quantityMode.requireAllowed"))
        assertTrue(sales.contains("item.quantityText"))
        assertTrue(sales.contains("correction.cancelledQuantityText"))
    }

    @Test
    fun lineEditorPreservesFractionalQuantityAndUsesModeAwareDecimalInput() {
        val source = File("src/main/java/jp/co/tenposinfo/register/MainActivity.kt").readText()
        val edit = source.substringAfter("private fun LineEditScreen(").substringBefore("@Composable\nprivate fun DiscountScreen(")
        assertTrue(edit.contains("item.quantityText"))
        assertTrue(edit.contains("QuantityV136.parse(quantity).hundredths"))
        assertTrue(edit.contains("allowDecimal = item.product.quantityMode == QuantityMode.DECIMAL"))
        assertTrue(edit.contains("quantityHundredths = effectiveQuantityHundredths"))
    }

    @Test
    fun hostUsesExactCorrectionForDecreaseAndCancellation() {
        val source = File("src/main/java/jp/co/tenposinfo/register/MainActivity.kt").readText()
        val host = source.substringBefore("@Composable\nprivate fun Header(")
        assertTrue(host.contains("fun applyCartCorrectionHundredths("))
        assertTrue(host.contains("current.quantityHundredths - quantityHundredths"))
        assertTrue(host.contains("cart[index].quantityHundredths"))
        assertTrue(host.contains("edited.quantityHundredths < original.quantityHundredths"))
    }
}
