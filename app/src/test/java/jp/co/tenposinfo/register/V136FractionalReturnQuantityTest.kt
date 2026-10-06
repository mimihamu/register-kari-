package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V136FractionalReturnQuantityTest {
    private fun line(
        originalHundredths: Long = 150L,
        returnedHundredths: Long = 0L,
        originalDiscount: Long = 150L,
        refundedDiscount: Long = 0L,
    ) = ReturnableSaleLine(
        saleItemId = 10L,
        productId = "P-10",
        productName = "量り売り返品",
        unitPrice = 1_000L,
        taxCategory = TaxCategory.INCLUDED_10,
        originalQuantity = (originalHundredths / QuantityV136.SCALE).coerceAtLeast(1L).toInt(),
        originalDiscount = originalDiscount,
        note = "",
        returnedQuantity = (returnedHundredths / QuantityV136.SCALE).toInt(),
        refundedDiscount = refundedDiscount,
        originalQuantityHundredths = originalHundredths,
        returnedQuantityHundredths = returnedHundredths,
    )

    @Test
    fun fractionalReturnUsesExactQuantityForAmountAndDiscountAllocation() {
        val item = line().toReturnItemHundredths(50L)
        assertEquals(50L, item.quantityHundredths)
        assertEquals("0.5", item.quantityText)
        assertEquals(500L, item.amountBeforeDiscount)
        assertEquals(50L, item.discountAmount)
        assertEquals(450L, item.baseAmount)
    }

    @Test
    fun exactSelectionAcceptsQuarterAndRejectsBeyondRemaining() {
        val source = line(originalHundredths = 150L, returnedHundredths = 25L, refundedDiscount = 25L)
        val selected = PartialReturnPolicy.selectHundredths(
            ReversalType.RETURN,
            listOf(source),
            mapOf(source.saleItemId to 25L),
        )
        assertEquals("0.25", selected.single().second.quantityText)

        val tooMuch = runCatching {
            PartialReturnPolicy.selectHundredths(
                ReversalType.RETURN,
                listOf(source),
                mapOf(source.saleItemId to 126L),
            )
        }
        assertTrue(tooMuch.isFailure)
        assertTrue(tooMuch.exceptionOrNull()?.message?.contains("返品数量が残数を超えています") == true)
    }

    @Test
    fun cancelSelectsExactFullRemainingQuantity() {
        val source = line(originalHundredths = 150L, returnedHundredths = 0L)
        val selected = PartialReturnPolicy.selectHundredths(
            ReversalType.CANCEL,
            listOf(source),
            emptyMap(),
        )
        assertEquals(150L, selected.single().second.quantityHundredths)
        assertEquals("1.5", selected.single().second.quantityText)
    }

    @Test
    fun integerApiRemainsBackwardCompatible() {
        val whole = ReturnableSaleLine(
            saleItemId = 20L,
            productId = "P-20",
            productName = "整数返品",
            unitPrice = 500L,
            taxCategory = TaxCategory.INCLUDED_10,
            originalQuantity = 2,
            originalDiscount = 0L,
            note = "",
            returnedQuantity = 0,
            refundedDiscount = 0L,
        )
        val selected = PartialReturnPolicy.select(
            ReversalType.RETURN,
            listOf(whole),
            mapOf(whole.saleItemId to 1),
        )
        assertEquals(100L, selected.single().second.quantityHundredths)
    }

    @Test
    fun productionPathsExposeExactQuantityApiAndDecimalEditor() {
        val coordinator = File("src/main/java/jp/co/tenposinfo/register/SecureOperationsCoordinator.kt").readText()
        val store = File("src/main/java/jp/co/tenposinfo/register/OperationsStore.kt").readText()
        val activity = File("src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt").readText()

        assertTrue(coordinator.contains("fun createReversalHundredths("))
        assertTrue(store.contains("fun createReversalHundredths("))
        assertTrue(store.contains("requestedQuantityHundredths"))
        assertTrue(store.contains("remainingQuantityHundredths"))
        assertTrue(activity.contains("Map<Long, String>"))
        assertTrue(activity.contains("KeyboardType.Decimal"))
        assertTrue(activity.contains("PartialReturnPolicy.selectHundredths"))
        assertTrue(activity.contains("line.remainingQuantityHundredths"))
    }

    @Test
    fun completedSaleDetectionUsesExactQuantityColumns() {
        val store = File("src/main/java/jp/co/tenposinfo/register/OperationsStore.kt").readText()
        assertTrue(store.contains("SUM(COALESCE(si.quantity_hundredths, si.quantity * 100))"))
        assertTrue(store.contains("COALESCE(ri.return_quantity_hundredths, ri.return_quantity * 100)"))
    }
}
