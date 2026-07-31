package jp.co.tenposinfo.register

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

private val AoNavy = Color(0xFF173F6B)
private val AoBlue = Color(0xFF1976B9)
private val AoDanger = Color(0xFFC62828)
private val AoGreen = Color(0xFF2E7D32)
private val AoBackground = Color(0xFFF4F7FA)
private val AoBorder = Color(0xFFD5DEE7)
private val AoPaleBlue = Color(0xFFEAF3FA)
private val AoPaleGreen = Color(0xFFEAF5EC)
private val AoPaleYellow = Color(0xFFFFF4D9)

class AdvancedOperationsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AdvancedOperationsApp(onClose = { finish() })
            }
        }
    }
}

private enum class AdvancedScreen {
    MENU,
    BUSINESS,
    DAILY,
    SETTLEMENT,
    CASH,
    REVERSAL,
    PRINT_QUEUE,
}

@Composable
private fun AdvancedOperationsApp(onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember { AdvancedOperationsStore(context.applicationContext) }
    val registerDatabase = remember { RegisterDatabase(context.applicationContext) }
    val currentOperator = remember { OperatorSessionRegistry.current(context.applicationContext) }
    val permissions = currentOperator?.permissions.orEmpty()
    var screen by remember { mutableStateOf(AdvancedScreen.MENU) }
    var revision by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            store.close()
            registerDatabase.close()
        }
    }

    val session = store.activeSession()
    val summary = store.dailySummary()

    Surface(Modifier.fillMaxSize(), color = AoBackground) {
        when (screen) {
            AdvancedScreen.MENU -> AdvancedMenuScreen(
                session = session,
                summary = summary,
                message = message,
                onBusiness = {
                    if (RegisterPermission.SETTLEMENT in permissions) {
                        message = null
                        screen = AdvancedScreen.BUSINESS
                    } else message = "営業開始・終了の権限がありません"
                },
                onDaily = {
                    if (RegisterPermission.VIEW_SALES in permissions) {
                        message = null
                        screen = AdvancedScreen.DAILY
                    } else message = "売上確認の権限がありません"
                },
                onSettlement = {
                    if (RegisterPermission.SETTLEMENT in permissions) {
                        message = null
                        screen = AdvancedScreen.SETTLEMENT
                    } else message = "点検・精算の権限がありません"
                },
                onCash = {
                    if (RegisterPermission.CASH_MOVEMENT in permissions) {
                        message = null
                        screen = AdvancedScreen.CASH
                    } else message = "入出金の権限がありません"
                },
                onReversal = {
                    if (RegisterPermission.REVERSAL in permissions) {
                        message = null
                        screen = AdvancedScreen.REVERSAL
                    } else message = "返品・取消の権限がありません"
                },
                onPrintQueue = {
                    if (permissions.any {
                            it == RegisterPermission.VIEW_SALES ||
                                it == RegisterPermission.SETTLEMENT ||
                                it == RegisterPermission.REVERSAL
                        }
                    ) {
                        message = null
                        screen = AdvancedScreen.PRINT_QUEUE
                    } else message = "印刷キュー確認の権限がありません"
                },
                onClose = onClose,
            )

            AdvancedScreen.BUSINESS -> BusinessDayScreen(
                session = session,
                history = store.recentSessions(),
                summary = summary,
                revision = revision,
                message = message,
                onStart = { openingCash, operator ->
                    val result = runCatching { store.startBusinessDay(LocalDate.now(), openingCash, operator) }
                    message = result.fold(
                        onSuccess = { "営業を開始しました（No.$it）" },
                        onFailure = { it.message ?: "営業開始に失敗しました" },
                    )
                    if (result.isSuccess) revision++
                },
                onCloseDay = { actualCash, operator, pin ->
                    val result = runCatching {
                        require(OperatorSessionRegistry.verifyManagerPin(context.applicationContext, pin)) { "責任者PINが違います" }
                        store.endBusinessDay(actualCash, operator)
                    }
                    message = result.fold(
                        onSuccess = { "営業を終了しました（No.$it）" },
                        onFailure = { it.message ?: "営業終了に失敗しました" },
                    )
                    if (result.isSuccess) revision++
                },
                onBack = { screen = AdvancedScreen.MENU },
            )

            AdvancedScreen.DAILY -> AdvancedDailyScreen(
                session = session,
                summary = summary,
                onBack = { screen = AdvancedScreen.MENU },
            )

            AdvancedScreen.SETTLEMENT -> AdvancedSettlementScreen(
                session = session,
                summary = summary,
                history = store.recentSettlements(),
                revision = revision,
                message = message,
                onExecute = { type, actualCash, operator, pin, paperWidth ->
                    val result = runCatching {
                        if (type == SettlementReportType.Z_SETTLEMENT) {
                            require(OperatorSessionRegistry.verifyManagerPin(context.applicationContext, pin)) { "責任者PINが違います" }
                        }
                        store.recordSettlement(type, actualCash, operator, paperWidth).also { AutomaticPrintScheduler.enqueueNow(context.applicationContext); DriveOutboxScheduler.enqueueNow(context.applicationContext) }
                    }
                    message = result.fold(
                        onSuccess = { "${type.displayName}を保存し、印刷キューへ登録しました（No.${it.reportId}）" },
                        onFailure = { it.message ?: "点検・精算に失敗しました" },
                    )
                    if (result.isSuccess) revision++
                    result.getOrNull()?.previewText
                },
                onBack = { screen = AdvancedScreen.MENU },
            )

            AdvancedScreen.CASH -> AdvancedCashScreen(
                session = session,
                records = store.recentCashMovements(),
                revision = revision,
                message = message,
                onSave = { type, amount, reason, operator ->
                    val result = runCatching { store.recordCashMovement(type, amount, reason, operator) }
                    message = result.fold(
                        onSuccess = { "${type.displayName}を保存しました（No.$it）" },
                        onFailure = { it.message ?: "入出金の保存に失敗しました" },
                    )
                    if (result.isSuccess) revision++
                },
                onBack = { screen = AdvancedScreen.MENU },
            )

            AdvancedScreen.REVERSAL -> AdvancedReversalScreen(
                session = session,
                sales = registerDatabase.listSales(200),
                loadLines = store::loadReturnableLines,
                reversals = store.recentReversals(),
                revision = revision,
                message = message,
                onExecute = { saleId, type, quantities, reason, operator, pin, paperWidth ->
                    val result = runCatching {
                        require(OperatorSessionRegistry.verifyManagerPin(context.applicationContext, pin)) { "責任者PINが違います" }
                        store.createReversal(saleId, type, quantities, reason, operator, paperWidth).also { AutomaticPrintScheduler.enqueueNow(context.applicationContext); DriveOutboxScheduler.enqueueNow(context.applicationContext) }
                    }
                    message = result.fold(
                        onSuccess = { "${type.displayName}を保存し、レシートを印刷キューへ登録しました（No.${it.reversalId}）" },
                        onFailure = { it.message ?: "返品・取消に失敗しました" },
                    )
                    if (result.isSuccess) revision++
                    result.getOrNull()?.previewText
                },
                onBack = { screen = AdvancedScreen.MENU },
            )

            AdvancedScreen.PRINT_QUEUE -> DocumentPrintQueueScreen(
                jobs = store.listDocumentPrintJobs(),
                revision = revision,
                message = message,
                onPrint = { jobId ->
                    val result = store.processDocumentPrint(jobId, MemoryPrinterGateway())
                    message = result.fold(
                        onSuccess = { "テスト印刷を完了しました（Job.$jobId）" },
                        onFailure = { it.message ?: "印刷に失敗しました" },
                    )
                    revision++
                },
                onRetry = { jobId ->
                    store.retryDocumentPrint(jobId)
                    message = "再試行待ちへ戻しました（Job.$jobId）"
                    revision++
                },
                onBack = { screen = AdvancedScreen.MENU },
            )
        }
    }
}

