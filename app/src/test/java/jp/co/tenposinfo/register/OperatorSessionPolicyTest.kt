package jp.co.tenposinfo.register

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperatorSessionPolicyTest {
    @Test
    fun cashierDefaultsAllowSalesHoldAndSalesViewOnly() {
        val permissions = OperatorPermissionPolicy.defaults(OperatorRole.CASHIER)
        assertTrue(RegisterPermission.SALES in permissions)
        assertTrue(RegisterPermission.HOLD_TICKET in permissions)
        assertTrue(RegisterPermission.VIEW_SALES in permissions)
        assertFalse(RegisterPermission.SETTLEMENT in permissions)
        assertFalse(RegisterPermission.REVERSAL in permissions)
        assertFalse(RegisterPermission.SETTINGS in permissions)
    }

    @Test
    fun automaticPrintRetriesOnlyAfterAnAttemptedFailure() {
        assertTrue(AutomaticPrintPolicy.shouldRetry(configurationUsable = true, attempted = 1, failures = 1))
        assertFalse(AutomaticPrintPolicy.shouldRetry(configurationUsable = false, attempted = 1, failures = 1))
        assertFalse(AutomaticPrintPolicy.shouldRetry(configurationUsable = true, attempted = 0, failures = 0))
        assertFalse(AutomaticPrintPolicy.shouldRetry(configurationUsable = true, attempted = 3, failures = 0))
    }

    @Test
    fun authenticatedOperatorUsesSnapshotPermissions() {
        val operator = AuthenticatedOperator(
            id = 10,
            code = "OP010",
            name = "テスト担当者",
            role = OperatorRole.CASHIER,
            permissions = setOf(RegisterPermission.SALES, RegisterPermission.CASH_MOVEMENT),
        )
        assertTrue(operator.allows(RegisterPermission.SALES))
        assertTrue(operator.allows(RegisterPermission.CASH_MOVEMENT))
        assertFalse(operator.allows(RegisterPermission.REVERSAL))
        assertFalse(operator.isManager)
    }
}
