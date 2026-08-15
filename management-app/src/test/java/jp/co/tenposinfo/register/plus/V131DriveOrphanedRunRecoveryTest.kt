package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V131DriveOrphanedRunRecoveryTest {
    @Test
    fun persistedRunningStatusIsRecoverableAtFreshProcessStart() {
        assertTrue(
            GoogleDriveOrphanedRunRecoveryPolicyV131.shouldRecoverAtProcessStart(
                GoogleDriveDirectSyncStatus(running = true),
            ),
        )
    }

    @Test
    fun finalizedStatusIsNotTreatedAsOrphanedRun() {
        assertFalse(
            GoogleDriveOrphanedRunRecoveryPolicyV131.shouldRecoverAtProcessStart(
                GoogleDriveDirectSyncStatus(
                    running = false,
                    lastCompletedAt = 100L,
                ),
            ),
        )
    }

    @Test
    fun recoveredRunHistoryPreservesCommittedProgressAndMarksFailure() {
        val record = GoogleDriveOrphanedRunRecoveryPolicyV131.recordFromRecoveredStatus(
            GoogleDriveDirectSyncStatus(
                running = false,
                lastStartedAt = 100L,
                lastCompletedAt = 200L,
                listedCount = 1_250,
                downloadedCount = 210,
                unchangedCount = 1_040,
                importedCount = 175,
                duplicateCount = 25,
                rejectedCount = 10,
                errorCount = 0,
                lastFailureCategory = GoogleDriveSyncFailureCategory.UNKNOWN,
            ),
        )

        assertFalse(record.success)
        assertEquals(200L, record.recordedAt)
        assertEquals(1_250, record.listedCount)
        assertEquals(175, record.importedCount)
        assertEquals(25, record.duplicateCount)
        assertEquals(10, record.rejectedCount)
        assertEquals(1, record.errorCount)
        assertTrue(record.message.contains("前回process中断run"))
    }
}
