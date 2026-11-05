package jp.co.tenposinfo.register

import android.database.sqlite.SQLiteDatabase

object QuantityModeSchemaV136 {
    private val tables = listOf("cart_items", "held_ticket_items", "sale_items")

    fun ensure(db: SQLiteDatabase) {
        tables.forEach { table ->
            if (!hasColumn(db, table, "quantity_mode")) {
                db.execSQL("ALTER TABLE $table ADD COLUMN quantity_mode TEXT")
            }
            db.execSQL(
                """
                UPDATE $table
                SET quantity_mode = CASE
                    WHEN COALESCE(quantity_hundredths, quantity * 100) % 100 <> 0 THEN 'DECIMAL'
                    ELSE 'INTEGER'
                END
                WHERE quantity_mode IS NULL OR quantity_mode NOT IN ('INTEGER','DECIMAL')
                """.trimIndent(),
            )
        }
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) return@use true
            }
            false
        }
}
