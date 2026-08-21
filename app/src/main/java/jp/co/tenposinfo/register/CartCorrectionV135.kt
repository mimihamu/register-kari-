package jp.co.tenposinfo.register

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.util.UUID

enum class CartCorrectionTypeV135 {
    LAST_LINE,
    SELECTED_LINE,
}

data class CartCorrectionRecordV135(
    val id: Long = 0L,
    val lineId: String,
    val correctionType: CartCorrectionTypeV135,
    val productId: String,
    val productName: String,
    val unitPrice: Long,
    val cancelledQuantity: Int,
    val quantityBefore: Int,
    val quantityAfter: Int,
    val cancelledDiscountAmount: Long,
    val cancelledAmount: Long,
    val operatorName: String,
    val createdAt: Long,
)

data class CartCorrectionResultV135(
    val items: List<CartItem>,
    val record: CartCorrectionRecordV135,
)

object CartLineIdentityV135 {
    fun newId(): String = "line-${UUID.randomUUID()}"
}

object CartCorrectionPolicyV135 {
    fun apply(
        items: List<CartItem>,
        targetIndex: Int,
        cancelQuantity: Int,
        correctionType: CartCorrectionTypeV135,
        operatorName: String,
        createdAt: Long,
    ): CartCorrectionResultV135 {
        require(targetIndex in items.indices) { "取消対象行がありません" }
        require(cancelQuantity > 0) { "取消数量は1以上で指定してください" }
        require(operatorName.isNotBlank()) { "担当者が必要です" }

        val target = items[targetIndex]
        require(target.lineId.isNotBlank()) { "取消対象行の識別子がありません" }
        require(cancelQuantity <= target.quantity) { "取消数量が現在数量を超えています" }

        val cancelledDiscount = if (cancelQuantity == target.quantity) {
            target.discountAmount
        } else {
            target.discountAmount * cancelQuantity / target.quantity
        }
        val remainingQuantity = target.quantity - cancelQuantity
        val remainingDiscount = target.discountAmount - cancelledDiscount
        val cancelledAmount = Math.subtractExact(
            Math.multiplyExact(target.unitPrice, cancelQuantity.toLong()),
            cancelledDiscount,
        )

        val updatedItems = items.toMutableList()
        if (remainingQuantity == 0) {
            updatedItems.removeAt(targetIndex)
        } else {
            updatedItems[targetIndex] = target.copy(
                quantity = remainingQuantity,
                discountAmount = remainingDiscount,
            )
        }

        return CartCorrectionResultV135(
            items = updatedItems,
            record = CartCorrectionRecordV135(
                lineId = target.lineId,
                correctionType = correctionType,
                productId = target.product.id,
                productName = target.product.name,
                unitPrice = target.unitPrice,
                cancelledQuantity = cancelQuantity,
                quantityBefore = target.quantity,
                quantityAfter = remainingQuantity,
                cancelledDiscountAmount = cancelledDiscount,
                cancelledAmount = cancelledAmount,
                operatorName = operatorName.trim(),
                createdAt = createdAt,
            ),
        )
    }
}

object CartCorrectionSchemaV135 {
    private const val TABLE = "cart_correction_history"

    fun ensure(db: SQLiteDatabase) {
        ensureLineIdColumn(db, "cart_items")
        ensureLineIdColumn(db, "held_ticket_items")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                line_id TEXT NOT NULL,
                correction_type TEXT NOT NULL,
                product_id TEXT NOT NULL,
                product_name TEXT NOT NULL,
                unit_price INTEGER NOT NULL,
                cancelled_quantity INTEGER NOT NULL,
                quantity_before INTEGER NOT NULL,
                quantity_after INTEGER NOT NULL,
                cancelled_discount_amount INTEGER NOT NULL,
                cancelled_amount INTEGER NOT NULL,
                operator_name TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cart_correction_line ON $TABLE(line_id, id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cart_correction_created ON $TABLE(created_at, id)")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_cart_items_line_id ON cart_items(line_id) WHERE line_id <> ''",
        )
        db.execSQL(
            "UPDATE cart_items SET line_id = 'legacy-' || line_no || '-' || product_id WHERE line_id = ''",
        )
        db.execSQL(
            "UPDATE held_ticket_items SET line_id = 'held-' || ticket_id || '-' || id || '-' || product_id WHERE line_id = ''",
        )
    }

    private fun ensureLineIdColumn(db: SQLiteDatabase, table: String) {
        val exists = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "line_id") {
                    found = true
                    break
                }
            }
            found
        }
        if (!exists) {
            db.execSQL("ALTER TABLE $table ADD COLUMN line_id TEXT NOT NULL DEFAULT ''")
        }
    }

    fun insert(db: SQLiteDatabase, record: CartCorrectionRecordV135): Long = db.insertOrThrow(
        TABLE,
        null,
        ContentValues().apply {
            put("line_id", record.lineId)
            put("correction_type", record.correctionType.name)
            put("product_id", record.productId)
            put("product_name", record.productName)
            put("unit_price", record.unitPrice)
            put("cancelled_quantity", record.cancelledQuantity)
            put("quantity_before", record.quantityBefore)
            put("quantity_after", record.quantityAfter)
            put("cancelled_discount_amount", record.cancelledDiscountAmount)
            put("cancelled_amount", record.cancelledAmount)
            put("operator_name", record.operatorName)
            put("created_at", record.createdAt)
        },
    )

    fun load(db: SQLiteDatabase): List<CartCorrectionRecordV135> = db.query(
        TABLE,
        arrayOf(
            "id",
            "line_id",
            "correction_type",
            "product_id",
            "product_name",
            "unit_price",
            "cancelled_quantity",
            "quantity_before",
            "quantity_after",
            "cancelled_discount_amount",
            "cancelled_amount",
            "operator_name",
            "created_at",
        ),
        null,
        null,
        null,
        null,
        "created_at ASC, id ASC",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    CartCorrectionRecordV135(
                        id = cursor.getLong(0),
                        lineId = cursor.getString(1),
                        correctionType = CartCorrectionTypeV135.valueOf(cursor.getString(2)),
                        productId = cursor.getString(3),
                        productName = cursor.getString(4),
                        unitPrice = cursor.getLong(5),
                        cancelledQuantity = cursor.getInt(6),
                        quantityBefore = cursor.getInt(7),
                        quantityAfter = cursor.getInt(8),
                        cancelledDiscountAmount = cursor.getLong(9),
                        cancelledAmount = cursor.getLong(10),
                        operatorName = cursor.getString(11),
                        createdAt = cursor.getLong(12),
                    ),
                )
            }
        }
    }

    fun clear(db: SQLiteDatabase) {
        db.delete(TABLE, null, null)
    }
}
