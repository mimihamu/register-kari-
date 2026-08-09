package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V043DriveConnectionTest {
    @Test
    fun autoImportRequiresReadyConnectionAndCooldown() {
        val now = 1_000_000L
        assertTrue(
            DriveConnectionPolicy.shouldAutoImport(
                enabled = true,
                status = DriveConnectionStatus.READY,
                lastStartedAt = 0L,
                now = now,
            ),
        )
        assertFalse(
            DriveConnectionPolicy.shouldAutoImport(
                enabled = false,
                status = DriveConnectionStatus.READY,
                lastStartedAt = 0L,
                now = now,
            ),
        )
        assertFalse(
            DriveConnectionPolicy.shouldAutoImport(
                enabled = true,
                status = DriveConnectionStatus.PERMISSION_MISSING,
                lastStartedAt = 0L,
                now = now,
            ),
        )
        assertFalse(
            DriveConnectionPolicy.shouldAutoImport(
                enabled = true,
                status = DriveConnectionStatus.READY,
                lastStartedAt = now - DriveConnectionPolicy.AUTO_IMPORT_COOLDOWN_MS + 1,
                now = now,
            ),
        )
        assertTrue(
            DriveConnectionPolicy.shouldAutoImport(
                enabled = true,
                status = DriveConnectionStatus.READY,
                lastStartedAt = now - DriveConnectionPolicy.AUTO_IMPORT_COOLDOWN_MS,
                now = now,
            ),
        )
    }

    @Test
    fun inspectorUiDocsAndWorkflowStayConnected() {
        val root = File("..")
        val sourceRoot = File("src/main/java/jp/co/tenposinfo/register/plus")
        val connection = File(sourceRoot, "DriveConnection.kt").readText()
        val screen = File(sourceRoot, "ManagementFolderSyncScreen.kt").readText()
        val folderSource = File(sourceRoot, "ImportFolderSource.kt").readText()
        val plusBuild = File("build.gradle.kts").readText()
        val registerBuild = File(root, "app/build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        val docs = File(root, "docs/V0.43_GOOGLE_DRIVE_CONNECTION.md").readText()
        val notes = File(root, "docs/V0.43_RELEASE_NOTES.md").readText()

        for (token in listOf(
            "DriveConnectionStatus",
            "PERMISSION_MISSING",
            "PROVIDER_UNAVAILABLE",
            "READ_FAILED",
            "persistedUriPermissions",
            "resolveContentProvider",
            "DocumentsContract.getTreeDocumentId",
            "AUTO_IMPORT_COOLDOWN_MS",
            "DriveSyncPreferences",
        )) assertTrue(connection.contains(token))

        assertTrue(folderSource.contains("takePersistableUriPermission"))
        assertTrue(screen.contains("接続診断"))
        assertTrue(screen.contains("永続読取権限"))
        assertTrue(screen.contains("起動時に差分取込"))
        assertTrue(screen.contains("フォルダ方式は互換用"))
        assertTrue(screen.contains("DriveConnectionPolicy.shouldAutoImport"))

        assertTrue(registerBuild.contains("versionCode = 108"))
        assertTrue(registerBuild.contains("versionName = \"0.78.0-dev.1\""))
        assertTrue(plusBuild.contains("versionCode = 14"))
        assertTrue(plusBuild.contains("versionName = \"0.14.0-dev.1\""))
        assertTrue(workflow.contains(":management-app:testDebugUnitTest"))
        assertTrue(workflow.contains(":management-app:assembleDebug"))
        assertTrue(workflow.contains("TSUGUREGI_PLUS_v0.14.0_dev1_sync_operations_dashboard_debug.apk"))
        assertTrue(docs.contains("Storage Access Framework"))
        assertTrue(docs.contains("Drive REST API"))
        assertTrue(notes.contains("接続診断"))
        assertFalse(File(root, "tools/v043_apply.py").exists())
        assertFalse(File(root, ".github/workflows/v043-apply.yml").exists())
    }
}