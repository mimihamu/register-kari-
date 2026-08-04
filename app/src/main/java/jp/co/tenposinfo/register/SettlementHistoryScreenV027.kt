package jp.co.tenposinfo.register

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

private val HistoryNavy = Color(0xFF173F6B)
private val HistoryBlue = Color(0xFF1976B9)
private val HistoryDanger = Color(0xFFC62828)
private val HistoryGreen = Color(0xFF2E7D32)
private val HistoryBorder = Color(0xFFD5DEE7)
private val HistoryPanel = Color.White
private val HistorySelected = Color(0xFFE3F2FD)

@Composable
internal fun SettlementHistoryScreenV027(
    sessions: List<BusinessSessionRecord>,
    settlements: List<SettlementRecord>,
    operatorName: String,
    permissions: Set<RegisterPermission>,
    revision: Int,
    message: String?,
    printerPaperWidthMm: Int,
    previewLoader: (Long) -> String,
    onReprint: (SettlementRecord, String) -> Unit,
    onBack: () -> Unit,
) {
    var selectedSessionId by remember {
        mutableStateOf(settlements.firstOrNull()?.businessSessionId ?: sessions.firstOrNull()?.id)
    }
    var selectedType by remember { mutableStateOf<SettlementReportType?>(null) }
    var selectedReportId by remember { mutableStateOf<Long?>(settlements.firstOrNull()?.id) }
    var managerPin by remember { mutableStateOf("") }
    @Suppress("UNUSED_VARIABLE") val refresh = revision

    val filtered = SettlementHistoryPolicyV027.filter(
        records = settlements,
        businessSessionId = selectedSessionId,
        type = selectedType,
    )
    val selected = filtered.firstOrNull { it.id == selectedReportId } ?: filtered.firstOrNull()

    LaunchedEffect(selectedSessionId, selectedType, settlements.size, revision) {
        if (selectedReportId == null || filtered.none { it.id == selectedReportId }) {
            selectedReportId = filtered.firstOrNull()?.id
            managerPin = ""
        }
    }

    Column(Modifier.fillMaxSize()) {
        SettlementHistoryHeader()
        Row(
            Modifier.weight(1f).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HistoryPanel(Modifier.width(220.dp).fillMaxHeight()) {
                Text("営業セッション", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = HistoryNavy)
                Spacer(Modifier.height(8.dp))
                HistoryChoice(
                    label = "すべてのセッション",
                    selected = selectedSessionId == null,
                    onClick = {
                        selectedSessionId = null
                        selectedReportId = null
                        managerPin = ""
                    },
                )
                Spacer(Modifier.height(6.dp))
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(sessions, key = { it.id }) { session ->
                        HistoryChoice(
                            label = buildString {
                                append("No.${session.id}  ${session.businessDate}")
                                append("\n${session.status.displayName}")
                                session.closedAt?.let { append("  ${historyDateTime(it)}") }
                            },
                            selected = selectedSessionId == session.id,
                            onClick = {
                                selectedSessionId = session.id
                                selectedReportId = null
                                managerPin = ""
                            },
                        )
                    }
                }
            }

            HistoryPanel(Modifier.width(310.dp).fillMaxHeight()) {
                Text("点検・精算履歴", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = HistoryNavy)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HistoryFilterButton("すべて", selectedType == null, Modifier.weight(1f)) {
                        selectedType = null
                        selectedReportId = null
                    }
                    HistoryFilterButton(
                        "X点検",
                        selectedType == SettlementReportType.X_INSPECTION,
                        Modifier.weight(1f),
                    ) {
                        selectedType = SettlementReportType.X_INSPECTION
                        selectedReportId = null
                    }
                    HistoryFilterButton(
                        "Z精算",
                        selectedType == SettlementReportType.Z_SETTLEMENT,
                        Modifier.weight(1f),
                    ) {
                        selectedType = SettlementReportType.Z_SETTLEMENT
                        selectedReportId = null
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (filtered.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("該当する履歴はありません", color = Color.Gray)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        items(filtered, key = { it.id }) { record ->
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    selectedReportId = record.id
                                    managerPin = ""
                                },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (selected?.id == record.id) HistorySelected else Color.White,
                                ),
                                border = BorderStroke(1.dp, HistoryBorder),
                                shape = RoundedCornerShape(9.dp),
                            ) {
                                Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                    Row(Modifier.fillMaxWidth()) {
                                        Text(
                                            "${record.type.displayName} No.${record.id}",
                                            fontWeight = FontWeight.Bold,
                                            color = if (record.type == SettlementReportType.Z_SETTLEMENT) {
                                                HistoryDanger
                                            } else {
                                                HistoryNavy
                                            },
                                        )
                                        Spacer(Modifier.weight(1f))
                                        Text(historyDateTime(record.createdAt), color = Color.Gray, fontSize = 12.sp)
                                    }
                                    Text(
                                        "${record.businessDate} / セッションNo.${record.businessSessionId}",
                                        fontSize = 13.sp,
                                    )
                                    Text(
                                        "純売上 ${historyYen(record.netSales)} / 過不足 ${historySignedYen(record.variance)}",
                                        fontSize = 13.sp,
                                    )
                                    Text("担当 ${record.operatorName}", color = Color.Gray, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            HistoryPanel(Modifier.width(300.dp).fillMaxHeight()) {
                Text("保存内容", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = HistoryNavy)
                Spacer(Modifier.height(8.dp))
                if (selected == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("履歴を選択してください", color = Color.Gray)
                    }
                } else {
                    HistoryAmountRow("種別", selected.type.displayName)
                    HistoryAmountRow("レポートNo.", selected.id.toString())
                    HistoryAmountRow("営業日", selected.businessDate)
                    HistoryAmountRow("営業セッション", "No.${selected.businessSessionId}")
                    HistoryAmountRow("売上総額", historyYen(selected.salesGross))
                    HistoryAmountRow("返品・取消", "-${historyYen(selected.reversalGross)}")
                    HistoryAmountRow("純売上", historyYen(selected.netSales), true)
                    HistoryAmountRow("現金理論", historyYen(selected.expectedCash))
                    HistoryAmountRow("現金実査", historyYen(selected.actualCash))
                    HistoryAmountRow("過不足", historySignedYen(selected.variance), true)
                    HistoryAmountRow("売上件数", "${selected.transactionCount}件")
                    HistoryAmountRow("未印刷", "${selected.pendingPrints}件")
                    HistoryAmountRow("未会計伝票", "${selected.heldTickets}件")
                    HistoryAmountRow(
                        "保存明細",
                        if (selected.snapshotVersion >= SettlementSnapshotSchemaV027.SNAPSHOT_VERSION) {
                            "完全保存"
                        } else {
                            "旧形式"
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "担当 ${selected.operatorName}\n発行 ${historyDateTime(selected.createdAt)}",
                        color = Color.DarkGray,
                        fontSize = 13.sp,
                    )
                }
            }

            HistoryPanel(Modifier.weight(1f).fillMaxHeight()) {
                Text("印字プレビュー・再印字", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = HistoryNavy)
                Spacer(Modifier.height(8.dp))
                if (selected == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                        Modifier.weight(1f).fillMaxWidth()
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
                    Spacer(Modifier.height(8.dp))
                    val requiredPermission = SettlementHistoryPolicyV027.permissionFor(selected.type)
                    val hasPermission = requiredPermission in permissions
                    if (selected.type == SettlementReportType.Z_SETTLEMENT) {
                        OutlinedTextField(
                            value = managerPin,
                            onValueChange = { managerPin = it.filter(Char::isDigit).take(8) },
                            label = { Text("Z精算票再印字の責任者PIN") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(
                        when {
                            !hasPermission -> "${requiredPermission.displayName}の権限がありません"
                            selected.type == SettlementReportType.Z_SETTLEMENT ->
                                "Z精算票の再印字は責任者PINと監査ログを必須とします。"
                            else -> "X点検票の再印字はX点検権限で実行できます。"
                        },
                        color = if (hasPermission) Color.DarkGray else HistoryDanger,
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
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected.type == SettlementReportType.Z_SETTLEMENT) {
                                HistoryDanger
                            } else {
                                HistoryBlue
                            },
                        ),
                    ) {
                        Text("${selected.type.displayName}票を再印字", fontWeight = FontWeight.Bold)
                    }
                    if (message != null) {
                        Spacer(Modifier.height(7.dp))
                        Text(
                            message,
                            color = if (
                                message.contains("失敗") || message.contains("違い") ||
                                message.contains("権限") || message.contains("できません")
                            ) {
                                HistoryDanger
                            } else {
                                HistoryGreen
                            },
                            fontSize = 13.sp,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("ログイン担当: $operatorName", color = Color.Gray, fontSize = 12.sp)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 16.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.width(220.dp).fillMaxHeight()) {
                Text("レジ管理へ戻る", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SettlementHistoryHeader() {
    Row(
        Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("SCR-520", color = Color.Gray)
        Spacer(Modifier.width(16.dp))
        Text("点検・精算履歴／再印字", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = HistoryNavy)
    }
}

@Composable
private fun HistoryPanel(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = HistoryPanel),
        border = BorderStroke(1.dp, HistoryBorder),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp), content = content)
    }
}

@Composable
private fun HistoryChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (selected) HistorySelected else Color.White),
        border = BorderStroke(1.dp, if (selected) HistoryBlue else HistoryBorder),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            label,
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            color = HistoryNavy,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun HistoryFilterButton(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier.height(42.dp)) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.height(42.dp)) { Text(label) }
    }
}

@Composable
private fun HistoryAmountRow(label: String, value: String, emphasized: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.DarkGray, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            color = if (emphasized) HistoryNavy else Color.Black,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal,
            fontSize = if (emphasized) 16.sp else 13.sp,
            textAlign = TextAlign.End,
        )
    }
}

private fun historyYen(value: Long): String =
    NumberFormat.getCurrencyInstance(Locale.JAPAN).format(value)

private fun historySignedYen(value: Long): String = when {
    value > 0 -> "+${historyYen(value)}"
    value < 0 -> "-${historyYen(-value)}"
    else -> historyYen(0)
}

private fun historyDateTime(value: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(Date(value))
