package jp.co.tenposinfo.register

import android.content.Context

enum class SaleReceiptReprintLedgerFilter(val displayName: String) {
    ALL("すべて"),
    ACTION_REQUIRED("要対応"),
    ACTIVE("処理中"),
    COMPLETED("完了"),
    DISCARDED("破棄済み"),
}

data class SaleReceiptReprintLedgerEntry(
    val auditId: Long,
    val requestId: String,
    val saleId: Long,
    val saleAmount: Long,
    val saleCreatedAt: Long,
    val printJobId: Long,
    val operatorName: String,
    val paperWidthMm: Int,
    val requestedAt: Long,
    val status: PrintJobStatus,
    val attemptCount: Int,
    val lastError: String?,
) {
    val failureCategory: UnifiedPrintFailureCategory
        get() = UnifiedPrintFailureClassifier.classify(lastError)
}

data class SaleReceiptReprintLedgerCriteria(
    val filter: SaleReceiptReprintLedgerFilter = SaleReceiptReprintLedgerFilter.ALL,
    val query: String = "",
)

data class SaleReceiptReprintLedgerSummary(
    val total: Int,
    val actionRequired: Int,
    val active: Int,
    val completed: Int,
    val discarded: Int,
) {
    companion object {
        fun from(entries: List<SaleReceiptReprintLedgerEntry>) = SaleReceiptReprintLedgerSummary(
            total = entries.size,
            actionRequired = entries.count {
                it.status == PrintJobStatus.RETRY || it.status == PrintJobStatus.FAILED
            },
            active = entries.count {
                it.status in setOf(
                    PrintJobStatus.PENDING,
                    PrintJobStatus.RETRY,
                    PrintJobStatus.FAILED,
                    PrintJobStatus.PRINTING,
                )
            },
            completed = entries.count { it.status == PrintJobStatus.COMPLETED },
            discarded = entries.count { it.status == PrintJobStatus.DISCARDED },
        )
    }
}

object SaleReceiptReprintLedgerPolicy {
    const val LOAD_LIMIT = 1_000

    fun filter(
        entries: List<SaleReceiptReprintLedgerEntry>,
        criteria: SaleReceiptReprintLedgerCriteria,
    ): List<SaleReceiptReprintLedgerEntry> = entries.filter { entry ->
        val statusMatches = when (criteria.filter) {
            SaleReceiptReprintLedgerFilter.ALL -> true
            SaleReceiptReprintLedgerFilter.ACTION_REQUIRED -> entry.status in setOf(
                PrintJobStatus.RETRY,
                PrintJobStatus.FAILED,
            )
            SaleReceiptReprintLedgerFilter.ACTIVE -> entry.status in setOf(
                PrintJobStatus.PENDING,
                PrintJobStatus.RETRY,
                PrintJobStatus.FAILED,
                PrintJobStatus.PRINTING,
            )
            SaleReceiptReprintLedgerFilter.COMPLETED -> entry.status == PrintJobStatus.COMPLETED
            SaleReceiptReprintLedgerFilter.DISCARDED -> entry.status == PrintJobStatus.DISCARDED
        }
        if (!statusMatches) return@filter false

        val query = criteria.query.trim()
        if (query.isEmpty()) return@filter true
        buildString {
            append(entry.saleId).append(' ')
            append(entry.printJobId).append(' ')
            append(entry.auditId).append(' ')
            append(entry.operatorName).append(' ')
            append(entry.status.name).append(' ')
            append(entry.paperWidthMm).append(' ')
            append(entry.failureCategory.displayName).append(' ')
            append(entry.lastError.orEmpty())
        }.contains(query, ignoreCase = true)
    }
}

/**
 * 通常レシート再印字要求の全売上横断・読み取り専用台帳。
 * 再試行、破棄、強制印刷などの書込操作はここでは提供しない。
 */
class SaleReceiptReprintOperationsStore(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val database = RegisterDatabase(appContext)

    init {
        // v0.68監査テーブルがまだ一度も使用されていない端末でも空台帳を開けるよう、
        // 既存監査ストアの追加型CREATE IF NOT EXISTSだけを実行する。
        SaleReceiptReprintAuditStore(appContext).close()
    }

    fun list(limit: Int = SaleReceiptReprintLedgerPolicy.LOAD_LIMIT): List<SaleReceiptReprintLedgerEntry> {
        val safeLimit = limit.coerceIn(1, SaleReceiptReprintLedgerPolicy.LOAD_LIMIT)
        return database.readableDatabase.rawQuery(
            """
            SELECT r.id, r.request_id, r.sale_id,
                   s.total_amount, s.created_at,
                   r.print_job_id, r.operator_name, r.paper_width_mm, r.requested_at,
                   j.status, j.attempt_count, j.last_error
            FROM ${SaleReceiptReprintAuditStore.TABLE} r
            INNER JOIN print_jobs j ON j.id = r.print_job_id
            INNER JOIN sales s ON s.id = r.sale_id
            ORDER BY r.requested_at DESC, r.id DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(safeLimit.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        SaleReceiptReprintLedgerEntry(
                            auditId = cursor.getLong(0),
                            requestId = cursor.getString(1),
                            saleId = cursor.getLong(2),
                            saleAmount = cursor.getLong(3),
                            saleCreatedAt = cursor.getLong(4),
                            printJobId = cursor.getLong(5),
                            operatorName = cursor.getString(6),
                            paperWidthMm = cursor.getInt(7),
                            requestedAt = cursor.getLong(8),
                            status = runCatching { PrintJobStatus.valueOf(cursor.getString(9)) }
                                .getOrDefault(PrintJobStatus.FAILED),
                            attemptCount = cursor.getInt(10),
                            lastError = if (cursor.isNull(11)) null else cursor.getString(11),
                        ),
                    )
                }
            }
        }
    }

    override fun close() = database.close()
}
