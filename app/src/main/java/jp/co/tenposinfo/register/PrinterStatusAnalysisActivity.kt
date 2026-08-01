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

private val PaNavy = Color(0xFF173F6B)
private val PaBlue = Color(0xFF1976B9)
private val PaGreen = Color(0xFF2E7D32)
private val PaOrange = Color(0xFFEF6C00)
private val PaRed = Color(0xFFC62828)
private val PaPurple = Color(0xFF6A4C93)
private val PaBackground = Color(0xFFF4F7FA)
private val PaBorder = Color(0xFFD5DEE7)

class PrinterStatusAnalysisActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                PrinterStatusAnalysisScreen(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun PrinterStatusAnalysisScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { PrinterStatusProbeStore(context.applicationContext) }
    var analyses by remember {
        mutableStateOf(PrinterStatusProbeAnalysisPolicy.analyze(store.listRecent(PrinterStatusProbeRetentionPolicy.MAX_ROWS)))
    }
    var selectedKey by remember { mutableStateOf(analyses.firstOrNull()?.key) }
    var pendingCsv by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("保存済みRAWだけを分析します。プリンターへ通信しません") }
    var messageColor by remember { mutableStateOf(PaNavy) }
    val selected = analyses.firstOrNull { it.key == selectedKey } ?: analyses.firstOrNull()

    DisposableEffect(Unit) {
        onDispose { store.close() }
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
                onSuccess = { "条件採取進捗・ビット候補をCSV保存しました" },
                onFailure = { "CSV保存に失敗しました：${it.message ?: it.javaClass.simpleName}" },
            )
            messageColor = if (result.isSuccess) PaGreen else PaRed
            pendingCsv = null
        }
    }

    fun reload() {
        val refreshed = PrinterStatusProbeAnalysisPolicy.analyze(
            store.listRecent(PrinterStatusProbeRetentionPolicy.MAX_ROWS),
        )
        analyses = refreshed
        selectedKey = selectedKey?.takeIf { key -> refreshed.any { it.key == key } }
            ?: refreshed.firstOrNull()?.key
        message = "RAW履歴を再集計しました：実機グループ${refreshed.size}件"
        messageColor = PaGreen
    }

    Surface(Modifier.fillMaxSize(), color = PaBackground) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).background(PaNavy).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("つぐレジ", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(24.dp))
                Text("プリンター状態 応答分析", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("条件採取進捗・変化ビット候補", color = Color.White, fontSize = 14.sp)
            }

            if (analyses.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("分析できる条件付きRAW履歴がありません", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PaNavy)
                        Spacer(Modifier.height(10.dp))
                        Text("状態ラボで実機型番と試験条件を付けて採取してください", color = Color.DarkGray)
                    }
                }
            } else {
                Row(
                    Modifier.weight(1f).fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AnalysisPanel(Modifier.width(390.dp).fillMaxHeight()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("実機グループ", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = PaNavy)
                                Text("型番・モード・接続先・プリセット単位", fontSize = 12.sp, color = Color.Gray)
                            }
                            Spacer(Modifier.weight(1f))
                            OutlinedButton(onClick = ::reload) { Text("再集計") }
                        }
                        Spacer(Modifier.height(8.dp))
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                            analyses.forEach { analysis ->
                                val isSelected = analysis.key == selected?.key
                                OutlinedButton(
                                    onClick = { selectedKey = analysis.key },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    border = BorderStroke(if (isSelected) 3.dp else 1.dp, if (isSelected) PaRed else PaBorder),
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(analysis.key.displayName, fontWeight = FontWeight.Bold, color = PaNavy)
                                        Text(
                                            "${analysis.key.profile.displayName} / ${analysis.key.host}:${analysis.key.port}",
                                            fontSize = 12.sp,
                                            color = Color.DarkGray,
                                        )
                                        Text(
                                            "成功条件 ${analysis.successfulConditionCount}/${analysis.requiredConditionCount} / RAW ${analysis.records.size}件",
                                            color = if (analysis.successfulConditionCount == analysis.requiredConditionCount) PaGreen else PaOrange,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    AnalysisPanel(Modifier.width(430.dp).fillMaxHeight()) {
                        Text("条件採取進捗", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = PaNavy)
                        if (selected != null) {
                            Text(selected.key.displayName, fontWeight = FontWeight.Bold)
                            Text(
                                "${selected.key.preset.displayName} / ${selected.key.host}:${selected.key.port}",
                                color = Color.Gray,
                                fontSize = 12.sp,
                            )
                            Spacer(Modifier.height(8.dp))
                            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                selected.progress.forEach { progress ->
                                    val color = when {
                                        progress.successCount > 0 -> PaGreen
                                        progress.failureCount > 0 -> PaRed
                                        else -> PaOrange
                                    }
                                    Card(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.07f)),
                                        border = BorderStroke(1.dp, color),
                                        shape = RoundedCornerShape(8.dp),
                                    ) {
                                        Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                Text(progress.condition.displayName, fontWeight = FontWeight.Bold, color = PaNavy)
                                                Spacer(Modifier.weight(1f))
                                                Text(progress.stateLabel, color = color, fontWeight = FontWeight.Bold)
                                            }
                                            Text(
                                                "総数${progress.totalCount} / 成功${progress.successCount} / 失敗${progress.failureCount}",
                                                fontSize = 12.sp,
                                            )
                                            if (progress.latestAt > 0) {
                                                Text("最終 ${formatAnalysisTime(progress.latestAt)}", fontSize = 11.sp, color = Color.Gray)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    AnalysisPanel(Modifier.weight(1f).fillMaxHeight()) {
                        Text("正常時との差分ビット候補", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = PaNavy)
                        Text(
                            "同一グループ内で各条件の値が安定しているビットだけを候補表示します",
                            fontSize = 12.sp,
                            color = Color.DarkGray,
                        )
                        Spacer(Modifier.height(8.dp))
                        if (selected != null) {
                            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                selected.candidates.forEach { candidate ->
                                    val color = when {
                                        candidate.stableChanges.isNotEmpty() -> PaPurple
                                        candidate.sizeMismatch -> PaRed
                                        candidate.normalSampleCount == 0 || candidate.conditionSampleCount == 0 -> PaOrange
                                        else -> PaBlue
                                    }
                                    Card(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.06f)),
                                        border = BorderStroke(1.dp, color),
                                        shape = RoundedCornerShape(8.dp),
                                    ) {
                                        Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                Text(candidate.condition.displayName, fontWeight = FontWeight.Bold, color = PaNavy)
                                                Spacer(Modifier.weight(1f))
                                                Text(
                                                    "正常${candidate.normalSampleCount} / 条件${candidate.conditionSampleCount}",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                            }
                                            Text(candidate.note, color = color, fontSize = 12.sp, lineHeight = 17.sp)
                                            if (candidate.unstableBitCount > 0) {
                                                Text("サンプル内で不安定：${candidate.unstableBitCount}ビット", color = PaOrange, fontSize = 12.sp)
                                            }
                                            candidate.stableChanges.forEach { change ->
                                                Text(
                                                    change.label,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 12.sp,
                                                    modifier = Modifier.padding(top = 3.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    pendingCsv = PrinterStatusProbeAnalysisCsv.render(selected)
                                    val model = selected.key.printerModel.ifBlank { "unknown" }
                                        .replace(Regex("[^A-Za-z0-9._-]"), "_")
                                        .take(40)
                                    exportLauncher.launch("TSUGUREGI_status_analysis_${model}_${System.currentTimeMillis()}.csv")
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PaPurple),
                            ) { Text("進捗・候補をCSV保存", fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().height(72.dp).background(Color.White).padding(horizontal = 18.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onClose, modifier = Modifier.width(220.dp).fillMaxHeight()) {
                    Text("閉じる", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(16.dp))
                Text(message, color = messageColor, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    "候補は自動判定や正式プロトコルではありません",
                    color = PaRed,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun AnalysisPanel(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, PaBorder),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(13.dp), content = content)
    }
}

private fun formatAnalysisTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date(epochMillis))
