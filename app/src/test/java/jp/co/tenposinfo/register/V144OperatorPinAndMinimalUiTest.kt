package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V144OperatorPinAndMinimalUiTest {
    private val root = File("src/main/java/jp/co/tenposinfo/register")
    private fun src(name: String) = File(root, name).readText()

    @Test fun onlyLimitedCashiersCanOptOutOfPin() {
        val cashierPermissions = OperatorPermissionPolicy.defaults(OperatorRole.CASHIER)
        assertTrue(OperatorPinOptionalPolicyV144.canLoginWithoutPin(
            OperatorRole.CASHIER, cashierPermissions, pinRequired = false))
        assertFalse(OperatorPinOptionalPolicyV144.canLoginWithoutPin(
            OperatorRole.CASHIER, cashierPermissions, pinRequired = true))
        assertFalse(OperatorPinOptionalPolicyV144.canLoginWithoutPin(
            OperatorRole.MANAGER, cashierPermissions, pinRequired = false))
        assertFalse(OperatorPinOptionalPolicyV144.canLoginWithoutPin(
            OperatorRole.CASHIER, cashierPermissions + RegisterPermission.REVERSAL, false))
        assertFalse(OperatorPinOptionalPolicyV144.canLoginWithoutPin(
            OperatorRole.CASHIER, cashierPermissions + RegisterPermission.SETTINGS, false))
    }

    @Test fun pinOptOutMustPersistWithSecureDefaultAndAudit() {
        val settings = src("AdminSettingsStore.kt")
        val login = src("OperatorSession.kt")
        assertTrue(settings.contains("pin_required INTEGER NOT NULL DEFAULT 1"))
        assertTrue(settings.contains("ALTER TABLE register_operators ADD COLUMN pin_required"))
        assertTrue(settings.contains("currentOperatorPinRequired(id)"))
        assertTrue(settings.contains("pinRequired: Boolean = true"))
        assertTrue(login.contains("OperatorPinOptionalPolicyV144.canLoginWithoutPin"))
        assertTrue(login.contains("LOGIN_SUCCEEDED_NO_PIN"))
        assertTrue(login.contains("if (!noPinAllowed)"))
    }

    @Test fun managerSettingsCanToggleCashierOnly() {
        val admin = src("AdminSettingsActivity.kt")
        val login = src("MainActivity.kt")
        assertTrue(admin.contains("Text(\"ログイン時にPINを使う\""))
        assertTrue(admin.contains("enabled = role == OperatorRole.CASHIER"))
        assertTrue(admin.contains("pinRequired = pinRequired"))
        assertTrue(login.contains("if (!operator.pinRequired) onLogin(operator.id, \"\")"))
        assertTrue(login.contains("if (selectedOperator?.pinRequired == false)"))
        assertTrue(login.contains("preferredMinKeyDp = 72"))
    }

    @Test fun cashMovementCommentIsOptionalWhileReversalReasonStaysRequired() {
        val operations = src("OperationsActivity.kt")
        val cashScreen = operations.substring(operations.indexOf("private fun CashMovementScreen("),
            operations.indexOf("private fun ReversalScreen("))
        assertTrue(cashScreen.contains("コメント（任意）"))
        for (store in listOf("OperationsStore.kt", "AdvancedOperationsStore.kt")) {
            val code = src(store)
            val cash = code.substring(code.indexOf("    fun recordCashMovement("),
                code.indexOf("    fun recentCashMovements("))
            assertFalse(cash.contains("require(reason.isNotBlank())"))
            assertTrue(code.contains("require(reason.isNotBlank())"))
        }
    }

    @Test fun remainingSystemBarsAndMenuDescriptionsUseCommonLayout() {
        for (file in listOf("InitialReleaseSettingsV135.kt",
            "PerformanceDiagnosticsActivityV136.kt",
            "SettlementReconciliationAuditCsvExportActivityV082.kt")) {
            assertTrue(file, src(file).contains("RegisterSafeAreaV141 {"))
        }
        assertFalse(src("TaxPaymentSettingsHubActivity.kt")
            .contains("Text(screenId, color = TpNavy"))
        assertFalse(src("CatalogHubActivityV030.kt")
            .contains("Text(screenId, color = CatalogHubBlueV030"))
        assertFalse(src("DynamicCatalogHubActivityV030.kt")
            .contains("Text(screenId, color = DynamicHubBlueV030"))
    }
}
