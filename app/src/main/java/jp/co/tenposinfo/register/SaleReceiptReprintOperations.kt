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

internal data class SaleReceiptReprintLedgerSqlQuery(
    val whereSql: String,
    val args: List<String>,
)

internal data class SaleReceiptReprintLedgerPage(
    val entries: List<SaleReceiptReprintLedgerEntry>,
    val offset: Int,
    val pageSize: Int,
    val totalMatches: Int,
    val hasNext: Boolean,
)

object SaleReceiptReprintLedgerPolicy {
    const val DATABASE_PAGE_SIZE = 200

    /**
     * v0.69までのメモリ内フィルタ互換。純粋ポリシーの単体テストと既存呼出しを維持する。
     * v0.70の運用画面は buildDatabaseQuery() + Store.search() を使用する。
     */
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
            append(entry.requestId).append(' ')
            append(entry.operatorName).append(' ')
            append(entry.status.name).append(' ')
            append(entry.paperWidthMm).append(' ')
            append(entry.saleAmount).append(' ')
            append(entry.failureCategory.displayName).append(' ')
            append(entry.lastError.orEmpty())
        }.contains(query, ignoreCase = true)
    }

    internal fun buildDatabaseQuery(criteria: SaleReceiptReprintLedgerCriteria): SaleReceiptReprintLedgerSqlQuery {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()

        when (criteria.filter) {
            SaleReceiptReprintLedgerFilter.ALL -> Unit
            SaleReceiptReprintLedgerFilter.ACTION_REQUIRED -> {
                clauses += "j.status IN (?, ?)"
                args += PrintJobStatus.RETRY.name
                args += PrintJobStatus.FAILED.name
            }
            SaleReceiptReprintLedgerFilter.ACTIVE -> {
                clauses += "j.status IN (?, ?, ?, ?)"
                args += PrintJobStatus.PENDING.name
                args += PrintJobStatus.RETRY.name
                args += PrintJobStatus.FAILED.name
                args += PrintJobStatus.PRINTING.name
            }
            SaleReceiptReprintLedgerFilter.COMPLETED -> {
                clauses += "j.status = ?"
                args += PrintJobStatus.COMPLETED.name
            }
            SaleReceiptReprintLedgerFilter.DISCARDED -> {
                clauses += "j.status = ?"
                args += PrintJobStatus.DISCARDED.name
            }
        }

        val query = criteria.query.trim().removePrefix("#").lowercase()
        if (query.isNotBlank()) {
            val pattern = "%${escapeLike(query)}%"
            val searchable = listOf(
                "CAST(r.id AS TEXT) LIKE ? ESCAPE '\\'",
                "LOWER(r.request_id) LIKE ? ESCAPE '\\'",
                "CAST(r.sale_id AS TEXT) LIKE ? ESCAPE '\\'",
                "CAST(r.print_job_id AS TEXT) LIKE ? ESCAPE '\\'",
                "LOWER(r.operator_name) LIKE ? ESCAPE '\\'",
                "CAST(r.paper_width_mm AS TEXT) LIKE ? ESCAPE '\\'",
                "CAST(s.total_amount AS TEXT) LIKE ? ESCAPE '\\'",
                "LOWER(j.status) LIKE ? ESCAPE '\\'",
                "LOWER(COALESCE(j.last_error, '')) LIKE ? ESCAPE '\\'",
            )
            clauses += searchable.joinToString(" OR ", prefix = "(", postfix = ")")
            repeat(searchable.size) { args += pattern }
        }

        return SaleReceiptReprintLedgerSqlQuery(
            whereSql = if (clauses.isEmpty()) "" else clauses.joinToString(" AND ", prefix = "WHERE "),
            args = args,
        )
    }

    private fun escapeLike(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            when (ch) {
                '\\', '%', '_' -> append('\\').append(ch)
                else -> append(ch)
            }
        }
    }
}

