package jp.co.tenposinfo.register

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V11FoundationPolicyTest {
    @Test
    fun activeBusinessDateOverridesCalendarDate() {
        val result = BusinessDatePolicy.resolve("2026-07-30", LocalDate.of(2026, 7, 31))
        assertEquals(LocalDate.of(2026, 7, 30), result)
    }

    @Test
    fun calendarDateIsUsedWhenNoBusinessSessionExists() {
        val calendar = LocalDate.of(2026, 7, 31)
        assertEquals(calendar, BusinessDatePolicy.resolve(null, calendar))
    }

    @Test
    fun revisionDiffDetectsOnlyChangedFields() {
        val live = LiveMenuProduct(
            productId = "P001",
            name = "生ビール",
            enabled = true,
            unitPrice = 600,
            legacyTaxCategory = TaxCategory.INCLUDED_10,
            taxKey = TaxCategory.INCLUDED_10.name,
            buttonColor = "BLUE",
            pageNo = 1,
            slotNo = 1,
        )
        val revision = RevisionEditableProduct(
            revisionId = 10,
            productId = "P001",
            name = "生ビール",
            enabled = true,
            unitPrice = 650,
            legacyTaxCategory = TaxCategory.INCLUDED_10,
            taxKey = TaxCategory.EXCLUDED_10.name,
            buttonColor = "BLUE",
            pageNo = 1,
            slotNo = 1,
        )
        assertEquals(setOf("価格", "税区分"), RevisionDiffPolicy.changedFields(live, revision))
    }

    @Test
    fun outboxObjectKeyRemovesUnsafePathCharacters() {
        val key = OutboxObjectKey.build("つぐレジ / 店舗A", "2026-07-30", "SALE", "12:34")
        assertFalse(key.contains(" / "))
        assertFalse(key.contains(":"))
        assertTrue(key.endsWith("sale-12_34.json"))
    }

    @Test(expected = IllegalStateException::class)
    fun sameRateIncludedAndExcludedTaxIsBlocked() {
        val included = Product(
            id = "I12",
            name = "12%内税",
            unitPrice = 1120,
            taxCategory = TaxCategory.INCLUDED_10,
            displayOrder = 1,
            taxKey = "INCLUDED_12",
            taxLabel = "12%内税",
            taxSymbol = "内12",
            taxRatePercent = 12,
            taxIncluded = true,
            taxable = true,
        )
        val excluded = Product(
            id = "E12",
            name = "12%外税",
            unitPrice = 1000,
            taxCategory = TaxCategory.EXCLUDED_10,
            displayOrder = 2,
            taxKey = "EXCLUDED_12",
            taxLabel = "12%外税",
            taxSymbol = "外12",
            taxRatePercent = 12,
            taxIncluded = false,
            taxable = true,
        )
        TaxEngine.validateMixedTax(
            listOf(CartItem(included, 1), CartItem(excluded, 1)),
            MixedTaxPolicy.BLOCK,
        )
    }
}
