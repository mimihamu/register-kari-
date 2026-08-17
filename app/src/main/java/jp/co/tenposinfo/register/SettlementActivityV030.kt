package jp.co.tenposinfo.register

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SettlementNavyV030 = Color(0xFF173F6B)
private val SettlementBlueV030 = Color(0xFF1976B9)
private val SettlementBackgroundV030 = Color(0xFFF4F7FA)
private val SettlementBorderV030 = Color(0xFFD5DEE7)
private val SettlementDangerV030 = Color(0xFFC62828)
private val SettlementGreenV030 = Color(0xFF2E7D32)
private val SettlementPaleBlueV030 = Color(0xFFEAF3FA)

/** v0.30のレスポンシブX点検・Z精算画面。 */
class SettlementActivityV030 : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureRegisterSystemBars(window)
        val reportType = runCatching {
            SettlementReportType.valueOf(intent.getStringExtra(EXTRA_REPORT_TYPE).orEmpty())
        }.getOrDefault(SettlementReportType.X_INSPECTION)
        setContent {
            MaterialTheme {
                SettlementRouteV030(reportType = reportType, onClose = { finish() })
            }
        }
    }

    companion object {
        private const val EXTRA_REPORT_TYPE = "jp.co.tenposinfo.register.extra.SETTLEMENT_REPORT_TYPE"

        fun intent(context: Context, type: SettlementReportType): Intent =
            Intent(context, SettlementActivityV030::class.java)
                .putExtra(EXTRA_REPORT_TYPE, type.name)
    }
}

@Composable
private fun SettlementRouteV030(
    reportType: SettlementReportType,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val store = remember { OperationsStore(appContext) }
    val secureStore = remember { SecureOperationsCoordinator(appContext, store) }
    var revision by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var operator by remember { mutableStateOf(OperatorSessionRegistry.current(appContext)) }

    DisposableEffect(Unit) {
        onDispose { store.close() }
    }

    val requiredPermission = when (reportType) {
        SettlementReportType.X_INSPECTION -> RegisterPermission.X_INSPECTION
        SettlementReportType.Z_SETTLEMENT -> RegisterPermission.Z_SETTLEMENT
    }
    val current = operator
    Surface(Modifier.fillMaxSize(), color = SettlementBackgroundV030) {
        if (current == null || !current.allows(requiredPermission)) {
            SettlementDeniedV030(requiredPermission, onClose)
            return@Surface
        }
        OperatorSessionRegistry.touch(appContext)
        @Suppress("UNUSED_VARIABLE") val refresh = revision
        val session = store.activeBusinessSession()
        val summary = store.dailySummary()
        val history = if (reportType == SettlementReportType.X_INSPECTION) {
            emptyList()
        } else {
            session?.let { store.recentSettlementsForSession(it.id, reportType) }.orEmpty()
        }
        SettlementScreenV030(
            reportType = reportType,
            session = session,
            summary = summary,
            history = history,
            operatorName = current.name,
            message = message,
            onExecute = { actualCash, managerPin, pendingAcknowledged ->
                if (reportType == SettlementReportType.X_INSPECTION) {
                    val result = runCatching { secureStore.inspectX() }
                    message = result.fold(
                        onSuccess = { "X点検を更新しました（固定スナップショットは保存しません）" },
                        onFailure = { it.message ?: "X点検の更新に失敗しました" },
                    )
                    if (result.isSuccess) revision++
                } else {
                    val result = runCatching {
                        secureStore.recordSettlement(
                            type = reportType,
                            actualCash = actualCash,
                            managerPin = managerPin,
                            pendingPrintsAcknowledged = pendingAcknowledged,
                        )
                    }
                    message = result.fold(
                        onSuccess = { "Z精算を保存し、営業を終了しました（No.$it）" },
                        onFailure = { it.message ?: "保存に失敗しました" },
                    )
                    if (result.isSuccess) revision++
                }
                operator = OperatorSessionRegistry.current(appContext)
            },
            onClose = onClose,
        )
    }
}

