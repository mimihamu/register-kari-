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
import androidx.compose.foundation.lazy.itemsIndexed
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

private val OpNavy = Color(0xFF173F6B)
private val OpBlue = Color(0xFF1976B9)
private val OpBackground = Color(0xFFF4F7FA)
private val OpBorder = Color(0xFFD5DEE7)
private val OpDanger = Color(0xFFC62828)
private val OpGreen = Color(0xFF2E7D32)
private val OpPaleBlue = Color(0xFFEAF3FA)
private val OpPaleGreen = Color(0xFFEAF5EC)
private val OpPaleYellow = Color(0xFFFFF4D9)

class OperationsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                OperationsApp(onClose = { finish() })
            }
        }
    }
}

private enum class OperationsScreen {
    MENU,
    BUSINESS,
    DAILY_SALES,
    SETTLEMENT,
    CASH_MOVEMENT,
    REVERSAL,
}

@Composable
private fun OperationsApp(onClose: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val store = remember { OperationsStore(appContext) }
    val secureStore = remember { SecureOperationsCoordinator(appContext, store) }
    val registerDatabase = remember { RegisterDatabase(appContext) }
    var screen by remember { mutableStateOf(OperationsScreen.MENU) }
    var revision by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var activeOperator by remember { mutableStateOf(OperatorSessionRegistry.current(appContext)) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(5_000L)
            activeOperator = OperatorSessionRegistry.current(appContext)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            store.close()
            registerDatabase.close()
        }
    }

    fun openScreen(permission: RegisterPermission, destination: OperationsScreen) {
        val current = OperatorSessionRegistry.current(appContext)
        activeOperator = current
        if (current?.allows(permission) == true) {
            message = null
            screen = destination
        } else {
            message = "${permission.displayName}の権限がありません"
        }
    }

    val operator = activeOperator
    Surface(Modifier.fillMaxSize(), color = OpBackground) {
        if (operator == null) {
            OperationsAccessDeniedScreen(onClose)
            return@Surface
        }

        when (screen) {
            OperationsScreen.MENU -> OperationsMenuScreen(
                session = store.activeBusinessSession(),
                summary = if (
                    operator.allows(RegisterPermission.VIEW_SALES) ||
                    operator.allows(RegisterPermission.SETTLEMENT)
                ) store.dailySummary() else null,
                operatorName = operator.name,
                permissions = operator.permissions,
                message = message,
                onBusiness = { openScreen(RegisterPermission.SETTLEMENT, OperationsScreen.BUSINESS) },
                onDailySales = { openScreen(RegisterPermission.VIEW_SALES, OperationsScreen.DAILY_SALES) },
                onSettlement = { openScreen(RegisterPermission.SETTLEMENT, OperationsScreen.SETTLEMENT) },
                onCashMovement = { openScreen(RegisterPermission.CASH_MOVEMENT, OperationsScreen.CASH_MOVEMENT) },
                onReversal = { openScreen(RegisterPermission.REVERSAL, OperationsScreen.REVERSAL) },
                onClose = onClose,
            )

            OperationsScreen.BUSINESS -> BusinessDayScreen(
                session = store.activeBusinessSession(),
                history = store.recentBusinessSessions(),
                summary = store.dailySummary(),
                operatorName = operator.name,
                revision = revision,
                message = message,
                onStart = { date, openingCash ->
                    val result = runCatching { secureStore.startBusinessDay(date, openingCash) }
                    message = result.fold(
                        onSuccess = { "営業を開始しました（No.$it）" },
                        onFailure = { it.message ?: "営業開始に失敗しました" },
                    )
                    if (result.isSuccess) revision++
                    activeOperator = OperatorSessionRegistry.current(appContext)
                },
                onCloseDay = { actualCash, pin ->
                    val result = runCatching { secureStore.endBusinessDay(actualCash, pin) }
                    message = result.fold(
                        onSuccess = { "営業を終了しました（No.$it）" },
                        onFailure = { it.message ?: "営業終了に失敗しました" },
                    )
                    if (result.isSuccess) revision++
                    activeOperator = OperatorSessionRegistry.current(appContext)
                },
                onBack = { screen = OperationsScreen.MENU },
            )

            OperationsScreen.DAILY_SALES -> DailySalesScreen(
                summary = store.dailySummary(),
                onBack = { screen = OperationsScreen.MENU },
            )

            OperationsScreen.SETTLEMENT -> SettlementScreen(
                summary = store.dailySummary(),
                history = store.recentSettlements(),
                operatorName = operator.name,
                revision = revision,
                onExecute = { type, actualCash, pin ->
                    val result = runCatching { secureStore.recordSettlement(type, actualCash, pin) }
                    message = result.fold(
                        onSuccess = { "${type.displayName}を保存しました（No.$it）" },
                        onFailure = { it.message ?: "保存に失敗しました" },
                    )
                    if (result.isSuccess) revision++
                    activeOperator = OperatorSessionRegistry.current(appContext)
                },
                message = message,
                onBack = { screen = OperationsScreen.MENU },
            )

            OperationsScreen.CASH_MOVEMENT -> CashMovementScreen(
                records = store.recentCashMovements(),
                operatorName = operator.name,
                revision = revision,
                message = message,
                onSave = { type, amount, reason ->
                    val result = runCatching { secureStore.recordCashMovement(type, amount, reason) }
                    message = result.fold(
                        onSuccess = { "${type.displayName}を保存しました（No.$it）" },
                        onFailure = { it.message ?: "保存に失敗しました" },
                    )
                    if (result.isSuccess) revision++
                    activeOperator = OperatorSessionRegistry.current(appContext)
                },
                onBack = { screen = OperationsScreen.MENU },
            )

            OperationsScreen.REVERSAL -> ReversalScreen(
                sales = registerDatabase.listSales(200),
                reversedSaleIds = store.reversedSaleIds(),
                reversals = store.recentReversals(),
                operatorName = operator.name,
                revision = revision,
                message = message,
                onExecute = { saleId, type, reason, pin ->
                    val result = runCatching { secureStore.createFullReversal(saleId, type, reason, pin) }
                    message = result.fold(
                        onSuccess = { "${type.displayName}を反対取引として保存しました（No.$it）" },
                        onFailure = { it.message ?: "処理に失敗しました" },
                    )
                    if (result.isSuccess) revision++
                    activeOperator = OperatorSessionRegistry.current(appContext)
                },
                onBack = { screen = OperationsScreen.MENU },
            )
        }
    }
}

