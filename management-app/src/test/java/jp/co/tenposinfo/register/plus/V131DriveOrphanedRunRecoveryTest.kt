package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    @Test
    fun startupRecoveryDurablyFinalizesStatusBeforeHistoryPublication() {
        val source = File(
            "src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveOrphanedRunRecoveryV131.kt",
        ).readText()
        val recover = source.indexOf("fun recover(context: Context)")
        val durable = source.indexOf("check(finalizePersistedRunDurably(appContext))", recover)
        val finalized = source.indexOf("val finalized = statusStore.load()", durable)
        val append = source.indexOf("GoogleDriveSyncVerificationHistoryStore(appContext).append(record)", finalized)
        val durableFunction = source.indexOf("internal fun finalizePersistedRunDurably")
        val commit = source.indexOf(".commit()", durableFunction)

        assertTrue(recover >= 0)
        assertTrue(durable > recover)
        assertTrue(finalized > durable)
        assertTrue(append > finalized)
        assertTrue(durableFunction > recover)
        assertTrue(commit > durableFunction)
    }
}
