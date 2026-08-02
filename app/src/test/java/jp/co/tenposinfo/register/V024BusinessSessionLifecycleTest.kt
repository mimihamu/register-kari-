package jp.co.tenposinfo.register

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V024BusinessSessionLifecycleTest {
    @Test
    fun sameBusinessDateMayHaveDistinctSettlementKeysBySession() {
        val firstSession = OperationsIdempotencyPolicy.settlementKey(SettlementReportType.Z_SETTLEMENT, 100L)
        val secondSession = OperationsIdempotencyPolicy.settlementKey(SettlementReportType.Z_SETTLEMENT, 101L)

        assertNotEquals(firstSession, secondSession)
    }

    @Test
    fun newBusinessSessionMayStartAfterPreviousSessionIsClosed() {
        assertFalse(BusinessSessionLifecyclePolicy.mayStart(BusinessSessionStatus.OPEN))
        assertTrue(BusinessSessionLifecyclePolicy.mayStart(BusinessSessionStatus.CLOSED))
        assertTrue(BusinessSessionLifecyclePolicy.mayStart(null))
    }

    @Test
    fun v024SchemaDoesNotMakeBusinessDateUnique() {
        val normalized = BusinessSessionMultiplicityMigration.CREATE_TABLE_SQL
            .replace(Regex("\\s+"), " ")
            .uppercase()

        assertTrue(normalized.contains("BUSINESS_DATE TEXT NOT NULL"))
        assertFalse(normalized.contains("BUSINESS_DATE TEXT NOT NULL UNIQUE"))
    }
}
