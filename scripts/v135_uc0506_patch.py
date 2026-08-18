from pathlib import Path


def read(path):
    return Path(path).read_text(encoding="utf-8")


def write(path, text):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(text, encoding="utf-8")


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, got {count}")
    return text.replace(old, new, 1)


# Product domain: append search metadata so existing positional call sites remain compatible.
path = "app/src/main/java/jp/co/tenposinfo/register/Domain.kt"
text = read(path)
text = replace_once(
    text,
    '    val slotNo: Int = ((displayOrder.coerceAtLeast(1) - 1) % 24) + 1,\n) {',
    '    val slotNo: Int = ((displayOrder.coerceAtLeast(1) - 1) % 24) + 1,\n    val kana: String = "",\n    val barcode: String = "",\n) {',
    "Domain.Product search metadata",
)
write(path, text)

# Catalog model + validation.
path = "app/src/main/java/jp/co/tenposinfo/register/CatalogMasterModels.kt"
text = read(path)
text = replace_once(
    text,
    '    val displayOrder: Int,\n)\n\ndata class TaxMasterRecord(',
    '    val displayOrder: Int,\n    val kana: String = "",\n    val barcode: String = "",\n)\n\ndata class TaxMasterRecord(',
    "ProductMasterRecord metadata",
)
text = replace_once(
    text,
    '''    fun requireName(value: String, label: String): String {\n        val name = value.trim()\n        require(name.isNotBlank()) { "${label}を入力してください" }\n        require(name.length <= 60) { "${label}は60文字以内です" }\n        return name\n    }\n\n    fun parseTime(value: String): Int {''',
    '''    fun requireName(value: String, label: String): String {\n        val name = value.trim()\n        require(name.isNotBlank()) { "${label}を入力してください" }\n        require(name.length <= 60) { "${label}は60文字以内です" }\n        return name\n    }\n\n    fun normalizeKana(value: String): String {\n        val kana = value.trim()\n        require(kana.length <= 60) { "かなは60文字以内です" }\n        return kana\n    }\n\n    fun normalizeBarcode(value: String): String {\n        val barcode = value.trim()\n        require(barcode.length <= 64) { "バーコードは64文字以内です" }\n        require(barcode.none { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }) {\n            "バーコードに空白・制御文字は使用できません"\n        }\n        return barcode\n    }\n\n    fun parseTime(value: String): Int {''',
    "CatalogValidation search metadata",
)
write(path, text)

