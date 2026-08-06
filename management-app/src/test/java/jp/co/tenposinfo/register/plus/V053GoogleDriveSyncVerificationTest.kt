package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V053GoogleDriveSyncVerificationTest {
    private val now = 2_000_000_000_000L

    @Test
    fun unresolvedModeRequiresSetup() {
        assertEquals(
            GoogleDriveSyncHealth.SETUP_REQUIRED,
            GoogleDriveSyncVerificationPolicy.health(
                snapshot(resolvedMode = GoogleDriveResolvedMode.UNDECIDED),
                now,
            ),
        )
    }

    @Test
    fun runningLongerThanThirtyMinutesIsStalled() {
        val direct = GoogleDriveDirectSyncStatus(
            running = true,
            lastStartedAt = now - GoogleDriveSyncVerificationPolicy.STALE_RUNNING_MILLIS - 1L,
        )
        assertEquals(
            GoogleDriveSyncHealth.STALLED,
            GoogleDriveSyncVerificationPolicy.health(
                snapshot(directStatus = direct),
                now,
            ),
        )
    }

    @Test
    fun recentSuccessfulDriveSyncIsHealthy() {
        val direct = GoogleDriveDirectSyncStatus(
            running = false,
            lastCompletedAt = now - 10_000L,
            listedCount = 3,
            downloadedCount = 1,
            importedCount = 1,
        )
        assertEquals(
            GoogleDriveSyncHealth.HEALTHY,
            GoogleDriveSyncVerificationPolicy.health(
                snapshot(directStatus = direct),
                now,
            ),
        )
    }

    @Test
    fun nonRetryableFailureIsError() {
        val direct = GoogleDriveDirectSyncStatus(
            running = false,
            lastCompletedAt = now - 10_000L,
            lastFailureCategory = GoogleDriveSyncFailureCategory.AUTHORIZATION_REQUIRED,
        )
        assertEquals(
            GoogleDriveSyncHealth.ERROR,
            GoogleDriveSyncVerificationPolicy.health(
                snapshot(directStatus = direct),
                now,
            ),
        )
    }

    @Test
    fun reportMasksAccountAndDoesNotContainSecretsOrUris() {
        val exactEmail = "operator@example.com"
        val report = GoogleDriveSyncVerificationReport.build(
            snapshot = snapshot(accountEmail = exactEmail),
            history = listOf(
                GoogleDriveSyncVerificationRecord(
                    recordedAt = now,
                    mode = GoogleDriveResolvedMode.DRIVE_API,
                    success = true,
                    listedCount = 1,
                    importedCount = 1,
                    duplicateCount = 0,
                    rejectedCount = 0,
                    errorCount = 0,
                    message = "同期成功",
                ),
            ),
            applicationId = "jp.co.tenposinfo.register.plus.dev",
            versionName = "0.12.0-dev.1",
            generatedAt = now,
        )
        assertFalse(report.contains(exactEmail))
        assertTrue(report.contains("o***@example.com"))
        assertFalse(report.contains("Bearer "))
        assertFalse(report.contains("access_token"))
        assertFalse(report.contains("refresh_token"))
        assertFalse(report.contains("content://"))
        assertFalse(report.contains("tree_uri"))
    }

    private fun snapshot(
        accountEmail: String? = "operator@example.com",
        resolvedMode: GoogleDriveResolvedMode = GoogleDriveResolvedMode.DRIVE_API,
        directStatus: GoogleDriveDirectSyncStatus = GoogleDriveDirectSyncStatus(
            lastCompletedAt = now - 1_000L,
        ),
    ): GoogleDriveSyncVerificationSnapshot = GoogleDriveSyncVerificationSnapshot(
        accountEmail = accountEmail,
        selectedMode = GoogleDriveOperatingMode.AUTOMATIC,
        resolvedMode = resolvedMode,
        folderStatus = DriveConnectionStatus.READY,
        directStatus = directStatus,
        folderSummary = null,
        dashboard = ImportDashboard(
            totalImported = 0,
            totalRejected = 0,
            distinctStores = 0,
            latestImportedAt = null,
            eventTypeCounts = emptyMap(),
        ),
        recentRejectionCount = 0,
    )
}
