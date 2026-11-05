package jp.co.tenposinfo.register

import android.database.sqlite.SQLiteDatabase

/**
 * Additive migration for formal v2.5 §16.3 fractional quantity.
 * Legacy quantity remains untouched; quantity_hundredths is backfilled as quantity * 100.
 */
object QuantitySchemaV136 {
    private val tables = listOf("cart_items", "held_ticket_items", "sale_items")

    fun ensure(db: SQLiteDatabase) {
        tables.forEach { table ->
            if (!hasColumn(db, table, "quantity_hundredths")) {
                db.execSQL("ALTER TABLE $table ADD COLUMN quantity_hundredths INTEGER")
            }
            db.execSQL(
                "UPDATE $table SET quantity_hundredths = quantity * 100 WHERE quantity_hundredths IS NULL",
            )
        }
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) {
                    found = true
                    break
                }
            }
            found
        }
}
