package jp.co.tenposinfo.register

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V055OutboxOperationsEntryTest {
    @Test
    fun registerSurfacesExistingSafeOutboxOperations() {
        val root = File("..")
        val settings = File("src/main/java/jp/co/tenposinfo/register/SyncSettingsActivity.kt").readText()
        val operations = File("src/main/java/jp/co/tenposinfo/register/OutboxDeliveryOperations.kt").readText()
        val panel = File("src/main/java/jp/co/tenposinfo/register/OutboxDeliveryOperationsPanel.kt").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()

        assertTrue(build.contains("versionCode = 88"))
        assertTrue(build.contains("versionName = \"0.58.0-dev.1\""))
        assertTrue(settings.contains("送信運用・個別再試行"))
        assertTrue(settings.contains("互換用フォルダ送信設定"))
        assertTrue(settings.contains("OutboxDeliverySettingsActivity::class.java"))
        assertTrue(operations.contains("fun retryItem"))
        assertTrue(operations.contains("OutboxItemRetryPolicy.canRetry"))
        assertTrue(panel.contains("この1件を再試行"))
        assertTrue(panel.contains("DriveOutboxScheduler.enqueueNow"))
        assertTrue(workflow.contains("V055OutboxOperationsEntryTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_v0.58.0_dev1_receipt_voucher_foundation_debug.apk"))
    }
}
