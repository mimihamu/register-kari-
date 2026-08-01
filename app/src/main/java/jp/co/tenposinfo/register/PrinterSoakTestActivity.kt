package jp.co.tenposinfo.register

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
    val resultStore = remember { PrinterSoakTestResultStore(context.applicationContext) }
    val actor = remember { OperatorSessionRegistry.lastKnownName() ?: "プリンター連続試験" }
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
    var activeRunId by remember { mutableStateOf<Long?>(null) }
    var finalizedRunId by remember { mutableStateOf<Long?>(null) }
    var lastResult by remember { mutableStateOf<PrinterSoakTestStoredResult?>(null) }
    var recentRuns by remember { mutableStateOf(resultStore.listRecent(5)) }
    val logs = remember { mutableStateListOf<String>() }

    fun addLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(Date())
        logs.add(0, "$time  $message")
        while (logs.size > 100) logs.removeAt(logs.lastIndex)
    }

    suspend fun finalizeRun(
        runStatus: PrinterSoakTestRunStatus,
        message: String,
        color: Color,
    ) {
        val runId = activeRunId
        running = false
        testJob = null
        statusMessage = message
        statusColor = color
        addLog(message)
        if (runId == null || finalizedRunId == runId) return
        finalizedRunId = runId
        val stored = withContext(Dispatchers.IO) {
            resultStore.finish(
                runId = runId,
                status = runStatus,
                completedCount = completed,
                summary = message,
                actor = actor,
            )
        }
        lastResult = stored
        activeRunId = null
        recentRuns = withContext(Dispatchers.IO) { resultStore.listRecent(5) }
        addLog("試験結果を保存しました ID=${stored.runId}")
        if (stored.csvPath != null) addLog("端末内CSV ${stored.csvPath}")
    }

    fun requestStop(message: String) {
        val job = testJob
        testJob = null
        job?.cancel()
        if (!running) return
        running = false
        statusMessage = message
        statusColor = StOrange
        addLog(message)
        scope.launch {
            finalizeRun(PrinterSoakTestRunStatus.STOPPED, message, StOrange)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        val result = lastResult
        if (uri == null || result == null) return@rememberLauncherForActivityResult
        scope.launch {
            val export = withContext(Dispatchers.IO) {
                runCatching {
                    val output = requireNotNull(context.contentResolver.openOutputStream(uri, "w")) {
                        "保存先を開けません"
                    }
                    output.bufferedWriter(Charsets.UTF_8).use { writer ->
                        writer.write("\uFEFF")
                        writer.write(result.csvText)
                    }
                    resultStore.recordCsvExport(result.runId, actor, uri.toString())
                }
            }
            export.fold(
                onSuccess = {
                    statusMessage = "CSVを保存しました：試験ID ${result.runId}"
                    statusColor = StGreen
                    addLog(statusMessage)
                },
                onFailure = {
                    statusMessage = "CSV保存に失敗しました：${it.message ?: it.javaClass.simpleName}"
                    statusColor = StRed
                    addLog(statusMessage)
                },
            )
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && running) {
                requestStop("画面がバックグラウンドになったため安全停止しました")
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            testJob?.cancel()
            val runId = activeRunId
            if (runId != null && finalizedRunId != runId) {
                runCatching {
                    resultStore.finish(
                        runId = runId,
                        status = PrinterSoakTestRunStatus.STOPPED,
                        completedCount = completed,
                        summary = "画面破棄により安全停止",
                        actor = actor,
                    )
                }
            }
            resultStore.close()
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
        lastResult = null
        finalizedRunId = null
        statusMessage = "プリンター設定を確認中"
        statusColor = StBlue
        val startedAt = System.currentTimeMillis()

        testJob = scope.launch {
            val configuration = withContext(Dispatchers.IO) {
                AdminSettingsStore(context.applicationContext).use { it.loadPrinterConfiguration() }
            }
            if (!configuration.usable) {
                running = false
                testJob = null
                statusMessage = "プリンターが未設定または無効です"
                statusColor = StRed
                addLog(statusMessage)
                return@launch
            }
            if (configuration.profile.statusProtocol == PrinterStatusProtocol.NONE) {
                running = false
                testJob = null
                statusMessage = "状態取得非対応のプリンターでは連続印刷試験を開始できません"
                statusColor = StRed
                addLog(statusMessage)
                return@launch
            }

            activeRunId = withContext(Dispatchers.IO) {
                resultStore.start(plan, configuration, actor, startedAt)
            }
            recentRuns = withContext(Dispatchers.IO) { resultStore.listRecent(5) }
            addLog("開始 ID=$activeRunId ${configuration.name} ${configuration.host}:${configuration.port}")

            for (sequence in 1..plan.totalPrints) {
                if (!isActive) return@launch
                statusMessage = "$sequence / ${plan.totalPrints} 状態確認中"
                statusColor = StBlue

                val checkedAt = System.currentTimeMillis()
                val statusResult = withContext(Dispatchers.IO) {
                    TcpPrinterStatusClient(configuration).query()
                }
                if (statusResult.isFailure) {
                    val error = statusResult.exceptionOrNull()
                    val detail = "状態確認に失敗したため送信前に停止：${error?.message ?: "不明なエラー"}"
                    activeRunId?.let { runId ->
                        withContext(Dispatchers.IO) {
                            resultStore.recordStep(
                                runId,
                                PrinterSoakTestStepRecord(
                                    sequence = sequence,
                                    checkedAt = checkedAt,
                                    statusLevel = "QUERY_FAILED",
                                    statusSummary = "状態取得失敗",
                                    rawHex = "",
                                    statusElapsedMillis = 0L,
                                    sentAt = null,
                                    outcome = PrinterSoakTestStepOutcome.STATUS_QUERY_FAILED,
                                    detail = detail,
                                ),
                            )
                        }
                    }
                    finalizeRun(PrinterSoakTestRunStatus.FAILED, detail, StRed)
                    return@launch
                }

                val printerStatus = statusResult.getOrThrow()
                if (!PrinterSoakTestPolicy.canSend(printerStatus)) {
                    val detail = PrinterSoakTestPolicy.stoppedByStatusMessage(printerStatus)
                    activeRunId?.let { runId ->
                        withContext(Dispatchers.IO) {
                            resultStore.recordStep(
                                runId,
                                printerStatus.toSoakStep(
                                    sequence = sequence,
                                    outcome = PrinterSoakTestStepOutcome.STOPPED_BY_STATUS,
                                    sentAt = null,
                                    detail = detail,
                                ),
                            )
                        }
                    }
                    finalizeRun(PrinterSoakTestRunStatus.STOPPED, detail, StRed)
                    return@launch
                }

                statusMessage = "$sequence / ${plan.totalPrints} 印刷送信中"
                val payload = PrinterCommandEncoder.encodeText(
                    text = PrinterSoakTestPolicy.pageText(sequence, plan.totalPrints, configuration, startedAt),
                    configuration = configuration,
                    openDrawer = false,
                    appendCut = plan.cutEachPrint,
                )
                val sentAt = System.currentTimeMillis()
                val sendResult = withContext(Dispatchers.IO) {
                    TcpEscPosPrinterGateway(
                        host = configuration.host,
                        port = configuration.port,
                        timeoutMillis = configuration.timeoutMillis,
                    ).send(payload)
                }
                if (sendResult.isFailure) {
                    val error = sendResult.exceptionOrNull() ?: IllegalStateException("印刷送信失敗")
                    val disposition = PrinterRetrySafety.classify(error)
                    val outcome = if (disposition == PrinterFailureDisposition.MANUAL_CONFIRMATION_REQUIRED) {
                        PrinterSoakTestStepOutcome.SEND_RESULT_UNKNOWN
                    } else {
                        PrinterSoakTestStepOutcome.SEND_FAILED_BEFORE_WRITE
                    }
                    val detail = PrinterSoakTestPolicy.stoppedByFailureMessage(error)
                    activeRunId?.let { runId ->
                        withContext(Dispatchers.IO) {
                            resultStore.recordStep(
                                runId,
                                printerStatus.toSoakStep(
                                    sequence = sequence,
                                    outcome = outcome,
                                    sentAt = sentAt,
                                    detail = detail,
                                ),
                            )
                        }
                    }
                    finalizeRun(PrinterSoakTestRunStatus.FAILED, detail, StRed)
                    return@launch
                }

                activeRunId?.let { runId ->
                    withContext(Dispatchers.IO) {
                        resultStore.recordStep(
                            runId,
                            printerStatus.toSoakStep(
                                sequence = sequence,
                                outcome = PrinterSoakTestStepOutcome.SENT,
                                sentAt = sentAt,
                                detail = "送信完了（自動再送なし・ドロア作動なし）",
                            ),
                        )
                    }
                }
                completed = sequence
                statusMessage = "$sequence / ${plan.totalPrints} 完了"
                statusColor = StGreen
                addLog("$sequence / ${plan.totalPrints} 送信完了（自動再送なし）")
                if (sequence < plan.totalPrints) delay(plan.intervalMillis)
            }

            finalizeRun(
                PrinterSoakTestRunStatus.COMPLETED,
                "連続印刷試験が完了しました：$completed / $total",
                StGreen,
            )
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
                Text("結果保存・CSV対応", color = Color.White, fontSize = 14.sp)
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
                        "各印刷前に状態確認を行い、正常時だけ1枚送信します。注意・異常・応答なし・送信失敗では即時停止し、自動再送しません。ドロアは開きません。結果と各回のRAW状態はSQLiteとCSVへ保存します。",
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
                            onClick = {
                                requestStop("操作により停止しました。最後の用紙が出ていないか確認してください")
                            },
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
                        Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$completed / $total", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = statusColor)
                            Spacer(Modifier.height(6.dp))
                            Text(statusMessage, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (recentRuns.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("直近の保存結果", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        recentRuns.take(3).forEach { run ->
                            Text(
                                "ID ${run.id}  ${run.status.displayName}  ${run.completedCount}/${run.totalPlanned}  ${formatSoakTime(run.startedAt)}",
                                color = if (run.status == PrinterSoakTestRunStatus.COMPLETED) StGreen else StOrange,
                                fontSize = 13.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
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
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onClose,
                    enabled = !running,
                    modifier = Modifier.width(220.dp).fillMaxHeight(),
                ) { Text("閉じる", fontWeight = FontWeight.Bold) }
                Button(
                    onClick = {
                        val result = lastResult ?: return@Button
                        exportLauncher.launch("TSUGUREGI_printer_soak_test_${result.runId}.csv")
                    },
                    enabled = !running && lastResult != null,
                    modifier = Modifier.width(240.dp).fillMaxHeight(),
                    colors = ButtonDefaults.buttonColors(containerColor = StBlue),
                ) { Text("最新結果をCSV保存", fontWeight = FontWeight.Bold) }
                Spacer(Modifier.weight(1f))
                Text("印字・カット・紙詰まり・重複の有無は必ず目視確認", color = StRed, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun PrinterRealtimeStatus.toSoakStep(
    sequence: Int,
    outcome: PrinterSoakTestStepOutcome,
    sentAt: Long?,
    detail: String,
): PrinterSoakTestStepRecord = PrinterSoakTestStepRecord(
    sequence = sequence,
    checkedAt = checkedAt,
    statusLevel = level.name,
    statusSummary = summary,
    rawHex = rawHex,
    statusElapsedMillis = elapsedMillis,
    sentAt = sentAt,
    outcome = outcome,
    detail = detail,
)

private fun formatSoakTime(epochMillis: Long): String =
    SimpleDateFormat("MM/dd HH:mm:ss", Locale.JAPAN).format(Date(epochMillis))

@Composable
private fun SoakPanel(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, StBorder),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(18.dp), content = content)
    }
}
