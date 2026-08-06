package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V053GoogleDriveSyncVerificationSourceTest {
    @Test
    fun verificationCenterIsWiredWithoutPersistingSecrets() {
        val root = File("..")
        val build = File("build.gradle.kts").readText()
        val source = File("src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveSyncVerificationActivity.kt").readText()
        val recovery = File("src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveRecoveryActivity.kt").readText()
        val direct = File("src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveDirectSync.kt").readText()
        val easyConnect = File("src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveEasyConnectActivity.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()

        assertTrue(build.contains("versionCode = 13"))
        assertTrue(build.contains("versionName = \"0.13.0-dev.1\""))
        assertTrue(source.contains("GoogleDriveSyncVerificationPolicy"))
        assertTrue(source.contains("CompatibilityFolderSyncRunner"))
        assertTrue(source.contains("現在の方式で差分同期を実行"))
        assertTrue(source.contains("自動設定を修復"))
        assertTrue(source.contains("Intent.ACTION_SEND"))
        assertTrue(source.contains("MAX_HISTORY = 20"))
        assertTrue(recovery.contains("売上同期検証・復旧"))
        assertTrue(easyConnect.contains("GoogleDriveRecoveryActivity::class.java"))
        assertTrue(easyConnect.contains("保守・復旧"))
        assertTrue(manifest.contains("GoogleDriveSyncVerificationActivity"))
        assertTrue(manifest.contains("GoogleDriveEasyConnectActivity"))
        assertTrue(direct.contains("lastFailureCategory"))
        assertTrue(direct.contains("recoverStaleRun"))
        assertFalse(source.contains("putString(\"access_token\""))
        assertFalse(source.contains("putString(\"refresh_token\""))
        assertTrue(workflow.contains("TSUGUREGI_PLUS_v0.13.0_dev1_google_easy_connect_debug.apk"))
        assertFalse(File(root, "tools/v053_apply.py").exists())
        assertFalse(File(root, ".github/workflows/v053-apply-temp.yml").exists())
    }
}