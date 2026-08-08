package jp.co.tenposinfo.register

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V047GoogleDriveDiagnosticsTest {
    @Test
    fun diagnosticsAreSanitizedAndConnected() {
        val root = File("src/main/java/jp/co/tenposinfo/register")
        val diagnostics = File(root, "GoogleDriveDiagnosticsActivity.kt").readText()
        val account = File(root, "GoogleDriveAccountActivity.kt").readText()
        val easyConnect = File(root, "GoogleDriveEasyConnectActivity.kt").readText()
        val upload = File(root, "GoogleDriveDirectUpload.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val build = File("build.gradle.kts").readText()
        val docs = File("../docs/V0.47_GOOGLE_DRIVE_DIAGNOSTICS.md").readText()
        val notes = File("../docs/V0.47_RELEASE_NOTES.md").readText()
        val workflow = File("../.github/workflows/build-apk.yml").readText()

        for (token in listOf(
            "GoogleDriveDiagnosticsActivity",
            "GoogleDriveDiagnosticLogStore",
            "GoogleDriveDiagnosticRepository",
            "GoogleDriveDiagnosticReport",
            "GoogleApiAvailability.getInstance()",
            "GoogleApiAvailability.getInstance().getErrorString",
            "NetworkCapabilities.NET_CAPABILITY_VALIDATED",
            "GoogleDriveDirectUploadCoordinator.TABLE",
            "SELECT status, COUNT(*)",
            "Intent.ACTION_SEND",
            "診断ログを共有",
            "REDACTED_TOKEN",
            "content://[REDACTED]",
            "MAX_EVENTS = 100",
        )) assertTrue(diagnostics.contains(token))

        assertFalse(diagnostics.contains("putString(\"access_token\""))
        assertFalse(diagnostics.contains("putString(\"refresh_token\""))
        assertTrue(account.contains("GoogleDriveDiagnosticsActivity::class.java"))
        assertTrue(account.contains("診断・ログ"))
        assertTrue(account.contains("GoogleDriveDiagnosticLogStore"))
        assertTrue(easyConnect.contains("保守・復旧"))
        assertTrue(upload.contains("GoogleDriveDiagnosticLogStore"))
        assertTrue(upload.contains("const val TABLE = \"drive_api_uploads\""))
        assertTrue(manifest.contains("android:name=\".GoogleDriveDiagnosticsActivity\""))
        assertTrue(manifest.contains("android:name=\".GoogleDriveEasyConnectActivity\""))
        assertTrue(build.contains("versionCode = 106"))
        assertTrue(build.contains("versionName = \"0.76.0-dev.1\""))
        assertTrue(docs.contains("アクセストークン"))
        assertTrue(docs.contains("売上JSON本文"))
        assertTrue(notes.contains("0.47.0-dev.1"))
        assertTrue(workflow.contains("V047GoogleDriveDiagnosticsTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_v0.76.0_dev1_business_session_sales_drilldown_debug.apk"))
    }

    @Test
    fun sanitizerMasksSensitiveValues() {
        assertTrue(GoogleDriveDiagnosticSanitizer.detail("Bearer abc.def").contains("[REDACTED]"))
        assertTrue(GoogleDriveDiagnosticSanitizer.detail("ya29.secret-token").contains("[REDACTED_TOKEN]"))
        assertTrue(GoogleDriveDiagnosticSanitizer.detail("content://provider/tree/secret").contains("content://[REDACTED]"))
        assertTrue(GoogleDriveDiagnosticSanitizer.maskedEmail("person@example.com") == "p***@example.com")
    }
}