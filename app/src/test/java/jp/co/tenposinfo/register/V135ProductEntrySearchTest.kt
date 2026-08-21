package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V135ProductEntrySearchTest {
    private fun product(id: String, name: String, kana: String = "", barcode: String = "") = Product(
        id = id,
        name = name,
        unitPrice = 100L,
        taxCategory = TaxCategory.INCLUDED_10,
        displayOrder = 1,
        kana = kana,
        barcode = barcode,
    )

    @Test
    fun exactLookupSupportsProductCodeAndBarcode() {
        val beer = product("P0001", "生ビール", "なまびーる", "4901234567890")
        assertEquals(beer, ProductLookupPolicyV135.findExact(listOf(beer), "p0001"))
        assertEquals(beer, ProductLookupPolicyV135.findExact(listOf(beer), "4901234567890"))
    }

    @Test
    fun searchSupportsNameKanaCodeAndBarcode() {
        val beer = product("P0001", "生ビール", "なまびーる", "4901234567890")
        val food = product("F0020", "枝豆", "えだまめ", "4900000000020")
        val products = listOf(beer, food)
        assertEquals(listOf(beer), ProductLookupPolicyV135.search(products, "生ビ"))
        assertEquals(listOf(food), ProductLookupPolicyV135.search(products, "えだ"))
        assertTrue(ProductLookupPolicyV135.search(products, "P000").contains(beer))
        assertEquals(listOf(food), ProductLookupPolicyV135.search(products, "0000000020"))
    }

    @Test
    fun ambiguousExactCodeAndBarcodeFailsClosed() {
        val a = product("ABC1", "A", barcode = "ZZZ1")
        val b = product("ZZZ1", "B")
        assertNull(ProductLookupPolicyV135.findExact(listOf(a, b), "ZZZ1"))
    }

    @Test
    fun quantityKeyEditsSelectedLineOrReservesNextProduct() {
        assertEquals(3, ProductQuantityKeyPolicyV135.decide("3", true)?.selectedLineQuantity)
        assertNull(ProductQuantityKeyPolicyV135.decide("3", true)?.pendingProductQuantity)
        assertEquals(3, ProductQuantityKeyPolicyV135.decide("3", false)?.pendingProductQuantity)
        assertNull(ProductQuantityKeyPolicyV135.decide("0", false))
        assertNull(ProductQuantityKeyPolicyV135.decide("100000", false))
    }

    @Test
    fun catalogValidationAllowsBlankBarcodeAndRejectsWhitespace() {
        assertEquals("", CatalogValidation.normalizeBarcode("  "))
        assertEquals("490123", CatalogValidation.normalizeBarcode("490123"))
        val failure = runCatching { CatalogValidation.normalizeBarcode("490 123") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }
}
