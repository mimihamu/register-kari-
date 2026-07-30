package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

private val MrNavy = Color(0xFF173F6B)
private val MrBlue = Color(0xFF1976B9)
private val MrDanger = Color(0xFFC62828)
private val MrBackground = Color(0xFFF4F7FA)
private val MrBorder = Color(0xFFD5DEE7)
private val MrSelected = Color(0xFFFFEBEE)
private val MrChanged = Color(0xFFFFF4D9)
private val MrSame = Color(0xFFEAF5EC)

class MenuRevisionEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MenuRevisionEditorApp(onClose = { finish() })
            }
        }
    }
}

data class RevisionEditableProduct(
    val revisionId: Long,
    val productId: String,
    val name: String,
    val enabled: Boolean,
    val unitPrice: Long,
    val legacyTaxCategory: TaxCategory,
    val taxKey: String,
    val buttonColor: String,
    val pageNo: Int,
    val slotNo: Int,
)

data class LiveMenuProduct(
    val productId: String,
    val name: String,
    val enabled: Boolean,
    val unitPrice: Long,
    val legacyTaxCategory: TaxCategory,
    val taxKey: String,
    val buttonColor: String,
    val pageNo: Int,
    val slotNo: Int,
)

data class RevisionComparisonRow(
    val productId: String,
    val revision: RevisionEditableProduct?,
    val live: LiveMenuProduct?,
    val changedFields: Set<String>,
) {
    val changed: Boolean get() = changedFields.isNotEmpty()
    val statusLabel: String
        get() = when {
            revision == null -> "ライブのみ"
            live == null -> "改定のみ"
            changed -> changedFields.joinToString("・")
            else -> "同一"
        }
}

data class RevisionComparisonSummary(
    val total: Int,
    val changed: Int,
    val same: Int,
    val liveOnly: Int,
    val revisionOnly: Int,
)

object RevisionDiffPolicy {
    fun changedFields(live: LiveMenuProduct?, revision: RevisionEditableProduct?): Set<String> {
        if (live == null && revision == null) return emptySet()
        if (live == null) return setOf("改定のみ")
        if (revision == null) return setOf("ライブのみ")
        val changed = linkedSetOf<String>()
        if (live.name != revision.name) changed += "名称"
        if (live.enabled != revision.enabled) changed += "有効状態"
        if (live.unitPrice != revision.unitPrice) changed += "価格"
        if (live.taxKey != revision.taxKey) changed += "税区分"
        if (live.buttonColor != revision.buttonColor) changed += "色"
        if (live.pageNo != revision.pageNo || live.slotNo != revision.slotNo) changed += "配置"
        return changed
    }
}