@Composable
private fun OperationsMenuScreen(
    session: BusinessSessionRecord?,
    summary: DailyOperationsSummary?,
    operatorName: String,
    permissions: Set<RegisterPermission>,
    message: String?,
    onBusiness: () -> Unit,
    onDailySales: () -> Unit,
    onSettlement: () -> Unit,
    onCashMovement: () -> Unit,
    onReversal: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        OpHeader("SCR-700", "レジ管理メニュー")
        Row(
            Modifier.weight(1f).padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            OpPanel(Modifier.width(390.dp).fillMaxHeight()) {
                Text("営業日の状態", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = OpNavy)
                Spacer(Modifier.height(8.dp))
                OpAuthenticatedOperator(operatorName)
                Spacer(Modifier.height(8.dp))
                if (summary == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("売上集計の表示権限がありません", color = Color.Gray)
                    }
                } else {
                    OpAmountRow("営業日", summary.businessDate)
                    OpAmountRow("営業状態", session?.status?.displayName ?: "営業開始前")
                    OpAmountRow("開始釣銭", opYen(summary.openingCash))
                    OpAmountRow("純売上", opYen(summary.netSales), emphasized = true)
                    OpAmountRow("取引件数", "${summary.transactionCount}件")
                    OpAmountRow("返品・取消", "${summary.reversalCount}件 / -${opYen(summary.reversalGross)}")
                    OpAmountRow("現金理論残高", opYen(summary.expectedCash))
                    OpAmountRow("未印刷", "${summary.pendingPrints}件")
                    OpAmountRow("未会計伝票", "${summary.heldTickets}件")
                    OpAmountRow("精算状態", if (summary.settled) "Z精算済み" else "未精算")
                }
                if (message != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        message,
                        color = if (message.contains("失敗") || message.contains("違い") || message.contains("権限")) OpDanger else OpGreen,
                    )
                }
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MenuTile(
                    "営業開始・終了",
                    "営業日と開始釣銭を登録／Z精算後に営業終了",
                    Color(0xFFE8EAF6),
                    Modifier.weight(0.82f),
                    RegisterPermission.SETTLEMENT in permissions,
                    onBusiness,
                )
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    MenuTile(
                        "当日売上",
                        "SCR-510\n売上・支払・現金を確認",
                        OpPaleBlue,
                        Modifier.weight(1f),
                        RegisterPermission.VIEW_SALES in permissions,
                        onDailySales,
                    )
                    MenuTile(
                        "点検・精算",
                        "SCR-500\nX点検／Z精算／現金実査",
                        OpPaleGreen,
                        Modifier.weight(1f),
                        RegisterPermission.SETTLEMENT in permissions,
                        onSettlement,
                    )
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    MenuTile(
                        "入出金",
                        "入金・出金を理由付きで記録",
                        OpPaleYellow,
                        Modifier.weight(1f),
                        RegisterPermission.CASH_MOVEMENT in permissions,
                        onCashMovement,
                    )
                    MenuTile(
                        "返品・取消",
                        "元売上を残して反対取引を作成",
                        Color(0xFFFCE8E6),
                        Modifier.weight(1f),
                        RegisterPermission.REVERSAL in permissions,
                        onReversal,
                    )
                }
            }
        }
        OpBottomBar("販売へ戻る", onClose)
    }
}

