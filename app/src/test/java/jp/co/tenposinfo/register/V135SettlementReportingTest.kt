package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V135SettlementReportingTest {
    @Test
    fun rep003BackupAcknowledgementUsesV25AuditEventName() {
        assertEquals(
            "Z_SETTLEMENT_BACKUP_FAILURE_ACK",
            SettlementReportingPolicyV135.normalizedAuditEvent(
                "Z_SETTLEMENT_BACKUP_FAILURE_ACKNOWLEDGED",
            ),
        )
    }

    @Test
    fun unrelatedAuditEventIsNotChanged() {
        assertEquals(
            "Z_SETTLEMENT",
            SettlementReportingPolicyV135.normalizedAuditEvent("Z_SETTLEMENT"),
        )
    }

    @Test
    fun rep001ZeroTotalsAreExplicitAndDoNotFabricateGuests() {
        assertEquals(0L, SettlementRep001TotalsV135.ZERO.discountTotalYen)
        assertEquals(0L, SettlementRep001TotalsV135.ZERO.taxTotalYen)
        assertEquals(0, SettlementRep001TotalsV135.ZERO.itemCount)
        assertEquals(0, SettlementRep001TotalsV135.ZERO.guestCount)
    }

    @Test
    fun guestCountRequiresExplicitPositiveValueWithinBounds() {
        assertEquals(1, SaleGuestCountPolicyV135.validate(1))
        assertEquals(999, SaleGuestCountPolicyV135.validate(999))
        assertTrue(runCatching { SaleGuestCountPolicyV135.validate(0) }.isFailure)
        assertTrue(runCatching { SaleGuestCountPolicyV135.validate(1000) }.isFailure)
    }

    @Test
    fun taxRateBreakdownSubtractsReturnsAndKeepsNonTaxableSeparate() {
        val sale = TaxSummary(
            listOf(
                TaxBucket(10, true, setOf(TaxCategory.INCLUDED_10), 1_000, 100, 1_100),
                TaxBucket(8, true, setOf(TaxCategory.INCLUDED_8), 500, 40, 540),
                TaxBucket(0, false, setOf(TaxCategory.NON_TAXABLE), 300, 0, 300),
            ),
        )
        val returned = TaxSummary(
            listOf(
                TaxBucket(10, true, setOf(TaxCategory.INCLUDED_10), 200, 20, 220),
                TaxBucket(0, false, setOf(TaxCategory.NON_TAXABLE), 100, 0, 100),
            ),
        )
        val result = SettlementTaxBreakdownPolicyV135.aggregate(listOf(1 to sale, -1 to returned))
        assertEquals(
            listOf(
                SettlementTaxRateBucketV135(10, true, 880, 80),
                SettlementTaxRateBucketV135(8, true, 540, 40),
                SettlementTaxRateBucketV135(0, false, 200, 0),
            ),
            result,
        )
    }

    @Test
    fun pdfFileNameContainsTypeDateAndSnapshotNumber() {
        val record = SettlementRecord(
            id = 42L,
            businessSessionId = 7L,
            businessDate = "2026-08-18",
            type = SettlementReportType.X_INSPECTION,
            salesGross = 1000L,
            reversalGross = 0L,
            netSales = 1000L,
            expectedCash = 1000L,
            actualCash = 1000L,
            variance = 0L,
            transactionCount = 1,
            reversalCount = 0,
            pendingPrints = 0,
            heldTickets = 0,
            operatorName = "test",
            createdAt = 1L,
        )
        assertEquals(
            "TSUGUREGI_X_20260818_No42.pdf",
            SettlementPdfExportPolicyV135.fileName(record),
        )
        assertTrue(SettlementPdfExportPolicyV135.linesPerPage() > 0)
    }
}