# Catalog persistence + idempotent migration.
path = "app/src/main/java/jp/co/tenposinfo/register/CatalogMasterStore.kt"
text = read(path)
text = replace_once(
    text,
    '''                   COALESCE(m.button_color, 'BLUE'), COALESCE(m.page_no, 1),\n                   COALESCE(m.slot_no, p.display_order), p.display_order\n            FROM products p''',
    '''                   COALESCE(m.button_color, 'BLUE'), COALESCE(m.page_no, 1),\n                   COALESCE(m.slot_no, p.display_order), p.display_order,\n                   COALESCE(m.kana, ''), COALESCE(m.barcode, '')\n            FROM products p''',
    "Catalog listProducts SQL",
)
text = replace_once(
    text,
    '''                    slotNo = cursor.getInt(9),\n                    displayOrder = cursor.getInt(10),\n                )''',
    '''                    slotNo = cursor.getInt(9),\n                    displayOrder = cursor.getInt(10),\n                    kana = cursor.getString(11),\n                    barcode = cursor.getString(12),\n                )''',
    "Catalog listProducts mapping",
)
text = replace_once(
    text,
    '''        pageNo: Int,\n        slotNo: Int,\n        actor: String,\n    ) {\n        val cleanId = CatalogValidation.requireCode(productId, "商品コード")\n        val cleanName = CatalogValidation.requireName(name, "商品名")''',
    '''        pageNo: Int,\n        slotNo: Int,\n        actor: String,\n        kana: String = "",\n        barcode: String = "",\n    ) {\n        val cleanId = CatalogValidation.requireCode(productId, "商品コード")\n        val cleanName = CatalogValidation.requireName(name, "商品名")\n        val cleanKana = CatalogValidation.normalizeKana(kana)\n        val cleanBarcode = CatalogValidation.normalizeBarcode(barcode)''',
    "Catalog saveProduct signature",
)
text = replace_once(
    text,
    '''        val cleanColor = buttonColor.uppercase().takeIf { it in BUTTON_COLORS } ?: "BLUE"\n        require(originalId == null || originalId == cleanId) { "登録後の商品コードは変更できません" }\n\n        db.transaction {''',
    '''        val cleanColor = buttonColor.uppercase().takeIf { it in BUTTON_COLORS } ?: "BLUE"\n        require(originalId == null || originalId == cleanId) { "登録後の商品コードは変更できません" }\n        if (cleanBarcode.isNotBlank()) {\n            val owner = db.rawQuery(\n                "SELECT product_id FROM product_meta WHERE barcode = ? AND product_id <> ? LIMIT 1",\n                arrayOf(cleanBarcode, cleanId),\n            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }\n            require(owner == null) { "このバーコードは商品 $owner で使用されています" }\n        }\n\n        db.transaction {''',
    "Catalog barcode uniqueness guard",
)
text = replace_once(
    text,
    '''                    put("slot_no", slotNo)\n                    put("updated_at", System.currentTimeMillis())''',
    '''                    put("slot_no", slotNo)\n                    put("kana", cleanKana)\n                    put("barcode", cleanBarcode)\n                    put("updated_at", System.currentTimeMillis())''',
    "Catalog product_meta values",
)
text = replace_once(
    text,
    '''                slot_no INTEGER NOT NULL DEFAULT 1,\n                updated_at INTEGER NOT NULL\n            )''',
    '''                slot_no INTEGER NOT NULL DEFAULT 1,\n                kana TEXT NOT NULL DEFAULT '',\n                barcode TEXT NOT NULL DEFAULT '',\n                updated_at INTEGER NOT NULL\n            )''',
    "Catalog product_meta fresh schema",
)
text = replace_once(
    text,
    '''        db.execSQL(\n            """\n            CREATE TABLE IF NOT EXISTS tax_rate_master (''',
    '''        ensureColumn(db, "product_meta", "kana", "TEXT NOT NULL DEFAULT ''")\n        ensureColumn(db, "product_meta", "barcode", "TEXT NOT NULL DEFAULT ''")\n        db.execSQL(\n            "CREATE UNIQUE INDEX IF NOT EXISTS idx_product_meta_barcode_unique " +\n                "ON product_meta(barcode) WHERE barcode <> ''",\n        )\n        db.execSQL(\n            """\n            CREATE TABLE IF NOT EXISTS tax_rate_master (''',
    "Catalog product_meta migration",
)
text = replace_once(
    text,
    '''    private fun scalar(db: SQLiteDatabase, sql: String): Long =\n        db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getLong(0) else 0L }\n}\n''',
    '''    private fun ensureColumn(db: SQLiteDatabase, table: String, column: String, definition: String) {\n        val exists = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->\n            val nameIndex = cursor.getColumnIndexOrThrow("name")\n            var found = false\n            while (cursor.moveToNext()) {\n                if (cursor.getString(nameIndex) == column) {\n                    found = true\n                    break\n                }\n            }\n            found\n        }\n        if (!exists) db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")\n    }\n\n    private fun scalar(db: SQLiteDatabase, sql: String): Long =\n        db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getLong(0) else 0L }\n}\n''',
    "Catalog ensureColumn helper",
)
write(path, text)

