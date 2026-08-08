package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.util.UUID

internal data class SaleReceiptReprintRequestRecord(
    val id: Long,
    val requestId: String,
    val saleId: Long,
    val printJobId: Long,
    val operatorName: String,
    val paperWidthMm: Int,
    val requestedAt: Long,
    val printStatus: PrintJobStatus,
    val attemptCount: Int,
    val lastError: String?,
)

internal data class SaleReceiptReprintRequestResult(
    val record: SaleReceiptReprintRequestRecord,
    val newlyCreated: Boolean,
)

internal object SaleReceiptReprintAuditPolicy {
    const val HISTORY_LIMIT = 100

    fun normalizeRequestId(raw: String): String =
        runCatching { UUID.fromString(raw.trim()).toString() }
            .getOrElse { throw IllegalArgumentException("再印字要求IDが不正です") }

    fun normalizeOperatorName(raw: String): String =
        raw.trim().takeIf { it.isNotBlank() }?.take(100)
            ?: throw IllegalArgumentException("再印字担当者が必要です")

    fun normalizePaperWidth(widthMm: Int): Int = if (widthMm >= 80) 80 else 58
}

/**
 * 通常レシート再印字要求の追記専用監査ストア。
 *
 * - request UUID により同じ要求を二重登録しない。
 * - print_jobs と監査行を同一SQLiteトランザクションで追加する。
 * - 売上・税・支払データは更新しない。
 * - 監査行の更新・削除APIは提供しない。
 */
internal class SaleReceiptReprintAuditStore(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val database = RegisterDatabase(appContext)
    private val db: SQLiteDatabase get() = database.writableDatabase

    init {
        ensureSchema(db)
    }

    override fun close() = database.close()

    fun request(
        saleId: Long,
        operatorName: String,
        requestId: String,
    ): SaleReceiptReprintRequestResult {
        require(saleId > 0L) { "売上No.が不正です" }
        val normalizedRequestId = SaleReceiptReprintAuditPolicy.normalizeRequestId(requestId)
        val normalizedOperator = SaleReceiptReprintAuditPolicy.normalizeOperatorName(operatorName)
        val paperWidthMm = SaleReceiptReprintAuditPolicy.normalizePaperWidth(
            PrinterPaperSettingPolicy.currentPaper(appContext).widthMm,
        )
        val now = System.currentTimeMillis()

        db.beginTransaction()
        try {
            findByRequestId(db, normalizedRequestId)?.let { existing ->
                require(existing.saleId == saleId) { "再印字要求IDが別の売上に使用されています" }
                db.setTransactionSuccessful()
                return SaleReceiptReprintRequestResult(existing, newlyCreated = false)
            }
            require(saleExists(db, saleId)) { "売上No.$saleId は見つかりません" }

            val jobId = db.insertOrThrow(
                "print_jobs",
                null,
                ContentValues().apply {
                    put("sale_id", saleId)
                    put("paper_width_mm", paperWidthMm)
                    put("status", PrintJobStatus.PENDING.name)
                    put("attempt_count", 0)
                    putNull("last_error")
                    put("created_at", now)
                    put("updated_at", now)
                },
            )
            val auditId = db.insertOrThrow(
                TABLE,
                null,
                ContentValues().apply {
                    put("request_id", normalizedRequestId)
                    put("sale_id", saleId)
                    put("print_job_id", jobId)
                    put("operator_name", normalizedOperator)
                    put("paper_width_mm", paperWidthMm)
                    put("requested_at", now)
                },
            )
            val record = SaleReceiptReprintRequestRecord(
                id = auditId,
                requestId = normalizedRequestId,
                saleId = saleId,
                printJobId = jobId,
                operatorName = normalizedOperator,
                paperWidthMm = paperWidthMm,
                requestedAt = now,
                printStatus = PrintJobStatus.PENDING,
                attemptCount = 0,
                lastError = null,
            )
            db.setTransactionSuccessful()
            return SaleReceiptReprintRequestResult(record, newlyCreated = true)
        } finally {
            db.endTransaction()
        }
    }

    fun listForSale(
        saleId: Long,
        limit: Int = SaleReceiptReprintAuditPolicy.HISTORY_LIMIT,
    ): List<SaleReceiptReprintRequestRecord> {
        if (saleId <= 0L) return emptyList()
        val safeLimit = limit.coerceIn(1, SaleReceiptReprintAuditPolicy.HISTORY_LIMIT)
        return db.rawQuery(
            """
            SELECT r.id, r.request_id, r.sale_id, r.print_job_id,
                   r.operator_name, r.paper_width_mm, r.requested_at,
                   j.status, j.attempt_count, j.last_error
            FROM $TABLE r
            INNER JOIN print_jobs j ON j.id = r.print_job_id
            WHERE r.sale_id = ?
            ORDER BY r.requested_at DESC, r.id DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(saleId.toString(), safeLimit.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toReprintRequest())
            }
        }
    }

    private fun saleExists(db: SQLiteDatabase, saleId: Long): Boolean = db.rawQuery(
        "SELECT 1 FROM sales WHERE id = ? LIMIT 1",
        arrayOf(saleId.toString()),
    ).use { it.moveToFirst() }

    private fun findByRequestId(
        db: SQLiteDatabase,
        requestId: String,
    ): SaleReceiptReprintRequestRecord? = db.rawQuery(
        """
        SELECT r.id, r.request_id, r.sale_id, r.print_job_id,
               r.operator_name, r.paper_width_mm, r.requested_at,
               j.status, j.attempt_count, j.last_error
        FROM $TABLE r
        INNER JOIN print_jobs j ON j.id = r.print_job_id
        WHERE r.request_id = ?
        LIMIT 1
        """.trimIndent(),
        arrayOf(requestId),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toReprintRequest() else null }

    private fun Cursor.toReprintRequest(): SaleReceiptReprintRequestRecord =
        SaleReceiptReprintRequestRecord(
            id = getLong(0),
            requestId = getString(1),
            saleId = getLong(2),
            printJobId = getLong(3),
            operatorName = getString(4),
            paperWidthMm = getInt(5),
            requestedAt = getLong(6),
            printStatus = runCatching { PrintJobStatus.valueOf(getString(7)) }.getOrDefault(PrintJobStatus.FAILED),
            attemptCount = getInt(8),
            lastError = if (isNull(9)) null else getString(9),
        )

    private fun ensureSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                request_id TEXT NOT NULL UNIQUE,
                sale_id INTEGER NOT NULL,
                print_job_id INTEGER NOT NULL UNIQUE,
                operator_name TEXT NOT NULL,
                paper_width_mm INTEGER NOT NULL,
                requested_at INTEGER NOT NULL,
                FOREIGN KEY(sale_id) REFERENCES sales(id),
                FOREIGN KEY(print_job_id) REFERENCES print_jobs(id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_sale_receipt_reprint_sale_time ON $TABLE(sale_id, requested_at DESC, id DESC)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_sale_receipt_reprint_requested_time ON $TABLE(requested_at DESC, id DESC)",
        )
    }

    companion object {
        const val TABLE = "sale_receipt_reprint_requests"
    }
}