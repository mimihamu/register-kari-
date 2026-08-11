package jp.co.tenposinfo.register.plus

import java.io.File
import java.io.IOException
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V101GoogleDriveTransientImportSafetyTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "management-app").isDirectory) current else current.parentFile
    }

    @Test
    fun authorizationAndPermissionFailuresBlockBatchWithoutQuarantine() {
        listOf(
            GoogleDriveSyncApiException(401, "unauthorized"),
            GoogleDriveSyncApiException(403, "permission denied"),
        ).forEach { error ->
            val decision = GoogleDriveRemoteFileFailurePolicy.decide(error)
            assertEquals(GoogleDriveRemoteFileFailureDisposition.BLOCK_BATCH, decision.disposition)
        }
    }

    @Test
    fun transientDriveFailuresRetryBatchInsteadOfCreatingReadError() {
        listOf(
            GoogleDriveSyncApiException(429, "rateLimitExceeded"),
            GoogleDriveSyncApiException(503, "backend error"),
            IOException("network down"),
        ).forEach { error ->
            val decision = GoogleDriveRemoteFileFailurePolicy.decide(error)
            assertEquals(GoogleDriveRemoteFileFailureDisposition.RETRY_BATCH, decision.disposition)
            assertTrue(decision.category.retryable)
        }
    }

    @Test
    fun localInvalidDocumentCanStillBeRejectedPerFile() {
        listOf(
            IllegalArgumentException("too large"),
            JSONException("bad json"),
        ).forEach { error ->
            val decision = GoogleDriveRemoteFileFailurePolicy.decide(error)
            assertEquals(GoogleDriveSyncFailureCategory.INVALID_DATA, decision.category)
            assertEquals(GoogleDriveRemoteFileFailureDisposition.REJECT_FILE, decision.disposition)
        }
    }

    @Test
    fun sourceThrowsBatchErrorBeforeImportQuarantineForTransientFailure() {
        val source = File(root, "management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveDirectSync.kt").readText()
        assertTrue(source.contains("GoogleDriveRemoteFileFailurePolicy.decide(error)"))
        assertTrue(source.contains("GoogleDriveRemoteFileFailureDisposition.RETRY_BATCH"))
        assertTrue(source.contains("GoogleDriveRemoteFileFailureDisposition.BLOCK_BATCH"))
        assertTrue(source.contains("throw GoogleDriveSyncBatchException("))
        val rejectStart = source.indexOf("GoogleDriveRemoteFileFailureDisposition.REJECT_FILE -> {")
        val retryStart = source.indexOf("GoogleDriveRemoteFileFailureDisposition.RETRY_BATCH", rejectStart)
        assertTrue(rejectStart >= 0)
        assertTrue(retryStart > rejectStart)
        val rejectBody = source.substring(rejectStart, retryStart)
        assertTrue(rejectBody.contains("ImportRejectionCode.READ_ERROR"))
        val retryBodyEnd = source.indexOf("}\n            }", retryStart).takeIf { it > retryStart } ?: source.length
        val retryBody = source.substring(retryStart, retryBodyEnd)
        assertFalse(retryBody.contains("ImportRejectionCode.READ_ERROR"))
    }

    @Test
    fun plusVersionWasIncrementedForBehaviorChange() {
        val gradle = File(root, "management-app/build.gradle.kts").readText()
        assertTrue(gradle.contains("versionCode = 15"))
        assertTrue(gradle.contains("versionName = \"0.15.0-dev.1\""))
    }
}
