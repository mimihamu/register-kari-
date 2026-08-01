package jp.co.tenposinfo.register

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PpNavy = Color(0xFF173F6B)
private val PpBlue = Color(0xFF1976B9)
private val PpGreen = Color(0xFF2E7D32)
private val PpOrange = Color(0xFFEF6C00)
private val PpRed = Color(0xFFC62828)
private val PpBackground = Color(0xFFF4F7FA)
private val PpBorder = Color(0xFFD5DEE7)

class PrinterStatusProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                PrinterStatusProbeScreen(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun PrinterStatusProbeScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember { AdminSettingsStore(context.applicationContext) }
    var configuration by remember { mutableStateOf(settingsStore.loadPrinterConfiguration()) }
    var preset by remember { mutableStateOf(PrinterStatusProbePolicy.presetFor(configuration.profile)) }
    var experimentalConfirmed by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<PrinterStatusProbeResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var operationMessage by remember { mutableStateOf<String?>(null) }

    val availablePresets = remember(configuration.profile) {
        listOf(
            PrinterStatusProbePreset.TCP_CONNECT_ONLY,
            PrinterStatusProbePolicy.presetFor(configuration.profile),
        ).distinct()
    }
    val runAllowed = PrinterStatusProbePolicy.canRun(preset, experimentalConfirmed)

    DisposableEffect(Unit) {
        onDispose { settingsStore.close() }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        val current = result
        if (uri == null || current == null) return@rememberLauncherForActivityResult
        scope.launch {
            val exportResult = withContext(Dispatchers.IO) {
                runCatching {
                    requireNotNull(context.contentResolver.openOutputStream(uri, "w")).bufferedWriter(Charsets.UTF_8).use { writer ->
                        writer.write("\uFEFF")
                        writer.write(PrinterStatusProbeCsv.render(configuration.profile, current))
                    }
                }
            }
            operationMessage = exportResult.fold(
                onSuccess = { "RAWプローブ結果をCSV保存しました" },
                onFailure = { "CSV保存に失敗しました：${it.message ?: it.javaClass.simpleName}" },
            )
        }
    }

    fun executeProbe() {
        if (running || !runAllowed) return
        running = true
        result = null
        errorMessage = null
        operationMessage = "RAWプローブを実行中です"
        val target = configuration
        val selected = preset
        scope.launch {
            val probe = withContext(Dispatchers.IO) {
                TcpPrinterStatusProbeClient(target).execute(selected)
            }
            probe.fold(
                onSuccess = {
                    result = it
                    operationMessage = "RAWプローブが完了しました：受信${it.responseBytes.size}バイト"
                },
                onFailure = {
                    errorMessage = it.message ?: it.javaClass.simpleName
                    operationMessage = null
                },
            )
            running = false
        }
    }

    Surface(Modifier.fillMaxSize(), color = PpBackground) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).background(PpNavy).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("つぐレジ", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(24.dp))
                Text("プリンター状態RAWプローブ", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("印刷・カット・ドロア送信なし", color = Color.White, fontSize = 14.sp)
            }

            Row(
                Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ProbePanel(Modifier.width(450.dp).fillMaxHeight()) {
                    Text("接続・試験条件", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = PpNavy)
                    Spacer(Modifier.height(10.dp))
                    ProbeValue("プリンター", configuration.name)
                    ProbeValue("機種", configuration.profile.displayName)
                    ProbeValue("接続先", if (configuration.host.isBlank()) "未設定" else "${configuration.host}:${configuration.port}")
                    ProbeValue("タイムアウト", "${configuration.timeoutMillis}ms")
                    Spacer(Modifier.height(12.dp))
                    Text("プローブ種別", fontWeight = FontWeight.Bold, color = PpNavy)
                    availablePresets.forEach { candidate ->
                        OutlinedButton(
                            onClick = {
                                preset = candidate
                                experimentalConfirmed = false
                                result = null
                                errorMessage = null
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            border = BorderStroke(
                                if (preset == candidate) 3.dp else 1.dp,
                                if (preset == candidate) PpRed else PpBorder,
                            ),
                        ) {
                            Text(candidate.displayName, fontWeight = FontWeight.Bold)
                        }
                    }
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (preset.experimental) PpOrange.copy(alpha = 0.09f) else PpGreen.copy(alpha = 0.07f),
                        ),
                        border = BorderStroke(1.dp, if (preset.experimental) PpOrange else PpGreen),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(
                                if (preset.experimental) "互換試行・未検証" else "安全な接続確認／メーカー仕様に基づく試行",
                                color = if (preset.experimental) PpOrange else PpGreen,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(preset.description, color = Color.DarkGray, lineHeight = 20.sp)
                        }
                    }
                    if (preset.experimental) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = experimentalConfirmed,
                                onCheckedChange = { experimentalConfirmed = it },
                            )
                            Text("未検証の互換状態コマンドを送信することを確認しました")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = {
                            configuration = settingsStore.loadPrinterConfiguration()
                            preset = PrinterStatusProbePolicy.presetFor(configuration.profile)
                            experimentalConfirmed = false
                            result = null
                            errorMessage = null
                            operationMessage = "保存済みプリンター設定を再読込しました"
                        },
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text("設定を再読込") }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = ::executeProbe,
                        enabled = !running && configuration.host.isNotBlank() && runAllowed,
                        modifier = Modifier.fillMaxWidth().height(58.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PpBlue),
                    ) { Text(if (running) "実行中…" else "RAWプローブを実行", fontWeight = FontWeight.Bold) }
                }

                ProbePanel(Modifier.weight(1f).fillMaxHeight()) {
                    Text("送受信結果", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = PpNavy)
                    Spacer(Modifier.height(12.dp))
                    when {
                        errorMessage != null -> ProbeError(errorMessage.orEmpty())
                        result != null -> ProbeResultView(requireNotNull(result))
                        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("プローブを実行してください", color = Color.Gray, fontSize = 20.sp)
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().height(72.dp).background(Color.White).padding(horizontal = 18.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onClose, enabled = !running, modifier = Modifier.width(220.dp).fillMaxHeight()) {
                    Text("閉じる", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = {
                        val current = result ?: return@Button
                        exportLauncher.launch("TSUGUREGI_printer_status_probe_${current.startedAt}.csv")
                    },
                    enabled = !running && result != null,
                    modifier = Modifier.width(250.dp).fillMaxHeight(),
                    colors = ButtonDefaults.buttonColors(containerColor = PpGreen),
                ) { Text("結果をCSV保存", fontWeight = FontWeight.Bold) }
                Spacer(Modifier.weight(1f))
                Text(operationMessage.orEmpty(), color = if (operationMessage?.contains("失敗") == true) PpRed else PpNavy)
            }
        }
    }
}