@Composable
private fun MenuTile(
    title: String,
    description: String,
    background: Color,
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxHeight().clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (enabled) background else Color(0xFFE8ECEF)),
        border = BorderStroke(1.dp, OpBorder),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, fontSize = 27.sp, fontWeight = FontWeight.Bold, color = if (enabled) OpNavy else Color.Gray)
            Spacer(Modifier.height(12.dp))
            Text(
                if (enabled) description else "$description\n権限なし",
                textAlign = TextAlign.Center,
                color = if (enabled) Color.DarkGray else Color.Gray,
                lineHeight = 22.sp,
            )
        }
    }
}

@Composable
private fun BusinessDayScreen(
    session: BusinessSessionRecord?,
    history: List<BusinessSessionRecord>,
    summary: DailyOperationsSummary,
    operatorName: String,
    revision: Int,
    message: String?,
    onStart: (LocalDate, Long) -> Unit,
    onCloseDay: (Long, String) -> Unit,
    onBack: () -> Unit,
) {
    var businessDate by remember { mutableStateOf(session?.businessDate ?: LocalDate.now().toString()) }
    var openingCash by remember { mutableStateOf("") }
    var actualCash by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var validationMessage by remember { mutableStateOf<String?>(null) }
    @Suppress("UNUSED_VARIABLE") val refresh = revision

    Column(Modifier.fillMaxSize()) {
        OpHeader("SCR-490", "営業開始・終了")
        Row(Modifier.weight(1f).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OpPanel(Modifier.width(440.dp).fillMaxHeight()) {
                Text("現在の営業日", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = OpNavy)
                Spacer(Modifier.height(10.dp))
                if (session == null) {
                    Text("営業開始前です", color = OpDanger, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = businessDate,
                        onValueChange = { businessDate = it.take(10); validationMessage = null },
                        label = { Text("営業日（YYYY-MM-DD）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OpNumericField("開始釣銭", openingCash, { openingCash = it })
                    Spacer(Modifier.height(8.dp))
                    OpAuthenticatedOperator(operatorName)
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            val date = runCatching { LocalDate.parse(businessDate) }.getOrNull()
                            if (date == null) {
                                validationMessage = "営業日はYYYY-MM-DD形式で入力してください"
                            } else {
                                validationMessage = null
                                onStart(date, openingCash.toLongOrNull() ?: 0L)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = OpBlue),
                    ) { Text("営業を開始", fontWeight = FontWeight.Bold) }
                } else {
                    OpAmountRow("営業日", session.businessDate)
                    OpAmountRow("状態", session.status.displayName)
                    OpAmountRow("開始釣銭", opYen(session.openingCash))
                    OpAmountRow("開始時刻", opDateTime(session.openedAt))
                    OpAmountRow("開始担当", session.openedBy)
                    Spacer(Modifier.height(12.dp))
                    if (session.status == BusinessSessionStatus.OPEN) {
                        Text("営業中です。営業終了には先にZ精算を実行してください。", color = OpGreen, fontWeight = FontWeight.Bold)
                    } else {
                        Text("Z精算済みです。現金実査額を確認して営業終了してください。", color = OpDanger, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        OpNumericField("営業終了時の現金実査額", actualCash, { actualCash = it })
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = pin,
                            onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                            label = { Text("責任者PIN") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OpAuthenticatedOperator(operatorName)
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = { onCloseDay(actualCash.toLongOrNull() ?: summary.expectedCash, pin) },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = OpDanger),
                        ) { Text("営業を終了", fontWeight = FontWeight.Bold) }
                    }
                }
                val shownMessage = validationMessage ?: message
                if (shownMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(shownMessage, color = if (shownMessage.contains("しました")) OpGreen else OpDanger)
                }
            }

            OpPanel(Modifier.width(360.dp).fillMaxHeight()) {
                Text("営業日集計", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = OpNavy)
                Spacer(Modifier.height(10.dp))
                OpAmountRow("営業日", summary.businessDate)
                OpAmountRow("開始釣銭", opYen(summary.openingCash))
                OpAmountRow("純売上", opYen(summary.netSales), emphasized = true)
                OpAmountRow("入金", opYen(summary.cashIn))
                OpAmountRow("出金", "-${opYen(summary.cashOut)}")
                OpAmountRow("現金理論残高", opYen(summary.expectedCash), emphasized = true)
                OpAmountRow("Z精算", if (summary.settled) "済" else "未")
            }

            OpPanel(Modifier.weight(1f).fillMaxHeight()) {
                Text("営業日履歴", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = OpNavy)
                Spacer(Modifier.height(8.dp))
                if (history.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("履歴はありません", color = Color.Gray) }
                } else {
                    LazyColumn {
                        itemsIndexed(history) { _, record ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Row(Modifier.fillMaxWidth()) {
                                    Text(record.businessDate, fontWeight = FontWeight.Bold, color = OpNavy)
                                    Spacer(Modifier.weight(1f))
                                    Text(record.status.displayName, color = if (record.status == BusinessSessionStatus.CLOSED) OpGreen else OpDanger)
                                }
                                Text("開始 ${opDateTime(record.openedAt)} / ${record.openedBy}", color = Color.Gray)
                                if (record.closedAt != null) {
                                    Text("終了 ${opDateTime(record.closedAt)} / ${record.closedBy.orEmpty()}  差異 ${signedYen(record.closeVariance ?: 0L)}", color = Color.Gray)
                                }
                            }
                        }
                    }
                }
            }
        }
        OpBottomBar("レジ管理へ戻る", onBack)
    }
}

