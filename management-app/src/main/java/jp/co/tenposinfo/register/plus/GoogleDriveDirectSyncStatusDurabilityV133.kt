package jp.co.tenposinfo.register.plus

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class GoogleDriveDirectSyncStatusPersistenceException(
    val stage: String,
) : IOException("Drive direct statusを永続化できませんでした：$stage")

object GoogleDriveDirectSyncStatusDurabilityV133 {
    fun start(context: Context): String {
        val runToken = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        commitOrThrow(
            context = context,
            stage = "run start",
            editor = preferences(context).edit()
                .putBoolean("running", true)
                .putString("run_token", runToken)
                .putLong("started_at", startedAt)
                .putInt("listed", 0)
                .putInt("downloaded", 0)
                .putInt("unchanged", 0)
                .putInt("imported", 0)
                .putInt("duplicates", 0)
                .putInt("rejected", 0)
                .putInt("errors", 0)
                .remove("failure_category")
                .remove(KEY_OWNED_RUN_FAILURE_PENDING)
                .putString("message", "Google Driveの売上JSONを確認しています"),
        )
        return runToken
    }

    fun progress(
        context: Context,
        runToken: String,
        result: GoogleDriveDirectSyncResult,
    ): Boolean {
        val preferences = preferences(context)
        if (preferences.getString("run_token", null) != runToken) return false
        commitOrThrow(
            context = context,
            stage = "page progress",
            editor = preferences.edit()
                .putInt("listed", result.listedCount)
                .putInt("downloaded", result.downloadedCount)
                .putInt("unchanged", result.unchangedCount)
                .putInt("imported", result.importedCount)
                .putInt("duplicates", result.duplicateCount)
                .putInt("rejected", result.rejectedCount)
                .putInt("errors", result.errorCount)
                .putString(
                    "message",
                    "同期中／確認${result.listedCount}件／取得${result.downloadedCount}件／" +
                        "新規${result.importedCount}件／重複${result.duplicateCount}件／隔離${result.rejectedCount}件",
                ),
        )
        return true
    }

    fun complete(
        context: Context,
        runToken: String,
        result: GoogleDriveDirectSyncResult,
    ): Boolean {
        val preferences = preferences(context)
        if (preferences.getString("run_token", null) != runToken) return false
        val completedAt = System.currentTimeMillis()
        commitOrThrow(
            context = context,
            stage = "run success finalization",
            editor = preferences.edit()
                .putBoolean("running", false)
                .remove("run_token")
                .putLong("completed_at", completedAt)
                .putInt("listed", result.listedCount)
                .putInt("downloaded", result.downloadedCount)
                .putInt("unchanged", result.unchangedCount)
                .putInt("imported", result.importedCount)
                .putInt("duplicates", result.duplicateCount)
                .putInt("rejected", result.rejectedCount)
                .putInt("errors", result.errorCount)
                .remove("failure_category")
                .remove(KEY_OWNED_RUN_FAILURE_PENDING)
                .putString(
                    "message",
                    "最終同期 ${formatSyncTime(completedAt)}／確認${result.listedCount}件／" +
                        "取得${result.downloadedCount}件／新規${result.importedCount}件／" +
                        "重複${result.duplicateCount}件／隔離${result.rejectedCount}件",
                ),
        )
        return true
    }

    fun failedForRun(
        context: Context,
        runToken: String,
        category: GoogleDriveSyncFailureCategory,
        message: String,
    ): Boolean {
        val preferences = preferences(context)
        if (preferences.getString("run_token", null) != runToken) return false
        writeFailure(
            context = context,
            category = category,
            message = message,
            resetProgress = false,
            markOwnedRunFailurePending = true,
            stage = "owned run failure finalization",
        )
        return true
    }

    fun failed(
        context: Context,
        category: GoogleDriveSyncFailureCategory,
        message: String,
    ): Boolean {
        val preferences = preferences(context)
        if (preferences.getBoolean("running", false)) return false
        val ownedRunFailure = preferences.getBoolean(KEY_OWNED_RUN_FAILURE_PENDING, false)
        writeFailure(
            context = context,
            category = category,
            message = message,
            resetProgress = !ownedRunFailure,
            markOwnedRunFailurePending = false,
            stage = "worker failure finalization",
        )
        return true
    }

    private fun writeFailure(
        context: Context,
        category: GoogleDriveSyncFailureCategory,
        message: String,
        resetProgress: Boolean,
        markOwnedRunFailurePending: Boolean,
        stage: String,
    ) {
        val preferences = preferences(context)
        val completedAt = System.currentTimeMillis()
        val editor = preferences.edit()
            .putBoolean("running", false)
            .remove("run_token")
            .putLong("completed_at", completedAt)
            .putString("failure_category", category.name)
            .putString(
                "message",
                "最終同期 ${formatSyncTime(completedAt)}（失敗）／${message.take(420)}",
            )
        if (resetProgress) {
            editor
                .putInt("listed", 0)
                .putInt("downloaded", 0)
                .putInt("unchanged", 0)
                .putInt("imported", 0)
                .putInt("duplicates", 0)
                .putInt("rejected", 0)
                .putInt("errors", 0)
        }
        if (markOwnedRunFailurePending) {
            editor.putBoolean(KEY_OWNED_RUN_FAILURE_PENDING, true)
        } else {
            editor.remove(KEY_OWNED_RUN_FAILURE_PENDING)
        }
        commitOrThrow(context, stage, editor)
    }

    private fun commitOrThrow(
        context: Context,
        stage: String,
        editor: SharedPreferences.Editor,
    ) {
        if (editor.commit()) return
        val error = GoogleDriveDirectSyncStatusPersistenceException(stage)
        GoogleDriveStartupRecoveryBarrierV132.block(
            stage = "v1.33 direct status durability",
            error = error,
        )
        throw error
    }

    private fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(STATUS_PREFS_NAME, Context.MODE_PRIVATE)

    private fun formatSyncTime(value: Long): String =
        SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date(value))

    private const val STATUS_PREFS_NAME = "tsuguregi_plus_drive_api_sync_status"
    private const val KEY_OWNED_RUN_FAILURE_PENDING = "owned_run_failure_pending_v125"
}
