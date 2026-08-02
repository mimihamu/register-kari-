package jp.co.tenposinfo.register

import android.database.sqlite.SQLiteDatabase

/**
 * v0.24の営業セッション規則。
 * Z精算は現在の営業セッションを同一DBトランザクション内で終了させる。
 * 営業日は集計属性であり、一意な営業セッション識別子ではない。
 */
object BusinessSessionLifecyclePolicy {
    fun isActive(status: BusinessSessionStatus?): Boolean = status == BusinessSessionStatus.OPEN

    fun mayStart(activeStatus: BusinessSessionStatus?): Boolean = !isActive(activeStatus)

    fun resultStatus(type: SettlementReportType, currentStatus: BusinessSessionStatus): BusinessSessionStatus {
        require(currentStatus == BusinessSessionStatus.OPEN) { "営業中のセッションだけ点検・精算できます" }
        return when (type) {
            SettlementReportType.X_INSPECTION -> BusinessSessionStatus.OPEN
            SettlementReportType.Z_SETTLEMENT -> BusinessSessionStatus.CLOSED
        }
    }
}

/** business_dateの旧UNIQUE制約を除去し、同一営業日の複数セッションを安全に保持する。 */
object BusinessSessionMultiplicityMigration {
    const val CREATE_TABLE_SQL = """
        CREATE TABLE business_sessions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            business_date TEXT NOT NULL,
            status TEXT NOT NULL,
            opening_cash INTEGER NOT NULL,
            opened_by TEXT NOT NULL,
            opened_at INTEGER NOT NULL,
            closed_by TEXT,
            closed_at INTEGER,
            closing_actual INTEGER,
            close_variance INTEGER
        )
    """

    fun ensure(db: SQLiteDatabase) {
        if (!SchemaMigration.tableExists(db, "business_sessions")) return
        if (hasUniqueBusinessDateConstraint(db)) rebuildWithoutBusinessDateUnique(db)
        normalizeLegacyZSettled(db)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_business_sessions_status ON business_sessions(status, opened_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_business_sessions_date_opened ON business_sessions(business_date, opened_at DESC)")
        if (activeCount(db) <= 1L) {
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_business_sessions_single_active " +
                    "ON business_sessions((1)) WHERE status = 'OPEN'",
            )
        }
    }

    fun hasUniqueBusinessDateConstraint(db: SQLiteDatabase): Boolean =
        db.rawQuery("PRAGMA index_list('business_sessions')", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            val uniqueIndex = cursor.getColumnIndex("unique")
            while (cursor.moveToNext()) {
                if (uniqueIndex < 0 || cursor.getInt(uniqueIndex) == 0) continue
                val indexName = cursor.getString(nameIndex)
                val columns = db.rawQuery("PRAGMA index_info(${quoteIdentifier(indexName)})", null).use { info ->
                    val columnNameIndex = info.getColumnIndex("name")
                    buildList {
                        while (info.moveToNext()) {
                            if (columnNameIndex >= 0 && !info.isNull(columnNameIndex)) add(info.getString(columnNameIndex))
                        }
                    }
                }
                if (columns == listOf("business_date")) return true
            }
            false
        }

    private fun rebuildWithoutBusinessDateUnique(db: SQLiteDatabase) {
        val schemaObjects = db.rawQuery(
            """
            SELECT type, name, sql
            FROM sqlite_master
            WHERE tbl_name = 'business_sessions'
              AND type IN ('index','trigger')
              AND sql IS NOT NULL
            ORDER BY type, name
            """.trimIndent(),
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(2))
            }
        }

        db.beginTransaction()
        try {
            db.execSQL("ALTER TABLE business_sessions RENAME TO business_sessions_v023_unique_date")
            db.execSQL(CREATE_TABLE_SQL)
            db.execSQL(
                """
                INSERT INTO business_sessions(
                    id, business_date, status, opening_cash, opened_by, opened_at,
                    closed_by, closed_at, closing_actual, close_variance
                )
                SELECT
                    id, business_date, status, opening_cash, opened_by, opened_at,
                    closed_by, closed_at, closing_actual, close_variance
                FROM business_sessions_v023_unique_date
                ORDER BY id
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE business_sessions_v023_unique_date")
            schemaObjects.forEach(db::execSQL)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun normalizeLegacyZSettled(db: SQLiteDatabase) {
        if (!SchemaMigration.tableExists(db, "settlement_reports")) {
            normalizeLegacyWithoutSettlementLink(db)
            return
        }
        val relation = if (SchemaMigration.hasColumn(db, "settlement_reports", "business_session_id")) {
            "sr.business_session_id = business_sessions.id"
        } else {
            "sr.business_date = business_sessions.business_date"
        }
        db.execSQL(
            """
            UPDATE business_sessions
            SET status = 'CLOSED',
                closed_by = COALESCE(
                    closed_by,
                    (SELECT sr.operator_name FROM settlement_reports sr
                     WHERE $relation
                       AND sr.report_type = 'Z_SETTLEMENT'
                     ORDER BY sr.created_at DESC LIMIT 1),
                    opened_by
                ),
                closed_at = COALESCE(
                    closed_at,
                    (SELECT sr.created_at FROM settlement_reports sr
                     WHERE $relation
                       AND sr.report_type = 'Z_SETTLEMENT'
                     ORDER BY sr.created_at DESC LIMIT 1),
                    opened_at
                ),
                closing_actual = COALESCE(
                    closing_actual,
                    (SELECT sr.actual_cash FROM settlement_reports sr
                     WHERE $relation
                       AND sr.report_type = 'Z_SETTLEMENT'
                     ORDER BY sr.created_at DESC LIMIT 1),
                    opening_cash
                ),
                close_variance = COALESCE(
                    close_variance,
                    (SELECT sr.variance FROM settlement_reports sr
                     WHERE $relation
                       AND sr.report_type = 'Z_SETTLEMENT'
                     ORDER BY sr.created_at DESC LIMIT 1),
                    0
                )
            WHERE status = 'Z_SETTLED'
            """.trimIndent(),
        )
    }

    private fun normalizeLegacyWithoutSettlementLink(db: SQLiteDatabase) {
        db.execSQL(
            """
            UPDATE business_sessions
            SET status = 'CLOSED',
                closed_by = COALESCE(closed_by, opened_by),
                closed_at = COALESCE(closed_at, opened_at),
                closing_actual = COALESCE(closing_actual, opening_cash),
                close_variance = COALESCE(close_variance, 0)
            WHERE status = 'Z_SETTLED'
            """.trimIndent(),
        )
    }

    private fun activeCount(db: SQLiteDatabase): Long = db.rawQuery(
        "SELECT COUNT(*) FROM business_sessions WHERE status = 'OPEN'",
        null,
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    private fun quoteIdentifier(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}
