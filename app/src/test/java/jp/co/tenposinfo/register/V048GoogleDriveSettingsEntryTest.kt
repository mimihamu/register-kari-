package jp.co.tenposinfo.register

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V048GoogleDriveSettingsEntryTest {
    @Test
    fun visibleAdministratorMenuOpensDriveAndSyncScreen() {
        val root = File("src/main/java/jp/co/tenposinfo/register")
        val main = File(root, "MainActivity.kt").readText()
        val admin = File(root, "AdminSettingsActivity.kt").readText()
        val sync = File(root, "SyncSettingsActivity.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File("../.github/workflows/build-apk.yml").readText()
        val docs = File("../docs/V0.48_GOOGLE_DRIVE_SETTINGS_ENTRY.md").readText()
        val notes = File("../docs/V0.48_RELEASE_NOTES.md").readText()

        assertTrue(main.contains("onOpenSettings = { context.startActivity(Intent(context, AdminSettingsActivity::class.java)) }"))
        assertTrue(admin.contains("onSync = { context.startActivity(Intent(context, SyncSettingsActivity::class.java)) }"))
        assertTrue(admin.contains("onSync: () -> Unit"))
        assertTrue(admin.contains("Google Drive・同期"))
        assertTrue(admin.contains("初期設定、アカウント、送信状況、診断"))
        assertTrue(sync.contains("Google Drive・同期設定"))
        assertTrue(sync.contains("Googleかんたん接続"))
        assertTrue(sync.contains("GoogleDriveEasyConnectActivity::class.java"))
        assertTrue(sync.contains("初期設定ガイド・詳細"))
        assertTrue(sync.contains("GoogleDriveSetupGuideActivity::class.java"))
        assertTrue(manifest.contains("android:name=\".SyncSettingsActivity\""))
        assertTrue(manifest.contains("android:name=\".GoogleDriveEasyConnectActivity\""))
        assertTrue(manifest.contains("android:name=\".GoogleDriveSetupGuideActivity\""))
        assertTrue(build.contains("versionCode = 106"))
        assertTrue(build.contains("versionName = \"0.76.0-dev.1\""))
        assertTrue(workflow.contains("V048GoogleDriveSettingsEntryTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_v0.76.0_dev1_business_session_sales_drilldown_debug.apk"))
        assertTrue(workflow.contains("TSUGUREGI-v0.76.0-dev1-business-session-sales-drilldown-apks"))
        assertTrue(docs.contains("販売画面 → 各種設定 → 責任者認証 → Google Drive・同期"))
        assertTrue(notes.contains("0.48.0-dev.1"))
        assertFalse(File("../tools/v048_apply.py").exists())
        assertFalse(File("../.github/workflows/v048-apply-temp.yml").exists())
    }
}