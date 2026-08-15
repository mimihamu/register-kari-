package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class V128DriveFailureHistoryRunOwnershipTest {
    @Test
    fun activeForeignRunCountersAreNotAttributedToPreflightFailure() {
        val foreignRun = GoogleDriveDirectSyncStatus(
            running = true,
            listedCount = 900,
            importedCount = 700,
            duplicateCount = 120,
            rejectedCount = 30,
            errorCount = 2,
            lastFailureCategory = null,
        )
        val error = GoogleDriveSyncAuthorizationRequiredException("authorization expired")

        val record = GoogleDriveFailureVerificationRecordV126.fromDirectStatus(
            status = foreignRun,
            error = error,
            recordedAt = 100L,
        )

        assertFalse(record.success)
        assertEquals(0, record.listedCount)
        assertEquals(0, record.importedCount)
        assertEquals(0, record.duplicateCount)
        assertEquals(0, record.rejectedCount)
        assertEquals(1, record.errorCount)
        assertTrue(record.message.contains("件数は関連付けていません"))
    }

    @Test
    fun newerSuccessfulStatusIsNotAttributedToOlderFailure() {
        val newerSuccess = GoogleDriveDirectSyncStatus(
            running = false,
            listedCount = 50,
            importedCount = 40,
            duplicateCount = 10,
            rejectedCount = 0,
            errorCount = 0,
            lastFailureCategory = null,
        )

        val record = GoogleDriveFailureVerificationRecordV126.fromDirectStatus(
            status = newerSuccess,
            error = IOException("older network failure"),
            recordedAt = 200L,
        )

        assertEquals(0, record.listedCount)
        assertEquals(0, record.importedCount)
        assertEquals(0, record.duplicateCount)
        assertEquals(0, record.rejectedCount)
        assertEquals(1, record.errorCount)
    }

    @Test
    fun differentFailureCategoryIsNotTreatedAsOwnedFinalization() {
        val foreignFailure = GoogleDriveDirectSyncStatus(
            running = false,
            listedCount = 80,
            importedCount = 60,
            duplicateCount = 10,
            rejectedCount = 10,
            errorCount = 1,
            lastFailureCategory = GoogleDriveSyncFailureCategory.SERVER,
        )
        val error = IOException("network failure")

        assertFalse(
            GoogleDriveFailureVerificationRecordV126.ownsFinalizedFailure(
                status = foreignFailure,
                error = error,
            ),
        )
        val record = GoogleDriveFailureVerificationRecordV126.fromDirectStatus(
            status = foreignFailure,
            error = error,
            recordedAt = 300L,
        )
        assertEquals(0, record.listedCount)
        assertEquals(0, record.importedCount)
        assertEquals(1, record.errorCount)
    }

    @Test
    fun matchingFinalizedFailureStillKeepsCommittedProgress() {
        val ownedFailure = GoogleDriveDirectSyncStatus(
            running = false,
            listedCount = 1_250,
            importedCount = 200,
            duplicateCount = 25,
            rejectedCount = 5,
            errorCount = 0,
            lastFailureCategory = GoogleDriveSyncFailureCategory.NETWORK,
        )
        val error = IOException("page two disconnected")

        assertTrue(
            GoogleDriveFailureVerificationRecordV126.ownsFinalizedFailure(
                status = ownedFailure,
                error = error,
            ),
        )
        val record = GoogleDriveFailureVerificationRecordV126.fromDirectStatus(
            status = ownedFailure,
            error = error,
            recordedAt = 400L,
        )
        assertEquals(1_250, record.listedCount)
        assertEquals(200, record.importedCount)
        assertEquals(25, record.duplicateCount)
        assertEquals(5, record.rejectedCount)
        assertEquals(1, record.errorCount)
    }
}
