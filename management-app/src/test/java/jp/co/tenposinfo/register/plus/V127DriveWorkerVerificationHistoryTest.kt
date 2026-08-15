package jp.co.tenposinfo.register.plus

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V127DriveWorkerVerificationHistoryTest {
    @Test
    fun successfulWorkerRecordUsesFinalizedDirectStatusCounters() {
        val status = GoogleDriveDirectSyncStatus(
            listedCount = 12,
            importedCount = 5,
            duplicateCount = 4,
            rejectedCount = 0,
            errorCount = 0,
        )

        val record = GoogleDriveWorkerVerificationRecordV127.success(status, recordedAt = 123L)

        assertEquals(123L, record.recordedAt)
        assertEquals(GoogleDriveResolvedMode.DRIVE_API, record.mode)
        assertTrue(record.success)
        assertEquals(12, record.listedCount)
        assertEquals(5, record.importedCount)
        assertEquals(4, record.duplicateCount)
        assertEquals(0, record.rejectedCount)
        assertEquals(0, record.errorCount)
        assertTrue(record.message.contains("WorkManager"))
    }

    @Test
    fun workerSuccessRecordPreservesWarningSemanticsForRejectedOrReadErrors() {
        val record = GoogleDriveWorkerVerificationRecordV127.success(
            GoogleDriveDirectSyncStatus(
                listedCount = 3,
                importedCount = 1,
                rejectedCount = 1,
                errorCount = 1,
            ),
            recordedAt = 456L,
        )

        assertFalse(record.success)
        assertEquals(1, record.rejectedCount)
        assertEquals(1, record.errorCount)
    }

    @Test
    fun failedWorkerRecordKeepsFinalizedPartialProgressAndMinimumErrorCount() {
        val record = GoogleDriveWorkerVerificationRecordV127.failure(
            status = GoogleDriveDirectSyncStatus(
                listedCount = 1_000,
                importedCount = 740,
                duplicateCount = 250,
                rejectedCount = 10,
                errorCount = 0,
            ),
            error = IllegalStateException("page 2 failed"),
            recordedAt = 789L,
        )

        assertFalse(record.success)
        assertEquals(1_000, record.listedCount)
        assertEquals(740, record.importedCount)
        assertEquals(250, record.duplicateCount)
        assertEquals(10, record.rejectedCount)
        assertEquals(1, record.errorCount)
        assertTrue(record.message.contains("WorkManager"))
    }

    @Test
    fun workerWritesHistoryOnlyAfterDirectStatusIsFinalized() {
        val source = File(
            "src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveDirectSync.kt",
        ).readText()
        val worker = source.substringAfter("class GoogleDriveDirectSyncWorker")
            .substringBefore("object GoogleDriveDirectSyncScheduler")

        val successLoad = worker.indexOf("val finalizedStatus = statusStore.load()")
        val successRecord = worker.indexOf("GoogleDriveWorkerVerificationRecordV127.success")
        assertTrue(successLoad >= 0)
        assertTrue(successRecord > successLoad)

        val failureStart = worker.indexOf("onFailure = { error ->")
        val failure = worker.substring(failureStart)
        val failedStatus = failure.indexOf("statusStore.failed(")
        val finalizedLoad = failure.indexOf("val finalizedStatus = statusStore.load()")
        val failureRecord = failure.indexOf("GoogleDriveWorkerVerificationRecordV127.failure")
        val retryDecision = failure.indexOf("if (category.retryable)")
        assertTrue(failedStatus >= 0)
        assertTrue(finalizedLoad > failedStatus)
        assertTrue(failureRecord > finalizedLoad)
        assertTrue(retryDecision > failureRecord)
    }
}
