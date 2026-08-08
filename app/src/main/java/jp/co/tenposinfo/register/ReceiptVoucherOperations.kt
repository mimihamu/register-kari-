package jp.co.tenposinfo.register

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class ReceiptVoucherPrintKind(val displayName: String) {
    ORIGINAL("初回発行"),
    REPRINT("再発行"),
}

data class ReceiptVoucherPrintEventRecord(
    val jobId: Long,
    val issuanceId: Long,
    val kind: ReceiptVoucherPrintKind,
    val paperWidthMm: Int,
    val status: PrintJobStatus,
    val attemptCount: Int,
    val lastError: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val reprintEventId: Long? = null,
    val reprintedBy: String? = null,
    val reprintedAt: Long? = null,
) {
    val failureCategory: UnifiedPrintFailureCategory
        get() = UnifiedPrintFailureClassifier.classify(lastError)
}

data class ReceiptVoucherLedgerEntry(
    val receipt: ReceiptVoucherRecord,
    val printEvents: List<ReceiptVoucherPrintEventRecord>,
) {
    val latestEvent: ReceiptVoucherPrintEventRecord?
        get() = printEvents.maxWithOrNull(compareBy<ReceiptVoucherPrintEventRecord> { it.createdAt }.thenBy { it.jobId })

    val reprintCount: Int
        get() = printEvents.count { it.kind == ReceiptVoucherPrintKind.REPRINT }

    val actionRequired: Boolean
        get() = printEvents.any { it.status in setOf(PrintJobStatus.RETRY, PrintJobStatus.FAILED) }

    val active: Boolean
        get() = printEvents.any {
            it.status in setOf(
                PrintJobStatus.PENDING,
                PrintJobStatus.PRINTING,
                PrintJobStatus.RETRY,
                PrintJobStatus.FAILED,
            )
        }
}

data class ReceiptVoucherLedgerSummary(
    val receiptCount: Int,
    val printJobCount: Int,
    val actionRequiredReceipts: Int,
    val activeReceipts: Int,
    val completedPrintJobs: Int,
    val reprintEvents: Int,
    val missingPrintJobs: Int,
) {
    companion object {
        fun from(entries: List<ReceiptVoucherLedgerEntry>): ReceiptVoucherLedgerSummary =
            ReceiptVoucherLedgerSummary(
                receiptCount = entries.size,
                printJobCount = entries.sumOf { it.printEvents.size },
                actionRequiredReceipts = entries.count { it.actionRequired },
                activeReceipts = entries.count { it.active },
                completedPrintJobs = entries.sumOf { entry ->
                    entry.printEvents.count { it.status == PrintJobStatus.COMPLETED }
                },
                reprintEvents = entries.sumOf { it.reprintCount },
                missingPrintJobs = entries.count { it.printEvents.isEmpty() },
            )
    }
}

enum class ReceiptVoucherLedgerFilter(val displayName: String) {
    ALL("すべて"),
    ACTION_REQUIRED("要対応"),
    ACTIVE("未完了"),
    COMPLETED("完了"),
    REPRINTED("再発行あり"),
}

data class ReceiptVoucherLedgerCriteria(
    val filter: ReceiptVoucherLedgerFilter = ReceiptVoucherLedgerFilter.ALL,
    val query: String = "",
)

object ReceiptVoucherLedgerPolicy {
    fun filter(
        entries: List<ReceiptVoucherLedgerEntry>,
        criteria: ReceiptVoucherLedgerCriteria,
    ): List<ReceiptVoucherLedgerEntry> = entries.filter { entry ->
        val statusMatches = when (criteria.filter) {
            ReceiptVoucherLedgerFilter.ALL -> true
            ReceiptVoucherLedgerFilter.ACTION_REQUIRED -> entry.actionRequired || entry.printEvents.isEmpty()
            ReceiptVoucherLedgerFilter.ACTIVE -> entry.active || entry.printEvents.isEmpty()
            ReceiptVoucherLedgerFilter.COMPLETED -> entry.printEvents.isNotEmpty() &&
                entry.printEvents.all { it.status == PrintJobStatus.COMPLETED }
            ReceiptVoucherLedgerFilter.REPRINTED -> entry.reprintCount > 0
        }
        if (!statusMatches) return@filter false
        val query = criteria.query.trim()
        if (query.isEmpty()) return@filter true
        val haystack = buildString {
            append("R").append(entry.receipt.id).append(' ')
            append(entry.receipt.id).append(' ')
            append(entry.receipt.saleId).append(' ')
            append(entry.receipt.addressee).append(' ')
            append(entry.receipt.purpose).append(' ')
            append(entry.receipt.operatorName).append(' ')
            entry.printEvents.forEach { event ->
                append(event.jobId).append(' ')
                append(event.status.name).append(' ')
                append(printStatusLabel(event.status)).append(' ')
                append(event.failureCategory.displayName).append(' ')
                append(event.lastError.orEmpty()).append(' ')
                append(event.reprintedBy.orEmpty()).append(' ')
            }
        }
        haystack.contains(query, ignoreCase = true)
    }

