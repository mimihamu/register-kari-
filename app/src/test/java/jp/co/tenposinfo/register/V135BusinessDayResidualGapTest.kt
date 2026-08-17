package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V135BusinessDayResidualGapTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    private fun source(name: String): String = File(
        root,
        "app/src/main/java/jp/co/tenposinfo/register/$name",
    ).readText()

    @Test
    fun businessStartPermissionIsIndependentFromZSettlement() {
        assertTrue(BusinessStartNavigationPolicyV030.canOpenBusinessStart(setOf(RegisterPermission.BUSINESS_START)))
        assertFalse(BusinessStartNavigationPolicyV030.canOpenBusinessStart(setOf(RegisterPermission.Z_SETTLEMENT)))
        val secure = source("SecureOperationsCoordinator.kt")
        assertTrue(secure.contains("BUSINESS_START(RegisterPermission.BUSINESS_START"))
        assertTrue(secure.contains("requireOperator(OperationsAction.BUSINESS_START)"))
    }

    @Test
    fun legacySettlementCompatibilityIncludesBusinessStartButExplicitZDoesNot() {
        val legacy = RegisterPermissionCompatibilityV026.expand(setOf(RegisterPermission.SETTLEMENT))
        val explicitZ = RegisterPermissionCompatibilityV026.expand(setOf(RegisterPermission.Z_SETTLEMENT))
        assertTrue(RegisterPermission.BUSINESS_START in legacy)
        assertFalse(RegisterPermission.BUSINESS_START in explicitZ)
        val settings = source("AdminSettingsStore.kt")
        assertTrue(settings.contains("BUSINESS_START_FROM_Z_V135"))
        assertTrue(settings.contains("register_permission_migrations_v135"))
    }

    @Test
    fun lifecyclePreventsDoubleOpenAndOnlyZCloses() {
        assertFalse(BusinessSessionLifecyclePolicy.mayStart(BusinessSessionStatus.OPEN))
        assertTrue(BusinessSessionLifecyclePolicy.mayStart(BusinessSessionStatus.CLOSED))
        assertTrue(BusinessSessionLifecyclePolicy.mayStart(null))
        assertEquals(BusinessSessionStatus.OPEN, BusinessSessionLifecyclePolicy.resultStatus(SettlementReportType.X_INSPECTION, BusinessSessionStatus.OPEN))
        assertEquals(BusinessSessionStatus.CLOSED, BusinessSessionLifecyclePolicy.resultStatus(SettlementReportType.Z_SETTLEMENT, BusinessSessionStatus.OPEN))
        assertTrue(runCatching { BusinessSessionLifecyclePolicy.resultStatus(SettlementReportType.Z_SETTLEMENT, BusinessSessionStatus.CLOSED) }.isFailure)
        assertTrue(source("BusinessSessionV024.kt").contains("idx_business_sessions_single_active"))
    }

    @Test
    fun canonicalBackupFailureAcknowledgementEventIsUsedForNewWrites() {
        val store = source("OperationsStore.kt")
        assertTrue(store.contains("Z_SETTLEMENT_BACKUP_FAILURE_ACK"))
        assertFalse(store.contains("Z_SETTLEMENT_BACKUP_FAILURE_ACKNOWLEDGED"))
    }

    @Test
    fun heldTicketListLoadsAndDisplaysPersistedGuestCount() {
        val database = source("RegisterDatabase.kt")
        val main = source("MainActivity.kt")
        assertTrue(database.contains("val guestCount: Int = 0"))
        assertTrue(database.contains("held_ticket_guest_count_v135 g"))
        assertTrue(database.contains("guestCount = cursor.getInt(5)"))
        assertTrue(main.contains("ticket.guestCount"))
    }
}
