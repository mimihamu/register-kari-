package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V049GoogleDrivePlusSetupDiagnosticsTest {
    @Test
    fun plusExposesSetupGuideAndDiagnosticsFromDriveAccountScreen() {
        val root = File("src/main/java/jp/co/tenposinfo/register/plus")
        val account = File(root, "GoogleDriveAccountActivity.kt").readText()
        val setup = File(root, "GoogleDriveSetupGuideActivity.kt").readText()
        val diagnostics = File(root, "GoogleDriveDiagnosticsActivity.kt").readText()
        val folderScreen = File(root, "ManagementFolderSyncScreen.kt").readText()
        val easyConnect = File(root, "GoogleDriveEasyConnectActivity.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File("../.github/workflows/build-apk.yml").readText()
        val docs = File("../docs/V0.49_TSUGUREGI_PLUS_DRIVE_SETUP_DIAGNOSTICS.md").readText()

        assertTrue(account.contains("GoogleDriveSetupGuideActivity::class.java"))
        assertTrue(account.contains("GoogleDriveDiagnosticsActivity::class.java"))
        assertTrue(account.contains("初期設定ガイド"))
        assertTrue(account.contains("診断・ログ"))
        assertTrue(folderScreen.contains("Googleかんたん接続"))
        assertTrue(easyConnect.contains("GoogleDriveRecoveryActivity::class.java"))
        assertTrue(easyConnect.contains("保守・復旧"))

        assertTrue(setup.contains("GET_SIGNING_CERTIFICATES"))
        assertTrue(setup.contains("applicationId（パッケージ名）"))
        assertTrue(setup.contains("署名SHA-1"))
        assertTrue(setup.contains("別々のAndroid OAuthクライアント"))
        assertTrue(setup.contains("Google Cloud Consoleを開く"))

        assertTrue(diagnostics.contains("GoogleApiAvailability"))
        assertTrue(diagnostics.contains("ConnectivityManager"))
        assertTrue(diagnostics.contains("ManagementDatabase"))
        assertTrue(diagnostics.contains("drive_sync_files"))
        assertTrue(diagnostics.contains("import_rejections"))
        assertTrue(diagnostics.contains("Intent.ACTION_SEND"))
        assertTrue(diagnostics.contains("REDACTED_TOKEN"))
        assertFalse(diagnostics.contains("putString(\"access_token\""))
        assertFalse(diagnostics.contains("putString(\"refresh_token\""))

        assertTrue(manifest.contains("android:name=\".GoogleDriveSetupGuideActivity\""))
        assertTrue(manifest.contains("android:name=\".GoogleDriveDiagnosticsActivity\""))
        assertTrue(manifest.contains("android:name=\".GoogleDriveEasyConnectActivity\""))
        assertTrue(manifest.contains("android:screenOrientation=\"sensorPortrait\""))
        assertTrue(build.contains("versionCode = 14"))
        assertTrue(build.contains("versionName = \"0.14.0-dev.1\""))
        assertTrue(workflow.contains("V049GoogleDrivePlusSetupDiagnosticsTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_PLUS_v0.14.0_dev1_sync_operations_dashboard_debug.apk"))
        assertTrue(workflow.contains("TSUGUREGI-v0.57.0-dev1-held-ticket-operations-ui-apks"))
        assertTrue(docs.contains("つぐレジ＋用のAndroid OAuthクライアント"))
        assertFalse(File("../tools/v049_apply.py").exists())
        assertFalse(File("../.github/workflows/v049-apply-temp.yml").exists())
        assertFalse(File("../tools/build-apk-v049.generated.yml").exists())
    }
}