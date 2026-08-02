package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V027SettlementHistoryReprintTest {
    private fun record(
        id: Long,
        sessionId: Long,
        type: SettlementReportType,
        snapshotVersion: Int = 1,
    ) = SettlementRecord(
        id = id,
        businessSessionId = sessionId,
        businessDate = "2026-08-03",
        type = type,
        salesGross = 10_000,
        reversalGross = 0,
        netSales = 10_000,
        expectedCash = 10_000,
        actualCash = 10_000,
        variance = 0,
        transactionCount = 2,
        reversalCount = 0,
        pendingPrints = 0,
        heldTickets = 0,
        operatorName = "責任者",
        createdAt = id,
        snapshotVersion = snapshotVersion,
    )

    @Test
    fun historyFiltersByBusinessSessionAndReportType() {
        val records = listOf(
            record(1, 10, SettlementReportType.X_INSPECTION),
            record(2, 10, SettlementReportType.Z_SETTLEMENT),
            record(3, 11, SettlementReportType.X_INSPECTION),
        )

        assertEquals(
            listOf(2L),
            SettlementHistoryPolicyV027.filter(
                records,
                businessSessionId = 10,
                type = SettlementReportType.Z_SETTLEMENT,
            ).map { it.id },
        )
        assertEquals(
            listOf(1L, 2L),
            SettlementHistoryPolicyV027.filter(records, businessSessionId = 10, type = null).map { it.id },
        )
    }

    @Test
    fun xInspectionAndZSettlementReprintPermissionsStaySeparated() {
        val x = record(1, 10, SettlementReportType.X_INSPECTION)
        val z = record(2, 10, SettlementReportType.Z_SETTLEMENT)

        assertTrue(
            SettlementHistoryPolicyV027.canReprint(
                x,
                setOf(RegisterPermission.X_INSPECTION),
                managerPinProvided = false,
            ),
        )
        assertFalse(
            SettlementHistoryPolicyV027.canReprint(
                z,
                setOf(RegisterPermission.X_INSPECTION),
                managerPinProvided = true,
            ),
        )
        assertFalse(
            SettlementHistoryPolicyV027.canReprint(
                z,
                setOf(RegisterPermission.Z_SETTLEMENT),
                managerPinProvided = false,
            ),
        )
        assertTrue(
            SettlementHistoryPolicyV027.canReprint(
                z,
                setOf(RegisterPermission.Z_SETTLEMENT),
                managerPinProvided = true,
            ),
        )
    }

    @Test
    fun completeSnapshotOrOriginalPayloadCanBeReprinted() {
        assertTrue(SettlementHistoryPolicyV027.canReconstruct(snapshotVersion = 1, originalPayloadExists = false))
        assertTrue(SettlementHistoryPolicyV027.canReconstruct(snapshotVersion = 0, originalPayloadExists = true))
        assertFalse(SettlementHistoryPolicyV027.canReconstruct(snapshotVersion = 0, originalPayloadExists = false))
    }
}