/**
 * 通常レシート再印字要求の全売上横断・読み取り専用台帳。
 * v0.70では検索条件をSQLiteへ直接渡し、全履歴を対象に200件単位でページングする。
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

    fun search(
        criteria: SaleReceiptReprintLedgerCriteria,
        offset: Int = 0,
        pageSize: Int = SaleReceiptReprintLedgerPolicy.DATABASE_PAGE_SIZE,
    ): SaleReceiptReprintLedgerPage {
        val safeOffset = offset.coerceAtLeast(0)
        val safePageSize = pageSize.coerceIn(1, SaleReceiptReprintLedgerPolicy.DATABASE_PAGE_SIZE)
        val spec = SaleReceiptReprintLedgerPolicy.buildDatabaseQuery(criteria)
        val baseFrom = ledgerFromSql()
        val totalMatches = database.readableDatabase.rawQuery(
            "SELECT COUNT(*) $baseFrom ${spec.whereSql}",
            spec.args.toTypedArray(),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

        val selectionArgs = spec.args.toMutableList().apply {
            add((safePageSize + 1).toString())
            add(safeOffset.toString())
        }
        val loaded = database.readableDatabase.rawQuery(
            """
            SELECT r.id, r.request_id, r.sale_id,
                   s.total_amount, s.created_at,
                   r.print_job_id, r.operator_name, r.paper_width_mm, r.requested_at,
                   j.status, j.attempt_count, j.last_error
            $baseFrom
            ${spec.whereSql}
            ORDER BY r.requested_at DESC, r.id DESC
            LIMIT ? OFFSET ?
            """.trimIndent(),
            selectionArgs.toTypedArray(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toLedgerEntry())
            }
        }
        val hasNext = loaded.size > safePageSize
        return SaleReceiptReprintLedgerPage(
            entries = loaded.take(safePageSize),
            offset = safeOffset,
            pageSize = safePageSize,
            totalMatches = totalMatches,
            hasNext = hasNext,
        )
    }

    fun summary(): SaleReceiptReprintLedgerSummary = database.readableDatabase.rawQuery(
        """
        SELECT
            COUNT(*),
            COALESCE(SUM(CASE WHEN j.status IN ('RETRY', 'FAILED') THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN j.status IN ('PENDING', 'RETRY', 'FAILED', 'PRINTING') THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN j.status = 'COMPLETED' THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN j.status = 'DISCARDED' THEN 1 ELSE 0 END), 0)
        ${ledgerFromSql()}
        """.trimIndent(),
        null,
    ).use { cursor ->
        if (!cursor.moveToFirst()) {
            SaleReceiptReprintLedgerSummary(0, 0, 0, 0, 0)
        } else {
            SaleReceiptReprintLedgerSummary(
                total = cursor.getInt(0),
                actionRequired = cursor.getInt(1),
                active = cursor.getInt(2),
                completed = cursor.getInt(3),
                discarded = cursor.getInt(4),
            )
        }
    }

    private fun ledgerFromSql(): String = """
        FROM ${SaleReceiptReprintAuditStore.TABLE} r
        INNER JOIN print_jobs j ON j.id = r.print_job_id
        INNER JOIN sales s ON s.id = r.sale_id
    """.trimIndent()

    private fun android.database.Cursor.toLedgerEntry() = SaleReceiptReprintLedgerEntry(
        auditId = getLong(0),
        requestId = getString(1),
        saleId = getLong(2),
        saleAmount = getLong(3),
        saleCreatedAt = getLong(4),
        printJobId = getLong(5),
        operatorName = getString(6),
        paperWidthMm = getInt(7),
        requestedAt = getLong(8),
        status = runCatching { PrintJobStatus.valueOf(getString(9)) }.getOrDefault(PrintJobStatus.FAILED),
        attemptCount = getInt(10),
        lastError = if (isNull(11)) null else getString(11),
    )

    override fun close() = database.close()
}
