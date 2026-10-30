package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class V146SelfUpdateDistributionTest {
    private fun read(path: String) = File(path).readText()
    private val modules = listOf(
        Triple(".", "pos", "180"),
        Triple("../management-app", "plus", "48"),
        Triple("../customer-display", "cd", "26"),
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
            assertTrue(src.contains("incomingVersion <= currentCode()"))
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
        // The v1.46 version codes are minimum baselines, not pinned current release IDs.
        val builds = listOf(
            Triple(".", "pos", 180),
            Triple("../management-app", "plus", 48),
            Triple("../customer-display", "cd", 26),
        )
        val standard = read("../.github/workflows/build-apk.yml")
        val fallback = read("../.github/workflows/build-apk-ci-fallback.yml")
        val integrity = read("../ci/verify-apk-release-integrity.sh")
        for ((dir, key, previousCode) in builds) {
            val gradle = read("$dir/build.gradle.kts")
            val code = Regex("""(?m)^        versionCode = ([0-9]+)$""")
                .find(gradle)?.groupValues?.get(1)?.toInt()
                ?: error("$key has no active versionCode")
            val name = Regex("""(?m)^        versionName = "([^"]+)"$""")
                .find(gradle)?.groupValues?.get(1)
                ?: error("$key has no active versionName")
            assertTrue("$key release code must increase", code > previousCode)
            assertTrue("$key invalid development version", name.matches(Regex("""[0-9]+[.][0-9]+[.][0-9]+-dev[.]1""")))
            val ciKey = when (key) { "pos" -> "POS"; "plus" -> "PLUS"; else -> null }
            if (ciKey != null) {
                for (workflow in listOf(standard, fallback)) {
                    assertTrue("$key CI code mismatch", workflow.contains("${ciKey}_VERSION_CODE: $code"))
                    assertTrue("$key CI name mismatch", workflow.contains("${ciKey}_VERSION_NAME: $name"))
                }
            } else {
                assertTrue("CD integrity code mismatch", integrity.contains("'$code'"))
                assertTrue("CD integrity name mismatch", integrity.contains("'$name'"))
            }
            val prefix = when (key) { "pos" -> "TSUGUREGI"; "plus" -> "TSUGUREGI_PLUS"; else -> "TSUGUREGI_CD" }
            val artifact = "${prefix}_v${name.removeSuffix("-dev.1")}_dev1_printer_chrome_debug.apk"
            assertTrue("$key standard APK name mismatch", standard.contains(artifact))
            assertTrue("$key fallback APK name mismatch", fallback.contains(artifact))
        }
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
