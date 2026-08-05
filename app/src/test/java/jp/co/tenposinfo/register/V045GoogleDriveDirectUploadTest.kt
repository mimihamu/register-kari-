package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class V045GoogleDriveDirectUploadTest {
    @Test
    fun errorPolicySeparatesRetryableAndPermanentFailures() {
        assertEquals(
            GoogleDriveApiFailureCategory.RATE_LIMITED,
            GoogleDriveApiErrorPolicy.classify(
                GoogleDriveApiException(429, "userRateLimitExceeded"),
            ),
        )
        assertEquals(
            GoogleDriveApiFailureCategory.API_DISABLED,
            GoogleDriveApiErrorPolicy.classify(
                GoogleDriveApiException(403, "SERVICE_DISABLED"),
            ),
        )
        assertEquals(
            GoogleDriveApiFailureCategory.NETWORK,
            GoogleDriveApiErrorPolicy.classify(IOException("offline")),
        )
        assertTrue(GoogleDriveApiFailureCategory.RATE_LIMITED.retryable)
        assertFalse(GoogleDriveApiFailureCategory.STORAGE_FULL.retryable)
    }

    @Test
    fun directUploadUsesNarrowScopeOutboxAndAppProperties() {
        val source = File(
            "src/main/java/jp/co/tenposinfo/register/GoogleDriveDirectUpload.kt",
        ).readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val account = File(
            "src/main/java/jp/co/tenposinfo/register/GoogleDriveAccountActivity.kt",
        ).readText()

        for (token in listOf(
            "GoogleDriveAccessTokenProvider",
            "AuthorizationRequest.builder",
            "GoogleDriveAccountPolicy.requestedScopes",
            "https://www.googleapis.com/drive/v3/files",
            "https://www.googleapis.com/upload/drive/v3/files",
            "files(id,name,modifiedTime,size,appProperties)",
            "supportsAllDrives=false",
            "spaces=drive",
            "appProperties has",
            "sales-journal-root",
            "stores",
            "terminals",
            "journal",
            "businessDate",
            "duplicateKey",
            "contentSha256",
            "drive_api_uploads",
            "sync_outbox",
            "STAGED",
            "SENT",
            "GoogleDriveDirectUploadWorker",
            "GoogleDriveDirectUploadScheduler",
        )) assertTrue(token, source.contains(token))

        assertTrue(manifest.contains("GoogleDriveDirectUploadBootstrapProvider"))
        assertTrue(account.contains("今すぐアップロード"))
        assertTrue(account.contains("互換用フォルダ送信"))
        assertFalse(source.contains("putString(\"access_token\""))
        assertFalse(source.contains("putString(\"refresh_token\""))
        assertFalse(source.contains("Scopes.DRIVE"))
    }

    @Test
    fun driveIsNotTreatedAsTheOnlySourceOfTruth() {
        val source = File(
            "src/main/java/jp/co/tenposinfo/register/GoogleDriveDirectUpload.kt",
        ).readText()
        val account = File(
            "src/main/java/jp/co/tenposinfo/register/GoogleDriveAccountActivity.kt",
        ).readText()

        assertTrue(source.contains("OutboxDeliverySettingsStore"))
        assertTrue(source.contains("ローカルJSON"))
        assertTrue(account.contains("SQLiteのローカル売上を原本"))
        assertTrue(account.contains("ローカル売上データは削除していません"))
    }
}
