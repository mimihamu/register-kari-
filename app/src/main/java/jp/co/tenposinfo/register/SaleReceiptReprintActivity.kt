package jp.co.tenposinfo.register

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private val SaleReceiptNavy = Color(0xFF173F6B)
private val SaleReceiptBlue = Color(0xFF1976B9)
private val SaleReceiptBackground = Color(0xFFF4F7FA)
private val SaleReceiptBorder = Color(0xFFD5DEE7)
private val SaleReceiptDanger = Color(0xFFC62828)
private val SaleReceiptGreen = Color(0xFF2E7D32)
private val SaleReceiptWarning = Color(0xFFFFF4D9)
private val SaleReceiptPaleBlue = Color(0xFFEAF3FA)

/**
 * v0.68 売上No.指定の通常レシート確認・再印字。
 *
 * 売上コンテキストを固定したまま、再印字要求をrequest UUIDで冪等化し、
 * print_jobと追記専用監査履歴を同一SQLiteトランザクションで作成する。
 */
class SaleReceiptReprintActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureRegisterSystemBars(window)
        val requestedSaleId = SaleReceiptNavigation.requestedSaleId(intent)
        setContent {
            MaterialTheme {
                SaleReceiptReprintRoute(
                    requestedSaleId = requestedSaleId,
                    onClose = { finish() },
                )
            }
        }
    }
}

@Composable
private fun SaleReceiptReprintRoute(
    requestedSaleId: Long?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val database = remember { RegisterDatabase(appContext) }
    val auditStore = remember { SaleReceiptReprintAuditStore(appContext) }
    var operator by remember { mutableStateOf(OperatorSessionRegistry.current(appContext)) }
    var refreshEpoch by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmingReprint by remember { mutableStateOf(false) }
    var pendingRequestId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            auditStore.close()
            database.close()
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(5_000L)
            operator = OperatorSessionRegistry.current(appContext)
            refreshEpoch++
        }
    }

    Surface(Modifier.fillMaxSize(), color = SaleReceiptBackground) {
        val current = operator
        when {
            current == null || !current.allows(RegisterPermission.VIEW_SALES) -> {
                SaleReceiptDenied(onClose)
            }
            requestedSaleId == null -> {
                SaleReceiptUnavailable(
                    title = "対象売上が指定されていません",
                    message = "売上一覧または営業日別売上検索から対象売上を指定して開いてください。",
                    onClose = onClose,
                )
            }
            else -> {
                OperatorSessionRegistry.touch(appContext)
                val detail = remember(requestedSaleId, refreshEpoch) {
                    database.loadSaleDetail(requestedSaleId)
                }
                if (detail == null) {
                    SaleReceiptUnavailable(
                        title = "売上No.$requestedSaleId は見つかりません",
                        message = "別売上へ自動で切り替えません。元画面へ戻って対象売上を確認してください。",
                        onClose = onClose,
                    )
                } else {
                    val history = remember(requestedSaleId, refreshEpoch) {
                        auditStore.listForSale(requestedSaleId, limit = 20)
                    }
                    SaleReceiptReprintScreen(
                        detail = detail,
                        paper = PrinterPaperSettingPolicy.currentPaper(appContext),
                        history = history,
                        message = message,
                        confirmingReprint = confirmingReprint,
                        onRequestReprint = {
                            pendingRequestId = UUID.randomUUID().toString()
                            confirmingReprint = true
                            message = null
                        },
                        onCancelReprint = {
                            pendingRequestId = null
                            confirmingReprint = false
                            message = null
                        },
                        onConfirmReprint = {
                            val requestId = pendingRequestId ?: UUID.randomUUID().toString().also {
                                pendingRequestId = it
                            }
                            runCatching {
                                auditStore.request(
                                    saleId = detail.summary.id,
                                    operatorName = current.name,
                                    requestId = requestId,
                                )
                            }.onSuccess { result ->
                                AutomaticPrintScheduler.enqueueNow(appContext)
                                confirmingReprint = false
                                pendingRequestId = null
                                message = if (result.newlyCreated) {
                                    "再印字要求を記録し、印刷job No.${result.record.printJobId}をキューへ登録しました"
                                } else {
                                    "同じ再印字要求は二重登録せず、既存job No.${result.record.printJobId}を使用します"
                                }
                                refreshEpoch++
                            }.onFailure { error ->
                                confirmingReprint = false
                                pendingRequestId = null
                                message = error.message ?: "再印字を登録できませんでした"
                                refreshEpoch++
                            }
                        },
                        onOpenQueue = {
                            context.startActivity(Intent(context, UnifiedPrintQueueActivity::class.java))
                        },
                        onClose = onClose,
                    )
                }
            }
        }
    }
}