@Composable
private fun AdvancedMenuScreen(
    session: BusinessSessionRecord?,
    summary: AdvancedDailySummary,
    message: String?,
    onBusiness: () -> Unit,
    onDaily: () -> Unit,
    onSettlement: () -> Unit,
    onCash: () -> Unit,
    onReversal: () -> Unit,
    onPrintQueue: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        AoHeader("SCR-700", "レジ管理メニュー")
        Row(Modifier.weight(1f).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AoPanel(Modifier.width(360.dp).fillMaxHeight()) {
                Text("営業状態", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AoNavy)
                Spacer(Modifier.height(12.dp))
                AoAmountRow("営業日", session?.businessDate ?: "未開始")
                AoAmountRow("状態", session?.status?.displayName ?: "営業開始が必要", emphasized = true)
                AoAmountRow("開始釣銭", aoYen(summary.openingCash))
                AoAmountRow("純売上", aoYen(summary.netSales))
                AoAmountRow("現金理論", aoYen(summary.expectedCash))
                AoAmountRow("未印刷", "${summary.pendingPrints}件")
                AoAmountRow("未会計伝票", "${summary.heldTickets}件")
                Spacer(Modifier.height(8.dp))
                Text(
                    when (session?.status) {
                        BusinessSessionStatus.OPEN -> "販売可能です"
                        BusinessSessionStatus.Z_SETTLED -> "販売停止中。営業終了を実行してください"
                        else -> "営業開始と開始釣銭の登録が必要です"
                    },
                    color = if (session?.status == BusinessSessionStatus.OPEN) AoGreen else AoDanger,
                    fontWeight = FontWeight.Bold,
                )
                if (message != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(message, color = messageColor(message))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AoMenuTile("営業開始・終了", "開始釣銭／Z精算後の終了", AoPaleGreen, Modifier.weight(1f), onBusiness)
                    AoMenuTile("当日売上", "売上・支払・現金理論", AoPaleBlue, Modifier.weight(1f), onDaily)
                    AoMenuTile("点検・精算", "X点検／Z精算票", AoPaleYellow, Modifier.weight(1f), onSettlement)
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AoMenuTile("入出金", "入金・出金と監査記録", Color(0xFFEFF4FA), Modifier.weight(1f), onCash)
                    AoMenuTile("返品・取消", "部分返品／全額取消", Color(0xFFFCE8E6), Modifier.weight(1f), onReversal)
                    AoMenuTile("印刷キュー", "取消票・精算票の再試行", Color(0xFFF0EAF8), Modifier.weight(1f), onPrintQueue)
                }
            }
        }
        AoBottomBar("販売へ戻る", onClose)
    }
}

