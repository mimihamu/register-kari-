package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V089AppUpdateDatabaseHealthGateTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    @Test
    fun healthyRequiresIntegrityForeignKeysAndRequiredSchema() {
        val healthy = AppUpdateDatabaseHealthV089(
            checkedAt = 1L,
            userVersion = 4,
            integrityOk = true,
            foreignKeyViolationCount = 0,
            missingTables = emptySet(),
            missingColumns = emptySet(),
        )
        assertTrue(healthy.healthy)

        assertFalse(healthy.copy(integrityOk = false).healthy)
        assertFalse(healthy.copy(foreignKeyViolationCount = 1).healthy)
        assertFalse(healthy.copy(missingTables = setOf("sales")).healthy)
        assertFalse(healthy.copy(missingColumns = setOf("sales.business_date")).healthy)
        assertFalse(healthy.copy(errorType = "SQLiteException").healthy)
    }

    @Test
    fun registeredV089ProviderReplacesV088AndKeepsStartupOrder() {
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:name=\".DataRestoreBootstrapProviderV086\""))
        assertTrue(manifest.contains("android:name=\".AppUpdateTransitionBootstrapProviderV089\""))
        assertFalse(manifest.contains("android:name=\".AppUpdateTransitionBootstrapProviderV088\""))
        val restoreIndex = manifest.indexOf(".DataRestoreBootstrapProviderV086")
        val updateIndex = manifest.indexOf(".AppUpdateTransitionBootstrapProviderV089")
        val catalogIndex = manifest.indexOf(".CatalogBootstrapProvider")
        assertTrue(restoreIndex in 0 until updateIndex)
        assertTrue(updateIndex in 0 until catalogIndex)
        assertTrue(manifest.contains("android:initOrder=\"1000\""))
        assertTrue(manifest.contains("android:initOrder=\"900\""))
    }

    @Test
    fun databaseHealthGateRunsBeforeAnySuccessAudit() {
        val source = File(
            root,
            "app/src/main/java/jp/co/tenposinfo/register/AppUpdateTransitionV089.kt",
        ).readText()
        val inspectIndex = source.indexOf("AppUpdateDatabaseHealthCheckV089.inspect(appContext)")
        val unhealthyIndex = source.indexOf("if (!health.healthy)")
        val successAuditIndex = source.indexOf("APP_UPDATE_STARTUP_SUCCEEDED")
        assertTrue(inspectIndex >= 0)
        assertTrue(unhealthyIndex > inspectIndex)
        assertTrue(successAuditIndex > unhealthyIndex)
        assertTrue(source.contains("AppUpdateDatabaseHealthEvidenceV089.recordFailure"))
        assertTrue(source.contains("return\n        }"))
        assertTrue(source.contains("APP_UPDATE_STARTUP_DB_HEALTH_RECOVERED"))
    }

    @Test
    fun healthCheckIsReadOnlyAndDoesNotWriteUnhealthyDatabase() {
        val source = File(
            root,
            "app/src/main/java/jp/co/tenposinfo/register/AppUpdateTransitionV089.kt",
        ).readText()
        assertTrue(source.contains("SQLiteDatabase.OPEN_READONLY"))
        assertTrue(source.contains("PRAGMA integrity_check"))
        assertTrue(source.contains("PRAGMA foreign_key_check"))
        assertTrue(source.contains("SELECT name FROM sqlite_master"))
        assertTrue(source.contains("PRAGMA table_info"))
        assertTrue(source.contains("不健全DBにはoperation_auditも書かない"))

        assertFalse(source.contains("writableDatabase"))
        assertFalse(source.contains("execSQL("))
        assertFalse(source.contains("DELETE FROM", ignoreCase = true))
        assertFalse(source.contains("UPDATE sales", ignoreCase = true))
        assertFalse(source.contains("DROP TABLE", ignoreCase = true))
        assertFalse(source.contains("ALTER TABLE", ignoreCase = true))
    }

    @Test
    fun v088LedgerKeysAreReusedForCumulativeUpgradeContinuity() {
        val v088 = File(root, "app/src/main/java/jp/co/tenposinfo/register/AppUpdateTransitionV088.kt").readText()
        val v089 = File(root, "app/src/main/java/jp/co/tenposinfo/register/AppUpdateTransitionV089.kt").readText()
        assertTrue(v088.contains("app_update_transition_v088"))
        assertTrue(v089.contains("app_update_transition_v088"))
        assertTrue(v089.contains("last_success_name"))
        assertTrue(v089.contains("pending_target_name"))
        assertTrue(v089.contains("incomplete_target_name"))
    }

    @Test
    fun healthFailureEvidenceIsOutsideBusinessDatabaseAndSanitized() {
        val source = File(
            root,
            "app/src/main/java/jp/co/tenposinfo/register/AppUpdateTransitionV089.kt",
        ).readText()
        assertTrue(source.contains("app_update_database_health_v089"))
        assertTrue(source.contains("error.javaClass.simpleName"))
        assertTrue(source.contains("health.auditSummary().take(1000)"))
        assertFalse(source.contains("databaseFile.absolutePath" + "}"))
    }

    @Test
    fun documentationAndCumulativeWorkflowArePresent() {
        assertTrue(File(root, "docs/V0.89_APP_UPDATE_DATABASE_HEALTH_GATE.md").isFile)
        assertTrue(File(root, "docs/V0.89_RELEASE_NOTES.md").isFile)
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        assertTrue(workflow.contains(":app:testDebugUnitTest"))
        assertTrue(workflow.contains(":customer-display:testDebugUnitTest"))
        assertTrue(workflow.contains(":management-app:testDebugUnitTest"))
    }
}
