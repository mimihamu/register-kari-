package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class V137GoogleAuthorizationFailureVisibilityTest {
    @Test fun nonOkIntentResultMustNotPretendUserCanceled() {
        val message = GoogleDriveAuthorizationOutcomePolicy.unfinishedMessage(0)
        assertTrue(message.contains("Google連携を完了できませんでした"))
        assertTrue(message.contains("結果：0"))
        assertTrue(message.contains("Google Cloud"))
        assertTrue(GoogleDriveAuthorizationOutcomePolicy.unfinishedMessage(-2).contains("結果：-2"))
        assertFalse(message.contains("キャンセルされました"))
    }

    @Test fun bothEntrypointsPreserveErrorDiagnosticsAndShowCloudSetup() {
        val base = File("src/main/java/jp/co/tenposinfo/register/plus")
        val account = File(base, "GoogleDriveAccountActivity.kt").readText()
        val easy = File(base, "GoogleDriveEasyConnectActivity.kt").readText()
        for (source in listOf(account, easy)) {
            assertTrue(source.contains("result.resultCode != Activity.RESULT_OK"))
            assertTrue(source.contains("getAuthorizationResultFromIntent(intent)"))
            assertTrue(source.contains("GoogleDriveAuthorizationOutcomePolicy.unfinishedMessage(result.resultCode)"))
            assertTrue(source.contains("GoogleDriveAuthorizationOutcomePolicy.errorMessage(error)"))
            assertFalse(source.contains("Googleアカウントの選択がキャンセルされました"))
        }
        assertTrue(easy.contains("onOpenSetupGuide"))
        assertTrue(easy.contains("Google Cloudの初期設定"))
        assertTrue(easy.contains("GoogleDriveSetupGuideActivity::class.java"))
    }
}
