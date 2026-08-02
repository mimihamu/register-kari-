package jp.co.tenposinfo.register

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
    fun onlyZSettlementHasPersistentBusinessSessionKey() {
        assertNull(OperationsIdempotencyPolicy.settlementKey(SettlementReportType.X_INSPECTION, 41L))
        assertEquals(
            "Z_SETTLEMENT:SESSION:41",
            OperationsIdempotencyPolicy.settlementKey(SettlementReportType.Z_SETTLEMENT, 41L),
        )
        assertTrue(
            OperationsIdempotencyPolicy.settlementKey(SettlementReportType.Z_SETTLEMENT, 41L) !=
                OperationsIdempotencyPolicy.settlementKey(SettlementReportType.Z_SETTLEMENT, 42L),
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
            guard.runExclusive("Z_SETTLEMENT:SESSION:41", "処理中") {
                error("expected")
            }
        }

        val result = guard.runExclusive("Z_SETTLEMENT:SESSION:41", "処理中") { 10 }
        assertEquals(10, result)
    }
}