@Composable
private fun DailySalesScreen(summary: DailyOperationsSummary, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        OpHeader("SCR-510", "当日売上簡易確認")
        Row(Modifier.weight(1f).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            OpPanel(Modifier.weight(1f).fillMaxHeight()) {
                Text("売上速報", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = OpNavy)
                Spacer(Modifier.height(12.dp))
                OpAmountRow("売上総額", opYen(summary.salesGross))
                OpAmountRow("返品・取消", "-${opYen(summary.reversalGross)}")
                OpAmountRow("純売上", opYen(summary.netSales), emphasized = true)
                OpAmountRow("売上件数", "${summary.transactionCount}件")
                OpAmountRow("返品・取消件数", "${summary.reversalCount}件")
                val average = if (summary.transactionCount > 0) summary.netSales / summary.transactionCount else 0
                OpAmountRow("客単価参考", opYen(average))
                Spacer(Modifier.height(12.dp))
                Text(
                    if (summary.settled) "この営業日はZ精算済みです" else "この営業日は未精算です",
                    color = if (summary.settled) OpGreen else OpDanger,
                    fontWeight = FontWeight.Bold,
                )
            }
            OpPanel(Modifier.weight(1f).fillMaxHeight()) {
                Text("支払別・現金", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = OpNavy)
                Spacer(Modifier.height(12.dp))
                if (summary.paymentTotals.isEmpty()) {
                    Text("支払データはありません", color = Color.Gray)
                } else {
                    summary.paymentTotals.forEach { payment ->
                        OpAmountRow(opPaymentLabel(payment.method), opYen(payment.amount))
                    }
                }
                Spacer(Modifier.height(10.dp))
                OpAmountRow("開始釣銭", opYen(summary.openingCash))
                OpAmountRow("入金", opYen(summary.cashIn))
                OpAmountRow("出金", "-${opYen(summary.cashOut)}")
                OpAmountRow("現金理論残高", opYen(summary.expectedCash), emphasized = true)
                Spacer(Modifier.height(10.dp))
                OpAmountRow("未印刷", "${summary.pendingPrints}件")
                OpAmountRow("未会計伝票", "${summary.heldTickets}件")
            }
        }
        OpBottomBar("レジ管理へ戻る", onBack)
    }
}

