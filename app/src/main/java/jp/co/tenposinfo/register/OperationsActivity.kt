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
import androidx.compose.material3.AlertDialog
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
import java.util.UUID

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
        configureRegisterSystemBars(window)
        val requestedReversalSaleId = ReversalNavigation.requestedSaleId(intent)
        setContent {
            MaterialTheme {
                OperationsApp(
                    requestedReversalSaleId = requestedReversalSaleId,
                    onClose = { finish() },
                )
            }
        }
    }
}

private enum class OperationsScreen {
    MENU,
    BUSINESS,
    DAILY_SALES,
    X_INSPECTION,
    Z_SETTLEMENT,
    SETTLEMENT_HISTORY,
    CASH_MOVEMENT,
    REVERSAL,
}

@Composable
private fun OperationsApp(
    requestedReversalSaleId: Long?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val store = remember { OperationsStore(appContext) }
    val secureStore = remember { SecureOperationsCoordinator(appContext, store) }
    val registerDatabase = remember { RegisterDatabase(appContext) }
    var screen by remember { mutableStateOf(OperationsScreen.MENU) }
    var revision by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var activeOperator by remember { mutableStateOf(OperatorSessionRegistry.current(appContext)) }
    var reversalContextSaleId by remember { mutableStateOf<Long?>(null) }
    var requestedReversalHandled by remember(requestedReversalSaleId) { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(requestedReversalSaleId) {
        val requested = requestedReversalSaleId
        if (requested != null && !requestedReversalHandled) {
            val current = OperatorSessionRegistry.current(appContext)
            activeOperator = current
            if (current?.allows(RegisterPermission.REVERSAL) == true) {
                reversalContextSaleId = requested
                message = null
                screen = OperationsScreen.REVERSAL
            } else {
                reversalContextSaleId = null
                message = "返品・取消の権限がありません"
                screen = OperationsScreen.MENU
            }
            requestedReversalHandled = true
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(5_000L)
            val refreshed = OperatorSessionRegistry.current(appContext)
            activeOperator = refreshed
            if (screen == OperationsScreen.REVERSAL && refreshed?.allows(RegisterPermission.REVERSAL) != true) {
                reversalContextSaleId = null
                message = "返品・取消の権限が失効したため管理メニューへ戻りました"
                screen = OperationsScreen.MENU
            }
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

    fun openSettlementHistory() {
        val current = OperatorSessionRegistry.current(appContext)
        activeOperator = current
        if (current != null && SettlementHistoryPolicyV027.canView(current.permissions)) {
            message = null
            screen = OperationsScreen.SETTLEMENT_HISTORY
        } else {
            message = "点検・精算履歴の表示権限がありません"
        }
    }

    fun executeSettlement(
        type: SettlementReportType,
        actualCash: Long?,
        pin: String,
        pendingPrintsAcknowledged: Boolean,
    ) {
        val result = runCatching {
            secureStore.recordSettlement(type, actualCash, pin, pendingPrintsAcknowledged)
        }
        message = result.fold(
            onSuccess = {
                if (type == SettlementReportType.Z_SETTLEMENT) {
                    "Z精算を保存し、営業を終了しました（No.$it）"
                } else {
                    "X点検を保存しました（No.$it）"
                }
            },
            onFailure = { it.message ?: "保存に失敗しました" },
        )
        if (result.isSuccess) revision++
        activeOperator = OperatorSessionRegistry.current(appContext)
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
                    operator.allows(RegisterPermission.X_INSPECTION) ||
                    operator.allows(RegisterPermission.Z_SETTLEMENT)
                ) store.dailySummary() else null,
                operatorName = operator.name,
                permissions = operator.permissions,
                message = message,
                onBusiness = { openScreen(RegisterPermission.Z_SETTLEMENT, OperationsScreen.BUSINESS) },
                onDailySales = { openScreen(RegisterPermission.VIEW_SALES, OperationsScreen.DAILY_SALES) },
                onXInspection = { openScreen(RegisterPermission.X_INSPECTION, OperationsScreen.X_INSPECTION) },
                onZSettlement = { openScreen(RegisterPermission.Z_SETTLEMENT, OperationsScreen.Z_SETTLEMENT) },
                onSettlementHistory = ::openSettlementHistory,
                onCashMovement = { openScreen(RegisterPermission.CASH_MOVEMENT, OperationsScreen.CASH_MOVEMENT) },
                onReversal = {
                    reversalContextSaleId = null
                    openScreen(RegisterPermission.REVERSAL, OperationsScreen.REVERSAL)
                },
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
                onBack = { screen = OperationsScreen.MENU },
            )

            OperationsScreen.DAILY_SALES -> DailySalesScreen(
                summary = store.dailySummary(),
                onOpenSalesDetail = { businessDate, businessSessionId ->
                    context.startActivity(
                        BusinessDateSalesLookupNavigation.intent(context, businessDate, businessSessionId),
                    )
                },
                onBack = { screen = OperationsScreen.MENU },
            )

            OperationsScreen.X_INSPECTION -> SettlementScreen(
                reportType = SettlementReportType.X_INSPECTION,
                screenCode = "SCR-500-X",
                title = "X点検",
                session = store.activeBusinessSession(),
                summary = store.dailySummary(),
                history = store.activeBusinessSession()?.let {
                    store.recentSettlementsForSession(it.id, SettlementReportType.X_INSPECTION)
                } ?: emptyList(),
                operatorName = operator.name,
                revision = revision,
                onExecute = { actualCash, pin, pendingPrintsAcknowledged ->
                    executeSettlement(
                        SettlementReportType.X_INSPECTION,
                        actualCash,
                        pin,
                        pendingPrintsAcknowledged,
                    )
                },
                message = message,
                onBack = { screen = OperationsScreen.MENU },
            )

            OperationsScreen.Z_SETTLEMENT -> SettlementScreen(
                reportType = SettlementReportType.Z_SETTLEMENT,
                screenCode = "SCR-500-Z",
                title = "Z精算・営業終了",
                session = store.activeBusinessSession(),
                summary = store.dailySummary(),
                history = store.activeBusinessSession()?.let {
                    store.recentSettlementsForSession(it.id, SettlementReportType.Z_SETTLEMENT)
                } ?: emptyList(),
                operatorName = operator.name,
                revision = revision,
                onExecute = { actualCash, pin, pendingPrintsAcknowledged ->
                    executeSettlement(
                        SettlementReportType.Z_SETTLEMENT,
                        actualCash,
                        pin,
                        pendingPrintsAcknowledged,
                    )
                },
                message = message,
                onBack = { screen = OperationsScreen.MENU },
            )

            OperationsScreen.SETTLEMENT_HISTORY -> SettlementHistoryScreenV027(
                sessions = store.recentBusinessSessions(100),
                settlements = store.recentSettlements(500),
                operatorName = operator.name,
                permissions = operator.permissions,
                revision = revision,
                message = message,
                printerPaperWidthMm = PrinterPaperSettingPolicy.currentWidthMm(appContext),
                previewLoader = store::previewSettlement,
                onOpenSalesDetail = { record ->
                    val current = OperatorSessionRegistry.current(appContext)
                    activeOperator = current
                    if (current?.allows(RegisterPermission.VIEW_SALES) == true) {
                        message = null
                        context.startActivity(
                            BusinessDateSalesLookupNavigation.intent(
                                context,
                                record.businessDate,
                                record.businessSessionId,
                            ),
                        )
                    } else {
                        message = "売上参照の権限がありません"
                    }
                },
                onReprint = { record, managerPin ->
                    val result = runCatching {
                        secureStore.reprintSettlement(record.id, managerPin)
                    }
                    message = result.fold(
                        onSuccess = {
                            "${record.type.displayName}票の再印字を受け付けました（印刷ジョブNo.$it）"
                        },
                        onFailure = { it.message ?: "再印字に失敗しました" },
                    )
                    if (result.isSuccess) revision++
                    activeOperator = OperatorSessionRegistry.current(appContext)
                },
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
                initialSaleId = reversalContextSaleId,
                sales = registerDatabase.listSales(SalesHistoryLookupPolicy.RECENT_LOAD_LIMIT),
                reversedSaleIds = store.reversedSaleIds(),
                reversals = store.recentReversals(),
                operatorName = operator.name,
                revision = revision,
                message = message,
                printerPaperWidthMm = PrinterPaperSettingPolicy.currentWidthMm(appContext),
                loadLines = store::loadReturnableLines,
                lookupSale = { saleId -> registerDatabase.loadSaleDetail(saleId)?.summary },
                onExecute = { saleId, type, quantities, reason, pin, requestId ->
                    val result = runCatching {
                        secureStore.createReversal(
                            saleId,
                            type,
                            quantities,
                            reason,
                            pin,
                            requestId,
                        )
                    }
                    message = result.fold(
                        onSuccess = { "${type.displayName}を保存しました（No.${it.reversalId}／返金 ${opYen(it.refundAmount)}）" },
                        onFailure = { it.message ?: "処理に失敗しました" },
                    )
                    result.onSuccess {
                        revision++
                        AutomaticPrintScheduler.enqueueNow(appContext)
                        DriveOutboxScheduler.enqueueNow(appContext)
                    }
                    activeOperator = OperatorSessionRegistry.current(appContext)
                    result.getOrNull()
                },
                onBack = {
                    reversalContextSaleId = null
                    screen = OperationsScreen.MENU
                },
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
    onXInspection: () -> Unit,
    onZSettlement: () -> Unit,
    onSettlementHistory: () -> Unit,
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
                    OpAmountRow("営業セッション", session?.let { "No.${it.id}" } ?: "開始前")
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
                    "営業開始・状態",
                    "営業セッションを開始／Z精算で営業終了",
                    Color(0xFFE8EAF6),
                    Modifier.weight(0.82f),
                    RegisterPermission.Z_SETTLEMENT in permissions,
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
                        "X点検",
                        "SCR-500-X\n営業を継続したまま確認",
                        OpPaleGreen,
                        Modifier.weight(1f),
                        RegisterPermission.X_INSPECTION in permissions,
                        onXInspection,
                    )
                    MenuTile(
                        "Z精算",
                        "SCR-500-Z\n精算して営業終了",
                        Color(0xFFFFE8E8),
                        Modifier.weight(1f),
                        RegisterPermission.Z_SETTLEMENT in permissions,
                        onZSettlement,
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
                    MenuTile(
                        "点検・精算履歴",
                        "SCR-520\nセッション別確認・再印字",
                        Color(0xFFEDE7F6),
                        Modifier.weight(1f),
                        SettlementHistoryPolicyV027.canView(permissions),
                        onSettlementHistory,
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
    onBack: () -> Unit,
) {
    var businessDate by remember { mutableStateOf(session?.businessDate ?: LocalDate.now().toString()) }
    var openingCash by remember { mutableStateOf("") }
    var validationMessage by remember { mutableStateOf<String?>(null) }
    @Suppress("UNUSED_VARIABLE") val refresh = revision

    Column(Modifier.fillMaxSize()) {
        OpHeader("SCR-490", "営業開始・状態")
        Row(Modifier.weight(1f).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OpPanel(Modifier.width(440.dp).fillMaxHeight()) {
                Text("現在の営業セッション", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = OpNavy)
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
                    Text(
                        "同じ営業日でも、前の営業セッションがZ精算済みなら新しい営業を開始できます。",
                        color = Color.DarkGray,
                    )
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
                    OpAmountRow("営業セッション", "No.${session.id}")
                    OpAmountRow("営業日", session.businessDate)
                    OpAmountRow("状態", session.status.displayName)
                    OpAmountRow("開始釣銭", opYen(session.openingCash))
                    OpAmountRow("開始時刻", opDateTime(session.openedAt))
                    OpAmountRow("開始担当", session.openedBy)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Z精算を実行すると、この営業セッションは精算と同時に終了します。",
                        color = OpDanger,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "終了後は、同じ営業日を指定して新しい営業セッションを開始できます。",
                        color = Color.DarkGray,
                    )
                    Spacer(Modifier.weight(1f))
                    Text("営業終了は点検・精算画面の「Z精算して営業終了」から実行します。", color = OpNavy)
                }
                val shownMessage = validationMessage ?: message
                if (shownMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(shownMessage, color = if (shownMessage.contains("しました")) OpGreen else OpDanger)
                }
            }

            OpPanel(Modifier.width(360.dp).fillMaxHeight()) {
                Text("直近セッション集計", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = OpNavy)
                Spacer(Modifier.height(10.dp))
                OpAmountRow("営業セッション", if (summary.businessSessionId > 0) "No.${summary.businessSessionId}" else "未開始")
                OpAmountRow("営業日", summary.businessDate)
                OpAmountRow("開始釣銭", opYen(summary.openingCash))
                OpAmountRow("純売上", opYen(summary.netSales), emphasized = true)
                OpAmountRow("入金", opYen(summary.cashIn))
                OpAmountRow("出金", "-${opYen(summary.cashOut)}")
                OpAmountRow("現金理論残高", opYen(summary.expectedCash), emphasized = true)
                OpAmountRow("Z精算", if (summary.settled) "済" else "未")
            }

            OpPanel(Modifier.weight(1f).fillMaxHeight()) {
                Text("営業セッション履歴", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = OpNavy)
                Spacer(Modifier.height(8.dp))
                if (history.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("履歴はありません", color = Color.Gray) }
                } else {
                    LazyColumn {
                        itemsIndexed(history) { _, record ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Row(Modifier.fillMaxWidth()) {
                                    Text("${record.businessDate}  No.${record.id}", fontWeight = FontWeight.Bold, color = OpNavy)
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
private fun DailySalesScreen(
    summary: DailyOperationsSummary,
    onOpenSalesDetail: (String, Long) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        OpHeader("SCR-510", "営業セッション売上簡易確認")
        Row(Modifier.weight(1f).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            OpPanel(Modifier.weight(1f).fillMaxHeight()) {
                Text("売上速報", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = OpNavy)
                Spacer(Modifier.height(12.dp))
                OpAmountRow("営業セッション", if (summary.businessSessionId > 0) "No.${summary.businessSessionId}" else "未開始")
                OpAmountRow("営業日", summary.businessDate)
                OpAmountRow("売上総額", opYen(summary.salesGross))
                OpAmountRow("返品・取消", "-${opYen(summary.reversalGross)}")
                OpAmountRow("純売上", opYen(summary.netSales), emphasized = true)
                OpAmountRow("売上件数", "${summary.transactionCount}件")
                OpAmountRow("返品・取消件数", "${summary.reversalCount}件")
                val average = if (summary.transactionCount > 0) summary.netSales / summary.transactionCount else 0
                OpAmountRow("客単価参考", opYen(average))
                Spacer(Modifier.height(12.dp))
                Text(
                    if (summary.settled) "この営業セッションはZ精算済みです" else "この営業セッションは未精算です",
                    color = if (summary.settled) OpGreen else OpDanger,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    enabled = summary.businessSessionId > 0L,
                    onClick = { onOpenSalesDetail(summary.businessDate, summary.businessSessionId) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = OpBlue),
                ) { Text("この営業セッションの売上明細", fontWeight = FontWeight.Bold) }
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
    reportType: SettlementReportType,
    screenCode: String,
    title: String,
    session: BusinessSessionRecord?,
    summary: DailyOperationsSummary,
    history: List<SettlementRecord>,
    operatorName: String,
    revision: Int,
    onExecute: (Long?, String, Boolean) -> Unit,
    message: String?,
    onBack: () -> Unit,
) {
    var actualCash by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var pendingPrintsAcknowledged by remember { mutableStateOf(false) }
    var showZConfirmation by remember { mutableStateOf(false) }
    @Suppress("UNUSED_VARIABLE") val refresh = revision
    val isZSettlement = reportType == SettlementReportType.Z_SETTLEMENT
    val actual = actualCash.toLongOrNull()
    val previewActual = actual ?: summary.expectedCash
    val variance = OperationsMath.variance(previewActual, summary.expectedCash)
    val zPreflight = if (isZSettlement) {
        ZSettlementPreflightPolicy.evaluate(
            heldTickets = summary.heldTickets,
            pendingPrints = summary.pendingPrints,
            pendingPrintsAcknowledged = pendingPrintsAcknowledged,
        )
    } else {
        ZSettlementPreflightResult(true, summary.heldTickets, summary.pendingPrints, false, null)
    }

    if (showZConfirmation) {
        AlertDialog(
            onDismissRequest = { showZConfirmation = false },
            title = { Text("Z精算して営業を終了しますか？", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("営業日 ${summary.businessDate} / セッションNo.${summary.businessSessionId}")
                    Text("純売上 ${opYen(summary.netSales)}")
                    Text("現金実査 ${opYen(previewActual)} / 過不足 ${signedYen(variance)}")
                    Text("未会計伝票 ${summary.heldTickets}件 / 未印刷 ${summary.pendingPrints}件")
                    if (summary.pendingPrints > 0) {
                        Text("未印刷データを残したまま精算する責任者確認済み", color = OpDanger, fontWeight = FontWeight.Bold)
                    }
                    Text("完了後、この営業セッションでは販売できません。", color = OpDanger, fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showZConfirmation = false
                        onExecute(actual, pin, pendingPrintsAcknowledged)
                    },
                    enabled = pin.isNotBlank() && zPreflight.mayProceed,
                    colors = ButtonDefaults.buttonColors(containerColor = OpDanger),
                ) { Text("Z精算して営業終了") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showZConfirmation = false }) { Text("戻る") }
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        OpHeader(screenCode, title)
        Row(Modifier.weight(1f).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OpPanel(Modifier.width(410.dp).fillMaxHeight()) {
                Text(if (isZSettlement) "Z精算" else "X点検", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (isZSettlement) OpDanger else OpNavy)
                Spacer(Modifier.height(6.dp))
                Text(
                    session?.let { "対象: ${it.businessDate} / セッションNo.${it.id}" } ?: "営業中のセッションがありません",
                    color = if (session == null) OpDanger else OpGreen,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(10.dp))
                OpNumericField("現金実査額（空欄は理論額）", actualCash, { actualCash = it })
                Spacer(Modifier.height(6.dp))
                OpAuthenticatedOperator(operatorName)
                if (isZSettlement) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                        label = { Text("責任者PIN") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (summary.heldTickets > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "未会計伝票が${summary.heldTickets}件あります。会計または伝票取消を完了するまでZ精算できません。",
                            color = OpDanger,
                            fontWeight = FontWeight.Bold,
                        )
                    } else if (summary.pendingPrints > 0) {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                pendingPrintsAcknowledged = !pendingPrintsAcknowledged
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = pendingPrintsAcknowledged,
                                onCheckedChange = { pendingPrintsAcknowledged = it },
                            )
                            Text(
                                "未印刷データ${summary.pendingPrints}件を確認し、未印刷のまま精算する",
                                color = OpDanger,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (isZSettlement) {
                        "Z精算は現在の営業セッションに対して1回だけ実行し、完了と同時に営業終了します。同じ営業日で再開する場合は新しい営業セッションになります。"
                    } else {
                        "X点検は現在の営業セッションの値を保存します。営業は終了せず、そのまま販売を継続できます。"
                    },
                    color = Color.DarkGray,
                )
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        if (isZSettlement) showZConfirmation = true else onExecute(actual, "", false)
                    },
                    enabled = session != null && (!isZSettlement || (!summary.settled && summary.heldTickets == 0)),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (isZSettlement) OpDanger else OpBlue),
                ) {
                    Text(if (isZSettlement) "Z精算の確認へ" else "X点検を実行", fontWeight = FontWeight.Bold)
                }
                if (message != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        message,
                        color = if (
                            message.contains("違い") || message.contains("既に") ||
                            message.contains("失敗") || message.contains("未会計") || message.contains("未印刷")
                        ) OpDanger else OpGreen,
                    )
                }
            }

            OpPanel(Modifier.width(350.dp).fillMaxHeight()) {
                Text("プレビュー", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = OpNavy)
                Spacer(Modifier.height(10.dp))
                OpAmountRow("営業セッション", if (summary.businessSessionId > 0) "No.${summary.businessSessionId}" else "未開始")
                OpAmountRow("営業日", summary.businessDate)
                OpAmountRow("売上総額", opYen(summary.salesGross))
                OpAmountRow("返品・取消", "-${opYen(summary.reversalGross)}")
                OpAmountRow("純売上", opYen(summary.netSales), emphasized = true)
                OpAmountRow("現金理論", opYen(summary.expectedCash))
                OpAmountRow("現金実査", opYen(previewActual))
                OpAmountRow("過不足", signedYen(variance), emphasized = true)
                OpAmountRow("未印刷", "${summary.pendingPrints}件")
                OpAmountRow("未会計伝票", "${summary.heldTickets}件")
                if (summary.heldTickets > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text("未会計伝票があるためZ精算は禁止されています。", color = OpDanger, fontWeight = FontWeight.Bold)
                } else if (summary.pendingPrints > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text("未印刷データがあります。責任者確認後に限りZ精算できます。", color = OpDanger, fontWeight = FontWeight.Bold)
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
                                Text("${record.businessDate}  セッションNo.${record.businessSessionId}")
                                Text("純売上 ${opYen(record.netSales)}  差異 ${signedYen(record.variance)}")
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
    initialSaleId: Long?,
    sales: List<SaleSummaryRecord>,
    reversedSaleIds: Set<Long>,
    reversals: List<ReversalRecord>,
    operatorName: String,
    revision: Int,
    message: String?,
    printerPaperWidthMm: Int,
    loadLines: (Long) -> List<ReturnableSaleLine>,
    lookupSale: (Long) -> SaleSummaryRecord?,
    onExecute: (Long, ReversalType, Map<Long, Int>, String, String, String) -> PartialReversalResult?,
    onBack: () -> Unit,
) {
    var selectedSaleId by remember(initialSaleId) { mutableStateOf<Long?>(initialSaleId) }
    var contextSaleLocked by remember(initialSaleId) { mutableStateOf(initialSaleId != null) }
    var lines by remember { mutableStateOf<List<ReturnableSaleLine>>(emptyList()) }
    var quantities by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var type by remember { mutableStateOf(ReversalType.RETURN) }
    var reason by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var requestId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var savedResult by remember { mutableStateOf<PartialReversalResult?>(null) }
    var localMessage by remember { mutableStateOf<String?>(null) }
    var saleQuery by remember { mutableStateOf("") }
    var directSaleIdText by remember { mutableStateOf("") }
    var directSaleOverride by remember { mutableStateOf<SaleSummaryRecord?>(null) }
    @Suppress("UNUSED_VARIABLE") val refresh = revision
    val visibleSales = SalesHistoryLookupPolicy.filter(sales, SalesHistoryCriteria(query = saleQuery))
    val directSaleId = SalesHistoryLookupPolicy.parseDirectSaleId(directSaleIdText)
    val selected = directSaleOverride?.takeIf { it.id == selectedSaleId }
        ?: sales.firstOrNull { it.id == selectedSaleId }
    val selectedItems = runCatching { PartialReturnPolicy.select(type, lines, quantities) }.getOrNull().orEmpty()
    val previewSummary = selectedItems.takeIf { it.isNotEmpty() }?.let { TaxEngine.calculate(it.map { pair -> pair.second }) }
    val canCancel = lines.isNotEmpty() && lines.all { it.returnedQuantity == 0 && it.remainingQuantity > 0 }
    val canExecute = selected != null && reason.isNotBlank() && pin.isNotBlank() && when (type) {
        ReversalType.CANCEL -> canCancel
        ReversalType.RETURN -> selectedItems.isNotEmpty()
    }

    androidx.compose.runtime.LaunchedEffect(initialSaleId) {
        val saleId = initialSaleId ?: return@LaunchedEffect
        val sale = lookupSale(saleId)
        val context = ReversalNavigation.resolve(
            requestedSaleId = saleId,
            saleExists = sale != null,
            alreadyCompleted = saleId in reversedSaleIds,
        )
        when {
            !context.saleExists -> {
                selectedSaleId = null
                directSaleOverride = null
                contextSaleLocked = false
                lines = emptyList()
                quantities = emptyMap()
                savedResult = null
                requestId = UUID.randomUUID().toString()
                localMessage = "指定された売上No.$saleId は見つかりません。元売上は選択していません"
            }
            context.alreadyCompleted -> {
                selectedSaleId = null
                directSaleOverride = null
                contextSaleLocked = false
                lines = emptyList()
                quantities = emptyMap()
                savedResult = null
                requestId = UUID.randomUUID().toString()
                localMessage = "売上No.$saleId は全量返品・取消済みです。元売上は選択していません"
            }
            context.mayOpenLocked && sale != null -> {
                selectedSaleId = sale.id
                directSaleOverride = sale.takeIf { candidate -> sales.none { it.id == candidate.id } }
                lines = runCatching { loadLines(sale.id) }.getOrElse {
                    localMessage = it.message ?: "明細取得に失敗しました"
                    emptyList()
                }
                quantities = emptyMap()
                savedResult = null
                requestId = UUID.randomUUID().toString()
                if (lines.isEmpty()) {
                    selectedSaleId = null
                    directSaleOverride = null
                    contextSaleLocked = false
                    localMessage = "売上No.${sale.id} に返品可能な明細がありません。元売上は選択していません"
                } else {
                    contextSaleLocked = true
                    localMessage = "売上No.${sale.id} から開きました。元売上を固定しています"
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        OpHeader("SCR-400/410", "返品・取消")
        Row(Modifier.weight(1f).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OpPanel(Modifier.width(355.dp).fillMaxHeight()) {
                Text("元売上を検索・選択", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = OpNavy)
                if (contextSaleLocked && selectedSaleId != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        colors = CardDefaults.cardColors(containerColor = OpPaleBlue),
                        border = BorderStroke(2.dp, OpBlue),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(8.dp)) {
                            Text(
                                "売上No.$selectedSaleId から開いています",
                                fontWeight = FontWeight.Bold,
                                color = OpNavy,
                            )
                            Text("対象売上は固定中です。変更する場合だけ下のボタンを押してください。", fontSize = 11.sp)
                            Spacer(Modifier.height(5.dp))
                            OutlinedButton(
                                onClick = {
                                    contextSaleLocked = false
                                    selectedSaleId = null
                                    directSaleOverride = null
                                    lines = emptyList()
                                    quantities = emptyMap()
                                    savedResult = null
                                    requestId = UUID.randomUUID().toString()
                                    localMessage = "元売上固定を解除しました。別売上を検索してください"
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("別売上を検索") }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = saleQuery,
                    onValueChange = { saleQuery = it.take(80) },
                    enabled = !contextSaleLocked,
                    label = { Text("売上No.・担当・支払") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(5.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = directSaleIdText,
                        onValueChange = {
                            directSaleIdText = it.filter { ch -> ch.isDigit() || ch == '#' }.take(20)
                            localMessage = null
                        },
                        enabled = !contextSaleLocked,
                        label = { Text("売上No.直接指定") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            val saleId = directSaleId ?: return@Button
                            val sale = lookupSale(saleId)
                            when {
                                sale == null -> {
                                    selectedSaleId = null
                                    directSaleOverride = null
                                    lines = emptyList()
                                    quantities = emptyMap()
                                    savedResult = null
                                    requestId = UUID.randomUUID().toString()
                                    localMessage = "売上No.$saleId は見つかりません。元売上の選択を解除しました"
                                }
                                sale.id in reversedSaleIds -> {
                                    selectedSaleId = null
                                    directSaleOverride = null
                                    lines = emptyList()
                                    quantities = emptyMap()
                                    savedResult = null
                                    requestId = UUID.randomUUID().toString()
                                    localMessage = "売上No.${sale.id} は全量返品・取消済みです。元売上の選択を解除しました"
                                }
                                else -> {
                                    selectedSaleId = sale.id
                                    directSaleOverride = sale.takeIf { candidate -> sales.none { it.id == candidate.id } }
                                    lines = runCatching { loadLines(sale.id) }.getOrElse {
                                        localMessage = it.message ?: "明細取得に失敗しました"
                                        emptyList()
                                    }
                                    quantities = emptyMap()
                                    savedResult = null
                                    requestId = UUID.randomUUID().toString()
                                    localMessage = if (lines.isEmpty()) "返品可能な明細がありません" else null
                                }
                            }
                        },
                        enabled = directSaleId != null && !contextSaleLocked,
                        colors = ButtonDefaults.buttonColors(containerColor = OpBlue),
                    ) { Text("表示") }
                }
                Text(
                    "表示 ${visibleSales.size}件 / 読込 ${sales.size}件（直近最大${SalesHistoryLookupPolicy.RECENT_LOAD_LIMIT}件）",
                    color = Color.Gray,
                    fontSize = 11.sp,
                )
                directSaleOverride?.takeIf { it.id == selectedSaleId }?.let { sale ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                        colors = CardDefaults.cardColors(containerColor = OpPaleBlue),
                        border = BorderStroke(2.dp, OpBlue),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(8.dp)) {
                            Text("直接指定 No.${sale.id}", fontWeight = FontWeight.Bold, color = OpNavy)
                            Text("${opDateTime(sale.createdAt)} / ${sale.operatorName} / ${sale.paymentLabel}", fontSize = 11.sp)
                            Text(opYen(sale.totalAmount), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(5.dp))
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(visibleSales) { _, sale ->
                        val completed = sale.id in reversedSaleIds
                        val selectedRow = sale.id == selectedSaleId
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(if (selectedRow) OpPaleBlue else Color.Transparent)
                                .clickable(enabled = !completed && !contextSaleLocked) {
                                    selectedSaleId = sale.id
                                    directSaleOverride = null
                                    lines = runCatching { loadLines(sale.id) }.getOrElse {
                                        localMessage = it.message ?: "明細取得に失敗しました"
                                        emptyList()
                                    }
                                    quantities = emptyMap()
                                    savedResult = null
                                    requestId = UUID.randomUUID().toString()
                                    localMessage = if (lines.isEmpty()) "返品可能な明細がありません" else null
                                }
                                .padding(horizontal = 7.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("No.${sale.id}  ${opDateTime(sale.createdAt)}", fontWeight = FontWeight.Bold)
                                Text("${sale.paymentLabel} / ${sale.operatorName}", color = Color.Gray, fontSize = 12.sp)
                            }
                            Text(opYen(sale.totalAmount), fontWeight = FontWeight.Bold)
                            if (completed) Text(" 完了", color = OpDanger, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("最近の処理", fontWeight = FontWeight.Bold, color = OpNavy)
                reversals.take(3).forEach { record ->
                    Text("${record.type.displayName} No.${record.originalSaleId}  -${opYen(record.grossAmount)}", fontSize = 12.sp)
                }
            }

            OpPanel(Modifier.weight(1f).fillMaxHeight()) {
                Text("返品商品・数量", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = OpNavy)
                Spacer(Modifier.height(5.dp))
                Text(
                    if (type == ReversalType.CANCEL) "取消は未返品の全商品を対象にします" else "商品ごとに返品数量を指定してください",
                    color = Color.DarkGray,
                )
                Spacer(Modifier.height(8.dp))
                if (selected == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("元売上を選択してください", color = Color.Gray) }
                } else if (lines.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("返品可能な明細がありません", color = OpDanger) }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        itemsIndexed(lines) { _, line ->
                            val requested = if (type == ReversalType.CANCEL) line.remainingQuantity else quantities[line.saleItemId] ?: 0
                            Card(
                                colors = CardDefaults.cardColors(containerColor = if (line.remainingQuantity == 0) Color(0xFFF1F1F1) else Color.White),
                                border = BorderStroke(1.dp, OpBorder),
                            ) {
                                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("${line.productName} [${line.taxSymbol}]", fontWeight = FontWeight.Bold)
                                        Text(
                                            "${opYen(line.unitPrice)} / 販売 ${line.originalQuantity}・返品済 ${line.returnedQuantity}・残 ${line.remainingQuantity}",
                                            color = Color.Gray,
                                            fontSize = 12.sp,
                                        )
                                        if (line.remainingDiscount > 0) Text("値引残 ${opYen(line.remainingDiscount)}", color = Color.Gray, fontSize = 12.sp)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            val next = (requested - 1).coerceAtLeast(0)
                                            quantities = quantities + (line.saleItemId to next)
                                        },
                                        enabled = type == ReversalType.RETURN && requested > 0,
                                        modifier = Modifier.width(48.dp),
                                    ) { Text("−") }
                                    Text("$requested", modifier = Modifier.width(42.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
                                    OutlinedButton(
                                        onClick = {
                                            val next = (requested + 1).coerceAtMost(line.remainingQuantity)
                                            quantities = quantities + (line.saleItemId to next)
                                        },
                                        enabled = type == ReversalType.RETURN && requested < line.remainingQuantity,
                                        modifier = Modifier.width(48.dp),
                                    ) { Text("＋") }
                                }
                            }
                        }
                    }
                }
            }

            OpPanel(Modifier.width(390.dp).fillMaxHeight()) {
                Text("返品・取消確定", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = OpNavy)
                Spacer(Modifier.height(8.dp))
                OpAmountRow("元売上", selected?.let { "No.${it.id}" } ?: "未選択")
                OpAmountRow("返金予定", opYen(previewSummary?.grossAmount ?: 0L), emphasized = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OpChoiceButton("返品", type == ReversalType.RETURN, Modifier.weight(1f)) {
                        type = ReversalType.RETURN
                        requestId = UUID.randomUUID().toString()
                    }
                    OpChoiceButton("取消", type == ReversalType.CANCEL, Modifier.weight(1f)) {
                        type = ReversalType.CANCEL
                        requestId = UUID.randomUUID().toString()
                    }
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    "印字幅：プリンタ設定 ${printerPaperWidthMm}mm（この画面では変更できません）",
                    color = Color.DarkGray,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(7.dp))
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(100) },
                    label = { Text("理由（必須）") },
                    modifier = Modifier.fillMaxWidth().height(82.dp),
                )
                Spacer(Modifier.height(7.dp))
                OpAuthenticatedOperator(operatorName)
                Spacer(Modifier.height(7.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                    label = { Text("責任者PIN") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val saleId = selectedSaleId ?: return@Button
                        val result = onExecute(saleId, type, quantities, reason, pin, requestId)
                        if (result != null) {
                            savedResult = result
                            lines = loadLines(saleId)
                            quantities = emptyMap()
                            reason = ""
                            pin = ""
                            requestId = UUID.randomUUID().toString()
                        }
                    },
                    enabled = canExecute,
                    modifier = Modifier.fillMaxWidth().height(53.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = OpDanger),
                ) { Text("${type.displayName}を確定", fontWeight = FontWeight.Bold) }
                (localMessage ?: message)?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = if (it.contains("失敗") || it.contains("違い") || it.contains("超え") || it.contains("できません")) OpDanger else OpGreen, fontSize = 12.sp)
                }
                savedResult?.let { result ->
                    Spacer(Modifier.height(7.dp))
                    Text("印刷ジョブ No.${result.printJobId}", fontWeight = FontWeight.Bold, color = OpNavy)
                    Text(
                        result.previewText,
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    )
                } ?: Spacer(Modifier.weight(1f))
                Text("元売上は変更せず、返品明細と反対支払を追記します。", color = Color.DarkGray, fontSize = 11.sp)
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