@Composable
private fun SaleReceiptReprintScreen(
    detail: SaleDetailRecord,
    paper: ReceiptPaper,
    history: List<SaleReceiptReprintRequestRecord>,
    message: String?,
    confirmingReprint: Boolean,
    onRequestReprint: () -> Unit,
    onCancelReprint: () -> Unit,
    onConfirmReprint: () -> Unit,
    onOpenQueue: () -> Unit,
    onClose: () -> Unit,
) {
    val receiptData = ReceiptFactory.fromSale(detail, reprint = detail.summary.printCount > 0)
    val receiptText = ReceiptRenderer.render(receiptData, paper)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(58.dp).background(SaleReceiptNavy).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("つぐレジ", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(20.dp))
            Text("SCR-647  売上指定 レシート確認・再印字", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("売上No.${detail.summary.id} 固定", color = Color.White, fontWeight = FontWeight.Bold)
        }

        Row(
            Modifier.weight(1f).fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, SaleReceiptBorder),
            ) {
                Column(Modifier.fillMaxSize().padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "売上No.${detail.summary.id} / ${paper.widthMm}mm",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = SaleReceiptNavy,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "完了印字 ${detail.summary.printCount}回 / 再印字要求 ${history.size}件",
                            color = Color.Gray,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color.White, RoundedCornerShape(8.dp))
                            .padding(14.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            receiptText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = if (paper == ReceiptPaper.MM58) 14.sp else 15.sp,
                            lineHeight = 20.sp,
                        )
                    }
                }
            }

            Card(
                modifier = Modifier.width(400.dp).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, SaleReceiptBorder),
            ) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Text("再印字操作", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SaleReceiptNavy)
                    Spacer(Modifier.height(8.dp))
                    Text("対象売上は売上No.${detail.summary.id}に固定されています。別売上へ自動切替しません。")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "再印字要求とprint_jobは同一トランザクションで追記し、request UUIDで二重登録を防止します。",
                        color = SaleReceiptGreen,
                        fontWeight = FontWeight.Bold,
                    )
                    if (!message.isNullOrBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            message,
                            color = if (message.contains("登録") || message.contains("二重登録せず")) SaleReceiptGreen else SaleReceiptDanger,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    if (confirmingReprint) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SaleReceiptWarning),
                            border = BorderStroke(1.dp, Color(0xFFFFB300)),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text("再印字内容を確認", fontWeight = FontWeight.Bold, color = SaleReceiptNavy)
                                Text("売上No.${detail.summary.id} を1枚、現在の${paper.widthMm}mm設定で監査記録＋キュー登録します。")
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = onConfirmReprint,
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = SaleReceiptBlue),
                                ) { Text("再印字を確定") }
                                Spacer(Modifier.height(6.dp))
                                OutlinedButton(onClick = onCancelReprint, modifier = Modifier.fillMaxWidth().height(44.dp)) {
                                    Text("取消")
                                }
                            }
                        }
                    } else {
                        Button(
                            onClick = onRequestReprint,
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SaleReceiptBlue),
                        ) { Text("再印字を確認") }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onOpenQueue, modifier = Modifier.fillMaxWidth().height(46.dp)) {
                        Text("統合印刷キューを開く")
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("再印字要求履歴（追記専用）", color = SaleReceiptNavy, fontWeight = FontWeight.Bold)
                    if (history.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("再印字要求はありません", color = Color.Gray)
                        }
                    } else {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                            items(history, key = { it.id }) { record ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(SaleReceiptPaleBlue, RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                ) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            saleReceiptHistoryDate(record.requestedAt),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(record.printStatus.name, fontSize = 12.sp, color = saleReceiptStatusColor(record.printStatus))
                                    }
                                    Text(
                                        "${record.operatorName} / ${record.paperWidthMm}mm / job No.${record.printJobId} / 試行${record.attemptCount}",
                                        fontSize = 12.sp,
                                    )
                                    if (!record.lastError.isNullOrBlank()) {
                                        Text(record.lastError.take(120), color = SaleReceiptDanger, fontSize = 11.sp)
                                    }
                                }
                                Spacer(Modifier.height(5.dp))
                            }
                        }
                    }
                    Text(
                        "FAILEDは自動再送せず、統合印刷キューから安全に操作します。監査履歴は削除しません。",
                        color = SaleReceiptDanger,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().height(68.dp).background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onClose, modifier = Modifier.width(220.dp).fillMaxHeight()) {
                Text("元画面へ戻る", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            Text(
                "構造化売上からプレビュー / 再印字要求は冪等・追記専用監査",
                color = SaleReceiptGreen,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun SaleReceiptDenied(onClose: () -> Unit) {
    SaleReceiptUnavailable(
        title = "レシート確認を利用できません",
        message = "売上確認権限がないか、ログインセッションが失効しています。",
        onClose = onClose,
    )
}

@Composable
private fun SaleReceiptUnavailable(
    title: String,
    message: String,
    onClose: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = SaleReceiptDanger, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(message, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onClose) { Text("戻る") }
    }
}

private fun saleReceiptHistoryDate(epochMillis: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date(epochMillis))

private fun saleReceiptStatusColor(status: PrintJobStatus): Color = when (status) {
    PrintJobStatus.COMPLETED -> SaleReceiptGreen
    PrintJobStatus.FAILED -> SaleReceiptDanger
    PrintJobStatus.DISCARDED -> Color.Gray
    else -> SaleReceiptNavy
}