@Composable
private fun SettlementScreen(
    summary: DailyOperationsSummary,
    history: List<SettlementRecord>,
    operatorName: String,
    revision: Int,
    onExecute: (SettlementReportType, Long?, String) -> Unit,
    message: String?,
    onBack: () -> Unit,
) {
    var reportType by remember { mutableStateOf(SettlementReportType.X_INSPECTION) }
    var actualCash by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    @Suppress("UNUSED_VARIABLE") val refresh = revision
    val actual = actualCash.toLongOrNull()
    val previewActual = actual ?: summary.expectedCash
    val variance = OperationsMath.variance(previewActual, summary.expectedCash)

    Column(Modifier.fillMaxSize()) {
        OpHeader("SCR-500", "点検・精算")
        Row(Modifier.weight(1f).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OpPanel(Modifier.width(390.dp).fillMaxHeight()) {
                Text("レポート", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = OpNavy)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OpChoiceButton("X点検", reportType == SettlementReportType.X_INSPECTION, Modifier.weight(1f)) {
                        reportType = SettlementReportType.X_INSPECTION
                    }
                    OpChoiceButton("Z精算", reportType == SettlementReportType.Z_SETTLEMENT, Modifier.weight(1f)) {
                        reportType = SettlementReportType.Z_SETTLEMENT
                    }
                }
                Spacer(Modifier.height(12.dp))
                OpNumericField("現金実査額（空欄は理論額）", actualCash, { actualCash = it })
                Spacer(Modifier.height(8.dp))
                OpAuthenticatedOperator(operatorName)
                if (reportType == SettlementReportType.Z_SETTLEMENT) {
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
                Spacer(Modifier.height(12.dp))
                Text(
                    if (reportType == SettlementReportType.X_INSPECTION) {
                        "X点検は現在値を保存します。営業日は締めません。"
                    } else {
                        "Z精算は営業日単位で1回だけ保存します。元売上は変更しません。"
                    },
                    color = Color.DarkGray,
                )
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { onExecute(reportType, actual, pin) },
                    enabled = reportType != SettlementReportType.Z_SETTLEMENT || !summary.settled,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (reportType == SettlementReportType.Z_SETTLEMENT) OpDanger else OpBlue),
                ) { Text("${reportType.displayName}を保存", fontWeight = FontWeight.Bold) }
                if (message != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(message, color = if (message.contains("違い") || message.contains("既に") || message.contains("失敗")) OpDanger else OpGreen)
                }
            }

            OpPanel(Modifier.width(360.dp).fillMaxHeight()) {
                Text("プレビュー", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = OpNavy)
                Spacer(Modifier.height(10.dp))
                OpAmountRow("営業日", summary.businessDate)
                OpAmountRow("売上総額", opYen(summary.salesGross))
                OpAmountRow("返品・取消", "-${opYen(summary.reversalGross)}")
                OpAmountRow("純売上", opYen(summary.netSales), emphasized = true)
                OpAmountRow("現金理論", opYen(summary.expectedCash))
                OpAmountRow("現金実査", opYen(previewActual))
                OpAmountRow("過不足", signedYen(variance), emphasized = true)
                OpAmountRow("未印刷", "${summary.pendingPrints}件")
                OpAmountRow("未会計伝票", "${summary.heldTickets}件")
                if (summary.pendingPrints > 0 || summary.heldTickets > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text("未処理項目があります。Z精算前に確認してください。", color = OpDanger, fontWeight = FontWeight.Bold)
                }
            }

            OpPanel(Modifier.weight(1f).fillMaxHeight()) {
                Text("保存履歴", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = OpNavy)
                Spacer(Modifier.height(8.dp))
                if (history.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("履歴はありません", color = Color.Gray) }
                } else {
                    LazyColumn {
                        itemsIndexed(history) { _, record ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Row(Modifier.fillMaxWidth()) {
                                    Text(record.type.displayName, fontWeight = FontWeight.Bold, color = OpNavy)
                                    Spacer(Modifier.weight(1f))
                                    Text(opDateTime(record.createdAt), color = Color.Gray)
                                }
                                Text("${record.businessDate}  純売上 ${opYen(record.netSales)}  差異 ${signedYen(record.variance)}")
                                Text("担当 ${record.operatorName}", color = Color.Gray)
                            }
                        }
                    }
                }
            }
        }
        OpBottomBar("レジ管理へ戻る", onBack)
    }
}

