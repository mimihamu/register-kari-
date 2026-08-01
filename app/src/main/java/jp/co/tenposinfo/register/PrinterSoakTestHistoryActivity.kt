package jp.co.tenposinfo.register

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val HsNavy = Color(0xFF173F6B)
private val HsBlue = Color(0xFF1976B9)
private val HsGreen = Color(0xFF2E7D32)
private val HsOrange = Color(0xFFEF6C00)
private val HsRed = Color(0xFFC62828)
private val HsBackground = Color(0xFFF4F7FA)
private val HsBorder = Color(0xFFD5DEE7)

class PrinterSoakTestHistoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                PrinterSoakTestHistoryScreen(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun PrinterSoakTestHistoryScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { PrinterSoakTestResultStore(context.applicationContext) }
    val maintenance = remember { PrinterSoakTestMaintenance(context.applicationContext) }
    val actor = remember { OperatorSessionRegistry.lastKnownName() ?: "責任者" }
    var revision by remember { mutableStateOf(0) }
    var selectedRunId by remember { mutableStateOf<Long?>(null) }
    var retentionText by remember { mutableStateOf(maintenance.retentionDays().toString()) }
    var message by remember { mutableStateOf("履歴を選択してください") }
    var messageColor by remember { mutableStateOf(HsNavy) }
    var deleteArmedFor by remember { mutableStateOf<Long?>(null) }
    var pendingExport by remember { mutableStateOf<PrinterSoakTestStoredResult?>(null) }

    val runs = remember(revision) { store.listRecent(100) }
    if (selectedRunId == null && runs.isNotEmpty()) selectedRunId = runs.first().id
    val selectedRun = runs.firstOrNull { it.id == selectedRunId }
    val selectedSteps = remember(selectedRunId, revision) {
        selectedRunId?.let(store::listSteps).orEmpty()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        val result = pendingExport
        if (uri == null || result == null) return@rememberLauncherForActivityResult
        scope.launch {
            val save = withContext(Dispatchers.IO) {
                runCatching {
                    requireNotNull(context.contentResolver.openOutputStream(uri, "w")) {
                        "保存先を開けません"
                    }.bufferedWriter(Charsets.UTF_8).use { writer ->
                        writer.write("\uFEFF")
                        writer.write(result.csvText)
                    }
                    store.recordCsvExport(result.runId, actor, uri.toString())
                }
            }
            save.fold(
                onSuccess = {
                    message = "試験ID ${result.runId}をCSV保存しました"
                    messageColor = HsGreen
                },
                onFailure = {
                    message = "CSV保存に失敗しました：${it.message ?: it.javaClass.simpleName}"
                    messageColor = HsRed
                },
            )
            pendingExport = null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            maintenance.close()
            store.close()
        }
    }

    fun refresh() {
        revision++
        deleteArmedFor = null
    }

    Surface(Modifier.fillMaxSize(), color = HsBackground) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).background(HsNavy).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("つぐレジ", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(24.dp))
                Text("連続印刷試験履歴", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("${runs.size}件表示 / 保持${maintenance.retentionDays()}日", color = Color.White)
            }

            Row(
                Modifier.weight(1f).fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HistoryPanel(Modifier.width(360.dp).fillMaxHeight()) {
                    Text("試験一覧", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = HsNavy)
                    Spacer(Modifier.height(8.dp))
                    if (runs.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("履歴はありません", color = Color.Gray)
                        }
                    } else {
                        LazyColumn(Modifier.weight(1f)) {
                            items(runs, key = { it.id }) { run ->
                                val selected = run.id == selectedRunId
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable {
                                            selectedRunId = run.id
                                            deleteArmedFor = null
                                            message = "試験ID ${run.id}を選択"
                                            messageColor = HsNavy
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (selected) HsBlue.copy(alpha = 0.10f) else Color.White,
                                    ),
                                    border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) HsBlue else HsBorder),
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text("ID ${run.id}", fontWeight = FontWeight.Bold, color = HsNavy)
                                            Spacer(Modifier.weight(1f))
                                            Text(run.status.displayName, color = historyStatusColor(run.status), fontWeight = FontWeight.Bold)
                                        }
                                        Text("${run.completedCount}/${run.totalPlanned}回  ${historyTime(run.startedAt)}", fontSize = 13.sp)
                                        Text(run.summary, color = Color.DarkGray, fontSize = 12.sp, maxLines = 2)
                                    }
                                }
                            }
                        }
                    }
                    OutlinedButton(onClick = ::refresh, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text("一覧を更新")
                    }
                }

                HistoryPanel(Modifier.weight(1f).fillMaxHeight()) {
                    Text("試験詳細", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = HsNavy)
                    Spacer(Modifier.height(8.dp))
                    if (selectedRun == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("試験を選択してください", color = Color.Gray)
                        }
                    } else {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            HistoryValue("試験ID", selectedRun.id.toString())
                            HistoryValue("結果", selectedRun.status.displayName)
                            HistoryValue("開始", historyTime(selectedRun.startedAt))
                            HistoryValue("終了", selectedRun.finishedAt?.let(::historyTime) ?: "未確定")
                            HistoryValue("進捗", "${selectedRun.completedCount}/${selectedRun.totalPlanned}")
                            HistoryValue("間隔", "${selectedRun.intervalMillis}ms")
                            HistoryValue("カット", if (selectedRun.cutEachPrint) "1枚ごと" else "なし")
                            HistoryValue("プリンター", selectedRun.printerName)
                            HistoryValue("接続先", "${selectedRun.host}:${selectedRun.port}")
                            HistoryValue("用紙", "${selectedRun.paperWidthMm}mm")
                            HistoryValue("機種", selectedRun.profileName)
                            HistoryValue("実行者", selectedRun.actorName)
                            HistoryValue("端末内CSV", selectedRun.csvPath ?: "なし")
                            Spacer(Modifier.height(8.dp))
                            Text("概要", fontWeight = FontWeight.Bold)
                            Text(selectedRun.summary, color = Color.DarkGray)
                            Spacer(Modifier.height(12.dp))
                            Text("各回結果 ${selectedSteps.size}件", fontWeight = FontWeight.Bold, color = HsNavy)
                            selectedSteps.forEach { step ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    colors = CardDefaults.cardColors(containerColor = HsBackground),
                                    border = BorderStroke(1.dp, HsBorder),
                                ) {
                                    Column(Modifier.fillMaxWidth().padding(9.dp)) {
                                        Row(Modifier.fillMaxWidth()) {
                                            Text("${step.sequence}回目", fontWeight = FontWeight.Bold)
                                            Spacer(Modifier.weight(1f))
                                            Text(step.outcome.displayName, color = stepOutcomeColor(step.outcome), fontWeight = FontWeight.Bold)
                                        }
                                        Text("${historyTime(step.checkedAt)} / ${step.statusLevel} / ${step.statusElapsedMillis}ms", fontSize = 12.sp)
                                        Text(step.statusSummary, fontSize = 13.sp)
                                        if (step.rawHex.isNotBlank()) Text("RAW ${step.rawHex}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                        Text(step.detail, color = Color.DarkGray, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                HistoryPanel(Modifier.width(350.dp).fillMaxHeight()) {
                    Text("履歴管理", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = HsNavy)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = retentionText,
                        onValueChange = { retentionText = it.filter(Char::isDigit).take(3) },
                        label = { Text("保持日数（1～365日）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val value = retentionText.toIntOrNull() ?: 0
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { maintenance.saveRetentionDays(value, actor) }
                                }
                                result.fold(
                                    onSuccess = {
                                        retentionText = maintenance.retentionDays().toString()
                                        message = "保持設定を保存し、${it.deletedCount}件を整理しました"
                                        messageColor = HsGreen
                                        refresh()
                                    },
                                    onFailure = {
                                        message = "履歴整理に失敗しました：${it.message ?: it.javaClass.simpleName}"
                                        messageColor = HsRed
                                    },
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = HsBlue),
                    ) { Text("保持設定を保存・整理", fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val report = withContext(Dispatchers.IO) { maintenance.recoverInterruptedRuns() }
                                message = if (report.recoveredCount == 0) {
                                    "回収対象の実行中試験はありません"
                                } else {
                                    "中断試験を${report.recoveredCount}件、安全停止として回収しました"
                                }
                                messageColor = if (report.recoveredCount == 0) HsNavy else HsOrange
                                refresh()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                    ) { Text("中断試験を安全回収") }
                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = {
                            val runId = selectedRunId ?: return@Button
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { maintenance.regenerateCsv(runId, actor) }
                                }
                                result.fold(
                                    onSuccess = {
                                        pendingExport = it
                                        exportLauncher.launch("TSUGUREGI_printer_soak_test_${it.runId}.csv")
                                        refresh()
                                    },
                                    onFailure = {
                                        message = "CSV生成に失敗しました：${it.message ?: it.javaClass.simpleName}"
                                        messageColor = HsRed
                                    },
                                )
                            }
                        },
                        enabled = selectedRun != null && selectedRun.status != PrinterSoakTestRunStatus.RUNNING,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = HsGreen),
                    ) { Text("選択結果をCSV保存", fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val runId = selectedRunId ?: return@Button
                            if (deleteArmedFor != runId) {
                                deleteArmedFor = runId
                                message = "もう一度押すと試験ID $runId を削除します"
                                messageColor = HsRed
                                return@Button
                            }
                            scope.launch {
                                val deleted = withContext(Dispatchers.IO) {
                                    runCatching { maintenance.deleteRun(runId, actor) }
                                }
                                deleted.fold(
                                    onSuccess = {
                                        message = if (it) "試験ID $runId を削除しました" else "試験結果が見つかりません"
                                        messageColor = if (it) HsGreen else HsOrange
                                        selectedRunId = null
                                        refresh()
                                    },
                                    onFailure = {
                                        message = "削除に失敗しました：${it.message ?: it.javaClass.simpleName}"
                                        messageColor = HsRed
                                    },
                                )
                            }
                        },
                        enabled = selectedRun != null && selectedRun.status != PrinterSoakTestRunStatus.RUNNING,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = HsRed),
                    ) {
                        Text(if (deleteArmedFor == selectedRunId) "確認：本当に削除" else "選択履歴を削除", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(message, color = messageColor, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "RUNNINGは削除せず、安全回収後に操作します。履歴削除は売上・印刷キューには影響しません。",
                        color = Color.DarkGray,
                        fontSize = 13.sp,
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().height(72.dp).background(Color.White).padding(horizontal = 18.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onClose, modifier = Modifier.width(230.dp).fillMaxHeight()) {
                    Text("プリンター運用へ戻る", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                Text("送信結果不明の試験は、用紙を確認してから新しい試験を開始してください", color = HsRed, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HistoryPanel(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, HsBorder),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp), content = content)
    }
}

@Composable
private fun HistoryValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(label, modifier = Modifier.width(120.dp), color = Color.DarkGray)
        Text(value, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
    }
}

private fun historyStatusColor(status: PrinterSoakTestRunStatus): Color = when (status) {
    PrinterSoakTestRunStatus.RUNNING -> HsBlue
    PrinterSoakTestRunStatus.COMPLETED -> HsGreen
    PrinterSoakTestRunStatus.STOPPED -> HsOrange
    PrinterSoakTestRunStatus.FAILED -> HsRed
}

private fun stepOutcomeColor(outcome: PrinterSoakTestStepOutcome): Color = when (outcome) {
    PrinterSoakTestStepOutcome.SENT -> HsGreen
    PrinterSoakTestStepOutcome.STOPPED_BY_STATUS -> HsOrange
    PrinterSoakTestStepOutcome.STATUS_QUERY_FAILED,
    PrinterSoakTestStepOutcome.SEND_FAILED_BEFORE_WRITE,
    PrinterSoakTestStepOutcome.SEND_RESULT_UNKNOWN,
    -> HsRed
}

private fun historyTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date(epochMillis))
