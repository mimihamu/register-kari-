package jp.co.tenposinfo.register

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val HistoryNavyV030 = Color(0xFF173F6B)
private val HistoryBlueV030 = Color(0xFF1976B9)
private val HistoryBackgroundV030 = Color(0xFFF4F7FA)
private val HistoryBorderV030 = Color(0xFFD5DEE7)
private val HistoryDangerV030 = Color(0xFFC62828)
private val HistoryGreenV030 = Color(0xFF2E7D32)
private val HistorySelectedV030 = Color(0xFFE3F2FD)

/** v0.30のレスポンシブな点検・精算履歴画面。 */
class SettlementHistoryActivityV030 : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureRegisterSystemBars(window)
        setContent {
            MaterialTheme {
                SettlementHistoryRouteV030(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun SettlementHistoryRouteV030(onClose: () -> Unit) {
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

    val current = operator
    Surface(Modifier.fillMaxSize(), color = HistoryBackgroundV030) {
        if (current == null || !SettlementHistoryPolicyV027.canView(current.permissions)) {
            HistoryDeniedV030(onClose)
            return@Surface
        }
        OperatorSessionRegistry.touch(appContext)
        @Suppress("UNUSED_VARIABLE") val refresh = revision
        SettlementHistoryScreenV030(
            sessions = store.recentBusinessSessions(100),
            settlements = store.recentSettlements(500),
            permissions = current.permissions,
            operatorName = current.name,
            message = message,
            printerPaperWidthMm = PrinterPaperSettingPolicy.currentWidthMm(appContext),
            previewLoader = store::previewSettlement,
            onReprint = { record, managerPin ->
                val result = runCatching {
                    secureStore.reprintSettlement(record.id, managerPin)
                }
                message = result.fold(
                    onSuccess = { "${record.type.displayName}票の再印字を受け付けました（印刷ジョブNo.$it）" },
                    onFailure = { it.message ?: "再印字に失敗しました" },
                )
                if (result.isSuccess) revision++
                operator = OperatorSessionRegistry.current(appContext)
            },
            onClose = onClose,
        )
    }
}

@Composable
private fun SettlementHistoryScreenV030(
    sessions: List<BusinessSessionRecord>,
    settlements: List<SettlementRecord>,
    permissions: Set<RegisterPermission>,
    operatorName: String,
    message: String?,
    printerPaperWidthMm: Int,
    previewLoader: (Long) -> String,
    onReprint: (SettlementRecord, String) -> Unit,
    onClose: () -> Unit,
) {
    val metrics = rememberRegisterResponsiveMetrics()
    var selectedSessionId by remember {
        mutableStateOf(settlements.firstOrNull()?.businessSessionId ?: sessions.firstOrNull()?.id)
    }
    var selectedType by remember { mutableStateOf<SettlementReportType?>(null) }
    var selectedReportId by remember { mutableStateOf<Long?>(settlements.firstOrNull()?.id) }
    var managerPin by remember { mutableStateOf("") }

    val filtered = SettlementHistoryPolicyV027.filter(
        records = settlements,
        businessSessionId = selectedSessionId,
        type = selectedType,
    )
    val selected = filtered.firstOrNull { it.id == selectedReportId } ?: filtered.firstOrNull()

    LaunchedEffect(selectedSessionId, selectedType, settlements.size) {
        if (selectedReportId == null || filtered.none { it.id == selectedReportId }) {
            selectedReportId = filtered.firstOrNull()?.id
            managerPin = ""
        }
    }

    Column(Modifier.fillMaxSize()) {
        HistoryHeaderV030(metrics)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val compact = metrics.isCompact || maxWidth < 1_100.dp || maxHeight < 560.dp
            if (compact) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(metrics.screenPaddingDp.dp),
                    verticalArrangement = Arrangement.spacedBy(metrics.panelGapDp.dp),
                ) {
                    HistoryFilterAndListV030(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
                        sessions = sessions,
                        filtered = filtered,
                        selectedSessionId = selectedSessionId,
                        selectedType = selectedType,
                        selectedReportId = selected?.id,
                        onSessionChanged = {
                            selectedSessionId = it
                            selectedReportId = null
                            managerPin = ""
                        },
                        onTypeChanged = {
                            selectedType = it
                            selectedReportId = null
                            managerPin = ""
                        },
                        onReportSelected = {
                            selectedReportId = it
                            managerPin = ""
                        },
                    )
                    HistoryDetailV030(Modifier.fillMaxWidth(), selected, operatorName)
                    HistoryPreviewV030(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 420.dp),
                        selected = selected,
                        permissions = permissions,
                        printerPaperWidthMm = printerPaperWidthMm,
                        managerPin = managerPin,
                        message = message,
                        previewLoader = previewLoader,
                        onManagerPinChanged = { managerPin = it.filter(Char::isDigit).take(8) },
                        onReprint = onReprint,
                    )
                }
            } else {
                Row(
                    Modifier.fillMaxSize().padding(metrics.screenPaddingDp.dp),
                    horizontalArrangement = Arrangement.spacedBy(metrics.panelGapDp.dp),
                ) {
                    HistoryFilterAndListV030(
                        modifier = Modifier.weight(1.05f).fillMaxHeight(),
                        sessions = sessions,
                        filtered = filtered,
                        selectedSessionId = selectedSessionId,
                        selectedType = selectedType,
                        selectedReportId = selected?.id,
                        onSessionChanged = {
                            selectedSessionId = it
                            selectedReportId = null
                            managerPin = ""
                        },
                        onTypeChanged = {
                            selectedType = it
                            selectedReportId = null
                            managerPin = ""
                        },
                        onReportSelected = {
                            selectedReportId = it
                            managerPin = ""
                        },
                    )
                    HistoryDetailV030(Modifier.weight(0.88f).fillMaxHeight(), selected, operatorName)
                    HistoryPreviewV030(
                        modifier = Modifier.weight(1.35f).fillMaxHeight(),
                        selected = selected,
                        permissions = permissions,
                        printerPaperWidthMm = printerPaperWidthMm,
                        managerPin = managerPin,
                        message = message,
                        previewLoader = previewLoader,
                        onManagerPinChanged = { managerPin = it.filter(Char::isDigit).take(8) },
                        onReprint = onReprint,
                    )
                }
            }
        }
        HistoryBottomV030(metrics, onClose)
    }
}

