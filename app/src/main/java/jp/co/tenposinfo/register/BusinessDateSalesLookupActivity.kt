package jp.co.tenposinfo.register

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BusinessLookupNavy = Color(0xFF173F6B)
private val BusinessLookupBlue = Color(0xFF1976B9)
private val BusinessLookupBackground = Color(0xFFF4F7FA)
private val BusinessLookupBorder = Color(0xFFD5DEE7)
private val BusinessLookupPaleBlue = Color(0xFFEAF3FA)
private val BusinessLookupDanger = Color(0xFFC62828)
private val BusinessLookupGreen = Color(0xFF2E7D32)

/**
 * v0.66 営業日別売上DB直接検索。
 *
 * salesのbusiness_date/business_session_idは読み取りのみ。旧DBに列がない場合も
 * SchemaMigration.hasColumn()による存在確認だけを行い、ALTER/BACKFILLは実行しない。
 * 値条件はselectionArgsへバインドし、ページ単位で取得する。
 */
class BusinessDateSalesLookupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureRegisterSystemBars(window)
        val requestedContext = BusinessDateSalesLookupNavigation.requestedContext(intent)
        setContent {
            MaterialTheme {
                BusinessDateSalesLookupRoute(
                    requestedContext = requestedContext,
                    onClose = { finish() },
                )
            }
        }
    }
}

internal class BusinessDateSalesReadStore(context: Context) : AutoCloseable {
    private val database = RegisterDatabase(context.applicationContext)
    private val db: SQLiteDatabase get() = database.readableDatabase

    fun search(
        criteria: SalesHistoryCriteria,
        offset: Int = 0,
        pageSize: Int = SalesHistoryLookupPolicy.DATABASE_PAGE_SIZE,
    ): BusinessDateSalesQueryPage {
        val safeOffset = offset.coerceAtLeast(0)
        val safePageSize = pageSize.coerceIn(1, SalesHistoryLookupPolicy.DATABASE_PAGE_SIZE)
        val businessDateAvailable = SchemaMigration.hasColumn(db, "sales", "business_date")
        val businessSessionIdAvailable = SchemaMigration.hasColumn(db, "sales", "business_session_id")
        val query = SalesHistoryLookupPolicy.buildDatabaseQuery(
            criteria = criteria,
            businessDateColumnAvailable = businessDateAvailable,
            businessSessionIdColumnAvailable = businessSessionIdAvailable,
        ) ?: return BusinessDateSalesQueryPage(emptyList(), safeOffset, safePageSize, hasNext = false)

        val columns = attributionSelectColumns(db)
        val selectionArgs = query.args + listOf((safePageSize + 1).toString(), safeOffset.toString())
        val rows = db.rawQuery(
            "SELECT $columns FROM sales ${query.whereSql} ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
            selectionArgs.toTypedArray(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toBusinessDateSaleRecord())
            }
        }
        return BusinessDateSalesQueryPage(
            records = rows.take(safePageSize),
            offset = safeOffset,
            pageSize = safePageSize,
            hasNext = rows.size > safePageSize,
        )
    }

    fun loadExact(saleId: Long): BusinessDateSaleRecord? {
        if (saleId <= 0L) return null
        val columns = attributionSelectColumns(db)
        return db.rawQuery(
            "SELECT $columns FROM sales WHERE id = ? LIMIT 1",
            arrayOf(saleId.toString()),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toBusinessDateSaleRecord() else null }
    }

    fun loadDetail(saleId: Long): SaleDetailRecord? = database.loadSaleDetail(saleId)

    override fun close() = database.close()

    private fun attributionSelectColumns(db: SQLiteDatabase): String {
        val businessDate = if (SchemaMigration.hasColumn(db, "sales", "business_date")) {
            "business_date"
        } else {
            "NULL"
        }
        val businessSessionId = if (SchemaMigration.hasColumn(db, "sales", "business_session_id")) {
            "business_session_id"
        } else {
            "NULL"
        }
        return listOf(
            "id",
            "operator_name",
            "payment_method",
            "total_amount",
            "tax_amount",
            "change_amount",
            "created_at",
            "print_count",
            "$businessDate AS business_date",
            "$businessSessionId AS business_session_id",
        ).joinToString(", ")
    }

    private fun Cursor.toBusinessDateSaleRecord(): BusinessDateSaleRecord = BusinessDateSaleRecord(
        summary = SaleSummaryRecord(
            id = getLong(0),
            operatorName = getString(1),
            paymentLabel = getString(2),
            totalAmount = getLong(3),
            taxAmount = getLong(4),
            changeAmount = getLong(5),
            createdAt = getLong(6),
            printCount = getInt(7),
        ),
        businessDate = if (isNull(8)) null else getString(8),
        businessSessionId = if (isNull(9)) null else getLong(9),
    )
}

