package jp.co.tenposinfo.register

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V054GoogleEasyConnectTest {
    @Test
    fun registerEasyConnectAutomatesNormalDriveSetup() {
        val root = File("..")
        val source = File("src/main/java/jp/co/tenposinfo/register/GoogleDriveEasyConnectActivity.kt").readText()
        val settings = File("src/main/java/jp/co/tenposinfo/register/SyncSettingsActivity.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        val docs = File(root, "docs/V0.54_GOOGLE_EASY_CONNECT.md").readText()

        assertTrue(build.contains("versionCode = 108"))
        assertTrue(build.contains("versionName = \"0.78.0-dev.1\""))
        assertTrue(source.contains("AuthorizationRequest.Prompt.SELECT_ACCOUNT"))
        assertTrue(source.contains("GoogleDriveConnectionTestCoordinator(applicationContext)"))
        assertTrue(source.contains("createOrUpdate(accessToken)"))
        assertTrue(source.contains("GoogleDriveDirectUploadScheduler.ensurePeriodic"))
        assertTrue(source.contains("JournalOutboxStore(applicationContext).use { it.stagePending(500) }"))
        assertTrue(source.contains("Googleと連携"))
        assertTrue(source.contains("保守・復旧"))
        assertTrue(settings.contains("GoogleDriveEasyConnectActivity::class.java"))
        assertTrue(settings.contains("Googleかんたん接続"))
        assertTrue(manifest.contains(".GoogleDriveEasyConnectActivity"))
        assertTrue(workflow.contains("TSUGUREGI_v0.78.0_dev1_settlement_reconciliation_debug.apk"))
        assertTrue(docs.contains("アカウントを選ぶだけ"))
        assertFalse(source.contains("putString(\"access_token\""))
        assertFalse(source.contains("putString(\"refresh_token\""))
    }
}
