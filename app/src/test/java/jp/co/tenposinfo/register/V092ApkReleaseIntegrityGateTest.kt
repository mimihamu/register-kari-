package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V092ApkReleaseIntegrityGateTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    private fun version(path: String): Pair<String, String> {
        val source = File(root, path).readLines()
            .map(String::trim)
            .filterNot { it.startsWith("//") }
            .joinToString("\n")
        val code = Regex("versionCode = (\\d+)").find(source)?.groupValues?.get(1)
            ?: error("versionCode not found: $path")
        val name = Regex("versionName = \\\"([^\\\"]+)\\\"").find(source)?.groupValues?.get(1)
            ?: error("versionName not found: $path")
        return code to name
    }

    @Test
    fun releaseIdentityTracksCurrentApps() {
        val register = File(root, "app/build.gradle.kts").readText()
        val plus = File(root, "management-app/build.gradle.kts").readText()
        val cd = File(root, "customer-display/build.gradle.kts").readText()
        val (registerCode, registerName) = version("app/build.gradle.kts")
        val (plusCode, plusName) = version("management-app/build.gradle.kts")

        assertTrue(register.contains("applicationId = \"jp.co.tenposinfo.register\""))
        assertTrue(registerCode.toInt() > 0)
        assertTrue(registerName.matches(Regex("\\d+\\.\\d+\\.\\d+-dev\\.\\d+")))
        assertTrue(plus.contains("applicationId = \"jp.co.tenposinfo.register.plus\""))
        assertTrue(plusCode.toInt() >= 14)
        assertTrue(plusName.matches(Regex("\\d+\\.\\d+\\.\\d+-dev\\.\\d+")))
        assertTrue(cd.contains("applicationId = \"jp.co.tenposinfo.register.cd\""))
        assertTrue(cd.contains("versionCode = 7"))
        assertTrue(cd.contains("versionName = \"0.14.0-dev.1\""))
    }

    @Test
    fun verifierCoversIdentitySdkLauncherArchiveAndSignature() {
        val verifier = File(root, "ci/verify-apk-release-integrity.sh")
        assertTrue(verifier.isFile)
        val source = verifier.readText()

        listOf(
            "apkanalyzer",
            "manifest_value application-id",
            "manifest_value version-code",
            "manifest_value version-name",
            "manifest_value min-sdk",
            "manifest_value target-sdk",
            "aapt2",
            "apksigner",
            "unzip -tq",
            "signature_v2",
            "PLUS_VERSION_CODE",
            "PLUS_VERSION_NAME",
        ).forEach { assertTrue("missing verifier token: $it", source.contains(it)) }
    }

    @Test
    fun verifierRequiresAllThreeDebugApplicationIds() {
        val source = File(root, "ci/verify-apk-release-integrity.sh").readText()
        assertTrue(source.contains("jp.co.tenposinfo.register.dev"))
        assertTrue(source.contains("jp.co.tenposinfo.register.plus.dev"))
        assertTrue(source.contains("jp.co.tenposinfo.register.cd.dev"))
        assertTrue(source.contains("APK_RELEASE_INTEGRITY_GATE=passed"))
    }

    @Test
    fun workflowRunsIntegrityGateAfterBuildAndBeforeArtifactPreparation() {
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        val build = workflow.indexOf("- name: Build debug APKs")
        val gate = workflow.indexOf("- name: Verify built APK release identity and integrity")
        val prepare = workflow.indexOf("- name: Prepare named APKs and SHA-256")
        val (registerCode, registerName) = version("app/build.gradle.kts")
        val (plusCode, plusName) = version("management-app/build.gradle.kts")

        assertTrue(build >= 0)
        assertTrue(gate > build)
        assertTrue(prepare > gate)
        assertTrue(workflow.contains("POS_VERSION_CODE: $registerCode"))
        assertTrue(workflow.contains("POS_VERSION_NAME: $registerName"))
        assertTrue(workflow.contains("PLUS_VERSION_CODE: $plusCode"))
        assertTrue(workflow.contains("PLUS_VERSION_NAME: $plusName"))
        assertTrue(workflow.contains("bash ci/verify-apk-release-integrity.sh"))
        assertTrue(workflow.contains(":app:testDebugUnitTest"))
        assertTrue(workflow.contains(":customer-display:testDebugUnitTest"))
        assertTrue(workflow.contains(":management-app:testDebugUnitTest"))
    }

    @Test
    fun gateDoesNotOpenWritableApplicationDatabase() {
        val source = File(root, "ci/verify-apk-release-integrity.sh").readText()
        assertFalse(source.contains("writableDatabase", ignoreCase = true))
    }

    @Test
    fun realDeviceVerificationRemainsFinalIntegratedAcceptance() {
        val notes = File(root, "docs/V0.92_RELEASE_NOTES.md")
        val requirements = File(root, "docs/V0.92_APK_RELEASE_INTEGRITY_GATE.md")
        assertTrue(notes.isFile)
        assertTrue(requirements.isFile)
        assertTrue(notes.readText().contains("最終総合実機試験へ繰越"))
        assertTrue(requirements.readText().contains("最終総合実機試験"))
    }
}