@Composable
private fun BusinessDayScreen(
    session: BusinessSessionRecord?,
    history: List<BusinessSessionRecord>,
    summary: AdvancedDailySummary,
    revision: Int,
    message: String?,
    onStart: (Long, String) -> Unit,
    onCloseDay: (Long, String, String) -> Unit,
    onBack: () -> Unit,
) {
    var openingCash by remember { mutableStateOf("") }
    var actualCash by remember { mutableStateOf("") }
    var operator by remember { mutableStateOf(OperatorSessionRegistry.lastKnownName() ?: "責任者") }
    var pin by remember { mutableStateOf("") }
    @Suppress("UNUSED_VARIABLE") val refresh = revision

    Column(Modifier.fillMaxSize()) {
        AoHeader("SCR-020/520", "営業開始・営業終了")
        Row(Modifier.weight(1f).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AoPanel(Modifier.width(430.dp).fillMaxHeight()) {
                Text("現在の営業日", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = AoNavy)
                Spacer(Modifier.height(10.dp))
                AoAmountRow("営業日", session?.businessDate ?: LocalDate.now().toString())
                AoAmountRow("状態", session?.status?.displayName ?: "未開始", emphasized = true)
                AoAmountRow("開始釣銭", aoYen(session?.openingCash ?: 0))
                AoAmountRow("現金理論", aoYen(summary.expectedCash))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = operator,
                    onValueChange = { operator = it.take(30) },
                    label = { Text("担当者") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (session == null) {
                    Spacer(Modifier.height(8.dp))
                    AoNumericField("開始釣銭", openingCash, { openingCash = it })
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { onStart(openingCash.toLongOrNull() ?: 0L, operator) },
                        modifier = Modifier.fillMaxWidth().height(58.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AoBlue),
                    ) { Text("営業を開始", fontWeight = FontWeight.Bold) }
                } else {
                    Spacer(Modifier.height(8.dp))
                    AoNumericField("営業終了時の現金実査額", actualCash, { actualCash = it })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                        label = { Text("責任者PIN") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { onCloseDay(actualCash.toLongOrNull() ?: summary.expectedCash, operator, pin) },
                        enabled = session.status == BusinessSessionStatus.Z_SETTLED,
                        modifier = Modifier.fillMaxWidth().height(58.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AoDanger),
                    ) { Text("営業を終了", fontWeight = FontWeight.Bold) }
                    if (session.status == BusinessSessionStatus.OPEN) {
                        Spacer(Modifier.height(6.dp))
                        Text("営業終了には先にZ精算が必要です", color = AoDanger)
                    }
                }
                if (message != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(message, color = messageColor(message))
                }
            }
            AoPanel(Modifier.weight(1f).fillMaxHeight()) {
                Text("営業履歴", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = AoNavy)
                Spacer(Modifier.height(8.dp))
                if (history.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("履歴はありません", color = Color.Gray) }
                } else {
                    LazyColumn {
                        items(history) { record ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${record.businessDate}  ${record.status.displayName}", fontWeight = FontWeight.Bold)
                                    Text("開始 ${aoDateTime(record.openedAt)} / ${record.openedBy}", color = Color.Gray)
                                    if (record.closedAt != null) Text("終了 ${aoDateTime(record.closedAt)} / ${record.closedBy}", color = Color.Gray)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("開始 ${aoYen(record.openingCash)}")
                                    if (record.closeVariance != null) Text("過不足 ${aoSignedYen(record.closeVariance)}")
                                }
                            }
                        }
                    }
                }
            }
        }
        AoBottomBar("レジ管理へ戻る", onBack)
    }
}

