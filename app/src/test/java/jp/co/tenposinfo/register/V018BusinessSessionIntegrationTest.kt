package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class V018BusinessSessionIntegrationTest {
    @Test
    fun openingCashIsIncludedInExpectedCash() {
        assertEquals(
            63_000L,
            OperationsMath.expectedCash(
                cashSalesAfterRefunds = 12_000L,
                cashIn = 3_000L,
                cashOut = 2_000L,
                openingCash = 50_000L,
            ),
        )
    }

    @Test
    fun businessDateMayBeTodayOrPreviousDayOnly() {
        val today = LocalDate.of(2026, 8, 2)
        assertTrue(BusinessSessionTransitionPolicy.mayStart(today, today))
        assertTrue(BusinessSessionTransitionPolicy.mayStart(today.minusDays(1), today))
        assertFalse(BusinessSessionTransitionPolicy.mayStart(today.plusDays(1), today))
        assertFalse(BusinessSessionTransitionPolicy.mayStart(today.minusDays(2), today))
    }

    @Test
    fun onlyOpenSessionMayAcceptOperationsAndSettlement() {
        assertTrue(BusinessSessionTransitionPolicy.mayOperate(BusinessSessionStatus.OPEN))
        assertTrue(BusinessSessionTransitionPolicy.maySettle(BusinessSessionStatus.OPEN))
        assertFalse(BusinessSessionTransitionPolicy.mayOperate(BusinessSessionStatus.Z_SETTLED))
        assertFalse(BusinessSessionTransitionPolicy.maySettle(BusinessSessionStatus.Z_SETTLED))
        assertFalse(BusinessSessionTransitionPolicy.mayOperate(BusinessSessionStatus.CLOSED))
        assertFalse(BusinessSessionTransitionPolicy.maySettle(BusinessSessionStatus.CLOSED))
        assertFalse(BusinessSessionTransitionPolicy.mayOperate(null))
        assertFalse(BusinessSessionTransitionPolicy.maySettle(null))
    }

    @Test
    fun zSettlementClosesSessionWhileXInspectionKeepsItOpen() {
        assertEquals(
            BusinessSessionStatus.OPEN,
            BusinessSessionLifecyclePolicy.resultStatus(SettlementReportType.X_INSPECTION, BusinessSessionStatus.OPEN),
        )
        assertEquals(
            BusinessSessionStatus.CLOSED,
            BusinessSessionLifecyclePolicy.resultStatus(SettlementReportType.Z_SETTLEMENT, BusinessSessionStatus.OPEN),
        )
    }
}
