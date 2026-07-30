package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase

class CatalogMasterStore(context: Context) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val baseDatabase = RegisterDatabase(applicationContext)
    private val db = baseDatabase.writableDatabase

    init {
        CatalogSchema.ensure(db)
        synchronizeEffectiveProducts()
    }

    override fun close() = baseDatabase.close()

    fun listDepartments(): List<DepartmentRecord> {
        val result = mutableListOf<DepartmentRecord>()
        db.query(
            "catalog_departments",
            arrayOf("id", "code", "name", "enabled", "display_order"),
            null,
            null,
            null,
            null,
            "display_order ASC, id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += DepartmentRecord(
                    id = cursor.getLong(0),
                    code = cursor.getString(1),
                    name = cursor.getString(2),
                    enabled = cursor.getInt(3) != 0,
                    displayOrder = cursor.getInt(4),
                )
            }
        }
        return result
    }

    fun saveDepartment(
        id: Long?,
        code: String,
        name: String,
        enabled: Boolean,
        displayOrder: Int,
        actor: String,
    ): Long {
        val cleanCode = CatalogValidation.requireCode(code, "部門コード")
        val cleanName = CatalogValidation.requireName(name, "部門名")
        require(displayOrder in 0..9999) { "表示順は0～9999です" }
        return db.transaction {
            val values = ContentValues().apply {
                put("code", cleanCode)
                put("name", cleanName)
                put("enabled", if (enabled) 1 else 0)
                put("display_order", displayOrder)
                put("updated_at", System.currentTimeMillis())
            }
            val resultId = if (id == null) {
                values.put("created_at", System.currentTimeMillis())
                insertOrThrow("catalog_departments", null, values)
            } else {
                require(update("catalog_departments", values, "id = ?", arrayOf(id.toString())) == 1) { "部門が見つかりません" }
                id
            }
            bumpRevision(this)
            insertAudit(this, if (id == null) "DEPARTMENT_CREATED" else "DEPARTMENT_UPDATED", resultId, "$cleanCode / $cleanName", actor)
            resultId
        }
    }

    fun listGroups(): List<ProductGroupRecord> {
        val result = mutableListOf<ProductGroupRecord>()
        db.query(
            "catalog_groups",
            arrayOf("id", "code", "name", "department_id", "enabled", "display_order"),
            null,
            null,
            null,
            null,
            "display_order ASC, id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += ProductGroupRecord(
                    id = cursor.getLong(0),
                    code = cursor.getString(1),
                    name = cursor.getString(2),
                    departmentId = if (cursor.isNull(3)) null else cursor.getLong(3),
                    enabled = cursor.getInt(4) != 0,
                    displayOrder = cursor.getInt(5),
                )
            }
        }
        return result
    }

    fun saveGroup(
        id: Long?,
        code: String,
        name: String,
        departmentId: Long?,
        enabled: Boolean,
        displayOrder: Int,
        actor: String,
    ): Long {
        val cleanCode = CatalogValidation.requireCode(code, "グループコード")
        val cleanName = CatalogValidation.requireName(name, "グループ名")
        require(displayOrder in 0..9999) { "表示順は0～9999です" }
        departmentId?.let { require(exists("catalog_departments", it)) { "部門が見つかりません" } }
        return db.transaction {
            val values = ContentValues().apply {
                put("code", cleanCode)
                put("name", cleanName)
                if (departmentId == null) putNull("department_id") else put("department_id", departmentId)
                put("enabled", if (enabled) 1 else 0)
                put("display_order", displayOrder)
                put("updated_at", System.currentTimeMillis())
            }
            val resultId = if (id == null) {
                values.put("created_at", System.currentTimeMillis())
                insertOrThrow("catalog_groups", null, values)
            } else {
                require(update("catalog_groups", values, "id = ?", arrayOf(id.toString())) == 1) { "グループが見つかりません" }
                id
            }
            bumpRevision(this)
            insertAudit(this, if (id == null) "GROUP_CREATED" else "GROUP_UPDATED", resultId, "$cleanCode / $cleanName", actor)
            resultId
        }
    }

    fun listProducts(includeDisabled: Boolean = true): List<ProductMasterRecord> {
        val result = mutableListOf<ProductMasterRecord>()
        val selection = if (includeDisabled) null else "COALESCE(m.enabled, 1) = 1"
        db.rawQuery(
            """
            SELECT p.id, p.name, b.base_price, b.base_tax_category,
                   m.department_id, m.group_id, COALESCE(m.enabled, 1),
                   COALESCE(m.button_color, 'BLUE'), COALESCE(m.page_no, 1),
                   COALESCE(m.slot_no, p.display_order), p.display_order
            FROM products p
            INNER JOIN catalog_product_base b ON b.product_id = p.id
            LEFT JOIN product_meta m ON m.product_id = p.id
            ${if (selection == null) "" else "WHERE $selection"}
            ORDER BY COALESCE(m.page_no, 1), COALESCE(m.slot_no, p.display_order), p.id
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += ProductMasterRecord(
                    productId = cursor.getString(0),
                    name = cursor.getString(1),
                    basePrice = cursor.getLong(2),
                    baseTaxCategory = TaxCategory.valueOf(cursor.getString(3)),
                    departmentId = if (cursor.isNull(4)) null else cursor.getLong(4),
                    groupId = if (cursor.isNull(5)) null else cursor.getLong(5),
                    enabled = cursor.getInt(6) != 0,
                    buttonColor = cursor.getString(7),
                    pageNo = cursor.getInt(8),
                    slotNo = cursor.getInt(9),
                    displayOrder = cursor.getInt(10),
                )
            }
        }
        return result
    }

    fun saveProduct(
        originalId: String?,
        productId: String,
        name: String,
        basePrice: Long,
        taxCategory: TaxCategory,
        departmentId: Long?,
        groupId: Long?,
        enabled: Boolean,
        buttonColor: String,
        pageNo: Int,
        slotNo: Int,
        actor: String,
    ) {
        val cleanId = CatalogValidation.requireCode(productId, "商品コード")
        val cleanName = CatalogValidation.requireName(name, "商品名")
        require(basePrice in 0..99_999_999L) { "価格は0～99,999,999円です" }
        ButtonLayoutPolicy.validate(pageNo, slotNo)
        departmentId?.let { require(exists("catalog_departments", it)) { "部門が見つかりません" } }
        groupId?.let { require(exists("catalog_groups", it)) { "グループが見つかりません" } }
        val cleanColor = buttonColor.uppercase().takeIf { it in BUTTON_COLORS } ?: "BLUE"
        require(originalId == null || originalId == cleanId) { "登録後の商品コードは変更できません" }

        db.transaction {
            val exists = longQuery(this, "SELECT COUNT(*) FROM products WHERE id = ?", arrayOf(cleanId)) > 0
            if (!exists) {
                insertOrThrow(
                    "products",
                    null,
                    ContentValues().apply {
                        put("id", cleanId)
                        put("name", cleanName)
                        put("unit_price", basePrice)
                        put("tax_category", taxCategory.name)
                        put("display_order", ButtonLayoutPolicy.displayOrder(pageNo, slotNo))
                    },
                )
            } else {
                update(
                    "products",
                    ContentValues().apply { put("name", cleanName) },
                    "id = ?",
                    arrayOf(cleanId),
                )
            }
            insertWithOnConflict(
                "catalog_product_base",
                null,
                ContentValues().apply {
                    put("product_id", cleanId)
                    put("base_price", basePrice)
                    put("base_tax_category", taxCategory.name)
                    put("updated_at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            val oldPlacement = productPlacement(this, cleanId)
            val occupied = productAt(this, pageNo, slotNo, cleanId)
            if (occupied != null && oldPlacement != null) {
                update(
                    "product_meta",
                    ContentValues().apply {
                        put("page_no", oldPlacement.first)
                        put("slot_no", oldPlacement.second)
                        put("updated_at", System.currentTimeMillis())
                    },
                    "product_id = ?",
                    arrayOf(occupied),
                )
            }
            insertWithOnConflict(
                "product_meta",
                null,
                ContentValues().apply {
                    put("product_id", cleanId)
                    if (departmentId == null) putNull("department_id") else put("department_id", departmentId)
                    if (groupId == null) putNull("group_id") else put("group_id", groupId)
                    put("enabled", if (enabled) 1 else 0)
                    put("button_color", cleanColor)
                    put("page_no", pageNo)
                    put("slot_no", slotNo)
                    put("updated_at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            bumpRevision(this)
            insertAudit(this, if (exists) "PRODUCT_UPDATED" else "PRODUCT_CREATED", 0, "$cleanId / $cleanName / ${basePrice}円 / ${taxCategory.displayName}", actor)
        }
        synchronizeEffectiveProducts()
    }

    fun moveProduct(productId: String, pageNo: Int, slotNo: Int, actor: String) {
        ButtonLayoutPolicy.validate(pageNo, slotNo)
        db.transaction {
            val old = productPlacement(this, productId) ?: error("商品が見つかりません")
            val occupied = productAt(this, pageNo, slotNo, productId)
            if (occupied != null) {
                update(
                    "product_meta",
                    ContentValues().apply {
                        put("page_no", old.first)
                        put("slot_no", old.second)
                        put("updated_at", System.currentTimeMillis())
                    },
                    "product_id = ?",
                    arrayOf(occupied),
                )
            }
            update(
                "product_meta",
                ContentValues().apply {
                    put("page_no", pageNo)
                    put("slot_no", slotNo)
                    put("updated_at", System.currentTimeMillis())
                },
                "product_id = ?",
                arrayOf(productId),
            )
            bumpRevision(this)
            insertAudit(this, "PRODUCT_BUTTON_MOVED", 0, "$productId を ${pageNo}ページ ${slotNo}番へ移動", actor)
        }
        synchronizeEffectiveProducts()
    }

    fun listTaxMasters(): List<TaxMasterRecord> {
        val result = mutableListOf<TaxMasterRecord>()
        db.query(
            "tax_rate_master",
            arrayOf("system_key", "label", "rate_percent", "price_mode", "reduced", "enabled", "valid_from", "valid_to"),
            null,
            null,
            null,
            null,
            "display_order ASC, system_key ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.getString(0)
                result += TaxMasterRecord(
                    systemKey = key,
                    label = cursor.getString(1),
                    ratePercent = cursor.getInt(2),
                    priceMode = cursor.getString(3),
                    reduced = cursor.getInt(4) != 0,
                    enabled = cursor.getInt(5) != 0,
                    validFrom = cursor.getString(6),
                    validTo = cursor.getString(7),
                    engineSupported = TaxMasterCompatibility.supportedCategory(key) != null,
                )
            }
        }
        return result
    }

    fun saveTaxMaster(record: TaxMasterRecord, actor: String) {
        require(TaxMasterCompatibility.supportedCategory(record.systemKey) != null) { "現在の税計算エンジンで未対応の税区分です" }
        val label = CatalogValidation.requireName(record.label, "税区分名")
        db.transaction {
            update(
                "tax_rate_master",
                ContentValues().apply {
                    put("label", label)
                    put("enabled", if (record.enabled) 1 else 0)
                    put("valid_from", record.validFrom.trim())
                    put("valid_to", record.validTo.trim())
                    put("updated_at", System.currentTimeMillis())
                },
                "system_key = ?",
                arrayOf(record.systemKey),
            )
            bumpRevision(this)
            insertAudit(this, "TAX_MASTER_UPDATED", 0, "${record.systemKey} / $label / ${if (record.enabled) "有効" else "停止"}", actor)
        }
    }

    fun listProfiles(): List<SalesProfileRecord> {
        val result = mutableListOf<SalesProfileRecord>()
        db.query(
            "sales_profiles",
            arrayOf("id", "code", "name", "enabled", "start_minute", "end_minute", "priority", "is_default"),
            null,
            null,
            null,
            null,
            "priority DESC, id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += SalesProfileRecord(
                    id = cursor.getLong(0),
                    code = cursor.getString(1),
                    name = cursor.getString(2),
                    enabled = cursor.getInt(3) != 0,
                    startMinute = cursor.getInt(4),
                    endMinute = cursor.getInt(5),
                    priority = cursor.getInt(6),
                    isDefault = cursor.getInt(7) != 0,
                )
            }
        }
        return result
    }

    fun activeProfile(): SalesProfileRecord? = SalesProfileSelector.select(listProfiles(), SalesProfileSelector.currentMinute())

    fun saveProfile(
        id: Long?,
        code: String,
        name: String,
        enabled: Boolean,
        startMinute: Int,
        endMinute: Int,
        priority: Int,
        isDefault: Boolean,
        actor: String,
    ): Long {
        val cleanCode = CatalogValidation.requireCode(code, "プロファイルコード")
        val cleanName = CatalogValidation.requireName(name, "プロファイル名")
        require(startMinute in 0..1439 && endMinute in 0..1439) { "開始・終了時刻が不正です" }
        require(priority in 0..9999) { "優先度は0～9999です" }
        val result = db.transaction {
            if (isDefault) update("sales_profiles", ContentValues().apply { put("is_default", 0) }, null, null)
            val values = ContentValues().apply {
                put("code", cleanCode)
                put("name", cleanName)
                put("enabled", if (enabled) 1 else 0)
                put("start_minute", startMinute)
                put("end_minute", endMinute)
                put("priority", priority)
                put("is_default", if (isDefault) 1 else 0)
                put("updated_at", System.currentTimeMillis())
            }
            val profileId = if (id == null) {
                values.put("created_at", System.currentTimeMillis())
                insertOrThrow("sales_profiles", null, values)
            } else {
                require(update("sales_profiles", values, "id = ?", arrayOf(id.toString())) == 1) { "販売プロファイルが見つかりません" }
                id
            }
            bumpRevision(this)
            insertAudit(this, if (id == null) "SALES_PROFILE_CREATED" else "SALES_PROFILE_UPDATED", profileId, "$cleanCode / $cleanName / ${SalesProfileRecord.minuteText(startMinute)}-${SalesProfileRecord.minuteText(endMinute)}", actor)
            profileId
        }
        synchronizeEffectiveProducts()
        return result
    }

    fun listOverrides(profileId: Long): List<ProductProfileOverride> {
        val result = mutableListOf<ProductProfileOverride>()
        db.query(
            "profile_product_overrides",
            arrayOf("profile_id", "product_id", "unit_price", "tax_system_key"),
            "profile_id = ?",
            arrayOf(profileId.toString()),
            null,
            null,
            "product_id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += ProductProfileOverride(
                    profileId = cursor.getLong(0),
                    productId = cursor.getString(1),
                    unitPrice = if (cursor.isNull(2)) null else cursor.getLong(2),
                    taxCategory = if (cursor.isNull(3)) null else TaxMasterCompatibility.supportedCategory(cursor.getString(3)),
                )
            }
        }
        return result
    }

    fun saveOverride(
        profileId: Long,
        productId: String,
        unitPrice: Long?,
        taxCategory: TaxCategory?,
        actor: String,
    ) {
        require(exists("sales_profiles", profileId)) { "販売プロファイルが見つかりません" }
        require(longQuery(db, "SELECT COUNT(*) FROM products WHERE id = ?", arrayOf(productId)) > 0) { "商品が見つかりません" }
        unitPrice?.let { require(it in 0..99_999_999L) { "価格は0～99,999,999円です" } }
        db.transaction {
            if (unitPrice == null && taxCategory == null) {
                delete("profile_product_overrides", "profile_id = ? AND product_id = ?", arrayOf(profileId.toString(), productId))
            } else {
                insertWithOnConflict(
                    "profile_product_overrides",
                    null,
                    ContentValues().apply {
                        put("profile_id", profileId)
                        put("product_id", productId)
                        if (unitPrice == null) putNull("unit_price") else put("unit_price", unitPrice)
                        if (taxCategory == null) putNull("tax_system_key") else put("tax_system_key", taxCategory.name)
                        put("updated_at", System.currentTimeMillis())
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            bumpRevision(this)
            insertAudit(this, "PROFILE_PRODUCT_OVERRIDE", profileId, "$productId / 価格 ${unitPrice ?: "基準"} / 税 ${taxCategory?.displayName ?: "基準"}", actor)
        }
        synchronizeEffectiveProducts()
    }

    fun catalogRevision(): Long = longQuery(db, "SELECT revision FROM catalog_revision WHERE id = 1", null)

    fun runtimeToken(): String = "${catalogRevision()}:${activeProfile()?.id ?: 0L}"

    fun synchronizeEffectiveProducts(): String {
        CatalogSchema.ensure(db)
        val profile = activeProfile()
        val overrides = if (profile == null) emptyMap() else listOverrides(profile.id).associateBy { it.productId }
        db.transaction {
            val products = listProducts(includeDisabled = true)
            products.forEach { product ->
                val override = overrides[product.productId]
                update(
                    "products",
                    ContentValues().apply {
                        put("unit_price", override?.unitPrice ?: product.basePrice)
                        put("tax_category", (override?.taxCategory ?: product.baseTaxCategory).name)
                        put("display_order", ButtonLayoutPolicy.displayOrder(product.pageNo, product.slotNo))
                    },
                    "id = ?",
                    arrayOf(product.productId),
                )
            }
        }
        return runtimeToken()
    }

    private fun exists(table: String, id: Long): Boolean =
        longQuery(db, "SELECT COUNT(*) FROM $table WHERE id = ?", arrayOf(id.toString())) > 0

    companion object {
        val BUTTON_COLORS = setOf("BLUE", "GREEN", "YELLOW", "PINK", "GRAY", "WHITE")
    }
}

object CatalogSchema {
    fun ensure(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS catalog_departments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                code TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                display_order INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS catalog_groups (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                code TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                department_id INTEGER,
                enabled INTEGER NOT NULL DEFAULT 1,
                display_order INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS catalog_product_base (
                product_id TEXT PRIMARY KEY,
                base_price INTEGER NOT NULL,
                base_tax_category TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS product_meta (
                product_id TEXT PRIMARY KEY,
                department_id INTEGER,
                group_id INTEGER,
                enabled INTEGER NOT NULL DEFAULT 1,
                button_color TEXT NOT NULL DEFAULT 'BLUE',
                page_no INTEGER NOT NULL DEFAULT 1,
                slot_no INTEGER NOT NULL DEFAULT 1,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS tax_rate_master (
                system_key TEXT PRIMARY KEY,
                label TEXT NOT NULL,
                rate_percent INTEGER NOT NULL,
                price_mode TEXT NOT NULL,
                reduced INTEGER NOT NULL DEFAULT 0,
                enabled INTEGER NOT NULL DEFAULT 1,
                valid_from TEXT NOT NULL DEFAULT '',
                valid_to TEXT NOT NULL DEFAULT '',
                display_order INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sales_profiles (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                code TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                start_minute INTEGER NOT NULL,
                end_minute INTEGER NOT NULL,
                priority INTEGER NOT NULL DEFAULT 0,
                is_default INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS profile_product_overrides (
                profile_id INTEGER NOT NULL,
                product_id TEXT NOT NULL,
                unit_price INTEGER,
                tax_system_key TEXT,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(profile_id, product_id)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS catalog_revision (id INTEGER PRIMARY KEY CHECK(id = 1), revision INTEGER NOT NULL)")
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
        db.execSQL("INSERT OR IGNORE INTO catalog_revision(id, revision) VALUES(1, 1)")
        seed(db)
    }

    private fun seed(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        db.execSQL("INSERT OR IGNORE INTO catalog_departments(code,name,enabled,display_order,created_at,updated_at) VALUES('DRINK','ドリンク',1,10,$now,$now)")
        db.execSQL("INSERT OR IGNORE INTO catalog_departments(code,name,enabled,display_order,created_at,updated_at) VALUES('FOOD','フード',1,20,$now,$now)")
        db.execSQL("INSERT OR IGNORE INTO catalog_departments(code,name,enabled,display_order,created_at,updated_at) VALUES('RETAIL','物販',1,30,$now,$now)")
        val drinkId = scalar(db, "SELECT id FROM catalog_departments WHERE code = 'DRINK'")
        val foodId = scalar(db, "SELECT id FROM catalog_departments WHERE code = 'FOOD'")
        val retailId = scalar(db, "SELECT id FROM catalog_departments WHERE code = 'RETAIL'")
        db.execSQL("INSERT OR IGNORE INTO catalog_groups(code,name,department_id,enabled,display_order,created_at,updated_at) VALUES('ALCOHOL','アルコール',$drinkId,1,10,$now,$now)")
        db.execSQL("INSERT OR IGNORE INTO catalog_groups(code,name,department_id,enabled,display_order,created_at,updated_at) VALUES('SOFTDRINK','ソフトドリンク',$drinkId,1,20,$now,$now)")
        db.execSQL("INSERT OR IGNORE INTO catalog_groups(code,name,department_id,enabled,display_order,created_at,updated_at) VALUES('FOOD_ALL','料理',$foodId,1,10,$now,$now)")
        db.execSQL("INSERT OR IGNORE INTO catalog_groups(code,name,department_id,enabled,display_order,created_at,updated_at) VALUES('TAKEOUT','持帰り・物販',$retailId,1,10,$now,$now)")

        db.execSQL(
            """
            INSERT OR IGNORE INTO catalog_product_base(product_id, base_price, base_tax_category, updated_at)
            SELECT id, unit_price, tax_category, $now FROM products
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO product_meta(product_id, department_id, group_id, enabled, button_color, page_no, slot_no, updated_at)
            SELECT id,
                   CASE WHEN id IN ('P0001','P0002','P0003') THEN $drinkId WHEN id IN ('P0021','P0022') THEN $retailId ELSE $foodId END,
                   NULL, 1,
                   CASE display_order % 4 WHEN 0 THEN 'GREEN' WHEN 1 THEN 'BLUE' WHEN 2 THEN 'YELLOW' ELSE 'PINK' END,
                   ((display_order - 1) / 24) + 1,
                   ((display_order - 1) % 24) + 1,
                   $now
            FROM products
            """.trimIndent(),
        )
        TaxCategory.entries.forEachIndexed { index, category ->
            db.insertWithOnConflict(
                "tax_rate_master",
                null,
                ContentValues().apply {
                    put("system_key", category.name)
                    put("label", category.displayName)
                    put("rate_percent", category.ratePercent)
                    put("price_mode", TaxMasterCompatibility.mode(category))
                    put("reduced", if (category.ratePercent == 8) 1 else 0)
                    put("enabled", 1)
                    put("valid_from", "")
                    put("valid_to", "")
                    put("display_order", index + 1)
                    put("updated_at", now)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
        }
        db.execSQL(
            """
            INSERT OR IGNORE INTO sales_profiles(code,name,enabled,start_minute,end_minute,priority,is_default,created_at,updated_at)
            VALUES('DEFAULT','通常営業',1,0,0,0,1,$now,$now)
            """.trimIndent(),
        )
    }

    private fun scalar(db: SQLiteDatabase, sql: String): Long =
        db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
}

private fun productPlacement(db: SQLiteDatabase, productId: String): Pair<Int, Int>? =
    db.rawQuery("SELECT page_no, slot_no FROM product_meta WHERE product_id = ?", arrayOf(productId)).use {
        if (it.moveToFirst()) it.getInt(0) to it.getInt(1) else null
    }

private fun productAt(db: SQLiteDatabase, pageNo: Int, slotNo: Int, exceptProductId: String): String? =
    db.rawQuery(
        "SELECT product_id FROM product_meta WHERE page_no = ? AND slot_no = ? AND product_id <> ? LIMIT 1",
        arrayOf(pageNo.toString(), slotNo.toString(), exceptProductId),
    ).use { if (it.moveToFirst()) it.getString(0) else null }

private fun bumpRevision(db: SQLiteDatabase) {
    db.execSQL("UPDATE catalog_revision SET revision = revision + 1 WHERE id = 1")
}

private fun insertAudit(db: SQLiteDatabase, event: String, referenceId: Long, detail: String, actor: String) {
    db.insertOrThrow(
        "operation_audit",
        null,
        ContentValues().apply {
            put("event_type", event)
            put("reference_id", referenceId)
            put("detail", detail.take(500))
            put("operator_name", actor.ifBlank { "責任者" })
            put("created_at", System.currentTimeMillis())
        },
    )
}

private fun longQuery(db: SQLiteDatabase, sql: String, args: Array<String>?): Long =
    db.rawQuery(sql, args).use { if (it.moveToFirst()) it.getLong(0) else 0L }

private inline fun <T> SQLiteDatabase.transaction(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    return try {
        val value = block()
        setTransactionSuccessful()
        value
    } finally {
        endTransaction()
    }
}
