package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V054GoogleEasyConnectTest {
    @Test
    fun plusEasyConnectSelectsOneSafeAutomaticRoute() {
        val root = File("..")
        val source = File("src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveEasyConnectActivity.kt").readText()
        val entry = File("src/main/java/jp/co/tenposinfo/register/plus/ManagementFolderSyncScreen.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        val docs = File(root, "docs/V0.54_GOOGLE_EASY_CONNECT.md").readText()

        assertTrue(build.contains("versionCode = 13"))
        assertTrue(build.contains("versionName = \"0.13.0-dev.1\""))
        assertTrue(source.contains("AuthorizationRequest.Prompt.SELECT_ACCOUNT"))
        assertTrue(source.contains("GoogleDriveOperatingMode.DRIVE_API"))
        assertTrue(source.contains("setAutoImportOnLaunch(false)"))
        assertTrue(source.contains("setAutoSyncOnLaunch(true)"))
        assertTrue(source.contains("setAutomaticSyncEnabled(applicationContext, enabled = true)"))
        assertTrue(source.contains("searchAndVerify(accessToken)"))
        assertTrue(source.contains("repository.synchronize(accessToken, forceReimport = false)"))
        assertTrue(source.contains("Googleと連携"))
        assertTrue(source.contains("保守・復旧"))
        assertTrue(entry.contains("GoogleDriveEasyConnectActivity::class.java"))
        assertTrue(entry.contains("Googleかんたん接続"))
        assertTrue(manifest.contains(".GoogleDriveEasyConnectActivity"))
        assertTrue(workflow.contains("TSUGUREGI_PLUS_v0.13.0_dev1_google_easy_connect_debug.apk"))
        assertTrue(docs.contains("互換フォルダ"))
        assertFalse(source.contains("putString(\"access_token\""))
        assertFalse(source.contains("putString(\"refresh_token\""))
    }
}
