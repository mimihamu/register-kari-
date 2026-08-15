package jp.co.tenposinfo.register.plus

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GoogleDriveOrphanedRunRecoveryPolicyV131 {
    fun shouldRecoverAtProcessStart(status: GoogleDriveDirectSyncStatus): Boolean = status.running

    fun recordFromRecoveredStatus(
        status: GoogleDriveDirectSyncStatus,
    ): GoogleDriveSyncVerificationRecord =
        GoogleDriveVerificationHistoryRecoveryPolicyV130.recordFromFinalizedStatus(status).copy(
            message = "監査履歴復旧：前回process中断runを起動時に中断確定",
        )
}

object GoogleDriveOrphanedRunRecoveryV131 {
    fun recover(context: Context): GoogleDriveSyncVerificationRecord? {
        val appContext = context.applicationContext
        val statusStore = GoogleDriveDirectSyncStatusStore(appContext)
        val persisted = statusStore.load()
        if (!GoogleDriveOrphanedRunRecoveryPolicyV131.shouldRecoverAtProcessStart(persisted)) {
            return null
        }

        statusStore.recoverStaleRun(
            "前回のアプリprocess終了により実行中runを中断扱いで復旧しました",
        )
        check(finalizePersistedRunDurably(appContext)) {
            "前回Drive同期runの中断状態を永続化できませんでした"
        }
        val finalized = statusStore.load()
        val record = GoogleDriveOrphanedRunRecoveryPolicyV131.recordFromRecoveredStatus(finalized)
        GoogleDriveSyncVerificationHistoryStore(appContext).append(record)
        return record
    }

    internal fun finalizePersistedRunDurably(context: Context): Boolean {
        val completedAt = System.currentTimeMillis()
        return context.applicationContext
            .getSharedPreferences(STATUS_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("running", false)
            .remove("run_token")
            .remove(KEY_OWNED_RUN_FAILURE_PENDING)
            .putLong("completed_at", completedAt)
            .putString("failure_category", GoogleDriveSyncFailureCategory.UNKNOWN.name)
            .putString(
                "message",
                "最終同期 ${formatSyncTime(completedAt)}（停止状態を修復）／" +
                    "前回のアプリprocess終了により実行中runを中断扱いで復旧しました",
            )
            .commit()
    }

    private fun formatSyncTime(value: Long): String =
        SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date(value))

    private const val STATUS_PREFS_NAME = "tsuguregi_plus_drive_api_sync_status"
    private const val KEY_OWNED_RUN_FAILURE_PENDING = "owned_run_failure_pending_v125"
}

class GoogleDriveOrphanedRunRecoveryProviderV131 : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return false
        runCatching { GoogleDriveOrphanedRunRecoveryV131.recover(appContext) }
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
