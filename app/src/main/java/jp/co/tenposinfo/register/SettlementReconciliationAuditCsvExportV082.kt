package jp.co.tenposinfo.register

import android.content.Context
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** v0.82: CSV出力ボタン押下時点を固定する安定スナップショット。 */
data class SettlementReconciliationAuditExportSnapshotV082(
    val createdAt: Long,
    val auditId: Long,
)

data class SettlementReconciliationAuditExportCriteriaV082(
    val filter: SettlementReconciliationAuditFilterV081,
    val searchText: String,
    val snapshot: SettlementReconciliationAuditExportSnapshotV082?,
)

data class SettlementReconciliationAuditCsvFieldsV082(
    val reportType: String,
    val businessDate: String,
    val businessSessionId: String,
    val snapshotType: String,
    val differenceCount: String,
)

object SettlementReconciliationAuditCsvPolicyV082 {
    val header = listOf(
        "audit_id",
        "executed_at",
        "severity",
        "report_id",
        "report_type",
        "business_date",
        "business_session_id",
        "snapshot_type",
        "difference_count",
        "operator_name",
        "event_type",
        "detail",
    )

    fun fileName(
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val stamp = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
            .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        return "tsuguregi_reconciliation_audit_$stamp.csv"
    }

    fun fields(detail: String): SettlementReconciliationAuditCsvFieldsV082 {
        val reportType = detail.substringBefore(" No.", missingDelimiterValue = "")
        val businessDate = valueBetween(detail, " / 営業日 ", " / セッションNo.")
        val businessSessionId = valueBetween(detail, " / セッションNo.", " / 判定 ")
        val snapshotType = valueBetween(detail, " / snapshot ", " / 差異 ")
        val differencePart = detail.substringAfter(" / 差異 ", missingDelimiterValue = "")
        val differenceCount = differencePart.substringBefore("件", missingDelimiterValue = "")
        return SettlementReconciliationAuditCsvFieldsV082(
            reportType = reportType,
            businessDate = businessDate,
            businessSessionId = businessSessionId,
            snapshotType = snapshotType,
            differenceCount = differenceCount,
        )
    }

    fun headerRow(): String = csvRow(header)

    fun row(
        record: SettlementReconciliationAuditRecordV081,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val parsed = fields(record.detail)
        return csvRow(
            listOf(
                record.id.toString(),
                formatTimestamp(record.createdAt, zoneId),
                record.severity.name,
                record.reportId.toString(),
                safeText(parsed.reportType),
                safeText(parsed.businessDate),
                safeText(parsed.businessSessionId),
                safeText(parsed.snapshotType),
                safeText(parsed.differenceCount),
                safeText(record.operatorName),
                safeText(record.eventType),
                safeText(record.detail),
            ),
        )
    }

    internal fun safeText(value: String): String {
        val normalized = value.replace("\r\n", "\n").replace('\r', '\n')
        val firstNonWhitespace = normalized.firstOrNull { !it.isWhitespace() }
        return if (firstNonWhitespace in setOf('=', '+', '-', '@')) "'$normalized" else normalized
    }

    internal fun csvCell(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun csvRow(values: List<String>): String = values.joinToString(",", transform = ::csvCell)

    private fun valueBetween(value: String, start: String, end: String): String {
        val tail = value.substringAfter(start, missingDelimiterValue = "")
        if (tail.isEmpty()) return ""
        return tail.substringBefore(end, missingDelimiterValue = "")
    }

    private fun formatTimestamp(epochMillis: Long, zoneId: ZoneId): String =
        Instant.ofEpochMilli(epochMillis).atZone(zoneId).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}

/**
 * v0.82: SCR-521の適用済み条件を、ボタン押下時点のsnapshotへ固定して全件CSV出力する。
 * operation_auditは読み取り専用で、既存監査・売上・税・支払・点検精算データを更新しない。
 */
class SettlementReconciliationAuditCsvExporterV082(context: Context) : AutoCloseable {
    private val database = RegisterDatabase(context.applicationContext)
    private val db = database.readableDatabase

    fun captureSnapshot(
        filter: SettlementReconciliationAuditFilterV081,
        searchText: String,
    ): SettlementReconciliationAuditExportSnapshotV082? {
        val query = SettlementReconciliationAuditLedgerPolicyV081.query(filter, searchText)
        return db.query(
            "operation_audit",
            arrayOf("created_at", "id"),
            query.selection,
            query.args.toTypedArray(),
            null,
            null,
            "created_at DESC, id DESC",
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) null
            else SettlementReconciliationAuditExportSnapshotV082(
                createdAt = cursor.getLong(0),
                auditId = cursor.getLong(1),
            )
        }
    }

    fun exportSnapshot(
        criteria: SettlementReconciliationAuditExportCriteriaV082,
        output: OutputStream,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Int {
        val base = SettlementReconciliationAuditLedgerPolicyV081.query(criteria.filter, criteria.searchText)
        val selectionParts = mutableListOf("(${base.selection})")
        val args = base.args.toMutableList()
        val snapshot = criteria.snapshot
        if (snapshot == null) {
            selectionParts += "1 = 0"
        } else {
            selectionParts += "(created_at < ? OR (created_at = ? AND id <= ?))"
            args.add(snapshot.createdAt.toString())
            args.add(snapshot.createdAt.toString())
            args.add(snapshot.auditId.toString())
        }

        val writer = output.bufferedWriter(Charsets.UTF_8)
        writer.write('\uFEFF'.code)
        writer.write(SettlementReconciliationAuditCsvPolicyV082.headerRow())
        writer.write("\r\n")

        var count = 0
        db.query(
            "operation_audit",
            arrayOf("id", "event_type", "reference_id", "detail", "operator_name", "created_at"),
            selectionParts.joinToString(" AND "),
            args.toTypedArray(),
            null,
            null,
            "created_at DESC, id DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val record = SettlementReconciliationAuditRecordV081(
                    id = cursor.getLong(0),
                    eventType = cursor.getString(1),
                    reportId = cursor.getLong(2),
                    detail = cursor.getString(3),
                    operatorName = cursor.getString(4),
                    createdAt = cursor.getLong(5),
                )
                writer.write(SettlementReconciliationAuditCsvPolicyV082.row(record, zoneId))
                writer.write("\r\n")
                count++
            }
        }
        writer.flush()
        return count
    }

    override fun close() = database.close()
}
