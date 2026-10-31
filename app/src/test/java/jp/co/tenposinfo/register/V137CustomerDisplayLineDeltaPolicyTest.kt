package jp.co.tenposinfo.register

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V137CustomerDisplayLineDeltaPolicyTest {
    private val sameProduct = Product(
        id = "same",
        name = "同じ商品",
        unitPrice = 200L,
        taxCategory = TaxCategory.INCLUDED_10,
        displayOrder = 1,
    )

    private fun line(id: String, quantity: Int = 1, note: String = "") =
        CartItem(product = sameProduct, quantity = quantity, lineId = id, note = note)

    @Test
    fun detectsSecondRowEvenWhenProductIdAndQuantityMatchFirstRow() {
        val before = listOf(line("a"))
        val after = before + line("b")
        assertEquals("b", CustomerDisplayCartChangePolicy.latestChangedLineId(before, after))
        val snapshot = CustomerDisplaySnapshotFactory.sales(
            items = after, storeName = "店舗", latestLineId = "b",
        )
        assertFalse(snapshot.orderItems[0].latest)
        assertTrue(snapshot.orderItems[1].latest)
        assertEquals("b", JSONObject(snapshot.toJson()).getString("latestLineId"))
    }

    @Test
    fun correctionToFirstDuplicateRowHighlightsFirstNotLastMatchingProduct() {
        val before = listOf(line("a"), line("b"))
        val after = listOf(line("a", quantity = 2), line("b"))
        assertEquals("a", CustomerDisplayCartChangePolicy.latestChangedLineId(before, after))
        val snapshot = CustomerDisplaySnapshotFactory.sales(
            items = after, storeName = "店舗", latestLineId = "a",
        )
        assertTrue(snapshot.orderItems[0].latest)
        assertFalse(snapshot.orderItems[1].latest)
    }

    @Test
    fun reorderingKnownRowsChangesFingerprintWithoutInventingNewRegistration() {
        val before = listOf(line("a"), line("b"))
        val after = before.reversed()
        assertNotEquals(
            CustomerDisplayCartChangePolicy.fingerprint(before),
            CustomerDisplayCartChangePolicy.fingerprint(after),
        )
        assertNull(CustomerDisplayCartChangePolicy.latestChangedLineId(before, after))
    }

    @Test
    fun taxSymbolAndRowIdChangesRequireFreshSnapshot() {
        val before = listOf(line("a"))
        val changedSymbol = listOf(line("a").copy(
            product = sameProduct.copy(taxSymbol = "新税表示"),
        ))
        val changedId = listOf(line("b"))
        assertNotEquals(
            CustomerDisplayCartChangePolicy.fingerprint(before),
            CustomerDisplayCartChangePolicy.fingerprint(changedSymbol),
        )
        assertEquals("a", CustomerDisplayCartChangePolicy.latestChangedLineId(before, changedSymbol))
        assertNotEquals(
            CustomerDisplayCartChangePolicy.fingerprint(before),
            CustomerDisplayCartChangePolicy.fingerprint(changedId),
        )
        assertEquals("b", CustomerDisplayCartChangePolicy.latestChangedLineId(before, changedId))
    }

    @Test
    fun namesAndNotesContainingDelimitersCannotCollide() {
        val before = listOf(line("a", note = "x|1:y"))
        val after = listOf(line("a", note = "x|1:yy"))
        assertNotEquals(
            CustomerDisplayCartChangePolicy.fingerprint(before),
            CustomerDisplayCartChangePolicy.fingerprint(after),
        )
    }

    @Test
    fun legacyBlankLineIdFallbackHighlightsCorrectLastLine() {
        val before = listOf(line(""))
        val after = before + line("")
        assertEquals("legacy-line-2", CustomerDisplayCartChangePolicy.latestChangedLineId(before, after))
        val snapshot = CustomerDisplaySnapshotFactory.sales(
            items = after, storeName = "店舗", latestLineId = "legacy-line-2",
        )
        assertFalse(snapshot.orderItems[0].latest)
        assertTrue(snapshot.orderItems[1].latest)
    }
}
