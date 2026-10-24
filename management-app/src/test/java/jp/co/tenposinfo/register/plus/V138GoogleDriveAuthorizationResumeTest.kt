package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V138GoogleDriveAuthorizationResumeTest {
    @Test fun googleSheetFailureStateSurvivesResume() {
        val persisted = GoogleDriveAccountState(status = GoogleDriveAccountStatus.CONNECTED,
            email = "shop@example.com", message = "保存済み")
        for (status in listOf(
            GoogleDriveAccountStatus.CONNECTING,
            GoogleDriveAccountStatus.CLOUD_CONFIGURATION_REQUIRED,
            GoogleDriveAccountStatus.AUTHORIZATION_FAILED,
            GoogleDriveAccountStatus.VERIFICATION_FAILED
        )) {
            val latest = persisted.copy(status = status, message = "Google認証コード10")
            assertEquals(latest, GoogleDriveAuthorizationRefreshPolicy.merge(latest, persisted))
        }
    }

    @Test fun realAccountChangeAndNormalStateUsePersistedRecord() {
        val failed = GoogleDriveAccountState(status = GoogleDriveAccountStatus.AUTHORIZATION_FAILED,
            email = "old@example.com", message = "認可失敗")
        val persisted = GoogleDriveAccountState(status = GoogleDriveAccountStatus.CONNECTED,
            email = "new@example.com", message = "接続成功")
        assertEquals(persisted, GoogleDriveAuthorizationRefreshPolicy.merge(failed, persisted))
        assertEquals(persisted, GoogleDriveAuthorizationRefreshPolicy.merge(
            persisted.copy(message = "古い表示"), persisted))
        assertEquals(GoogleDriveAccountState(),
            GoogleDriveAuthorizationRefreshPolicy.merge(failed, GoogleDriveAccountState()))
    }

    @Test fun bothGoogleEntryPointsApplyResumePolicy() {
        val root = File("src/main/java/jp/co/tenposinfo/register/plus")
        val account = File(root, "GoogleDriveAccountActivity.kt").readText()
        val easy = File(root, "GoogleDriveEasyConnectActivity.kt").readText()
        assertTrue(account.contains("GoogleDriveAuthorizationRefreshPolicy.merge(state.value, accountStore.load())"))
        assertTrue(easy.contains("GoogleDriveAuthorizationRefreshPolicy.merge(uiState.value.account, accountStore.load())"))
    }
}
