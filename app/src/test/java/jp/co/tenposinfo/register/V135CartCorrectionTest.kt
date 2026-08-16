package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V135CartCorrectionTest {
    private val product = Product(
        id = "P-COR",
        name = "訂正テスト",
        unitPrice = 100L,
        taxCategory = TaxCategory.INCLUDED_10,
        displayOrder = 1,
    )

    @Test
    fun lastLineCorrectionRemovesActiveRowAndLeavesLinkedHistory() {
        val item = CartItem(product = product, quantity = 2, lineId = "line-last")
        val result = CartCorrectionPolicyV135.apply(
            items = listOf(item),
            targetIndex = 0,
            cancelQuantity = 2,
            correctionType = CartCorrectionTypeV135.LAST_LINE,
            operatorName = "担当A",
            createdAt = 100L,
        )

        assertTrue(result.items.isEmpty())
        assertEquals("line-last", result.record.lineId)
        assertEquals(2, result.record.cancelledQuantity)
        assertEquals(2, result.record.quantityBefore)
        assertEquals(0, result.record.quantityAfter)
        assertEquals(200L, result.record.cancelledAmount)
    }

    @Test
    fun selectedLineSupportsPartialCancellationAndAllocatesDiscount() {
        val item = CartItem(
            product = product,
            quantity = 3,
            discountAmount = 30L,
            lineId = "line-partial",
        )
        val result = CartCorrectionPolicyV135.apply(
            items = listOf(item),
            targetIndex = 0,
            cancelQuantity = 1,
            correctionType = CartCorrectionTypeV135.SELECTED_LINE,
            operatorName = "担当B",
            createdAt = 200L,
        )

        assertEquals(1, result.items.size)
        assertEquals(2, result.items.single().quantity)
        assertEquals(20L, result.items.single().discountAmount)
        assertEquals("line-partial", result.items.single().lineId)
        assertEquals(10L, result.record.cancelledDiscountAmount)
        assertEquals(90L, result.record.cancelledAmount)
        assertEquals(1, result.record.cancelledQuantity)
        assertEquals(2, result.record.quantityAfter)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cancellationCannotExceedCurrentQuantity() {
        CartCorrectionPolicyV135.apply(
            items = listOf(CartItem(product = product, quantity = 2, lineId = "line-limit")),
            targetIndex = 0,
            cancelQuantity = 3,
            correctionType = CartCorrectionTypeV135.SELECTED_LINE,
            operatorName = "担当C",
            createdAt = 300L,
        )
    }

    @Test
    fun salesScreenKeepsCor001PriorityAndProvidesSeparateSelectedLineCancellation() {
        val source = File("src/main/java/jp/co/tenposinfo/register/MainActivity.kt").readText()
        val salesScreen = source
            .substringAfter("private fun SalesScreen(")
            .substringBefore("@Composable\nprivate fun LineEditScreen(")

        assertTrue(salesScreen.contains("if (NumericCorrectionPolicyV135.shouldClearInput(numericInput))"))
        assertTrue(salesScreen.contains("onRemove()"))
        assertTrue(salesScreen.contains("onCancelSelected(quantity)"))
        assertTrue(salesScreen.contains("Text(\"行取消\""))
        assertTrue(salesScreen.contains("訂正履歴"))

        val registerApp = source.substringBefore("@Composable\nprivate fun Header(")
        assertTrue(registerApp.contains("edited.quantity < original.quantity"))
        assertTrue(registerApp.contains("CartCorrectionTypeV135.SELECTED_LINE"))
    }

    @Test
    fun databaseCorrectionIsAtomicAndSaleBoundaryClearsWorkHistory() {
        val source = File("src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt").readText()
        val correctionMethod = source
            .substringAfter("fun applyCartCorrection(")
            .substringBefore("fun holdCart(")
        val saveSale = source
            .substringAfter("fun saveSale(")
            .substringBefore("fun listSales(")

        assertTrue(correctionMethod.contains("runInTransactionWithResult"))
        assertTrue(correctionMethod.contains("CartCorrectionSchemaV135.insert"))
        assertTrue(correctionMethod.contains("LineTaxSnapshotStore.save"))
        assertTrue(saveSale.contains("CartCorrectionSchemaV135.clear(this)"))
    }
}
