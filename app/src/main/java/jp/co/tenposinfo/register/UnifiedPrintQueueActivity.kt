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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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

private val UqNavy = Color(0xFF173F6B)
private val UqBlue = Color(0xFF1976B9)
private val UqGreen = Color(0xFF2E7D32)
private val UqOrange = Color(0xFFEF6C00)
private val UqRed = Color(0xFFC62828)
private val UqBackground = Color(0xFFF4F7FA)
private val UqBorder = Color(0xFFD5DEE7)
private val UqSelected = Color(0xFFEAF3FA)

class UnifiedPrintQueueActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureRegisterSystemBars(window)
        setContent {
            MaterialTheme {
                UnifiedPrintQueueApp(onClose = { finish() })
            }
        }
    }
}

private enum class QueueFilter(val displayName: String) {
    ALL("すべて"),
    WAITING("待機中"),
    FAILED("要確認"),
    COMPLETED("完了"),
}

@Composable
private fun UnifiedPrintQueueApp(onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val controller = remember { UnifiedPrintQueueController(context.applicationContext) }
    var revision by remember { mutableStateOf(0) }
    var filter by remember { mutableStateOf(QueueFilter.ALL) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var lastPrinterStatus by remember { mutableStateOf<PrinterRealtimeStatus?>(null) }
    val scope = rememberCoroutineScope()
    val configuration = remember(revision) { controller.loadConfiguration() }
    val allJobs = remember(revision) { controller.loadJobs() }
    val jobs = remember(allJobs, filter) {
        allJobs.filter { job ->
            when (filter) {
                QueueFilter.ALL -> true
                QueueFilter.WAITING -> job.status == PrintJobStatus.PENDING || job.status == PrintJobStatus.RETRY || job.status == PrintJobStatus.PRINTING
                QueueFilter.FAILED -> job.status == PrintJobStatus.FAILED
                QueueFilter.COMPLETED -> job.status == PrintJobStatus.COMPLETED
            }
        }
    }
    val summary = remember(allJobs) { UnifiedPrintQueueSummary.from(allJobs) }
    val selected = allJobs.firstOrNull { it.key == selectedKey }

    DisposableEffect(Unit) {
        onDispose { controller.close() }
    }

    fun refresh() {
        revision++
        if (selectedKey != null && allJobs.none { it.key == selectedKey }) selectedKey = null
    }

    fun executePrint(job: UnifiedPrintJob, safe: Boolean) {
        if (working) return
        working = true
        message = if (safe) "プリンター状態を確認して安全印刷しています…" else "確認済み強制印刷を実行しています…"
        scope.launch {
            val result = withContext(Dispatchers.IO) { controller.print(job, requireHealthyPrinter = safe) }
            message = result.fold(
                onSuccess = { it },
                onFailure = { it.message ?: "印刷に失敗しました" },
            )
            working = false
            refresh()
        }
    }

    Surface(Modifier.fillMaxSize(), color = UqBackground) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).background(UqNavy).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("つぐレジ", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(24.dp))
                Text("統合印刷キュー", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("売上・返品・点検・精算", color = Color.White)
            }

            Row(
                Modifier.weight(1f).fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                QueuePanel(Modifier.width(360.dp).fillMaxHeight()) {
                    Text("プリンターと件数", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = UqNavy)
                    Spacer(Modifier.height(10.dp))
                    QueueValue("プリンター", configuration.name)
                    QueueValue("接続先", if (configuration.host.isBlank()) "未設定" else "${configuration.host}:${configuration.port}")
                    QueueValue("機種", configuration.profile.displayName)
                    QueueValue("状態方式", configuration.profile.statusProtocol.displayName)
                    QueueValue("実印刷", if (configuration.usable) "使用可能" else "未設定")
                    Spacer(Modifier.height(12.dp))
                    QueueValue("全件", "${summary.total}件")
                    QueueValue("待機", "${summary.pending}件")
                    QueueValue("再試行", "${summary.retry}件")
                    QueueValue("要確認", "${summary.failed}件")
                    QueueValue("印刷中", "${summary.printing}件")
                    QueueValue("完了", "${summary.completed}件")
                    Spacer(Modifier.height(14.dp))
                    val status = lastPrinterStatus
                    if (status != null) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = queueStatusColor(status.level).copy(alpha = 0.10f)),
                            border = BorderStroke(2.dp, queueStatusColor(status.level)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(status.level.displayName, color = queueStatusColor(status.level), fontWeight = FontWeight.Bold)
                                Text(status.summary)
                                Text("RAW ${status.rawHex}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            working = true
                            message = "プリンター状態を確認しています…"
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { controller.queryPrinterStatus(configuration) }
                                result.onSuccess {
                                    lastPrinterStatus = it
                                    message = "状態確認：${it.summary}"
                                }.onFailure {
                                    lastPrinterStatus = null
                                    message = it.message ?: "状態確認に失敗しました"
                                }
                                working = false
                            }
                        },
                        enabled = !working && configuration.host.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = UqGreen),
                    ) { Text("プリンター状態確認", fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { refresh() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text("キューを更新")
                    }
                }

                QueuePanel(Modifier.width(540.dp).fillMaxHeight()) {
                    Text("印刷ジョブ", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = UqNavy)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        QueueFilter.entries.forEach { candidate ->
                            QueueChoice(
                                label = candidate.displayName,
                                selected = filter == candidate,
                                modifier = Modifier.weight(1f),
                            ) { filter = candidate }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (jobs.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("該当する印刷ジョブはありません", color = Color.Gray)
                        }
                    } else {
                        LazyColumn(Modifier.weight(1f)) {
                            items(jobs, key = { it.key }) { job ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(if (job.key == selectedKey) UqSelected else Color.Transparent)
                                        .clickable { selectedKey = job.key; message = null }
                                        .padding(horizontal = 8.dp, vertical = 9.dp),
                                ) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(job.type.displayName, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.weight(1f))
                                        Text(job.status.name, color = queueJobColor(job.status), fontWeight = FontWeight.Bold)
                                    }
                                    Text(
                                        "Job.${job.sourceId} / 参照No.${job.referenceId} / ${job.paperWidthMm}mm / ${queueDate(job.createdAt)}",
                                        color = Color.Gray,
                                        fontSize = 13.sp,
                                    )
                                    Text("試行 ${job.attemptCount}回", color = Color.Gray, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                QueuePanel(Modifier.weight(1f).fillMaxHeight()) {
                    Text("ジョブ詳細", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = UqNavy)
                    Spacer(Modifier.height(8.dp))
                    if (selected == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("印刷ジョブを選択してください", color = Color.Gray)
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(selected.type.displayName, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text(selected.status.name, color = queueJobColor(selected.status), fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "Job.${selected.sourceId} / 参照No.${selected.referenceId} / 試行${selected.attemptCount}回",
                            color = Color.Gray,
                        )
                        if (!selected.lastError.isNullOrBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text("最終エラー：${selected.lastError}", color = UqRed, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(8.dp))
                        Card(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFAFA)),
                            border = BorderStroke(1.dp, UqBorder),
                        ) {
                            Text(
                                selected.previewText,
                                modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { controller.retry(selected) }
                                        message = "再試行待ちへ戻しました（Job.${selected.sourceId}）"
                                        refresh()
                                    }
                                },
                                enabled = !working,
                                modifier = Modifier.weight(1f).height(50.dp),
                            ) { Text("再試行待ちへ") }
                            Button(
                                onClick = { executePrint(selected, safe = true) },
                                enabled = !working,
                                modifier = Modifier.weight(1f).height(50.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = UqBlue),
                            ) { Text("安全印刷", fontWeight = FontWeight.Bold) }
                            Button(
                                onClick = { executePrint(selected, safe = false) },
                                enabled = !working,
                                modifier = Modifier.weight(1f).height(50.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = UqRed),
                            ) { Text("確認済み強制印刷", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
                        }
                        Text(
                            "強制印刷は、送信結果不明時に紙が出ていないことを確認した場合だけ使用してください。",
                            color = UqRed,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 5.dp),
                        )
                    }
                    if (!message.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(message.orEmpty(), color = queueMessageColor(message.orEmpty()), fontWeight = FontWeight.Bold)
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().height(70.dp).background(Color.White).padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onClose, modifier = Modifier.width(220.dp).fillMaxHeight()) {
                    Text("閉じる", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                Text("FAILEDは自動再送しません。紙確認後に手動操作します。", color = UqRed, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun QueuePanel(modifier: Modifier = Modifier, content: @Composable Column.() -> Unit) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, UqBorder),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp), content = content)
    }
}

@Composable
private fun QueueValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = Color.DarkGray)
        Text(value, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
    }
}

@Composable
private fun QueueChoice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) UqRed else UqBorder),
    ) { Text(label, fontWeight = FontWeight.Bold, color = UqNavy) }
}

private fun queueStatusColor(level: PrinterStatusLevel): Color = when (level) {
    PrinterStatusLevel.READY -> UqGreen
    PrinterStatusLevel.WARNING -> UqOrange
    PrinterStatusLevel.OFFLINE,
    PrinterStatusLevel.ERROR,
    -> UqRed
}

private fun queueJobColor(status: PrintJobStatus): Color = when (status) {
    PrintJobStatus.COMPLETED -> UqGreen
    PrintJobStatus.PENDING,
    PrintJobStatus.RETRY,
    PrintJobStatus.PRINTING,
    -> UqOrange
    PrintJobStatus.FAILED -> UqRed
}

private fun queueMessageColor(message: String): Color = if (
    message.contains("失敗") || message.contains("停止") || message.contains("エラー") || message.contains("ありません")
) UqRed else UqGreen

private fun queueDate(epochMillis: Long): String =
    SimpleDateFormat("MM/dd HH:mm:ss", Locale.JAPAN).format(Date(epochMillis))