@Composable
private fun CashMovementScreen(
    records: List<CashMovementRecord>,
    operatorName: String,
    revision: Int,
    message: String?,
    onSave: (CashMovementType, Long, String) -> Unit,
    onBack: () -> Unit,
) {
    var type by remember { mutableStateOf(CashMovementType.IN) }
    var amount by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    @Suppress("UNUSED_VARIABLE") val refresh = revision

    Column(Modifier.fillMaxSize()) {
        OpHeader("SCR-700-INOUT", "入出金")
        Row(Modifier.weight(1f).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            OpPanel(Modifier.width(430.dp).fillMaxHeight()) {
                Text("入出金登録", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = OpNavy)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OpChoiceButton("入金", type == CashMovementType.IN, Modifier.weight(1f)) { type = CashMovementType.IN }
                    OpChoiceButton("出金", type == CashMovementType.OUT, Modifier.weight(1f)) { type = CashMovementType.OUT }
                }
                Spacer(Modifier.height(10.dp))
                OpNumericField("金額", amount, { amount = it })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(100) },
                    label = { Text("理由（必須）") },
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                )
                Spacer(Modifier.height(8.dp))
                OpAuthenticatedOperator(operatorName)
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        onSave(type, amount.toLongOrNull() ?: 0, reason)
                        amount = ""
                        reason = ""
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (type == CashMovementType.IN) OpBlue else OpDanger),
                ) { Text("${type.displayName}を保存", fontWeight = FontWeight.Bold) }
                if (message != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(message, color = if (message.contains("失敗") || message.contains("入力")) OpDanger else OpGreen)
                }
            }

            OpPanel(Modifier.weight(1f).fillMaxHeight()) {
                Text("入出金履歴", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = OpNavy)
                Spacer(Modifier.height(8.dp))
                if (records.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("履歴はありません", color = Color.Gray) }
                } else {
                    LazyColumn {
                        itemsIndexed(records) { _, record ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(record.type.displayName, color = if (record.type == CashMovementType.IN) OpBlue else OpDanger, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(record.reason, fontWeight = FontWeight.Medium)
                                    Text("${opDateTime(record.createdAt)} / ${record.operatorName}", color = Color.Gray)
                                }
                                Text(
                                    if (record.type == CashMovementType.IN) "+${opYen(record.amount)}" else "-${opYen(record.amount)}",
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
        OpBottomBar("レジ管理へ戻る", onBack)
    }
}

@Composable
private fun ReversalScreen(
    sales: List<SaleSummaryRecord>,
    reversedSaleIds: Set<Long>,
    reversals: List<ReversalRecord>,
    operatorName: String,
    revision: Int,
    message: String?,
    onExecute: (Long, ReversalType, String, String) -> Unit,
    onBack: () -> Unit,
) {
    var selectedSaleId by remember { mutableStateOf<Long?>(null) }
    var type by remember { mutableStateOf(ReversalType.RETURN) }
    var reason by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    @Suppress("UNUSED_VARIABLE") val refresh = revision
    val selected = sales.firstOrNull { it.id == selectedSaleId }

    Column(Modifier.fillMaxSize()) {
        OpHeader("SCR-400/410", "返品・取消")
        Row(Modifier.weight(1f).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            OpPanel(Modifier.weight(1f).fillMaxHeight()) {
                Text("元売上を選択", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = OpNavy)
                Spacer(Modifier.height(8.dp))
                if (sales.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("売上はありません", color = Color.Gray) }
                } else {
                    LazyColumn {
                        itemsIndexed(sales) { _, sale ->
                            val reversed = sale.id in reversedSaleIds
                            val selectedRow = sale.id == selectedSaleId
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(if (selectedRow) OpPaleBlue else Color.Transparent)
                                    .clickable(enabled = !reversed) { selectedSaleId = sale.id }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("No.${sale.id}  ${opDateTime(sale.createdAt)}", fontWeight = FontWeight.Bold)
                                    Text("${sale.paymentLabel} / 担当 ${sale.operatorName}", color = Color.Gray)
                                }
                                Text(opYen(sale.totalAmount), fontSize = 19.sp, fontWeight = FontWeight.Bold)
                                if (reversed) {
                                    Spacer(Modifier.width(10.dp))
                                    Text("処理済", color = OpDanger, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            OpPanel(Modifier.width(410.dp).fillMaxHeight()) {
                Text("反対取引", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = OpNavy)
                Spacer(Modifier.height(10.dp))
                OpAmountRow("元売上", selected?.let { "No.${it.id}" } ?: "未選択")
                OpAmountRow("金額", selected?.let { opYen(it.totalAmount) } ?: opYen(0), emphasized = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OpChoiceButton("返品", type == ReversalType.RETURN, Modifier.weight(1f)) { type = ReversalType.RETURN }
                    OpChoiceButton("取消", type == ReversalType.CANCEL, Modifier.weight(1f)) { type = ReversalType.CANCEL }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(100) },
                    label = { Text("理由（必須）") },
                    modifier = Modifier.fillMaxWidth().height(90.dp),
                )
                Spacer(Modifier.height(8.dp))
                OpAuthenticatedOperator(operatorName)
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
                Text("元売上は変更・削除せず、全額の反対取引として追記します。", color = Color.DarkGray)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        selectedSaleId?.let { onExecute(it, type, reason, pin) }
                        reason = ""
                        pin = ""
                        selectedSaleId = null
                    },
                    enabled = selected != null,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = OpDanger),
                ) { Text("${type.displayName}を確定", fontWeight = FontWeight.Bold) }
                if (message != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(message, color = if (message.contains("違い") || message.contains("既に") || message.contains("失敗")) OpDanger else OpGreen)
                }
            }

            OpPanel(Modifier.width(330.dp).fillMaxHeight()) {
                Text("処理履歴", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = OpNavy)
                Spacer(Modifier.height(8.dp))
                if (reversals.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("履歴はありません", color = Color.Gray) }
                } else {
                    LazyColumn {
                        itemsIndexed(reversals) { _, record ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Row(Modifier.fillMaxWidth()) {
                                    Text(record.type.displayName, color = OpDanger, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.weight(1f))
                                    Text("-${opYen(record.grossAmount)}", fontWeight = FontWeight.Bold)
                                }
                                Text("元売上 No.${record.originalSaleId} / ${record.reason}")
                                Text("${opDateTime(record.createdAt)} / ${record.operatorName}", color = Color.Gray)
                            }
                        }
                    }
                }
            }
        }
        OpBottomBar("レジ管理へ戻る", onBack)
    }
}

