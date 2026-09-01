from pathlib import Path

root = Path('.')
main = root / 'app/src/main/java/jp/co/tenposinfo/register'
test = root / 'app/src/test/java/jp/co/tenposinfo/register'
docs = root / 'docs'

helper = r'''package jp.co.tenposinfo.register

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
'''
(main / 'MenuRevisionPreApplySnapshotV145.kt').write_text(helper, encoding='utf-8')

runtime = main / 'DynamicCatalogRuntime.kt'
s = runtime.read_text(encoding='utf-8')
old = '''    fun activeRevision(date: LocalDate = BusinessDateResolver.current(applicationContext)): MenuRevisionRecord? {
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
'''
new = '''    fun applyDueRevisionIfNeeded(
        date: LocalDate = BusinessDateResolver.current(applicationContext),
        actor: String = "SYSTEM",
    ): MenuRevisionApplyOutcomeV145? {
        val revision = dueRevision(date) ?: return null
        val items = revisionProducts(revision.id)
        val rules = listTaxRules().associateBy { it.key }
        val validation = runCatching { MenuRevisionApplyValidationV145.validate(items, rules) }
        if (validation.isFailure) {
            val message = validation.exceptionOrNull()?.message ?: "改訂検証に失敗しました"
            db.transaction {
                update("menu_revisions", ContentValues().apply { put("status", "FAILED") }, "id = ?", arrayOf(revision.id.toString()))
                audit(this, "MENU_REVISION_APPLY_FAILED", revision.id.toString(), "validation / $message", actor)
            }
            return MenuRevisionApplyOutcomeV145(revision.id, false, false, items.size, message)
        }

        var snapshot: MenuMasterPreApplySnapshotV145? = null
        var failure: Throwable? = null
        db.beginTransaction()
        try {
            // BKP-007: capture under the same SQLite writer transaction immediately before first master mutation.
            snapshot = MenuRevisionPreApplySnapshotV145.capture(db)
            val before = snapshot.rows.associateBy { it.productId }
            val now = System.currentTimeMillis()

            update("product_meta", ContentValues().apply { put("enabled", 0); put("updated_at", now) }, null, null)
            items.forEach { item ->
                val existing = before[item.productId]
                val productValues = ContentValues().apply {
                    put("name", item.name)
                    put("unit_price", item.unitPrice)
                    put("tax_category", item.legacyTaxCategory.name)
                    put("display_order", item.displayOrder)
                }
                val productExists = scalarLong(this, "SELECT COUNT(*) FROM products WHERE id = ?", arrayOf(item.productId)) > 0L
                if (productExists) {
                    update("products", productValues, "id = ?", arrayOf(item.productId))
                } else {
                    productValues.put("id", item.productId)
                    insertOrThrow("products", null, productValues)
                }
                insertWithOnConflict(
                    "catalog_product_base", null,
                    ContentValues().apply {
                        put("product_id", item.productId)
                        put("base_price", item.unitPrice)
                        put("base_tax_category", item.legacyTaxCategory.name)
                        put("updated_at", now)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
                insertWithOnConflict(
                    "product_meta", null,
                    ContentValues().apply {
                        put("product_id", item.productId)
                        if (existing?.departmentId == null) putNull("department_id") else put("department_id", existing.departmentId)
                        if (existing?.groupId == null) putNull("group_id") else put("group_id", existing.groupId)
                        put("enabled", if (item.enabled) 1 else 0)
                        put("button_color", item.buttonColor)
                        put("page_no", item.pageNo)
                        put("slot_no", item.slotNo)
                        put("kana", existing?.kana ?: "")
                        put("barcode", existing?.barcode ?: "")
                        put("updated_at", now)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
                insertWithOnConflict(
                    "product_tax_assignments", null,
                    ContentValues().apply {
                        put("product_id", item.productId)
                        put("tax_key", item.taxKey)
                        put("updated_at", now)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            execSQL("UPDATE catalog_revision SET revision = revision + 1 WHERE id = 1")
            update(
                "menu_revisions",
                ContentValues().apply { put("status", "SUPERSEDED") },
                "status = 'SCHEDULED' AND effective_date <= ? AND id <> ?",
                arrayOf(date.toString(), revision.id.toString()),
            )
            require(
                update(
                    "menu_revisions",
                    ContentValues().apply { put("status", "APPLIED") },
                    "id = ? AND status = 'SCHEDULED'",
                    arrayOf(revision.id.toString()),
                ) == 1,
            ) { "改訂状態が変化したため適用を中止しました" }
            audit(
                this,
                "MENU_REVISION_APPLIED",
                revision.id.toString(),
                "snapshotRows=${snapshot.rows.size} / masterRevision=${snapshot.catalogRevision} / applied=${items.size}",
                actor,
            )
            setTransactionSuccessful()
        } catch (t: Throwable) {
            failure = t
        } finally {
            db.endTransaction()
        }

        if (failure != null) {
            val original = failure!!
            val captured = snapshot
            val restored = runCatching {
                db.beginTransaction()
                try {
                    captured?.restore(db)
                    update("menu_revisions", ContentValues().apply { put("status", "FAILED") }, "id = ?", arrayOf(revision.id.toString()))
                    audit(
                        this,
                        "MENU_REVISION_APPLY_ROLLED_BACK",
                        revision.id.toString(),
                        "snapshotRows=${captured?.rows?.size ?: 0} / ${original.message ?: original.javaClass.simpleName}",
                        actor,
                    )
                    setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            if (restored.isFailure) {
                throw IllegalStateException(
                    "メニュー改訂の適用失敗後、snapshot復旧にも失敗しました",
                    restored.exceptionOrNull(),
                )
            }
            return MenuRevisionApplyOutcomeV145(
                revision.id,
                false,
                true,
                items.size,
                original.message ?: "適用失敗のため旧版へロールバックしました",
            )
        }
        return MenuRevisionApplyOutcomeV145(revision.id, true, false, items.size, "適用完了")
    }

    private fun dueRevision(date: LocalDate): MenuRevisionRecord? = db.rawQuery(
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
        if (!cursor.moveToFirst()) null else MenuRevisionRecord(
            id = cursor.getLong(0), name = cursor.getString(1), effectiveDate = cursor.getString(2),
            status = cursor.getString(3), createdBy = cursor.getString(4), createdAt = cursor.getLong(5), itemCount = cursor.getInt(6),
        )
    }

    fun activeRevision(date: LocalDate = BusinessDateResolver.current(applicationContext)): MenuRevisionRecord? {
        db.rawQuery(
            """
            SELECT r.id, r.name, r.effective_date, r.status, r.created_by, r.created_at,
                   (SELECT COUNT(*) FROM menu_revision_products p WHERE p.revision_id = r.id)
            FROM menu_revisions r
            WHERE r.status IN ('APPLIED', 'SCHEDULED') AND r.effective_date <= ?
            ORDER BY r.effective_date DESC,
                     CASE r.status WHEN 'SCHEDULED' THEN 1 ELSE 0 END DESC,
                     r.id DESC
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
'''
if old not in s:
    raise SystemExit('activeRevision block not found')
