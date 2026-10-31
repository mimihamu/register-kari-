package jp.co.tenposinfo.register

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/** v2.5 BKP-007: menu master snapshot captured immediately before destructive revision apply. */
data class MenuMasterSnapshotRowV145(
    val productId: String,
    val name: String,
    val unitPrice: Long,
    val taxCategory: String,
    val displayOrder: Int,
    val baseExists: Boolean,
    val basePrice: Long,
    val baseTaxCategory: String,
    val baseUpdatedAt: Long,
    val metaExists: Boolean,
    val departmentId: Long?,
    val groupId: Long?,
    val enabled: Boolean,
    val buttonColor: String,
    val pageNo: Int,
    val slotNo: Int,
    val kana: String,
    val barcode: String,
    val metaUpdatedAt: Long,
    val assignmentExists: Boolean,
    val assignmentTaxKey: String?,
    val assignmentUpdatedAt: Long,
)

data class MenuMasterPreApplySnapshotV145(
    val catalogRevision: Long,
    val capturedAt: Long,
    val rows: List<MenuMasterSnapshotRowV145>,
) {
    fun restore(db: SQLiteDatabase) {
        val beforeIds = rows.mapTo(linkedSetOf()) { it.productId }
        val currentIds = buildList {
            db.rawQuery("SELECT id FROM products", null).use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        currentIds.filterNot(beforeIds::contains).forEach { id ->
            db.delete("product_tax_assignments", "product_id = ?", arrayOf(id))
            db.delete("product_meta", "product_id = ?", arrayOf(id))
            db.delete("catalog_product_base", "product_id = ?", arrayOf(id))
            db.delete("products", "id = ?", arrayOf(id))
        }
        rows.forEach { row -> restoreRow(db, row) }
        db.update(
            "catalog_revision",
            ContentValues().apply { put("revision", catalogRevision) },
            "id = 1",
            null,
        )
    }

    private fun restoreRow(db: SQLiteDatabase, row: MenuMasterSnapshotRowV145) {
        val product = ContentValues().apply {
            put("name", row.name)
            put("unit_price", row.unitPrice)
            put("tax_category", row.taxCategory)
            put("display_order", row.displayOrder)
        }
        val exists = db.rawQuery("SELECT COUNT(*) FROM products WHERE id = ?", arrayOf(row.productId)).use {
            it.moveToFirst() && it.getLong(0) > 0L
        }
        if (exists) {
            db.update("products", product, "id = ?", arrayOf(row.productId))
        } else {
            product.put("id", row.productId)
            db.insertOrThrow("products", null, product)
        }

        if (row.baseExists) {
            db.insertWithOnConflict(
                "catalog_product_base", null,
                ContentValues().apply {
                    put("product_id", row.productId)
                    put("base_price", row.basePrice)
                    put("base_tax_category", row.baseTaxCategory)
                    put("updated_at", row.baseUpdatedAt)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        } else {
            db.delete("catalog_product_base", "product_id = ?", arrayOf(row.productId))
        }

        if (row.metaExists) {
            db.insertWithOnConflict(
                "product_meta", null,
                ContentValues().apply {
                    put("product_id", row.productId)
                    putNullableLong("department_id", row.departmentId)
                    putNullableLong("group_id", row.groupId)
                    put("enabled", if (row.enabled) 1 else 0)
                    put("button_color", row.buttonColor)
                    put("page_no", row.pageNo)
                    put("slot_no", row.slotNo)
                    put("kana", row.kana)
                    put("barcode", row.barcode)
                    put("updated_at", row.metaUpdatedAt)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        } else {
            db.delete("product_meta", "product_id = ?", arrayOf(row.productId))
        }

        if (row.assignmentExists && !row.assignmentTaxKey.isNullOrBlank()) {
            db.insertWithOnConflict(
                "product_tax_assignments", null,
                ContentValues().apply {
                    put("product_id", row.productId)
                    put("tax_key", row.assignmentTaxKey)
                    put("updated_at", row.assignmentUpdatedAt)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        } else {
            db.delete("product_tax_assignments", "product_id = ?", arrayOf(row.productId))
        }
    }

    private fun ContentValues.putNullableLong(key: String, value: Long?) {
        if (value == null) putNull(key) else put(key, value)
    }
}

object MenuRevisionPreApplySnapshotV145 {
    fun capture(db: SQLiteDatabase): MenuMasterPreApplySnapshotV145 {
        val catalogRevision = db.rawQuery("SELECT revision FROM catalog_revision WHERE id = 1", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 1L
        }
        val rows = buildList {
            db.rawQuery(
                """
                SELECT p.id, p.name, p.unit_price, p.tax_category, p.display_order,
                       b.product_id, b.base_price, b.base_tax_category, b.updated_at,
                       m.product_id, m.department_id, m.group_id, m.enabled, m.button_color,
                       m.page_no, m.slot_no, m.kana, m.barcode, m.updated_at,
                       a.product_id, a.tax_key, a.updated_at
                FROM products p
                LEFT JOIN catalog_product_base b ON b.product_id = p.id
                LEFT JOIN product_meta m ON m.product_id = p.id
                LEFT JOIN product_tax_assignments a ON a.product_id = p.id
                ORDER BY p.id
                """.trimIndent(),
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val baseExists = !cursor.isNull(5)
                    val metaExists = !cursor.isNull(9)
                    val assignmentExists = !cursor.isNull(19)
                    add(
                        MenuMasterSnapshotRowV145(
                            productId = cursor.getString(0),
                            name = cursor.getString(1),
                            unitPrice = cursor.getLong(2),
                            taxCategory = cursor.getString(3),
                            displayOrder = cursor.getInt(4),
                            baseExists = baseExists,
                            basePrice = if (baseExists) cursor.getLong(6) else 0L,
                            baseTaxCategory = if (baseExists) cursor.getString(7) else "",
                            baseUpdatedAt = if (baseExists) cursor.getLong(8) else 0L,
                            metaExists = metaExists,
                            departmentId = if (metaExists && !cursor.isNull(10)) cursor.getLong(10) else null,
                            groupId = if (metaExists && !cursor.isNull(11)) cursor.getLong(11) else null,
                            enabled = metaExists && cursor.getInt(12) != 0,
                            buttonColor = if (metaExists) cursor.getString(13) else "BLUE",
                            pageNo = if (metaExists) cursor.getInt(14) else 1,
                            slotNo = if (metaExists) cursor.getInt(15) else 1,
                            kana = if (metaExists) cursor.getString(16) else "",
                            barcode = if (metaExists) cursor.getString(17) else "",
                            metaUpdatedAt = if (metaExists) cursor.getLong(18) else 0L,
                            assignmentExists = assignmentExists,
                            assignmentTaxKey = if (assignmentExists) cursor.getString(20) else null,
                            assignmentUpdatedAt = if (assignmentExists) cursor.getLong(21) else 0L,
                        ),
                    )
                }
            }
        }
        return MenuMasterPreApplySnapshotV145(catalogRevision, System.currentTimeMillis(), rows)
    }
}

data class MenuRevisionApplyOutcomeV145(
    val revisionId: Long,
    val applied: Boolean,
    val rollbackPerformed: Boolean,
    val itemCount: Int,
    val message: String,
)

object MenuRevisionApplyValidationV145 {
    fun validate(items: List<MenuRevisionProduct>, rules: Map<String, DynamicTaxRule>) {
        require(items.isNotEmpty()) { "改訂商品が空です" }
        require(items.size <= 10_000) { "改訂商品は10,000件以内です" }
        require(items.map { it.productId }.toSet().size == items.size) { "改訂内の商品コードが重複しています" }
        val enabledSlots = items.filter { it.enabled }.map { it.pageNo to it.slotNo }
        require(enabledSlots.toSet().size == enabledSlots.size) { "有効商品のボタン配置が重複しています" }
        items.forEach { item ->
            require(item.productId.isNotBlank()) { "商品コードが空です" }
            require(item.name.isNotBlank()) { "商品名が空です: ${item.productId}" }
            require(item.unitPrice in 0..99_999_999L) { "価格が範囲外です: ${item.productId}" }
            ButtonLayoutPolicy.validate(item.pageNo, item.slotNo)
            require(item.displayOrder == ButtonLayoutPolicy.displayOrder(item.pageNo, item.slotNo)) {
                "表示順が配置と一致しません: ${item.productId}"
            }
            val rule = rules[item.taxKey] ?: error("税区分が見つかりません: ${item.taxKey}")
            require(rule.label == item.taxLabel &&
                rule.ratePercent == item.taxRatePercent &&
                rule.taxIncluded == item.taxIncluded &&
                rule.taxable == item.taxable &&
                rule.reduced == item.reduced &&
                rule.symbol == item.taxSymbol
            ) { "予約時の税snapshotと現在の税区分が競合しています: ${item.productId}" }
        }
    }
}