@Composable
private fun OperationsAccessDeniedScreen(onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("管理画面を利用できません", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = OpDanger)
        Spacer(Modifier.height(12.dp))
        Text("ログインセッションが失効したか、担当者が停止・権限変更されています。")
        Spacer(Modifier.height(24.dp))
        Button(onClick = onClose) { Text("販売画面へ戻る") }
    }
}

@Composable
private fun OpAuthenticatedOperator(operatorName: String) {
    Row(
        Modifier.fillMaxWidth().background(OpPaleBlue, RoundedCornerShape(8.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("操作担当", color = Color.DarkGray)
        Spacer(Modifier.weight(1f))
        Text(operatorName, fontWeight = FontWeight.Bold, color = OpNavy)
    }
}

@Composable
private fun OpHeader(screenId: String, title: String) {
    Row(
        Modifier.fillMaxWidth().height(62.dp).background(OpNavy).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("つぐレジ", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(24.dp))
        Text("$screenId  $title", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text("オフライン管理", color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun OpPanel(modifier: Modifier = Modifier, content: @Composable Column.() -> Unit) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, OpBorder),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(18.dp), content = content)
    }
}

@Composable
private fun OpAmountRow(label: String, value: String, emphasized: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = if (emphasized) OpNavy else Color.DarkGray, fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal)
        Text(value, fontSize = if (emphasized) 22.sp else 17.sp, fontWeight = FontWeight.Bold, color = if (emphasized) OpNavy else Color.Black)
    }
}

@Composable
private fun OpChoiceButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) OpDanger else OpBorder),
    ) { Text(label, fontWeight = FontWeight.Bold, color = OpNavy) }
}

@Composable
private fun OpNumericField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(12)) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun OpBottomBar(label: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(72.dp).background(Color.White).padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onBack, modifier = Modifier.width(220.dp).fillMaxHeight()) {
            Text(label, fontWeight = FontWeight.Bold)
        }
    }
}

private fun opYen(value: Long): String = NumberFormat.getCurrencyInstance(Locale.JAPAN).format(value)

private fun signedYen(value: Long): String = when {
    value > 0 -> "+${opYen(value)}"
    value < 0 -> "-${opYen(-value)}"
    else -> opYen(0)
}

private fun opDateTime(epochMillis: Long): String =
    SimpleDateFormat("MM/dd HH:mm", Locale.JAPAN).format(Date(epochMillis))

private fun opPaymentLabel(method: String): String = runCatching {
    PaymentMethod.valueOf(method).displayName
}.getOrElse {
    when (method) {
        "OTHER" -> "その他"
        else -> method
    }
}
