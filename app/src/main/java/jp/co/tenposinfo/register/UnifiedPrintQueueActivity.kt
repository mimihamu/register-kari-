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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
private val UqMuted = Color(0xFF607D8B)

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

@Composable
private fun UnifiedPrintQueueApp(onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val controller = remember { UnifiedPrintQueueController(context.applicationContext) }
    val responsive = rememberRegisterResponsiveMetrics()
    val actor = remember {
        OperatorSessionRegistry.current(context)?.name
            ?: OperatorSessionRegistry.lastKnownName()
            ?: "印刷キュー担当者"
    }
    var revision by remember { mutableStateOf(0) }
    var criteria by remember { mutableStateOf(UnifiedPrintQueueCriteria()) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var lastPrinterStatus by remember { mutableStateOf<PrinterRealtimeStatus?>(null) }
    var managerPin by remember { mutableStateOf("") }
    var discardReason by remember { mutableStateOf("") }
    var forceConfirmed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val configuration = remember(revision) { controller.loadConfiguration() }
    val allJobs = remember(revision) { controller.loadJobs() }
    val jobs = remember(allJobs, criteria) {
        UnifiedPrintQueueFilterPolicy.filter(allJobs, criteria)
    }
    val summary = remember(allJobs) { UnifiedPrintQueueSummary.from(allJobs) }
    val selected = allJobs.firstOrNull { it.key == selectedKey }

    DisposableEffect(Unit) {
        onDispose { controller.close() }
    }

    fun resetApprovalInputs() {
        managerPin = ""
        discardReason = ""
        forceConfirmed = false
    }

    fun refresh(clearSelection: Boolean = false) {
        if (clearSelection) selectedKey = null
        revision++
    }

    fun executeRetry(job: UnifiedPrintJob) {
        if (working) return
        working = true
        message = "再試行待ちへ戻しています…"
        scope.launch {
            val result = withContext(Dispatchers.IO) { controller.retry(job, actor) }
            message = result.fold(onSuccess = { it }, onFailure = { it.message ?: "再試行登録に失敗しました" })
            working = false
            refresh()
        }
    }

    fun executePrint(job: UnifiedPrintJob, safe: Boolean) {
        if (working) return
        if (!safe && !forceConfirmed) {
            message = "紙が出ていないことを確認し、確認チェックを入れてください"
            return
        }
        working = true
        message = if (safe) "プリンター状態を確認して安全印刷しています…" else "責任者承認付き強制印刷を実行しています…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                controller.print(
                    job = job,
                    requireHealthyPrinter = safe,
                    actor = actor,
                    managerPin = managerPin,
                )
            }
            message = result.fold(onSuccess = { it }, onFailure = { it.message ?: "印刷に失敗しました" })
            if (result.isSuccess) resetApprovalInputs()
            working = false
            refresh()
        }
    }

    fun executeDiscard(job: UnifiedPrintJob) {
        if (working) return
        working = true
        message = "責任者承認を確認して印刷ジョブを破棄しています…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                controller.discard(
                    job = job,
                    managerPin = managerPin,
                    reason = discardReason,
                    actor = actor,
                )
            }
            message = result.fold(onSuccess = { it }, onFailure = { it.message ?: "破棄に失敗しました" })
            if (result.isSuccess) resetApprovalInputs()
            working = false
            refresh(clearSelection = result.isSuccess && criteria.status != UnifiedPrintStatusFilter.ALL)
        }
    }

    Surface(Modifier.fillMaxSize(), color = UqBackground) {
        Column(Modifier.fillMaxSize()) {
            QueueHeader(
                heightDp = responsive.headerHeightDp,
                actor = actor,
                activeCount = summary.active,
                actionRequired = summary.actionRequired,
            )

            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth().padding(responsive.screenPaddingDp.dp),
            ) {
                val stacked = responsive.isCompact || maxWidth < 1_180.dp || maxHeight < 620.dp
                if (stacked) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(responsive.panelGapDp.dp),
                    ) {
                        PrinterSummaryPanel(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 360.dp),
                            configuration = configuration,
                            summary = summary,
                            status = lastPrinterStatus,
                            working = working,
                            onStatusCheck = {
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
                            onRefresh = { refresh() },
                        )
                        JobListPanel(
                            modifier = Modifier.fillMaxWidth().height(540.dp),
                            jobs = jobs,
                            criteria = criteria,
                            selectedKey = selectedKey,
                            onCriteriaChange = { criteria = it },
                            onSelect = {
                                selectedKey = it.key
                                message = null
                                resetApprovalInputs()
                            },
                        )
                        JobDetailPanel(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 720.dp),
                            selected = selected,
                            working = working,
                            managerPin = managerPin,
                            discardReason = discardReason,
                            forceConfirmed = forceConfirmed,
                            onManagerPinChanged = { managerPin = it.filter(Char::isDigit).take(8) },
                            onDiscardReasonChanged = { discardReason = it.take(200) },
                            onForceConfirmedChanged = { forceConfirmed = it },
                            onRetry = ::executeRetry,
                            onSafePrint = { executePrint(it, safe = true) },
                            onForcePrint = { executePrint(it, safe = false) },
                            onDiscard = ::executeDiscard,
                        )
                    }
                } else {
                    Row(
                        Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(responsive.panelGapDp.dp),
                    ) {
                        PrinterSummaryPanel(
                            modifier = Modifier.weight(0.24f).fillMaxHeight(),
                            configuration = configuration,
                            summary = summary,
                            status = lastPrinterStatus,
                            working = working,
                            onStatusCheck = {
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
                            onRefresh = { refresh() },
                        )
                        JobListPanel(
                            modifier = Modifier.weight(0.38f).fillMaxHeight(),
                            jobs = jobs,
                            criteria = criteria,
                            selectedKey = selectedKey,
                            onCriteriaChange = { criteria = it },
                            onSelect = {
                                selectedKey = it.key
                                message = null
                                resetApprovalInputs()
                            },
                        )
                        JobDetailPanel(
                            modifier = Modifier.weight(0.38f).fillMaxHeight(),
                            selected = selected,
                            working = working,
                            managerPin = managerPin,
                            discardReason = discardReason,
                            forceConfirmed = forceConfirmed,
                            onManagerPinChanged = { managerPin = it.filter(Char::isDigit).take(8) },
                            onDiscardReasonChanged = { discardReason = it.take(200) },
                            onForceConfirmedChanged = { forceConfirmed = it },
                            onRetry = ::executeRetry,
                            onSafePrint = { executePrint(it, safe = true) },
                            onForcePrint = { executePrint(it, safe = false) },
                            onDiscard = ::executeDiscard,
                        )
                    }
                }
            }

            QueueFooter(
                heightDp = responsive.bottomBarHeightDp,
                message = message,
                working = working,
                onClose = onClose,
            )
        }
    }
}

