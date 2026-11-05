package jp.co.tenposinfo.register

import android.database.sqlite.SQLiteDatabase

/**
 * Formal v2.5 §16.3: freeze the optional receipt short name with the sale.
 * Reprints must not change when the product master is edited later.
 */
object ReceiptProductNameSnapshotV136 {
    fun ensureSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sale_receipt_name_snapshots (
                sale_id INTEGER NOT NULL,
                line_no INTEGER NOT NULL,
                product_id TEXT NOT NULL,
                receipt_short_name TEXT NOT NULL DEFAULT '',
                PRIMARY KEY(sale_id, line_no)
            )
            """.trimIndent(),
        )
    }

    fun save(db: SQLiteDatabase, saleId: Long, items: List<CartItem>) {
        ensureSchema(db)
        db.delete("sale_receipt_name_snapshots", "sale_id = ?", arrayOf(saleId.toString()))
        items.forEachIndexed { index, item ->
            db.execSQL(
                "INSERT INTO sale_receipt_name_snapshots(sale_id,line_no,product_id,receipt_short_name) VALUES(?,?,?,?)",
                arrayOf<Any>(saleId, index + 1, item.product.id, item.product.receiptShortName),
            )
        }
    }

    fun apply(db: SQLiteDatabase, saleId: Long, items: List<CartItem>): List<CartItem> {
        ensureSchema(db)
        val names = mutableMapOf<Int, String>()
        db.query(
            "sale_receipt_name_snapshots",
            arrayOf("line_no", "receipt_short_name"),
            "sale_id = ?",
            arrayOf(saleId.toString()),
            null,
            null,
            "line_no ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) names[cursor.getInt(0)] = cursor.getString(1)
        }
        return items.mapIndexed { index, item ->
            val shortName = names[index + 1] ?: return@mapIndexed item
            item.copy(product = item.product.copy(receiptShortName = shortName))
        }
    }
}
