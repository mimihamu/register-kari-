package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.time.LocalDate

enum class DynamicTaxMode {
    NON_TAXABLE,
    INCLUDED,
    EXCLUDED,
}

data class DynamicTaxRule(
    val key: String,
    val label: String,
    val ratePercent: Int,
    val mode: DynamicTaxMode,
    val reduced: Boolean,
    val enabled: Boolean,
    val symbol: String,
    val validFrom: String,
    val validTo: String,
) {
    val taxable: Boolean get() = mode != DynamicTaxMode.NON_TAXABLE
    val taxIncluded: Boolean get() = mode == DynamicTaxMode.INCLUDED

    fun isEffective(date: LocalDate): Boolean {
        if (!enabled) return false
        val from = validFrom.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
        val to = validTo.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
        return (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to))
    }

    fun applyTo(product: Product): Product = product.copy(
        taxKey = key,
        taxLabel = label,
        taxSymbol = symbol,
        taxRatePercent = ratePercent,
        taxIncluded = taxIncluded,
        taxable = taxable,
        reducedTax = reduced,
    )
}

data class ProductTaxAssignment(
    val productId: String,
    val productName: String,
    val taxKey: String,
    val taxLabel: String,
)

data class MenuRevisionRecord(
    val id: Long,
    val name: String,
    val effectiveDate: String,
    val status: String,
    val createdBy: String,
    val createdAt: Long,
    val itemCount: Int,
)

data class MenuRevisionProduct(
    val productId: String,
    val name: String,
    val enabled: Boolean,
    val unitPrice: Long,
    val legacyTaxCategory: TaxCategory,
    val taxKey: String,
    val taxLabel: String,
    val taxRatePercent: Int,
    val taxIncluded: Boolean,
    val taxable: Boolean,
    val reduced: Boolean,
    val taxSymbol: String,
    val buttonColor: String,
    val pageNo: Int,
    val slotNo: Int,
    val displayOrder: Int,
)

object DynamicTaxValidation {
    private val keyPattern = Regex("[A-Z0-9_-]{1,30}")

    fun normalizeKey(value: String): String = value.trim().uppercase()

    fun validateKey(value: String): String {
        val key = normalizeKey(value)
        require(keyPattern.matches(key)) { "税区分キーは英数字・_・-で30文字以内です" }
        return key
    }

    fun validateDate(value: String, label: String): String {
        val clean = value.trim()
        if (clean.isBlank()) return ""
        runCatching { LocalDate.parse(clean) }.getOrElse { error("$labelはyyyy-MM-dd形式です") }
        return clean
    }

    fun validateRule(record: DynamicTaxRule): DynamicTaxRule {
        val key = validateKey(record.key)
        val label = record.label.trim()
        require(label.isNotBlank()) { "税区分名を入力してください" }
        require(label.length <= 60) { "税区分名は60文字以内です" }
        require(record.ratePercent in 0..100) { "税率は0～100%です" }
        if (record.mode == DynamicTaxMode.NON_TAXABLE) {
            require(record.ratePercent == 0) { "非課税の税率は0%です" }
        } else {
            require(record.ratePercent > 0) { "課税区分の税率は1%以上です" }
        }
        val symbol = record.symbol.trim()
        require(symbol.isNotBlank()) { "税記号を入力してください" }
        require(symbol.length <= 4) { "税記号は4文字以内です" }
        val from = validateDate(record.validFrom, "適用開始日")
        val to = validateDate(record.validTo, "適用終了日")
        if (from.isNotBlank() && to.isNotBlank()) {
            require(!LocalDate.parse(to).isBefore(LocalDate.parse(from))) { "適用終了日は開始日以降です" }
        }
        return record.copy(key = key, label = label, symbol = symbol, validFrom = from, validTo = to)
    }
}

