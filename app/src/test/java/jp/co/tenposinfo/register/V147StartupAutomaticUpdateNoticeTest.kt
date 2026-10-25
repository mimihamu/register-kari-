package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source and policy gates for low-disruption automatic update discovery across all three apps. */
class V147StartupAutomaticUpdateNoticeTest {
    private val modules = listOf(
        Triple("", "jp/co/tenposinfo/register", "pos"),
        Triple("../management-app/", "jp/co/tenposinfo/register/plus", "plus"),
        Triple("../customer-display/", "jp/co/tenposinfo/register/cd", "cd"),
    )
    private fun source(base: String, pkg: String, name: String) =
        File(base + "src/main/java/" + pkg + "/" + name).readText()

    @Test fun allThreeAppsUseThrottledBackgroundCheckOnlyFromOwnPackage() {
        for ((base, pkg, key) in modules) {
            val code = source(base, pkg, "AppDistributionUpdateNoticeV147.kt")
            assertTrue(code.contains("https://tenpos.jp/support2/update/tsuguregi/api/latest.php"))
            assertTrue(code.contains("24 * 60 * 60 * 1000L"))
            assertTrue(code.contains("6 * 60 * 60 * 1000L"))
            assertTrue(code.contains("getBoolean(\"auto_check\", true)"))
            assertTrue(code.contains("getLong(\"last_attempt\", 0)"))
            assertTrue(code.contains("AtomicBoolean(false)"))
            assertTrue(code.contains("conn.instanceFollowRedirects = false"))
            assertTrue(code.contains("if (code > installedCode)"))
            assertTrue(code.contains("json.optString(\"packageName\") != appContext.packageName"))
            assertTrue(code.contains("NotificationManager.IMPORTANCE_LOW"))
            assertTrue(code.contains("PackageManager.PERMISSION_GRANTED"))
            assertTrue(code.contains("AppDistributionUpdateActivityV146::class.java"))
            assertTrue(code.contains("Thread({"))
            assertFalse(code.contains("ACTION_INSTALL_PACKAGE"))
            assertTrue("module $key", code.contains("if (Build.VERSION.SDK_INT >= 28)"))
            assertTrue("module $key", code.contains("installedInfo.versionCode.toLong()"))
        }
    }

    @Test fun appStartsChecksWithoutInterruptingTransactionScreens() {
        assertTrue(source("", "jp/co/tenposinfo/register", "MainActivity.kt").contains(
            "applicationContext, \"pos\", deviceSettings.automaticUpdateCheck,"))
        assertTrue(source("../management-app/", "jp/co/tenposinfo/register/plus", "MainActivity.kt")
            .contains("maybeCheck(applicationContext, \"plus\")"))
        assertTrue(source("../customer-display/", "jp/co/tenposinfo/register/cd", "MainActivity.kt")
            .contains("maybeCheck(applicationContext, \"cd\")"))
        for ((base, pkg, _) in modules) {
            val code = source(base, pkg, "AppDistributionUpdateActivityV146.kt")
            assertTrue(code.contains("pendingAfterPermission = apk"))
            assertTrue(code.contains("override fun onResume()"))
            assertTrue(code.contains("launchInstaller(pending)"))
            assertTrue(code.contains("lastAvailable(this@AppDistributionUpdateActivityV146)"))
            assertTrue(code.contains("requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS)"))
            assertTrue(code.contains("it.versionCode.toLong()"))
            assertTrue(code.contains("parsed.versionCode.toLong()"))
        }
    }

    @Test fun plusAndCdExposeAutomaticCheckSettingsAndNotificationPermission() {
        for ((base, _, _) in modules) {
            assertTrue(File(base + "src/main/AndroidManifest.xml").readText()
                .contains("android.permission.POST_NOTIFICATIONS"))
        }
        for ((base, pkg, _) in modules.drop(1)) {
            val code = source(base, pkg, "AppDistributionUpdateActivityV146.kt")
            assertTrue(code.contains("起動時に更新を確認"))
            assertTrue(code.contains("AppDistributionUpdateNoticeV147.setEnabled"))
        }
    }

    @Test fun releaseIdentityHasAdvancedFromV146() {
        for ((base, code, name) in listOf(
            Triple("", "174", "1.34.10-dev.1"),
            Triple("../management-app/", "42", "0.33.9-dev.1"),
            Triple("../customer-display/", "20", "0.15.12-dev.1"),
        )) {
            val build = File(base + "build.gradle.kts").readText()
            assertTrue(build.contains("        versionCode = $code"))
            assertTrue(build.contains("        versionName = \"$name\""))
        }
        val integrity = File("../ci/verify-apk-release-integrity.sh").readText()
        assertTrue(integrity.contains("'20'"))
        assertTrue(integrity.contains("'0.15.12-dev.1'"))
    }
}
