package jp.co.tenposinfo.register

import android.database.sqlite.SQLiteDatabase

/** Shared audit schema required by the sale reissue path even before advanced operations is opened. */
object OperationAuditSchemaV136 {
    fun ensure(db: SQLiteDatabase) {
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
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_operation_audit_event_ref ON operation_audit(event_type, reference_id, created_at)",
        )
    }
}
