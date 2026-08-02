package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V026SettlementPreflightPermissionTest {
    @Test
    fun heldTicketsAlwaysBlockZSettlement() {
        val result = ZSettlementPreflightPolicy.evaluate(
            heldTickets = 2,
            pendingPrints = 0,
            pendingPrintsAcknowledged = true,
        )
        assertFalse(result.mayProceed)
        assertTrue(result.message.orEmpty().contains("未会計伝票が2件"))
    }

    @Test
    fun pendingPrintsRequireExplicitAcknowledgement() {
        val blocked = ZSettlementPreflightPolicy.evaluate(
            heldTickets = 0,
            pendingPrints = 3,
            pendingPrintsAcknowledged = false,
        )
        val approved = ZSettlementPreflightPolicy.evaluate(
            heldTickets = 0,
            pendingPrints = 3,
            pendingPrintsAcknowledged = true,
        )
        assertFalse(blocked.mayProceed)
        assertTrue(blocked.requiresPendingPrintAcknowledgement)
        assertTrue(approved.mayProceed)
        assertTrue(approved.requiresPendingPrintAcknowledgement)
    }

    @Test
    fun noPendingWorkAllowsSettlement() {
        val result = ZSettlementPreflightPolicy.evaluate(0, 0, false)
        assertTrue(result.mayProceed)
        assertFalse(result.requiresPendingPrintAcknowledgement)
    }

    @Test
    fun legacySettlementPermissionExpandsToBothNewPermissions() {
        val expanded = RegisterPermissionCompatibilityV026.expand(
            setOf(RegisterPermission.SETTLEMENT),
        )
        assertTrue(RegisterPermission.X_INSPECTION in expanded)
        assertTrue(RegisterPermission.Z_SETTLEMENT in expanded)
    }

    @Test
    fun legacyPermissionIsNotSavedOrDisplayed() {
        val normalized = RegisterPermissionCompatibilityV026.normalizeForSave(
            setOf(
                RegisterPermission.SETTLEMENT,
                RegisterPermission.X_INSPECTION,
                RegisterPermission.Z_SETTLEMENT,
            ),
        )
        assertFalse(RegisterPermission.SETTLEMENT in normalized)
        assertFalse(RegisterPermission.SETTLEMENT in RegisterPermissionCompatibilityV026.selectablePermissions)
    }

    @Test
    fun uiAndStoreBothEnforcePreflight() {
        val activity = File("src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt").readText()
        val store = File("src/main/java/jp/co/tenposinfo/register/OperationsStore.kt").readText()
        val secure = File("src/main/java/jp/co/tenposinfo/register/SecureOperationsCoordinator.kt").readText()

        assertTrue(activity.contains("pendingPrintsAcknowledged"))
        assertTrue(activity.contains("未会計伝票があるためZ精算は禁止されています"))
        assertTrue(activity.contains("未印刷のまま精算する"))
        assertTrue(store.contains("ZSettlementPreflightPolicy.evaluate"))
        assertTrue(store.contains("Z_SETTLEMENT_PENDING_PRINTS_ACKNOWLEDGED"))
        assertTrue(secure.contains("OperationsAction.X_INSPECTION"))
        assertTrue(secure.contains("OperationsAction.Z_SETTLEMENT"))
    }
}
