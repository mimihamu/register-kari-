package jp.co.tenposinfo.register

import android.content.Intent
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ReprintLedgerNavy = Color(0xFF173F6B)
private val ReprintLedgerBlue = Color(0xFF1976B9)
private val ReprintLedgerBackground = Color(0xFFF4F7FA)
private val ReprintLedgerBorder = Color(0xFFD5DEE7)
private val ReprintLedgerPaleBlue = Color(0xFFEAF3FA)
private val ReprintLedgerDanger = Color(0xFFC62828)
private val ReprintLedgerGreen = Color(0xFF2E7D32)
private val ReprintLedgerWarning = Color(0xFFFFF4D9)

/** v0.69 通常レシート再印字要求の全売上横断・読み取り専用運用台帳。 */
class SaleReceiptReprintLedgerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureRegisterSystemBars(window)
        setContent {
            MaterialTheme {
                SaleReceiptReprintLedgerRoute(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun SaleReceiptReprintLedgerRoute(onClose: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val store = remember { SaleReceiptReprintOperationsStore(appContext) }
    var operator by remember { mutableStateOf(OperatorSessionRegistry.current(appContext)) }
    var refreshEpoch by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        onDispose { store.close() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(5_000L)
            operator = OperatorSessionRegistry.current(appContext)
            refreshEpoch++
        }
    }

    Surface(Modifier.fillMaxSize(), color = ReprintLedgerBackground) {
        val current = operator
        if (current == null || !current.allows(RegisterPermission.VIEW_SALES)) {
            ReprintLedgerDenied(onClose)
            return@Surface
        }
        OperatorSessionRegistry.touch(appContext)
        val entries = remember(refreshEpoch) { store.list() }
        SaleReceiptReprintLedgerScreen(
            entries = entries,
            onOpenSale = { saleId -> context.startActivity(SaleReceiptNavigation.intent(context, saleId)) },
            onOpenQueue = { context.startActivity(Intent(context, UnifiedPrintQueueActivity::class.java)) },
            onClose = onClose,
        )
    }
}

@Composable
private fun SaleReceiptReprintLedgerScreen(
    entries: List<SaleReceiptReprintLedgerEntry>,
    onOpenSale: (Long) -> Unit,
    onOpenQueue: () -> Unit,
    onClose: () -> Unit,
) {
    var filter by remember { mutableStateOf(SaleReceiptReprintLedgerFilter.ALL) }
    var query by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<Long?>(null) }

    val filtered = SaleReceiptReprintLedgerPolicy.filter(
        entries,
        SaleReceiptReprintLedgerCriteria(filter = filter, query = query),
    )
    val summary = SaleReceiptReprintLedgerSummary.from(entries)
    val selected = filtered.firstOrNull { it.auditId == selectedId }
        ?: filtered.firstOrNull()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(58.dp).background(ReprintLedgerNavy).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("つぐレジ", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(20.dp))
            Text("SCR-648  通常レシート再印字 運用台帳", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("読み取り専用 / 5秒更新", color = Color.White)
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReprintLedgerSummaryCard("全要求", summary.total, ReprintLedgerPaleBlue)
            ReprintLedgerSummaryCard("要対応", summary.actionRequired, if (summary.actionRequired > 0) ReprintLedgerWarning else ReprintLedgerPaleBlue)
            ReprintLedgerSummaryCard("処理中", summary.active, ReprintLedgerPaleBlue)
            ReprintLedgerSummaryCard("完了", summary.completed, Color(0xFFEAF5EC))
            ReprintLedgerSummaryCard("破棄済み", summary.discarded, Color(0xFFECEFF1))
            Spacer(Modifier.weight(1f))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(100) },
                label = { Text("売上No.・job・担当・状態・エラー") },
                singleLine = true,
                modifier = Modifier.width(330.dp),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SaleReceiptReprintLedgerFilter.entries.forEach { item ->
                val active = filter == item
                if (active) {
                    Button(
                        onClick = { filter = item },
                        colors = ButtonDefaults.buttonColors(containerColor = ReprintLedgerBlue),
                    ) { Text(item.displayName) }
                } else {
                    OutlinedButton(onClick = { filter = item }) { Text(item.displayName) }
                }
            }
            Spacer(Modifier.weight(1f))
            Text("表示 ${filtered.size} / ${entries.size}件", color = Color.Gray, modifier = Modifier.align(Alignment.CenterVertically))
        }

        Row(
            Modifier.weight(1f).fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                modifier = Modifier.weight(1.15f).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, ReprintLedgerBorder),
            ) {
                if (filtered.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("条件に一致する再印字要求はありません", color = Color.Gray)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
                        items(filtered, key = { it.auditId }) { entry ->
                            val selectedRow = selected?.auditId == entry.auditId
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(if (selectedRow) ReprintLedgerPaleBlue else Color.Transparent, RoundedCornerShape(6.dp))
                                    .clickable { selectedId = entry.auditId }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(reprintLedgerDate(entry.requestedAt), Modifier.width(132.dp), fontSize = 12.sp)
                                Text("#${entry.saleId}", Modifier.width(70.dp), fontWeight = FontWeight.Bold)
                                Text(entry.operatorName, Modifier.width(100.dp), maxLines = 1)
                                Text("${entry.paperWidthMm}mm", Modifier.width(58.dp))
                                Text("job ${entry.printJobId}", Modifier.width(82.dp), fontSize = 12.sp)
                                Text(
                                    entry.status.name,
                                    Modifier.weight(1f),
                                    color = reprintLedgerStatusColor(entry.status),
                                    fontWeight = FontWeight.Bold,
                                )
                                Text("${entry.attemptCount}回", Modifier.width(45.dp), textAlign = TextAlign.End)
                            }
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.width(420.dp).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, ReprintLedgerBorder),
            ) {
                Column(Modifier.fillMaxSize().padding(14.dp)) {
                    val entry = selected
                    if (entry == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("再印字要求を選択してください", color = Color.Gray)
                        }
                    } else {
                        Text("売上 #${entry.saleId}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ReprintLedgerNavy)
                        Text("要求 ${reprintLedgerDate(entry.requestedAt)}", color = Color.Gray)
                        Text("元売上 ${reprintLedgerDate(entry.saleCreatedAt)}", color = Color.Gray)
                        Spacer(Modifier.height(8.dp))
                        ReprintLedgerDetailRow("売上金額", reprintLedgerYen(entry.saleAmount))
                        ReprintLedgerDetailRow("担当", entry.operatorName)
                        ReprintLedgerDetailRow("用紙幅", "${entry.paperWidthMm}mm")
                        ReprintLedgerDetailRow("監査ID", entry.auditId.toString())
                        ReprintLedgerDetailRow("印刷job", entry.printJobId.toString())
                        ReprintLedgerDetailRow("状態", entry.status.name)
                        ReprintLedgerDetailRow("試行回数", "${entry.attemptCount}回")
                        ReprintLedgerDetailRow("エラー分類", entry.failureCategory.displayName)
                        Spacer(Modifier.height(8.dp))
                        Text("最終エラー", fontWeight = FontWeight.Bold, color = ReprintLedgerNavy)
                        Text(
                            entry.lastError ?: "なし",
                            color = if (entry.lastError.isNullOrBlank()) Color.Gray else ReprintLedgerDanger,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                        OutlinedButton(
                            onClick = { onOpenSale(entry.saleId) },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) { Text("この売上のレシート画面") }
                        Spacer(Modifier.height(6.dp))
                        Button(
                            onClick = onOpenQueue,
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ReprintLedgerBlue),
                        ) { Text("統合印刷キューで確認・対応") }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "この台帳では再試行・破棄・強制印刷・履歴削除を行いません。",
                            color = ReprintLedgerGreen,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                        )
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
            Text("監査履歴は追記専用 / 復旧操作は統合印刷キューへ集約", color = ReprintLedgerGreen, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ReprintLedgerSummaryCard(label: String, count: Int, background: Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = background),
        border = BorderStroke(1.dp, ReprintLedgerBorder),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = ReprintLedgerNavy, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(count.toString(), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (label == "要対応" && count > 0) ReprintLedgerDanger else ReprintLedgerNavy)
        }
    }
}

@Composable
private fun ReprintLedgerDetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.width(100.dp), color = Color.Gray)
        Text(value, Modifier.weight(1f), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ReprintLedgerDenied(onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("再印字運用台帳を利用できません", color = ReprintLedgerDanger, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("売上確認権限がないか、ログインセッションが失効しています。")
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onClose) { Text("戻る") }
    }
}

private fun reprintLedgerDate(epochMillis: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date(epochMillis))

private fun reprintLedgerYen(value: Long): String = NumberFormat.getCurrencyInstance(Locale.JAPAN).format(value)

private fun reprintLedgerStatusColor(status: PrintJobStatus): Color = when (status) {
    PrintJobStatus.COMPLETED -> ReprintLedgerGreen
    PrintJobStatus.FAILED, PrintJobStatus.RETRY -> ReprintLedgerDanger
    PrintJobStatus.DISCARDED -> Color.Gray
    else -> ReprintLedgerNavy
}
