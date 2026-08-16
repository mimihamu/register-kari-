from pathlib import Path

ROOT = Path('.')


def replace_once(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding='utf-8')
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{path}: expected exactly one match, found {count}: {old[:120]!r}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')


def write(path: str, content: str) -> None:
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content, encoding='utf-8')

# Domain: preserve a stable work-cart line identity without breaking existing positional constructors.
replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/Domain.kt',
    '''    val discountAmount: Long = 0,\n    val note: String = "",\n) {''',
    '''    val discountAmount: Long = 0,\n    val note: String = "",\n    val lineId: String = "",\n) {''',
)

# RegisterDatabase: schema hook, persisted line id, atomic correction ledger, and sale-boundary cleanup.
replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt',
    '''    override fun onConfigure(db: SQLiteDatabase) {\n        super.onConfigure(db)\n        db.setForeignKeyConstraintsEnabled(true)\n    }\n''',
    '''    override fun onConfigure(db: SQLiteDatabase) {\n        super.onConfigure(db)\n        db.setForeignKeyConstraintsEnabled(true)\n    }\n\n    override fun onOpen(db: SQLiteDatabase) {\n        super.onOpen(db)\n        CartCorrectionSchemaV135.ensure(db)\n    }\n''',
)

replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt',
    '''    fun saveCart(items: List<CartItem>) {\n        writableDatabase.runInTransaction {\n            delete("cart_items", null, null)\n            items.forEachIndexed { index, item ->\n                insertOrThrow("cart_items", null, item.toContentValues().apply { put("line_no", index + 1) })\n            }\n            LineTaxSnapshotStore.save(this, LineTaxSnapshotStore.SCOPE_CART, 0L, items)\n        }\n    }\n''',
    '''    fun saveCart(items: List<CartItem>) {\n        writableDatabase.runInTransaction {\n            delete("cart_items", null, null)\n            items.forEachIndexed { index, item ->\n                insertOrThrow("cart_items", null, item.toContentValues().apply { put("line_no", index + 1) })\n            }\n            LineTaxSnapshotStore.save(this, LineTaxSnapshotStore.SCOPE_CART, 0L, items)\n        }\n    }\n\n    fun loadCartCorrections(): List<CartCorrectionRecordV135> =\n        CartCorrectionSchemaV135.load(readableDatabase)\n\n    fun clearCartCorrections() {\n        writableDatabase.runInTransaction {\n            CartCorrectionSchemaV135.clear(this)\n        }\n    }\n\n    /**\n     * COR-002/COR-003: active cart rewrite and cancellation history append are committed atomically.\n     * Cancellation history is not part of [CartItem], so tax/payment/sale totals only see active rows.\n     */\n    fun applyCartCorrection(\n        targetIndex: Int,\n        cancelQuantity: Int,\n        correctionType: CartCorrectionTypeV135,\n        operatorName: String,\n    ): CartCorrectionResultV135 {\n        require(operatorName.isNotBlank()) { "担当者が必要です" }\n        return writableDatabase.runInTransactionWithResult {\n            val rawItems = query(\n                "cart_items",\n                CART_COLUMNS,\n                null,\n                null,\n                null,\n                null,\n                "line_no ASC",\n            ).use { cursor ->\n                buildList {\n                    while (cursor.moveToNext()) add(cursor.toCartItem())\n                }\n            }\n            val currentItems = LineTaxSnapshotStore.apply(\n                this,\n                LineTaxSnapshotStore.SCOPE_CART,\n                0L,\n                rawItems,\n            )\n            val result = CartCorrectionPolicyV135.apply(\n                items = currentItems,\n                targetIndex = targetIndex,\n                cancelQuantity = cancelQuantity,\n                correctionType = correctionType,\n                operatorName = operatorName,\n                createdAt = System.currentTimeMillis(),\n            )\n\n            delete("cart_items", null, null)\n            result.items.forEachIndexed { index, item ->\n                insertOrThrow(\n                    "cart_items",\n                    null,\n                    item.toContentValues().apply { put("line_no", index + 1) },\n                )\n            }\n            LineTaxSnapshotStore.save(this, LineTaxSnapshotStore.SCOPE_CART, 0L, result.items)\n            val historyId = CartCorrectionSchemaV135.insert(this, result.record)\n            result.copy(record = result.record.copy(id = historyId))\n        }\n    }\n''',
)

replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt',
    '''                    delete(\n                        "line_tax_snapshots",\n                        "scope = ? AND owner_id = ?",\n                        arrayOf(LineTaxSnapshotStore.SCOPE_CART, "0"),\n                    )\n                    return@runInTransactionWithResult existing.saleId''',
    '''                    delete(\n                        "line_tax_snapshots",\n                        "scope = ? AND owner_id = ?",\n                        arrayOf(LineTaxSnapshotStore.SCOPE_CART, "0"),\n                    )\n                    CartCorrectionSchemaV135.clear(this)\n                    return@runInTransactionWithResult existing.saleId''',
)

replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt',
    '''            delete(\n                "line_tax_snapshots",\n                "scope = ? AND owner_id = ?",\n                arrayOf(LineTaxSnapshotStore.SCOPE_CART, "0"),\n            )\n            saleId\n''',
    '''            delete(\n                "line_tax_snapshots",\n                "scope = ? AND owner_id = ?",\n                arrayOf(LineTaxSnapshotStore.SCOPE_CART, "0"),\n            )\n            CartCorrectionSchemaV135.clear(this)\n            saleId\n''',
)

replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt',
    '''        return CartItem(\n            product = product,\n            quantity = getInt(5),\n            unitPrice = getLong(2),\n            discountAmount = getLong(6),\n            note = getString(7),\n        )''',
    '''        return CartItem(\n            product = product,\n            quantity = getInt(5),\n            unitPrice = getLong(2),\n            discountAmount = getLong(6),\n            note = getString(7),\n            lineId = getString(8),\n        )''',
)

replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt',
    '''        put("discount_amount", discountAmount)\n        put("note", note)\n    }''',
    '''        put("discount_amount", discountAmount)\n        put("note", note)\n        put("line_id", lineId)\n    }''',
)

replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt',
    '''                quantity INTEGER NOT NULL,\n                discount_amount INTEGER NOT NULL DEFAULT 0,\n                note TEXT NOT NULL DEFAULT ''\n            )''',
    '''                quantity INTEGER NOT NULL,\n                discount_amount INTEGER NOT NULL DEFAULT 0,\n                note TEXT NOT NULL DEFAULT '',\n                line_id TEXT NOT NULL DEFAULT ''\n            )''',
)

# The same column shape appears in held_ticket_items; patch its first remaining occurrence.
replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt',
    '''                quantity INTEGER NOT NULL,\n                discount_amount INTEGER NOT NULL DEFAULT 0,\n                note TEXT NOT NULL DEFAULT '',\n                FOREIGN KEY(ticket_id) REFERENCES held_tickets(id) ON DELETE CASCADE''',
    '''                quantity INTEGER NOT NULL,\n                discount_amount INTEGER NOT NULL DEFAULT 0,\n                note TEXT NOT NULL DEFAULT '',\n                line_id TEXT NOT NULL DEFAULT '',\n                FOREIGN KEY(ticket_id) REFERENCES held_tickets(id) ON DELETE CASCADE''',
)

replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt',
    '''            "quantity",\n            "discount_amount",\n            "note",\n        )''',
    '''            "quantity",\n            "discount_amount",\n            "note",\n            "line_id",\n        )''',
)

# MainActivity: keep live rows and correction history separate; route all quantity decreases through the ledger.
replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt',
    '''    val cart = remember { mutableStateListOf<CartItem>().apply { addAll(database.loadCart()) } }\n    var selectedIndex by remember { mutableStateOf<Int?>(null) }''',
    '''    val cart = remember { mutableStateListOf<CartItem>().apply { addAll(database.loadCart()) } }\n    val corrections = remember {\n        mutableStateListOf<CartCorrectionRecordV135>().apply { addAll(database.loadCartCorrections()) }\n    }\n    var selectedIndex by remember { mutableStateOf<Int?>(null) }''',
)

replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt',
    '''    fun updateCartItem(index: Int, item: CartItem) {\n        if (index !in cart.indices) return\n        cart[index] = item\n        selectedIndex = index\n        database.saveCart(cart.toList())\n    }\n\n    fun openUnifiedPrintQueue()''',
    '''    fun updateCartItem(index: Int, item: CartItem) {\n        if (index !in cart.indices) return\n        cart[index] = item\n        selectedIndex = index\n        database.saveCart(cart.toList())\n    }\n\n    fun applyCartCorrection(index: Int, quantity: Int, type: CartCorrectionTypeV135) {\n        if (index !in cart.indices) return\n        runCatching {\n            database.applyCartCorrection(\n                targetIndex = index,\n                cancelQuantity = quantity,\n                correctionType = type,\n                operatorName = operatorName,\n            )\n        }.onSuccess { result ->\n            cart.clear()\n            cart.addAll(result.items)\n            corrections.clear()\n            corrections.addAll(database.loadCartCorrections())\n            selectedIndex = null\n            paymentDraftStore.clear()\n            paymentCommitKey = null\n        }.onFailure { error ->\n            accessMessage = error.message ?: "訂正できませんでした"\n        }\n    }\n\n    fun openUnifiedPrintQueue()''',
)

replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt',
    '''                products = products,\n                cart = cart,\n                selectedIndex = selectedIndex,''',
    '''                products = products,\n                cart = cart,\n                corrections = corrections,\n                selectedIndex = selectedIndex,''',
)

replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt',
    '''                onAddProduct = { product ->\n                    val index = cart.indexOfFirst {\n                        it.product.id == product.id &&\n                            it.unitPrice == product.unitPrice &&\n                            it.discountAmount == 0L &&\n                            it.note.isEmpty()\n                    }\n                    if (index >= 0) {\n                        cart[index] = cart[index].copy(quantity = cart[index].quantity + 1)\n                    } else {\n                        cart += CartItem(product = product, quantity = 1)\n                    }\n                    database.saveCart(cart.toList())\n                },\n                onChangeQuantity = { quantity ->\n                    val index = selectedIndex\n                    if (index != null && index in cart.indices && quantity > 0) {\n                        updateCartItem(index, cart[index].copy(quantity = quantity))\n                    }\n                },\n                onRemove = {\n                    val index = selectedIndex ?: cart.lastIndex\n                    if (index in cart.indices) {\n                        cart.removeAt(index)\n                        selectedIndex = null\n                        database.saveCart(cart.toList())\n                    }\n                },\n                onCancelTransaction = { replaceCart(emptyList()) },''',
    '''                onAddProduct = { product ->\n                    val index = cart.indexOfFirst {\n                        it.product.id == product.id &&\n                            it.unitPrice == product.unitPrice &&\n                            it.discountAmount == 0L &&\n                            it.note.isEmpty()\n                    }\n                    if (index >= 0) {\n                        val updated = cart[index].copy(quantity = cart[index].quantity + 1)\n                        cart.removeAt(index)\n                        cart += updated\n                        selectedIndex = null\n                    } else {\n                        cart += CartItem(\n                            product = product,\n                            quantity = 1,\n                            lineId = CartLineIdentityV135.newId(),\n                        )\n                    }\n                    database.saveCart(cart.toList())\n                },\n                onChangeQuantity = { quantity ->\n                    val index = selectedIndex\n                    if (index != null && index in cart.indices && quantity > 0) {\n                        val current = cart[index]\n                        if (quantity < current.quantity) {\n                            applyCartCorrection(\n                                index,\n                                current.quantity - quantity,\n                                CartCorrectionTypeV135.SELECTED_LINE,\n                            )\n                        } else {\n                            updateCartItem(index, current.copy(quantity = quantity))\n                        }\n                    }\n                },\n                onRemove = {\n                    val index = cart.lastIndex\n                    if (index in cart.indices) {\n                        applyCartCorrection(\n                            index,\n                            cart[index].quantity,\n                            CartCorrectionTypeV135.LAST_LINE,\n                        )\n                    }\n                },\n                onCancelSelected = { quantity ->\n                    val index = selectedIndex\n                    if (index != null && index in cart.indices) {\n                        applyCartCorrection(index, quantity, CartCorrectionTypeV135.SELECTED_LINE)\n                    }\n                },\n                onCancelTransaction = {\n                    database.clearCartCorrections()\n                    corrections.clear()\n                    replaceCart(emptyList())\n                },''',
)

replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt',
    '''                        database.holdCart(name, operatorName, cart.toList())\n                        replaceCart(emptyList())''',
    '''                        database.holdCart(name, operatorName, cart.toList())\n                        database.clearCartCorrections()\n                        corrections.clear()\n                        replaceCart(emptyList())''',
)

replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt',
    '''                    }.onSuccess { result ->\n                        replaceCart(result.loadedItems)\n                        ticketMessage = result.message''',
    '''                    }.onSuccess { result ->\n                        database.clearCartCorrections()\n                        corrections.clear()\n                        replaceCart(result.loadedItems)\n                        ticketMessage = result.message''',
)

replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt',
    '''                        lastSaleId = saleId\n                        selectedSaleId = saleId\n                        replaceCart(emptyList())''',
    '''                        lastSaleId = saleId\n                        selectedSaleId = saleId\n                        corrections.clear()\n                        replaceCart(emptyList())''',
)

replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt',
    '''    products: List<Product>,\n    cart: List<CartItem>,\n    selectedIndex: Int?,''',
    '''    products: List<Product>,\n    cart: List<CartItem>,\n    corrections: List<CartCorrectionRecordV135>,\n    selectedIndex: Int?,''',
)

replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt',
    '''    onChangeQuantity: (Int) -> Unit,\n    onRemove: () -> Unit,\n    onCancelTransaction: () -> Unit,''',
    '''    onChangeQuantity: (Int) -> Unit,\n    onRemove: () -> Unit,\n    onCancelSelected: (Int) -> Unit,\n    onCancelTransaction: () -> Unit,''',
)

replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt',
    '''                            Text(yen(item.baseAmount), fontWeight = FontWeight.Bold)\n                        }\n                    }\n                }\n                Row(verticalAlignment = Alignment.Bottom) {''',
    '''                            Text(yen(item.baseAmount), fontWeight = FontWeight.Bold)\n                        }\n                    }\n                    if (corrections.isNotEmpty()) {\n                        item {\n                            Text(\n                                "訂正履歴",\n                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),\n                                color = Danger,\n                                fontSize = 13.sp,\n                                fontWeight = FontWeight.Bold,\n                            )\n                        }\n                        itemsIndexed(corrections) { _, correction ->\n                            Row(\n                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),\n                                verticalAlignment = Alignment.CenterVertically,\n                            ) {\n                                Column(Modifier.weight(1f)) {\n                                    Text("取消 ${correction.productName}", color = Danger, fontWeight = FontWeight.SemiBold)\n                                    Text(\n                                        "${correction.cancelledQuantity} × ${yen(correction.unitPrice)} / 元行 ${correction.lineId.takeLast(8)}",\n                                        fontSize = 12.sp,\n                                        color = Color.Gray,\n                                    )\n                                }\n                                Text("-${yen(correction.cancelledAmount)}", color = Danger, fontWeight = FontWeight.Bold)\n                            }\n                        }\n                    }\n                }\n                Row(verticalAlignment = Alignment.Bottom) {''',
)

replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt',
    '''                                OutlinedButton(\n                                    onClick = { selectedIndex?.let(onEdit) },\n                                    enabled = selectedIndex != null,\n                                    modifier = Modifier.weight(1f).height(keypad.functionHeightDp.dp),\n                                ) { Text("行編集", fontSize = 13.sp, maxLines = 1) }''',
    '''                                OutlinedButton(\n                                    onClick = {\n                                        val selected = selectedIndex?.let { cart.getOrNull(it) }\n                                        if (selected != null) {\n                                            val quantity = numericInput.toIntOrNull() ?: selected.quantity\n                                            onCancelSelected(quantity)\n                                            numericInput = ""\n                                        }\n                                    },\n                                    enabled = selectedIndex != null,\n                                    modifier = Modifier.weight(1f).height(keypad.functionHeightDp.dp),\n                                ) { Text("行取消", fontSize = 13.sp, maxLines = 1) }\n                                OutlinedButton(\n                                    onClick = { selectedIndex?.let(onEdit) },\n                                    enabled = selectedIndex != null,\n                                    modifier = Modifier.weight(1f).height(keypad.functionHeightDp.dp),\n                                ) { Text("行編集", fontSize = 13.sp, maxLines = 1) }''',
)