@Composable
private fun QueueHeader(heightDp: Int, actor: String, activeCount: Int, actionRequired: Int) {
    Row(
        Modifier.fillMaxWidth().height(heightDp.dp).background(UqNavy).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("つぐレジ", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(24.dp))
        Text("統合印刷キュー", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text("担当：$actor　未完了${activeCount}件　要対応${actionRequired}件", color = Color.White)
    }
}

@Composable
private fun PrinterSummaryPanel(
    modifier: Modifier,
    configuration: PrinterConfiguration,
    summary: UnifiedPrintQueueSummary,
    status: PrinterRealtimeStatus?,
    working: Boolean,
    onStatusCheck: () -> Unit,
    onRefresh: () -> Unit,
) {
    QueuePanel(modifier) {
        Text("プリンターと件数", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = UqNavy)
        Spacer(Modifier.height(10.dp))
        QueueValue("プリンター", configuration.name)
        QueueValue("接続先", if (configuration.host.isBlank()) "未設定" else "${configuration.host}:${configuration.port}")
        QueueValue("機種", configuration.profile.displayName)
        QueueValue("用紙設定", "${configuration.paperWidthMm}mm（印刷時指定なし）")
        QueueValue("実印刷", if (configuration.usable) "使用可能" else "未設定")
        Spacer(Modifier.height(12.dp))
        QueueValue("未完了", "${summary.active}件")
        QueueValue("要対応", "${summary.actionRequired}件")
        QueueValue("待機", "${summary.pending}件")
        QueueValue("再試行", "${summary.retry}件")
        QueueValue("失敗", "${summary.failed}件")
        QueueValue("印刷中", "${summary.printing}件")
        QueueValue("完了", "${summary.completed}件")
        QueueValue("破棄済み", "${summary.discarded}件")
        Spacer(Modifier.height(12.dp))
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
            onClick = onStatusCheck,
            enabled = !working && configuration.host.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = UqGreen),
        ) { Text("プリンター状態確認", fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onRefresh, enabled = !working, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("キューを更新")
        }
    }
}