# Product editor fields.
path = "app/src/main/java/jp/co/tenposinfo/register/CatalogSettingsActivity.kt"
text = read(path)
text = replace_once(
    text,
    '''    var productId by remember { mutableStateOf("") }\n    var name by remember { mutableStateOf("") }\n    var price by remember { mutableStateOf("0") }''',
    '''    var productId by remember { mutableStateOf("") }\n    var name by remember { mutableStateOf("") }\n    var kana by remember { mutableStateOf("") }\n    var barcode by remember { mutableStateOf("") }\n    var price by remember { mutableStateOf("0") }''',
    "Catalog UI state",
)
text = replace_once(
    text,
    '''        productId = selected?.productId.orEmpty()\n        name = selected?.name.orEmpty()\n        price = selected?.basePrice?.toString() ?: "0"''',
    '''        productId = selected?.productId.orEmpty()\n        name = selected?.name.orEmpty()\n        kana = selected?.kana.orEmpty()\n        barcode = selected?.barcode.orEmpty()\n        price = selected?.basePrice?.toString() ?: "0"''',
    "Catalog UI selected metadata",
)
text = replace_once(
    text,
    '''            MasterField(productId, { productId = it }, "商品コード", enabled = selected == null)\n            MasterField(name, { name = it }, "商品名")\n            MasterField(price, { price = it.filter(Char::isDigit).take(8) }, "基準価格（円）", KeyboardType.Number)''',
    '''            MasterField(productId, { productId = it }, "商品コード", enabled = selected == null)\n            MasterField(name, { name = it }, "商品名")\n            MasterField(kana, { kana = it.take(60) }, "かな（検索用・任意）")\n            MasterField(barcode, { barcode = it.filterNot(Char::isWhitespace).take(64) }, "バーコード（任意・一意）")\n            MasterField(price, { price = it.filter(Char::isDigit).take(8) }, "基準価格（円）", KeyboardType.Number)''',
    "Catalog UI fields",
)
text = replace_once(
    text,
    '''                        slotNo = slot.toIntOrNull() ?: 1,\n                        actor = actor,\n                    )''',
    '''                        slotNo = slot.toIntOrNull() ?: 1,\n                        actor = actor,\n                        kana = kana,\n                        barcode = barcode,\n                    )''',
    "Catalog UI save metadata",
)
write(path, text)

# Runtime carries metadata through both normal and scheduled menus.
path = "app/src/main/java/jp/co/tenposinfo/register/V11CatalogRuntime.kt"
text = read(path)
text = replace_once(
    text,
    '''        DynamicCatalogStore(appContext).use { store ->\n            store.runtimeProducts(products, metadata, businessDate)\n        }''',
    '''        val searchableProducts = products.map { product ->\n            metadata[product.id]?.let { meta ->\n                product.copy(kana = meta.kana, barcode = meta.barcode)\n            } ?: product\n        }\n        DynamicCatalogStore(appContext).use { store ->\n            store.runtimeProducts(searchableProducts, metadata, businessDate)\n        }''',
    "V11 runtime searchable products",
)
write(path, text)

