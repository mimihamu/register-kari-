package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V052GoogleDriveOperationsSetupTest {
    @Test
    fun automaticModePrefersVerifiedDriveApiThenReadyFolder() {
        val direct = GoogleDriveOperationsSnapshot(
            accountConnected = true,
            connectionTestStatus = GoogleDriveConnectionTestStatus.SUCCEEDED,
            folderStatus = DriveConnectionStatus.READY,
            selectedMode = GoogleDriveOperatingMode.AUTOMATIC,
            directAutoSyncEnabled = false,
            folderAutoImportEnabled = true,
        )
        assertEquals(
            GoogleDriveResolvedMode.DRIVE_API,
            GoogleDriveOperationsPolicy.resolve(direct),
        )

        val fallback = direct.copy(
            connectionTestStatus = GoogleDriveConnectionTestStatus.NOT_FOUND,
        )
        assertEquals(
            GoogleDriveResolvedMode.COMPATIBILITY_FOLDER,
            GoogleDriveOperationsPolicy.resolve(fallback),
        )

        val undecided = fallback.copy(folderStatus = DriveConnectionStatus.NOT_REGISTERED)
        assertEquals(
            GoogleDriveResolvedMode.UNDECIDED,
            GoogleDriveOperationsPolicy.resolve(undecided),
        )
    }

    @Test
    fun fixedModesRequireTheirOwnVerifiedTransport() {
        val direct = GoogleDriveOperationsSnapshot(
            accountConnected = false,
            connectionTestStatus = GoogleDriveConnectionTestStatus.NOT_RUN,
            folderStatus = DriveConnectionStatus.READY,
            selectedMode = GoogleDriveOperatingMode.DRIVE_API,
            directAutoSyncEnabled = false,
            folderAutoImportEnabled = true,
        )
        assertEquals(
            GoogleDriveResolvedMode.UNDECIDED,
            GoogleDriveOperationsPolicy.resolve(direct),
        )
        assertTrue(GoogleDriveOperationsPolicy.nextAction(direct).contains("Googleアカウント"))

        val folder = direct.copy(
            selectedMode = GoogleDriveOperatingMode.COMPATIBILITY_FOLDER,
        )
        assertEquals(
            GoogleDriveResolvedMode.COMPATIBILITY_FOLDER,
            GoogleDriveOperationsPolicy.resolve(folder),
        )
    }

    @Test
    fun operationsScreenWiresSafeApplyAndValidationChecklist() {
        val root = File("..")
        val sourceRoot = File("src/main/java/jp/co/tenposinfo/register/plus")
        val operations = File(sourceRoot, "GoogleDriveOperations.kt").readText()
        val recovery = File(sourceRoot, "GoogleDriveRecoveryActivity.kt").readText()
        val account = File(sourceRoot, "GoogleDriveAccountActivity.kt").readText()
        val folderScreen = File(sourceRoot, "ManagementFolderSyncScreen.kt").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        val docs = File(root, "docs/V0.52_GOOGLE_DRIVE_OPERATIONS_SETUP.md").readText()
        val notes = File(root, "docs/V0.52_RELEASE_NOTES.md").readText()

        for (token in listOf(
            "GoogleDriveOperatingMode",
            "GoogleDriveResolvedMode",
            "GoogleDriveOperationsSnapshot",
            "GoogleDriveOperationsPolicy",
            "GoogleDriveOperatingModeStore",
            "GoogleDriveValidationChecklistStore",
            "currentConfigurationHealthy",
        )) {
            assertTrue(operations.contains(token))
        }

        for (token in listOf(
            "Google Drive運用セットアップ・復旧",
            "推奨設定を一括適用",
            "DriveSyncPreferences(applicationContext).setAutoImportOnLaunch(false)",
            "GoogleDriveDirectSyncScheduler.setAutomaticSyncEnabled(applicationContext, enabled = true)",
            "GoogleDriveDirectSyncScheduler.setAutomaticSyncEnabled(applicationContext, enabled = false)",
            "実機確認チェック",
            "端末再起動後も同期方式と権限を維持",
        )) {
            assertTrue(recovery.contains(token))
        }

        assertTrue(account.contains("運用セットアップ・復旧"))
        assertTrue(folderScreen.contains("Google Drive運用設定・診断"))
        assertTrue(build.contains("versionCode = 11"))
        assertTrue(build.contains("versionName = \"0.11.0-dev.1\""))
        assertTrue(workflow.contains("V052GoogleDriveOperationsSetupTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_PLUS_v0.11.0_dev1_drive_operations_setup_debug.apk"))
        assertTrue(workflow.contains("TSUGUREGI-v0.52.0-dev1-drive-operations-setup-apks"))
        assertTrue(docs.contains("自動選択"))
        assertTrue(docs.contains("同時に自動実行しない"))
        assertTrue(notes.contains("0.52.0-dev.1"))
        assertFalse(operations.contains("putString(\"access_token\""))
        assertFalse(operations.contains("putString(\"refresh_token\""))
        assertFalse(recovery.contains("putString(\"access_token\""))
        assertFalse(recovery.contains("putString(\"refresh_token\""))
    }
}
