package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V051GoogleDriveRecoveryFallbackTest {
    @Test
    fun recommendationUsesFolderWhenDirectTestCannotBeFound() {
        assertEquals(
            GoogleDriveRecoveryRecommendation.USE_COMPATIBILITY_FOLDER,
            GoogleDriveRecoveryPolicy.recommend(
                GoogleDriveConnectionTestStatus.NOT_FOUND,
                DriveConnectionStatus.NOT_REGISTERED,
            ),
        )
        assertEquals(
            GoogleDriveRecoveryRecommendation.COMPATIBILITY_FOLDER_READY,
            GoogleDriveRecoveryPolicy.recommend(
                GoogleDriveConnectionTestStatus.NOT_FOUND,
                DriveConnectionStatus.READY,
            ),
        )
        assertEquals(
            GoogleDriveRecoveryRecommendation.DIRECT_API_READY,
            GoogleDriveRecoveryPolicy.recommend(
                GoogleDriveConnectionTestStatus.SUCCEEDED,
                DriveConnectionStatus.NOT_REGISTERED,
            ),
        )
    }

    @Test
    fun recoveryFlowPersistsFolderAndStopsAutomaticDirectSync() {
        val root = File("..")
        val sourceRoot = File("src/main/java/jp/co/tenposinfo/register/plus")
        val recovery = File(sourceRoot, "GoogleDriveRecoveryActivity.kt").readText()
        val directSync = File(sourceRoot, "GoogleDriveDirectSync.kt").readText()
        val account = File(sourceRoot, "GoogleDriveAccountActivity.kt").readText()
        val easyConnect = File(sourceRoot, "GoogleDriveEasyConnectActivity.kt").readText()
        val folderScreen = File(sourceRoot, "ManagementFolderSyncScreen.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val plusBuild = File("build.gradle.kts").readText()
        val registerBuild = File(root, "app/build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        val docs = File(root, "docs/V0.51_GOOGLE_DRIVE_RECOVERY_FALLBACK.md").readText()

        for (token in listOf(
            "ActivityResultContracts.OpenDocumentTree",
            "persistFolderPermission",
            "ImportFolderPreferences",
            "DriveConnectionInspector",
            "DriveSyncPreferences(applicationContext).setAutoImportOnLaunch(true)",
            "GoogleDriveDirectSyncStatusStore(applicationContext).setAutoSyncOnLaunch(false)",
            "GoogleDriveDirectSyncScheduler.setAutomaticSyncEnabled",
            "connection.status == DriveConnectionStatus.READY",
            "同じGoogle Driveフォルダを選択",
            "Googleアカウント連携、取込済み売上",
        )) assertTrue(recovery.contains(token))

        assertTrue(directSync.contains("fun setAutomaticSyncEnabled"))
        assertTrue(directSync.contains("cancelUniqueWork(PERIODIC_NAME)"))
        assertTrue(directSync.contains("cancelUniqueWork(STARTUP_NAME)"))
        assertTrue(account.contains("GoogleDriveRecoveryActivity::class.java"))
        assertTrue(account.contains("運用セットアップ・復旧"))
        assertTrue(easyConnect.contains("GoogleDriveRecoveryActivity::class.java"))
        assertTrue(easyConnect.contains("保守・復旧"))
        assertTrue(folderScreen.contains("Drive APIで取得できない場合の復旧経路"))
        assertTrue(manifest.contains("android:name=\".GoogleDriveRecoveryActivity\""))
        assertTrue(manifest.contains("android:screenOrientation=\"sensorPortrait\""))
        assertTrue(registerBuild.contains("applicationId = \"jp.co.tenposinfo.register\""))
        assertTrue(registerBuild.contains("compileSdk = 36"))
        assertTrue(plusBuild.contains("versionCode = 14"))
        assertTrue(plusBuild.contains("versionName = \"0.14.0-dev.1\""))
        assertTrue(workflow.contains(":management-app:testDebugUnitTest"))
        assertTrue(workflow.contains("TSUGUREGI_PLUS_v0.14.0_dev1_sync_operations_dashboard_debug.apk"))
        assertTrue(docs.contains("Drive API自動同期を停止"))
        assertFalse(recovery.contains("putString(\"access_token\""))
        assertFalse(recovery.contains("putString(\"refresh_token\""))
        assertFalse(recovery.contains("treeUri = Text"))
        assertFalse(File(root, "tools/v051_apply.py").exists())
        assertFalse(File(root, ".github/workflows/v051-apply-temp.yml").exists())
        assertFalse(File(root, "tools/build-apk-v051.generated.yml").exists())
    }
}
