package jp.co.tenposinfo.register.plus

internal object GoogleDriveWorkerVerificationRecordV127 {
    fun success(
        status: GoogleDriveDirectSyncStatus,
        recordedAt: Long = System.currentTimeMillis(),
    ): GoogleDriveSyncVerificationRecord = GoogleDriveSyncVerificationRecord(
        recordedAt = recordedAt,
        mode = GoogleDriveResolvedMode.DRIVE_API,
        success = status.errorCount == 0 && status.rejectedCount == 0,
        listedCount = status.listedCount,
        importedCount = status.importedCount,
        duplicateCount = status.duplicateCount,
        rejectedCount = status.rejectedCount,
        errorCount = status.errorCount,
        message = "WorkManager Drive API差分同期",
    )

    fun failure(
        status: GoogleDriveDirectSyncStatus,
        error: Throwable,
        recordedAt: Long = System.currentTimeMillis(),
    ): GoogleDriveSyncVerificationRecord {
        val finalized = GoogleDriveFailureVerificationRecordV126.fromDirectStatus(
            status = status,
            error = error,
            recordedAt = recordedAt,
        )
        return finalized.copy(
            message = "WorkManager ${finalized.message}",
        )
    }
}
