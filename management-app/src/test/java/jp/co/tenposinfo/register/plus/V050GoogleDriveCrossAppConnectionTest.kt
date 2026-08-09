package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V050GoogleDriveCrossAppConnectionTest {
    @Test
    fun plusSearchesDownloadsAndValidatesRegisterConnectionTestWithoutImportingIt() {
        val root = File("src/main/java/jp/co/tenposinfo/register/plus")
        val source = File(root, "GoogleDriveConnectionTest.kt").readText()
        val account = File(root, "GoogleDriveAccountActivity.kt").readText()
        val easyConnect = File(root, "GoogleDriveEasyConnectActivity.kt").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File("../.github/workflows/build-apk.yml").readText()
        val docs = File("../docs/V0.50_GOOGLE_DRIVE_CROSS_APP_CONNECTION_TEST.md").readText()

        for (token in listOf(
            "GoogleDriveConnectionTestVerifier",
            "findLatest",
            "download",
            "ALLOWED_KEYS",
            "containsSalesData",
            "NOT_FOUND",
            "modifiedTime desc",
        )) assertTrue(source.contains(token))
        assertTrue(account.contains("つぐレジ接続テストを検索"))
        assertTrue(account.contains("verifyConnectionTest"))
        assertTrue(easyConnect.contains("searchAndVerify(accessToken)"))
        assertFalse(source.contains("SalesJournalImportRepository"))
        assertFalse(source.contains("drive_sync_files"))
        assertFalse(source.contains("putString(\"access_token\""))
        assertFalse(source.contains("putString(\"refresh_token\""))
        assertTrue(build.contains("versionCode = 14"))
        assertTrue(build.contains("versionName = \"0.14.0-dev.1\""))
        assertTrue(workflow.contains("V050GoogleDriveCrossAppConnectionTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_PLUS_v0.14.0_dev1_sync_operations_dashboard_debug.apk"))
        assertTrue(workflow.contains("TSUGUREGI-v0.77.0-dev1-settlement-history-sales-drilldown-apks"))
        assertTrue(docs.contains("別Android OAuthクライアント"))
        assertFalse(File("../tools/v050_apply.py").exists())
        assertFalse(File("../.github/workflows/v050-apply-temp.yml").exists())
        assertFalse(File("../tools/build-apk-v050.generated.yml").exists())
    }
}