class DynamicCatalogStore(context: Context) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val baseDatabase = RegisterDatabase(applicationContext)
    private val db = baseDatabase.writableDatabase

    init {
        ensureSchema(db)
        seedSystemRules(db)
    }

    override fun close() = baseDatabase.close()

    fun listTaxRules(): List<DynamicTaxRule> {
        val result = mutableListOf<DynamicTaxRule>()
        db.query(
            "dynamic_tax_rules",
            TAX_COLUMNS,
            null,
            null,
            null,
            null,
            "display_order ASC, tax_key ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += DynamicTaxRule(
                    key = cursor.getString(0),
                    label = cursor.getString(1),
                    ratePercent = cursor.getInt(2),
                    mode = DynamicTaxMode.valueOf(cursor.getString(3)),
                    reduced = cursor.getInt(4) != 0,
                    enabled = cursor.getInt(5) != 0,
                    symbol = cursor.getString(6),
                    validFrom = cursor.getString(7),
                    validTo = cursor.getString(8),
                )
            }
        }
        return result
    }

    fun saveTaxRule(originalKey: String?, record: DynamicTaxRule, actor: String) {
        val clean = DynamicTaxValidation.validateRule(record)
        require(originalKey == null || DynamicTaxValidation.normalizeKey(originalKey) == clean.key) {
            "登録後の税区分キーは変更できません"
        }
        db.transaction {
            val exists = scalarLong(this, "SELECT COUNT(*) FROM dynamic_tax_rules WHERE tax_key = ?", arrayOf(clean.key)) > 0
            val values = ContentValues().apply {
                put("tax_key", clean.key)
                put("label", clean.label)
                put("rate_percent", clean.ratePercent)
                put("price_mode", clean.mode.name)
                put("reduced", if (clean.reduced) 1 else 0)
                put("enabled", if (clean.enabled) 1 else 0)
                put("symbol", clean.symbol)
                put("valid_from", clean.validFrom)
                put("valid_to", clean.validTo)
                put("display_order", listTaxRules().indexOfFirst { it.key == clean.key }.takeIf { it >= 0 } ?: 999)
                put("updated_at", System.currentTimeMillis())
            }
            insertWithOnConflict("dynamic_tax_rules", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            audit(this, if (exists) "DYNAMIC_TAX_UPDATED" else "DYNAMIC_TAX_CREATED", clean.key, "${clean.label} ${clean.ratePercent}% ${clean.mode.name}", actor)
        }
    }

    fun deleteTaxRule(key: String, actor: String) {
        val clean = DynamicTaxValidation.validateKey(key)
        require(TaxCategory.entries.none { it.name == clean }) { "標準税区分は削除できません" }
        val assigned = scalarLong(db, "SELECT COUNT(*) FROM product_tax_assignments WHERE tax_key = ?", arrayOf(clean))
        require(assigned == 0L) { "商品に割り当てられている税区分は削除できません" }
        db.transaction {
            delete("dynamic_tax_rules", "tax_key = ?", arrayOf(clean))
            audit(this, "DYNAMIC_TAX_DELETED", clean, clean, actor)
        }
    }

    fun listAssignments(): List<ProductTaxAssignment> {
        val result = mutableListOf<ProductTaxAssignment>()
        db.rawQuery(
            """
            SELECT p.id, p.name, COALESCE(a.tax_key, p.tax_category),
                   COALESCE(t.label, p.tax_category)
            FROM products p
            LEFT JOIN product_tax_assignments a ON a.product_id = p.id
            LEFT JOIN dynamic_tax_rules t ON t.tax_key = COALESCE(a.tax_key, p.tax_category)
            ORDER BY p.display_order, p.id
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += ProductTaxAssignment(
                    productId = cursor.getString(0),
                    productName = cursor.getString(1),
                    taxKey = cursor.getString(2),
                    taxLabel = cursor.getString(3),
                )
            }
        }
        return result
    }

    fun assignProductTax(productId: String, taxKey: String, actor: String) {
        val cleanKey = DynamicTaxValidation.validateKey(taxKey)
        require(scalarLong(db, "SELECT COUNT(*) FROM products WHERE id = ?", arrayOf(productId)) == 1L) { "商品が見つかりません" }
        require(scalarLong(db, "SELECT COUNT(*) FROM dynamic_tax_rules WHERE tax_key = ?", arrayOf(cleanKey)) == 1L) { "税区分が見つかりません" }
        db.transaction {
            insertWithOnConflict(
                "product_tax_assignments",
                null,
                ContentValues().apply {
                    put("product_id", productId)
                    put("tax_key", cleanKey)
                    put("updated_at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            audit(this, "PRODUCT_DYNAMIC_TAX_ASSIGNED", productId, "$productId / $cleanKey", actor)
        }
    }

    fun clearProductTaxAssignment(productId: String, actor: String) {
        db.transaction {
            delete("product_tax_assignments", "product_id = ?", arrayOf(productId))
            audit(this, "PRODUCT_DYNAMIC_TAX_CLEARED", productId, productId, actor)
        }
    }

    fun scheduleCurrentMenu(name: String, effectiveDate: String, actor: String): Long {
        val cleanName = name.trim()
        require(cleanName.isNotBlank()) { "改定名を入力してください" }
        require(cleanName.length <= 80) { "改定名は80文字以内です" }
        val cleanDate = DynamicTaxValidation.validateDate(effectiveDate, "適用営業日")
        require(cleanDate.isNotBlank()) { "適用営業日を入力してください" }
        require(!LocalDate.parse(cleanDate).isBefore(BusinessDateResolver.current(applicationContext))) { "過去の日付は予約できません" }

        CatalogMasterStore(applicationContext).use { catalog ->
            val metadata = catalog.listProducts(includeDisabled = true).associateBy { it.productId }
            val effectiveProducts = baseDatabase.loadProducts().associateBy { it.id }
            val assignments = assignmentMap(db)
            val taxRules = listTaxRules().associateBy { it.key }
            return db.transaction {
                val revisionId = insertOrThrow(
                    "menu_revisions",
                    null,
                    ContentValues().apply {
                        put("name", cleanName)
                        put("effective_date", cleanDate)
                        put("status", "SCHEDULED")
                        put("created_by", actor)
                        put("created_at", System.currentTimeMillis())
                    },
                )
                metadata.values.sortedBy { it.displayOrder }.forEach { meta ->
                    val product = effectiveProducts[meta.productId] ?: Product(
                        id = meta.productId,
                        name = meta.name,
                        unitPrice = meta.basePrice,
                        taxCategory = meta.baseTaxCategory,
                        displayOrder = meta.displayOrder,
                    )
                    val revisionTaxKey = assignments[meta.productId] ?: product.taxKey
                    val revisionTax = taxRules[revisionTaxKey]?.let(TaxSnapshot::from) ?: TaxSnapshot.from(product)
                    insertOrThrow(
                        "menu_revision_products",
                        null,
                        ContentValues().apply {
                            put("revision_id", revisionId)
                            put("product_id", meta.productId)
                            put("product_name", product.name)
                            put("enabled", if (meta.enabled) 1 else 0)
                            put("unit_price", product.unitPrice)
                            put("legacy_tax_category", product.taxCategory.name)
                            put("tax_key", revisionTax.key)
                            put("tax_label", revisionTax.label)
                            put("tax_rate_percent", revisionTax.ratePercent)
                            put("tax_included", if (revisionTax.taxIncluded) 1 else 0)
                            put("taxable", if (revisionTax.taxable) 1 else 0)
                            put("reduced", if (revisionTax.reduced) 1 else 0)
                            put("tax_symbol", revisionTax.symbol)
                            put("button_color", meta.buttonColor)
                            put("page_no", meta.pageNo)
                            put("slot_no", meta.slotNo)
                            put("display_order", ButtonLayoutPolicy.displayOrder(meta.pageNo, meta.slotNo))
                        },
                    )
                }
                audit(this, "MENU_REVISION_SCHEDULED", revisionId.toString(), "$cleanName / $cleanDate / ${metadata.size}商品", actor)
                revisionId
            }
        }
    }

    fun listMenuRevisions(): List<MenuRevisionRecord> {
        val result = mutableListOf<MenuRevisionRecord>()
        db.rawQuery(
            """
            SELECT r.id, r.name, r.effective_date, r.status, r.created_by, r.created_at,
                   COUNT(p.product_id)
            FROM menu_revisions r
            LEFT JOIN menu_revision_products p ON p.revision_id = r.id
            GROUP BY r.id, r.name, r.effective_date, r.status, r.created_by, r.created_at
            ORDER BY r.effective_date DESC, r.id DESC
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += MenuRevisionRecord(
                    id = cursor.getLong(0),
                    name = cursor.getString(1),
                    effectiveDate = cursor.getString(2),
                    status = cursor.getString(3),
                    createdBy = cursor.getString(4),
                    createdAt = cursor.getLong(5),
                    itemCount = cursor.getInt(6),
                )
            }
        }
        return result
    }

    fun cancelMenuRevision(revisionId: Long, actor: String) {
        db.transaction {
            val changed = update(
                "menu_revisions",
                ContentValues().apply { put("status", "CANCELLED") },
                "id = ? AND status = 'SCHEDULED' AND effective_date > ?",
                arrayOf(revisionId.toString(), BusinessDateResolver.current(applicationContext).toString()),
            )
            require(changed == 1) { "適用済みまたは取消済みの改定は取り消せません" }
            audit(this, "MENU_REVISION_CANCELLED", revisionId.toString(), revisionId.toString(), actor)
        }
    }

    fun activeRevision(date: LocalDate = BusinessDateResolver.current(applicationContext)): MenuRevisionRecord? {
        db.rawQuery(
            """
            SELECT r.id, r.name, r.effective_date, r.status, r.created_by, r.created_at,
                   (SELECT COUNT(*) FROM menu_revision_products p WHERE p.revision_id = r.id)
            FROM menu_revisions r
            WHERE r.status = 'SCHEDULED' AND r.effective_date <= ?
            ORDER BY r.effective_date DESC, r.id DESC
            LIMIT 1
            """.trimIndent(),
            arrayOf(date.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return MenuRevisionRecord(
                id = cursor.getLong(0),
                name = cursor.getString(1),
                effectiveDate = cursor.getString(2),
                status = cursor.getString(3),
                createdBy = cursor.getString(4),
                createdAt = cursor.getLong(5),
                itemCount = cursor.getInt(6),
            )
        }
    }

    fun runtimeProducts(
        products: List<Product>,
        metadata: Map<String, ProductMasterRecord>,
        date: LocalDate = BusinessDateResolver.current(applicationContext),
    ): List<Product> {
        val rules = listTaxRules().associateBy { it.key }
        val revision = activeRevision(date)
        if (revision != null) {
            val base = products.associateBy { it.id }
            return revisionProducts(revision.id)
                .filter { it.enabled }
                .map { snapshot ->
                    val fallback = base[snapshot.productId] ?: Product(
                        id = snapshot.productId,
                        name = snapshot.name,
                        unitPrice = snapshot.unitPrice,
                        taxCategory = snapshot.legacyTaxCategory,
                        displayOrder = snapshot.displayOrder,
                    )
                    val positioned = fallback.copy(
                        name = snapshot.name,
                        unitPrice = snapshot.unitPrice,
                        taxCategory = snapshot.legacyTaxCategory,
                        displayOrder = snapshot.displayOrder,
                        buttonColor = snapshot.buttonColor,
                        pageNo = snapshot.pageNo,
                        slotNo = snapshot.slotNo,
                    ).withLegacyTaxCategory(snapshot.legacyTaxCategory)
                    TaxSnapshot(
                        snapshot.taxKey, snapshot.taxLabel, snapshot.taxRatePercent, snapshot.taxIncluded,
                        snapshot.taxable, snapshot.reduced, snapshot.taxSymbol,
                    ).applyTo(positioned)
                }
                .sortedWith(compareBy<Product> { it.pageNo }.thenBy { it.slotNo }.thenBy { it.id })
        }

        val assignments = assignmentMap(db)
        return products.mapNotNull { product ->
            val meta = metadata[product.id] ?: return@mapNotNull product
            if (!meta.enabled) return@mapNotNull null
            val positioned = product.copy(
                displayOrder = ButtonLayoutPolicy.displayOrder(meta.pageNo, meta.slotNo),
                buttonColor = meta.buttonColor,
                pageNo = meta.pageNo,
                slotNo = meta.slotNo,
            )
            val key = assignments[product.id] ?: product.taxKey
            rules[key]?.takeIf { it.isEffective(date) }?.applyTo(positioned) ?: positioned
        }.sortedWith(compareBy<Product> { it.pageNo }.thenBy { it.slotNo }.thenBy { it.id })
    }

    private fun revisionProducts(revisionId: Long): List<MenuRevisionProduct> {
        val result = mutableListOf<MenuRevisionProduct>()
        db.query(
            "menu_revision_products",
            arrayOf(
                "product_id", "product_name", "enabled", "unit_price", "legacy_tax_category",
                "tax_key", "tax_label", "tax_rate_percent", "tax_included", "taxable", "reduced", "tax_symbol",
                "button_color", "page_no", "slot_no", "display_order",
            ),
            "revision_id = ?",
            arrayOf(revisionId.toString()),
            null,
            null,
            "display_order ASC, product_id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += MenuRevisionProduct(
                    productId = cursor.getString(0),
                    name = cursor.getString(1),
                    enabled = cursor.getInt(2) != 0,
                    unitPrice = cursor.getLong(3),
                    legacyTaxCategory = TaxCategory.valueOf(cursor.getString(4)),
                    taxKey = cursor.getString(5),
                    taxLabel = cursor.getString(6),
                    taxRatePercent = cursor.getInt(7),
                    taxIncluded = cursor.getInt(8) != 0,
                    taxable = cursor.getInt(9) != 0,
                    reduced = cursor.getInt(10) != 0,
                    taxSymbol = cursor.getString(11),
                    buttonColor = cursor.getString(12),
                    pageNo = cursor.getInt(13),
                    slotNo = cursor.getInt(14),
                    displayOrder = cursor.getInt(15),
                )
            }
        }
        return result
    }

    companion object {
        private val TAX_COLUMNS = arrayOf(
            "tax_key", "label", "rate_percent", "price_mode", "reduced", "enabled",
            "symbol", "valid_from", "valid_to",
        )

        fun ensureSchema(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS dynamic_tax_rules (
                    tax_key TEXT PRIMARY KEY,
                    label TEXT NOT NULL,
                    rate_percent INTEGER NOT NULL,
                    price_mode TEXT NOT NULL,
                    reduced INTEGER NOT NULL,
                    enabled INTEGER NOT NULL,
                    symbol TEXT NOT NULL,
                    valid_from TEXT NOT NULL DEFAULT '',
                    valid_to TEXT NOT NULL DEFAULT '',
                    display_order INTEGER NOT NULL DEFAULT 999,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS product_tax_assignments (
                    product_id TEXT PRIMARY KEY,
                    tax_key TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    FOREIGN KEY(product_id) REFERENCES products(id) ON DELETE CASCADE,
                    FOREIGN KEY(tax_key) REFERENCES dynamic_tax_rules(tax_key)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS menu_revisions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    effective_date TEXT NOT NULL,
                    status TEXT NOT NULL,
                    created_by TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS menu_revision_products (
                    revision_id INTEGER NOT NULL,
                    product_id TEXT NOT NULL,
                    product_name TEXT NOT NULL,
                    enabled INTEGER NOT NULL,
                    unit_price INTEGER NOT NULL,
                    legacy_tax_category TEXT NOT NULL,
                    tax_key TEXT NOT NULL,
                    tax_label TEXT NOT NULL DEFAULT '',
                    tax_rate_percent INTEGER NOT NULL DEFAULT 0,
                    tax_included INTEGER NOT NULL DEFAULT 0,
                    taxable INTEGER NOT NULL DEFAULT 0,
                    reduced INTEGER NOT NULL DEFAULT 0,
                    tax_symbol TEXT NOT NULL DEFAULT '',
                    button_color TEXT NOT NULL,
                    page_no INTEGER NOT NULL,
                    slot_no INTEGER NOT NULL,
                    display_order INTEGER NOT NULL,
                    PRIMARY KEY(revision_id, product_id),
                    FOREIGN KEY(revision_id) REFERENCES menu_revisions(id) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS dynamic_catalog_audit (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    event_type TEXT NOT NULL,
                    target_key TEXT NOT NULL,
                    details TEXT NOT NULL,
                    actor TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            SchemaMigration.ensureColumn(db, "menu_revision_products", "tax_label", "TEXT NOT NULL DEFAULT ''")
            SchemaMigration.ensureColumn(db, "menu_revision_products", "tax_rate_percent", "INTEGER NOT NULL DEFAULT 0")
            SchemaMigration.ensureColumn(db, "menu_revision_products", "tax_included", "INTEGER NOT NULL DEFAULT 0")
            SchemaMigration.ensureColumn(db, "menu_revision_products", "taxable", "INTEGER NOT NULL DEFAULT 0")
            SchemaMigration.ensureColumn(db, "menu_revision_products", "reduced", "INTEGER NOT NULL DEFAULT 0")
            SchemaMigration.ensureColumn(db, "menu_revision_products", "tax_symbol", "TEXT NOT NULL DEFAULT ''")
            db.execSQL(
                """
                UPDATE menu_revision_products
                SET tax_label = CASE legacy_tax_category
                        WHEN 'INCLUDED_10' THEN '10%内税' WHEN 'EXCLUDED_10' THEN '10%外税'
                        WHEN 'INCLUDED_8' THEN '8%内税' WHEN 'EXCLUDED_8' THEN '8%外税' ELSE '非課税' END,
                    tax_rate_percent = CASE legacy_tax_category
                        WHEN 'INCLUDED_10' THEN 10 WHEN 'EXCLUDED_10' THEN 10
                        WHEN 'INCLUDED_8' THEN 8 WHEN 'EXCLUDED_8' THEN 8 ELSE 0 END,
                    tax_included = CASE WHEN legacy_tax_category IN ('INCLUDED_10','INCLUDED_8') THEN 1 ELSE 0 END,
                    taxable = CASE WHEN legacy_tax_category = 'NON_TAXABLE' THEN 0 ELSE 1 END,
                    reduced = CASE WHEN legacy_tax_category IN ('INCLUDED_8','EXCLUDED_8') THEN 1 ELSE 0 END,
                    tax_symbol = CASE legacy_tax_category
                        WHEN 'INCLUDED_10' THEN '内' WHEN 'EXCLUDED_10' THEN '外'
                        WHEN 'INCLUDED_8' THEN '内※' WHEN 'EXCLUDED_8' THEN '外※' ELSE '非' END
                WHERE tax_label = ''
                """.trimIndent(),
            )
            LineTaxSnapshotStore.ensureSchema(db)
        }

        private fun seedSystemRules(db: SQLiteDatabase) {
            TaxCategory.entries.forEachIndexed { index, category ->
                val mode = when {
                    !category.taxable -> DynamicTaxMode.NON_TAXABLE
                    category.taxIncluded -> DynamicTaxMode.INCLUDED
                    else -> DynamicTaxMode.EXCLUDED
                }
                db.insertWithOnConflict(
                    "dynamic_tax_rules",
                    null,
                    ContentValues().apply {
                        put("tax_key", category.name)
                        put("label", category.displayName)
                        put("rate_percent", category.ratePercent)
                        put("price_mode", mode.name)
                        put("reduced", if (category.symbol.contains("※")) 1 else 0)
                        put("enabled", 1)
                        put("symbol", category.symbol)
                        put("valid_from", "")
                        put("valid_to", "")
                        put("display_order", index)
                        put("updated_at", System.currentTimeMillis())
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
        }

        private fun assignmentMap(db: SQLiteDatabase): Map<String, String> {
            val result = linkedMapOf<String, String>()
            db.query("product_tax_assignments", arrayOf("product_id", "tax_key"), null, null, null, null, null)
                .use { cursor -> while (cursor.moveToNext()) result[cursor.getString(0)] = cursor.getString(1) }
            return result
        }

        private fun scalarLong(db: SQLiteDatabase, sql: String, args: Array<String>): Long =
            db.rawQuery(sql, args).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

        private fun audit(db: SQLiteDatabase, event: String, target: String, details: String, actor: String) {
            db.insertOrThrow(
                "dynamic_catalog_audit",
                null,
                ContentValues().apply {
                    put("event_type", event)
                    put("target_key", target)
                    put("details", details.take(500))
                    put("actor", actor)
                    put("created_at", System.currentTimeMillis())
                },
            )
        }
    }
}

object CatalogProductRuntime {
    fun visibleProducts(context: Context, products: List<Product>): List<Product> = runCatching {
        val metadata = CatalogMasterStore(context.applicationContext).use { store ->
            store.listProducts(includeDisabled = true).associateBy { it.productId }
        }
        DynamicCatalogStore(context.applicationContext).use { store ->
            store.runtimeProducts(products, metadata)
        }
    }.getOrElse { products }

    fun metadata(context: Context): Map<String, ProductMasterRecord> = runCatching {
        CatalogMasterStore(context.applicationContext).use { store ->
            store.listProducts(includeDisabled = false).associateBy { it.productId }
        }
    }.getOrDefault(emptyMap())

    fun status(context: Context): String = runCatching {
        CatalogMasterStore(context.applicationContext).use { catalog ->
            val profile = catalog.activeProfile()?.name ?: "既定"
            DynamicCatalogStore(context.applicationContext).use { dynamic ->
                val revision = dynamic.activeRevision()
                if (revision == null) "プロファイル：$profile" else "改定：${revision.name}（${revision.effectiveDate}）"
            }
        }
    }.getOrDefault("メニュー読込中")
}

object LineTaxSnapshotStore {
    const val SCOPE_CART = "CART"
    const val SCOPE_HELD = "HELD"
    const val SCOPE_SALE = "SALE"

    fun ensureSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS line_tax_snapshots (
                scope TEXT NOT NULL,
                owner_id INTEGER NOT NULL,
                line_no INTEGER NOT NULL,
                product_id TEXT NOT NULL,
                tax_key TEXT NOT NULL,
                tax_label TEXT NOT NULL,
                tax_symbol TEXT NOT NULL,
                rate_percent INTEGER NOT NULL,
                tax_included INTEGER NOT NULL,
                taxable INTEGER NOT NULL,
                reduced INTEGER NOT NULL,
                PRIMARY KEY(scope, owner_id, line_no)
            )
            """.trimIndent(),
        )
    }

    fun save(db: SQLiteDatabase, scope: String, ownerId: Long, items: List<CartItem>) {
        ensureSchema(db)
        db.delete("line_tax_snapshots", "scope = ? AND owner_id = ?", arrayOf(scope, ownerId.toString()))
        items.forEachIndexed { index, item ->
            db.insertOrThrow(
                "line_tax_snapshots",
                null,
                ContentValues().apply {
                    put("scope", scope)
                    put("owner_id", ownerId)
                    put("line_no", index + 1)
                    put("product_id", item.product.id)
                    put("tax_key", item.product.taxKey)
                    put("tax_label", item.product.taxLabel)
                    put("tax_symbol", item.product.taxSymbol)
                    put("rate_percent", item.product.taxRatePercent)
                    put("tax_included", if (item.product.taxIncluded) 1 else 0)
                    put("taxable", if (item.product.taxable) 1 else 0)
                    put("reduced", if (item.product.reducedTax) 1 else 0)
                },
            )
        }
    }

    fun apply(db: SQLiteDatabase, scope: String, ownerId: Long, items: List<CartItem>): List<CartItem> {
        ensureSchema(db)
        val snapshots = linkedMapOf<Int, DynamicTaxRule>()
        db.query(
            "line_tax_snapshots",
            arrayOf("line_no", "tax_key", "tax_label", "rate_percent", "tax_included", "taxable", "reduced", "tax_symbol"),
            "scope = ? AND owner_id = ?",
            arrayOf(scope, ownerId.toString()),
            null,
            null,
            "line_no ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val mode = when {
                    cursor.getInt(5) == 0 -> DynamicTaxMode.NON_TAXABLE
                    cursor.getInt(4) != 0 -> DynamicTaxMode.INCLUDED
                    else -> DynamicTaxMode.EXCLUDED
                }
                snapshots[cursor.getInt(0)] = DynamicTaxRule(
                    key = cursor.getString(1),
                    label = cursor.getString(2),
                    ratePercent = cursor.getInt(3),
                    mode = mode,
                    reduced = cursor.getInt(6) != 0,
                    enabled = true,
                    symbol = cursor.getString(7),
                    validFrom = "",
                    validTo = "",
                )
            }
        }
        return items.mapIndexed { index, item ->
            val snapshot = snapshots[index + 1] ?: return@mapIndexed item
            item.copy(product = snapshot.applyTo(item.product))
        }
    }
}

private inline fun <T> SQLiteDatabase.transaction(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    return try {
        val result = block()
        setTransactionSuccessful()
        result
    } finally {
        endTransaction()
    }
}