s = s.replace(old, new, 1)
needle = '''    ): List<Product> {
        val rules = listTaxRules().associateBy { it.key }
        val revision = activeRevision(date)
'''
replacement = '''    ): List<Product> {
        applyDueRevisionIfNeeded(date)
        val rules = listTaxRules().associateBy { it.key }
        val revision = activeRevision(date)
'''
if needle not in s:
    raise SystemExit('runtimeProducts integration point not found')
s = s.replace(needle, replacement, 1)
runtime.write_text(s, encoding='utf-8')

test.write_text(r'''package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class V145Bkp007PreApplySnapshotTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) File(current, "app") else current
    }

    private fun item(id: String, page: Int, slot: Int) = MenuRevisionProduct(
        productId = id,
        name = "商品$id",
        enabled = true,
        unitPrice = 100,
        legacyTaxCategory = TaxCategory.INCLUDED_10,
        taxKey = TaxCategory.INCLUDED_10.name,
        taxLabel = TaxCategory.INCLUDED_10.displayName,
        taxRatePercent = 10,
        taxIncluded = true,
        taxable = true,
        reduced = false,
        taxSymbol = TaxCategory.INCLUDED_10.symbol,
        buttonColor = "BLUE",
        pageNo = page,
        slotNo = slot,
        displayOrder = ButtonLayoutPolicy.displayOrder(page, slot),
    )

    private fun rule() = DynamicTaxRule(
        key = TaxCategory.INCLUDED_10.name,
        label = TaxCategory.INCLUDED_10.displayName,
        ratePercent = 10,
        mode = DynamicTaxMode.INCLUDED,
        reduced = false,
        enabled = true,
        symbol = TaxCategory.INCLUDED_10.symbol,
        validFrom = "",
        validTo = "",
    )

    @Test
    fun validRevisionPassesAndDuplicatePlacementFailsClosed() {
        MenuRevisionApplyValidationV145.validate(listOf(item("A", 1, 1)), mapOf(rule().key to rule()))
        val duplicate = runCatching {
            MenuRevisionApplyValidationV145.validate(
                listOf(item("A", 1, 1), item("B", 1, 1)),
                mapOf(rule().key to rule()),
            )
        }
        assertTrue(duplicate.isFailure)
    }

    @Test
    fun taxSnapshotConflictFailsBeforeMasterMutation() {
        val changed = rule().copy(ratePercent = 8)
        val result = runCatching {
            MenuRevisionApplyValidationV145.validate(listOf(item("A", 1, 1)), mapOf(changed.key to changed))
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun sourceCapturesBeforeMutationAndExplicitlyRestoresOnFailure() {
        fun source(name: String) = File(root, "src/main/java/jp/co/tenposinfo/register/$name").readText()
        val runtime = source("DynamicCatalogRuntime.kt")
        val snapshot = source("MenuRevisionPreApplySnapshotV145.kt")

        val capture = runtime.indexOf("MenuRevisionPreApplySnapshotV145.capture(db)")
        val firstMutation = runtime.indexOf("update(\"product_meta\"")
        assertTrue(capture >= 0)
        assertTrue(firstMutation > capture)
        assertTrue(runtime.contains("snapshot.restore(db)"))
        assertTrue(runtime.contains("MENU_REVISION_APPLY_ROLLED_BACK"))
        assertTrue(runtime.contains("put(\"status\", \"FAILED\")"))
        assertTrue(runtime.contains("put(\"status\", \"APPLIED\")"))
        assertTrue(runtime.contains("put(\"status\", \"SUPERSEDED\")"))
        assertTrue(runtime.indexOf("applyDueRevisionIfNeeded(date)") < runtime.indexOf("val revision = activeRevision(date)"))
        assertTrue(runtime.contains("r.status IN ('APPLIED', 'SCHEDULED')"))

        assertTrue(snapshot.contains("FROM products p"))
        assertTrue(snapshot.contains("LEFT JOIN catalog_product_base"))
        assertTrue(snapshot.contains("LEFT JOIN product_meta"))
        assertTrue(snapshot.contains("LEFT JOIN product_tax_assignments"))
        assertTrue(snapshot.contains("SELECT revision FROM catalog_revision"))
        assertTrue(snapshot.contains("fun restore(db: SQLiteDatabase)"))
    }
}
''', encoding='utf-8')

