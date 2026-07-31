package jp.co.tenposinfo.register

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V0111StabilizationPolicyTest {
    @Test
    fun saleAfterMidnightBelongsToTheOpenBusinessSession() {
        val sessions = listOf(
            BusinessSessionWindow(30, "2026-07-30", 1_000L, 10_000L),
            BusinessSessionWindow(31, "2026-07-31", 20_000L, null),
        )
        val resolved = BusinessSessionAttributionPolicy.resolve(8_000L, sessions)
        assertEquals(30L, resolved?.id)
        assertEquals("2026-07-30", resolved?.businessDate)
    }

    @Test
    fun businessScreenBeforeOpeningUsesZeroSessionFallback() {
        val date = LocalDate.of(2026, 7, 31)
        val fallback = BusinessSessionDisplayFallback.forDate(date, date)
        assertEquals(0L, fallback?.id)
        assertEquals("2026-07-31", fallback?.businessDate)
        assertEquals(0L, fallback?.openingCash)
        assertEquals(null, BusinessSessionDisplayFallback.forDate(date.minusDays(1), date))
    }

    @Test
    fun twelvePercentIncludedReturnKeepsOriginalSnapshot() {
        val line = ReturnLineRecord(
            saleItemId = 1,
            productId = "I12",
            productName = "12%内税商品",
            unitPrice = 1120,
            taxCategory = TaxCategory.INCLUDED_10,
            taxKey = "INCLUDED_12",
            taxLabel = "12%内税",
            taxRatePercent = 12,
            taxIncluded = true,
            taxable = true,
            reduced = false,
            taxSymbol = "内12",
            originalQuantity = 2,
            originalDiscount = 0,
            note = "",
            returnedQuantity = 0,
            refundedDiscount = 0,
        )
        val item = line.toReturnItem(1)
        assertEquals(12, item.product.taxRatePercent)
        assertTrue(item.product.taxIncluded)
        assertEquals("INCLUDED_12", item.product.taxKey)
        assertEquals(120L, TaxEngine.calculate(listOf(item)).taxAmount)
    }

    @Test
    fun twelvePercentExcludedPartialReturnKeepsOriginalSnapshot() {
        val line = ReturnLineRecord(
            saleItemId = 2,
            productId = "E12",
            productName = "12%外税商品",
            unitPrice = 1000,
            taxCategory = TaxCategory.EXCLUDED_10,
            taxKey = "EXCLUDED_12",
            taxLabel = "12%外税",
            taxRatePercent = 12,
            taxIncluded = false,
            taxable = true,
            reduced = false,
            taxSymbol = "外12",
            originalQuantity = 3,
            originalDiscount = 0,
            note = "",
            returnedQuantity = 0,
            refundedDiscount = 0,
        )
        val item = line.toReturnItem(2)
        assertFalse(item.product.taxIncluded)
        assertEquals(240L, TaxEngine.calculate(listOf(item)).taxAmount)
        assertEquals(2240L, TaxEngine.calculate(listOf(item)).grossAmount)
    }

    @Test
    fun reservedTaxSnapshotDoesNotDependOnLaterMasterChanges() {
        val original = TaxSnapshot("TAX12", "12%内税", 12, true, true, false, "内12")
        val changedMaster = DynamicTaxRule("TAX12", "15%内税", 15, DynamicTaxMode.INCLUDED, false, true, "内15", "", "")
        val base = Product("P", "商品", 1120, TaxCategory.INCLUDED_10, 1)
        assertEquals(12, original.applyTo(base).taxRatePercent)
        assertEquals(15, TaxSnapshot.from(changedMaster).applyTo(base).taxRatePercent)
    }

    @Test
    fun staleProcessingLeaseIsRecovered() {
        assertTrue(OutboxLeasePolicy.isStale(null, 1000L))
        assertTrue(OutboxLeasePolicy.isStale(999L, 1000L))
        assertFalse(OutboxLeasePolicy.isStale(1001L, 1000L))
    }

    @Test
    fun folderSettingIsUsedInNewObjectKey() {
        val key = OutboxObjectKey.build("店舗A 同期", "2026-07-30", "SALE", "99")
        assertTrue(key.startsWith("店舗A_同期/2026-07-30/"))
    }

    @Test
    fun salePayloadTaxTotalsSupportArbitraryRate() {
        val lines = listOf(
            PayloadTaxLine(
                "P12", "12%外税", 1000, 2, 0, "", TaxCategory.EXCLUDED_10,
                TaxSnapshot("E12", "12%外税", 12, false, true, false, "外12"),
            ),
        )
        val summary = PayloadTaxAggregation.calculate(lines)
        assertEquals(2000L, summary.netAmount)
        assertEquals(240L, summary.taxAmount)
        assertEquals(2240L, summary.grossAmount)
    }

    @Test
    fun operatorPermissionRevisionForcesReloadAndSalesRemovalStopsSession() {
        assertTrue(OperatorSessionRevisionPolicy.shouldReload(10L, 11L))
        assertFalse(OperatorSessionRevisionPolicy.mayContinue(true, setOf(RegisterPermission.VIEW_SALES)))
        assertTrue(OperatorSessionRevisionPolicy.mayContinue(true, setOf(RegisterPermission.SALES)))
    }
}
