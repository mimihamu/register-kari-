package jp.co.tenposinfo.register

import android.content.Context

data class SaleReceiptReprintLedgerCursor(
    val requestedAt: Long,
    val auditId: Long,
)

data class SaleReceiptReprintLedgerSnapshot(
    val newest: SaleReceiptReprintLedgerCursor,
)

data class SaleReceiptReprintStablePage(
    val entries: List<SaleReceiptReprintLedgerEntry>,
    val pageSize: Int,
    val totalMatches: Int,
    val nextCursor: SaleReceiptReprintLedgerCursor?,
    val hasNext: Boolean,
    val newerAuditCount: Int,
)

internal object SaleReceiptReprintStablePagingPolicy {
    fun appendSnapshotBound(
        spec: SaleReceiptReprintLedgerSqlQuery,
        snapshot: SaleReceiptReprintLedgerSnapshot,
    ): SaleReceiptReprintLedgerSqlQuery = appendClause(
        spec = spec,
        clause = "(r.requested_at < ? OR (r.requested_at = ? AND r.id <= ?))",
        extraArgs = listOf(
            snapshot.newest.requestedAt.toString(),
            snapshot.newest.requestedAt.toString(),
            snapshot.newest.auditId.toString(),
        ),
    )

    fun appendAfterCursor(
        spec: SaleReceiptReprintLedgerSqlQuery,
        cursor: SaleReceiptReprintLedgerCursor?,
    ): SaleReceiptReprintLedgerSqlQuery = if (cursor == null) {
        spec
    } else {
        appendClause(
            spec = spec,
            clause = "(r.requested_at < ? OR (r.requested_at = ? AND r.id < ?))",
            extraArgs = listOf(
                cursor.requestedAt.toString(),
                cursor.requestedAt.toString(),
                cursor.auditId.toString(),
            ),
        )
    }

    fun appendNewerThanSnapshot(
        spec: SaleReceiptReprintLedgerSqlQuery,
        snapshot: SaleReceiptReprintLedgerSnapshot,
    ): SaleReceiptReprintLedgerSqlQuery = appendClause(
        spec = spec,
        clause = "(r.requested_at > ? OR (r.requested_at = ? AND r.id > ?))",
        extraArgs = listOf(
            snapshot.newest.requestedAt.toString(),
            snapshot.newest.requestedAt.toString(),
            snapshot.newest.auditId.toString(),
        ),
    )

    fun cursorOf(entry: SaleReceiptReprintLedgerEntry): SaleReceiptReprintLedgerCursor =
        SaleReceiptReprintLedgerCursor(entry.requestedAt, entry.auditId)

    private fun appendClause(
        spec: SaleReceiptReprintLedgerSqlQuery,
        clause: String,
        extraArgs: List<String>,
    ): SaleReceiptReprintLedgerSqlQuery = SaleReceiptReprintLedgerSqlQuery(
        whereSql = if (spec.whereSql.isBlank()) "WHERE $clause" else "${spec.whereSql} AND $clause",
        args = spec.args + extraArgs,
    )
}

/**
 * v0.73: SCR-648用の安定スナップショット＋keysetページング。
 * v0.74: snapshotより新しい監査要求の件数も、現在の適用済み検索条件に一致する行だけを数える。
 *
 * 検索適用時に、その条件で最も新しい監査行(requested_at,id)を固定する。
 * その後に新しい再印字要求が追加されても、5秒更新中の現在ページへ割り込ませない。
 * NextはOFFSETではなく最後に表示した(requested_at,id)より古い行だけを取得する。
 *
 * print_jobsの状態・エラーは現在値をJOINするため、印刷状態の更新表示は維持する。
 * 履歴・売上・印刷jobへのUPDATE/DELETEは提供しない。
 */
