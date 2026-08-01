package jp.co.tenposinfo.register

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val StNavy = Color(0xFF173F6B)
private val StBlue = Color(0xFF1976B9)
private val StGreen = Color(0xFF2E7D32)
private val StOrange = Color(0xFFEF6C00)
private val StRed = Color(0xFFC62828)
private val StBackground = Color(0xFFF4F7FA)
private val StBorder = Color(0xFFD5DEE7)

class PrinterSoakTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                PrinterSoakTestScreen(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun PrinterSoakTestScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var printCountText by remember { mutableStateOf("20") }
    var intervalSecondsText by remember { mutableStateOf("5") }
    var cutEachPrint by remember { mutableStateOf(false) }
    var paperChecked by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var statusMessage by remember { mutableStateOf("試験条件を確認してください") }
    var statusColor by remember { mutableStateOf(StNavy) }
    var testJob by remember { mutableStateOf<Job?>(null) }
    val logs = remember { mutableStateListOf<String>() }

    fun addLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(Date())
        logs.add(0, "$time  $message")
        while (logs.size > 100) logs.removeAt(logs.lastIndex)
    }

    fun stopTest(message: String, color: Color = StOrange) {
        testJob?.cancel()
        testJob = null
        running = false
        statusMessage = message
        statusColor = color
        addLog(message)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && running) {
                stopTest("画面がバックグラウンドになったため安全停止しました")
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            testJob?.cancel()
        }
    }

    fun startTest() {
        val count = printCountText.toIntOrNull() ?: 0
        val intervalSeconds = intervalSecondsText.toLongOrNull() ?: 0L
        val plan = PrinterSoakTestPlan(
            totalPrints = count,
            intervalMillis = intervalSeconds * 1_000L,
            cutEachPrint = cutEachPrint,
        )
        val validationError = PrinterSoakTestPolicy.validationError(plan)
        if (validationError != null) {
            statusMessage = validationError
            statusColor = StRed
            return
        }
        if (!paperChecked) {
            statusMessage = "ロール紙・排紙口・プリンター周辺の確認が必要です"
            statusColor = StRed
            return
        }

        running = true
        completed = 0
        total = count
        logs.clear()
        statusMessage = "プリンター設定を確認中"
        statusColor = StBlue
        val startedAt = System.currentTimeMillis()

        testJob = scope.launch {
            val configuration = withContext(Dispatchers.IO) {
                AdminSettingsStore(context.applicationContext).use { it.loadPrinterConfiguration() }
            }
            if (!configuration.usable) {
                stopTest("プリンターが未設定または無効です", StRed)
                return@launch
            }
            if (configuration.profile.statusProtocol == PrinterStatusProtocol.NONE) {
                stopTest("状態取得非対応のプリンターでは連続印刷試験を開始できません", StRed)
                return@launch
            }

            addLog("開始 ${configuration.name} ${configuration.host}:${configuration.port}")
            for (sequence in 1..plan.totalPrints) {
                if (!isActive) return@launch
                statusMessage = "$sequence / ${plan.totalPrints} 状態確認中"
                statusColor = StBlue

                val statusResult = withContext(Dispatchers.IO) {
                    TcpPrinterStatusClient(configuration).query()
                }
                if (statusResult.isFailure) {
                    val error = statusResult.exceptionOrNull()
                    stopTest(
                        "状態確認に失敗したため送信前に停止しました：${error?.message ?: "不明なエラー"}",
                        StRed,
                    )
                    return@launch
                }
                val printerStatus = statusResult.getOrThrow()
                if (!PrinterSoakTestPolicy.canSend(printerStatus)) {
                    stopTest(PrinterSoakTestPolicy.stoppedByStatusMessage(printerStatus), StRed)
                    return@launch
                }

                statusMessage = "$sequence / ${plan.totalPrints} 印刷送信中"
                val payload = PrinterCommandEncoder.encodeText(
                    text = PrinterSoakTestPolicy.pageText(sequence, plan.totalPrints, configuration, startedAt),
                    configuration = configuration,
                    openDrawer = false,
                    appendCut = plan.cutEachPrint,
                )
                val sendResult = withContext(Dispatchers.IO) {
                    TcpEscPosPrinterGateway(
                        host = configuration.host,
                        port = configuration.port,
                        timeoutMillis = configuration.timeoutMillis,
                    ).send(payload)
                }
                if (sendResult.isFailure) {
                    val error = sendResult.exceptionOrNull() ?: IllegalStateException("印刷送信失敗")
                    stopTest(PrinterSoakTestPolicy.stoppedByFailureMessage(error), StRed)
                    return@launch
                }

                completed = sequence
                statusMessage = "$sequence / ${plan.totalPrints} 完了"
                statusColor = StGreen
                addLog("$sequence / ${plan.totalPrints} 送信完了（自動再送なし）")
                if (sequence < plan.totalPrints) delay(plan.intervalMillis)
            }

            running = false
            testJob = null
            statusMessage = "連続印刷試験が完了しました：$completed / $total"
            statusColor = StGreen
            addLog("試験完了 $completed / $total")
        }
    }

    Surface(Modifier.fillMaxSize(), color = StBackground) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).background(StNavy).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("つぐレジ", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(24.dp))
                Text("プリンター連続印刷試験", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("実機検証専用", color = Color.White, fontSize = 14.sp)
            }

            Row(
                Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SoakPanel(Modifier.width(420.dp).fillMaxHeight()) {
                    Text("試験条件", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = StNavy)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = printCountText,
                        onValueChange = { value ->
                            if (!running && value.length <= 3 && value.all(Char::isDigit)) printCountText = value
                        },
                        label = { Text("印刷回数（1～500回）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = intervalSecondsText,
                        onValueChange = { value ->
                            if (!running && value.length <= 2 && value.all(Char::isDigit)) intervalSecondsText = value
                        },
                        label = { Text("印刷間隔（1～60秒）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = cutEachPrint,
                            onCheckedChange = { if (!running) cutEachPrint = it },
                            enabled = !running,
                        )
                        Text("1枚ごとにカットする")
                    }
                    Spacer(Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = StOrange.copy(alpha = 0.08f)),
                        border = BorderStroke(1.dp, StOrange),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = paperChecked,
                                onCheckedChange = { if (!running) paperChecked = it },
                                enabled = !running,
                            )
                            Text(
                                "ロール紙、排紙口、プリンター周辺を確認しました",
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "各印刷前に状態確認を行い、正常時だけ1枚送信します。注意・異常・応答なし・送信失敗では即時停止し、自動再送しません。ドロアは開きません。",
                        color = Color.DarkGray,
                        lineHeight = 22.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    if (!running) {
                        Button(
                            onClick = ::startTest,
                            modifier = Modifier.fillMaxWidth().height(58.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = StBlue),
                        ) { Text("連続印刷試験を開始", fontWeight = FontWeight.Bold) }
                    } else {
                        Button(
                            onClick = { stopTest("操作により停止しました。最後の用紙が出ていないか確認してください") },
                            modifier = Modifier.fillMaxWidth().height(58.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = StRed),
                        ) { Text("安全停止", fontWeight = FontWeight.Bold) }
                    }
                }

                SoakPanel(Modifier.weight(1f).fillMaxHeight()) {
                    Text("進行状況", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = StNavy)
                    Spacer(Modifier.height(14.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.08f)),
                        border = BorderStroke(2.dp, statusColor),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$completed / $total", fontSize = 42.sp, fontWeight = FontWeight.Bold, color = statusColor)
                            Spacer(Modifier.height(6.dp))
                            Text(statusMessage, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("試験ログ", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        if (logs.isEmpty()) {
                            Text("ログはありません", color = Color.Gray)
                        } else {
                            logs.forEach { line ->
                                Text(
                                    line,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(vertical = 3.dp),
                                )
                            }
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().height(72.dp).background(Color.White).padding(horizontal = 18.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onClose,
                    enabled = !running,
                    modifier = Modifier.width(240.dp).fillMaxHeight(),
                ) { Text("閉じる", fontWeight = FontWeight.Bold) }
                Spacer(Modifier.weight(1f))
                Text("印字結果、カット、紙詰まり、重複の有無は必ず目視確認してください", color = StRed, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SoakPanel(modifier: Modifier, content: @Composable Column.() -> Unit) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, StBorder),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(18.dp), content = content)
    }
}