@Composable
private fun AdvancedDailyScreen(
    session: BusinessSessionRecord?,
    summary: AdvancedDailySummary,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        AoHeader("SCR-510", "当日売上簡易確認")
        Row(Modifier.weight(1f).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            AoPanel(Modifier.weight(1f).fillMaxHeight()) {
                Text("売上速報", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = AoNavy)
                Spacer(Modifier.height(10.dp))
                AoAmountRow("営業日", summary.businessDate)
                AoAmountRow("営業状態", session?.status?.displayName ?: "未開始")
                AoAmountRow("売上総額", aoYen(summary.salesGross))
                AoAmountRow("返品・取消", "-${aoYen(summary.reversalGross)}")
                AoAmountRow("純売上", aoYen(summary.netSales), emphasized = true)
                AoAmountRow("売上件数", "${summary.transactionCount}件")
                AoAmountRow("返品・取消件数", "${summary.reversalCount}件")
            }
            AoPanel(Modifier.weight(1f).fillMaxHeight()) {
                Text("支払別", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = AoNavy)
                Spacer(Modifier.height(10.dp))
                if (summary.paymentTotals.isEmpty()) Text("支払データはありません", color = Color.Gray)
                summary.paymentTotals.forEach { AoAmountRow(aoPaymentLabel(it.method), aoYen(it.amount)) }
            }
            AoPanel(Modifier.weight(1f).fillMaxHeight()) {
                Text("現金・未処理", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = AoNavy)
                Spacer(Modifier.height(10.dp))
                AoAmountRow("開始釣銭", aoYen(summary.openingCash))
                AoAmountRow("入金", aoYen(summary.cashIn))
                AoAmountRow("出金", "-${aoYen(summary.cashOut)}")
                AoAmountRow("現金理論", aoYen(summary.expectedCash), emphasized = true)
                AoAmountRow("未印刷", "${summary.pendingPrints}件")
                AoAmountRow("未会計伝票", "${summary.heldTickets}件")
                AoAmountRow("Z精算", if (summary.settled) "済み" else "未実施")
            }
        }
        AoBottomBar("レジ管理へ戻る", onBack)
    }
}