@Composable
private fun HistoryFilterAndListV030(
    modifier: Modifier,
    sessions: List<BusinessSessionRecord>,
    filtered: List<SettlementRecord>,
    selectedSessionId: Long?,
    selectedType: SettlementReportType?,
    selectedReportId: Long?,
    onSessionChanged: (Long?) -> Unit,
    onTypeChanged: (SettlementReportType?) -> Unit,
    onReportSelected: (Long) -> Unit,
) {
    HistoryPanelV030(modifier) {
        Text("点検・精算履歴", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = HistoryNavyV030)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                val ids = listOf<Long?>(null) + sessions.map { it.id }
                val index = ids.indexOf(selectedSessionId).takeIf { it >= 0 } ?: 0
                onSessionChanged(ids[(index + 1) % ids.size])
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            val session = sessions.firstOrNull { it.id == selectedSessionId }
            Text(
                session?.let { "セッション No.${it.id} / ${it.businessDate}" } ?: "すべてのセッション",
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HistoryChoiceButtonV030("すべて", selectedType == null, Modifier.weight(1f)) { onTypeChanged(null) }
            HistoryChoiceButtonV030(
                "X点検",
                selectedType == SettlementReportType.X_INSPECTION,
                Modifier.weight(1f),
            ) { onTypeChanged(SettlementReportType.X_INSPECTION) }
            HistoryChoiceButtonV030(
                "Z精算",
                selectedType == SettlementReportType.Z_SETTLEMENT,
                Modifier.weight(1f),
            ) { onTypeChanged(SettlementReportType.Z_SETTLEMENT) }
        }
        Spacer(Modifier.height(8.dp))
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
                Text("該当する履歴はありません", color = Color.Gray)
            }
        } else {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                filtered.take(100).forEach { record ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clickable { onReportSelected(record.id) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selectedReportId == record.id) HistorySelectedV030 else Color.White,
                        ),
                        border = BorderStroke(1.dp, HistoryBorderV030),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(10.dp)) {
                            Row(Modifier.fillMaxWidth()) {
                                Text(
                                    "${record.type.displayName} No.${record.id}",
                                    fontWeight = FontWeight.Bold,
                                    color = if (record.type == SettlementReportType.Z_SETTLEMENT) HistoryDangerV030 else HistoryNavyV030,
                                )
                                Spacer(Modifier.weight(1f))
                                Text(historyDateTimeV030(record.createdAt), color = Color.Gray, fontSize = 12.sp)
                            }
                            Text("${record.businessDate} / セッションNo.${record.businessSessionId}", fontSize = 13.sp)
                            Text("純売上 ${historyYenV030(record.netSales)} / 過不足 ${historySignedYenV030(record.variance)}", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryDetailV030(
    modifier: Modifier,
    selected: SettlementRecord?,
    operatorName: String,
) {
    HistoryPanelV030(modifier) {
        Text("保存内容", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = HistoryNavyV030)
        Spacer(Modifier.height(8.dp))
        if (selected == null) {
            Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
                Text("履歴を選択してください", color = Color.Gray)
            }
        } else {
            HistoryAmountV030("種別", selected.type.displayName)
            HistoryAmountV030("レポートNo.", selected.id.toString())
            HistoryAmountV030("営業日", selected.businessDate)
            HistoryAmountV030("営業セッション", "No.${selected.businessSessionId}")
            HistoryAmountV030("売上総額", historyYenV030(selected.salesGross))
            HistoryAmountV030("返品・取消", "-${historyYenV030(selected.reversalGross)}")
            HistoryAmountV030("純売上", historyYenV030(selected.netSales), true)
            HistoryAmountV030("現金理論", historyYenV030(selected.expectedCash))
            HistoryAmountV030("現金実査", historyYenV030(selected.actualCash))
            HistoryAmountV030("過不足", historySignedYenV030(selected.variance), true)
            HistoryAmountV030("売上件数", "${selected.transactionCount}件")
            HistoryAmountV030("未印刷", "${selected.pendingPrints}件")
            HistoryAmountV030("未会計伝票", "${selected.heldTickets}件")
            Spacer(Modifier.height(8.dp))
            Text("保存担当 ${selected.operatorName}", color = Color.DarkGray)
            Text("閲覧担当 $operatorName", color = Color.DarkGray)
            Text("発行 ${historyDateTimeV030(selected.createdAt)}", color = Color.DarkGray)
        }
    }
}

@Composable
private fun HistoryPreviewV030(
    modifier: Modifier,
    selected: SettlementRecord?,
    permissions: Set<RegisterPermission>,
    printerPaperWidthMm: Int,
    managerPin: String,
    message: String?,
    previewLoader: (Long) -> String,
    onManagerPinChanged: (String) -> Unit,
    onReprint: (SettlementRecord, String) -> Unit,
) {
    HistoryPanelV030(modifier) {
        Text("印字プレビュー・再印字", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = HistoryNavyV030)
        Spacer(Modifier.height(8.dp))
        if (selected == null) {
            Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) {
                Text("履歴を選択してください", color = Color.Gray)
            }
        } else {
            Text(
                "印字幅：プリンタ設定 ${printerPaperWidthMm}mm（再印字時は変更しません）",
                color = Color.DarkGray,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            val preview = runCatching { previewLoader(selected.id) }
                .getOrElse { it.message ?: "印字内容を復元できません" }
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(min = 180.dp, max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
            ) {
                Text(
                    preview,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                )
            }
            SettlementHistoryReconciliationActionV080(selected, permissions)
            val requiredPermission = SettlementHistoryPolicyV027.permissionFor(selected.type)
            val hasPermission = requiredPermission in permissions
            if (selected.type == SettlementReportType.Z_SETTLEMENT) {
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = managerPin,
                    onValueChange = onManagerPinChanged,
                    label = { Text("Z精算票再印字の責任者PIN") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    !hasPermission -> "${requiredPermission.displayName}の権限がありません"
                    selected.type == SettlementReportType.Z_SETTLEMENT -> "Z精算票の再印字は責任者PINと監査ログを必須とします。"
                    else -> "X点検票の再印字はX点検権限で実行できます。"
                },
                color = if (hasPermission) Color.DarkGray else HistoryDangerV030,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = { onReprint(selected, managerPin) },
                enabled = SettlementHistoryPolicyV027.canReprint(
                    record = selected,
                    permissions = permissions,
                    managerPinProvided = managerPin.isNotBlank(),
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected.type == SettlementReportType.Z_SETTLEMENT) HistoryDangerV030 else HistoryBlueV030,
                ),
            ) { Text("${selected.type.displayName}票を再印字", fontWeight = FontWeight.Bold) }
            if (message != null) {
                Spacer(Modifier.height(7.dp))
                Text(
                    message,
                    color = if (message.contains("受け付けました")) HistoryGreenV030 else HistoryDangerV030,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun HistoryPanelV030(
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val metrics = rememberRegisterResponsiveMetrics()
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, HistoryBorderV030),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(metrics.cardPaddingDp.dp), content = content)
    }
}

@Composable
private fun HistoryChoiceButtonV030(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) HistoryDangerV030 else HistoryBorderV030),
    ) { Text(label, fontWeight = FontWeight.Bold, color = HistoryNavyV030) }
}

