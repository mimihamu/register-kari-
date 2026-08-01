package jp.co.tenposinfo.register

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PsNavy = Color(0xFF173F6B)
private val PsBlue = Color(0xFF1976B9)
private val PsGreen = Color(0xFF2E7D32)
private val PsOrange = Color(0xFFEF6C00)
private val PsRed = Color(0xFFC62828)
private val PsBackground = Color(0xFFF4F7FA)
private val PsBorder = Color(0xFFD5DEE7)

class PrinterStatusActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                PrinterStatusApp(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun PrinterStatusApp(onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settingsStore = remember { AdminSettingsStore(context.applicationContext) }
    val monitoringStore = remember { PrinterMonitoringStore(context.applicationContext) }
    val actor = remember { OperatorSessionRegistry.lastKnownName() ?: "プリンター診断" }
    var configuration by remember { mutableStateOf(settingsStore.loadPrinterConfiguration()) }
    var runtimeSettings by remember { mutableStateOf(monitoringStore.loadSettings()) }
    var retentionText by remember { mutableStateOf(runtimeSettings.historyRetentionDays.toString()) }
    var status by remember { mutableStateOf<PrinterRealtimeStatus?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var operationMessage by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var autoMonitor by remember { mutableStateOf(false) }
    var experimentalConfirmed by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(monitoringStore.listHistory(100)) }
    val scope = rememberCoroutineScope()
    val capability = PrinterStatusCapabilityRegistry.forProfile(configuration.profile)

    DisposableEffect(Unit) {
        onDispose {
            monitoringStore.close()
            settingsStore.close()
        }
    }

    suspend fun reloadHistory() {
        history = withContext(Dispatchers.IO) { monitoringStore.listHistory(100) }
    }

    suspend fun performCheck(purpose: PrinterStatusCheckPurpose) {
        if (checking) return
        checking = true
        val target = configuration
        val result = withContext(Dispatchers.IO) {
            TcpPrinterStatusClient(target).query(
                purpose = purpose,
                experimentalConfirmed = experimentalConfirmed,
            )
        }
        result.onSuccess { current ->
            status = current
            errorMessage = null
            withContext(Dispatchers.IO) {
                monitoringStore.recordStatus(target, current, actor)
            }
        }.onFailure { error ->
            status = null
            errorMessage = error.message ?: error.javaClass.simpleName
            withContext(Dispatchers.IO) {
                monitoringStore.recordFailure(target, error, actor)
            }
        }
        reloadHistory()
        checking = false
    }

    LaunchedEffect(autoMonitor, configuration, capability) {
        if (!autoMonitor || !capability.automaticQueryAllowed) return@LaunchedEffect
        while (true) {
            performCheck(PrinterStatusCheckPurpose.SALES_MONITORING)
            delay(5_000)
        }
    }

    Surface(Modifier.fillMaxSize(), color = PsBackground) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).background(PsNavy).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("つぐレジ", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(24.dp))
                Text("プリンター状態診断", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("EPSON確認済み／STAR・汎用は手動互換試行", color = Color.White, fontSize = 14.sp)
            }

            Row(
                Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                StatusPanel(Modifier.width(470.dp).fillMaxHeight()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        Text("接続・運用設定", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = PsNavy)
                        Spacer(Modifier.height(10.dp))
                        StatusValue("プリンター", configuration.name)
                        StatusValue("機種", configuration.profile.displayName)
                        StatusValue("状態方式", capability.implementationName)
                        StatusValue("検証区分", capability.verification.displayName)
                        StatusValue("接続先", if (configuration.host.isBlank()) "未設定" else "${configuration.host}:${configuration.port}")
                        StatusValue("タイムアウト", "${configuration.timeoutMillis}ms")
                        StatusValue("履歴上限", "${PrinterHistoryRetentionPolicy.MAX_ROWS}件")
                        Spacer(Modifier.height(8.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (capability.automaticQueryAllowed) {
                                    PsGreen.copy(alpha = 0.07f)
                                } else {
                                    PsOrange.copy(alpha = 0.09f)
                                },
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (capability.automaticQueryAllowed) PsGreen else PsOrange,
                            ),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                Text(
                                    capability.verification.displayName,
                                    color = if (capability.automaticQueryAllowed) PsGreen else PsOrange,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(capability.note, color = Color.DarkGray, fontSize = 13.sp, lineHeight = 18.sp)
                            }
                        }
                        if (capability.verification == PrinterStatusVerification.EXPERIMENTAL_COMPATIBILITY) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = experimentalConfirmed,
                                    onCheckedChange = {
                                        experimentalConfirmed = it
                                        status = null
                                        errorMessage = null
                                    },
                                )
                                Text("未検証のDLE EOT互換試行を手動で実行する")
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = runtimeSettings.preflightEnabled,
                                onCheckedChange = { requested ->
                                    runtimeSettings = runtimeSettings.copy(
                                        preflightEnabled = requested && capability.automaticQueryAllowed,
                                    )
                                },
                                enabled = capability.automaticQueryAllowed || runtimeSettings.preflightEnabled,
                            )
                            Text("自動印刷前に状態確認")
                        }
                        if (!capability.automaticQueryAllowed && runtimeSettings.preflightEnabled) {
                            Text(
                                "この機種では自動印刷前診断を利用できません。チェックを外して保存するまで自動印刷は送信待ちになります。",
                                color = PsRed,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                            )
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = runtimeSettings.historyEnabled,
                                onCheckedChange = { runtimeSettings = runtimeSettings.copy(historyEnabled = it) },
                            )
                            Text("状態確認履歴を保存")
                        }
                        OutlinedTextField(
                            value = retentionText,
                            onValueChange = { value ->
                                if (value.length <= 3 && value.all(Char::isDigit)) retentionText = value
                            },
                            label = { Text("履歴保持日数（1～365日）") },
                            supportingText = {
                                Text("期限超過または5,000件超過分は古い順に自動削除")
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    val retention = retentionText.toIntOrNull()
                                        ?.coerceIn(
                                            PrinterHistoryRetentionPolicy.MIN_DAYS,
                                            PrinterHistoryRetentionPolicy.MAX_DAYS,
                                        )
                                        ?: runtimeSettings.historyRetentionDays
                                    withContext(Dispatchers.IO) {
                                        monitoringStore.saveSettings(
                                            preflightEnabled = runtimeSettings.preflightEnabled,
                                            historyEnabled = runtimeSettings.historyEnabled,
                                            historyRetentionDays = retention,
                                            actor = actor,
                                        )
                                    }
                                    runtimeSettings = monitoringStore.loadSettings()
                                    retentionText = runtimeSettings.historyRetentionDays.toString()
                                    reloadHistory()
                                    operationMessage = "運用設定を保存しました（履歴保持${runtimeSettings.historyRetentionDays}日）"
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PsBlue),
                        ) { Text("運用設定を保存", fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                configuration = settingsStore.loadPrinterConfiguration()
                                runtimeSettings = monitoringStore.loadSettings()
                                retentionText = runtimeSettings.historyRetentionDays.toString()
                                status = null
                                errorMessage = null
                                autoMonitor = false
                                experimentalConfirmed = false
                                operationMessage = "保存済み設定を再読込しました"
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) { Text("保存済み設定を再読込") }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = autoMonitor,
                                onCheckedChange = { autoMonitor = it },
                                enabled = configuration.host.isNotBlank() && capability.automaticQueryAllowed,
                            )
                            Text("5秒ごとに自動確認")
                        }
                        Button(
                            onClick = {
                                scope.launch {
                                    performCheck(PrinterStatusCheckPurpose.MANUAL_DIAGNOSTIC)
                                }
                            },
                            enabled = !checking &&
                                configuration.host.isNotBlank() &&
                                (capability.verification != PrinterStatusVerification.EXPERIMENTAL_COMPATIBILITY || experimentalConfirmed),
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PsGreen),
                        ) {
                            Text(if (checking) "確認中…" else "今すぐ状態確認", fontWeight = FontWeight.Bold)
                        }
                        if (operationMessage != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(operationMessage.orEmpty(), color = PsGreen, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                StatusPanel(Modifier.weight(1f).fillMaxHeight()) {
                    Text("現在の状態", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = PsNavy)
                    Spacer(Modifier.height(12.dp))
                    when {
                        errorMessage != null -> StatusError(errorMessage.orEmpty())
                        status != null -> StatusResult(requireNotNull(status))
                        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("状態確認を実行してください", color = Color.Gray, fontSize = 20.sp)
                        }
                    }
                }

                StatusPanel(Modifier.width(420.dp).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("保存履歴", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = PsNavy)
                            Text(
                                "保持${runtimeSettings.historyRetentionDays}日 / 表示${history.size}件",
                                color = Color.Gray,
                                fontSize = 12.sp,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { monitoringStore.clearHistory(actor) }
                                    reloadHistory()
                                    operationMessage = "状態履歴を消去しました"
                                }
                            },
                        ) { Text("消去") }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (history.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("履歴はありません", color = Color.Gray)
                        }
                    } else {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            history.forEach { item ->
                                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(formatStatusTime(item.checkedAt), color = Color.Gray, fontSize = 13.sp)
                                        Spacer(Modifier.weight(1f))
                                        Text(
                                            item.level?.displayName ?: "通信失敗",
                                            color = item.level?.let(::statusColor) ?: PsRed,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                    Text(item.summary, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "${item.host}:${item.port} / ${item.checkedBy} / ${item.elapsedMillis}ms",
                                        color = Color.Gray,
                                        fontSize = 12.sp,
                                    )
                                    if (item.rawHex.isNotBlank()) {
                                        Text(item.rawHex, fontFamily = FontFamily.Monospace, color = Color.Gray, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().height(72.dp).background(Color.White).padding(horizontal = 18.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onClose, modifier = Modifier.width(240.dp).fillMaxHeight()) {
                    Text("閉じる", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    if (capability.automaticQueryAllowed) {
                        "自動監視中は他画面から印刷しないでください"
                    } else {
                        "STAR・汎用の状態結果は互換試行であり、実機確認完了を意味しません"
                    },
                    color = PsRed,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun StatusResult(status: PrinterRealtimeStatus) {
    Column(Modifier.fillMaxSize()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = statusColor(status.level).copy(alpha = 0.10f)),
            border = BorderStroke(2.dp, statusColor(status.level)),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(status.level.displayName, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = statusColor(status.level))
                Text(status.summary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text("応答 ${status.elapsedMillis}ms / ${formatStatusTime(status.checkedAt)}", color = Color.Gray)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                StatusFlag("オンライン", status.online, positiveWhenTrue = true)
                StatusFlag("カバー閉", !status.coverOpen, positiveWhenTrue = true)
                StatusFlag("ロール紙あり", !status.paperOut, positiveWhenTrue = true)
                StatusFlag("用紙残量十分", !status.paperNearEnd, positiveWhenTrue = true)
                StatusFlag("紙送り停止なし", !status.paperFeedStopped, positiveWhenTrue = true)
                StatusFlag("プロトコル形式", status.protocolValid, positiveWhenTrue = true)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                StatusFlag("復帰可能エラー", status.recoverableError)
                StatusFlag("カッターエラー", status.cutterError)
                StatusFlag("復帰不可能エラー", status.unrecoverableError)
                StatusFlag("自動復帰エラー", status.autoRecoverableError)
                StatusFlag("オフライン要因エラー", status.offlineErrorPresent)
                StatusFlag("オンライン復帰待ち", status.waitingForOnlineRecovery)
                StatusFlag("紙送りボタン", status.feedButtonPressed)
                StatusFlag("ドロア信号 H", status.drawerSignalHigh, positiveWhenTrue = true)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("RAW ${status.rawHex}", fontFamily = FontFamily.Monospace, color = Color.DarkGray)
    }
}

@Composable
private fun StatusError(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            colors = CardDefaults.cardColors(containerColor = PsRed.copy(alpha = 0.08f)),
            border = BorderStroke(2.dp, PsRed),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("通信失敗", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = PsRed)
                Spacer(Modifier.height(10.dp))
                Text(message, fontSize = 18.sp, textAlign = TextAlign.Center, lineHeight = 25.sp)
                Spacer(Modifier.height(12.dp))
                Text("IP・ポート・電源・LAN・プロファイルを確認してください", color = Color.DarkGray)
            }
        }
    }
}

@Composable
private fun StatusFlag(label: String, active: Boolean, positiveWhenTrue: Boolean = false) {
    val good = if (positiveWhenTrue) active else !active
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = Color.DarkGray)
        Text(
            if (active) "ON" else "OFF",
            color = if (good) PsGreen else PsRed,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun StatusValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = Color.DarkGray)
        Text(value, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
    }
}

@Composable
private fun StatusPanel(modifier: Modifier = Modifier, content: @Composable Column.() -> Unit) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, PsBorder),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), content = content)
    }
}

private fun statusColor(level: PrinterStatusLevel): Color = when (level) {
    PrinterStatusLevel.READY -> PsGreen
    PrinterStatusLevel.WARNING -> PsOrange
    PrinterStatusLevel.OFFLINE,
    PrinterStatusLevel.ERROR,
    -> PsRed
}

private fun formatStatusTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date(epochMillis))