@Composable
private fun AdvancedSettlementScreen(
    session: BusinessSessionRecord?,
    summary: AdvancedDailySummary,
    history: List<SettlementRecord>,
    revision: Int,
    message: String?,
    onExecute: (SettlementReportType, Long?, String, String, Int) -> String?,
    onBack: () -> Unit,
) {
    var type by remember { mutableStateOf(SettlementReportType.X_INSPECTION) }
    var actualCash by remember { mutableStateOf("") }
    var operator by remember { mutableStateOf(OperatorSessionRegistry.lastKnownName() ?: "責任者") }
    var pin by remember { mutableStateOf("") }
    var paperWidth by remember { mutableStateOf(80) }
    var savedPreview by remember { mutableStateOf<String?>(null) }
    @Suppress("UNUSED_VARIABLE") val refresh = revision
    val actual = actualCash.toLongOrNull() ?: summary.expectedCash
    val previewData = SettlementDocumentData(
        reportId = 0,
        businessDate = summary.businessDate,
        type = type,
        createdAt = System.currentTimeMillis(),
        operatorName = operator,
        salesGross = summary.salesGross,
        reversalGross = summary.reversalGross,
        netSales = summary.netSales,
        openingCash = summary.openingCash,
        cashIn = summary.cashIn,
        cashOut = summary.cashOut,
        expectedCash = summary.expectedCash,
        actualCash = actual,
        variance = actual - summary.expectedCash,
        transactionCount = summary.transactionCount,
        reversalCount = summary.reversalCount,
        pendingPrints = summary.pendingPrints,
        heldTickets = summary.heldTickets,
        paymentTotals = summary.paymentTotals,
    )
    val preview = savedPreview ?: OperationDocumentRenderer.renderSettlement(previewData, ReceiptPaper.fromWidth(paperWidth))

    Column(Modifier.fillMaxSize()) {
        AoHeader("SCR-500", "点検・精算／精算票")
        Row(Modifier.weight(1f).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AoPanel(Modifier.width(360.dp).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AoChoiceButton("X点検", type == SettlementReportType.X_INSPECTION, Modifier.weight(1f)) {
                        type = SettlementReportType.X_INSPECTION; savedPreview = null
                    }
                    AoChoiceButton("Z精算", type == SettlementReportType.Z_SETTLEMENT, Modifier.weight(1f)) {
                        type = SettlementReportType.Z_SETTLEMENT; savedPreview = null
                    }
                }
                Spacer(Modifier.height(8.dp))
                AoNumericField("現金実査額（空欄は理論額）", actualCash) { actualCash = it; savedPreview = null }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(operator, { operator = it.take(30); savedPreview = null }, label = { Text("担当者") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (type == SettlementReportType.Z_SETTLEMENT) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                        label = { Text("責任者PIN") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AoChoiceButton("58mm", paperWidth == 58, Modifier.weight(1f)) { paperWidth = 58; savedPreview = null }
                    AoChoiceButton("80mm", paperWidth == 80, Modifier.weight(1f)) { paperWidth = 80; savedPreview = null }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { savedPreview = onExecute(type, actualCash.toLongOrNull(), operator, pin, paperWidth) },
                    enabled = session?.status == BusinessSessionStatus.OPEN,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (type == SettlementReportType.Z_SETTLEMENT) AoDanger else AoBlue),
                ) { Text("${type.displayName}を確定", fontWeight = FontWeight.Bold) }
                if (session?.status != BusinessSessionStatus.OPEN) Text("営業中のみ実行できます", color = AoDanger)
                if (message != null) Text(message, color = messageColor(message))
            }
            AoPanel(Modifier.weight(1f).fillMaxHeight()) {
                Text("印刷プレビュー", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AoNavy)
                Spacer(Modifier.height(8.dp))
                Text(preview, fontFamily = FontFamily.Monospace, fontSize = 14.sp, modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
            }
            AoPanel(Modifier.width(330.dp).fillMaxHeight()) {
                Text("点検・精算履歴", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AoNavy)
                Spacer(Modifier.height(8.dp))
                LazyColumn {
                    items(history) { record ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Row(Modifier.fillMaxWidth()) {
                                Text(record.type.displayName, fontWeight = FontWeight.Bold, color = AoNavy)
                                Spacer(Modifier.weight(1f))
                                Text(aoDateTime(record.createdAt), color = Color.Gray)
                            }
                            Text("${record.businessDate}  純売上 ${aoYen(record.netSales)}")
                            Text("実査 ${aoYen(record.actualCash)} / 過不足 ${aoSignedYen(record.variance)}", color = Color.Gray)
                        }
                    }
                }
            }
        }
        AoBottomBar("レジ管理へ戻る", onBack)
    }
}