@Composable
private fun BusinessDateSalesLookupRoute(
    requestedContext: BusinessDateSalesLookupContext?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val store = remember { BusinessDateSalesReadStore(appContext) }
    val reversalTraceStore = remember { SaleReversalTraceReadStoreV135(appContext) }
    var operator by remember { mutableStateOf(OperatorSessionRegistry.current(appContext)) }
    var refreshEpoch by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        onDispose {
            reversalTraceStore.close()
            store.close()
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(5_000L)
            operator = OperatorSessionRegistry.current(appContext)
        }
    }

    Surface(Modifier.fillMaxSize(), color = BusinessLookupBackground) {
        val current = operator
        if (current == null || !current.allows(RegisterPermission.VIEW_SALES)) {
            BusinessDateSalesLookupDenied(onClose)
            return@Surface
        }
        OperatorSessionRegistry.touch(appContext)
        BusinessDateSalesLookupScreen(
            store = store,
            reversalTraceStore = reversalTraceStore,
            requestedContext = requestedContext,
            refreshEpoch = refreshEpoch,
            canReverse = current.allows(RegisterPermission.REVERSAL),
            onRefresh = { refreshEpoch++ },
            onOpenReceipt = { saleId ->
                context.startActivity(SaleReceiptNavigation.intent(context, saleId))
            },
            onOpenVoucher = { saleId ->
                context.startActivity(ReceiptVoucherNavigation.issuanceIntent(context, saleId))
            },
            onOpenReversal = { saleId ->
                context.startActivity(ReversalNavigation.intent(context, saleId))
            },
            onOpenPrintQueue = {
                context.startActivity(Intent(context, UnifiedPrintQueueActivity::class.java))
            },
            onClose = onClose,
        )
    }
}

