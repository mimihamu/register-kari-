package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V030BusinessStartNavigationTest {
    private fun source(name: String) = File("src/main/java/jp/co/tenposinfo/register/$name").readText()

    @Test
    fun caseA_salesAndZSettlementShowsBusinessStartNavigation() {
        val permissions = setOf(RegisterPermission.SALES, RegisterPermission.Z_SETTLEMENT)

        assertTrue(BusinessStartNavigationPolicyV030.canOpenBusinessStart(permissions))
        assertTrue(OperationsAccessPolicyV030.canEnter(permissions))
    }

    @Test
    fun caseB_xInspectionOnlyDoesNotShowBusinessStartNavigation() {
        val permissions = setOf(RegisterPermission.SALES, RegisterPermission.X_INSPECTION)

        assertFalse(BusinessStartNavigationPolicyV030.canOpenBusinessStart(permissions))
        assertTrue(OperationsAccessPolicyV030.canEnter(permissions))
    }

    @Test
    fun caseC_settingsWithoutZSettlementDoesNotShowBusinessStartNavigation() {
        val permissions = setOf(RegisterPermission.SALES, RegisterPermission.SETTINGS)

        assertFalse(BusinessStartNavigationPolicyV030.canOpenBusinessStart(permissions))
        assertFalse(OperationsAccessPolicyV030.canEnter(permissions))
    }

    @Test
    fun caseD_legacySettlementIsExpandedBeforeUiPolicyEvaluation() {
        val expanded = RegisterPermissionCompatibilityV026.expand(
            setOf(RegisterPermission.SALES, RegisterPermission.SETTLEMENT),
        )

        assertTrue(RegisterPermission.X_INSPECTION in expanded)
        assertTrue(RegisterPermission.Z_SETTLEMENT in expanded)
        assertTrue(BusinessStartNavigationPolicyV030.canOpenBusinessStart(expanded))
    }

    @Test
    fun managementPolicyIncludesSplitInspectionAndSettlementPermissions() {
        assertTrue(ManagementNavigationPolicyV030.canOpenManagement(setOf(RegisterPermission.X_INSPECTION)))
        assertTrue(ManagementNavigationPolicyV030.canOpenManagement(setOf(RegisterPermission.Z_SETTLEMENT)))
        assertTrue(ManagementNavigationPolicyV030.canOpenManagement(setOf(RegisterPermission.VIEW_SALES)))
        assertFalse(ManagementNavigationPolicyV030.canOpenManagement(setOf(RegisterPermission.SETTINGS)))
    }

    @Test
    fun businessStartIntentContractUsesExplicitValue() {
        assertTrue(
            OperationsNavigationContractV030.requestsBusinessStart(
                OperationsNavigationContractV030.OPEN_BUSINESS_START,
            ),
        )
        assertFalse(OperationsNavigationContractV030.requestsBusinessStart(null))
        assertEquals(
            "OPEN_BUSINESS_START",
            OperationsNavigationContractV030.OPEN_BUSINESS_START,
        )
    }

    @Test
    fun registerApplicationDoesNotUseLegacySettlementForNewUiOrAccessChecks() {
        val application = source("RegisterApplication.kt")

        assertFalse(application.contains("RegisterPermission.SETTLEMENT"))
        assertTrue(application.contains("BusinessStartNavigationPolicyV030.canOpenBusinessStart"))
        assertTrue(application.contains("ManagementNavigationPolicyV030.canOpenManagement"))
        assertTrue(application.contains("OperationsAccessPolicyV030.canEnter"))
        assertTrue(application.contains("OperationsNavigationContractV030.OPEN_BUSINESS_START"))
    }
    @Test
    fun legacySettlementIsOnlyReferencedByCompatibilityMigration() {
        val sourceRoot = File("src/main/java/jp/co/tenposinfo/register")
        val references = sourceRoot.listFiles()
            .orEmpty()
            .filter { it.extension == "kt" && it.readText().contains("RegisterPermission.SETTLEMENT") }
            .map(File::getName)
            .sorted()

        assertEquals(listOf("SettlementPreflightV026.kt"), references)
        assertFalse(source("SecureOperationsCoordinator.kt").contains("OperationsAction.SETTLEMENT"))
    }

    @Test
    fun salesScreenManagementEntryUsesSharedPolicyAndResponsiveHub() {
        val main = source("MainActivity.kt")

        assertTrue(main.contains("ManagementNavigationPolicyV030::canOpenManagement"))
        assertTrue(main.contains("OperationsHubActivityV030::class.java"))
        assertFalse(main.contains("RegisterPermission.SETTLEMENT"))
    }

}
