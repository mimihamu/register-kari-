package jp.co.tenposinfo.register

import android.content.Context
import android.database.sqlite.SQLiteDatabase

internal data class SettlementRep001TotalsV135(
    val discountTotalYen: Long,
    val taxTotalYen: Long,
) {
    companion object {
        val ZERO = SettlementRep001TotalsV135(0L, 0L)
    }
}

/** REP-001 accounting totals that are not part of the legacy settlement row. */
internal object SettlementReportingPolicyV135 {
    const val BACKUP_FAILURE_ACK_EVENT = "Z_SETTLEMENT_BACKUP_FAILURE_ACK"
    const val LEGACY_BACKUP_FAILURE_ACK_EVENT = "Z_SETTLEMENT_BACKUP_FAILURE_ACKNOWLEDGED"

    fun normalizedAuditEvent(eventType: String): String =
        if (eventType == LEGACY_BACKUP_FAILURE_ACK_EVENT) BACKUP_FAILURE_ACK_EVENT else eventType
}

/**
 * REP-001 snapshot extension for X/Z reports.
 *
 * New reports are snapshotted by a SQLite trigger in the same transaction as settlement_reports.
 * Existing reports are backfilled once from append-only sales rows up to the report creation time.
 * Rendering during the still-uncommitted settlement transaction falls back to the same deterministic
 * session/cutoff query; reprints read the persisted snapshot.
 */
internal object SettlementReportingRuntimeV135 {
    private const val TABLE = "settlement_rep001_totals_v135"
    private const val SNAPSHOT_TRIGGER = "trg_settlement_rep001_snapshot_v135"
    private const val AUDIT_NORMALIZE_TRIGGER = "trg_settlement_rep003_backup_ack_v135"

    @Volatile
    private var applicationContext: Context? = null

    @Synchronized
    fun initialize(context: Context) {
        val appContext = context.applicationContext
        // Operations tables must exist before triggers that reference them are created.
        OperationsStore(appContext).close()
        val helper = RegisterDatabase(appContext)
        try {
            ensureSchema(helper.writableDatabase)
            applicationContext = appContext
        } finally {
            helper.close()
        }
    }

    fun currentTotals(businessSessionId: Long): SettlementRep001TotalsV135 {
        if (businessSessionId <= 0L) return SettlementRep001TotalsV135.ZERO
        return queryRawTotals(businessSessionId, Long.MAX_VALUE)
    }

    fun documentTotals(
        reportId: Long,
        businessSessionId: Long,
        createdAt: Long,
    ): SettlementRep001TotalsV135 {
        if (businessSessionId <= 0L) return SettlementRep001TotalsV135.ZERO
        val snapshot = querySnapshot(reportId)
        return snapshot ?: queryRawTotals(businessSessionId, createdAt)
    }

    private fun querySnapshot(reportId: Long): SettlementRep001TotalsV135? {
        if (reportId <= 0L) return null
        val context = applicationContext ?: return null
        val helper = RegisterDatabase(context)
        return try {
            helper.readableDatabase.query(
                TABLE,
                arrayOf("discount_total_yen", "tax_total_yen"),
                "report_id = ?",
                arrayOf(reportId.toString()),
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    SettlementRep001TotalsV135(cursor.getLong(0), cursor.getLong(1))
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        } finally {
            helper.close()
        }
    }

    private fun queryRawTotals(
        businessSessionId: Long,
        cutoffCreatedAt: Long,
    ): SettlementRep001TotalsV135 {
        val context = applicationContext ?: return SettlementRep001TotalsV135.ZERO
        val helper = RegisterDatabase(context)
        return try {
            val db = helper.readableDatabase
            val tax = scalarLong(
                db,
                """
                SELECT COALESCE(SUM(s.tax_amount), 0)
                FROM sales s
                WHERE s.business_session_id = ? AND s.created_at <= ?
                """.trimIndent(),
                arrayOf(businessSessionId.toString(), cutoffCreatedAt.toString()),
            )
            val discount = scalarLong(
                db,
                """
                SELECT COALESCE(SUM(si.discount_amount), 0)
                FROM sale_items si
                INNER JOIN sales s ON s.id = si.sale_id
                WHERE s.business_session_id = ? AND s.created_at <= ?
                """.trimIndent(),
                arrayOf(businessSessionId.toString(), cutoffCreatedAt.toString()),
            )
            SettlementRep001TotalsV135(discount, tax)
        } finally {
            helper.close()
        }
    }

    private fun ensureSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE (
                report_id INTEGER PRIMARY KEY,
                discount_total_yen INTEGER NOT NULL,
                tax_total_yen INTEGER NOT NULL,
                FOREIGN KEY(report_id) REFERENCES settlement_reports(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )

        // Backfill old X/Z history once. The created_at cutoff keeps historical X values stable.
        db.execSQL(
            """
            INSERT OR IGNORE INTO $TABLE(report_id, discount_total_yen, tax_total_yen)
            SELECT r.id,
                   COALESCE((
                       SELECT SUM(si.discount_amount)
                       FROM sale_items si
                       INNER JOIN sales s ON s.id = si.sale_id
                       WHERE s.business_session_id = r.business_session_id
                         AND s.created_at <= r.created_at
                   ), 0),
                   COALESCE((
                       SELECT SUM(s.tax_amount)
                       FROM sales s
                       WHERE s.business_session_id = r.business_session_id
                         AND s.created_at <= r.created_at
                   ), 0)
            FROM settlement_reports r
            WHERE r.business_session_id IS NOT NULL
            """.trimIndent(),
        )

        db.execSQL("DROP TRIGGER IF EXISTS $SNAPSHOT_TRIGGER")
        db.execSQL(
            """
            CREATE TRIGGER $SNAPSHOT_TRIGGER
            AFTER INSERT ON settlement_reports
            WHEN NEW.business_session_id IS NOT NULL
            BEGIN
                INSERT OR REPLACE INTO $TABLE(report_id, discount_total_yen, tax_total_yen)
                VALUES(
                    NEW.id,
                    COALESCE((
                        SELECT SUM(si.discount_amount)
                        FROM sale_items si
                        INNER JOIN sales s ON s.id = si.sale_id
                        WHERE s.business_session_id = NEW.business_session_id
                    ), 0),
                    COALESCE((
                        SELECT SUM(s.tax_amount)
                        FROM sales s
                        WHERE s.business_session_id = NEW.business_session_id
                    ), 0)
                );
            END
            """.trimIndent(),
        )

        // Compatibility guard: persist only the v2.5 event name while keeping old callers safe.
        db.execSQL("DROP TRIGGER IF EXISTS $AUDIT_NORMALIZE_TRIGGER")
        db.execSQL(
            """
            CREATE TRIGGER $AUDIT_NORMALIZE_TRIGGER
            BEFORE INSERT ON operation_audit
            WHEN NEW.event_type = '${SettlementReportingPolicyV135.LEGACY_BACKUP_FAILURE_ACK_EVENT}'
            BEGIN
                INSERT INTO operation_audit(event_type, reference_id, detail, operator_name, created_at)
                VALUES(
                    '${SettlementReportingPolicyV135.BACKUP_FAILURE_ACK_EVENT}',
                    NEW.reference_id,
                    NEW.detail,
                    NEW.operator_name,
                    NEW.created_at
                );
                SELECT RAISE(IGNORE);
            END
            """.trimIndent(),
        )
    }

    private fun scalarLong(db: SQLiteDatabase, sql: String, args: Array<String>): Long =
        db.rawQuery(sql, args).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
}