@Composable
private fun AdvancedCashScreen(
    session: BusinessSessionRecord?,
    records: List<CashMovementRecord>,
    revision: Int,
    message: String?,
    onSave: (CashMovementType, Long, String, String) -> Unit,
    onBack: () -> Unit,
) {
    var type by remember { mutableStateOf(CashMovementType.IN) }
    var amount by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var operator by remember { mutableStateOf(OperatorSessionRegistry.lastKnownName() ?: "責任者") }
    @Suppress("UNUSED_VARIABLE") val refresh = revision
    Column(Modifier.fillMaxSize()) {
        AoHeader("SCR-530", "入出金")
        Row(Modifier.weight(1f).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            AoPanel(Modifier.width(430.dp).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AoChoiceButton("入金", type == CashMovementType.IN, Modifier.weight(1f)) { type = CashMovementType.IN }
                    AoChoiceButton("出金", type == CashMovementType.OUT, Modifier.weight(1f)) { type = CashMovementType.OUT }
                }
                Spacer(Modifier.height(8.dp))
                AoNumericField("金額", amount) { amount = it }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(reason, { reason = it.take(100) }, label = { Text("理由（必須）") }, modifier = Modifier.fillMaxWidth().height(100.dp))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(operator, { operator = it.take(30) }, label = { Text("担当者") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        onSave(type, amount.toLongOrNull() ?: 0, reason, operator)
                        amount = ""; reason = ""
                    },
                    enabled = session?.status == BusinessSessionStatus.OPEN,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (type == CashMovementType.IN) AoBlue else AoDanger),
                ) { Text("${type.displayName}を保存", fontWeight = FontWeight.Bold) }
                if (session?.status != BusinessSessionStatus.OPEN) Text("営業中のみ登録できます", color = AoDanger)
                if (message != null) Text(message, color = messageColor(message))
            }
            AoPanel(Modifier.weight(1f).fillMaxHeight()) {
                Text("入出金履歴", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = AoNavy)
                Spacer(Modifier.height(8.dp))
                LazyColumn {
                    items(records) { record ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(record.type.displayName, color = if (record.type == CashMovementType.IN) AoBlue else AoDanger, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(record.reason, fontWeight = FontWeight.Medium)
                                Text("${aoDateTime(record.createdAt)} / ${record.operatorName}", color = Color.Gray)
                            }
                            Text(if (record.type == CashMovementType.IN) "+${aoYen(record.amount)}" else "-${aoYen(record.amount)}", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        AoBottomBar("レジ管理へ戻る", onBack)
    }
}

@Composable
private fun AdvancedReversalScreen(
    session: BusinessSessionRecord?,
    sales: List<SaleSummaryRecord>,
    loadLines: (Long) -> List<ReturnLineRecord>,
    reversals: List<ReversalRecord>,
    revision: Int,
    message: String?,
    onExecute: (Long, ReversalType, Map<Long, Int>, String, String, String, Int) -> String?,
    onBack: () -> Unit,
) {
    var selectedSaleId by remember { mutableStateOf<Long?>(null) }
    var lines by remember { mutableStateOf(emptyList<ReturnLineRecord>()) }
    var quantities by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var type by remember { mutableStateOf(ReversalType.RETURN) }
    var reason by remember { mutableStateOf("") }
    var operator by remember { mutableStateOf(OperatorSessionRegistry.lastKnownName() ?: "責任者") }
    var pin by remember { mutableStateOf("") }
    var paperWidth by remember { mutableStateOf(80) }
    var savedPreview by remember { mutableStateOf<String?>(null) }
    @Suppress("UNUSED_VARIABLE") val refresh = revision

    val selectedSale = sales.firstOrNull { it.id == selectedSaleId }
    val effectiveQuantities = if (type == ReversalType.CANCEL) {
        lines.associate { it.saleItemId to it.remainingQuantity }
    } else {
        quantities
    }
    val previewItems = lines.mapNotNull { line ->
        val quantity = effectiveQuantities[line.saleItemId] ?: 0
        if (quantity <= 0) null else runCatching { line.toReturnItem(quantity) }.getOrNull()
    }
    val refund = if (previewItems.isEmpty()) 0L else TaxEngine.calculate(previewItems).grossAmount

    Column(Modifier.fillMaxSize()) {
        AoHeader("SCR-400/410", "部分返品・取消")
        Row(Modifier.weight(1f).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AoPanel(Modifier.width(340.dp).fillMaxHeight()) {
                Text("元売上", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = AoNavy)
                Spacer(Modifier.height(6.dp))
                LazyColumn {
                    items(sales) { sale ->
                        val selected = sale.id == selectedSaleId
                        Row(
                            Modifier.fillMaxWidth().background(if (selected) AoPaleBlue else Color.Transparent).clickable {
                                selectedSaleId = sale.id
                                lines = loadLines(sale.id)
                                quantities = emptyMap()
                                savedPreview = null
                            }.padding(vertical = 8.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("No.${sale.id}  ${aoDateTime(sale.createdAt)}", fontWeight = FontWeight.Bold)
                                Text("${sale.paymentLabel} / ${sale.operatorName}", color = Color.Gray, fontSize = 13.sp)
                            }
                            Text(aoYen(sale.totalAmount), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            AoPanel(Modifier.weight(1f).fillMaxHeight()) {
                Text("返品商品・数量", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = AoNavy)
                Spacer(Modifier.height(6.dp))
                if (selectedSale == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("元売上を選択してください", color = Color.Gray) }
                } else if (lines.all { it.remainingQuantity == 0 }) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("返品可能な残数はありません", color = AoDanger) }
                } else {
                    LazyColumn(Modifier.weight(1f)) {
                        items(lines) { line ->
                            val quantity = effectiveQuantities[line.saleItemId] ?: 0
                            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${line.productName} [${line.taxSymbol}]", fontWeight = FontWeight.Bold)
                                    Text("販売 ${line.originalQuantity} / 返品済 ${line.returnedQuantity} / 残 ${line.remainingQuantity}", color = Color.Gray)
                                }
                                OutlinedButton(
                                    onClick = {
                                        quantities = quantities + (line.saleItemId to (quantity - 1).coerceAtLeast(0)); savedPreview = null
                                    },
                                    enabled = type == ReversalType.RETURN && quantity > 0,
                                    modifier = Modifier.width(48.dp),
                                ) { Text("−") }
                                Text(quantity.toString(), modifier = Modifier.width(42.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
                                OutlinedButton(
                                    onClick = {
                                        quantities = quantities + (line.saleItemId to (quantity + 1).coerceAtMost(line.remainingQuantity)); savedPreview = null
                                    },
                                    enabled = type == ReversalType.RETURN && quantity < line.remainingQuantity,
                                    modifier = Modifier.width(48.dp),
                                ) { Text("＋") }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { quantities = lines.associate { it.saleItemId to it.remainingQuantity }; savedPreview = null },
                            enabled = type == ReversalType.RETURN,
                            modifier = Modifier.weight(1f),
                        ) { Text("全残数を選択") }
                        OutlinedButton(
                            onClick = { quantities = emptyMap(); savedPreview = null },
                            enabled = type == ReversalType.RETURN,
                            modifier = Modifier.weight(1f),
                        ) { Text("数量クリア") }
                    }
                }
            }
            AoPanel(Modifier.width(390.dp).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AoChoiceButton("返品", type == ReversalType.RETURN, Modifier.weight(1f)) { type = ReversalType.RETURN; savedPreview = null }
                    AoChoiceButton("取消", type == ReversalType.CANCEL, Modifier.weight(1f)) { type = ReversalType.CANCEL; savedPreview = null }
                }
                Spacer(Modifier.height(6.dp))
                AoAmountRow("元売上", selectedSale?.let { "No.${it.id}" } ?: "未選択")
                AoAmountRow("返金予定", aoYen(refund), emphasized = true)
                OutlinedTextField(reason, { reason = it.take(100) }, label = { Text("理由（必須）") }, modifier = Modifier.fillMaxWidth().height(78.dp))
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(operator, { operator = it.take(30) }, label = { Text("担当者") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    pin,
                    { pin = it.filter(Char::isDigit).take(8) },
                    label = { Text("責任者PIN") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AoChoiceButton("58mm", paperWidth == 58, Modifier.weight(1f)) { paperWidth = 58; savedPreview = null }
                    AoChoiceButton("80mm", paperWidth == 80, Modifier.weight(1f)) { paperWidth = 80; savedPreview = null }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        val saleId = selectedSaleId ?: return@Button
                        savedPreview = onExecute(saleId, type, effectiveQuantities, reason, operator, pin, paperWidth)
                        if (savedPreview != null) {
                            lines = loadLines(saleId)
                            quantities = emptyMap()
                            reason = ""
                            pin = ""
                        }
                    },
                    enabled = session?.status == BusinessSessionStatus.OPEN && selectedSale != null && refund > 0,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AoDanger),
                ) { Text("${type.displayName}を確定", fontWeight = FontWeight.Bold) }
                if (message != null) Text(message, color = messageColor(message), fontSize = 13.sp)
                if (savedPreview != null) {
                    Spacer(Modifier.height(6.dp))
                    Text("直前レシートプレビュー", fontWeight = FontWeight.Bold, color = AoNavy)
                    Text(savedPreview!!, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.height(120.dp).verticalScroll(rememberScrollState()))
                }
            }
        }
        AoBottomBar("レジ管理へ戻る", onBack)
    }
}

@Composable
private fun DocumentPrintQueueScreen(
    jobs: List<DocumentPrintJobRecord>,
    revision: Int,
    message: String?,
    onPrint: (Long) -> Unit,
    onRetry: (Long) -> Unit,
    onBack: () -> Unit,
) {
    var selectedId by remember { mutableStateOf<Long?>(null) }
    @Suppress("UNUSED_VARIABLE") val refresh = revision
    val selected = jobs.firstOrNull { it.id == selectedId }
    Column(Modifier.fillMaxSize()) {
        AoHeader("SCR-700-PRINT", "業務帳票印刷キュー")
        Row(Modifier.weight(1f).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AoPanel(Modifier.width(520.dp).fillMaxHeight()) {
                Text("印刷ジョブ", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AoNavy)
                Spacer(Modifier.height(8.dp))
                LazyColumn {
                    items(jobs) { job ->
                        Row(
                            Modifier.fillMaxWidth().background(if (job.id == selectedId) AoPaleBlue else Color.Transparent).clickable { selectedId = job.id }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Job.${job.id} ${job.documentType.displayName}", fontWeight = FontWeight.Bold)
                                Text("参照No.${job.referenceId} / ${job.paperWidthMm}mm / ${aoDateTime(job.createdAt)}", color = Color.Gray)
                            }
                            Text(job.status.name, color = if (job.status == PrintJobStatus.COMPLETED) AoGreen else AoDanger, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            AoPanel(Modifier.weight(1f).fillMaxHeight()) {
                Text("帳票プレビュー", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AoNavy)
                Spacer(Modifier.height(8.dp))
                if (selected == null) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("印刷ジョブを選択してください", color = Color.Gray) }
                } else {
                    Text(selected.payloadText, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()))
                    if (selected.lastError != null) Text("最終エラー: ${selected.lastError}", color = AoDanger)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onRetry(selected.id) }, modifier = Modifier.weight(1f)) { Text("再試行待ちへ") }
                        Button(onClick = { onPrint(selected.id) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = AoBlue)) {
                            Text("テスト印刷")
                        }
                    }
                }
                if (message != null) Text(message, color = messageColor(message))
            }
        }
        AoBottomBar("レジ管理へ戻る", onBack)
    }
}

@Composable
private fun AoMenuTile(title: String, detail: String, background: Color, modifier: Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier.fillMaxHeight().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = background),
        border = BorderStroke(1.dp, AoBorder),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AoNavy, textAlign = TextAlign.Center)
            Spacer(Modifier.height(7.dp))
            Text(detail, color = Color.DarkGray, textAlign = TextAlign.Center, fontSize = 14.sp)
        }
    }
}