# Sales screen wiring.
path = "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt"
text = read(path)
text = replace_once(
    text,
    '''class MainActivity : ComponentActivity() {\n    override fun onCreate(savedInstanceState: Bundle?) {''',
    '''class MainActivity : ComponentActivity() {\n    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {\n        if (BarcodeScannerRuntimeV135.handle(event)) return true\n        return super.dispatchKeyEvent(event)\n    }\n\n    override fun onCreate(savedInstanceState: Bundle?) {''',
    "MainActivity scanner dispatch",
)
text = replace_once(
    text,
    '''                onAddProduct = { product ->\n                    val mergeSameItem = initialReleaseSettingsStore.loadSales().mergeSameItem\n                    val index = if (mergeSameItem) cart.indexOfFirst {\n                        it.product.id == product.id &&\n                            it.unitPrice == product.unitPrice &&\n                            it.discountAmount == 0L &&\n                            it.note.isEmpty()\n                    } else -1\n                    if (index >= 0) {\n                        val updated = cart[index].copy(quantity = cart[index].quantity + 1)\n                        cart.removeAt(index)\n                        cart += updated\n                        selectedIndex = null\n                    } else {\n                        cart += CartItem(\n                            product = product,\n                            quantity = 1,\n                            lineId = CartLineIdentityV135.newId(),\n                        )\n                    }\n                    database.saveCart(cart.toList())\n                },''',
    '''                onAddProduct = { product, quantity ->\n                    require(quantity > 0) { "数量は1以上で指定してください" }\n                    val mergeSameItem = initialReleaseSettingsStore.loadSales().mergeSameItem\n                    val index = if (mergeSameItem) cart.indexOfFirst {\n                        it.product.id == product.id &&\n                            it.unitPrice == product.unitPrice &&\n                            it.discountAmount == 0L &&\n                            it.note.isEmpty()\n                    } else -1\n                    if (index >= 0) {\n                        val updated = cart[index].copy(quantity = cart[index].quantity + quantity)\n                        cart.removeAt(index)\n                        cart += updated\n                    } else {\n                        cart += CartItem(\n                            product = product,\n                            quantity = quantity,\n                            lineId = CartLineIdentityV135.newId(),\n                        )\n                    }\n                    selectedIndex = null\n                    database.saveCart(cart.toList())\n                },''',
    "RegisterApp quantity registration",
)
text = replace_once(
    text,
    '    onAddProduct: (Product) -> Unit,\n    onChangeQuantity: (Int) -> Unit,',
    '    onAddProduct: (Product, Int) -> Unit,\n    onChangeQuantity: (Int) -> Unit,',
    "SalesScreen callback signature",
)
text = replace_once(
    text,
    '''    val summary = TaxEngine.calculate(cart)\n    var numericInput by remember { mutableStateOf("") }\n    val responsive = rememberRegisterResponsiveMetrics()\n    Column(Modifier.fillMaxSize()) {''',
    '''    val summary = TaxEngine.calculate(cart)\n    var numericInput by remember { mutableStateOf("") }\n    var pendingQuantity by remember { mutableStateOf<Int?>(null) }\n    var showProductSearch by remember { mutableStateOf(false) }\n    var lookupMessage by remember { mutableStateOf<String?>(null) }\n    val responsive = rememberRegisterResponsiveMetrics()\n\n    androidx.compose.runtime.DisposableEffect(products, pendingQuantity, onAddProduct) {\n        val listener: (String) -> Unit = { scanned ->\n            val product = ProductLookupPolicyV135.findExact(products, scanned)\n            if (product == null) {\n                lookupMessage = "商品未登録: ${scanned.take(20)}"\n            } else {\n                onAddProduct(product, pendingQuantity ?: 1)\n                pendingQuantity = null\n                numericInput = ""\n                lookupMessage = null\n            }\n        }\n        BarcodeScannerRuntimeV135.setListener(listener)\n        onDispose { BarcodeScannerRuntimeV135.clearListener(listener) }\n    }\n\n    if (showProductSearch) {\n        SalesProductSearchDialogV135(\n            products = products,\n            onDismiss = { showProductSearch = false },\n            onRegister = { product ->\n                onAddProduct(product, pendingQuantity ?: 1)\n                pendingQuantity = null\n                numericInput = ""\n                lookupMessage = null\n                showProductSearch = false\n            },\n        )\n    }\n\n    Column(Modifier.fillMaxSize()) {''',
    "SalesScreen lookup state",
)
text = replace_once(
    text,
    '''                            Text(\n                                "置数・機能",\n                                fontSize = if (responsive.isCompact) 16.sp else 18.sp,''',
    '''                            Text(\n                                lookupMessage ?: pendingQuantity?.let { "次商品 ${it}点" } ?: "置数・機能",\n                                fontSize = if (responsive.isCompact) 16.sp else 18.sp,''',
    "Sales utility status",
)
text = replace_once(
    text,
    '''                            bottomActionLabel = "数量",\n                            onBottomAction = {\n                                numericInput.toIntOrNull()?.let(onChangeQuantity)\n                                numericInput = ""\n                            },''',
    '''                            bottomActionLabel = "数量",\n                            onBottomAction = {\n                                ProductQuantityKeyPolicyV135.decide(numericInput, selectedIndex != null)?.let { decision ->\n                                    decision.selectedLineQuantity?.let(onChangeQuantity)\n                                    decision.pendingProductQuantity?.let { pendingQuantity = it }\n                                    lookupMessage = null\n                                }\n                                numericInput = ""\n                            },''',
    "Sales quantity key policy",
)
text = replace_once(
    text,
    '''                                OutlinedButton(\n                                    onClick = onDiscount,\n                                    enabled = cart.isNotEmpty(),\n                                    modifier = Modifier.weight(1f).height(keypad.functionHeightDp.dp),\n                                ) { Text("値引・割引", fontSize = 13.sp, maxLines = 1) }\n                                TransactionAbortButtonV135(''',
    '''                                OutlinedButton(\n                                    onClick = onDiscount,\n                                    enabled = cart.isNotEmpty(),\n                                    modifier = Modifier.weight(1f).height(keypad.functionHeightDp.dp),\n                                ) { Text("値引・割引", fontSize = 13.sp, maxLines = 1) }\n                                OutlinedButton(\n                                    onClick = { showProductSearch = true; lookupMessage = null },\n                                    modifier = Modifier.weight(1f).height(keypad.functionHeightDp.dp),\n                                ) { Text("商品検索", fontSize = 13.sp, maxLines = 1) }\n                                TransactionAbortButtonV135(''',
    "Sales search button",
)
text = replace_once(
    text,
    '                                        onClick = { onAddProduct(product) },',
    '''                                        onClick = {\n                                            onAddProduct(product, pendingQuantity ?: 1)\n                                            pendingQuantity = null\n                                            numericInput = ""\n                                            lookupMessage = null\n                                        },''',
    "Sales product button quantity",
)
write(path, text)