@Composable
private fun ProbeResultView(result: PrinterStatusProbeResult) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = PpGreen.copy(alpha = 0.07f)),
            border = BorderStroke(2.dp, PpGreen),
            shape = RoundedCornerShape(10.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("受信 ${result.responseBytes.size}バイト", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = PpGreen)
                Text("${result.elapsedMillis}ms / ${formatProbeTime(result.startedAt)}", color = Color.Gray)
            }
        }
        Spacer(Modifier.height(12.dp))
        ProbeValue("プリセット", result.preset.displayName)
        ProbeValue("接続先", "${result.host}:${result.port}")
        ProbeValue("送信バイト数", result.requestBytes.size.toString())
        ProbeValue("受信バイト数", result.responseBytes.size.toString())
        Spacer(Modifier.height(8.dp))
        Text("送信HEX", fontWeight = FontWeight.Bold, color = PpNavy)
        ProbeCode(result.requestHex.ifBlank { "（送信なし）" })
        Spacer(Modifier.height(8.dp))
        Text("受信HEX", fontWeight = FontWeight.Bold, color = PpNavy)
        ProbeCode(result.responseHex.ifBlank { "（受信なし）" })
        Spacer(Modifier.height(8.dp))
        Text("受信ASCII", fontWeight = FontWeight.Bold, color = PpNavy)
        ProbeCode(result.responseAscii.ifBlank { "（受信なし）" })
        result.parsedEpsonStatus?.let { status ->
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = PpOrange.copy(alpha = 0.07f)),
                border = BorderStroke(1.dp, PpOrange),
                shape = RoundedCornerShape(8.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("EPSON 4バイト形式としての参考解析", fontWeight = FontWeight.Bold, color = PpOrange)
                    Text("${status.level.displayName} / ${status.summary}")
                    Text("固定ビット：${if (status.protocolValid) "一致" else "不一致"}")
                    Text("STAR／汎用機で一致しても互換性確認完了とは扱いません", color = PpRed, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun ProbeError(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            colors = CardDefaults.cardColors(containerColor = PpRed.copy(alpha = 0.08f)),
            border = BorderStroke(2.dp, PpRed),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("プローブ失敗", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = PpRed)
                Spacer(Modifier.height(10.dp))
                Text(message, fontSize = 18.sp, textAlign = TextAlign.Center, lineHeight = 25.sp)
                Spacer(Modifier.height(12.dp))
                Text("失敗は印刷失敗とは別です。状態コマンド非対応の可能性があります。", color = Color.DarkGray)
            }
        }
    }
}

@Composable
private fun ProbeCode(value: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F9FB)),
        border = BorderStroke(1.dp, PpBorder),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            value,
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun ProbeValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = Color.DarkGray)
        Text(value, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
    }
}

@Composable
private fun ProbePanel(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, PpBorder),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), content = content)
    }
}

private fun formatProbeTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date(epochMillis))