# New implementation file.
write(
    'app/src/main/java/jp/co/tenposinfo/register/CartCorrectionV135.kt',
    r'''package jp.co.tenposinfo.register

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
''',
)

write(
    'app/src/test/java/jp/co/tenposinfo/register/V135CartCorrectionTest.kt',
    r'''package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V135CartCorrectionTest {
    private val product = Product(
        id = "P-COR",
        name = "訂正テスト",
        unitPrice = 100L,
        taxCategory = TaxCategory.INCLUDED_10,
        displayOrder = 1,
    )

    @Test
    fun lastLineCorrectionRemovesActiveRowAndLeavesLinkedHistory() {
        val item = CartItem(product = product, quantity = 2, lineId = "line-last")
        val result = CartCorrectionPolicyV135.apply(
            items = listOf(item),
            targetIndex = 0,
            cancelQuantity = 2,
            correctionType = CartCorrectionTypeV135.LAST_LINE,
            operatorName = "担当A",
            createdAt = 100L,
        )

        assertTrue(result.items.isEmpty())
        assertEquals("line-last", result.record.lineId)
        assertEquals(2, result.record.cancelledQuantity)
        assertEquals(2, result.record.quantityBefore)
        assertEquals(0, result.record.quantityAfter)
        assertEquals(200L, result.record.cancelledAmount)
    }

    @Test
    fun selectedLineSupportsPartialCancellationAndAllocatesDiscount() {
        val item = CartItem(
            product = product,
            quantity = 3,
            discountAmount = 30L,
            lineId = "line-partial",
        )
        val result = CartCorrectionPolicyV135.apply(
            items = listOf(item),
            targetIndex = 0,
            cancelQuantity = 1,
            correctionType = CartCorrectionTypeV135.SELECTED_LINE,
            operatorName = "担当B",
            createdAt = 200L,
        )

        assertEquals(1, result.items.size)
        assertEquals(2, result.items.single().quantity)
        assertEquals(20L, result.items.single().discountAmount)
        assertEquals("line-partial", result.items.single().lineId)
        assertEquals(10L, result.record.cancelledDiscountAmount)
        assertEquals(90L, result.record.cancelledAmount)
        assertEquals(1, result.record.cancelledQuantity)
        assertEquals(2, result.record.quantityAfter)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cancellationCannotExceedCurrentQuantity() {
        CartCorrectionPolicyV135.apply(
            items = listOf(CartItem(product = product, quantity = 2, lineId = "line-limit")),
            targetIndex = 0,
            cancelQuantity = 3,
            correctionType = CartCorrectionTypeV135.SELECTED_LINE,
            operatorName = "担当C",
            createdAt = 300L,
        )
    }

    @Test
    fun salesScreenKeepsCor001PriorityAndProvidesSeparateSelectedLineCancellation() {
        val source = File("src/main/java/jp/co/tenposinfo/register/MainActivity.kt").readText()
        val salesScreen = source
            .substringAfter("private fun SalesScreen(")
            .substringBefore("@Composable\nprivate fun LineEditScreen(")

        assertTrue(salesScreen.contains("if (NumericCorrectionPolicyV135.shouldClearInput(numericInput))"))
        assertTrue(salesScreen.contains("onRemove()"))
        assertTrue(salesScreen.contains("onCancelSelected(quantity)"))
        assertTrue(salesScreen.contains("Text(\"行取消\""))
        assertTrue(salesScreen.contains("訂正履歴"))
    }

    @Test
    fun databaseCorrectionIsAtomicAndSaleBoundaryClearsWorkHistory() {
        val source = File("src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt").readText()
        val correctionMethod = source
            .substringAfter("fun applyCartCorrection(")
            .substringBefore("fun holdCart(")
        val saveSale = source
            .substringAfter("fun saveSale(")
            .substringBefore("fun listSales(")

        assertTrue(correctionMethod.contains("runInTransactionWithResult"))
        assertTrue(correctionMethod.contains("CartCorrectionSchemaV135.insert"))
        assertTrue(correctionMethod.contains("LineTaxSnapshotStore.save"))
        assertTrue(saveSale.contains("CartCorrectionSchemaV135.clear(this)"))
    }
}
''',
)

