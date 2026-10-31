package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V145ManagerPinOptionalReversalReasonTest {
    private fun src(name: String) =
        File("src/main/java/jp/co/tenposinfo/register", name).readText()

    @Test fun aManagerMayExplicitlyChoosePinFreeLogin() {
        val managerPermissions = OperatorPermissionPolicy.defaults(OperatorRole.MANAGER)
        assertTrue(OperatorPinOptionalPolicyV144.canSaveWithoutPin(OperatorRole.MANAGER, managerPermissions))
        assertTrue(OperatorPinOptionalPolicyV144.canLoginWithoutPin(
            OperatorRole.MANAGER, managerPermissions, pinRequired = false))
        assertFalse(OperatorPinOptionalPolicyV144.canLoginWithoutPin(
            OperatorRole.MANAGER, managerPermissions, pinRequired = true))
        assertFalse(OperatorPinOptionalPolicyV144.canLoginWithoutPin(
            OperatorRole.CASHIER, managerPermissions, pinRequired = false))
    }

    @Test fun emptyPinManagerApprovalRequiresVerifiedCurrentManagerSession() {
        val approval = src("ManagerApprovalV145.kt")
        assertTrue(approval.contains("OperatorSessionRegistry.current"))
        assertTrue(approval.contains("if (!session.isManager) return null"))
        assertTrue(approval.contains("managerNameForPinFreeSession(session.id)"))
        assertTrue(approval.contains("if (suppliedPin.isNotBlank())"))
        val store = src("AdminSettingsStore.kt")
        assertTrue(store.contains("WHERE id = ? AND role = ? AND enabled = 1 AND pin_required = 0"))
        assertTrue(store.contains("pin_required INTEGER NOT NULL DEFAULT 1"))
        assertTrue(src("SecureOperationsCoordinator.kt").contains("ManagerApprovalV145.resolve(appContext, pin)"))
        assertTrue(src("AdminSettingsActivity.kt").contains("ManagerApprovalV145.resolve(context.applicationContext, pin)"))
        assertTrue(src("ManualReturnV135.kt").contains("ManagerApprovalV145.resolve(appContext, managerPin)"))
    }

    @Test fun reasonsAreOptionalButAllReversalSafetyRemains() {
        val ops = src("OperationsActivity.kt")
        val reversal = ops.substring(ops.indexOf("private fun ReversalScreen("))
        assertTrue(reversal.contains("label = { Text(\"理由（任意）\") }"))
        assertTrue(reversal.contains("val canExecute = selected != null && managerApproved && when (type)"))
        assertTrue(reversal.contains("selectedItems.isNotEmpty()"))
        assertTrue(src("SecureOperationsCoordinator.kt").contains("requireOperator(OperationsAction.REVERSAL)"))
        val advanced = src("AdvancedOperationsStore.kt")
        val modern = src("OperationsStore.kt")
        val a = advanced.substring(advanced.indexOf("    fun createReversal("),advanced.indexOf("    fun recentReversals("))
        val m = modern.substring(modern.indexOf("    fun createReversalHundredths("),modern.indexOf("    fun recentReversals("))
        assertFalse(a.contains("require(reason.isNotBlank())"))
        assertFalse(m.contains("require(reason.isNotBlank())"))
        assertTrue(m.contains("operations") || m.contains("reversal_transactions"))
        val manual = src("ManualReturnV135.kt")
        assertTrue(manual.contains("toPositiveCartItems(request, reasonRequired = false)"))
        assertTrue(manual.contains("fun isReasonRequired(): Boolean = false"))
    }

    @Test fun managerLoginAllowsPinFreeConfirmationButCashierCannotSkipApproval() {
        val activity = src("OperationsActivity.kt")
        assertTrue(activity.contains("ManagerApprovalV145.canUseCurrentManagerSession(LocalContext.current)"))
        val settle = src("SettlementActivityV030.kt")
        assertTrue(settle.contains("ManagerApprovalV145.canUseCurrentManagerSession(context)"))
        val history = src("SettlementHistoryActivityV030.kt")
        assertTrue(history.contains("ManagerApprovalV145.canUseCurrentManagerSession(LocalContext.current)"))
        val ui = src("ManualReturnActivityV135.kt")
        assertTrue(ui.contains("label = { Text(\"理由（任意）\") }"))
        assertFalse(ui.contains("理由必須設定を保存"))
        assertTrue(src("SecureOperationsCoordinator.kt").contains("reason.ifBlank"))
    }
}
