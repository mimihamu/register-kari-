package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase

internal enum class ReceiptVoucherBatchPrintStatus(val displayName: String) {
    COMMITTED("印刷待ち"),
    PRINTING("印刷中"),
    PARTIAL("一部印刷・要再開"),
    PRINTED("印刷完了"),
}

internal data class ReceiptVoucherBatchPrintItemV135(
    val sequenceNo: Int,
    val issuanceId: Long,
    val jobId: Long?,
    val status: PrintJobStatus?,
    val attemptCount: Int,
    val lastError: String?,
    val updatedAt: Long?,
)

internal data class ReceiptVoucherBatchProgressV135(
    val batchId: Long,
    val saleId: Long,
    val copyCount: Int,
    val printedCount: Int,
    val firstUnprintedSequence: Int?,
    val status: ReceiptVoucherBatchPrintStatus,
    val items: List<ReceiptVoucherBatchPrintItemV135>,
) {
    val remainingCount: Int get() = (copyCount - printedCount).coerceAtLeast(0)
    val resumable: Boolean get() = status != ReceiptVoucherBatchPrintStatus.PRINTED &&
        items.none { it.status == PrintJobStatus.DISCARDED || it.jobId == null } &&
        items.none { it.status == PrintJobStatus.SENDING || it.status == PrintJobStatus.PRINTING }
}

internal object ReceiptVoucherBatchRecoveryPolicyV135 {
    fun summarize(
        batchId: Long,
        saleId: Long,
        copyCount: Int,
        items: List<ReceiptVoucherBatchPrintItemV135>,
    ): ReceiptVoucherBatchProgressV135 {
        val ordered = items.sortedBy { it.sequenceNo }
        val printed = ordered.count { it.status == PrintJobStatus.COMPLETED }
        val firstUnprinted = ordered.firstOrNull { it.status != PrintJobStatus.COMPLETED }?.sequenceNo
        val hasFailure = ordered.any {
            it.jobId == null || it.status in setOf(PrintJobStatus.FAILED, PrintJobStatus.DISCARDED)
        }
        val hasStarted = ordered.any {
            it.attemptCount > 0 || it.status in setOf(PrintJobStatus.SENDING, PrintJobStatus.PRINTING, PrintJobStatus.RETRY)
        }
        val status = when {
            ordered.isNotEmpty() && printed == copyCount && ordered.size == copyCount ->
                ReceiptVoucherBatchPrintStatus.PRINTED
            hasFailure || printed > 0 -> ReceiptVoucherBatchPrintStatus.PARTIAL
            hasStarted -> ReceiptVoucherBatchPrintStatus.PRINTING
            else -> ReceiptVoucherBatchPrintStatus.COMMITTED
        }
        return ReceiptVoucherBatchProgressV135(
            batchId = batchId,
            saleId = saleId,
            copyCount = copyCount,
            printedCount = printed,
            firstUnprintedSequence = firstUnprinted,
            status = status,
            items = ordered,
        )
    }

    fun sequencesToResume(progress: ReceiptVoucherBatchProgressV135): List<Int> =
        progress.items
            .filter { it.status != PrintJobStatus.COMPLETED }
            .map { it.sequenceNo }
}

/**
 * v1.35 RCP-021: 一括領収書は既存の統合印刷キューを正本として進捗を判定する。
 * 再開時に新しい領収書・新しい印刷ジョブを作らず、失敗した元ジョブだけ RETRY へ戻す。
 * COMPLETED は絶対に更新しないため、途中成功済み票の二重印刷を防ぐ。
 */
