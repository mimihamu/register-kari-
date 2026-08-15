package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V130DriveVerificationHistoryRecoveryTest {
    @Test
    fun finalizedSuccessWithoutHistoryProducesRecoveryRecord() {
        val status = GoogleDriveDirectSyncStatus(
            running = false,
            lastCompletedAt = 1_000L,
            listedCount = 12,
            importedCount = 7,
            duplicateCount = 5,
            rejectedCount = 0,
            errorCount = 0,
            lastFailureCategory = null,
        )

        val record = GoogleDriveVerificationHistoryRecoveryPolicyV130.recoverableRecord(
            status = status,
            history = emptyList(),
        )

        requireNotNull(record)
        assertEquals(1_000L, record.recordedAt)
        assertTrue(record.success)
        assertEquals(12, record.listedCount)
        assertEquals(7, record.importedCount)
        assertEquals(5, record.duplicateCount)
        assertEquals(0, record.errorCount)
        assertTrue(record.message.contains("監査履歴復旧"))
    }

    @Test
    fun matchingPublishedHistoryPreventsDuplicateRecovery() {
        val status = GoogleDriveDirectSyncStatus(
            running = false,
            lastCompletedAt = 2_000L,
            listedCount = 9,
            importedCount = 4,
            duplicateCount = 5,
            rejectedCount = 0,
            errorCount = 0,
        )
        val published = GoogleDriveSyncVerificationRecord(
            recordedAt = 2_005L,
            mode = GoogleDriveResolvedMode.DRIVE_API,
            success = true,
            listedCount = 9,
            importedCount = 4,
            duplicateCount = 5,
            rejectedCount = 0,
            errorCount = 0,
            message = "WorkManager Drive API差分同期",
        )

        assertNull(
            GoogleDriveVerificationHistoryRecoveryPolicyV130.recoverableRecord(
                status = status,
                history = listOf(published),
            ),
        )
    }

    @Test
    fun activeRunIsNeverSynthesizedAsFinalizedHistory() {
        val status = GoogleDriveDirectSyncStatus(
            running = true,
            lastCompletedAt = 3_000L,
            listedCount = 100,
            importedCount = 80,
        )

        assertNull(
            GoogleDriveVerificationHistoryRecoveryPolicyV130.recoverableRecord(
                status = status,
                history = emptyList(),
            ),
        )
    }

    @Test
    fun finalizedFailureNormalizesHistoryErrorCountToAtLeastOne() {
        val status = GoogleDriveDirectSyncStatus(
            running = false,
            lastCompletedAt = 4_000L,
            listedCount = 1_000,
            importedCount = 650,
            duplicateCount = 300,
            rejectedCount = 50,
            errorCount = 0,
            lastFailureCategory = GoogleDriveSyncFailureCategory.NETWORK,
        )

        val record = GoogleDriveVerificationHistoryRecoveryPolicyV130.recoverableRecord(
            status = status,
            history = emptyList(),
        )

        requireNotNull(record)
        assertFalse(record.success)
        assertEquals(1, record.errorCount)
        assertTrue(record.message.contains("NETWORK"))
    }

    @Test
    fun olderSameCountHistoryDoesNotHideNewerMissingFinalization() {
        val status = GoogleDriveDirectSyncStatus(
            running = false,
            lastCompletedAt = 6_000L,
            listedCount = 20,
            importedCount = 10,
            duplicateCount = 10,
            rejectedCount = 0,
            errorCount = 0,
        )
        val older = GoogleDriveSyncVerificationRecord(
            recordedAt = 5_999L,
            mode = GoogleDriveResolvedMode.DRIVE_API,
            success = true,
            listedCount = 20,
            importedCount = 10,
            duplicateCount = 10,
            rejectedCount = 0,
            errorCount = 0,
            message = "older run",
        )

        val recovered = GoogleDriveVerificationHistoryRecoveryPolicyV130.recoverableRecord(
            status = status,
            history = listOf(older),
        )

        requireNotNull(recovered)
        assertEquals(6_000L, recovered.recordedAt)
    }
}
