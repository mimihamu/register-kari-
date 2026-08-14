package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V126DriveFailureHistoryConsistencyTest {
    @Test
    fun partialDriveFailureHistoryUsesCommittedStatusCounters() {
        val status = GoogleDriveDirectSyncStatus(
            running = false,
            listedCount = 1_200,
            downloadedCount = 210,
            unchangedCount = 990,
            importedCount = 180,
            duplicateCount = 20,
            rejectedCount = 10,
            errorCount = 0,
            lastFailureCategory = GoogleDriveSyncFailureCategory.NETWORK,
        )
        val record = GoogleDriveFailureVerificationRecordV126.fromDirectStatus(
            status = status,
            error = IllegalStateException("second page failed"),
            recordedAt = 1234L,
        )

        assertFalse(record.success)
        assertEquals(GoogleDriveResolvedMode.DRIVE_API, record.mode)
        assertEquals(1_200, record.listedCount)
        assertEquals(180, record.importedCount)
        assertEquals(20, record.duplicateCount)
        assertEquals(10, record.rejectedCount)
        assertEquals(1, record.errorCount)
    }

    @Test
    fun preflightFailureHistoryRemainsZeroAfterV125Reset() {
        val resetStatus = GoogleDriveDirectSyncStatus(
            running = false,
            listedCount = 0,
            importedCount = 0,
            duplicateCount = 0,
            rejectedCount = 0,
            errorCount = 0,
            lastFailureCategory = GoogleDriveSyncFailureCategory.AUTHORIZATION_REQUIRED,
        )
        val record = GoogleDriveFailureVerificationRecordV126.fromDirectStatus(
            status = resetStatus,
            error = IllegalStateException("authorization required"),
            recordedAt = 5678L,
        )

        assertEquals(0, record.listedCount)
        assertEquals(0, record.importedCount)
        assertEquals(0, record.duplicateCount)
        assertEquals(0, record.rejectedCount)
        assertEquals(1, record.errorCount)
    }

    @Test
    fun activityLoadsFinalizedDirectStatusAfterOuterFailureWrite() {
        val activity = File(
            "src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveSyncVerificationActivity.kt",
        ).readText()
        val failureBranch = activity.indexOf("onFailure = { error ->")
        val statusStore = activity.indexOf("val statusStore = GoogleDriveDirectSyncStatusStore(applicationContext)", failureBranch)
        val failedCall = activity.indexOf("statusStore.failed(", statusStore)
        val load = activity.indexOf("statusStore.load()", failedCall)
        val record = activity.indexOf("GoogleDriveFailureVerificationRecordV126.fromDirectStatus(", load)

        assertTrue(failureBranch >= 0)
        assertTrue(statusStore > failureBranch)
        assertTrue(failedCall > statusStore)
        assertTrue(load > failedCall)
        assertTrue(record > load)
    }

    @Test
    fun compatibilityFolderFailureKeepsGenericFailureSemantics() {
        val record = GoogleDriveFailureVerificationRecordV126.genericFailure(
            mode = GoogleDriveResolvedMode.COMPATIBILITY_FOLDER,
            error = IllegalStateException("folder unavailable"),
            recordedAt = 999L,
        )
        assertEquals(GoogleDriveResolvedMode.COMPATIBILITY_FOLDER, record.mode)
        assertEquals(0, record.listedCount)
        assertEquals(0, record.importedCount)
        assertEquals(1, record.errorCount)
    }
}
