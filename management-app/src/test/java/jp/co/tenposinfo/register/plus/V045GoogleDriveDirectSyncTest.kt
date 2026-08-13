package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class V045GoogleDriveDirectSyncTest {
    @Test
    fun errorPolicySeparatesRetryableAndPermanentFailures() {
        assertEquals(
            GoogleDriveSyncFailureCategory.RATE_LIMITED,
            GoogleDriveSyncErrorPolicy.classify(
                GoogleDriveSyncApiException(429, "rateLimitExceeded"),
            ),
        )
        assertEquals(
            GoogleDriveSyncFailureCategory.API_DISABLED,
            GoogleDriveSyncErrorPolicy.classify(
                GoogleDriveSyncApiException(403, "accessNotConfigured"),
            ),
        )
        assertEquals(
            GoogleDriveSyncFailureCategory.NETWORK,
            GoogleDriveSyncErrorPolicy.classify(IOException("offline")),
        )
        assertTrue(GoogleDriveSyncFailureCategory.SERVER.retryable)
        assertFalse(GoogleDriveSyncFailureCategory.PERMISSION_DENIED.retryable)
    }

    @Test
    fun directSyncUsesFileIdServerVersionAndSha256() {
        val source = File(
            "src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveDirectSync.kt",
        ).readText()
        val database = File(
            "src/main/java/jp/co/tenposinfo/register/plus/ManagementDatabase.kt",
        ).readText()
        val account = File(
            "src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveAccountActivity.kt",
        ).readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()

        for (token in listOf(
            "GoogleDriveSyncAccessTokenProvider",
            "AuthorizationRequest.builder",
            "GoogleDriveAccountPolicy.requestedScopes",
            "files(id,name,modifiedTime,version,size,appProperties)",
            "GoogleDriveRemoteVersionPolicyV119",
            "remote_version",
            "content_sha256",
            "SalesJournalImportRepository(database).importDocuments",
            "forceReimport",
            "gdrive://",
            "GoogleDriveDirectSyncWorker",
            "GoogleDriveDirectSyncScheduler",
            "enqueueStartup",
            "最終同期",
            "yyyy/MM/dd HH:mm:ss",
        )) assertTrue(token, source.contains(token))

        assertTrue(database.contains("DATABASE_VERSION = 4"))
        assertTrue(database.contains("remote_version TEXT"))
        assertTrue(database.contains("const val DATABASE_VERSION = 6"))
        assertTrue(database.contains("ensureDriveRemoteVersionV119"))
        assertTrue(manifest.contains("GoogleDriveDirectSyncBootstrapProvider"))
        assertTrue(account.contains("今すぐ差分同期"))
        assertTrue(account.contains("全件再取込"))
        assertTrue(account.contains("起動時に差分同期"))
        assertFalse(source.contains("putString(\"access_token\""))
        assertFalse(source.contains("putString(\"refresh_token\""))
    }

    @Test
    fun fastSkipRequiresPersistedMatchingServerVersion() {
        assertFalse(GoogleDriveRemoteVersionPolicyV119.canSkipDownload(null, "10", false))
        assertFalse(GoogleDriveRemoteVersionPolicyV119.canSkipDownload("", "10", false))
        assertFalse(GoogleDriveRemoteVersionPolicyV119.canSkipDownload("9", "10", false))
        assertFalse(GoogleDriveRemoteVersionPolicyV119.canSkipDownload("10", null, false))
        assertFalse(GoogleDriveRemoteVersionPolicyV119.canSkipDownload("10", "10", true))
        assertTrue(GoogleDriveRemoteVersionPolicyV119.canSkipDownload("10", "10", false))
    }

    @Test
    fun modifiedTimeAloneIsNeverUsedForFastSkip() {
        val source = File(
            "src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveDirectSync.kt",
        ).readText()
        assertFalse(source.contains("known.modifiedTime == remote.modifiedTime"))
        assertTrue(source.contains("knownRemoteVersion = known?.remoteVersion"))
        assertTrue(source.contains("currentRemoteVersion = remote.version"))
        assertTrue(source.contains("known.contentSha256 == sha256"))
    }

    @Test
    fun compatibilityFolderAndLocalSalesRemainIndependent() {
        val folderUi = File(
            "src/main/java/jp/co/tenposinfo/register/plus/ManagementFolderSyncScreen.kt",
        ).readText()
        val account = File(
            "src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveAccountActivity.kt",
        ).readText()

        assertTrue(folderUi.contains("フォルダ方式は互換用"))
        assertTrue(account.contains("取込済みローカル売上は削除していません"))
        assertTrue(account.contains("Drive上の削除とローカル売上削除は自動連動しません"))
    }
}
