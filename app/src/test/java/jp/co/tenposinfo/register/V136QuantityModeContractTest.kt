package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V136QuantityModeContractTest {
    @Test fun integerRejectsFractionalAndDecimalAcceptsIt() {
        assertTrue(runCatching { QuantityMode.INTEGER.requireAllowed(50L) }.isFailure)
        QuantityMode.INTEGER.requireAllowed(100L)
        QuantityMode.DECIMAL.requireAllowed(50L)
        QuantityMode.DECIMAL.requireAllowed(999_999L)
        assertTrue(runCatching { QuantityMode.DECIMAL.requireAllowed(1_000_000L) }.isFailure)
    }

    @Test fun manualReturnUsesExactQuantityAndProductMode() {
        val product = Product("WEIGHT", "量り売り", 1_000L, TaxCategory.INCLUDED_10, 1, quantityMode = QuantityMode.DECIMAL)
        val request = ManualReturnRequestV135(
            lines = listOf(ManualReturnLineRequestV135(product, 1, 50L)),
            reason = "test",
            refundMethod = ManualRefundMethodV135.CASH,
        )
        val item = ManualReturnPolicyV135.toPositiveCartItems(request, true).single()
        assertEquals(50L, item.quantityHundredths)
        assertEquals("0.5", item.quantityText)
        assertEquals(500L, item.amountBeforeDiscount)
        val invalid = request.copy(lines = listOf(ManualReturnLineRequestV135(product.copy(quantityMode = QuantityMode.INTEGER), 1, 50L)))
        assertTrue(runCatching { ManualReturnPolicyV135.toPositiveCartItems(invalid, true) }.isFailure)
    }

    @Test fun linkedReturnUsesSaleTimeQuantityMode() {
        val line = ReturnableSaleLine(
            saleItemId = 1L, productId = "WHOLE", productName = "通常商品", unitPrice = 1_000L,
            taxCategory = TaxCategory.INCLUDED_10, originalQuantity = 2, originalDiscount = 0L,
            note = "", returnedQuantity = 0, refundedDiscount = 0L, quantityMode = QuantityMode.INTEGER,
        )
        assertTrue(runCatching { line.toReturnItemHundredths(50L) }.isFailure)
        assertEquals(100L, line.toReturnItemHundredths(100L).quantityHundredths)
    }

    @Test fun sourcePathsPersistAndValidateQuantityMode() {
        val catalog = File("src/main/java/jp/co/tenposinfo/register/CatalogMasterStore.kt").readText()
        val db = File("src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt").readText()
        val manual = File("src/main/java/jp/co/tenposinfo/register/ManualReturnV135.kt").readText()
        val ops = File("src/main/java/jp/co/tenposinfo/register/OperationsStore.kt").readText()
        val ui = File("src/main/java/jp/co/tenposinfo/register/CatalogSettingsActivity.kt").readText()
        assertTrue(catalog.contains("quantity_mode TEXT NOT NULL DEFAULT 'INTEGER'"))
        assertTrue(catalog.contains("put(\"quantity_mode\", quantityMode.name)"))
        assertTrue(db.contains("QuantityModeSchemaV136.ensure(db)"))
        assertTrue(db.contains("put(\"quantity_mode\", item.product.quantityMode.name)"))
        assertTrue(db.contains("put(\"quantity_mode\", product.quantityMode.name)"))
        assertTrue(manual.contains("quantity_hundredths"))
        assertTrue(manual.contains("quantity_mode"))
        assertTrue(ops.contains("si.quantity_mode"))
        assertTrue(ui.contains("CycleButton(\"数量方式\", quantityMode.displayName)"))
    }
}
