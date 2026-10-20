package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V137CartLineIdentityMigrationTest {
    private val product = Product(
        id = "same", name = "商品", unitPrice = 250L,
        taxCategory = TaxCategory.INCLUDED_10, displayOrder = 1,
    )
    private fun row(id: String, price: Long = 250L, q: Int = 1) =
        CartItem(
            product = product, quantity = q, unitPrice = price,
            discountAmount = 10L, note = "メモ", lineId = id,
        )

    @Test
    fun fillsOnlyMissingAndDuplicateIdsWithoutChangingBusinessData() {
        val original = listOf(row("stable"), row(""), row("stable"), row(" ", 400), row("other"))
        val ids = iteratorOf("generated-1", "generated-2", "generated-3")
        val result = CartLineIdentityV137.normalize(original) { ids.next() }
        assertEquals(listOf("stable", "generated-1", "generated-2", "generated-3", "other"),
            result.map { it.lineId })
        original.zip(result).forEach { (old, updated) ->
            assertEquals(old.copy(lineId = updated.lineId), updated)
            assertEquals(old.baseAmount, updated.baseAmount)
        }
    }

    @Test
    fun repeatedNormalizationKeepsRowIdentityStable() {
        val first = CartLineIdentityV137.normalize(listOf(row(""), row(""), row("old")))
        val second = CartLineIdentityV137.normalize(first)
        assertEquals(first, second)
        assertEquals(first.size, first.map { it.lineId }.toSet().size)
        assertTrue(first.all { it.lineId.isNotBlank() })
    }

    @Test
    fun changingQuantityRetainsSameRowId() {
        val first = CartLineIdentityV137.normalize(listOf(row(""))).single()
        val changed = first.copy(quantity = 2, quantityHundredths = 200L)
        assertEquals(first.lineId, CartLineIdentityV137.normalize(listOf(changed)).single().lineId)
        assertNotEquals(first.baseAmount, changed.baseAmount)
    }

    @Test
    fun hotPathStaysReadOnlyAfterFirstMigration() {
        val valid = listOf(row("stable"), row("unique"))
        assertTrue(!CartLineIdentityV137.needsRepair(valid))
        assertTrue(CartLineIdentityV137.needsRepair(valid + row("")))
        assertTrue(CartLineIdentityV137.needsRepair(valid + row("stable")))
        val source = File("src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt").readText()
        assertTrue(source.contains("if (!CartLineIdentityV137.needsRepair(rows))"))
        assertTrue(source.contains("return repairLegacyCartLineIds()"))
    }

    @Test
    fun activeCartMigrationUpdatesIdentityOnlyInsideTransaction() {
        val source = File("src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt").readText()
        val load = source.substringAfter("private fun repairLegacyCartLineIds()")
            .substringBefore("fun saveCart(")
        assertTrue(load.contains("writableDatabase.runInTransactionWithResult"))
        assertTrue(load.contains("ContentValues().apply { put(\"line_id\", id) }"))
        assertTrue(load.contains("CartLineIdentityV137.normalize"))
    }
}