# Pure search policy + keyboard-wedge scanner capture.
write("app/src/main/java/jp/co/tenposinfo/register/ProductLookupV135.kt", '''package jp.co.tenposinfo.register\n\nimport android.view.KeyEvent\n\ndata class ProductQuantityKeyDecisionV135(\n    val selectedLineQuantity: Int? = null,\n    val pendingProductQuantity: Int? = null,\n)\n\nobject ProductQuantityKeyPolicyV135 {\n    fun decide(raw: String, hasSelectedLine: Boolean): ProductQuantityKeyDecisionV135? {\n        val quantity = raw.toIntOrNull()?.takeIf { it in 1..99_999 } ?: return null\n        return if (hasSelectedLine) {\n            ProductQuantityKeyDecisionV135(selectedLineQuantity = quantity)\n        } else {\n            ProductQuantityKeyDecisionV135(pendingProductQuantity = quantity)\n        }\n    }\n}\n\nobject ProductLookupPolicyV135 {\n    fun findExact(products: List<Product>, raw: String): Product? {\n        val token = raw.trim()\n        if (token.isBlank()) return null\n        val matches = products.filter { product ->\n            product.id.equals(token, ignoreCase = true) ||\n                product.barcode.isNotBlank() && product.barcode == token\n        }.distinctBy { it.id }\n        return matches.singleOrNull()\n    }\n\n    fun search(products: List<Product>, raw: String): List<Product> {\n        val query = raw.trim()\n        if (query.isBlank()) return products.sortedBy { it.id }\n        return products\n            .mapNotNull { product ->\n                val exactCode = product.id.equals(query, ignoreCase = true)\n                val exactBarcode = product.barcode.isNotBlank() && product.barcode == query\n                val codePrefix = product.id.startsWith(query, ignoreCase = true)\n                val nameMatch = product.name.contains(query, ignoreCase = true)\n                val kanaMatch = product.kana.contains(query, ignoreCase = true)\n                val codeMatch = product.id.contains(query, ignoreCase = true)\n                val barcodeMatch = product.barcode.isNotBlank() && product.barcode.contains(query)\n                if (!(exactCode || exactBarcode || codePrefix || nameMatch || kanaMatch || codeMatch || barcodeMatch)) {\n                    null\n                } else {\n                    val score = when {\n                        exactCode || exactBarcode -> 0\n                        codePrefix -> 1\n                        nameMatch || kanaMatch -> 2\n                        else -> 3\n                    }\n                    score to product\n                }\n            }\n            .sortedWith(compareBy<Pair<Int, Product>> { it.first }.thenBy { it.second.id })\n            .map { it.second }\n    }\n}\n\n/** Keyboard-wedge scanners typically emit a rapid ASCII sequence followed by Enter. */\nobject BarcodeScannerRuntimeV135 {\n    private const val MAX_INTER_KEY_MS = 180L\n    private const val MIN_TOKEN_LENGTH = 4\n    private const val MAX_TOKEN_LENGTH = 128\n    private val lock = Any()\n    private val buffer = StringBuilder()\n    private var lastKeyAt = 0L\n    @Volatile private var listener: ((String) -> Unit)? = null\n\n    fun setListener(value: (String) -> Unit) {\n        synchronized(lock) {\n            buffer.setLength(0)\n            lastKeyAt = 0L\n            listener = value\n        }\n    }\n\n    fun clearListener(expected: (String) -> Unit) {\n        synchronized(lock) {\n            if (listener === expected) {\n                listener = null\n                buffer.setLength(0)\n                lastKeyAt = 0L\n            }\n        }\n    }\n\n    fun handle(event: KeyEvent): Boolean {\n        if (event.action != KeyEvent.ACTION_DOWN) return false\n        val target = listener ?: return false\n        val token = synchronized(lock) {\n            val now = event.eventTime\n            when (event.keyCode) {\n                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {\n                    val captured = buffer.toString().trim()\n                    buffer.setLength(0)\n                    lastKeyAt = 0L\n                    captured.takeIf { it.length >= MIN_TOKEN_LENGTH }\n                }\n                else -> {\n                    val unicode = event.unicodeChar\n                    if (unicode in 0x21..0x7e) {\n                        if (lastKeyAt > 0L && now - lastKeyAt > MAX_INTER_KEY_MS) buffer.setLength(0)\n                        if (buffer.length < MAX_TOKEN_LENGTH) buffer.append(unicode.toChar())\n                        lastKeyAt = now\n                    }\n                    null\n                }\n            }\n        }\n        if (token == null) return false\n        target(token)\n        return true\n    }\n}\n''')

