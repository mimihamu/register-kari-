package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V044GoogleDriveAccountTest {
    @Test
    fun driveAuthorizationUsesNarrowFileScope() {
        assertEquals(
            "https://www.googleapis.com/auth/drive.file",
            GoogleDriveAccountPolicy.DRIVE_FILE_SCOPE,
        )
        assertEquals(1, GoogleDriveAccountPolicy.requestedScopes.size)
        assertEquals(
            GoogleDriveAccountPolicy.DRIVE_FILE_SCOPE,
            GoogleDriveAccountPolicy.requestedScopes.single().scopeUri,
        )
        assertEquals(
            GoogleDriveAccountStatus.AUTHORIZATION_FAILED,
            GoogleDriveAccountPolicy.statusForAuthorizationError(IllegalStateException("failure")),
        )
    }

    @Test
    fun managementAccountAuthorizationAndPortraitUiAreWired() {
        val root = File("..")
        val sourceRoot = File("src/main/java/jp/co/tenposinfo/register/plus")
        val account = File(sourceRoot, "GoogleDriveAccountActivity.kt").readText()
        val folderScreen = File(sourceRoot, "ManagementFolderSyncScreen.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val build = File("build.gradle.kts").readText()
        val posAccount = File(root, "app/src/main/java/jp/co/tenposinfo/register/GoogleDriveAccountActivity.kt").readText()
        val docs = File(root, "docs/V0.44_GOOGLE_ACCOUNT_AUTHORIZATION.md").readText()
        val notes = File(root, "docs/V0.44_RELEASE_NOTES.md").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()

        for (token in listOf(
            "Identity.getAuthorizationClient",
            "AuthorizationRequest.builder",
            "AuthorizationRequest.Prompt.SELECT_ACCOUNT",
            "Scopes.DRIVE_FILE",
            "RevokeAccessRequest.builder",
            "https://www.googleapis.com/drive/v3/about",
            "Google Drive APIへ接続できました",
        )) {
            assertTrue(account.contains(token))
            assertTrue(posAccount.contains(token))
        }

        assertFalse(account.contains("putString(\"access_token\""))
        assertFalse(account.contains("putString(\"refresh_token\""))
        assertTrue(folderScreen.contains("Googleアカウント連携"))
        assertTrue(folderScreen.contains("フォルダ方式は互換用"))
        assertTrue(manifest.contains("android:name=\".GoogleDriveAccountActivity\""))
        assertTrue(manifest.contains("android:screenOrientation=\"sensorPortrait\""))
        assertTrue(build.contains("com.google.android.gms:play-services-auth:21.6.0"))
        assertTrue(build.contains("versionCode = 6"))
        assertTrue(build.contains("versionName = \"0.6.0-dev.1\""))
        assertTrue(docs.contains("jp.co.tenposinfo.register.plus"))
        assertTrue(notes.contains("Drive API接続確認"))
        assertTrue(workflow.contains("management-app/src/test/java/jp/co/tenposinfo/register/plus/V044GoogleDriveAccountTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_PLUS_v0.6.0_dev1_google_account_debug.apk"))
    }
}
