package jp.co.tenposinfo.register

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.util.Locale

private val LedgerNavy = Color(0xFF173F6B)
private val LedgerBlue = Color(0xFF1976B9)
private val LedgerGreen = Color(0xFF2E7D32)
private val LedgerOrange = Color(0xFFEF6C00)
private val LedgerRed = Color(0xFFC62828)
private val LedgerBackground = Color(0xFFF4F7FA)
private val LedgerBorder = Color(0xFFD5DEE7)
private val LedgerSelected = Color(0xFFEAF3FA)
private val LedgerMuted = Color(0xFF607D8B)
private val LedgerPaleYellow = Color(0xFFFFF4D9)

class ReceiptVoucherLedgerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureRegisterSystemBars(window)
        val requestedSaleId = ReceiptVoucherNavigation.requestedSaleId(intent)
        setContent {
            MaterialTheme {
                ReceiptVoucherLedgerRoute(
                    requestedSaleId = requestedSaleId,
                    onOpenPrintQueue = { startActivity(Intent(this, UnifiedPrintQueueActivity::class.java)) },
                    onOpenIssuance = { saleId -> startActivity(ReceiptVoucherNavigation.issuanceIntent(this, saleId)) },
                    onClose = { finish() },
                )
            }
        }
    }
}

@Composable
private fun ReceiptVoucherLedgerRoute(
    requestedSaleId: Long?,
    onOpenPrintQueue: () -> Unit,
    onOpenIssuance: (Long?) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val operator = remember { OperatorSessionRegistry.current(context.applicationContext) }
    val store = remember { ReceiptVoucherOperationsStore(context.applicationContext) }
    var revision by remember { mutableIntStateOf(0) }
    var criteria by remember { mutableStateOf(ReceiptVoucherLedgerCriteria()) }
    var selectedId by remember { mutableStateOf<Long?>(null) }

    DisposableEffect(Unit) {
        onDispose { store.close() }
    }

    Surface(Modifier.fillMaxSize(), color = LedgerBackground) {
        if (operator == null || !operator.allows(RegisterPermission.VIEW_SALES)) {
            ReceiptVoucherLedgerDenied(onClose)
            return@Surface
        }
        OperatorSessionRegistry.touch(context.applicationContext)
        val allEntries = remember(revision) { store.listLedger() }
        val entries = remember(allEntries, criteria) { ReceiptVoucherLedgerPolicy.filter(allEntries, criteria) }
        val summary = remember(allEntries) { ReceiptVoucherLedgerSummary.from(allEntries) }
        val contextSelectedId = requestedSaleId?.let { saleId ->
            allEntries.firstOrNull { it.receipt.saleId == saleId }?.receipt?.id
        }
        val activeSelectedId = selectedId ?: contextSelectedId
        val selected = allEntries.firstOrNull { it.receipt.id == activeSelectedId }

        Column(Modifier.fillMaxSize()) {
            ReceiptVoucherLedgerHeader(summary)
            ReceiptVoucherLedgerFilters(
                criteria = criteria,
                onCriteriaChange = {
                    criteria = it
                    if (selectedId != null && allEntries.none { entry -> entry.receipt.id == selectedId }) selectedId = null
                },
                onRefresh = { revision++ },
            )
            if (requestedSaleId != null) {
                Text(
                    "売上No.$requestedSaleId の領収書コンテキストで開いています。台帳の他売上も確認できます。",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    color = LedgerBlue,
                    fontWeight = FontWeight.Bold,
                )
            }
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                val stacked = maxWidth < 1_080.dp || maxHeight < 620.dp
                if (stacked) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ReceiptVoucherLedgerList(
                            modifier = Modifier.fillMaxWidth().height(520.dp),
                            entries = entries,
                            selectedId = activeSelectedId,
                            onSelect = { selectedId = it.receipt.id },
                        )
                        ReceiptVoucherLedgerDetail(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 620.dp),
                            entry = selected,
                            onOpenPrintQueue = onOpenPrintQueue,
                            onOpenIssuance = { saleId -> onOpenIssuance(saleId) },
                        )
                    }
                } else {
                    Row(
                        Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ReceiptVoucherLedgerList(
                            modifier = Modifier.weight(0.46f).fillMaxHeight(),
                            entries = entries,
                            selectedId = activeSelectedId,
                            onSelect = { selectedId = it.receipt.id },
                        )
                        ReceiptVoucherLedgerDetail(
                            modifier = Modifier.weight(0.54f).fillMaxHeight(),
                            entry = selected,
                            onOpenPrintQueue = onOpenPrintQueue,
                            onOpenIssuance = { saleId -> onOpenIssuance(saleId) },
                        )
                    }
                }
            }
            ReceiptVoucherLedgerFooter(
                onOpenIssuance = { onOpenIssuance(requestedSaleId) },
                onOpenPrintQueue = onOpenPrintQueue,
                onClose = onClose,
            )
        }
    }
}

