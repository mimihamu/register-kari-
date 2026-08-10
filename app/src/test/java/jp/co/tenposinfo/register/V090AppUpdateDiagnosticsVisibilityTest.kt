package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V090AppUpdateDiagnosticsVisibilityTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    @Test
    fun currentDatabaseHealthFailureHasHighestVisiblePriority() {
        val current = AppReleaseIdentityV088("0.90.0-dev.1", 120)
        val pending = PendingAppStartupV088(
            source = AppReleaseIdentityV088("0.89.0-dev.1", 119),
            target = current,
            startedAt = 10L,
            attemptCount = 2,
        )
        val snapshot = AppUpdateDiagnosticsSnapshotV090(
            current = current,
            lastSuccessful = AppReleaseIdentityV088("0.89.0-dev.1", 119),
            lastSuccessfulAt = 5L,
            pending = pending,
            incomplete = null,
            databaseHealthFailure = AppUpdateDatabaseHealthFailureEvidenceV089(
                target = current,
                checkedAt = 11L,
                summary = "DB-health=NG / integrity=NG",
            ),
        )
        assertEquals(AppUpdateOperationalStateV090.DB_HEALTH_BLOCKED, snapshot.state)
    }

    @Test
    fun pendingWithoutCurrentHealthFailureIsVisibleAsStartupPending() {
        val current = AppReleaseIdentityV088("0.90.0-dev.1", 120)
        val snapshot = AppUpdateDiagnosticsSnapshotV090(
            current = current,
            lastSuccessful = AppReleaseIdentityV088("0.89.0-dev.1", 119),
            lastSuccessfulAt = 5L,
            pending = PendingAppStartupV088(null, current, 10L, 1),
            incomplete = null,
            databaseHealthFailure = null,
        )
        assertEquals(AppUpdateOperationalStateV090.STARTUP_PENDING, snapshot.state)
    }

    @Test
    fun matchingLastSuccessfulVersionIsConfirmed() {
        val current = AppReleaseIdentityV088("0.90.0-dev.1", 120)
        val snapshot = AppUpdateDiagnosticsSnapshotV090(
            current = current,
            lastSuccessful = current,
            lastSuccessfulAt = 20L,
            pending = null,
            incomplete = null,
            databaseHealthFailure = null,
        )
        assertEquals(AppUpdateOperationalStateV090.SUCCESS_CONFIRMED, snapshot.state)
    }

    @Test
    fun diagnosticsReaderIsReadOnlyAndDoesNotClearEvidence() {
        val source = File(
            root,
            "app/src/main/java/jp/co/tenposinfo/register/AppUpdateDiagnosticsV090.kt",
        ).readText()
        assertTrue(source.contains("AppUpdateDatabaseHealthEvidenceV089.read(appContext)"))
        assertTrue(source.contains("app_update_transition_v088"))
        assertFalse(source.contains("AppUpdateDatabaseHealthEvidenceV089.clear"))
        assertFalse(source.contains(".edit()"))
        assertFalse(source.contains("writableDatabase"))
        assertFalse(source.contains("execSQL("))
        assertFalse(source.contains("DELETE FROM", ignoreCase = true))
        assertFalse(source.contains("UPDATE ", ignoreCase = true))
        assertFalse(source.contains("DROP TABLE", ignoreCase = true))
    }

    @Test
    fun scr767ShowsUpdateStateAndRefreshesItWithoutMutationControls() {
        val screen = File(
            root,
            "app/src/main/java/jp/co/tenposinfo/register/DataProtectionActivity.kt",
        ).readText()
        assertTrue(screen.contains("var updateDiagnostics by remember"))
        assertTrue(screen.contains("AppUpdateDiagnosticsV090.read(appContext)"))
        assertTrue(screen.contains("アプリ更新状態"))
        assertTrue(screen.contains("起動成功確定済み"))
        assertTrue(screen.contains("DB健全性NG・更新成功未確定"))
        assertTrue(screen.contains("起動成功確認中"))
        assertTrue(screen.contains("最終成功版"))
        assertTrue(screen.contains("起動試行"))
        assertFalse(screen.contains("更新状態を削除"))
        assertFalse(screen.contains("更新成功を強制"))
    }

    @Test
    fun v090DocumentationAndCumulativeTestsRemainPresent() {
        assertTrue(File(root, "docs/V0.90_APP_UPDATE_DIAGNOSTICS_VISIBILITY.md").isFile)
        assertTrue(File(root, "docs/V0.90_RELEASE_NOTES.md").isFile)
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        assertTrue(workflow.contains(":app:testDebugUnitTest"))
        assertTrue(workflow.contains(":customer-display:testDebugUnitTest"))
        assertTrue(workflow.contains(":management-app:testDebugUnitTest"))
    }
}