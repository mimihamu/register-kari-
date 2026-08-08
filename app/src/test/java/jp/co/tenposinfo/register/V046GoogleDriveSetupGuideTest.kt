package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V046GoogleDriveSetupGuideTest {
    @Test
    fun fingerprintUsesUppercaseColonSeparatedFormat() {
        assertEquals(
            "00:01:AB:FF",
            GoogleDriveAndroidClientInfoReader.formatFingerprint(
                byteArrayOf(0x00, 0x01, 0xAB.toByte(), 0xFF.toByte()),
            ),
        )
    }

    @Test
    fun guideCoversCloudAndDeviceSetup() {
        assertEquals(7, GoogleDriveSetupGuidePolicy.steps.size)
        assertTrue(GoogleDriveSetupGuidePolicy.steps.any { it.contains("Google Drive API") })
        assertTrue(GoogleDriveSetupGuidePolicy.steps.any { it.contains("applicationId") && it.contains("SHA-1") })
        assertTrue(GoogleDriveSetupGuidePolicy.steps.any { it.contains("テストユーザー") })
        assertEquals("https://www.googleapis.com/auth/drive.file", GoogleDriveSetupGuidePolicy.DRIVE_FILE_SCOPE)
        assertTrue(
            GoogleDriveSetupGuidePolicy.advice(GoogleDriveAccountStatus.CLOUD_CONFIGURATION_REQUIRED)
                .contains("SHA-1"),
        )
    }

    @Test
    fun sourceManifestSettingsDocsAndWorkflowStayConnected() {
        val sourceRoot = File("src/main/java/jp/co/tenposinfo/register")
        val guide = File(sourceRoot, "GoogleDriveSetupGuideActivity.kt").readText()
        val settings = File(sourceRoot, "SyncSettingsActivity.kt").readText()
        val account = File(sourceRoot, "GoogleDriveAccountActivity.kt").readText()
        val easyConnect = File(sourceRoot, "GoogleDriveEasyConnectActivity.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File("../.github/workflows/build-apk.yml").readText()
        val docs = File("../docs/V0.46_GOOGLE_DRIVE_SETUP_GUIDE.md").readText()
        val notes = File("../docs/V0.46_RELEASE_NOTES.md").readText()

        for (token in listOf(
            "GET_SIGNING_CERTIFICATES",
            "MessageDigest.getInstance",
            "applicationId（パッケージ名）",
            "Google Cloud Consoleを開く",
            "Googleアカウント登録へ進む",
            "drive.file",
            "つぐレジ＋は別のAndroid OAuthクライアント",
        )) assertTrue(guide.contains(token))

        assertTrue(settings.contains("GoogleDriveEasyConnectActivity::class.java"))
        assertTrue(settings.contains("Googleかんたん接続"))
        assertTrue(settings.contains("GoogleDriveSetupGuideActivity::class.java"))
        assertTrue(settings.contains("初期設定ガイド・詳細"))
        assertTrue(account.contains("初期設定ガイドを確認してください"))
        assertTrue(easyConnect.contains("保守・復旧"))
        assertTrue(manifest.contains("android:name=\".GoogleDriveEasyConnectActivity\""))
        assertTrue(manifest.contains("android:name=\".GoogleDriveSetupGuideActivity\""))
        assertTrue(manifest.contains("android:screenOrientation=\"landscape\""))
        assertTrue(build.contains("versionCode = 91"))
        assertTrue(build.contains("versionName = \"0.61.0-dev.1\""))
        assertTrue(workflow.contains("V046GoogleDriveSetupGuideTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI-v0.61.0-dev1-sale-context-receipt-voucher-navigation-apks"))
        assertTrue(docs.contains("jp.co.tenposinfo.register.dev"))
        assertTrue(docs.contains("5C:CC:D8:26:5E:BF:69:FF:36:EC:9D:37:6E:8C:AC:2A:DE:DB:89:44"))
        assertTrue(notes.contains("初期設定ガイド"))
        assertTrue(!File("../.github/workflows/v046-apply-temp.yml").exists())
        assertTrue(!File("../.github/workflows/v046-trigger-temp.yml").exists())
        assertTrue(!File("../tools/v046_apply.py").exists())
    }
}