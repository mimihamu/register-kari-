package jp.co.tenposinfo.register

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri

/**
 * 売上レシート以外の業務帳票でも、送信開始後の通信失敗を自動再試行しないための共通方針。
 * 既存のdocument_print_jobs更新処理は維持し、SQLiteトリガーでRETRYからFAILEDへ補正する。
 */
object DocumentPrintFailurePolicy {
    const val UNKNOWN_DELIVERY_PREFIX = "送信結果が不明です。"

    fun shouldStopAutomaticRetry(error: Throwable): Boolean =
        PrinterRetrySafety.classify(error) == PrinterFailureDisposition.MANUAL_CONFIRMATION_REQUIRED

    fun shouldStopAutomaticRetry(lastError: String?): Boolean =
        lastError?.startsWith(UNKNOWN_DELIVERY_PREFIX) == true

    fun statusAfterFailure(attemptCount: Int, error: Throwable): PrintJobStatus = when {
        shouldStopAutomaticRetry(error) -> PrintJobStatus.FAILED
        attemptCount >= 5 -> PrintJobStatus.FAILED
        else -> PrintJobStatus.RETRY
    }
}

object DocumentPrintSafetySchema {
    private const val TRIGGER_NAME = "trg_document_print_unknown_delivery_stop"

    fun ensure(db: SQLiteDatabase) {
        val prefix = DocumentPrintFailurePolicy.UNKNOWN_DELIVERY_PREFIX.replace("'", "''")
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS $TRIGGER_NAME
            AFTER UPDATE OF status, last_error ON document_print_jobs
            FOR EACH ROW
            WHEN NEW.status = '${PrintJobStatus.RETRY.name}'
              AND NEW.last_error IS NOT NULL
              AND substr(NEW.last_error, 1, length('$prefix')) = '$prefix'
            BEGIN
                UPDATE document_print_jobs
                   SET status = '${PrintJobStatus.FAILED.name}',
                       updated_at = CAST(strftime('%s', 'now') AS INTEGER) * 1000
                 WHERE id = NEW.id;
            END
            """.trimIndent(),
        )
    }
}

/**
 * AdvancedOperationsStoreがdocument_print_jobsを作成した後に安全トリガーを導入する。
 * アプリ起動時に一度実行され、既存DBにも後付けされる。
 */
class DocumentPrintSafetyBootstrapProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return false
        AdvancedOperationsStore(appContext).close()
        val database = RegisterDatabase(appContext)
        return try {
            DocumentPrintSafetySchema.ensure(database.writableDatabase)
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

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
