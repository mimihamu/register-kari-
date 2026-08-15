package jp.co.tenposinfo.register.plus

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri

object GoogleDriveVerificationHistoryRecoveryPolicyV130 {
    fun hasNewFinalization(
        status: GoogleDriveDirectSyncStatus,
        observedCompletedAt: Long,
    ): Boolean {
        val completedAt = status.lastCompletedAt ?: return false
        return !status.running && completedAt > observedCompletedAt
    }

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
        completedAt: Long,
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

class GoogleDriveVerificationHistoryRecoveryStateStoreV130(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun observedCompletedAt(): Long? =
        if (preferences.contains(KEY_OBSERVED_COMPLETED_AT)) {
            preferences.getLong(KEY_OBSERVED_COMPLETED_AT, 0L)
        } else {
            null
        }

    fun markObserved(completedAt: Long?) {
        preferences.edit()
            .putLong(KEY_OBSERVED_COMPLETED_AT, completedAt ?: 0L)
            .apply()
    }

    fun markObservedDurably(completedAt: Long?): Boolean =
        preferences.edit()
            .putLong(KEY_OBSERVED_COMPLETED_AT, completedAt ?: 0L)
            .commit()

    companion object {
        private const val PREFS_NAME = "tsuguregi_plus_drive_history_recovery_v130"
        private const val KEY_OBSERVED_COMPLETED_AT = "observed_completed_at"
    }
}

object GoogleDriveVerificationHistoryRecoveryV130 {
    private const val HISTORY_PREFS_NAME = "tsuguregi_plus_drive_sync_verification_history"
    private const val HISTORY_KEY = "history"

    @Volatile
    private var installedListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    @Synchronized
    fun install(context: Context): GoogleDriveSyncVerificationRecord? {
        val appContext = context.applicationContext
        if (installedListener == null) {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == HISTORY_KEY) {
                    markCurrentFinalizationObserved(appContext)
                }
            }
            appContext.getSharedPreferences(HISTORY_PREFS_NAME, Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(listener)
            installedListener = listener
        }
        return reconcile(appContext)
    }

    fun reconcile(context: Context): GoogleDriveSyncVerificationRecord? {
        val appContext = context.applicationContext
        val status = GoogleDriveDirectSyncStatusStore(appContext).load()
        val stateStore = GoogleDriveVerificationHistoryRecoveryStateStoreV130(appContext)
        val observedCompletedAt = stateStore.observedCompletedAt()
        if (observedCompletedAt == null) {
            stateStore.markObservedDurably(status.lastCompletedAt)
            return null
        }
        if (!GoogleDriveVerificationHistoryRecoveryPolicyV130.hasNewFinalization(status, observedCompletedAt)) {
            return null
        }

        val historyStore = GoogleDriveSyncVerificationHistoryStore(appContext)
        val record = GoogleDriveVerificationHistoryRecoveryPolicyV130.recoverableRecord(
            status = status,
            history = historyStore.load(),
        )
        if (record != null) {
            historyStore.append(record)
        }
        stateStore.markObserved(status.lastCompletedAt)
        return record
    }

    private fun markCurrentFinalizationObserved(context: Context) {
        val status = GoogleDriveDirectSyncStatusStore(context).load()
        if (!status.running) {
            GoogleDriveVerificationHistoryRecoveryStateStoreV130(context)
                .markObserved(status.lastCompletedAt)
        }
    }
}

class GoogleDriveVerificationHistoryRecoveryProviderV130 : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return false
        runCatching { GoogleDriveVerificationHistoryRecoveryV130.install(appContext) }
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
