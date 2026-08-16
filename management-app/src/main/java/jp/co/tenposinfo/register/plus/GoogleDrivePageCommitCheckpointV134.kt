package jp.co.tenposinfo.register.plus

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri

/**
 * v1.34: SQLite page commit と SharedPreferences progress publication の間で process が終了しても、
 * 次 process が最後に commit 済みの page counters を復元できるようにする checkpoint。
 *
 * checkpoint 自体は page の最外 SQLite transaction 内で import/fingerprint と一緒に commit する。
 */
data class GoogleDrivePageCommitCheckpointV134(
    val runToken: String,
    val result: GoogleDriveDirectSyncResult,
    val committedAt: Long,
)

object GoogleDrivePageCommitCheckpointStoreV134 {
    const val TABLE = "drive_sync_progress_checkpoint_v134"

    fun ensureSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE (
                singleton_id INTEGER PRIMARY KEY NOT NULL,
                run_token TEXT NOT NULL,
                listed INTEGER NOT NULL,
                downloaded INTEGER NOT NULL,
                unchanged INTEGER NOT NULL,
                imported INTEGER NOT NULL,
                duplicates INTEGER NOT NULL,
                rejected INTEGER NOT NULL,
                errors INTEGER NOT NULL,
                committed_at INTEGER NOT NULL,
                CHECK (singleton_id = 1)
            )
            """.trimIndent(),
        )
    }

    fun persist(
        db: SQLiteDatabase,
        runToken: String,
        result: GoogleDriveDirectSyncResult,
        committedAt: Long = System.currentTimeMillis(),
    ) {
        require(runToken.isNotBlank())
        val rowId = db.insertWithOnConflict(
            TABLE,
            null,
            ContentValues().apply {
                put("singleton_id", SINGLETON_ID)
                put("run_token", runToken)
                put("listed", result.listedCount)
                put("downloaded", result.downloadedCount)
                put("unchanged", result.unchangedCount)
                put("imported", result.importedCount)
                put("duplicates", result.duplicateCount)
                put("rejected", result.rejectedCount)
                put("errors", result.errorCount)
                put("committed_at", committedAt)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        check(rowId != -1L) { "Drive page commit checkpointを保存できませんでした" }
    }

    fun load(db: SQLiteDatabase): GoogleDrivePageCommitCheckpointV134? = db.rawQuery(
        """
        SELECT run_token, listed, downloaded, unchanged, imported, duplicates, rejected, errors, committed_at
        FROM $TABLE
        WHERE singleton_id=?
        LIMIT 1
        """.trimIndent(),
        arrayOf(SINGLETON_ID.toString()),
    ).use { cursor ->
        if (!cursor.moveToFirst()) {
            null
        } else {
            GoogleDrivePageCommitCheckpointV134(
                runToken = cursor.getString(0),
                result = GoogleDriveDirectSyncResult(
                    listedCount = cursor.getInt(1),
                    downloadedCount = cursor.getInt(2),
                    unchangedCount = cursor.getInt(3),
                    importedCount = cursor.getInt(4),
                    duplicateCount = cursor.getInt(5),
                    rejectedCount = cursor.getInt(6),
                    errorCount = cursor.getInt(7),
                ),
                committedAt = cursor.getLong(8),
            )
        }
    }

    fun clear(db: SQLiteDatabase): Int = db.delete(TABLE, "singleton_id=?", arrayOf(SINGLETON_ID.toString()))

    private const val SINGLETON_ID = 1
}

object GoogleDrivePageCommitRecoveryPolicyV134 {
    fun shouldApply(
        statusRunning: Boolean,
        statusRunToken: String?,
        checkpointRunToken: String,
    ): Boolean = statusRunning && statusRunToken == checkpointRunToken
}

object GoogleDrivePageCommitRecoveryV134 {
    fun reconcile(context: Context): Boolean {
        val appContext = context.applicationContext
        val database = ManagementDatabase(appContext)
        try {
            val db = database.writableDatabase
            GoogleDrivePageCommitCheckpointStoreV134.ensureSchema(db)
            val checkpoint = GoogleDrivePageCommitCheckpointStoreV134.load(db) ?: return false

            val statusPreferences = appContext.getSharedPreferences(STATUS_PREFS_NAME, Context.MODE_PRIVATE)
            val statusRunning = statusPreferences.getBoolean("running", false)
            val statusRunToken = statusPreferences.getString("run_token", null)
            val shouldApply = GoogleDrivePageCommitRecoveryPolicyV134.shouldApply(
                statusRunning = statusRunning,
                statusRunToken = statusRunToken,
                checkpointRunToken = checkpoint.runToken,
            )

            if (shouldApply) {
                check(
                    GoogleDriveDirectSyncStatusDurabilityV133.progress(
                        context = appContext,
                        runToken = checkpoint.runToken,
                        result = checkpoint.result,
                    ),
                ) { "Drive page commit checkpointをdirect statusへ反映できませんでした" }
            }

            // progress() の commit が成功した後、または stale checkpoint と確定した後だけ削除する。
            // この削除前に process が終了しても次回同じ処理を再実行できる。
            db.beginTransaction()
            try {
                GoogleDrivePageCommitCheckpointStoreV134.clear(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            return shouldApply
        } finally {
            database.close()
        }
    }

    private const val STATUS_PREFS_NAME = "tsuguregi_plus_drive_api_sync_status"
}

class GoogleDrivePageCommitRecoveryProviderV134 : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return false
        GoogleDriveStartupRecoveryBarrierV132.resetForProcessStart()
        val recovery = runCatching { GoogleDrivePageCommitRecoveryV134.reconcile(appContext) }
        recovery.exceptionOrNull()?.let { error ->
            GoogleDriveStartupRecoveryBarrierV132.block(
                stage = "v1.34 page-commit checkpoint startup recovery",
                error = error,
            )
        }
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
