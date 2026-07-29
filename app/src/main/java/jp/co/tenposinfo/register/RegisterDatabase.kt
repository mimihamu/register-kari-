package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class HeldTicket(
    val id: Long,
    val name: String,
    val operatorName: String,
    val createdAt: Long,
    val itemCount: Int,
    val totalAmount: Long,
)

class RegisterDatabase(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE products (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                unit_price INTEGER NOT NULL,
                tax_category TEXT NOT NULL,
                display_order INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE cart_items (
                product_id TEXT PRIMARY KEY,
                product_name TEXT NOT NULL,
                unit_price INTEGER NOT NULL,
                tax_category TEXT NOT NULL,
                display_order INTEGER NOT NULL,
                quantity INTEGER NOT NULL,
                discount_amount INTEGER NOT NULL DEFAULT 0,
                note TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE held_tickets (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                operator_name TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE held_ticket_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ticket_id INTEGER NOT NULL,
                product_id TEXT NOT NULL,
                product_name TEXT NOT NULL,
                unit_price INTEGER NOT NULL,
                tax_category TEXT NOT NULL,
                display_order INTEGER NOT NULL,
                quantity INTEGER NOT NULL,
                discount_amount INTEGER NOT NULL DEFAULT 0,
                note TEXT NOT NULL DEFAULT '',
                FOREIGN KEY(ticket_id) REFERENCES held_tickets(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE sales (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                operator_name TEXT NOT NULL,
                payment_method TEXT NOT NULL,
                net_amount INTEGER NOT NULL,
                tax_amount INTEGER NOT NULL,
                total_amount INTEGER NOT NULL,
                deposit_amount INTEGER NOT NULL,
                change_amount INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE sale_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sale_id INTEGER NOT NULL,
                product_id TEXT NOT NULL,
                product_name TEXT NOT NULL,
                unit_price INTEGER NOT NULL,
                tax_category TEXT NOT NULL,
                quantity INTEGER NOT NULL,
                discount_amount INTEGER NOT NULL DEFAULT 0,
                note TEXT NOT NULL DEFAULT '',
                FOREIGN KEY(sale_id) REFERENCES sales(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        createSalePaymentsTable(db)
        insertSeedProducts(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("DROP TABLE IF EXISTS sale_payments")
            db.execSQL("DROP TABLE IF EXISTS sale_items")
            db.execSQL("DROP TABLE IF EXISTS sales")
            db.execSQL("DROP TABLE IF EXISTS held_ticket_items")
            db.execSQL("DROP TABLE IF EXISTS held_tickets")
            db.execSQL("DROP TABLE IF EXISTS cart_items")
            db.execSQL("DROP TABLE IF EXISTS products")
            onCreate(db)
            return
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE cart_items ADD COLUMN discount_amount INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE cart_items ADD COLUMN note TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE held_ticket_items ADD COLUMN discount_amount INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE held_ticket_items ADD COLUMN note TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE sale_items ADD COLUMN discount_amount INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE sale_items ADD COLUMN note TEXT NOT NULL DEFAULT ''")
            createSalePaymentsTable(db)
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    fun loadProducts(): List<Product> {
        readableDatabase.query(
            "products",
            arrayOf("id", "name", "unit_price", "tax_category", "display_order"),
            null,
            null,
            null,
            null,
            "display_order ASC",
        ).use { cursor ->
            val result = mutableListOf<Product>()
            while (cursor.moveToNext()) {
                result += Product(
                    id = cursor.getString(0),
                    name = cursor.getString(1),
                    unitPrice = cursor.getLong(2),
                    taxCategory = TaxCategory.valueOf(cursor.getString(3)),
                    displayOrder = cursor.getInt(4),
                )
            }
            return result
        }
    }

    fun loadCart(): List<CartItem> {
        readableDatabase.query(
            "cart_items",
            arrayOf(
                "product_id",
                "product_name",
                "unit_price",
                "tax_category",
                "display_order",
                "quantity",
                "discount_amount",
                "note",
            ),
            null,
            null,
            null,
            null,
            "display_order ASC",
        ).use { cursor ->
            val result = mutableListOf<CartItem>()
            while (cursor.moveToNext()) {
                result += cursor.toCartItem()
            }
            return result
        }
    }

    fun saveCart(items: List<CartItem>) {
        writableDatabase.runInTransaction {
            delete("cart_items", null, null)
            items.forEach { item ->
                insertOrThrow("cart_items", null, item.toContentValues())
            }
        }
    }

    fun holdCart(name: String, operatorName: String, items: List<CartItem>): Long {
        require(items.isNotEmpty()) { "Cannot hold an empty cart" }
        return writableDatabase.runInTransactionWithResult {
            val ticketId = insertOrThrow(
                "held_tickets",
                null,
                ContentValues().apply {
                    put("name", name)
                    put("operator_name", operatorName)
                    put("created_at", System.currentTimeMillis())
                },
            )
            items.forEach { item ->
                insertOrThrow(
                    "held_ticket_items",
                    null,
                    item.toContentValues().apply { put("ticket_id", ticketId) },
                )
            }
            ticketId
        }
    }

    fun listHeldTickets(): List<HeldTicket> {
        readableDatabase.rawQuery(
            """
            SELECT t.id, t.name, t.operator_name, t.created_at,
                   COALESCE(SUM(i.quantity), 0) AS item_count
            FROM held_tickets t
            LEFT JOIN held_ticket_items i ON i.ticket_id = t.id
            GROUP BY t.id, t.name, t.operator_name, t.created_at
            ORDER BY t.created_at DESC
            """.trimIndent(),
            null,
        ).use { cursor ->
            val result = mutableListOf<HeldTicket>()
            while (cursor.moveToNext()) {
                val items = loadHeldTicket(cursor.getLong(0))
                result += HeldTicket(
                    id = cursor.getLong(0),
                    name = cursor.getString(1),
                    operatorName = cursor.getString(2),
                    createdAt = cursor.getLong(3),
                    itemCount = cursor.getInt(4),
                    totalAmount = TaxEngine.calculate(items).grossAmount,
                )
            }
            return result
        }
    }

    fun loadHeldTicket(ticketId: Long): List<CartItem> {
        readableDatabase.query(
            "held_ticket_items",
            arrayOf(
                "product_id",
                "product_name",
                "unit_price",
                "tax_category",
                "display_order",
                "quantity",
                "discount_amount",
                "note",
            ),
            "ticket_id = ?",
            arrayOf(ticketId.toString()),
            null,
            null,
            "id ASC",
        ).use { cursor ->
            val result = mutableListOf<CartItem>()
            while (cursor.moveToNext()) {
                result += cursor.toCartItem()
            }
            return result
        }
    }

    fun deleteHeldTicket(ticketId: Long) {
        writableDatabase.delete("held_tickets", "id = ?", arrayOf(ticketId.toString()))
    }

    fun saveSale(
        operatorName: String,
        items: List<CartItem>,
        paymentState: PaymentState,
    ): Long {
        require(items.isNotEmpty()) { "Cannot save an empty sale" }
        val summary = TaxEngine.calculate(items)
        require(paymentState.remaining(summary.grossAmount) == 0L) { "Payment is incomplete" }
        return writableDatabase.runInTransactionWithResult {
            val saleId = insertOrThrow(
                "sales",
                null,
                ContentValues().apply {
                    put("operator_name", operatorName)
                    put("payment_method", paymentState.allocations.joinToString("+") { it.method.displayName })
                    put("net_amount", summary.netAmount)
                    put("tax_amount", summary.taxAmount)
                    put("total_amount", summary.grossAmount)
                    put("deposit_amount", paymentState.allocations.sumOf { it.receivedAmount })
                    put("change_amount", paymentState.changeAmount)
                    put("created_at", System.currentTimeMillis())
                },
            )
            items.forEach { item ->
                insertOrThrow(
                    "sale_items",
                    null,
                    ContentValues().apply {
                        put("sale_id", saleId)
                        put("product_id", item.product.id)
                        put("product_name", item.product.name)
                        put("unit_price", item.unitPrice)
                        put("tax_category", item.product.taxCategory.name)
                        put("quantity", item.quantity)
                        put("discount_amount", item.discountAmount)
                        put("note", item.note)
                    },
                )
            }
            paymentState.allocations.forEachIndexed { index, payment ->
                insertOrThrow(
                    "sale_payments",
                    null,
                    ContentValues().apply {
                        put("sale_id", saleId)
                        put("sequence_no", index + 1)
                        put("payment_method", payment.method.name)
                        put("applied_amount", payment.appliedAmount)
                        put("received_amount", payment.receivedAmount)
                    },
                )
            }
            saleId
        }
    }

    private fun android.database.Cursor.toCartItem(): CartItem {
        val product = Product(
            id = getString(0),
            name = getString(1),
            unitPrice = getLong(2),
            taxCategory = TaxCategory.valueOf(getString(3)),
            displayOrder = getInt(4),
        )
        return CartItem(
            product = product,
            quantity = getInt(5),
            unitPrice = getLong(2),
            discountAmount = getLong(6),
            note = getString(7),
        )
    }

    private fun CartItem.toContentValues() = ContentValues().apply {
        put("product_id", product.id)
        put("product_name", product.name)
        put("unit_price", unitPrice)
        put("tax_category", product.taxCategory.name)
        put("display_order", product.displayOrder)
        put("quantity", quantity)
        put("discount_amount", discountAmount)
        put("note", note)
    }

    private fun createSalePaymentsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sale_payments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sale_id INTEGER NOT NULL,
                sequence_no INTEGER NOT NULL,
                payment_method TEXT NOT NULL,
                applied_amount INTEGER NOT NULL,
                received_amount INTEGER NOT NULL,
                FOREIGN KEY(sale_id) REFERENCES sales(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }

    private fun insertSeedProducts(db: SQLiteDatabase) {
        val products = listOf(
            Product("P0001", "生ビール", 600, TaxCategory.INCLUDED_10, 1),
            Product("P0002", "ハイボール", 520, TaxCategory.INCLUDED_10, 2),
            Product("P0003", "ウーロン茶", 300, TaxCategory.INCLUDED_10, 3),
            Product("P0010", "枝豆", 420, TaxCategory.INCLUDED_10, 4),
            Product("P0011", "唐揚げ", 680, TaxCategory.INCLUDED_10, 5),
            Product("P0012", "刺身盛合せ", 1680, TaxCategory.INCLUDED_10, 6),
            Product("P0020", "焼き鳥", 180, TaxCategory.INCLUDED_10, 7),
            Product("P0021", "弁当", 800, TaxCategory.EXCLUDED_8, 8),
            Product("P0022", "お土産", 1200, TaxCategory.EXCLUDED_10, 9),
            Product("P0030", "サービス品", 100, TaxCategory.NON_TAXABLE, 10),
        )
        products.forEach { product ->
            db.insertOrThrow(
                "products",
                null,
                ContentValues().apply {
                    put("id", product.id)
                    put("name", product.name)
                    put("unit_price", product.unitPrice)
                    put("tax_category", product.taxCategory.name)
                    put("display_order", product.displayOrder)
                },
            )
        }
    }

    companion object {
        private const val DATABASE_NAME = "register.db"
        private const val DATABASE_VERSION = 3
    }
}

private inline fun <T> SQLiteDatabase.runInTransactionWithResult(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    return try {
        val result = block()
        setTransactionSuccessful()
        result
    } finally {
        endTransaction()
    }
}