write("app/src/main/java/jp/co/tenposinfo/register/SalesProductSearchDialogV135.kt", '''package jp.co.tenposinfo.register\n\nimport androidx.compose.foundation.clickable\nimport androidx.compose.foundation.layout.Column\nimport androidx.compose.foundation.layout.Row\nimport androidx.compose.foundation.layout.Spacer\nimport androidx.compose.foundation.layout.fillMaxWidth\nimport androidx.compose.foundation.layout.heightIn\nimport androidx.compose.foundation.layout.padding\nimport androidx.compose.foundation.layout.width\nimport androidx.compose.foundation.lazy.LazyColumn\nimport androidx.compose.foundation.lazy.items\nimport androidx.compose.foundation.text.KeyboardActions\nimport androidx.compose.foundation.text.KeyboardOptions\nimport androidx.compose.material3.AlertDialog\nimport androidx.compose.material3.HorizontalDivider\nimport androidx.compose.material3.OutlinedTextField\nimport androidx.compose.material3.Text\nimport androidx.compose.material3.TextButton\nimport androidx.compose.runtime.Composable\nimport androidx.compose.runtime.getValue\nimport androidx.compose.runtime.mutableStateOf\nimport androidx.compose.runtime.remember\nimport androidx.compose.runtime.setValue\nimport androidx.compose.ui.Modifier\nimport androidx.compose.ui.text.font.FontWeight\nimport androidx.compose.ui.text.input.ImeAction\nimport androidx.compose.ui.unit.dp\n\n@Composable\nfun SalesProductSearchDialogV135(\n    products: List<Product>,\n    onDismiss: () -> Unit,\n    onRegister: (Product) -> Unit,\n) {\n    var query by remember { mutableStateOf("") }\n    val results = remember(products, query) { ProductLookupPolicyV135.search(products, query).take(50) }\n    val submitExact = { ProductLookupPolicyV135.findExact(products, query)?.let(onRegister) }\n\n    AlertDialog(\n        onDismissRequest = onDismiss,\n        title = { Text("商品検索") },\n        text = {\n            Column {\n                OutlinedTextField(\n                    value = query,\n                    onValueChange = { query = it.take(80) },\n                    label = { Text("名称・かな・商品コード・バーコード") },\n                    singleLine = true,\n                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),\n                    keyboardActions = KeyboardActions(onSearch = { submitExact() }),\n                    modifier = Modifier.fillMaxWidth(),\n                )\n                Spacer(Modifier.width(8.dp))\n                Text("${results.size}件表示（最大50件）", modifier = Modifier.padding(vertical = 6.dp))\n                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {\n                    items(results, key = { it.id }) { product ->\n                        Row(\n                            Modifier.fillMaxWidth().clickable { onRegister(product) }.padding(vertical = 8.dp),\n                        ) {\n                            Column(Modifier.weight(1f)) {\n                                Text(product.name, fontWeight = FontWeight.Bold)\n                                Text(buildString {\n                                    append("コード ").append(product.id)\n                                    if (product.kana.isNotBlank()) append(" / ").append(product.kana)\n                                    if (product.barcode.isNotBlank()) append(" / JAN ").append(product.barcode)\n                                })\n                            }\n                            Text("${product.unitPrice}円")\n                        }\n                        HorizontalDivider()\n                    }\n                }\n            }\n        },\n        confirmButton = {\n            TextButton(\n                onClick = submitExact,\n                enabled = ProductLookupPolicyV135.findExact(products, query) != null,\n            ) { Text("コード一致を登録") }\n        },\n        dismissButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },\n    )\n}\n''')