class SaleReceiptReprintStablePagingStore(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val database = RegisterDatabase(appContext)

    init {
        SaleReceiptReprintAuditStore(appContext).close()
    }

    fun captureSnapshot(
        criteria: SaleReceiptReprintLedgerCriteria,
    ): SaleReceiptReprintLedgerSnapshot? {
        val spec = SaleReceiptReprintLedgerPolicy.buildDatabaseQuery(criteria)
        return database.readableDatabase.rawQuery(
            """
            SELECT r.requested_at, r.id
            ${ledgerFromSql()}
            ${spec.whereSql}
            ORDER BY r.requested_at DESC, r.id DESC
            LIMIT 1
            """.trimIndent(),
            spec.args.toTypedArray(),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null
            else SaleReceiptReprintLedgerSnapshot(
                newest = SaleReceiptReprintLedgerCursor(
                    requestedAt = cursor.getLong(0),
                    auditId = cursor.getLong(1),
                ),
            )
        }
    }

    fun searchStable(
        criteria: SaleReceiptReprintLedgerCriteria,
        snapshot: SaleReceiptReprintLedgerSnapshot?,
        afterCursor: SaleReceiptReprintLedgerCursor? = null,
        pageSize: Int = SaleReceiptReprintLedgerPolicy.DATABASE_PAGE_SIZE,
    ): SaleReceiptReprintStablePage {
        val safePageSize = pageSize.coerceIn(1, SaleReceiptReprintLedgerPolicy.DATABASE_PAGE_SIZE)
        if (snapshot == null) {
            return SaleReceiptReprintStablePage(
                entries = emptyList(),
                pageSize = safePageSize,
                totalMatches = 0,
                nextCursor = null,
                hasNext = false,
                newerAuditCount = 0,
            )
        }

        val base = SaleReceiptReprintLedgerPolicy.buildDatabaseQuery(criteria)
        val snapshotSpec = SaleReceiptReprintStablePagingPolicy.appendSnapshotBound(base, snapshot)
        val pageSpec = SaleReceiptReprintStablePagingPolicy.appendAfterCursor(snapshotSpec, afterCursor)
        val baseFrom = ledgerFromSql()

        val totalMatches = database.readableDatabase.rawQuery(
            "SELECT COUNT(*) $baseFrom ${snapshotSpec.whereSql}",
            snapshotSpec.args.toTypedArray(),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

        val pageArgs = pageSpec.args + (safePageSize + 1).toString()
        val loaded = database.readableDatabase.rawQuery(
            """
            SELECT r.id, r.request_id, r.sale_id,
                   s.total_amount, s.created_at,
                   r.print_job_id, r.operator_name, r.paper_width_mm, r.requested_at,
                   j.status, j.attempt_count, j.last_error
            $baseFrom
            ${pageSpec.whereSql}
            ORDER BY r.requested_at DESC, r.id DESC
            LIMIT ?
            """.trimIndent(),
            pageArgs.toTypedArray(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toLedgerEntry())
            }
        }
        val hasNext = loaded.size > safePageSize
        val entries = loaded.take(safePageSize)
        return SaleReceiptReprintStablePage(
            entries = entries,
            pageSize = safePageSize,
            totalMatches = totalMatches,
            nextCursor = if (hasNext) entries.lastOrNull()?.let(SaleReceiptReprintStablePagingPolicy::cursorOf) else null,
            hasNext = hasNext,
            newerAuditCount = countMatchingNewerThan(criteria, snapshot),
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

    private fun countMatchingNewerThan(
        criteria: SaleReceiptReprintLedgerCriteria,
        snapshot: SaleReceiptReprintLedgerSnapshot,
    ): Int {
        val base = SaleReceiptReprintLedgerPolicy.buildDatabaseQuery(criteria)
        val newerSpec = SaleReceiptReprintStablePagingPolicy.appendNewerThanSnapshot(base, snapshot)
        return database.readableDatabase.rawQuery(
            "SELECT COUNT(*) ${ledgerFromSql()} ${newerSpec.whereSql}",
            newerSpec.args.toTypedArray(),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
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
