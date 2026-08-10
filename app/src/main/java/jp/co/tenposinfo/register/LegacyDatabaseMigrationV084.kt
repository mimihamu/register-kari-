package jp.co.tenposinfo.register

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/**
 * v0.84: SQLiteOpenHelper の旧DB更新を非破壊で行う。
 *
 * 旧 v0.2 以前のDBにも商品・作業中カート・保留伝票・売上が存在し得るため、
 * 業務テーブルをDROPして作り直さない。存在しないテーブル／列だけを追加する。
 */
internal object LegacyDatabaseMigrationV084 {
    fun migrate(db: SQLiteDatabase, oldVersion: Int) {
        if (oldVersion < 2) {
            ensureLegacyBaseTables(db)
        }
        if (oldVersion < 3) {
            addColumnIfMissing(db, "cart_items", "discount_amount", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "cart_items", "note", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "held_ticket_items", "discount_amount", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "held_ticket_items", "note", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "sale_items", "discount_amount", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "sale_items", "note", "TEXT NOT NULL DEFAULT ''")
            createSalePaymentsTable(db)
        }
        if (oldVersion < 4) {
            if (!hasColumn(db, "cart_items", "line_no")) {
                migrateCartToLineNumber(db)
            }
            addColumnIfMissing(db, "sales", "print_count", "INTEGER NOT NULL DEFAULT 0")
            createPrintJobsTable(db)
        }
    }

    private fun ensureLegacyBaseTables(db: SQLiteDatabase) {
        val productsMissing = !tableExists(db, "products")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS products (
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
            CREATE TABLE IF NOT EXISTS cart_items (
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
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS held_tickets (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                operator_name TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS held_ticket_items (
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
            CREATE TABLE IF NOT EXISTS sales (
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
            CREATE TABLE IF NOT EXISTS sale_items (
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
        if (productsMissing) insertSeedProducts(db)
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

    private fun migrateCartToLineNumber(db: SQLiteDatabase) {
        check(!tableExists(db, CART_MIGRATION_TEMP)) {
            "作業中カートの旧マイグレーション一時テーブルが残っています。自動削除せず復旧を中止します"
        }
        db.execSQL("ALTER TABLE cart_items RENAME TO $CART_MIGRATION_TEMP")
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
        db.execSQL(
            """
            INSERT INTO cart_items (
                line_no, product_id, product_name, unit_price, tax_category,
                display_order, quantity, discount_amount, note
            )
            SELECT rowid, product_id, product_name, unit_price, tax_category,
                   display_order, quantity, discount_amount, note
            FROM $CART_MIGRATION_TEMP
            ORDER BY rowid
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE $CART_MIGRATION_TEMP")
    }

    internal fun tableExists(db: SQLiteDatabase, table: String): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
        arrayOf(table),
    ).use { it.moveToFirst() }

    internal fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean {
        require(table in ALLOWED_TABLES) { "未対応テーブルです" }
        return db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
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

    private fun addColumnIfMissing(
        db: SQLiteDatabase,
        table: String,
        column: String,
        definition: String,
    ) {
        require(table in ALLOWED_TABLES) { "未対応テーブルです" }
        require(column.matches(Regex("[a-z0-9_]+"))) { "列名が不正です" }
        if (!hasColumn(db, table, column)) {
            db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
        }
    }

    private fun insertSeedProducts(db: SQLiteDatabase) {
        val seeds = listOf(
            arrayOf("P0001", "生ビール", "600", TaxCategory.INCLUDED_10.name, "1"),
            arrayOf("P0002", "ハイボール", "520", TaxCategory.INCLUDED_10.name, "2"),
            arrayOf("P0003", "ウーロン茶", "300", TaxCategory.INCLUDED_10.name, "3"),
            arrayOf("P0010", "枝豆", "420", TaxCategory.INCLUDED_10.name, "4"),
            arrayOf("P0011", "唐揚げ", "680", TaxCategory.INCLUDED_10.name, "5"),
            arrayOf("P0012", "刺身盛合せ", "1680", TaxCategory.INCLUDED_10.name, "6"),
            arrayOf("P0020", "焼き鳥", "180", TaxCategory.INCLUDED_10.name, "7"),
            arrayOf("P0021", "弁当", "800", TaxCategory.EXCLUDED_8.name, "8"),
            arrayOf("P0022", "お土産", "1200", TaxCategory.EXCLUDED_10.name, "9"),
            arrayOf("P0030", "サービス品", "100", TaxCategory.NON_TAXABLE.name, "10"),
        )
        seeds.forEach { seed ->
            db.insertOrThrow(
                "products",
                null,
                ContentValues().apply {
                    put("id", seed[0])
                    put("name", seed[1])
                    put("unit_price", seed[2].toLong())
                    put("tax_category", seed[3])
                    put("display_order", seed[4].toInt())
                },
            )
        }
    }

    private const val CART_MIGRATION_TEMP = "cart_items_v3"
    private val ALLOWED_TABLES = setOf("cart_items", "held_ticket_items", "sale_items", "sales")
}