write(
    'docs/V1.35_COR_002_003_CART_CORRECTION.md',
    r'''# v1.35 COR-002 / COR-003 作業中訂正

## 対象

- COR-002 直前訂正: 直前登録行を取消し、取消行を作業中履歴へ残し、表示合計を即時更新する。
- COR-003 任意行訂正: 選択行の全量/一部取消を行い、元行識別子と取消数量を関連付ける。

## 実装

- `CartItem.lineId` を追加し、作業中明細に安定した行識別子を持たせる。
- 既存DBは `CartCorrectionSchemaV135.ensure` が `cart_items` / `held_ticket_items` に `line_id` を非破壊追加し、既存行へ識別子を補完する。
- `cart_correction_history` を作業中訂正専用の追記履歴とする。
- 有効明細は従来どおり `cart_items` のみに保持するため、取消履歴は税計算・小計・会計確定・売上明細へ混入しない。
- `RegisterDatabase.applyCartCorrection` は、有効カート更新・税スナップショット更新・取消履歴追加を単一SQLiteトランザクションで確定する。
- 全量取消では有効行を削除し履歴を残す。一部取消では有効数量を減らし、値引額も取消数量へ比例配賦して履歴へ残す。
- 取消数量が現在数量を超える操作は拒否する。
- 販売画面の「訂正」はCOR-001を維持し、置数があれば置数消去を優先する。置数なしでは直前行を取消する。
- 任意行は明細選択後の「行取消」で処理する。置数がある場合はその数量、置数なしは選択行全量を取消する。
- 数量キーで既存数量を減らした場合も任意行の一部取消として履歴化する。
- 作業中の取消履歴は販売画面の「訂正履歴」に表示し、合計・点数は有効明細だけで再計算する。
- 会計確定時は作業中訂正履歴を売上へ混入させずクリアする。取引中止でも作業中履歴をクリアする。

## 自動試験

`V135CartCorrectionTest` で以下を固定する。

1. 直前行の全量取消と元行リンク。
2. 指定行の一部取消。
3. 一部取消時の値引比例配賦。
4. 現在数量超過の拒否。
5. COR-001の置数優先を壊さず、任意行取消を別操作として提供すること。
6. DB訂正が単一トランザクションで履歴と有効カートを更新すること。
7. 会計確定境界で作業中履歴をクリアすること。

## 実機確認として残す項目

- 商品登録直後の「訂正」で直前行が取消表示され、合計が即時減額されること。
- 同一商品連打後の直前行挙動と表示順。
- 任意行の全量取消。
- 任意行の一部取消（置数あり）。
- 取消可能数量を超えた場合のエラー表示。
- 値引済み複数量行の一部取消と合計。
- 取消履歴表示、スクロール、選択状態。
- アプリ再起動後の作業中カートと取消履歴の復元。
- 取引中止後に旧取消履歴が次取引へ残らないこと。
- 会計確定後に取消履歴が次取引へ残らず、レシート・X/Zへ有効明細だけが一重計上されること。
- 保留/呼出と訂正履歴の運用については、現行初版の作業中履歴スコープを実機で確認し、必要なら伝票単位スコープへ拡張する。
''',
)

# Remove the temporary patch machinery from the resulting product commit.
(ROOT / '.github/workflows/patch-cor002-003-v135.yml').unlink(missing_ok=True)
(ROOT / '.github/scripts/patch_cor002_003_v135.py').unlink(missing_ok=True)
