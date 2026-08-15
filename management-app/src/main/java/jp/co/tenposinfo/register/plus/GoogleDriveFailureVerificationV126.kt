package jp.co.tenposinfo.register.plus

object GoogleDriveFailureVerificationRecordV126 {
    fun fromDirectStatus(
        status: GoogleDriveDirectSyncStatus,
        error: Throwable,
        recordedAt: Long,
    ): GoogleDriveSyncVerificationRecord {
        if (!ownsFinalizedFailure(status, error)) {
            return genericFailure(
                mode = GoogleDriveResolvedMode.DRIVE_API,
                error = error,
                recordedAt = recordedAt,
            ).copy(
                message = "${error.message ?: error.javaClass.simpleName}（別の同期runがdirect statusを所有しているため件数は関連付けていません）",
            )
        }
        return GoogleDriveSyncVerificationRecord(
            recordedAt = recordedAt,
            mode = GoogleDriveResolvedMode.DRIVE_API,
            success = false,
            listedCount = status.listedCount,
            importedCount = status.importedCount,
            duplicateCount = status.duplicateCount,
            rejectedCount = status.rejectedCount,
            errorCount = maxOf(status.errorCount, 1),
            message = error.message ?: error.javaClass.simpleName,
        )
    }

    fun genericFailure(
        mode: GoogleDriveResolvedMode,
        error: Throwable,
        recordedAt: Long,
    ): GoogleDriveSyncVerificationRecord = GoogleDriveSyncVerificationRecord(
        recordedAt = recordedAt,
        mode = mode,
        success = false,
        listedCount = 0,
        importedCount = 0,
        duplicateCount = 0,
        rejectedCount = 0,
        errorCount = 1,
        message = error.message ?: error.javaClass.simpleName,
    )

    internal fun ownsFinalizedFailure(
        status: GoogleDriveDirectSyncStatus,
        error: Throwable,
    ): Boolean = !status.running &&
        status.lastFailureCategory != null &&
        status.lastFailureCategory == GoogleDriveSyncErrorPolicy.classify(error)
}
