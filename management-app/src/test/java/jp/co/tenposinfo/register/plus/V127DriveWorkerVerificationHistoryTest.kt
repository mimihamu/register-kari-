package jp.co.tenposinfo.register.plus

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
}
