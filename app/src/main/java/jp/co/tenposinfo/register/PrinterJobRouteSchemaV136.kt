package jp.co.tenposinfo.register

import android.database.sqlite.SQLiteDatabase

/**
 * Formal v2.5 §16.9 / §16.10 printer snapshot fields for print jobs.
 * Existing rows are migrated non-destructively to printer-1 and standard dot width.
 */
object PrinterJobRouteSchemaV136 {
    fun ensureSale(db: SQLiteDatabase) {
        ensureColumn(db, "print_jobs", "printer_id", "TEXT NOT NULL DEFAULT 'printer-1'")
        ensureColumn(db, "print_jobs", "printable_dot_width", "INTEGER NOT NULL DEFAULT 0")
        normalize(db, "print_jobs")
    }

    fun ensureDocument(db: SQLiteDatabase) {
        ensureColumn(db, "document_print_jobs", "printer_id", "TEXT NOT NULL DEFAULT 'printer-1'")
        ensureColumn(db, "document_print_jobs", "printable_dot_width", "INTEGER NOT NULL DEFAULT 0")
        normalize(db, "document_print_jobs")
    }

    private fun normalize(db: SQLiteDatabase, table: String) {
        db.execSQL(
            """
            UPDATE $table
            SET printer_id = 'printer-1'
            WHERE printer_id IS NULL OR TRIM(printer_id) = ''
            """.trimIndent(),
        )
        db.execSQL(
            """
            UPDATE $table
            SET printable_dot_width = CASE paper_width_mm
                WHEN 58 THEN 384
                WHEN 80 THEN 576
                ELSE 576
            END
            WHERE printable_dot_width <= 0
            """.trimIndent(),
        )
    }

    private fun ensureColumn(
        db: SQLiteDatabase,
        table: String,
        column: String,
        definition: String,
    ) {
        val exists = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            var found = false
            while (cursor.moveToNext()) {
                if (nameIndex >= 0 && cursor.getString(nameIndex) == column) {
                    found = true
                    break
                }
            }
            found
        }
        if (!exists) db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
    }
}
