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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PvNavy = Color(0xFF173F6B)
private val PvBlue = Color(0xFF1976B9)
private val PvGreen = Color(0xFF2E7D32)
private val PvOrange = Color(0xFFEF6C00)
private val PvRed = Color(0xFFC62828)
private val PvPurple = Color(0xFF6A4C93)
private val PvBackground = Color(0xFFF4F7FA)
private val PvBorder = Color(0xFFD5DEE7)

class PrinterStatusValidationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                PrinterStatusValidationScreen(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun PrinterStatusValidationScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rawStore = remember { PrinterStatusProbeStore(context.applicationContext) }
    val candidateStore = remember { PrinterStatusProfileCandidateStore(context.applicationContext) }

    fun loadReports(): List<PrinterStatusValidationReport> =
        PrinterStatusValidationPolicy.buildAll(
            PrinterStatusProbeAnalysisPolicy.analyze(
                rawStore.listRecent(PrinterStatusProbeRetentionPolicy.MAX_ROWS),
            ),
        )

    var reports by remember { mutableStateOf(loadReports()) }
    var selectedKey by remember { mutableStateOf(reports.firstOrNull()?.key) }
    var candidates by remember { mutableStateOf(candidateStore.listRecent(200)) }
    var selectedCandidateId by remember { mutableStateOf(candidates.firstOrNull()?.id) }
    var actor by remember { mutableStateOf("責任者") }
    var reviewReason by remember { mutableStateOf("") }
    var pendingCsv by remember { mutableStateOf<String?>(null) }
    var message by remember {
        mutableStateOf("保存済みRAWだけを集計します。この画面からプリンターへ通信・印刷・カット・ドロア送信は行いません")
    }
    var messageColor by remember { mutableStateOf(PvNavy) }

    val selectedReport = reports.firstOrNull { it.key == selectedKey } ?: reports.firstOrNull()
    val selectedCandidate = candidates.firstOrNull { it.id == selectedCandidateId }

    DisposableEffect(Unit) {
        onDispose {
            rawStore.close()
            candidateStore.close()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        val csv = pendingCsv
        if (uri == null || csv == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    requireNotNull(context.contentResolver.openOutputStream(uri, "w"))
                        .bufferedWriter(Charsets.UTF_8)
                        .use { it.write(csv) }
                }
            }
            message = result.fold(
                onSuccess = { "UTF-8 BOM付き最終レポートCSVを保存しました" },
                onFailure = { "CSV保存に失敗しました：${it.message ?: it.javaClass.simpleName}" },
            )
            messageColor = if (result.isSuccess) PvGreen else PvRed
            pendingCsv = null
        }
    }

    fun reload() {
        reports = loadReports()
        selectedKey = selectedKey?.takeIf { key -> reports.any { it.key == key } }
            ?: reports.firstOrNull()?.key
        candidates = candidateStore.listRecent(200)
        selectedCandidateId = selectedCandidateId?.takeIf { id -> candidates.any { it.id == id } }
            ?: candidates.firstOrNull()?.id
        message = "最終検証結果と候補一覧を再読込しました"
        messageColor = PvGreen
    }

    fun createCandidate() {
        val report = selectedReport ?: return
        val result = runCatching { candidateStore.createDraft(report, actor) }
        result.onSuccess { created ->
            candidates = candidateStore.listRecent(200)
            selectedCandidateId = created.id
            message = "未承認候補ID ${created.id}を作成しました。runtimeには適用されません"
            messageColor = PvGreen
        }.onFailure {
            message = "候補を作成できません：${it.message ?: it.javaClass.simpleName}"
            messageColor = PvRed
        }
    }

    fun review(status: PrinterStatusProfileCandidateStatus) {
        val candidate = selectedCandidate ?: return
        val result = runCatching {
            candidateStore.review(
                id = candidate.id,
                status = status,
                note = reviewReason,
                actor = actor,
            )
        }
        result.onSuccess { reviewed ->
            candidates = candidateStore.listRecent(200)
            selectedCandidateId = reviewed?.id
            reviewReason = ""
            message = when (status) {
                PrinterStatusProfileCandidateStatus.APPROVED ->
                    "候補ID ${candidate.id}を承認しました。ただしruntimeApplied=falseのままです"
                PrinterStatusProfileCandidateStatus.REJECTED ->
                    "候補ID ${candidate.id}を却下しました"
                PrinterStatusProfileCandidateStatus.DRAFT -> "レビュー結果が不正です"
            }
            messageColor = if (status == PrinterStatusProfileCandidateStatus.REJECTED) PvOrange else PvGreen
        }.onFailure {
            message = "レビューを保存できません：${it.message ?: it.javaClass.simpleName}"
            messageColor = PvRed
        }
    }

    Surface(Modifier.fillMaxSize(), color = PvBackground) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).background(PvNavy).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("つぐレジ", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(24.dp))
                Text("プリンター検証", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("最終検証・承認候補（runtime未適用）", color = Color.White, fontSize = 14.sp)
            }

            Row(
                Modifier.weight(1f).fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ValidationPanel(Modifier.width(350.dp).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("実機グループ", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = PvNavy)
                            Text("型番・モード・接続先単位", fontSize = 11.sp, color = Color.Gray)
                        }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = ::reload) { Text("再集計") }
                    }
                    Spacer(Modifier.height(6.dp))
                    if (reports.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("条件付きRAW履歴がありません", color = Color.Gray)
                        }
                    } else {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            reports.forEach { report ->
                                val selected = report.key == selectedReport?.key
                                val stateColor = when {
                                    report.evidenceReadyForReview -> PvGreen
                                    report.overallConfidence == PrinterEvidenceConfidence.NOT_READY -> PvRed
                                    else -> PvOrange
                                }
                                OutlinedButton(
                                    onClick = { selectedKey = report.key },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) PvRed else stateColor),
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(report.key.displayName, fontWeight = FontWeight.Bold, color = PvNavy)
                                        Text(
                                            "${report.key.host}:${report.key.port} / ${report.key.profile.displayName}",
                                            fontSize = 11.sp,
                                            color = Color.DarkGray,
                                        )
                                        Text(
                                            "RAW ${report.analysis.records.size}件 / " +
                                                "信頼度 ${report.overallConfidence.displayName} / " +
                                                if (report.evidenceReadyForReview) "レビュー可能" else "未成立",
                                            color = stateColor,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                ValidationPanel(Modifier.weight(1f).fillMaxHeight()) {
                    Text("最終検証結果", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = PvNavy)
                    val report = selectedReport
                    if (report == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("表示する検証結果がありません", color = Color.Gray)
                        }
                    } else {
                        Text(
                            "${report.key.printerModel} / ${report.key.emulationMode} / ${report.key.host}:${report.key.port}",
                            fontWeight = FontWeight.Bold,
                        )
                        Text(report.manufacturerVerificationNote, color = PvOrange, fontSize = 11.sp)
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ValidationBadge("RAW", report.analysis.records.size.toString(), PvBlue, Modifier.weight(1f))
                            ValidationBadge("信頼度", report.overallConfidence.displayName, confidenceColor(report.overallConfidence), Modifier.weight(1f))
                            ValidationBadge("外れ値", report.totalOutlierCount.toString(), if (report.totalOutlierCount == 0) PvGreen else PvOrange, Modifier.weight(1f))
                            ValidationBadge("差分bit", report.stableChangeCount.toString(), PvPurple, Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(6.dp))
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            report.evidence.forEach { item ->
                                val color = if (item.ready) PvGreen else PvRed
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.06f)),
                                    border = BorderStroke(1.dp, color),
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Column(Modifier.fillMaxWidth().padding(8.dp)) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text(item.condition.displayName, fontWeight = FontWeight.Bold, color = PvNavy)
                                            Spacer(Modifier.weight(1f))
                                            Text(
                                                "${item.confidence.displayName} / ${if (item.ready) "成立" else "未成立"}",
                                                color = color,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                        Text(
                                            "総数${item.totalCount} 成功${item.successCount} 失敗${item.failureCount}" +
                                                (item.cluster?.let {
                                                    " / 一致${it.agreementPercent}% / 外れ値${it.outlierCount} / 長さ${it.responseLengths.joinToString("/")}"
                                                } ?: ""),
                                            fontSize = 11.sp,
                                        )
                                        item.cluster?.dominantResponseHex?.takeIf(String::isNotBlank)?.let {
                                            Text("代表応答 $it", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                        }
                                        Text(item.reason, color = color, fontSize = 11.sp)
                                        Text(
                                            "元履歴ID ${item.sourceRecordIds.joinToString("/")}",
                                            color = Color.Gray,
                                            fontSize = 10.sp,
                                        )
                                        item.candidate?.stableChanges.orEmpty().forEach { change ->
                                            Text(change.label, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                            if (report.blockers.isNotEmpty()) {
                                Text("未成立理由", color = PvRed, fontWeight = FontWeight.Bold)
                                report.blockers.forEach { Text("・$it", color = PvRed, fontSize = 11.sp) }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    pendingCsv = PrinterStatusValidationCsv.render(report)
                                    val model = report.key.printerModel.ifBlank { "unknown" }
                                        .replace(Regex("[^A-Za-z0-9._-]"), "_")
                                        .take(40)
                                    exportLauncher.launch(
                                        "TSUGUREGI_printer_validation_${model}_${System.currentTimeMillis()}.csv",
                                    )
                                },
                                modifier = Modifier.weight(1f).height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PvPurple),
                            ) { Text("最終レポートCSV", fontWeight = FontWeight.Bold) }
                            Button(
                                onClick = ::createCandidate,
                                enabled = PrinterStatusProfileCandidatePolicy.canCreate(report),
                                modifier = Modifier.weight(1f).height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PvBlue),
                            ) { Text("候補作成", fontWeight = FontWeight.Bold) }
                        }
                    }
                }

                ValidationPanel(Modifier.width(430.dp).fillMaxHeight()) {
                    Text("承認候補", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = PvNavy)
                    Text(
                        "承認してもruntimeApplied=false。正式反映には将来のコードレビューと実装変更が必要です",
                        color = PvRed,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        if (candidates.isEmpty()) {
                            Text("候補はありません", color = Color.Gray)
                        }
                        candidates.forEach { candidate ->
                            val selected = candidate.id == selectedCandidate?.id
                            val color = candidateStatusColor(candidate.status)
                            OutlinedButton(
                                onClick = { selectedCandidateId = candidate.id },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) PvRed else color),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Row(Modifier.fillMaxWidth()) {
                                        Text("ID ${candidate.id}", fontWeight = FontWeight.Bold, color = PvNavy)
                                        Spacer(Modifier.weight(1f))
                                        Text(candidate.status.displayName, color = color, fontWeight = FontWeight.Bold)
                                    }
                                    Text("${candidate.printerModel} / ${candidate.emulationMode}", fontSize = 11.sp)
                                    Text(
                                        "信頼度${candidate.confidence.displayName} / 差分${candidate.stableChangeCount}bit",
                                        fontSize = 10.sp,
                                    )
                                    Text("元履歴 ${candidate.sourceRecordIds}", fontSize = 10.sp, color = Color.Gray)
                                    Text(
                                        "runtimeApplied=${candidate.runtimeApplied}",
                                        color = if (candidate.runtimeApplied) PvRed else PvGreen,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                    )
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = actor,
                        onValueChange = { actor = it.take(50) },
                        label = { Text("作成者／承認者") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = reviewReason,
                        onValueChange = { reviewReason = it.take(PrinterStatusProfileCandidatePolicy.MAX_REVIEW_NOTE) },
                        label = { Text("承認・却下理由（必須）") },
                        modifier = Modifier.fillMaxWidth().height(92.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { review(PrinterStatusProfileCandidateStatus.APPROVED) },
                            enabled = selectedCandidate?.status == PrinterStatusProfileCandidateStatus.DRAFT,
                            modifier = Modifier.weight(1f).height(46.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PvGreen),
                        ) { Text("承認", fontWeight = FontWeight.Bold) }
                        Button(
                            onClick = { review(PrinterStatusProfileCandidateStatus.REJECTED) },
                            enabled = selectedCandidate?.status == PrinterStatusProfileCandidateStatus.DRAFT,
                            modifier = Modifier.weight(1f).height(46.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PvRed),
                        ) { Text("却下", fontWeight = FontWeight.Bold) }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().height(72.dp).background(Color.White)
                    .padding(horizontal = 18.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onClose, modifier = Modifier.width(220.dp).fillMaxHeight()) {
                    Text("閉じる", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(16.dp))
                Text(message, color = messageColor, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    "この画面は保存済みデータの閲覧・CSV・候補審査専用",
                    color = PvRed,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun ValidationPanel(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, PvBorder),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(11.dp), content = content)
    }
}

@Composable
private fun ValidationBadge(label: String, value: String, color: Color, modifier: Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f)),
        border = BorderStroke(1.dp, color),
    ) {
        Column(Modifier.fillMaxWidth().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 10.sp, color = Color.Gray)
            Text(value, color = color, fontWeight = FontWeight.Bold)
        }
    }
}

private fun confidenceColor(confidence: PrinterEvidenceConfidence): Color = when (confidence) {
    PrinterEvidenceConfidence.HIGH -> PvGreen
    PrinterEvidenceConfidence.MEDIUM -> PvBlue
    PrinterEvidenceConfidence.LOW -> PvOrange
    PrinterEvidenceConfidence.NOT_READY -> PvRed
}

private fun candidateStatusColor(status: PrinterStatusProfileCandidateStatus): Color = when (status) {
    PrinterStatusProfileCandidateStatus.DRAFT -> PvOrange
    PrinterStatusProfileCandidateStatus.APPROVED -> PvGreen
    PrinterStatusProfileCandidateStatus.REJECTED -> PvRed
}