@Composable
private fun BusinessDateSalesLookupScreen(
    store: BusinessDateSalesReadStore,
    reversalTraceStore: SaleReversalTraceReadStoreV135,
    requestedContext: BusinessDateSalesLookupContext?,
    refreshEpoch: Int,
    canReverse: Boolean,
    onRefresh: () -> Unit,
    onOpenReceipt: (Long) -> Unit,
    onOpenVoucher: (Long) -> Unit,
    onOpenReversal: (Long) -> Unit,
    onOpenPrintQueue: () -> Unit,
    onClose: () -> Unit,
) {
    var contextLocked by remember(requestedContext) { mutableStateOf(requestedContext != null) }
    var query by remember { mutableStateOf("") }
    var minAmountText by remember { mutableStateOf("") }
    var maxAmountText by remember { mutableStateOf("") }
    var businessDateFrom by remember(requestedContext) { mutableStateOf(requestedContext?.businessDate.orEmpty()) }
    var businessDateTo by remember(requestedContext) { mutableStateOf(requestedContext?.businessDate.orEmpty()) }
    var directSaleIdText by remember { mutableStateOf("") }
    var appliedCriteria by remember(requestedContext) {
        mutableStateOf(
            requestedContext?.let { requested ->
                SalesHistoryCriteria(
                    businessDateFrom = requested.businessDate,
                    businessDateTo = requested.businessDate,
                    businessSessionId = requested.businessSessionId,
                )
            } ?: SalesHistoryCriteria(),
        )
    }
    var pageOffset by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<BusinessDateSaleRecord?>(null) }
    var lookupMessage by remember { mutableStateOf<String?>(null) }

    val draftCriteria = SalesHistoryCriteria(
        query = query,
        minAmount = minAmountText.toLongOrNull(),
        maxAmount = maxAmountText.toLongOrNull(),
        businessDateFrom = businessDateFrom,
        businessDateTo = businessDateTo,
        businessSessionId = if (contextLocked) requestedContext?.businessSessionId else null,
    )
    val validation = SalesHistoryLookupPolicy.validate(draftCriteria)
    val page = remember(appliedCriteria, pageOffset, refreshEpoch) {
        store.search(appliedCriteria, pageOffset)
    }
    val directSaleId = SalesHistoryLookupPolicy.parseDirectSaleId(directSaleIdText)
    val pageReversalTraces = remember(page.records, refreshEpoch) {
        reversalTraceStore.load(page.records.map { it.summary.id })
    }
    val selectedSaleId = selected?.summary?.id
    val selectedReversalTrace = remember(selectedSaleId, pageReversalTraces, refreshEpoch) {
        selectedSaleId?.let { saleId ->
            pageReversalTraces[saleId] ?: reversalTraceStore.loadOne(saleId)
        } ?: SaleReversalTraceV135.ACTIVE
    }
    val selectedDetail = selected?.let { store.loadDetail(it.summary.id) }
    val resultFrom = if (page.records.isEmpty()) 0 else page.offset + 1
    val resultTo = page.offset + page.records.size

    Column(Modifier.fillMaxSize()) {
        BusinessDateLookupHeader()
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            if (contextLocked && requestedContext != null) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "SCR-510連携: 営業日 ${requestedContext.businessDate} / 営業セッション No.${requestedContext.businessSessionId} 固定",
                        color = BusinessLookupGreen,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = {
                            contextLocked = false
                            directSaleIdText = ""
                            appliedCriteria = SalesHistoryCriteria(
                                businessDateFrom = requestedContext.businessDate,
                                businessDateTo = requestedContext.businessDate,
                            )
                            pageOffset = 0
                            selected = null
                            lookupMessage = "営業セッション固定を解除し、同じ営業日の全売上を表示しました"
                        },
                    ) { Text("固定解除・同日全売上を表示") }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = businessDateFrom,
                    onValueChange = {
                        businessDateFrom = it.filter { ch -> ch.isDigit() || ch == '-' }.take(10)
                        lookupMessage = null
                    },
                    label = { Text("営業日From") },
                    enabled = !contextLocked,
                    placeholder = { Text("YYYY-MM-DD") },
                    singleLine = true,
                    modifier = Modifier.width(175.dp),
                )
                Text("～", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = businessDateTo,
                    onValueChange = {
                        businessDateTo = it.filter { ch -> ch.isDigit() || ch == '-' }.take(10)
                        lookupMessage = null
                    },
                    label = { Text("営業日To") },
                    enabled = !contextLocked,
                    placeholder = { Text("YYYY-MM-DD") },
                    singleLine = true,
                    modifier = Modifier.width(175.dp),
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(80) },
                    label = { Text("売上No.・担当・支払") },
                    enabled = !contextLocked,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = minAmountText,
                    onValueChange = { minAmountText = it.filter(Char::isDigit).take(12) },
                    label = { Text("金額以上") },
                    enabled = !contextLocked,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.width(135.dp),
                )
                OutlinedTextField(
                    value = maxAmountText,
                    onValueChange = { maxAmountText = it.filter(Char::isDigit).take(12) },
                    label = { Text("金額以下") },
                    enabled = !contextLocked,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.width(135.dp),
                )
                Button(
                    onClick = {
                        appliedCriteria = draftCriteria
                        pageOffset = 0
                        selected = null
                        lookupMessage = null
                    },
                    enabled = !contextLocked && validation.valid,
                    colors = ButtonDefaults.buttonColors(containerColor = BusinessLookupBlue),
                ) { Text("検索") }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "DB検索 ${resultFrom}～${resultTo}件 / 1ページ最大${page.pageSize}件",
                    color = BusinessLookupNavy,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = { pageOffset = (pageOffset - page.pageSize).coerceAtLeast(0) },
                    enabled = pageOffset > 0,
                ) { Text("前へ") }
                OutlinedButton(
                    onClick = { pageOffset += page.pageSize },
                    enabled = page.hasNext,
                ) { Text("次へ") }
                OutlinedTextField(
                    value = directSaleIdText,
                    onValueChange = {
                        directSaleIdText = it.filter { ch -> ch.isDigit() || ch == '#' }.take(20)
                        lookupMessage = null
                    },
                    label = { Text("売上No.直接表示") },
                    enabled = !contextLocked,
                    singleLine = true,
                    modifier = Modifier.width(205.dp),
                )
                Button(
                    onClick = {
                        val saleId = directSaleId ?: return@Button
                        val exact = store.loadExact(saleId)
                        if (exact == null) {
                            selected = null
                            lookupMessage = "売上No.$saleId は見つかりません。選択を解除しました"
                        } else {
                            selected = exact
                            lookupMessage = null
                        }
                    },
                    enabled = !contextLocked && directSaleId != null,
                    colors = ButtonDefaults.buttonColors(containerColor = BusinessLookupBlue),
                ) { Text("表示") }
                OutlinedButton(
                    enabled = !contextLocked,
                    onClick = {
                        query = ""
                        minAmountText = ""
                        maxAmountText = ""
                        businessDateFrom = ""
                        businessDateTo = ""
                        directSaleIdText = ""
                        appliedCriteria = SalesHistoryCriteria()
                        pageOffset = 0
                        selected = null
                        lookupMessage = null
                    },
                ) { Text("条件クリア") }
                OutlinedButton(onClick = onRefresh) { Text("更新") }
                OutlinedButton(onClick = onOpenPrintQueue) { Text("印刷キュー") }
            }

            when {
                contextLocked && requestedContext != null -> Text(
                    "営業日 ${requestedContext.businessDate} / セッションNo.${requestedContext.businessSessionId} の保存済み属性で固定DB検索中です。",
                    color = BusinessLookupGreen,
                    fontWeight = FontWeight.Bold,
                )
                !validation.valid -> Text(validation.message.orEmpty(), color = BusinessLookupDanger, fontWeight = FontWeight.Bold)
                !lookupMessage.isNullOrBlank() -> Text(lookupMessage.orEmpty(), color = BusinessLookupDanger, fontWeight = FontWeight.Bold)
                appliedCriteria.businessDateFrom.isNotBlank() || appliedCriteria.businessDateTo.isNotBlank() -> Text(
                    "営業日でDB検索中。売上時刻が0時を過ぎても、保存済みbusiness_dateに従います。",
                    color = BusinessLookupGreen,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Row(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                modifier = Modifier.weight(1.2f).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, BusinessLookupBorder),
            ) {
                if (page.records.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("条件に一致する売上はありません", color = Color.Gray)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
                        items(page.records, key = { it.summary.id }) { record ->
                            val sale = record.summary
                            val reversalTrace = pageReversalTraces[sale.id] ?: SaleReversalTraceV135.ACTIVE
                            val selectedRow = selected?.summary?.id == sale.id
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(if (selectedRow) BusinessLookupPaleBlue else Color.Transparent)
                                    .clickable {
                                        selected = record
                                        lookupMessage = null
                                    }
                                    .padding(horizontal = 8.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("#${sale.id}", Modifier.width(70.dp), fontWeight = FontWeight.Bold)
                                Text(
                                    record.businessDate ?: "営業日未記録",
                                    Modifier.width(115.dp),
                                    color = if (record.businessDate == null) BusinessLookupDanger else BusinessLookupNavy,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(businessLookupDateTime(sale.createdAt), Modifier.width(145.dp), fontSize = 12.sp)
                                Text(sale.operatorName, Modifier.width(90.dp), maxLines = 1)
                                Text(sale.paymentLabel, Modifier.weight(1f), maxLines = 1)
                                if (reversalTrace.hasReversal) {
                                    Text(
                                        reversalTrace.state.displayLabel,
                                        Modifier.width(118.dp),
                                        color = BusinessLookupDanger,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.End,
                                        maxLines = 1,
                                    )
                                }
                                Text(businessLookupYen(sale.totalAmount), Modifier.width(115.dp), textAlign = TextAlign.End, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.width(410.dp).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, BusinessLookupBorder),
            ) {
                Column(Modifier.fillMaxSize().padding(14.dp)) {
                    val record = selected
                    if (record == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("売上を選択してください", color = Color.Gray, fontSize = 20.sp)
                        }
                    } else {
                        val sale = record.summary
                        Text("売上 #${sale.id}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = BusinessLookupNavy)
                        Text("営業日 ${record.businessDate ?: "未記録"}", color = if (record.businessDate == null) BusinessLookupDanger else BusinessLookupNavy, fontWeight = FontWeight.Bold)
                        Text("営業セッション ${record.businessSessionId?.let { "No.$it" } ?: "未記録"}")
                        Text("売上時刻 ${businessLookupDateTime(sale.createdAt)}", color = Color.Gray)
                        Text("担当 ${sale.operatorName} / ${sale.paymentLabel}", color = Color.Gray)
                        if (selectedReversalTrace.hasReversal) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                selectedReversalTrace.state.displayLabel,
                                color = BusinessLookupDanger,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            selectedReversalTrace.references.forEach { reference ->
                                Text(
                                    "反対取引 ${reference.type.displayName} No.${reference.reversalId} / " +
                                        "-${businessLookupYen(reference.grossAmount)} / " +
                                        "訂正営業日 ${reference.businessDate ?: "未記録"}",
                                    color = BusinessLookupDanger,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        BusinessDateLookupAmountRow("合計", sale.totalAmount, true)
                        BusinessDateLookupAmountRow("消費税", sale.taxAmount)
                        BusinessDateLookupAmountRow("お釣り", sale.changeAmount)
                        Spacer(Modifier.height(8.dp))
                        Text("明細", fontWeight = FontWeight.Bold, color = BusinessLookupNavy)
                        if (selectedDetail == null) {
                            Text("売上明細を取得できません", color = BusinessLookupDanger)
                            Spacer(Modifier.weight(1f))
                        } else {
                            LazyColumn(Modifier.weight(1f)) {
                                items(selectedDetail.items, key = { "${it.product.id}:${it.note}:${it.unitPrice}" }) { item ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                        Text("${item.product.name} × ${item.quantity}", Modifier.weight(1f), maxLines = 1)
                                        Text(businessLookupYen(item.baseAmount - item.discountAmount), fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { onOpenReceipt(sale.id) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                        ) { Text("通常レシート確認・再印字") }
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = { onOpenVoucher(sale.id) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                        ) { Text("この売上で領収書発行") }
                        if (canReverse) {
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(
                                onClick = { onOpenReversal(sale.id) },
                                enabled = !selectedReversalTrace.blocksFurtherReversal,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                            ) {
                                Text(
                                    if (selectedReversalTrace.blocksFurtherReversal) {
                                        "全量処理済み（再返品・取消不可）"
                                    } else {
                                        "この売上を返品・取消"
                                    },
                                    color = BusinessLookupDanger,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().height(68.dp).background(Color.White).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onClose, modifier = Modifier.width(220.dp).fillMaxHeight()) {
                Text("レジ管理へ戻る", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            Text("営業日検索は読み取り専用です（売上データを更新しません）", color = BusinessLookupGreen, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BusinessDateLookupAmountRow(label: String, value: Long, emphasized: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = if (emphasized) BusinessLookupNavy else Color.DarkGray)
        Text(
            businessLookupYen(value),
            fontSize = if (emphasized) 20.sp else 16.sp,
            fontWeight = FontWeight.Bold,
            color = if (emphasized) BusinessLookupNavy else Color.Black,
        )
    }
}

@Composable
private fun BusinessDateLookupHeader() {
    Row(
        Modifier.fillMaxWidth().height(58.dp).background(BusinessLookupNavy).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("つぐレジ", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(20.dp))
        Text("SCR-415  営業日別 売上検索", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text("SQLite直接検索 / business_date基準", color = Color.White)
    }
}

@Composable
private fun BusinessDateSalesLookupDenied(onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("売上検索を利用できません", color = BusinessLookupDanger, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("売上確認権限がないか、ログインセッションが失効しています。")
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onClose) { Text("戻る") }
    }
}

private fun businessLookupYen(value: Long): String = NumberFormat.getCurrencyInstance(Locale.JAPAN).format(value)

private fun businessLookupDateTime(value: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(Date(value))