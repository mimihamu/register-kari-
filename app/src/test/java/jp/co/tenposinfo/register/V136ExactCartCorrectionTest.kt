package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V136ExactCartCorrectionTest {
    private val weighed = Product(
        id = "WEIGH",
        name = "量り売り",
        unitPrice = 1_000L,
        taxCategory = TaxCategory.INCLUDED_10,
        displayOrder = 1,
        quantityMode = QuantityMode.DECIMAL,
    )

    @Test
    fun exactCorrectionCanCancelHalfFromOnePointFive() {
        val item = CartItem(
            product = weighed,
            quantity = 2,
            quantityHundredths = 150L,
            discountAmount = 150L,
            lineId = "line-exact",
        )
        val result = CartCorrectionPolicyV135.applyHundredths(
            items = listOf(item),
            targetIndex = 0,
            cancelQuantityHundredths = 50L,
            correctionType = CartCorrectionTypeV135.SELECTED_LINE,
            operatorName = "担当",
            createdAt = 1L,
        )
        assertEquals(100L, result.items.single().quantityHundredths)
        assertEquals("0.5", result.record.cancelledQuantityText)
        assertEquals(50L, result.record.cancelledQuantityHundredths)
        assertEquals(100L, result.record.quantityAfterHundredths)
        assertEquals(450L, result.record.cancelledAmount)
        assertEquals(item.baseAmount, result.items.single().baseAmount + result.record.cancelledAmount)
    }

    @Test
    fun legacyCorrectionApiKeepsPriorProportionalBehavior() {
        val legacy = CartItem(
            product = weighed,
            quantity = 3,
            quantityHundredths = 150L,
            discountAmount = 30L,
            lineId = "line-legacy",
        )
        val result = CartCorrectionPolicyV135.apply(
            listOf(legacy), 0, 1, CartCorrectionTypeV135.SELECTED_LINE, "担当", 1L,
        )
        assertEquals(50L, result.record.cancelledQuantityHundredths)
        assertEquals(100L, result.items.single().quantityHundredths)
    }

    @Test
    fun exactQuantityKeyAcceptsDecimalPendingButRejectsDecimalForIntegerSelectedLine() {
        assertEquals(
            50L,
            ProductExactQuantityKeyPolicyV136.decide("0.5", null)?.pendingProductQuantityHundredths,
        )
        assertNull(ProductExactQuantityKeyPolicyV136.decide("0.5", QuantityMode.INTEGER))
        assertEquals(
            50L,
            ProductExactQuantityKeyPolicyV136.decide("0.5", QuantityMode.DECIMAL)?.selectedLineQuantityHundredths,
        )
        assertNull(ProductExactQuantityKeyPolicyV136.decide("10000", null))
    }

    @Test
    fun databaseExposesExactCorrectionApiAndSchemaStoresExactAuditQuantity() {
        val database = File("src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt").readText()
        val correction = File("src/main/java/jp/co/tenposinfo/register/CartCorrectionV135.kt").readText()
        assertTrue(database.contains("fun applyCartCorrectionHundredths("))
        assertTrue(correction.contains("cancelled_quantity_hundredths"))
        assertTrue(correction.contains("quantity_before_hundredths"))
        assertTrue(correction.contains("quantity_after_hundredths"))
    }
}
