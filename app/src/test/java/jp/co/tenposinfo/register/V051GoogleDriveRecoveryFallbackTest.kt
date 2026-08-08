package jp.co.tenposinfo.register

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V051GoogleDriveRecoveryFallbackTest {
    @Test
    fun registerKeepsCompatibilityDeliveryForRecoveryMode() {
        val root = File("..")
        val build = File("build.gradle.kts").readText()
        val delivery = File("src/main/java/jp/co/tenposinfo/register/OutboxExternalDelivery.kt").readText()
        val deliverySettings = File("src/main/java/jp/co/tenposinfo/register/OutboxDeliverySettingsActivity.kt").readText()
        val syncSettings = File("src/main/java/jp/co/tenposinfo/register/SyncSettingsActivity.kt").readText()
        val easyConnect = File("src/main/java/jp/co/tenposinfo/register/GoogleDriveEasyConnectActivity.kt").readText()
        val plusRecovery = File(root, "management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveRecoveryActivity.kt").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        val docs = File(root, "docs/V0.51_GOOGLE_DRIVE_RECOVERY_FALLBACK.md").readText()

        assertTrue(build.contains("versionCode = 101"))
        assertTrue(build.contains("versionName = \"0.71.0-dev.1\""))
        assertTrue(deliverySettings.contains("ActivityResultContracts.OpenDocumentTree"))
        assertTrue(deliverySettings.contains("takePersistableUriPermission"))
        assertTrue(delivery.contains("drive-sync-staging"))
        assertTrue(syncSettings.contains("Google Drive・同期設定"))
        assertTrue(syncSettings.contains("互換用フォルダ送信設定"))
        assertTrue(easyConnect.contains("保守・復旧"))
        assertTrue(plusRecovery.contains("両アプリで同じDriveフォルダを選択してください"))
        assertTrue(docs.contains("同じGoogle Driveフォルダ"))
        assertTrue(workflow.contains("TSUGUREGI_v0.71.0_dev1_sale_receipt_reprint_period_index_debug.apk"))
        assertTrue(workflow.contains("TSUGUREGI-v0.71.0-dev1-sale-receipt-reprint-period-index-apks"))
        assertFalse(File(root, "tools/v051_apply.py").exists())
        assertFalse(File(root, ".github/workflows/v051-apply-temp.yml").exists())
        assertFalse(File(root, "tools/build-apk-v051.generated.yml").exists())
    }
}