@Composable
private fun HistoryAmountV030(label: String, value: String, emphasized: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = if (emphasized) HistoryNavyV030 else Color.DarkGray,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            value,
            fontSize = if (emphasized) 20.sp else 16.sp,
            fontWeight = FontWeight.Bold,
            color = if (emphasized) HistoryNavyV030 else Color.Black,
        )
    }
}

@Composable
private fun HistoryHeaderV030(metrics: RegisterResponsiveMetrics) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(metrics.headerHeightDp.dp)
            .background(HistoryNavyV030)
            .padding(horizontal = metrics.screenPaddingDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("つぐレジ", color = Color.White, fontSize = if (metrics.isCompact) 19.sp else 23.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(if (metrics.isCompact) 12.dp else 24.dp))
        Text("SCR-520  点検・精算履歴", color = Color.White, fontSize = if (metrics.isCompact) 17.sp else 21.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        if (!metrics.isCompact) Text("オフライン管理", color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun HistoryBottomV030(metrics: RegisterResponsiveMetrics, onClose: () -> Unit) {
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
private fun HistoryDeniedV030(onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("点検・精算履歴を利用できません", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = HistoryDangerV030)
        Spacer(Modifier.height(12.dp))
        Text("X点検またはZ精算権限がないか、ログインセッションが失効しています。")
        Spacer(Modifier.height(24.dp))
        Button(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("閉じる") }
    }
}

private fun historyYenV030(value: Long): String =
    NumberFormat.getCurrencyInstance(Locale.JAPAN).format(value)

private fun historySignedYenV030(value: Long): String = when {
    value > 0 -> "+${historyYenV030(value)}"
    value < 0 -> "-${historyYenV030(-value)}"
    else -> historyYenV030(0)
}

private fun historyDateTimeV030(epochMillis: Long): String =
    SimpleDateFormat("MM/dd HH:mm", Locale.JAPAN).format(Date(epochMillis))