@Composable
private fun SettlementScreenV030(
    reportType: SettlementReportType,
    session: BusinessSessionRecord?,
    summary: DailyOperationsSummary,
    history: List<SettlementRecord>,
    operatorName: String,
    message: String?,
    onExecute: (Long?, String, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val metrics = rememberRegisterResponsiveMetrics()
    val isZ = reportType == SettlementReportType.Z_SETTLEMENT
    var actualCash by remember { mutableStateOf("") }
    var managerPin by remember { mutableStateOf("") }
    var pendingAcknowledged by remember { mutableStateOf(false) }
    var showConfirmation by remember { mutableStateOf(false) }
    val actual = actualCash.toLongOrNull()
    val previewActual = if (isZ) actual else actual ?: summary.expectedCash
    val variance = previewActual?.let { OperationsMath.variance(it, summary.expectedCash) }
    val preflight = if (isZ) {
        ZSettlementPreflightPolicy.evaluate(
            heldTickets = summary.heldTickets,
            pendingPrints = summary.pendingPrints,
            pendingPrintsAcknowledged = pendingAcknowledged,
        )
    } else {
        ZSettlementPreflightResult(
            mayProceed = true,
            heldTickets = summary.heldTickets,
            pendingPrints = summary.pendingPrints,
            requiresPendingPrintAcknowledgement = false,
            message = null,
        )
    }

    if (showConfirmation && previewActual != null && variance != null) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = { Text("Z精算して営業を終了しますか？", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("営業日 ${summary.businessDate} / セッションNo.${summary.businessSessionId}")
                    Text("純売上 ${settlementYenV030(summary.netSales)}")
                    Text("現金実査 ${settlementYenV030(previewActual)} / 過不足 ${settlementSignedYenV030(variance)}")
                    Text("未会計 ${summary.heldTickets}件 / 未印刷 ${summary.pendingPrints}件")
                    Text("完了後、この営業セッションでは販売できません。", color = SettlementDangerV030, fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmation = false
                        onExecute(actual, managerPin, pendingAcknowledged)
                    },
                    enabled = managerPin.isNotBlank() && preflight.mayProceed,
                    colors = ButtonDefaults.buttonColors(containerColor = SettlementDangerV030),
                ) { Text("Z精算して営業終了") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showConfirmation = false }) { Text("戻る") }
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        SettlementHeaderV030(metrics, reportType)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val compact = metrics.isCompact || maxWidth < 1_080.dp || maxHeight < 560.dp
            if (compact) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(metrics.screenPaddingDp.dp),
                    verticalArrangement = Arrangement.spacedBy(metrics.panelGapDp.dp),
                ) {
                    SettlementInputPanelV030(
                        modifier = Modifier.fillMaxWidth(),
                        reportType = reportType,
                        session = session,
                        summary = summary,
                        operatorName = operatorName,
                        actualCash = actualCash,
                        managerPin = managerPin,
                        pendingAcknowledged = pendingAcknowledged,
                        preflight = preflight,
                        message = message,
                        onActualCashChanged = { actualCash = it.filter(Char::isDigit).take(12) },
                        onManagerPinChanged = { managerPin = it.filter(Char::isDigit).take(8) },
                        onPendingAcknowledgedChanged = { pendingAcknowledged = it },
                        onExecute = {
                            if (isZ) {
                                if (SettlementActualCashSafetyV105.maySubmit(reportType, actual)) showConfirmation = true
                            } else {
                                onExecute(actual, "", false)
                            }
                        },
                    )
                    SettlementPreviewPanelV030(
                        Modifier.fillMaxWidth(),
                        summary,
                        previewActual,
                        variance,
                    )
                    SettlementHistoryPanelV030(
                        Modifier.fillMaxWidth().heightIn(min = 180.dp),
                        reportType,
                        history,
                    )
                }
            } else {
                Row(
                    Modifier.fillMaxSize().padding(metrics.screenPaddingDp.dp),
                    horizontalArrangement = Arrangement.spacedBy(metrics.panelGapDp.dp),
                ) {
                    SettlementInputPanelV030(
                        modifier = Modifier.weight(1.15f).fillMaxHeight(),
                        reportType = reportType,
                        session = session,
                        summary = summary,
                        operatorName = operatorName,
                        actualCash = actualCash,
                        managerPin = managerPin,
                        pendingAcknowledged = pendingAcknowledged,
                        preflight = preflight,
                        message = message,
                        onActualCashChanged = { actualCash = it.filter(Char::isDigit).take(12) },
                        onManagerPinChanged = { managerPin = it.filter(Char::isDigit).take(8) },
                        onPendingAcknowledgedChanged = { pendingAcknowledged = it },
                        onExecute = {
                            if (isZ) {
                                if (SettlementActualCashSafetyV105.maySubmit(reportType, actual)) showConfirmation = true
                            } else {
                                onExecute(actual, "", false)
                            }
                        },
                    )
                    SettlementPreviewPanelV030(
                        Modifier.weight(0.9f).fillMaxHeight(),
                        summary,
                        previewActual,
                        variance,
                    )
                    SettlementHistoryPanelV030(
                        Modifier.weight(1.05f).fillMaxHeight(),
                        reportType,
                        history,
                    )
                }
            }
        }
        SettlementBottomV030(metrics, onClose)
    }
}

