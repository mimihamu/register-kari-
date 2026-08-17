package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
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
    fun rep001ZeroTotalsAreExplicit() {
        assertEquals(0L, SettlementRep001TotalsV135.ZERO.discountTotalYen)
        assertEquals(0L, SettlementRep001TotalsV135.ZERO.taxTotalYen)
    }
}