class MenuRevisionEditorStore(context: Context) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val database = RegisterDatabase(applicationContext)
    private val db = database.writableDatabase

    init {
        DynamicCatalogStore(applicationContext).also { it.close() }
    }

    override fun close() = database.close()

    fun businessDate(): LocalDate = BusinessDateResolver.current(db)

    fun revisions(): List<MenuRevisionRecord> = DynamicCatalogStore(applicationContext).use { it.listMenuRevisions() }

    fun taxRules(): List<DynamicTaxRule> = DynamicCatalogStore(applicationContext).use { it.listTaxRules().filter(DynamicTaxRule::enabled) }

    fun revisionProducts(revisionId: Long): List<RevisionEditableProduct> {
        val result = mutableListOf<RevisionEditableProduct>()
        db.query(
            "menu_revision_products",
            arrayOf(
                "revision_id", "product_id", "product_name", "enabled", "unit_price",
                "legacy_tax_category", "tax_key", "button_color", "page_no", "slot_no",
            ),
            "revision_id = ?",
            arrayOf(revisionId.toString()),
            null,
            null,
            "display_order ASC, product_id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += RevisionEditableProduct(
                    revisionId = cursor.getLong(0),
                    productId = cursor.getString(1),
                    name = cursor.getString(2),
                    enabled = cursor.getInt(3) != 0,
                    unitPrice = cursor.getLong(4),
                    legacyTaxCategory = TaxCategory.valueOf(cursor.getString(5)),
                    taxKey = cursor.getString(6),
                    buttonColor = cursor.getString(7),
                    pageNo = cursor.getInt(8),
                    slotNo = cursor.getInt(9),
                )
            }
        }
        return result
    }

    fun liveProducts(): List<LiveMenuProduct> {
        val result = mutableListOf<LiveMenuProduct>()
        db.rawQuery(
            """
            SELECT p.id, p.name, COALESCE(m.enabled, 1), b.base_price, b.base_tax_category,
                   COALESCE(a.tax_key, b.base_tax_category), COALESCE(m.button_color, 'BLUE'),
                   COALESCE(m.page_no, 1), COALESCE(m.slot_no, p.display_order)
            FROM products p
            INNER JOIN catalog_product_base b ON b.product_id = p.id
            LEFT JOIN product_meta m ON m.product_id = p.id
            LEFT JOIN product_tax_assignments a ON a.product_id = p.id
            ORDER BY COALESCE(m.page_no, 1), COALESCE(m.slot_no, p.display_order), p.id
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += LiveMenuProduct(
                    productId = cursor.getString(0),
                    name = cursor.getString(1),
                    enabled = cursor.getInt(2) != 0,
                    unitPrice = cursor.getLong(3),
                    legacyTaxCategory = TaxCategory.valueOf(cursor.getString(4)),
                    taxKey = cursor.getString(5),
                    buttonColor = cursor.getString(6),
                    pageNo = cursor.getInt(7),
                    slotNo = cursor.getInt(8),
                )
            }
        }
        return result
    }

    fun comparison(revisionId: Long): List<RevisionComparisonRow> {
        val revision = revisionProducts(revisionId).associateBy { it.productId }
        val live = liveProducts().associateBy { it.productId }
        return (revision.keys + live.keys).sorted().map { productId ->
            val revisionRow = revision[productId]
            val liveRow = live[productId]
            RevisionComparisonRow(
                productId = productId,
                revision = revisionRow,
                live = liveRow,
                changedFields = RevisionDiffPolicy.changedFields(liveRow, revisionRow),
            )
        }.sortedWith(
            compareByDescending<RevisionComparisonRow> { it.changed }
                .thenBy { it.revision?.pageNo ?: it.live?.pageNo ?: 99 }
                .thenBy { it.revision?.slotNo ?: it.live?.slotNo ?: 99 }
                .thenBy { it.productId },
        )
    }

    fun comparisonSummary(rows: List<RevisionComparisonRow>): RevisionComparisonSummary = RevisionComparisonSummary(
        total = rows.size,
        changed = rows.count { it.changed },
        same = rows.count { !it.changed },
        liveOnly = rows.count { it.revision == null },
        revisionOnly = rows.count { it.live == null },
    )

    fun isEditable(revision: MenuRevisionRecord): Boolean =
        revision.status == "SCHEDULED" && LocalDate.parse(revision.effectiveDate).isAfter(businessDate())

    fun saveRevisionHeader(revisionId: Long, name: String, effectiveDate: String, actor: String) {
        val cleanName = name.trim()
        require(cleanName.isNotBlank()) { "改定名を入力してください" }
        require(cleanName.length <= 80) { "改定名は80文字以内です" }
        val cleanDate = DynamicTaxValidation.validateDate(effectiveDate, "適用営業日")
        require(LocalDate.parse(cleanDate).isAfter(businessDate())) { "適用営業日は現在の営業日より後を指定してください" }
        requireEditable(revisionId)
        db.transaction {
            update(
                "menu_revisions",
                ContentValues().apply {
                    put("name", cleanName)
                    put("effective_date", cleanDate)
                },
                "id = ?",
                arrayOf(revisionId.toString()),
            )
            audit(this, "MENU_REVISION_HEADER_UPDATED", revisionId.toString(), "$cleanName / $cleanDate", actor)
        }
    }

    fun saveProduct(product: RevisionEditableProduct, actor: String) {
        requireEditable(product.revisionId)
        val cleanName = product.name.trim()
        require(cleanName.isNotBlank()) { "商品名を入力してください" }
        require(cleanName.length <= 60) { "商品名は60文字以内です" }
        require(product.unitPrice in 0..99_999_999L) { "価格は0～99,999,999円です" }
        ButtonLayoutPolicy.validate(product.pageNo, product.slotNo)
        val color = product.buttonColor.uppercase()
        require(color in BUTTON_COLORS) { "ボタン色が不正です" }
        val taxRuleExists = db.rawQuery(
            "SELECT COUNT(*) FROM dynamic_tax_rules WHERE tax_key = ? AND enabled = 1",
            arrayOf(product.taxKey),
        ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 1 }
        require(taxRuleExists) { "有効な税区分を選択してください" }

        db.transaction {
            val old = query(
                "menu_revision_products",
                arrayOf("page_no", "slot_no", "legacy_tax_category"),
                "revision_id = ? AND product_id = ?",
                arrayOf(product.revisionId.toString(), product.productId),
                null,
                null,
                null,
            ).use { cursor ->
                require(cursor.moveToFirst()) { "改定商品が見つかりません" }
                Triple(cursor.getInt(0), cursor.getInt(1), TaxCategory.valueOf(cursor.getString(2)))
            }
            val occupied = query(
                "menu_revision_products",
                arrayOf("product_id"),
                "revision_id = ? AND page_no = ? AND slot_no = ? AND product_id <> ?",
                arrayOf(product.revisionId.toString(), product.pageNo.toString(), product.slotNo.toString(), product.productId),
                null,
                null,
                null,
                "1",
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            if (occupied != null) {
                update(
                    "menu_revision_products",
                    ContentValues().apply {
                        put("page_no", old.first)
                        put("slot_no", old.second)
                        put("display_order", ButtonLayoutPolicy.displayOrder(old.first, old.second))
                    },
                    "revision_id = ? AND product_id = ?",
                    arrayOf(product.revisionId.toString(), occupied),
                )
            }
            val legacyCategory = TaxCategory.entries.firstOrNull { it.name == product.taxKey } ?: old.third
            update(
                "menu_revision_products",
                ContentValues().apply {
                    put("product_name", cleanName)
                    put("enabled", if (product.enabled) 1 else 0)
                    put("unit_price", product.unitPrice)
                    put("legacy_tax_category", legacyCategory.name)
                    put("tax_key", product.taxKey)
                    put("button_color", color)
                    put("page_no", product.pageNo)
                    put("slot_no", product.slotNo)
                    put("display_order", ButtonLayoutPolicy.displayOrder(product.pageNo, product.slotNo))
                },
                "revision_id = ? AND product_id = ?",
                arrayOf(product.revisionId.toString(), product.productId),
            )
            audit(
                this,
                "MENU_REVISION_PRODUCT_UPDATED",
                "${product.revisionId}:${product.productId}",
                "$cleanName / ${product.unitPrice}円 / ${product.taxKey} / P${product.pageNo}-${product.slotNo}",
                actor,
            )
        }
    }

    fun resetProductToLive(revisionId: Long, productId: String, actor: String) {
        requireEditable(revisionId)
        val live = liveProducts().firstOrNull { it.productId == productId } ?: error("ライブ商品が見つかりません")
        val current = revisionProducts(revisionId).firstOrNull { it.productId == productId } ?: error("改定商品が見つかりません")
        saveProduct(
            current.copy(
                name = live.name,
                enabled = live.enabled,
                unitPrice = live.unitPrice,
                legacyTaxCategory = live.legacyTaxCategory,
                taxKey = live.taxKey,
                buttonColor = live.buttonColor,
                pageNo = live.pageNo,
                slotNo = live.slotNo,
            ),
            actor,
        )
    }

    private fun requireEditable(revisionId: Long) {
        val revision = revisions().firstOrNull { it.id == revisionId } ?: error("メニュー改定が見つかりません")
        require(isEditable(revision)) { "適用済み・当日適用・取消済みの改定は編集できません" }
    }

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

@Composable
private fun MenuRevisionEditorApp(onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember { MenuRevisionEditorStore(context.applicationContext) }
    val actor = remember { OperatorSessionRegistry.current(context.applicationContext)?.name ?: "責任者" }
    var refresh by remember { mutableIntStateOf(0) }
    val revisions = remember(refresh) { store.revisions() }
    var selectedRevisionId by remember { mutableStateOf<Long?>(revisions.firstOrNull()?.id) }
    val selectedRevision = revisions.firstOrNull { it.id == selectedRevisionId }
    val rows = remember(refresh, selectedRevisionId) {
        selectedRevisionId?.let(store::comparison).orEmpty()
    }
    val summary = remember(rows) { store.comparisonSummary(rows) }
    var selectedProductId by remember { mutableStateOf<String?>(null) }
    val selectedRow = rows.firstOrNull { it.productId == selectedProductId }
    var message by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) { onDispose { store.close() } }
    LaunchedEffect(selectedRevisionId) { selectedProductId = rows.firstOrNull()?.productId }

    Surface(Modifier.fillMaxSize(), color = MrBackground) {
        Column(Modifier.fillMaxSize()) {
            MrHeader(
                title = "SCR-274  メニュー改定内容編集・比較",
                subtitle = "判定営業日 ${store.businessDate()}",
                onClose = onClose,
            )
            if (message != null) {
                Text(message!!, modifier = Modifier.fillMaxWidth().background(MrSame).padding(8.dp), color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxSize().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(
                    Modifier.width(285.dp).fillMaxHeight(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, MrBorder),
                ) {
                    Column(Modifier.fillMaxSize().padding(12.dp)) {
                        Text("改定一覧", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MrNavy)
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(revisions, key = { it.id }) { revision ->
                                val editable = store.isEditable(revision)
                                Column(
                                    Modifier.fillMaxWidth()
                                        .background(if (selectedRevisionId == revision.id) MrSelected else Color.Transparent, RoundedCornerShape(6.dp))
                                        .clickable { selectedRevisionId = revision.id }
                                        .padding(10.dp),
                                ) {
                                    Text(revision.name, fontWeight = FontWeight.Bold, color = MrNavy)
                                    Text("適用 ${revision.effectiveDate} / ${revision.itemCount}商品", fontSize = 12.sp)
                                    Text(if (editable) "編集可能" else "参照のみ", color = if (editable) Color(0xFF2E7D32) else Color.Gray, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                Card(
                    Modifier.weight(1f).fillMaxHeight(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, MrBorder),
                ) {
                    Column(Modifier.fillMaxSize().padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(selectedRevision?.name ?: "改定未選択", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = MrNavy)
                                Text("ライブマスターとの差分", color = Color.Gray)
                            }
                            Text("差分 ${summary.changed}/${summary.total}", color = MrDanger, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MrMiniSummary("同一", summary.same, Modifier.weight(1f))
                            MrMiniSummary("変更", summary.changed, Modifier.weight(1f))
                            MrMiniSummary("ライブのみ", summary.liveOnly, Modifier.weight(1f))
                            MrMiniSummary("改定のみ", summary.revisionOnly, Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(rows, key = { it.productId }) { row ->
                                val name = row.revision?.name ?: row.live?.name ?: row.productId
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(
                                            when {
                                                selectedProductId == row.productId -> MrSelected
                                                row.changed -> MrChanged
                                                else -> Color.Transparent
                                            },
                                            RoundedCornerShape(5.dp),
                                        )
                                        .clickable { selectedProductId = row.productId }
                                        .padding(9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(row.productId, Modifier.width(85.dp), fontWeight = FontWeight.Bold)
                                    Column(Modifier.weight(1f)) {
                                        Text(name, fontWeight = FontWeight.SemiBold)
                                        Text(row.statusLabel, fontSize = 12.sp, color = if (row.changed) MrDanger else Color.Gray)
                                    }
                                    Text(
                                        row.revision?.unitPrice?.let { "¥%,d".format(it) } ?: "-",
                                        Modifier.width(90.dp),
                                        textAlign = TextAlign.End,
                                    )
                                }
                            }
                        }
                    }
                }

                RevisionProductEditor(
                    store = store,
                    revision = selectedRevision,
                    row = selectedRow,
                    actor = actor,
                    onChanged = { text ->
                        refresh++
                        message = text
                    },
                )
            }
        }
    }
}

@Composable
private fun RevisionProductEditor(
    store: MenuRevisionEditorStore,
    revision: MenuRevisionRecord?,
    row: RevisionComparisonRow?,
    actor: String,
    onChanged: (String) -> Unit,
) {
    val product = row?.revision
    val editable = revision != null && store.isEditable(revision) && product != null
    val rules = remember { store.taxRules() }
    var name by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("0") }
    var enabled by remember { mutableStateOf(true) }
    var taxKey by remember { mutableStateOf("") }
    var color by remember { mutableStateOf("BLUE") }
    var page by remember { mutableStateOf("1") }
    var slot by remember { mutableStateOf("1") }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(product?.productId, product?.revisionId) {
        name = product?.name.orEmpty()
        price = product?.unitPrice?.toString() ?: "0"
        enabled = product?.enabled ?: true
        taxKey = product?.taxKey.orEmpty()
        color = product?.buttonColor ?: "BLUE"
        page = product?.pageNo?.toString() ?: "1"
        slot = product?.slotNo?.toString() ?: "1"
        error = null
    }

    Card(
        Modifier.width(390.dp).fillMaxHeight(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, MrBorder),
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
            Text("改定商品編集", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = MrNavy)
            Spacer(Modifier.height(8.dp))
            if (product == null) {
                Text("改定に存在する商品を選択してください", color = Color.Gray)
                return@Column
            }
            Text("${product.productId} / ${if (editable) "編集可能" else "参照のみ"}", color = if (editable) Color(0xFF2E7D32) else Color.Gray)
            if (row.live != null) {
                Text("ライブ：${row.live.name} / ¥%,d / ${row.live.taxKey}".format(row.live.unitPrice), fontSize = 12.sp, color = Color.Gray)
            }
            Spacer(Modifier.height(8.dp))
            MrField(name, { name = it.take(60) }, "商品名", editable)
            MrField(price, { price = it.filter(Char::isDigit).take(8) }, "価格", editable, KeyboardType.Number)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(enabled, { enabled = it }, enabled = editable)
                Text("有効")
            }
            MrCycle("税区分", rules.firstOrNull { it.key == taxKey }?.let { "${it.label} ${it.ratePercent}%" } ?: taxKey, editable) {
                val index = rules.indexOfFirst { it.key == taxKey }
                taxKey = rules[(index + 1).mod(rules.size.coerceAtLeast(1))].key
            }
            MrCycle("ボタン色", color, editable) {
                val colors = BUTTON_COLORS.toList().sorted()
                val index = colors.indexOf(color)
                color = colors[(index + 1).mod(colors.size)]
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MrField(page, { page = it.filter(Char::isDigit).take(1) }, "ページ", editable, KeyboardType.Number, Modifier.weight(1f))
                MrField(slot, { slot = it.filter(Char::isDigit).take(2) }, "位置", editable, KeyboardType.Number, Modifier.weight(1f))
            }
            if (error != null) Text(error!!, color = MrDanger, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            if (editable) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            error = runCatching {
                                store.resetProductToLive(product.revisionId, product.productId, actor)
                                onChanged("ライブ内容へ戻しました")
                            }.exceptionOrNull()?.message
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("ライブへ戻す") }
                    Button(
                        onClick = {
                            error = runCatching {
                                store.saveProduct(
                                    product.copy(
                                        name = name,
                                        enabled = enabled,
                                        unitPrice = price.toLongOrNull() ?: 0,
                                        taxKey = taxKey,
                                        buttonColor = color,
                                        pageNo = page.toIntOrNull() ?: 1,
                                        slotNo = slot.toIntOrNull() ?: 1,
                                    ),
                                    actor,
                                )
                                onChanged("改定商品を保存しました")
                            }.exceptionOrNull()?.message
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MrBlue),
                    ) { Text("保存") }
                }
            }
            Spacer(Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = if (row.changed) MrChanged else MrSame)) {
                Column(Modifier.fillMaxWidth().padding(10.dp)) {
                    Text("差分", fontWeight = FontWeight.Bold)
                    Text(row.statusLabel)
                }
            }
        }
    }
}

@Composable
private fun MrHeader(title: String, subtitle: String, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(62.dp).background(MrNavy).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("REGISTER", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(20.dp))
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(subtitle, color = Color.White, fontSize = 13.sp)
        Spacer(Modifier.width(12.dp))
        OutlinedButton(onClick = onClose, border = BorderStroke(1.dp, Color.White)) { Text("戻る", color = Color.White) }
    }
}

@Composable
private fun MrMiniSummary(label: String, value: Int, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MrBackground), border = BorderStroke(1.dp, MrBorder)) {
        Column(Modifier.fillMaxWidth().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 11.sp, color = Color.Gray)
            Text(value.toString(), fontWeight = FontWeight.Bold, color = MrNavy)
        }
    }
}

@Composable
private fun MrField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        modifier = modifier.padding(vertical = 3.dp),
    )
}

@Composable
private fun MrCycle(label: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text("$label：$value", textAlign = TextAlign.Center)
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