@Composable
private fun SettlementInputPanelV030(
    modifier: Modifier,
    reportType: SettlementReportType,
    session: BusinessSessionRecord?,
    summary: DailyOperationsSummary,
    operatorName: String,
    actualCash: String,
    managerPin: String,
    pendingAcknowledged: Boolean,
    preflight: ZSettlementPreflightResult,
    message: String?,
    onActualCashChanged: (String) -> Unit,
    onManagerPinChanged: (String) -> Unit,
    onPendingAcknowledgedChanged: (Boolean) -> Unit,
    onExecute: () -> Unit,
) {
    val isZ = reportType == SettlementReportType.Z_SETTLEMENT
    SettlementPanelV030(modifier) {
        Text(
            if (isZ) "Z精算・営業終了" else "X点検",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = if (isZ) SettlementDangerV030 else SettlementNavyV030,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            session?.let { "対象: ${it.businessDate} / セッションNo.${it.id}" } ?: "営業中のセッションがありません",
            color = if (session == null) SettlementDangerV030 else SettlementGreenV030,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = actualCash,
            onValueChange = onActualCashChanged,
            label = { Text(if (isZ) "現金実査額（必須）" else "現金実査額（空欄は理論額）") },
            supportingText = {
                Text(
                    if (isZ) SettlementActualCashSafetyV105.Z_REQUIRED_MESSAGE
                    else "X点検では未入力の場合、理論現金を実在高として使用します。",
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().background(SettlementPaleBlueV030, RoundedCornerShape(8.dp)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("操作担当", color = Color.DarkGray)
            Spacer(Modifier.weight(1f))
            Text(operatorName, fontWeight = FontWeight.Bold, color = SettlementNavyV030)
        }
        if (isZ) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = managerPin,
                onValueChange = onManagerPinChanged,
                label = { Text("責任者PIN") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (summary.heldTickets > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "未会計伝票が${summary.heldTickets}件あります。会計または取消完了までZ精算できません。",
                    color = SettlementDangerV030,
                    fontWeight = FontWeight.Bold,
                )
            } else if (summary.pendingPrints > 0) {
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth().clickable {
                        onPendingAcknowledgedChanged(!pendingAcknowledged)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = pendingAcknowledged,
                        onCheckedChange = onPendingAcknowledgedChanged,
                    )
                    Text(
                        "未印刷${summary.pendingPrints}件を確認し、未印刷のまま精算する",
                        color = SettlementDangerV030,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        preflight.message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = SettlementDangerV030, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onExecute,
            enabled = session != null &&
                SettlementActualCashSafetyV105.maySubmit(reportType, actualCash.toLongOrNull()) &&
                (!isZ || (!summary.settled && summary.heldTickets == 0)),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isZ) SettlementDangerV030 else SettlementBlueV030,
            ),
        ) {
            Text(if (isZ) "Z精算の確認へ" else "X点検を実行", fontWeight = FontWeight.Bold)
        }
        if (message != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                color = if (message.contains("保存しました") || message.contains("終了しました")) {
                    SettlementGreenV030
                } else {
                    SettlementDangerV030
                },
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SettlementPreviewPanelV030(
    modifier: Modifier,
    summary: DailyOperationsSummary,
    previewActual: Long?,
    variance: Long?,
) {
    SettlementPanelV030(modifier) {
        Text("プレビュー", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SettlementNavyV030)
        Spacer(Modifier.height(10.dp))
        SettlementAmountV030("営業セッション", if (summary.businessSessionId > 0) "No.${summary.businessSessionId}" else "未開始")
        SettlementAmountV030("営業日", summary.businessDate)
        SettlementAmountV030("売上総額", settlementYenV030(summary.salesGross))
        SettlementAmountV030("返品・取消", "-${settlementYenV030(summary.reversalGross)}")
        SettlementAmountV030("純売上", settlementYenV030(summary.netSales), true)
        SettlementAmountV030("現金理論", settlementYenV030(summary.expectedCash))
        SettlementAmountV030("現金実査", previewActual?.let(::settlementYenV030) ?: "未入力")
        SettlementAmountV030("過不足", variance?.let(::settlementSignedYenV030) ?: "未計算", true)
        SettlementAmountV030("未印刷", "${summary.pendingPrints}件")
        SettlementAmountV030("未会計伝票", "${summary.heldTickets}件")
    }
}

@Composable
private fun SettlementHistoryPanelV030(
    modifier: Modifier,
    reportType: SettlementReportType,
    history: List<SettlementRecord>,
) {
    val isZ = reportType == SettlementReportType.Z_SETTLEMENT
    SettlementPanelV030(modifier) {
        Text(if (isZ) "保存履歴" else "X点検の扱い", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SettlementNavyV030)
        Spacer(Modifier.height(8.dp))
        if (!isZ) {
            Box(Modifier.fillMaxWidth().heightIn(min = 100.dp), contentAlignment = Alignment.Center) {
                Text("X点検はリアルタイム表示のみです。固定履歴・固定帳票・印刷ジョブは作成しません。", color = Color.Gray)
            }
        } else if (history.isEmpty()) {
            Box(Modifier.fillMaxWidth().heightIn(min = 100.dp), contentAlignment = Alignment.Center) {
                Text("履歴はありません", color = Color.Gray)
            }
        } else {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                history.take(30).forEach { record ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(record.type.displayName, fontWeight = FontWeight.Bold, color = SettlementNavyV030)
                            Spacer(Modifier.weight(1f))
                            Text(settlementDateTimeV030(record.createdAt), color = Color.Gray)
                        }
                        Text("${record.businessDate}  セッションNo.${record.businessSessionId}")
                        Text("純売上 ${settlementYenV030(record.netSales)}  差異 ${settlementSignedYenV030(record.variance)}")
                        Text("担当 ${record.operatorName}", color = Color.Gray)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettlementPanelV030(
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val metrics = rememberRegisterResponsiveMetrics()
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, SettlementBorderV030),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(metrics.cardPaddingDp.dp), content = content)
    }
}

@Composable
private fun SettlementAmountV030(label: String, value: String, emphasized: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = if (emphasized) SettlementNavyV030 else Color.DarkGray,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            value,
            fontSize = if (emphasized) 20.sp else 16.sp,
            fontWeight = FontWeight.Bold,
            color = if (emphasized) SettlementNavyV030 else Color.Black,
        )
    }
}

@Composable
private fun SettlementHeaderV030(
    metrics: RegisterResponsiveMetrics,
    reportType: SettlementReportType,
) {
    val title = if (reportType == SettlementReportType.Z_SETTLEMENT) "SCR-500-Z  Z精算・営業終了" else "SCR-500-X  X点検"
    Row(
        Modifier
            .fillMaxWidth()
            .height(metrics.headerHeightDp.dp)
            .background(SettlementNavyV030)
            .padding(horizontal = metrics.screenPaddingDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("つぐレジ", color = Color.White, fontSize = if (metrics.isCompact) 19.sp else 23.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(if (metrics.isCompact) 12.dp else 24.dp))
        Text(title, color = Color.White, fontSize = if (metrics.isCompact) 17.sp else 21.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        if (!metrics.isCompact) Text("オフライン管理", color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun SettlementBottomV030(metrics: RegisterResponsiveMetrics, onClose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(metrics.bottomBarHeightDp.dp)
            .background(Color.White)
            .padding(horizontal = metrics.screenPaddingDp.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).fillMaxHeight()) {
            Text("レジ管理へ戻る", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SettlementDeniedV030(permission: RegisterPermission, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("${permission.displayName}を利用できません", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = SettlementDangerV030)
        Spacer(Modifier.height(12.dp))
        Text("権限がないか、ログインセッションが失効しています。")
        Spacer(Modifier.height(24.dp))
        Button(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("閉じる") }
    }
}

private fun settlementYenV030(value: Long): String =
    NumberFormat.getCurrencyInstance(Locale.JAPAN).format(value)

private fun settlementSignedYenV030(value: Long): String = when {
    value > 0 -> "+${settlementYenV030(value)}"
    value < 0 -> "-${settlementYenV030(-value)}"
    else -> settlementYenV030(0)
}

private fun settlementDateTimeV030(epochMillis: Long): String =
    SimpleDateFormat("MM/dd HH:mm", Locale.JAPAN).format(Date(epochMillis))
