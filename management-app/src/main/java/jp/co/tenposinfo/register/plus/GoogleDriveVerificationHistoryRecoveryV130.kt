package jp.co.tenposinfo.register.plus

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri

object GoogleDriveVerificationHistoryRecoveryPolicyV130 {
    fun recoverableRecord(
        status: GoogleDriveDirectSyncStatus,
        history: List<GoogleDriveSyncVerificationRecord>,
    ): GoogleDriveSyncVerificationRecord? {
        val completedAt = status.lastCompletedAt ?: return null
        if (status.running) return null
        if (history.any { matchesFinalizedStatus(it, status, completedAt) }) return null
        return recordFromFinalizedStatus(status, completedAt)
    }

    fun matchesFinalizedStatus(
        record: GoogleDriveSyncVerificationRecord,
        status: GoogleDriveDirectSyncStatus,
        completedAt: Long = status.lastCompletedAt ?: return false,
    ): Boolean =
        record.mode == GoogleDriveResolvedMode.DRIVE_API &&
            record.recordedAt >= completedAt &&
            record.success == expectedSuccess(status) &&
            record.listedCount == status.listedCount &&
            record.importedCount == status.importedCount &&
            record.duplicateCount == status.duplicateCount &&
            record.rejectedCount == status.rejectedCount &&
            record.errorCount == expectedHistoryErrorCount(status)

    fun recordFromFinalizedStatus(
        status: GoogleDriveDirectSyncStatus,
        completedAt: Long = requireNotNull(status.lastCompletedAt),
    ): GoogleDriveSyncVerificationRecord = GoogleDriveSyncVerificationRecord(
        recordedAt = completedAt,
        mode = GoogleDriveResolvedMode.DRIVE_API,
        success = expectedSuccess(status),
        listedCount = status.listedCount,
        importedCount = status.importedCount,
        duplicateCount = status.duplicateCount,
        rejectedCount = status.rejectedCount,
        errorCount = expectedHistoryErrorCount(status),
        message = buildString {
            append("監査履歴復旧：finalized direct statusから補完")
            status.lastFailureCategory?.let { append("／").append(it.name) }
        },
    )

    private fun expectedSuccess(status: GoogleDriveDirectSyncStatus): Boolean =
        status.lastFailureCategory == null &&
            status.errorCount == 0 &&
            status.rejectedCount == 0

    private fun expectedHistoryErrorCount(status: GoogleDriveDirectSyncStatus): Int =
        if (status.lastFailureCategory != null) maxOf(status.errorCount, 1) else status.errorCount
}

object GoogleDriveVerificationHistoryRecoveryV130 {
    fun reconcile(context: Context): GoogleDriveSyncVerificationRecord? {
        val appContext = context.applicationContext
        val status = GoogleDriveDirectSyncStatusStore(appContext).load()
        val historyStore = GoogleDriveSyncVerificationHistoryStore(appContext)
        val record = GoogleDriveVerificationHistoryRecoveryPolicyV130.recoverableRecord(
            status = status,
            history = historyStore.load(),
        ) ?: return null
        historyStore.append(record)
        return record
    }
}

class GoogleDriveVerificationHistoryRecoveryProviderV130 : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return false
        runCatching { GoogleDriveVerificationHistoryRecoveryV130.reconcile(appContext) }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
