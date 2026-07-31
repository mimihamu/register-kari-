package jp.co.tenposinfo.register

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri

object InterruptedPrintRecoveryPolicy {
    const val ERROR_MESSAGE = "前回の印刷処理が途中で終了しました。紙が出ていないことを確認してから手動再試行してください。"

    fun recoveredStatus(previous: PrintJobStatus): PrintJobStatus = when (previous) {
        PrintJobStatus.PRINTING -> PrintJobStatus.FAILED
        else -> previous
    }
}

data class InterruptedPrintRecoveryResult(
    val saleJobs: Int,
    val documentJobs: Int,
) {
    val total: Int get() = saleJobs + documentJobs
}

/**
 * プロセス起動時にPRINTINGのまま残ったジョブを検出する。
 * 端末再起動・アプリ強制終了時は送信済みか判定できないため、
 * RETRYではなくFAILEDへ移し、二重印刷を防止する。
 */
object InterruptedPrintRecovery {
    fun execute(db: SQLiteDatabase, now: Long = System.currentTimeMillis()): InterruptedPrintRecoveryResult {
        val saleJobs = recoverTable(db, "print_jobs", now)
        val documentJobs = if (SchemaMigration.tableExists(db, "document_print_jobs")) {
            recoverTable(db, "document_print_jobs", now)
        } else {
            0
        }
        if (saleJobs + documentJobs > 0 && SchemaMigration.tableExists(db, "operation_audit")) {
            db.insert(
                "operation_audit",
                null,
                ContentValues().apply {
                    put("event_type", "INTERRUPTED_PRINT_RECOVERED")
                    put("reference_id", 0L)
                    put("detail", "売上レシート${saleJobs}件 / 業務帳票${documentJobs}件を要確認へ移動")
                    put("operator_name", "system")
                    put("created_at", now)
                },
            )
        }
        return InterruptedPrintRecoveryResult(saleJobs, documentJobs)
    }

    private fun recoverTable(db: SQLiteDatabase, table: String, now: Long): Int = db.update(
        table,
        ContentValues().apply {
            put("status", PrintJobStatus.FAILED.name)
            put("last_error", InterruptedPrintRecoveryPolicy.ERROR_MESSAGE)
            put("updated_at", now)
        },
        "status = ?",
        arrayOf(PrintJobStatus.PRINTING.name),
    )
}

/** アプリプロセス起動直後に印刷中断ジョブを安全側へ回収する。 */
class PrintQueueRecoveryBootstrapProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return false
        AdvancedOperationsStore(appContext).close()
        val database = RegisterDatabase(appContext)
        return try {
            InterruptedPrintRecovery.execute(database.writableDatabase)
            true
        } finally {
            database.close()
        }
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