internal class ReceiptVoucherBatchRecoveryStoreV135(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val schemaGuard = ReceiptVoucherStore(appContext)
    private val database = RegisterDatabase(appContext)
    private val db: SQLiteDatabase = database.writableDatabase

    init {
        ensureAuditSchema()
    }

    override fun close() {
        database.close()
        schemaGuard.close()
    }

    fun listForSale(saleId: Long): List<ReceiptVoucherBatchProgressV135> {
        val batches = db.rawQuery(
            "SELECT id, copy_count FROM receipt_voucher_batches WHERE sale_id = ? ORDER BY created_at DESC, id DESC",
            arrayOf(saleId.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getInt(1))
            }
        }
        return batches.map { (batchId, copies) -> load(batchId, saleId, copies) }
    }

    fun load(batchId: Long): ReceiptVoucherBatchProgressV135 {
        val header = db.rawQuery(
            "SELECT sale_id, copy_count FROM receipt_voucher_batches WHERE id = ? LIMIT 1",
            arrayOf(batchId.toString()),
        ).use { cursor ->
            require(cursor.moveToFirst()) { "領収書発行グループ RG-$batchId が見つかりません" }
            cursor.getLong(0) to cursor.getInt(1)
        }
        return load(batchId, header.first, header.second)
    }

    fun resumeUnprinted(batchId: Long, operatorName: String): ReceiptVoucherBatchProgressV135 {
        val actor = ReceiptVoucherPolicy.normalizeRequired(operatorName, "再開担当者")
        val before = load(batchId)
        require(before.status != ReceiptVoucherBatchPrintStatus.PRINTED) { "この一括発行は全票印刷済みです" }
        require(before.items.none { it.jobId == null }) { "印刷ジョブ欠落があるため自動再開できません" }
        require(before.items.none { it.status == PrintJobStatus.DISCARDED }) {
            "破棄済み印刷ジョブがあるため、統合印刷キューで確認してください"
        }
        require(before.items.none { it.status == PrintJobStatus.SENDING || it.status == PrintJobStatus.PRINTING }) {
            "送信中または印刷済みの可能性がある票があります。統合印刷キューで完了扱い／再印刷を判断してください"
        }
        val now = System.currentTimeMillis()
        val failedJobIds = before.items
            .filter { it.status == PrintJobStatus.FAILED }
            .mapNotNull { it.jobId }

        db.beginTransaction()
        try {
            failedJobIds.forEach { jobId ->
                db.update(
                    "document_print_jobs",
                    ContentValues().apply {
                        put("status", PrintJobStatus.RETRY.name)
                        putNull("last_error")
                        put("updated_at", now)
                    },
                    "id = ? AND status = ?",
                    arrayOf(jobId.toString(), PrintJobStatus.FAILED.name),
                )
            }
            val resumeSequences = ReceiptVoucherBatchRecoveryPolicyV135.sequencesToResume(before)
            db.insertOrThrow(
                "operation_audit",
                null,
                ContentValues().apply {
                    put("event_type", "RECEIPT_BATCH_RESUME")
                    put("reference_id", batchId)
                    put(
                        "detail",
                        "RG-$batchId / 印刷済み=${before.printedCount}/${before.copyCount} / 再開対象=${resumeSequences.joinToString(",")}",
                    )
                    put("operator_name", actor)
                    put("created_at", now)
                },
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        AutomaticPrintScheduler.enqueueNow(appContext)
        return load(batchId)
    }

    private fun load(batchId: Long, saleId: Long, copies: Int): ReceiptVoucherBatchProgressV135 {
        val items = db.rawQuery(
            """
            SELECT i.sequence_no,
                   i.id,
                   j.id,
                   j.status,
                   j.attempt_count,
                   j.last_error,
                   j.updated_at
            FROM receipt_voucher_issuances i
            LEFT JOIN document_print_jobs j
              ON j.reference_id = i.id
             AND j.document_type = ?
             AND NOT EXISTS (
                 SELECT 1 FROM receipt_voucher_reprints r
                  WHERE r.print_job_id = j.id
             )
            WHERE i.batch_id = ?
            ORDER BY i.sequence_no ASC, i.id ASC
            """.trimIndent(),
            arrayOf(OperationDocumentType.RECEIPT_VOUCHER.name, batchId.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ReceiptVoucherBatchPrintItemV135(
                            sequenceNo = cursor.getInt(0),
                            issuanceId = cursor.getLong(1),
                            jobId = if (cursor.isNull(2)) null else cursor.getLong(2),
                            status = if (cursor.isNull(3)) null else runCatching {
                                PrintJobStatus.valueOf(cursor.getString(3))
                            }.getOrNull(),
                            attemptCount = if (cursor.isNull(4)) 0 else cursor.getInt(4),
                            lastError = if (cursor.isNull(5)) null else cursor.getString(5),
                            updatedAt = if (cursor.isNull(6)) null else cursor.getLong(6),
                        ),
                    )
                }
            }
        }
        return ReceiptVoucherBatchRecoveryPolicyV135.summarize(batchId, saleId, copies, items)
    }

    private fun ensureAuditSchema() {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS operation_audit (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_type TEXT NOT NULL,
                reference_id INTEGER NOT NULL,
                detail TEXT NOT NULL,
                operator_name TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }
}

internal class ReceiptVoucherBatchSettingsV135(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "receipt_voucher_batch_settings_v135",
        Context.MODE_PRIVATE,
    )

    fun maxBatchReceiptCopies(): Int = preferences
        .getInt(KEY_MAX_BATCH_COPIES, DEFAULT_MAX_BATCH_COPIES)
        .coerceIn(MIN_BATCH_COPIES, MAX_BATCH_COPIES)

    fun setMaxBatchReceiptCopies(value: Int) {
        require(value in MIN_BATCH_COPIES..MAX_BATCH_COPIES) {
            "一括領収書の上限枚数は${MIN_BATCH_COPIES}～${MAX_BATCH_COPIES}枚で指定してください"
        }
        preferences.edit().putInt(KEY_MAX_BATCH_COPIES, value).apply()
    }

    companion object {
        const val MIN_BATCH_COPIES = 1
        const val MAX_BATCH_COPIES = 999
        const val DEFAULT_MAX_BATCH_COPIES = 100
        private const val KEY_MAX_BATCH_COPIES = "receipt.maxBatchReceiptCopies"
    }
}
