package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
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
    private val applicationContext = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        createProductsTable(db)
        createCartTable(db)
        createHeldTicketTables(db)
        createSalesTables(db)
        createSalePaymentsTable(db)
        createPrintJobsTable(db)
        insertSeedProducts(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            dropAllTables(db)
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
        if (oldVersion < 4) {
            migrateCartToLineNumber(db)
            db.execSQL("ALTER TABLE sales ADD COLUMN print_count INTEGER NOT NULL DEFAULT 0")
            createPrintJobsTable(db)
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
            CART_COLUMNS,
            null,
            null,
            null,
            null,
            "line_no ASC",
        ).use { cursor ->
            val result = mutableListOf<CartItem>()
            while (cursor.moveToNext()) result += cursor.toCartItem()
            return LineTaxSnapshotStore.apply(readableDatabase, LineTaxSnapshotStore.SCOPE_CART, 0L, result)
        }
    }

    fun saveCart(items: List<CartItem>) {
        writableDatabase.runInTransaction {
            delete("cart_items", null, null)
            items.forEachIndexed { index, item ->
                insertOrThrow("cart_items", null, item.toContentValues().apply { put("line_no", index + 1) })
            }
            LineTaxSnapshotStore.save(this, LineTaxSnapshotStore.SCOPE_CART, 0L, items)
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
            LineTaxSnapshotStore.save(this, LineTaxSnapshotStore.SCOPE_HELD, ticketId, items)
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
            CART_COLUMNS,
            "ticket_id = ?",
            arrayOf(ticketId.toString()),
            null,
            null,
            "id ASC",
        ).use { cursor ->
            val result = mutableListOf<CartItem>()
            while (cursor.moveToNext()) result += cursor.toCartItem()
            return LineTaxSnapshotStore.apply(readableDatabase, LineTaxSnapshotStore.SCOPE_HELD, ticketId, result)
        }
    }

    fun deleteHeldTicket(ticketId: Long) {
        writableDatabase.runInTransaction {
            delete("held_tickets", "id = ?", arrayOf(ticketId.toString()))
            delete("line_tax_snapshots", "scope = ? AND owner_id = ?", arrayOf(LineTaxSnapshotStore.SCOPE_HELD, ticketId.toString()))
        }
    }

    /**
     * 売上・明細・支払・印刷キューを同一SQLiteトランザクションで確定する。
     */
    fun saveSale(
        operatorName: String,
        items: List<CartItem>,
        paymentState: PaymentState,
        paperWidthMm: Int = 80,
    ): Long {
        require(items.isNotEmpty()) { "Cannot save an empty sale" }
        TaxEngine.validateMixedTax(items, MixedTaxPolicy.BLOCK)
        val summary = TaxEngine.calculate(items)
        require(paymentState.remaining(summary.grossAmount) == 0L) { "Payment is incomplete" }
        BusinessSessionSchema.ensure(writableDatabase)
        val businessLink = BusinessSessionSchema.current(writableDatabase)
        val createdAt = System.currentTimeMillis()
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
                    businessLink.sessionId?.let { put("business_session_id", it) }
                    put("business_date", businessLink.businessDate)
                    put("created_at", createdAt)
                    put("print_count", 0)
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
            insertPrintJob(this, saleId, paperWidthMm, createdAt)
            LineTaxSnapshotStore.save(this, LineTaxSnapshotStore.SCOPE_SALE, saleId, items)
            JournalOutboxSchema.recordSale(
                db = this,
                saleId = saleId,
                totalAmount = summary.grossAmount,
                taxAmount = summary.taxAmount,
                createdAt = createdAt,
                businessDate = businessLink.businessDate,
                folderName = DriveSyncSettingsStore.load(applicationContext).folderName,
            )
            saleId
        }
    }

    fun listSales(limit: Int = 200): List<SaleSummaryRecord> {
        readableDatabase.query(
            "sales",
            arrayOf(
                "id",
                "operator_name",
                "payment_method",
                "total_amount",
                "tax_amount",
                "change_amount",
                "created_at",
                "print_count",
            ),
            null,
            null,
            null,
            null,
            "created_at DESC",
            limit.coerceIn(1, 1_000).toString(),
        ).use { cursor ->
            val result = mutableListOf<SaleSummaryRecord>()
            while (cursor.moveToNext()) result += cursor.toSaleSummary()
            return result
        }
    }

    fun loadSaleDetail(saleId: Long): SaleDetailRecord? {
        val summary = readableDatabase.query(
            "sales",
            arrayOf(
                "id",
                "operator_name",
                "payment_method",
                "total_amount",
                "tax_amount",
                "change_amount",
                "created_at",
                "print_count",
            ),
            "id = ?",
            arrayOf(saleId.toString()),
            null,
            null,
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toSaleSummary() else null } ?: return null

        val items = readableDatabase.query(
            "sale_items",
            arrayOf(
                "product_id",
                "product_name",
                "unit_price",
                "tax_category",
                "quantity",
                "discount_amount",
                "note",
            ),
            "sale_id = ?",
            arrayOf(saleId.toString()),
            null,
            null,
            "id ASC",
        ).use { cursor ->
            val result = mutableListOf<CartItem>()
            while (cursor.moveToNext()) {
                val category = TaxCategory.valueOf(cursor.getString(3))
                val product = Product(
                    id = cursor.getString(0),
                    name = cursor.getString(1),
                    unitPrice = cursor.getLong(2),
                    taxCategory = category,
                    displayOrder = result.size + 1,
                )
                result += CartItem(
                    product = product,
                    quantity = cursor.getInt(4),
                    unitPrice = cursor.getLong(2),
                    discountAmount = cursor.getLong(5),
                    note = cursor.getString(6),
                )
            }
            result
        }

        val payments = readableDatabase.query(
            "sale_payments",
            arrayOf("payment_method", "applied_amount", "received_amount"),
            "sale_id = ?",
            arrayOf(saleId.toString()),
            null,
            null,
            "sequence_no ASC",
        ).use { cursor ->
            val result = mutableListOf<PaymentAllocation>()
            while (cursor.moveToNext()) {
                result += PaymentAllocation(
                    method = PaymentMethod.valueOf(cursor.getString(0)),
                    appliedAmount = cursor.getLong(1),
                    receivedAmount = cursor.getLong(2),
                )
            }
            result
        }
        val snapshotItems = LineTaxSnapshotStore.apply(readableDatabase, LineTaxSnapshotStore.SCOPE_SALE, saleId, items)
        return SaleDetailRecord(summary, snapshotItems, payments, TaxEngine.calculate(snapshotItems))
    }

    fun enqueueReprint(saleId: Long, paperWidthMm: Int): Long {
        require(loadSaleDetail(saleId) != null) { "Sale not found" }
        val now = System.currentTimeMillis()
        return writableDatabase.insertOrThrow(
            "print_jobs",
            null,
            ContentValues().apply {
                put("sale_id", saleId)
                put("paper_width_mm", if (paperWidthMm >= 80) 80 else 58)
                put("status", PrintJobStatus.PENDING.name)
                put("attempt_count", 0)
                putNull("last_error")
                put("created_at", now)
                put("updated_at", now)
            },
        )
    }

    fun listPrintJobs(limit: Int = 100): List<PrintJobRecord> {
        readableDatabase.query(
            "print_jobs",
            PRINT_JOB_COLUMNS,
            null,
            null,
            null,
            null,
            "created_at DESC",
            limit.coerceIn(1, 500).toString(),
        ).use { cursor ->
            val result = mutableListOf<PrintJobRecord>()
            while (cursor.moveToNext()) result += cursor.toPrintJob()
            return result
        }
    }

    fun nextPrintableJob(): PrintJobRecord? {
        readableDatabase.query(
            "print_jobs",
            PRINT_JOB_COLUMNS,
            "status IN (?, ?)",
            arrayOf(PrintJobStatus.PENDING.name, PrintJobStatus.RETRY.name),
            null,
            null,
            "created_at ASC",
            "1",
        ).use { cursor -> return if (cursor.moveToFirst()) cursor.toPrintJob() else null }
    }

    fun markPrintStarted(jobId: Long) {
        updatePrintJob(jobId, PrintJobStatus.PRINTING, null, incrementAttempt = true)
    }

    fun markPrintCompleted(jobId: Long) {
        writableDatabase.runInTransaction {
            val saleId = query(
                "print_jobs",
                arrayOf("sale_id"),
                "id = ?",
                arrayOf(jobId.toString()),
                null,
                null,
                null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
            update(
                "print_jobs",
                ContentValues().apply {
                    put("status", PrintJobStatus.COMPLETED.name)
                    putNull("last_error")
                    put("updated_at", System.currentTimeMillis())
                },
                "id = ?",
                arrayOf(jobId.toString()),
            )
            if (saleId != null) {
                execSQL("UPDATE sales SET print_count = print_count + 1 WHERE id = ?", arrayOf(saleId))
            }
        }
    }

    fun markPrintFailed(jobId: Long, error: String, permanent: Boolean) {
        val current = listPrintJobs(500).firstOrNull { it.id == jobId } ?: return
        val status = if (permanent || current.attemptCount >= 4) PrintJobStatus.FAILED else PrintJobStatus.RETRY
        updatePrintJob(jobId, status, error.take(500), incrementAttempt = false)
    }

    fun retryPrintJob(jobId: Long) {
        updatePrintJob(jobId, PrintJobStatus.RETRY, null, incrementAttempt = false)
    }

    private fun updatePrintJob(
        jobId: Long,
        status: PrintJobStatus,
        error: String?,
        incrementAttempt: Boolean,
    ) {
        writableDatabase.runInTransaction {
            val values = ContentValues().apply {
                put("status", status.name)
                if (error == null) putNull("last_error") else put("last_error", error)
                put("updated_at", System.currentTimeMillis())
            }
            update("print_jobs", values, "id = ?", arrayOf(jobId.toString()))
            if (incrementAttempt) {
                execSQL("UPDATE print_jobs SET attempt_count = attempt_count + 1 WHERE id = ?", arrayOf(jobId))
            }
        }
    }

    private fun Cursor.toSaleSummary() = SaleSummaryRecord(
        id = getLong(0),
        operatorName = getString(1),
        paymentLabel = getString(2),
        totalAmount = getLong(3),
        taxAmount = getLong(4),
        changeAmount = getLong(5),
        createdAt = getLong(6),
        printCount = getInt(7),
    )

    private fun Cursor.toPrintJob() = PrintJobRecord(
        id = getLong(0),
        saleId = getLong(1),
        paperWidthMm = getInt(2),
        status = PrintJobStatus.valueOf(getString(3)),
        attemptCount = getInt(4),
        lastError = if (isNull(5)) null else getString(5),
        createdAt = getLong(6),
        updatedAt = getLong(7),
    )

    private fun Cursor.toCartItem(): CartItem {
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

    private fun createProductsTable(db: SQLiteDatabase) {
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
    }

    private fun createCartTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE cart_items (
                line_no INTEGER PRIMARY KEY,
                product_id TEXT NOT NULL,
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
    }

    private fun createHeldTicketTables(db: SQLiteDatabase) {
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
    }

    private fun createSalesTables(db: SQLiteDatabase) {
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
                created_at INTEGER NOT NULL,
                print_count INTEGER NOT NULL DEFAULT 0
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

    private fun createPrintJobsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS print_jobs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sale_id INTEGER NOT NULL,
                paper_width_mm INTEGER NOT NULL,
                status TEXT NOT NULL,
                attempt_count INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(sale_id) REFERENCES sales(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_print_jobs_status ON print_jobs(status, created_at)")
    }

    private fun insertPrintJob(db: SQLiteDatabase, saleId: Long, paperWidthMm: Int, now: Long) {
        db.insertOrThrow(
            "print_jobs",
            null,
            ContentValues().apply {
                put("sale_id", saleId)
                put("paper_width_mm", if (paperWidthMm >= 80) 80 else 58)
                put("status", PrintJobStatus.PENDING.name)
                put("attempt_count", 0)
                putNull("last_error")
                put("created_at", now)
                put("updated_at", now)
            },
        )
    }

    private fun migrateCartToLineNumber(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE cart_items RENAME TO cart_items_v3")
        createCartTable(db)
        db.execSQL(
            """
            INSERT INTO cart_items (
                line_no, product_id, product_name, unit_price, tax_category,
                display_order, quantity, discount_amount, note
            )
            SELECT rowid, product_id, product_name, unit_price, tax_category,
                   display_order, quantity, discount_amount, note
            FROM cart_items_v3
            ORDER BY rowid
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE cart_items_v3")
    }

    private fun dropAllTables(db: SQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS print_jobs")
        db.execSQL("DROP TABLE IF EXISTS sale_payments")
        db.execSQL("DROP TABLE IF EXISTS sale_items")
        db.execSQL("DROP TABLE IF EXISTS sales")
        db.execSQL("DROP TABLE IF EXISTS held_ticket_items")
        db.execSQL("DROP TABLE IF EXISTS held_tickets")
        db.execSQL("DROP TABLE IF EXISTS cart_items")
        db.execSQL("DROP TABLE IF EXISTS products")
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
        private const val DATABASE_VERSION = 4

        private val CART_COLUMNS = arrayOf(
            "product_id",
            "product_name",
            "unit_price",
            "tax_category",
            "display_order",
            "quantity",
            "discount_amount",
            "note",
        )

        private val PRINT_JOB_COLUMNS = arrayOf(
            "id",
            "sale_id",
            "paper_width_mm",
            "status",
            "attempt_count",
            "last_error",
            "created_at",
            "updated_at",
        )
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
