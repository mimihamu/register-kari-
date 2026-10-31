package jp.co.tenposinfo.register.plus

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V152Syn004DriveChangesRateLimitTest {
    private val module = File(System.getProperty("user.dir")).let { if (File(it, "management-app").isDirectory) File(it, "management-app") else it }
    private fun source(name: String) = File(module, "src/main/java/jp/co/tenposinfo/register/plus/$name").readText()

    @Test fun normalSyncUsesPersistedDriveChangesCursor() {
        val drive = source("GoogleDriveDirectSync.kt")
        listOf(
            "SYN004_DRIVE_CHANGES_API",
            "DRIVE_CHANGES_URL",
            "DRIVE_CHANGES_START_TOKEN_URL",
            "listJournalChangesPage",
            "drive_changes_cursor",
            "loadChangesPageToken",
            "persistChangesPageToken",
            "newStartPageToken",
        ).forEach { assertTrue("missing $it", drive.contains(it)) }
        assertTrue(drive.contains("if (changesMode)"))
        assertTrue(drive.contains("client.listJournalChangesPage(checkNotNull(pageToken))"))
    }

    @Test fun bootstrapCapturesStartTokenBeforeFullScanAndThenConsumesChanges() {
        val drive = source("GoogleDriveDirectSync.kt")
        val capture = drive.indexOf("baselineChangesToken = if (changesMode) null else client.getStartPageToken()")
        val fullList = drive.indexOf("client.listJournalPage(pageToken)", capture)
        val transition = drive.indexOf("pageToken = baselineChangesToken", fullList)
        assertTrue(capture > 0 && fullList > capture && transition > fullList)
    }

    @Test fun cursorAdvancesInsideSamePageCommitTransaction() {
        val drive = source("GoogleDriveDirectSync.kt")
        val begin = drive.indexOf("pageDb.beginTransaction()")
        val persist = drive.indexOf("persistChangesPageToken(pageDb, page.newStartPageToken)", begin)
        val successful = drive.indexOf("pageDb.setTransactionSuccessful()", persist)
        val end = drive.indexOf("pageDb.endTransaction()", successful)
        assertTrue(begin > 0 && persist > begin && successful > persist && end > successful)
    }

    @Test fun rateLimitRetryHonorsRetryAfterAndExponentialJitter() {
        val drive = source("GoogleDriveDirectSync.kt")
        listOf(
            "Retry-After",
            "GoogleDriveSyncRetryPolicyV152",
            "MAX_ATTEMPTS = 5",
            "1L shl exponent",
            "0.5 + normalizedJitter",
            "Thread.sleep(delay)",
        ).forEach { assertTrue("missing $it", drive.contains(it)) }
        assertEquals(3_000L, GoogleDriveSyncRetryPolicyV152.delayMillis(1, 3_000L, 0.0))
        assertEquals(1_000L, GoogleDriveSyncRetryPolicyV152.delayMillis(1, null, 0.5))
        assertEquals(2_000L, GoogleDriveSyncRetryPolicyV152.delayMillis(2, null, 0.5))
    }

    @Test fun rateLimit403ClassificationRemainsRetryable() {
        val body = "{\"error\":{\"errors\":[{\"reason\":\"userRateLimitExceeded\"}]}}"
        val error = GoogleDriveSyncApiException(403, body, 1_000L)
        assertEquals(GoogleDriveSyncFailureCategory.RATE_LIMITED, GoogleDriveSyncErrorPolicy.classify(error))
        assertTrue(GoogleDriveSyncRetryPolicyV152.shouldRetry(error))
    }
}