@Composable
private fun AoHeader(screenId: String, title: String) {
    Row(Modifier.fillMaxWidth().height(62.dp).background(AoNavy).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("REGISTER", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(24.dp))
        Text("$screenId  $title", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text("オフライン業務", color = Color.White)
    }
}

@Composable
private fun AoPanel(modifier: Modifier = Modifier, content: @Composable Column.() -> Unit) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, AoBorder), shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.fillMaxSize().padding(16.dp), content = content)
    }
}

@Composable
private fun AoAmountRow(label: String, value: String, emphasized: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = if (emphasized) AoNavy else Color.DarkGray, fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal)
        Text(value, fontSize = if (emphasized) 21.sp else 16.sp, fontWeight = FontWeight.Bold, color = if (emphasized) AoNavy else Color.Black)
    }
}

@Composable
private fun AoChoiceButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick, modifier.height(46.dp), border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) AoDanger else AoBorder)) {
        Text(label, fontWeight = FontWeight.Bold, color = AoNavy)
    }
}

@Composable
private fun AoNumericField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value,
        { onValueChange(it.filter(Char::isDigit).take(12)) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AoBottomBar(label: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(70.dp).background(Color.White).padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onBack, Modifier.width(220.dp).fillMaxHeight()) { Text(label, fontWeight = FontWeight.Bold) }
    }
}

private fun aoYen(value: Long): String = NumberFormat.getCurrencyInstance(Locale.JAPAN).format(value)
private fun aoSignedYen(value: Long): String = when {
    value > 0 -> "+${aoYen(value)}"
    value < 0 -> "-${aoYen(-value)}"
    else -> aoYen(0)
}
private fun aoDateTime(value: Long): String = SimpleDateFormat("MM/dd HH:mm", Locale.JAPAN).format(Date(value))
private fun aoPaymentLabel(method: String): String = runCatching { PaymentMethod.valueOf(method).displayName }.getOrElse { if (method == "OTHER") "その他" else method }
private fun messageColor(message: String): Color = if (
    message.contains("失敗") || message.contains("違い") || message.contains("必要") || message.contains("できません") || message.contains("超え")
) AoDanger else AoGreen
