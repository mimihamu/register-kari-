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