@Composable
private fun JobListPanel(
    modifier: Modifier,
    jobs: List<UnifiedPrintJob>,
    criteria: UnifiedPrintQueueCriteria,
    selectedKey: String?,
    onCriteriaChange: (UnifiedPrintQueueCriteria) -> Unit,
    onSelect: (UnifiedPrintJob) -> Unit,
) {
    QueuePanel(modifier) {
        Text("印刷ジョブ ${jobs.size}件", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = UqNavy)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            UnifiedPrintStatusFilter.entries.forEach { candidate ->
                QueueChoice(
                    label = candidate.displayName,
                    selected = criteria.status == candidate,
                    modifier = Modifier.weight(1f),
                ) { onCriteriaChange(criteria.copy(status = candidate)) }
            }
        }
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            QueueCycleButton(
                label = criteria.type.displayName,
                modifier = Modifier.weight(1f),
                onClick = { onCriteriaChange(criteria.copy(type = criteria.type.next())) },
            )
            QueueCycleButton(
                label = criteria.time.displayName,
                modifier = Modifier.weight(1f),
                onClick = { onCriteriaChange(criteria.copy(time = criteria.time.next())) },
            )
            QueueCycleButton(
                label = criteria.attempts.displayName,
                modifier = Modifier.weight(1f),
                onClick = { onCriteriaChange(criteria.copy(attempts = criteria.attempts.next())) },
            )
        }
        Spacer(Modifier.height(7.dp))
        OutlinedTextField(
            value = criteria.query,
            onValueChange = { onCriteriaChange(criteria.copy(query = it.take(80))) },
            label = { Text("Job番号・参照No・エラー・印字内容で検索") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        if (jobs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("条件に該当する印刷ジョブはありません", color = Color.Gray)
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(jobs, key = { it.key }) { job ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(if (job.key == selectedKey) UqSelected else Color.Transparent)
                            .clickable { onSelect(job) }
                            .padding(horizontal = 8.dp, vertical = 9.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(job.type.displayName, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text(queueJobStatusLabel(job.status), color = queueJobColor(job.status), fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "Job.${job.sourceId} / 参照No.${job.referenceId} / ${job.paperWidthMm}mm / ${queueDate(job.createdAt)}",
                            color = Color.Gray,
                            fontSize = 13.sp,
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("試行 ${job.attemptCount}回", color = Color.Gray, fontSize = 12.sp)
                            if (job.failureCategory != UnifiedPrintFailureCategory.NONE) {
                                Spacer(Modifier.width(10.dp))
                                Text(job.failureCategory.displayName, color = UqRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JobDetailPanel(
    modifier: Modifier,
    selected: UnifiedPrintJob?,
    working: Boolean,
    managerPin: String,
    discardReason: String,
    forceConfirmed: Boolean,
    onManagerPinChanged: (String) -> Unit,
    onDiscardReasonChanged: (String) -> Unit,
    onForceConfirmedChanged: (Boolean) -> Unit,
    onRetry: (UnifiedPrintJob) -> Unit,
    onSafePrint: (UnifiedPrintJob) -> Unit,
    onForcePrint: (UnifiedPrintJob) -> Unit,
    onDiscard: (UnifiedPrintJob) -> Unit,
) {
    QueuePanel(modifier) {
        Text("ジョブ詳細", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = UqNavy)
        Spacer(Modifier.height(8.dp))
        if (selected == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("印刷ジョブを選択してください", color = Color.Gray)
            }
            return@QueuePanel
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(selected.type.displayName, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(queueJobStatusLabel(selected.status), color = queueJobColor(selected.status), fontWeight = FontWeight.Bold)
        }
        Text(
            "Job.${selected.sourceId} / 参照No.${selected.referenceId} / 試行${selected.attemptCount}回 / ${selected.paperWidthMm}mm",
            color = Color.Gray,
        )
        if (selected.failureCategory != UnifiedPrintFailureCategory.NONE) {
            Spacer(Modifier.height(6.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = UqRed.copy(alpha = 0.08f)),
                border = BorderStroke(1.dp, UqRed),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxWidth().padding(10.dp)) {
                    Text("失敗分類：${selected.failureCategory.displayName}", color = UqRed, fontWeight = FontWeight.Bold)
                    Text(selected.failureCategory.operatorGuidance, color = Color.DarkGray, fontSize = 13.sp)
                    if (!selected.lastError.isNullOrBlank()) {
                        Text("詳細：${selected.lastError}", color = UqRed, fontSize = 12.sp)
                    }
                }
            }
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

        if (UnifiedPrintJobActionPolicy.mayRetry(selected.status)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedButton(
                    onClick = { onRetry(selected) },
                    enabled = !working,
                    modifier = Modifier.weight(1f).height(50.dp),
                ) { Text("再試行待ちへ") }
                Button(
                    onClick = { onSafePrint(selected) },
                    enabled = !working,
                    modifier = Modifier.weight(1f).height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = UqBlue),
                ) { Text("安全印刷", fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(7.dp))
            OutlinedTextField(
                value = managerPin,
                onValueChange = onManagerPinChanged,
                label = { Text("強制印刷・破棄の責任者PIN") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth().clickable { onForceConfirmedChanged(!forceConfirmed) }.padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = forceConfirmed, onCheckedChange = onForceConfirmedChanged)
                Text("紙が出ていないことを目視確認しました", color = UqRed, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = { onForcePrint(selected) },
                enabled = !working && forceConfirmed && managerPin.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = UqRed),
            ) { Text("責任者承認付き強制印刷", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(7.dp))
            OutlinedTextField(
                value = discardReason,
                onValueChange = onDiscardReasonChanged,
                label = { Text("破棄理由（4文字以上・監査ログへ保存）") },
                minLines = 2,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { onDiscard(selected) },
                enabled = !working && managerPin.isNotBlank() && discardReason.trim().length >= 4,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                border = BorderStroke(2.dp, UqRed),
            ) { Text("責任者承認でジョブを破棄", color = UqRed, fontWeight = FontWeight.Bold) }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F3F5)),
            ) {
                Text(
                    when (selected.status) {
                        PrintJobStatus.COMPLETED -> "完了済みです。再度必要な場合は元の売上・履歴から再印字を登録してください。"
                        PrintJobStatus.DISCARDED -> "責任者承認で破棄済みです。未印刷件数やZ精算ブロックには含まれません。"
                        PrintJobStatus.PRINTING -> "印刷処理中です。完了または失敗状態へ変わるまで操作できません。"
                        else -> "この状態のジョブは操作できません。"
                    },
                    modifier = Modifier.padding(12.dp),
                    color = UqMuted,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun QueueFooter(heightDp: Int, message: String?, working: Boolean, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(heightDp.dp).background(Color.White).padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onClose, enabled = !working, modifier = Modifier.width(220.dp).fillMaxHeight()) {
            Text("閉じる", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(16.dp))
        if (!message.isNullOrBlank()) {
            Text(message, color = queueMessageColor(message), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        } else {
            Text(
                "FAILEDは自動再送しません。強制印刷と破棄は責任者PIN・監査ログを必須とします。",
                color = UqRed,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
            )
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
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = Color.DarkGray)
        Text(value, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
    }
}

@Composable
private fun QueueChoice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) UqRed else UqBorder),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
    ) { Text(label, fontWeight = FontWeight.Bold, color = UqNavy, fontSize = 12.sp) }
}

@Composable
private fun QueueCycleButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 5.dp),
    ) { Text(label, color = UqNavy, fontWeight = FontWeight.Bold, fontSize = 12.sp, textAlign = TextAlign.Center) }
}

private inline fun <reified T : Enum<T>> T.next(): T {
    val values = enumValues<T>()
    return values[(ordinal + 1) % values.size]
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
    PrintJobStatus.DISCARDED -> UqMuted
}

private fun queueJobStatusLabel(status: PrintJobStatus): String = when (status) {
    PrintJobStatus.PENDING -> "待機"
    PrintJobStatus.PRINTING -> "印刷中"
    PrintJobStatus.COMPLETED -> "完了"
    PrintJobStatus.RETRY -> "再試行"
    PrintJobStatus.FAILED -> "要確認"
    PrintJobStatus.DISCARDED -> "破棄済み"
}

private fun queueMessageColor(message: String): Color = if (
    message.contains("失敗") || message.contains("停止") || message.contains("エラー") ||
    message.contains("ありません") || message.contains("違います") || message.contains("必要")
) UqRed else UqGreen

private fun queueDate(epochMillis: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date(epochMillis))