    fun printStatusLabel(status: PrintJobStatus): String = when (status) {
        PrintJobStatus.PENDING -> "待機"
        PrintJobStatus.PRINTING -> "印刷中"
        PrintJobStatus.COMPLETED -> "完了"
        PrintJobStatus.RETRY -> "再試行待ち"
        PrintJobStatus.FAILED -> "要確認"
        PrintJobStatus.DISCARDED -> "破棄済み"
    }

    fun latestStatusLabel(entry: ReceiptVoucherLedgerEntry): String = when {
        entry.printEvents.isEmpty() -> "印刷ジョブなし"
        entry.actionRequired -> "要対応"
        else -> entry.latestEvent?.let { printStatusLabel(it.status) } ?: "印刷ジョブなし"
    }

    fun needsUnifiedQueue(entry: ReceiptVoucherLedgerEntry): Boolean =
        entry.printEvents.isEmpty() || entry.active
}

/**
 * 領収書の発行履歴と既存の統合印刷キュー／再発行イベントを読み取り専用で結合する。
 * ここから元売上・発行履歴・再発行履歴を更新または削除しない。
 */
class ReceiptVoucherOperationsStore(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val schemaGuard = ReceiptVoucherStore(appContext)
    private val database = RegisterDatabase(appContext)
    private val db = database.readableDatabase

    override fun close() {
        database.close()
        schemaGuard.close()
    }

    fun listLedger(limit: Int = 500): List<ReceiptVoucherLedgerEntry> {
        val receipts = db.query(
            "receipt_voucher_issuances",
            arrayOf(
                "id",
                "batch_id",
                "sale_id",
                "sequence_no",
                "sequence_count",
                "amount",
                "addressee",
                "purpose",
                "operator_name",
                "created_at",
            ),
            null,
            null,
            null,
            null,
            "created_at DESC, id DESC",
            limit.coerceIn(1, 2_000).toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ReceiptVoucherRecord(
                            id = cursor.getLong(0),
                            batchId = cursor.getLong(1),
                            saleId = cursor.getLong(2),
                            sequenceNo = cursor.getInt(3),
                            sequenceCount = cursor.getInt(4),
                            amount = cursor.getLong(5),
                            addressee = cursor.getString(6),
                            purpose = cursor.getString(7),
                            operatorName = cursor.getString(8),
                            createdAt = cursor.getLong(9),
                        ),
                    )
                }
            }
        }
        if (receipts.isEmpty()) return emptyList()

        val receiptIds = receipts.map { it.id }.toSet()
        val events = db.rawQuery(
            """
            SELECT j.id,
                   j.reference_id,
                   j.paper_width_mm,
                   j.status,
                   j.attempt_count,
                   j.last_error,
                   j.created_at,
                   j.updated_at,
                   r.id,
                   r.operator_name,
                   r.created_at
            FROM document_print_jobs j
            LEFT JOIN receipt_voucher_reprints r
              ON r.print_job_id = j.id
             AND r.issuance_id = j.reference_id
            WHERE j.document_type = ?
            ORDER BY j.created_at ASC, j.id ASC
            """.trimIndent(),
            arrayOf(OperationDocumentType.RECEIPT_VOUCHER.name),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val issuanceId = cursor.getLong(1)
                    if (issuanceId !in receiptIds) continue
                    val hasReprint = !cursor.isNull(8)
                    add(
                        ReceiptVoucherPrintEventRecord(
                            jobId = cursor.getLong(0),
                            issuanceId = issuanceId,
                            kind = if (hasReprint) ReceiptVoucherPrintKind.REPRINT else ReceiptVoucherPrintKind.ORIGINAL,
                            paperWidthMm = cursor.getInt(2),
                            status = runCatching { PrintJobStatus.valueOf(cursor.getString(3)) }
                                .getOrDefault(PrintJobStatus.FAILED),
                            attemptCount = cursor.getInt(4),
                            lastError = if (cursor.isNull(5)) null else cursor.getString(5),
                            createdAt = cursor.getLong(6),
                            updatedAt = cursor.getLong(7),
                            reprintEventId = if (hasReprint) cursor.getLong(8) else null,
                            reprintedBy = if (cursor.isNull(9)) null else cursor.getString(9),
                            reprintedAt = if (cursor.isNull(10)) null else cursor.getLong(10),
                        ),
                    )
                }
            }
        }.groupBy { it.issuanceId }

        return receipts.map { receipt ->
            ReceiptVoucherLedgerEntry(
                receipt = receipt,
                printEvents = events[receipt.id].orEmpty(),
            )
        }
    }
}

object ReceiptVoucherOperationsTimeFormatter {
    private val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

    fun format(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(formatter)
}
