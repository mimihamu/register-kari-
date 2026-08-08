package jp.co.tenposinfo.register

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V050GoogleDriveCrossAppConnectionTest {
    @Test
    fun registerCreatesSalesFreeReusableConnectionTestJson() {
        val root = File("src/main/java/jp/co/tenposinfo/register")
        val source = File(root, "GoogleDriveConnectionTest.kt").readText()
        val account = File(root, "GoogleDriveAccountActivity.kt").readText()
        val easyConnect = File(root, "GoogleDriveEasyConnectActivity.kt").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File("../.github/workflows/build-apk.yml").readText()
        val docs = File("../docs/V0.50_GOOGLE_DRIVE_CROSS_APP_CONNECTION_TEST.md").readText()

        for (token in listOf(
            "GoogleDriveConnectionTestCoordinator",
            "GoogleDriveConnectionTestContract",
            "oauth-cross-app-visibility",
            "connection-test",
            "containsSalesData",
            "client.updateJson",
            "slot",
        )) assertTrue(source.contains(token))
        assertTrue(account.contains("接続テストJSONを作成"))
        assertTrue(account.contains("createConnectionTest"))
        assertTrue(easyConnect.contains("GoogleDriveConnectionTestCoordinator(applicationContext)"))
        assertTrue(easyConnect.contains("createOrUpdate(accessToken)"))
        assertFalse(source.contains("SalesJournalJsonContract"))
        assertFalse(source.contains("putString(\"access_token\""))
        assertFalse(source.contains("putString(\"refresh_token\""))
        assertTrue(build.contains("versionCode = 100"))
        assertTrue(build.contains("versionName = \"0.70.0-dev.1\""))
        assertTrue(workflow.contains("V050GoogleDriveCrossAppConnectionTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_v0.70.0_dev1_sale_receipt_reprint_database_paging_debug.apk"))
        assertTrue(docs.contains("実売上を使用しない"))
        assertFalse(File("../tools/v050_apply.py").exists())
        assertFalse(File("../.github/workflows/v050-apply-temp.yml").exists())
        assertFalse(File("../tools/build-apk-v050.generated.yml").exists())
    }
}