write("app/src/test/java/jp/co/tenposinfo/register/V135ProductEntrySearchTest.kt", '''package jp.co.tenposinfo.register\n\nimport org.junit.Assert.assertEquals\nimport org.junit.Assert.assertNull\nimport org.junit.Assert.assertTrue\nimport org.junit.Test\n\nclass V135ProductEntrySearchTest {\n    private fun product(id: String, name: String, kana: String = "", barcode: String = "") = Product(\n        id = id,\n        name = name,\n        unitPrice = 100L,\n        taxCategory = TaxCategory.INCLUDED_10,\n        displayOrder = 1,\n        kana = kana,\n        barcode = barcode,\n    )\n\n    @Test\n    fun exactLookupSupportsProductCodeAndBarcode() {\n        val beer = product("P0001", "生ビール", "なまびーる", "4901234567890")\n        assertEquals(beer, ProductLookupPolicyV135.findExact(listOf(beer), "p0001"))\n        assertEquals(beer, ProductLookupPolicyV135.findExact(listOf(beer), "4901234567890"))\n    }\n\n    @Test\n    fun searchSupportsNameKanaCodeAndBarcode() {\n        val beer = product("P0001", "生ビール", "なまびーる", "4901234567890")\n        val food = product("F0020", "枝豆", "えだまめ", "4900000000020")\n        val products = listOf(beer, food)\n        assertEquals(listOf(beer), ProductLookupPolicyV135.search(products, "生ビ"))\n        assertEquals(listOf(food), ProductLookupPolicyV135.search(products, "えだ"))\n        assertTrue(ProductLookupPolicyV135.search(products, "P000").contains(beer))\n        assertEquals(listOf(food), ProductLookupPolicyV135.search(products, "0000000020"))\n    }\n\n    @Test\n    fun ambiguousExactCodeAndBarcodeFailsClosed() {\n        val a = product("ABC1", "A", barcode = "ZZZ1")\n        val b = product("ZZZ1", "B")\n        assertNull(ProductLookupPolicyV135.findExact(listOf(a, b), "ZZZ1"))\n    }\n\n    @Test\n    fun quantityKeyEditsSelectedLineOrReservesNextProduct() {\n        assertEquals(3, ProductQuantityKeyPolicyV135.decide("3", true)?.selectedLineQuantity)\n        assertNull(ProductQuantityKeyPolicyV135.decide("3", true)?.pendingProductQuantity)\n        assertEquals(3, ProductQuantityKeyPolicyV135.decide("3", false)?.pendingProductQuantity)\n        assertNull(ProductQuantityKeyPolicyV135.decide("0", false))\n        assertNull(ProductQuantityKeyPolicyV135.decide("100000", false))\n    }\n\n    @Test\n    fun catalogValidationAllowsBlankBarcodeAndRejectsWhitespace() {\n        assertEquals("", CatalogValidation.normalizeBarcode("  "))\n        assertEquals("490123", CatalogValidation.normalizeBarcode("490123"))\n        val failure = runCatching { CatalogValidation.normalizeBarcode("490 123") }.exceptionOrNull()\n        assertTrue(failure is IllegalArgumentException)\n    }\n}\n''')

