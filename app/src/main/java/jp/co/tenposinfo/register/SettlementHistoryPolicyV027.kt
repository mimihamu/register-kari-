package jp.co.tenposinfo.register

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

object SettlementHistoryPolicyV027 {
    fun canView(permissions: Set<RegisterPermission>): Boolean =
        RegisterPermission.VIEW_SALES in permissions ||
            RegisterPermission.X_INSPECTION in permissions ||
            RegisterPermission.Z_SETTLEMENT in permissions

    fun permissionFor(type: SettlementReportType): RegisterPermission = when (type) {
        SettlementReportType.X_INSPECTION -> RegisterPermission.X_INSPECTION
        SettlementReportType.Z_SETTLEMENT -> RegisterPermission.Z_SETTLEMENT
    }

    fun requiresManagerPin(type: SettlementReportType): Boolean =
        type == SettlementReportType.Z_SETTLEMENT

    fun canReprint(
        record: SettlementRecord,
        permissions: Set<RegisterPermission>,
        managerPinProvided: Boolean,
    ): Boolean {
        if (permissionFor(record.type) !in permissions) return false
        return !requiresManagerPin(record.type) || managerPinProvided
    }

    fun filter(
        records: List<SettlementRecord>,
        businessSessionId: Long?,
        type: SettlementReportType?,
    ): List<SettlementRecord> = records.filter { record ->
        (businessSessionId == null || record.businessSessionId == businessSessionId) &&
            (type == null || record.type == type)
    }

    fun canReconstruct(snapshotVersion: Int, originalPayloadExists: Boolean): Boolean =
        snapshotVersion >= SettlementSnapshotSchemaV027.SNAPSHOT_VERSION || originalPayloadExists
}

object SettlementSnapshotSchemaV027 {
    const val SNAPSHOT_VERSION = 1

    fun ensure(db: SQLiteDatabase) {
        ensureColumn(db, "opening_cash", "INTEGER NOT NULL DEFAULT 0")
        ensureColumn(db, "cash_in", "INTEGER NOT NULL DEFAULT 0")
        ensureColumn(db, "cash_out", "INTEGER NOT NULL DEFAULT 0")
        ensureColumn(db, "snapshot_version", "INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS settlement_payment_totals (
                report_id INTEGER NOT NULL,
                sequence_no INTEGER NOT NULL,
                payment_method TEXT NOT NULL,
                amount INTEGER NOT NULL,
                PRIMARY KEY(report_id, sequence_no),
                FOREIGN KEY(report_id) REFERENCES settlement_reports(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_settlement_payment_report " +
                "ON settlement_payment_totals(report_id, sequence_no)",
        )
    }

    fun savePaymentTotals(
        db: SQLiteDatabase,
        reportId: Long,
        totals: List<PaymentTotal>,
    ) {
        db.delete("settlement_payment_totals", "report_id = ?", arrayOf(reportId.toString()))
        totals.forEachIndexed { index, payment ->
            db.insertOrThrow(
                "settlement_payment_totals",
                null,
                ContentValues().apply {
                    put("report_id", reportId)
                    put("sequence_no", index + 1)
                    put("payment_method", payment.method)
                    put("amount", payment.amount)
                },
            )
        }
    }

    fun loadPaymentTotals(db: SQLiteDatabase, reportId: Long): List<PaymentTotal> =
        db.query(
            "settlement_payment_totals",
            arrayOf("payment_method", "amount"),
            "report_id = ?",
            arrayOf(reportId.toString()),
            null,
            null,
            "sequence_no ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(PaymentTotal(cursor.getString(0), cursor.getLong(1)))
            }
        }

    fun originalPayload(db: SQLiteDatabase, reportId: Long): String? =
        db.query(
            "document_print_jobs",
            arrayOf("payload_text"),
            "document_type = ? AND reference_id = ?",
            arrayOf(OperationDocumentType.SETTLEMENT_REPORT.name, reportId.toString()),
            null,
            null,
            "id ASC",
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    fun legacyReprintPayload(
        originalPayload: String,
        operatorName: String,
        reprintedAtText: String,
    ): String = buildString {
        appendLine("【再印字】")
        appendLine("再印字 $reprintedAtText")
        appendLine("再印字担当 $operatorName")
        appendLine("--------------------------------")
        append(originalPayload)
    }

    private fun ensureColumn(db: SQLiteDatabase, name: String, declaration: String) {
        val exists = db.rawQuery("PRAGMA table_info(settlement_reports)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == name) {
                    found = true
                    break
                }
            }
            found
        }
        if (!exists) db.execSQL("ALTER TABLE settlement_reports ADD COLUMN $name $declaration")
    }
}
