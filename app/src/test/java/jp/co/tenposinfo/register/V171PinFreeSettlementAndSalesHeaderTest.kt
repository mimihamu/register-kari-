package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** No sales header minus shortcut; PIN-free managers can close a business session. */
class V171PinFreeSettlementAndSalesHeaderTest {
    private val dir = File("src/main/java/jp/co/tenposinfo/register")
    private fun src(name: String) = File(dir, name).readText()

    @Test fun normalSalesOnlyUsesManagementForMinus() {
        val main = src("MainActivity.kt")
        assertFalse(main.contains("onRegisterMinus = if"))
        assertFalse(main.contains("onClick = onRegisterMinus"))
        assertFalse(main.contains("onRegisterMinus: (() -> Unit)? = null"))
        assertTrue(main.contains("onOpenManagement = { context.startActivity(Intent(context, OperationsHubActivityV030::class.java)) }"))
        assertTrue(src("OperationsHubActivityV030.kt").contains("RegisterPermission.REVERSAL in permissions, openManualReturn"))
        assertTrue(src("ManualReturnActivityV135.kt").contains("minusMode = true"))
    }

    @Test fun pinFreeApprovalRequiresVerifiedManagerAndIsCheckedAtCommitTime() {
        val approval = src("ManagerApprovalV145.kt")
        assertTrue(approval.contains("pinFreeSessionManagerName(context)?.let { return it }"))
        assertTrue(approval.contains("if (!session.isManager) return null"))
        assertTrue(approval.contains("managerNameForPinFreeSession(session.id)"))
        assertTrue(approval.contains("if (suppliedPin.isNotBlank())"))
        assertTrue(src("AdminSettingsStore.kt").contains("WHERE id = ? AND role = ? AND enabled = 1 AND pin_required = 0"))
        val secure = src("SecureOperationsCoordinator.kt")
        assertTrue(secure.contains("val operator = requireOperator(action)"))
        assertTrue(secure.contains("requireManagerName(managerPin)"))
        assertTrue(secure.contains("store.recordSettlement("))
        val policy = OperatorPermissionPolicy.defaults(OperatorRole.CASHIER)
        assertFalse(OperatorPinOptionalPolicyV144.canLoginWithoutPin(
            OperatorRole.CASHIER, policy + RegisterPermission.Z_SETTLEMENT, pinRequired = false))
        assertTrue(OperatorPinOptionalPolicyV144.canLoginWithoutPin(
            OperatorRole.MANAGER, OperatorPermissionPolicy.defaults(OperatorRole.MANAGER), pinRequired = false))
    }

    @Test fun newAndLegacyZSettlementScreensSkipPinForSessionApprovedManager() {
        val new = src("SettlementActivityV030.kt")
        val old = src("OperationsActivity.kt")
        for (screen in listOf(new, old)) {
            assertTrue(screen.contains("val pinFreeManagerApproved = ManagerApprovalV145.canUseCurrentManagerSession("))
            assertTrue(screen.contains("if (pinFreeManagerApproved)"))
            assertTrue(screen.contains("責任者承認済み（PIN不要）"))
            assertTrue(screen.contains("enabled = (pinFreeManagerApproved ||"))
            assertTrue(screen.contains("ZSettlementPreflightPolicy.evaluate("))
        }
        assertTrue(new.contains("pinFreeManagerApproved: Boolean"))
        assertTrue(new.contains("actualCash, managerPin, pinFreeManagerApproved, pendingAcknowledged"))
        assertTrue(new.contains("SettlementActualCashSafetyV105.maySubmit"))
    }
}