write("docs/V1.35_UC_05_06_PRODUCT_ENTRY_SEARCH.md", '''# v1.35 UC-05 / UC-06 商品コード入力・検索 / 商品選択・登録\n\n## 実装範囲\n\n- 既存 `products.id` を商品コードの正本として維持し、既存DB・売上参照との互換性を保持。\n- `product_meta` に検索用 `kana` / `barcode` を冪等追加。バーコードは空欄可、入力時は一意。\n- 商品マスターで「かな（検索用・任意）」「バーコード（任意・一意）」を編集可能。\n- 販売画面の商品検索は名称・かな・商品コード・バーコードを対象。\n- 商品コードまたはバーコードの完全一致は検索ダイアログから直接登録可能。\n- キーボードウェッジ型バーコードスキャナの高速ASCII＋Enterを販売画面で捕捉し、コード/バーコード完全一致なら即登録。\n- 置数後の「数量」は、選択行がある場合は従来どおり行数量訂正、未選択時は次に登録する商品の数量予約として動作。例: `3 → 数量 → 商品A` で3点登録。\n- SET-001 `sale.mergeSameItem` が有効な場合、数量予約で登録した数量を既存同一行へ加算。\n\n## 安全性 / 互換性\n\n- 商品コード主キーを変更しない。新しい別コード主キーは導入しない。\n- 既存DBでは `PRAGMA table_info` により列の有無を確認してから `ALTER TABLE`。\n- 非空バーコードには部分UNIQUE INDEXを設定し、保存前にも重複を拒否。\n- スキャナ捕捉は販売画面表示中だけ有効で、4文字未満や低速入力のEnterは消費しない。\n- コードと他商品のバーコードが衝突して完全一致候補が複数になる場合は fail-closed で自動登録しない。\n\n## 自動テスト\n\n`V135ProductEntrySearchTest` で以下を固定する。\n\n- 商品コード / バーコード完全一致\n- 名称 / かな / 商品コード / バーコード部分検索\n- 曖昧完全一致の自動登録禁止\n- 選択行数量訂正 / 次商品数量予約の分岐\n- バーコード正規化・空白拒否\n\n## 実機確認が必要な項目\n\n- 実バーコードリーダー（USB/Bluetooth HID）の入力速度・Enter終端\n- 日本語IME表示中の検索操作\n- 800×480端末で検索ダイアログと商品ボタン操作\n- 連続スキャン時の登録順・数量予約解除\n''')

print("UC-05/06 patch prepared")
