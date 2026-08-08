package jp.co.tenposinfo.register

import android.content.Context
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object SaleReceiptReprintCsvPolicy {
    val header = listOf(
        "request_id",
        "requested_at",
        "sale_id",
        "sale_amount",
        "sale_created_at",
        "print_job_id",
        "operator_name",
        "paper_width_mm",
        "status",
        "attempt_count",
        "last_error",
    )

    fun fileName(nowMillis: Long = System.currentTimeMillis(), zoneId: ZoneId = ZoneId.systemDefault()): String {
        val stamp = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
            .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        return "tsuguregi_receipt_reprint_$stamp.csv"
    }

    fun row(entry: SaleReceiptReprintLedgerEntry, zoneId: ZoneId = ZoneId.systemDefault()): String = csvRow(
        listOf(
            safeText(entry.requestId),
            formatTimestamp(entry.requestedAt, zoneId),
            entry.saleId.toString(),
            entry.saleAmount.toString(),
            formatTimestamp(entry.saleCreatedAt, zoneId),
            entry.printJobId.toString(),
            safeText(entry.operatorName),
            entry.paperWidthMm.toString(),
            entry.status.name,
            entry.attemptCount.toString(),
            safeText(entry.lastError.orEmpty()),
        ),
    )

    fun headerRow(): String = csvRow(header)

    internal fun safeText(value: String): String {
        val normalized = value.replace("\r\n", "\n").replace('\r', '\n')
        val firstNonWhitespace = normalized.firstOrNull { !it.isWhitespace() }
        return if (firstNonWhitespace in setOf('=', '+', '-', '@')) "'$normalized" else normalized
    }

    internal fun csvCell(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun csvRow(values: List<String>): String = values.joinToString(",", transform = ::csvCell)

    private fun formatTimestamp(epochMillis: Long, zoneId: ZoneId): String =
        Instant.ofEpochMilli(epochMillis).atZone(zoneId).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}

/**
 * v0.75: SCR-648の適用済み検索条件と固定snapshotをそのままCSVへ出力する読み取り専用exporter。
 *
 * UIの未適用入力値やsnapshot後に追加された新着は混入させない。
 * SQLiteの1 SELECTをカーソル走査し、全件をメモリへ載せずにUTF-8 BOM付きCSVへ逐次出力する。
 */
class SaleReceiptReprintCsvExporter(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val database = RegisterDatabase(appContext)

    init {
        SaleReceiptReprintAuditStore(appContext).close()
    }

    fun exportSnapshot(
        criteria: SaleReceiptReprintLedgerCriteria,
        snapshot: SaleReceiptReprintLedgerSnapshot,
        output: OutputStream,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Int {
        val base = SaleReceiptReprintLedgerPolicy.buildDatabaseQuery(criteria)
        val spec = SaleReceiptReprintStablePagingPolicy.appendSnapshotBound(base, snapshot)
        val writer = output.bufferedWriter(Charsets.UTF_8)
        writer.write('\uFEFF'.code)
        writer.write(SaleReceiptReprintCsvPolicy.headerRow())
        writer.write("\r\n")

        var count = 0
        database.readableDatabase.rawQuery(
            """
            SELECT r.id, r.request_id, r.sale_id,
                   s.total_amount, s.created_at,
                   r.print_job_id, r.operator_name, r.paper_width_mm, r.requested_at,
                   j.status, j.attempt_count, j.last_error
            FROM ${SaleReceiptReprintAuditStore.TABLE} r
            INNER JOIN print_jobs j ON j.id = r.print_job_id
            INNER JOIN sales s ON s.id = r.sale_id
            ${spec.whereSql}
            ORDER BY r.requested_at DESC, r.id DESC
            """.trimIndent(),
            spec.args.toTypedArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val entry = SaleReceiptReprintLedgerEntry(
                    auditId = cursor.getLong(0),
                    requestId = cursor.getString(1),
                    saleId = cursor.getLong(2),
                    saleAmount = cursor.getLong(3),
                    saleCreatedAt = cursor.getLong(4),
                    printJobId = cursor.getLong(5),
                    operatorName = cursor.getString(6),
                    paperWidthMm = cursor.getInt(7),
                    requestedAt = cursor.getLong(8),
                    status = runCatching { PrintJobStatus.valueOf(cursor.getString(9)) }.getOrDefault(PrintJobStatus.FAILED),
                    attemptCount = cursor.getInt(10),
                    lastError = if (cursor.isNull(11)) null else cursor.getString(11),
                )
                writer.write(SaleReceiptReprintCsvPolicy.row(entry, zoneId))
                writer.write("\r\n")
                count++
            }
        }
        writer.flush()
        return count
    }

    override fun close() = database.close()
}