@Composable
private fun ReceiptVoucherLedgerHeader(summary: ReceiptVoucherLedgerSummary) {
    Row(
        Modifier.fillMaxWidth().height(62.dp).background(LedgerNavy).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("つぐレジ", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(22.dp))
        Text("領収書運用台帳", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(
            "領収書 ${summary.receiptCount}枚　要対応 ${summary.actionRequiredReceipts + summary.missingPrintJobs}枚　再発行 ${summary.reprintEvents}回",
            color = Color.White,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ReceiptVoucherLedgerFilters(
    criteria: ReceiptVoucherLedgerCriteria,
    onCriteriaChange: (ReceiptVoucherLedgerCriteria) -> Unit,
    onRefresh: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, LedgerBorder),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReceiptVoucherLedgerFilter.entries.forEach { candidate ->
                    OutlinedButton(
                        onClick = { onCriteriaChange(criteria.copy(filter = candidate)) },
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        border = BorderStroke(
                            if (criteria.filter == candidate) 2.dp else 1.dp,
                            if (criteria.filter == candidate) LedgerBlue else LedgerBorder,
                        ),
                    ) {
                        Text(candidate.displayName, fontWeight = if (criteria.filter == candidate) FontWeight.Bold else FontWeight.Normal)
                    }
                }
                Button(
                    onClick = onRefresh,
                    colors = ButtonDefaults.buttonColors(containerColor = LedgerBlue),
                    modifier = Modifier.heightIn(min = 44.dp),
                ) { Text("更新") }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = criteria.query,
                onValueChange = { onCriteriaChange(criteria.copy(query = it.take(80))) },
                label = { Text("検索：領収書No.・売上No.・宛名・但し書き・担当・Job・エラー") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ReceiptVoucherLedgerList(
    modifier: Modifier,
    entries: List<ReceiptVoucherLedgerEntry>,
    selectedId: Long?,
    onSelect: (ReceiptVoucherLedgerEntry) -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, LedgerBorder),
    ) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Text("領収書 ${entries.size}件", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = LedgerNavy)
            Spacer(Modifier.height(6.dp))
            if (entries.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("条件に一致する領収書はありません", color = LedgerMuted)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(entries, key = { it.receipt.id }) { entry ->
                        val selected = entry.receipt.id == selectedId
                        val statusColor = receiptVoucherLedgerStatusColor(entry)
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { onSelect(entry) },
                            colors = CardDefaults.cardColors(containerColor = if (selected) LedgerSelected else Color.White),
                            border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) LedgerBlue else LedgerBorder),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(9.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "R${entry.receipt.id} / 売上No.${entry.receipt.saleId}",
                                        modifier = Modifier.weight(1f),
                                        fontWeight = FontWeight.Bold,
                                        color = LedgerNavy,
                                    )
                                    Text(
                                        ReceiptVoucherLedgerPolicy.latestStatusLabel(entry),
                                        color = statusColor,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(entry.receipt.addressee, fontWeight = FontWeight.Medium)
                                        Text(entry.receipt.purpose, fontSize = 12.sp, color = LedgerMuted)
                                    }
                                    Text(ledgerYen(entry.receipt.amount), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                }
                                Text(
                                    "印刷Job ${entry.printEvents.size}件 / 再発行 ${entry.reprintCount}回 / ${ReceiptVoucherOperationsTimeFormatter.format(entry.receipt.createdAt)}",
                                    fontSize = 11.sp,
                                    color = LedgerMuted,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReceiptVoucherLedgerDetail(
    modifier: Modifier,
    entry: ReceiptVoucherLedgerEntry?,
    onOpenPrintQueue: () -> Unit,
    onOpenIssuance: (Long) -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, LedgerBorder),
    ) {
        if (entry == null) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("領収書を選択すると印刷履歴を確認できます", color = LedgerMuted)
            }
            return@Card
        }

        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Text("領収書No.R${entry.receipt.id}", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = LedgerNavy)
            Spacer(Modifier.height(6.dp))
            ReceiptVoucherLedgerValue("元売上No.", entry.receipt.saleId.toString())
            ReceiptVoucherLedgerValue("金額", ledgerYen(entry.receipt.amount))
            ReceiptVoucherLedgerValue("宛名", entry.receipt.addressee)
            ReceiptVoucherLedgerValue("但し書き", entry.receipt.purpose)
            ReceiptVoucherLedgerValue("発行担当", entry.receipt.operatorName)
            ReceiptVoucherLedgerValue("発行日時", ReceiptVoucherOperationsTimeFormatter.format(entry.receipt.createdAt))
            if (entry.receipt.sequenceCount > 1) {
                ReceiptVoucherLedgerValue("一括発行", "${entry.receipt.sequenceNo}/${entry.receipt.sequenceCount}")
            }
            ReceiptVoucherLedgerValue("再発行回数", "${entry.reprintCount}回")
            Spacer(Modifier.height(8.dp))

            if (entry.actionRequired || entry.printEvents.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = LedgerPaleYellow),
                    border = BorderStroke(2.dp, LedgerOrange),
                ) {
                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        Text("印刷状態の確認が必要です", color = LedgerRed, fontWeight = FontWeight.Bold)
                        Text(
                            if (entry.printEvents.isEmpty())
                                "領収書履歴に対応する印刷ジョブが見つかりません。統合印刷キューと監査履歴を確認してください。"
                            else
                                "再試行・破棄・強制印刷は二重印刷防止のため統合印刷キューから実行してください。",
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Text("印刷イベント", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = LedgerNavy)
            Spacer(Modifier.height(4.dp))
            if (entry.printEvents.isEmpty()) {
                Text("印刷イベントなし", color = LedgerRed, fontWeight = FontWeight.Bold)
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(entry.printEvents.sortedByDescending { it.createdAt }, key = { it.jobId }) { event ->
                        ReceiptVoucherPrintEventCard(event)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onOpenIssuance(entry.receipt.saleId) },
                    modifier = Modifier.weight(1f).heightIn(min = 50.dp),
                ) {
                    Text("この売上で追加発行", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onOpenPrintQueue,
                    modifier = Modifier.weight(1f).heightIn(min = 50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LedgerBlue),
                ) {
                    Text("統合印刷キューで確認・対応", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ReceiptVoucherPrintEventCard(event: ReceiptVoucherPrintEventRecord) {
    val color = printJobStatusColor(event.status)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f)),
        border = BorderStroke(1.dp, color),
    ) {
        Column(Modifier.fillMaxWidth().padding(9.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${event.kind.displayName} / Job.${event.jobId}",
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    ReceiptVoucherLedgerPolicy.printStatusLabel(event.status),
                    color = color,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                "${event.paperWidthMm}mm / 試行 ${event.attemptCount}回 / ${ReceiptVoucherOperationsTimeFormatter.format(event.createdAt)}",
                fontSize = 12.sp,
                color = LedgerMuted,
            )
            if (event.kind == ReceiptVoucherPrintKind.REPRINT) {
                Text(
                    "再発行イベント ${event.reprintEventId ?: "-"} / 担当 ${event.reprintedBy ?: "-"}",
                    fontSize = 12.sp,
                    color = LedgerMuted,
                )
            }
            if (!event.lastError.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${event.failureCategory.displayName}: ${event.lastError}",
                    color = LedgerRed,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun ReceiptVoucherLedgerValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Text(label, modifier = Modifier.width(105.dp), color = LedgerMuted)
        Text(value, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ReceiptVoucherLedgerFooter(
    onOpenIssuance: () -> Unit,
    onOpenPrintQueue: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).background(Color.White).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("履歴は読取専用です。再試行などの印刷操作は統合印刷キューへ一本化しています。", modifier = Modifier.weight(1f), color = LedgerMuted, fontSize = 12.sp)
        OutlinedButton(onClick = onOpenIssuance, modifier = Modifier.heightIn(min = 46.dp)) { Text("発行・再発行") }
        OutlinedButton(onClick = onOpenPrintQueue, modifier = Modifier.heightIn(min = 46.dp)) { Text("統合印刷キュー") }
        Button(onClick = onClose, modifier = Modifier.heightIn(min = 46.dp), colors = ButtonDefaults.buttonColors(containerColor = LedgerBlue)) {
            Text("戻る")
        }
    }
}

@Composable
private fun ReceiptVoucherLedgerDenied(onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("領収書運用台帳を利用できません", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = LedgerRed)
        Spacer(Modifier.height(10.dp))
        Text("売上確認権限がないか、ログインセッションが失効しています。", textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onClose, colors = ButtonDefaults.buttonColors(containerColor = LedgerBlue)) { Text("戻る") }
    }
}

private fun receiptVoucherLedgerStatusColor(entry: ReceiptVoucherLedgerEntry): Color = when {
    entry.printEvents.isEmpty() -> LedgerRed
    entry.actionRequired -> LedgerRed
    entry.latestEvent?.status == PrintJobStatus.COMPLETED -> LedgerGreen
    entry.latestEvent?.status == PrintJobStatus.DISCARDED -> LedgerMuted
    else -> LedgerOrange
}

private fun printJobStatusColor(status: PrintJobStatus): Color = when (status) {
    PrintJobStatus.COMPLETED -> LedgerGreen
    PrintJobStatus.FAILED -> LedgerRed
    PrintJobStatus.RETRY -> LedgerOrange
    PrintJobStatus.PENDING -> LedgerBlue
    PrintJobStatus.PRINTING -> LedgerBlue
    PrintJobStatus.DISCARDED -> LedgerMuted
}

private fun ledgerYen(value: Long): String = NumberFormat.getCurrencyInstance(Locale.JAPAN).format(value)
