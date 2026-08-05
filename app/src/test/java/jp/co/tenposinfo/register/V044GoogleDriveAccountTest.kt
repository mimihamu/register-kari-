package jp.co.tenposinfo.register

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V044GoogleDriveAccountTest {
    @Test
    fun posAccountAuthorizationIsWiredWithoutPersistingAccessToken() {
        val sourceRoot = File("src/main/java/jp/co/tenposinfo/register")
        val account = File(sourceRoot, "GoogleDriveAccountActivity.kt").readText()
        val guide = File(sourceRoot, "GoogleDriveSetupGuideActivity.kt").readText()
        val sync = File(sourceRoot, "SyncSettingsActivity.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val build = File("build.gradle.kts").readText()
        val docs = File("../docs/V0.44_GOOGLE_ACCOUNT_AUTHORIZATION.md").readText()
        val notes = File("../docs/V0.44_RELEASE_NOTES.md").readText()
        val workflow = File("../.github/workflows/build-apk.yml").readText()

        for (token in listOf(
            "const val DRIVE_FILE_SCOPE = Scopes.DRIVE_FILE",
            "listOf(Scope(DRIVE_FILE_SCOPE))",
            "Identity.getAuthorizationClient",
            "AuthorizationRequest.builder",
            "AuthorizationRequest.Prompt.SELECT_ACCOUNT",
            "Scopes.DRIVE_FILE",
            "RevokeAccessRequest.builder",
            "https://www.googleapis.com/drive/v3/about",
            "GoogleDriveAccountStatus.CLOUD_CONFIGURATION_REQUIRED",
            "GoogleDriveAccountStatus.AUTHORIZATION_FAILED",
            "初期設定ガイドを確認してください",
            "Google Drive APIが無効",
        )) assertTrue(account.contains(token))

        assertFalse(account.contains("putString(\"access_token\""))
        assertFalse(account.contains("putString(\"refresh_token\""))
        assertTrue(sync.contains("GoogleDriveSetupGuideActivity::class.java"))
        assertTrue(guide.contains("GoogleDriveAccountActivity::class.java"))
        assertTrue(sync.contains("互換用フォルダ送信設定"))
        assertTrue(manifest.contains("android:name=\".GoogleDriveSetupGuideActivity\""))
        assertTrue(manifest.contains("android:name=\".GoogleDriveAccountActivity\""))
        assertTrue(build.contains("com.google.android.gms:play-services-auth:21.6.0"))
        assertTrue(build.contains("versionCode = 76"))
        assertTrue(build.contains("versionName = \"0.46.0-dev.1\""))
        assertTrue(docs.contains("drive.file"))
        assertTrue(docs.contains("OAuth"))
        assertTrue(notes.contains("0.44.0-dev.1"))
        assertTrue(notes.contains("Googleアカウント"))
        assertTrue(workflow.contains("V044GoogleDriveAccountTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_v0.46.0_dev1_drive_setup_guide_debug.apk"))
    }
}
