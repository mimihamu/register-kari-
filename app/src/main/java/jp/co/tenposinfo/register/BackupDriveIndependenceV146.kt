package jp.co.tenposinfo.register

data class BackupDriveIndependentStatusV146(
    val backupResult: AutoBackupResultState,
    val backupSuccessful: Boolean,
    val driveStateLabel: String,
)

/**
 * BKP-008 contract: terminal-recovery backup and Google Drive sales exchange
 * have independent purposes and independent success conditions.
 */
object BackupDriveIndependenceV146 {
    fun snapshot(
        backup: AutoBackupRuntimeStatus,
        drive: GoogleDriveDirectUploadStatus,
    ): BackupDriveIndependentStatusV146 = BackupDriveIndependentStatusV146(
        backupResult = backup.lastResult,
        backupSuccessful = backup.lastResult == AutoBackupResultState.CREATED,
        driveStateLabel = driveStateLabel(drive),
    )

    fun driveStateLabel(status: GoogleDriveDirectUploadStatus): String = when {
        status.running -> "送信中"
        status.blockedCategory != null -> "要確認（${status.blockedCategory.name}）"
        status.lastCompletedAt == null -> "未実行"
        status.permanentFailureCount > 0 -> "要確認（永久失敗あり）"
        status.retryCount > 0 -> "完了（再試行あり）"
        else -> "完了"
    }
}
