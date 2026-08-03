package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V016OperationsAuthorizationTest {
    private fun operator(vararg permissions: RegisterPermission) = AuthenticatedOperator(
        id = 1,
        code = "OP01",
        name = "担当者A",
        role = OperatorRole.CASHIER,
        permissions = permissions.toSet(),
    )

    @Test
    fun eachManagementActionUsesItsOwnPermission() {
        val cashier = operator(RegisterPermission.CASH_MOVEMENT)

        assertTrue(OperationsAuthorizationPolicy.canAccess(cashier, OperationsAction.CASH_MOVEMENT))
        assertFalse(OperationsAuthorizationPolicy.canAccess(cashier, OperationsAction.DAILY_SALES))
        assertFalse(OperationsAuthorizationPolicy.canAccess(cashier, OperationsAction.X_INSPECTION))
        assertFalse(OperationsAuthorizationPolicy.canAccess(cashier, OperationsAction.Z_SETTLEMENT))
        assertFalse(OperationsAuthorizationPolicy.canAccess(cashier, OperationsAction.REVERSAL))
    }

    @Test
    fun nullOrExpiredSessionCannotAccessManagementActions() {
        OperationsAction.entries.forEach { action ->
            assertFalse(OperationsAuthorizationPolicy.canAccess(null, action))
        }
    }

    @Test
    fun zSettlementAndReversalRequireManagerApproval() {
        assertFalse(
            OperationsAuthorizationPolicy.requiresManagerApproval(
                OperationsAction.X_INSPECTION,
                SettlementReportType.X_INSPECTION,
            ),
        )
        assertTrue(
            OperationsAuthorizationPolicy.requiresManagerApproval(
                OperationsAction.Z_SETTLEMENT,
                SettlementReportType.Z_SETTLEMENT,
            ),
        )
        assertTrue(OperationsAuthorizationPolicy.requiresManagerApproval(OperationsAction.REVERSAL))
    }

    @Test
    fun auditActorContainsAuthenticatedOperatorAndApprovingManager() {
        val actor = operator(RegisterPermission.REVERSAL)

        assertEquals("担当者A", OperationsActorFormatter.direct(actor))
        assertEquals("担当者A（承認:責任者B）", OperationsActorFormatter.approved(actor, "責任者B"))
    }
}
