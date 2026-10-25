package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class V146SelfUpdateDistributionTest {
    private fun read(path: String) = File(path).readText()
    private val modules = listOf(
        Triple(".", "pos", "169"),
        Triple("../management-app", "plus", "37"),
        Triple("../customer-display", "cd", "15"),
    )

    @Test fun updaterUsesOnlyOfficialHttpsAndChecksPackageVersionHashAndSignature() {
        for ((dir, app, _) in modules) {
            val pkg = when (app) {
                "pos" -> "jp/co/tenposinfo/register"
                "plus" -> "jp/co/tenposinfo/register/plus"
                else -> "jp/co/tenposinfo/register/cd"
            }
            val src = read("$dir/src/main/java/$pkg/AppDistributionUpdateActivityV146.kt")
            assertTrue(src.contains("https://tenpos.jp/support2/update/tsuguregi/"))
            assertTrue(src.contains("private val appKey = \"$app\""))
            assertTrue(src.contains("if (release") || src.contains("item.packageName != packageName"))
            assertTrue(src.contains("con.instanceFollowRedirects = false"))
            assertTrue(src.contains("maxApkBytes = 125_829_120L"))
            assertTrue(src.contains("MessageDigest.getInstance(\"SHA-256\")"))
            assertTrue(src.contains("PackageManager.GET_SIGNING_CERTIFICATES"))
            assertTrue(src.contains("getPackageArchiveInfo("))
            assertTrue(src.contains("parsed.longVersionCode <= currentCode()"))
            assertTrue(src.contains("existing.map { it.toCharsString() }.toSet()"))
            assertTrue(src.contains("FileProvider.getUriForFile"))
            assertTrue(src.contains("Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES"))
            assertTrue(src.contains("packageName + \".updates\""))
            assertFalse(src.contains("http://tenpos.jp"))
        }
    }

    @Test fun eachModuleHasAndroidInstallerPermissionProviderAndCacheOnlyGrant() {
        for ((dir, _, _) in modules) {
            val manifest = read("$dir/src/main/AndroidManifest.xml")
            assertTrue(manifest.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
            assertTrue(manifest.contains("android:name=\".AppDistributionUpdateActivityV146\""))
            assertTrue(manifest.contains("android:name=\"androidx.core.content.FileProvider\""))
            assertTrue(manifest.contains("android:authorities=") && manifest.contains(".updates"))
            assertTrue(manifest.contains("android:exported=\"false\""))
            assertTrue(manifest.contains("@xml/tsuguregi_update_paths"))
            val xml = read("$dir/src/main/res/xml/tsuguregi_update_paths.xml")
            assertTrue(xml.contains("<cache-path"))
            assertFalse(xml.contains("<external-path"))
            assertFalse(xml.contains("<root-path"))
        }
    }

    @Test fun installedVersionCodeMustIncreaseToAllowNextPublishedRelease() {
        val builds = listOf(
            Triple(".", "169", "1.34.5-dev.1"),
            Triple("../management-app", "37", "0.33.4-dev.1"),
            Triple("../customer-display", "15", "0.15.7-dev.1"),
        )
        for ((dir, code, name) in builds) {
            val gradle = read("$dir/build.gradle.kts")
            assertTrue(gradle.contains("versionCode = $code"))
            assertTrue(gradle.contains("versionName = \"$name\""))
        }
        val gate = read("../ci/verify-apk-release-integrity.sh")
        assertTrue(gate.contains("'15'"))
        assertTrue(gate.contains("'0.15.7-dev.1'"))
    }

    @Test fun updateActionsAreReachableFromAllThreeApps() {
        assertTrue(read("src/main/java/jp/co/tenposinfo/register/InitialReleaseSettingsV135.kt")
            .contains("AppDistributionUpdateActivityV146::class.java"))
        assertTrue(read("../management-app/src/main/java/jp/co/tenposinfo/register/plus/ManagementFolderSyncScreen.kt")
            .contains("AppDistributionUpdateActivityV146::class.java"))
        assertTrue(read("../customer-display/src/main/java/jp/co/tenposinfo/register/cd/MainActivity.kt")
            .contains("AppDistributionUpdateActivityV146::class.java"))
    }
}