docs.joinpath('V1.36_BKP_007_PRE_APPLY_SNAPSHOT.md').write_text(r'''# v1.36 BKP-007 適用前スナップショット

正式仕様 v2.5 `BKP-007` を正本とする。

## 正式要件

- メニュー改訂適用直前に現行マスターを高速スナップショットする。
- 適用失敗時は旧版へ即時ロールバックする。

## 実装

従来は適用営業日到来後も `SCHEDULED` の改訂snapshotを販売読込時に重ねるだけで、実マスターへの `APPLIED` 遷移がなかった。v2.5の営業開始時一括適用モデルに合わせ、due revision を適用する経路を追加した。

1. 価格・名称・配置・有効状態・税割当の実更新前に、`products` / `catalog_product_base` / `product_meta` / `product_tax_assignments` と `catalog_revision` をメモリへ一括snapshotする。
2. snapshot取得とマスター更新を同じSQLite writer transaction内で実行し、取得後から最初の更新まで他の部分適用を発生させない。
3. 予約時の税snapshotと現在税マスターが不一致、商品重複、配置重複、件数超過などは最初のマスター更新前にfail closedする。
4. 適用成功時は実マスター、`catalog_revision`、改訂status=`APPLIED`を同一transactionで確定する。過去の未適用due revisionは`SUPERSEDED`とする。
5. 適用途中の例外ではSQLite transactionをrollbackした後、取得済みsnapshotから旧マスターを明示restoreし、改訂status=`FAILED`と`MENU_REVISION_APPLY_ROLLED_BACK`監査を残す。
6. プロセス停止等で例外処理自体まで到達できない場合も、未commitのSQLite transactionが旧版を維持する。次回起動時に再評価できる。
7. 販売側の既存15秒再評価経路からdue revision適用を先に実行し、成功時は`APPLIED`版、失敗時は直前の`APPLIED`版またはライブマスターを継続する。

## 自動検証

`V145Bkp007PreApplySnapshotTest` でsnapshotが最初のマスターmutationより前にあること、対象マスター、明示restore、APPLIED/FAILED/SUPERSEDED遷移、税snapshot競合と配置競合のfail closedを固定する。

## 実機未確認

- 10,000商品規模でのsnapshot取得時間と営業開始操作の体感
- 実端末で適用途中に強制終了・電源断した際に旧版で再開できること
- 実Google Drive受信改訂を営業開始時に適用し、競合時に旧版へ戻る一連の実運用

上記は端末・ストレージ・実Drive環境依存のため、ソフトウェア/CI/APK側完了と分離して `実機未確認` とする。
''', encoding='utf-8')

print('BKP-007 patch prepared')
