package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V092ApkReleaseIntegrityGateTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    @Test
    fun releaseIdentityTracksCurrentRegisterAndCompanionAppsStayPinned() {
        val register = File(root, "app/build.gradle.kts").readText()
        val plus = File(root, "management-app/build.gradle.kts").readText()
        val cd = File(root, "customer-display/build.gradle.kts").readText()

        assertTrue(register.contains("applicationId = \"jp.co.tenposinfo.register\""))
        assertTrue(register.contains("versionCode = 127"))
        assertTrue(register.contains("versionName = \"0.97.0-dev.1\""))
        assertTrue(register.contains("applicationIdSuffix = \".dev\""))

        assertTrue(plus.contains("applicationId = \"jp.co.tenposinfo.register.plus\""))
        assertTrue(plus.contains("versionCode = 14"))
        assertTrue(plus.contains("versionName = \"0.14.0-dev.1\""))

        assertTrue(cd.contains("applicationId = \"jp.co.tenposinfo.register.cd\""))
        assertTrue(cd.contains("versionCode = 7"))
        assertTrue(cd.contains("versionName = \"0.14.0-dev.1\""))
    }

    @Test
    fun verifierChecksBuiltApkIdentitySdkLauncherZipAndSignature() {
        val verifier = File(root, "ci/verify-apk-release-integrity.sh")
        assertTrue(verifier.isFile)
        val source = verifier.readText()

        assertTrue(source.contains("apkanalyzer"))
        assertTrue(source.contains("manifest_value application-id"))
        assertTrue(source.contains("manifest_value version-code"))
        assertTrue(source.contains("manifest_value version-name"))
        assertTrue(source.contains("manifest_value min-sdk"))
        assertTrue(source.contains("manifest_value target-sdk"))
        assertTrue(source.contains("aapt2"))
        assertTrue(source.contains("apksigner"))
        assertTrue(source.contains("unzip -tq"))
        assertTrue(source.contains("^launchable-activity:"))
        assertTrue(source.contains("Verified using v2 scheme (APK Signature Scheme v2):"))
        assertTrue(source.contains("signature_v2"))
        assertTrue(source.contains("expect_equal \"\$key\" signatureV2 \"true\""))
        assertTrue(source.contains("Signer #1 certificate SHA-256 digest:"))
        assertTrue(source.contains("752c4f56263c8887ada96184d25fad200aff0e84a80c67eda60c7607da3ac9e4"))
    }

    @Test
    fun verifierRequiresAllThreeDebugApplicationIds() {
        val source = File(root, "ci/verify-apk-release-integrity.sh").readText()

        assertTrue(source.contains("jp.co.tenposinfo.register.dev"))
        assertTrue(source.contains("jp.co.tenposinfo.register.plus.dev"))
        assertTrue(source.contains("jp.co.tenposinfo.register.cd.dev"))
        assertTrue(source.contains("MANAGEMENT_APP"))
        assertTrue(source.contains("CUSTOMER_DISPLAY"))
        assertTrue(source.contains("APK_RELEASE_INTEGRITY_GATE=passed"))
    }

    @Test
    fun workflowRunsIntegrityGateAfterBuildAndBeforeArtifactPreparation() {
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        val build = workflow.indexOf("- name: Build debug APKs")
        val gate = workflow.indexOf("- name: Verify built APK release identity and integrity")
        val prepare = workflow.indexOf("- name: Prepare named APKs and SHA-256")

        assertTrue(build >= 0)
        assertTrue(gate > build)
        assertTrue(prepare > gate)
        assertTrue(workflow.contains("POS_VERSION_CODE: 127"))
        assertTrue(workflow.contains("POS_VERSION_NAME: 0.97.0-dev.1"))
        assertTrue(workflow.contains("bash ci/verify-apk-release-integrity.sh"))
        assertTrue(workflow.contains(":app:testDebugUnitTest"))
        assertTrue(workflow.contains(":customer-display:testDebugUnitTest"))
        assertTrue(workflow.contains(":management-app:testDebugUnitTest"))
    }

    @Test
    fun gateDoesNotModifyBusinessData() {
        val source = File(root, "ci/verify-apk-release-integrity.sh").readText()
        assertFalse(source.contains("DELETE FROM", ignoreCase = true))
        assertFalse(source.contains("UPDATE sales", ignoreCase = true))
        assertFalse(source.contains("DROP TABLE", ignoreCase = true))
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
