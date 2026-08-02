package jp.co.tenposinfo.register

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V017OperationExecutionSafetyTest {
    @Test
    fun reversalKeyIsStableAndBoundToOriginalSale() {
        assertEquals("REVERSAL:123", OperationsIdempotencyPolicy.reversalKey(123))
        assertTrue(OperationsIdempotencyPolicy.reversalKey(123) != OperationsIdempotencyPolicy.reversalKey(124))
    }

    @Test
    fun onlyZSettlementHasPersistentBusinessDateKey() {
        val date = LocalDate.of(2026, 8, 2)

        assertNull(OperationsIdempotencyPolicy.settlementKey(SettlementReportType.X_INSPECTION, date))
        assertEquals(
            "Z_SETTLEMENT:2026-08-02",
            OperationsIdempotencyPolicy.settlementKey(SettlementReportType.Z_SETTLEMENT, date),
        )
    }

    @Test
    fun guardRejectsReentrantExecutionForSameKey() {
        val guard = OperationExecutionGuard()
        val failure = runCatching {
            guard.runExclusive("REVERSAL:1", "処理中") {
                guard.runExclusive("REVERSAL:1", "処理中") { Unit }
            }
        }

        assertTrue(failure.isFailure)
        assertEquals("処理中", failure.exceptionOrNull()?.message)
    }

    @Test
    fun guardReleasesKeyAfterFailure() {
        val guard = OperationExecutionGuard()
        runCatching {
            guard.runExclusive("Z_SETTLEMENT:2026-08-02", "処理中") {
                error("expected")
            }
        }

        val result = guard.runExclusive("Z_SETTLEMENT:2026-08-02", "処理中") { 10 }
        assertEquals(10, result)
    }
}
