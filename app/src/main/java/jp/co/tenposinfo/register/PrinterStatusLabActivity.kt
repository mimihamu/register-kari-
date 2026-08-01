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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PlNavy = Color(0xFF173F6B)
private val PlBlue = Color(0xFF1976B9)
private val PlGreen = Color(0xFF2E7D32)
private val PlOrange = Color(0xFFEF6C00)
private val PlRed = Color(0xFFC62828)
private val PlPurple = Color(0xFF6A4C93)
private val PlBackground = Color(0xFFF4F7FA)
private val PlBorder = Color(0xFFD5DEE7)

enum class PrinterStatusProbeOutcomeFilter(val displayName: String) {
    ALL("全件"),
    SUCCESS("成功"),
    FAILURE("失敗"),
}

class PrinterStatusLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                PrinterStatusLabScreen(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun PrinterStatusLabScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember { AdminSettingsStore(context.applicationContext) }
    val historyStore = remember { PrinterStatusProbeStore(context.applicationContext) }
    val actor = remember { OperatorSessionRegistry.lastKnownName() ?: "プリンター状態ラボ" }

    var configuration by remember { mutableStateOf(settingsStore.loadPrinterConfiguration()) }
    var preset by remember { mutableStateOf(PrinterStatusProbePolicy.presetFor(configuration.profile)) }
    var experimentalConfirmed by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var operationMessage by remember { mutableStateOf("状態コマンドを送らないTCP接続確認から開始できます") }
    var operationColor by remember { mutableStateOf(PlNavy) }
    var currentRecord by remember { mutableStateOf<PrinterStatusProbeHistoryRecord?>(null) }
    var history by remember { mutableStateOf(historyStore.listRecent(200)) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var profileFilter by remember { mutableStateOf<PrinterProfile?>(null) }
    var outcomeFilter by remember { mutableStateOf(PrinterStatusProbeOutcomeFilter.ALL) }
    var retentionText by remember { mutableStateOf(historyStore.retentionDays().toString()) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var pendingCsv by remember { mutableStateOf<String?>(null) }

    val availablePresets = remember(configuration.profile) {
        listOf(
            PrinterStatusProbePreset.TCP_CONNECT_ONLY,
            PrinterStatusProbePolicy.presetFor(configuration.profile),
        ).distinct()
    }
    val runAllowed = PrinterStatusProbePolicy.canRun(preset, experimentalConfirmed)
    val filteredHistory = history.filter { record ->
        (profileFilter == null || record.profile == profileFilter) &&
            when (outcomeFilter) {
                PrinterStatusProbeOutcomeFilter.ALL -> true
                PrinterStatusProbeOutcomeFilter.SUCCESS -> record.success
                PrinterStatusProbeOutcomeFilter.FAILURE -> !record.success
            }
    }
    val selectedRecords = selectedIds.mapNotNull { id -> history.firstOrNull { it.id == id } }
        .sortedBy { it.startedAt }
    val comparisons = if (PrinterStatusProbeComparisonPolicy.canCompare(selectedRecords)) {
        runCatching { PrinterStatusProbeComparisonPolicy.compare(selectedRecords) }.getOrDefault(emptyList())
    } else {
        emptyList()
    }

    DisposableEffect(Unit) {
        onDispose {
            historyStore.close()
            settingsStore.close()
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
            operationMessage = result.fold(
                onSuccess = { "RAWプローブ結果をCSV保存しました" },
                onFailure = { "CSV保存に失敗しました：${it.message ?: it.javaClass.simpleName}" },
            )
            operationColor = if (result.isSuccess) PlGreen else PlRed
            pendingCsv = null
        }
    }

    fun reloadHistory() {
        history = historyStore.listRecent(200)
        selectedIds = selectedIds.intersect(history.map { it.id }.toSet())
    }

    fun startProbe() {
        if (running || !runAllowed) return
        running = true
        currentRecord = null
        operationMessage = "${preset.displayName}を実行中です"
        operationColor = PlBlue
        val target = configuration
        val selectedPreset = preset
        val startedAt = System.currentTimeMillis()
        scope.launch {
            val probeResult = withContext(Dispatchers.IO) {
                TcpPrinterStatusProbeClient(target).execute(selectedPreset)
            }
            val stored = withContext(Dispatchers.IO) {
                probeResult.fold(
                    onSuccess = { historyStore.recordSuccess(target, it, actor) },
                    onFailure = { historyStore.recordFailure(target, selectedPreset, it, actor, startedAt) },
                )
            }
            currentRecord = stored
            reloadHistory()
            if (probeResult.isSuccess) {
                operationMessage = "完了：受信${stored.responseSize}バイト / 履歴ID ${stored.id}"
                operationColor = PlGreen
            } else {
                operationMessage = "プローブ失敗を履歴へ保存しました：${stored.errorMessage.orEmpty()}"
                operationColor = PlRed
            }
            running = false
        }
    }

    fun requestExport(records: List<PrinterStatusProbeHistoryRecord>) {
        if (records.isEmpty()) return
        pendingCsv = PrinterStatusProbeMultiCsv.render(records)
        val suffix = if (records.size == 1) records.first().id.toString() else "compare_${records.size}"
        exportLauncher.launch("TSUGUREGI_printer_status_probe_$suffix.csv")
    }

    Surface(Modifier.fillMaxSize(), color = PlBackground) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).background(PlNavy).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("つぐレジ", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(24.dp))
                Text("プリンター状態ラボ", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("RAW採取・履歴・最大4件比較", color = Color.White, fontSize = 14.sp)
            }

            Row(
                Modifier.weight(1f).fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LabPanel(Modifier.width(400.dp).fillMaxHeight()) {
                    Text("1. プローブ実行", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = PlNavy)
                    Spacer(Modifier.height(8.dp))
                    LabValue("プリンター", configuration.name)
                    LabValue("機種", configuration.profile.displayName)
                    LabValue("接続先", if (configuration.host.isBlank()) "未設定" else "${configuration.host}:${configuration.port}")
                    LabValue(
                        "検証区分",
                        PrinterStatusCapabilityRegistry.forProfile(configuration.profile).verification.displayName,
                    )
                    Spacer(Modifier.height(8.dp))
                    availablePresets.forEach { candidate ->
                        OutlinedButton(
                            onClick = {
                                preset = candidate
                                experimentalConfirmed = false
                                currentRecord = null
                            },
                            enabled = !running,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            border = BorderStroke(
                                if (preset == candidate) 3.dp else 1.dp,
                                if (preset == candidate) PlRed else PlBorder,
                            ),
                        ) {
                            Text(candidate.displayName, fontWeight = FontWeight.Bold)
                        }
                    }
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (preset.experimental) PlOrange.copy(alpha = 0.09f) else PlGreen.copy(alpha = 0.07f),
                        ),
                        border = BorderStroke(1.dp, if (preset.experimental) PlOrange else PlGreen),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(10.dp)) {
                            Text(
                                if (preset.experimental) "互換試行・未検証" else "印刷・カット・ドロア送信なし",
                                color = if (preset.experimental) PlOrange else PlGreen,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(preset.description, color = Color.DarkGray, fontSize = 13.sp, lineHeight = 18.sp)
                        }
                    }
                    if (preset.experimental) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = experimentalConfirmed,
                                onCheckedChange = { experimentalConfirmed = it },
                                enabled = !running,
                            )
                            Text("未検証コマンドの送信を確認", fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = operationColor.copy(alpha = 0.07f)),
                        border = BorderStroke(1.dp, operationColor),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            operationMessage,
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            color = operationColor,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 19.sp,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = {
                            configuration = settingsStore.loadPrinterConfiguration()
                            preset = PrinterStatusProbePolicy.presetFor(configuration.profile)
                            experimentalConfirmed = false
                            currentRecord = null
                            operationMessage = "保存済みプリンター設定を再読込しました"
                            operationColor = PlNavy
                        },
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                    ) { Text("設定を再読込") }
                    Spacer(Modifier.height(7.dp))
                    Button(
                        onClick = ::startProbe,
                        enabled = !running && configuration.host.isNotBlank() && runAllowed,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PlBlue),
                    ) { Text(if (running) "実行中…" else "プローブを実行", fontWeight = FontWeight.Bold) }
                }

                LabPanel(Modifier.weight(1f).fillMaxHeight()) {
                    Text("2. 結果・比較", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = PlNavy)
                    Spacer(Modifier.height(8.dp))
                    val displayRecord = currentRecord ?: selectedRecords.lastOrNull()
                    if (displayRecord == null) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("プローブを実行するか、履歴を選択してください", color = Color.Gray)
                        }
                    } else {
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (displayRecord.success) PlGreen.copy(alpha = 0.07f) else PlRed.copy(alpha = 0.07f),
                                ),
                                border = BorderStroke(2.dp, if (displayRecord.success) PlGreen else PlRed),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        if (displayRecord.success) "受信 ${displayRecord.responseSize}バイト" else "プローブ失敗",
                                        fontSize = 25.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (displayRecord.success) PlGreen else PlRed,
                                    )
                                    Text("ID ${displayRecord.id} / ${formatLabTime(displayRecord.startedAt)}", color = Color.Gray)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            LabValue("機種", displayRecord.profile.displayName)
                            LabValue("プリセット", displayRecord.preset.displayName)
                            LabValue("接続先", "${displayRecord.host}:${displayRecord.port}")
                            LabValue("応答時間", "${displayRecord.elapsedMillis}ms")
                            LabCode("送信HEX", displayRecord.requestHex.ifBlank { "（送信なし）" })
                            LabCode("受信HEX", displayRecord.responseHex.ifBlank { "（受信なし）" })
                            LabCode("受信ASCII", displayRecord.responseAscii.ifBlank { "（受信なし）" })
                            if (!displayRecord.success) {
                                Text(displayRecord.errorMessage.orEmpty(), color = PlRed, fontWeight = FontWeight.Bold)
                            }
                            if (displayRecord.parsedSummary != null) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "EPSON形式参考解析：${displayRecord.parsedLevel?.displayName} / ${displayRecord.parsedSummary}",
                                    color = PlOrange,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            if (selectedRecords.size >= 2) {
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    "比較（基準 ID ${selectedRecords.first().id}）",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PlPurple,
                                )
                                comparisons.forEach { comparison ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (comparison.sameResponse) PlGreen.copy(alpha = 0.06f) else PlOrange.copy(alpha = 0.08f),
                                        ),
                                        border = BorderStroke(1.dp, if (comparison.sameResponse) PlGreen else PlOrange),
                                    ) {
                                        Column(Modifier.fillMaxWidth().padding(9.dp)) {
                                            Text(
                                                "ID ${comparison.comparedId}：${if (comparison.sameResponse) "同一応答" else "差分${comparison.differentByteCount}バイト"}",
                                                fontWeight = FontWeight.Bold,
                                            )
                                            Text(
                                                "サイズ ${comparison.baseSize} → ${comparison.comparedSize} / 位置 ${PrinterStatusProbeComparisonPolicy.changedPositionLabel(comparison)}",
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 12.sp,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { requestExport(listOf(displayRecord)) },
                                modifier = Modifier.weight(1f).height(46.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PlBlue),
                            ) { Text("この結果をCSV", fontWeight = FontWeight.Bold) }
                            Button(
                                onClick = { requestExport(selectedRecords) },
                                enabled = selectedRecords.size in 2..4,
                                modifier = Modifier.weight(1f).height(46.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PlPurple),
                            ) { Text("選択比較をCSV", fontWeight = FontWeight.Bold) }
                        }
                    }
                }

                LabPanel(Modifier.width(500.dp).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("3. 履歴", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = PlNavy)
                            Text("表示${filteredHistory.size}件 / 選択${selectedIds.size}件（最大4）", color = Color.Gray, fontSize = 12.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = { reloadHistory() }) { Text("更新") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        LabFilterButton("全機種", profileFilter == null) { profileFilter = null }
                        PrinterProfile.entries.forEach { profile ->
                            LabFilterButton(
                                when (profile) {
                                    PrinterProfile.EPSON_TM_JAPAN -> "EPSON"
                                    PrinterProfile.STAR_ESC_POS -> "STAR"
                                    PrinterProfile.GENERIC_ESC_POS -> "汎用"
                                },
                                profileFilter == profile,
                            ) { profileFilter = profile }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PrinterStatusProbeOutcomeFilter.entries.forEach { filter ->
                            LabFilterButton(filter.displayName, outcomeFilter == filter) { outcomeFilter = filter }
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        if (filteredHistory.isEmpty()) {
                            Text("該当する履歴はありません", color = Color.Gray)
                        }
                        filteredHistory.forEach { record ->
                            val selected = record.id in selectedIds
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = when {
                                        selected -> PlBlue.copy(alpha = 0.08f)
                                        record.success -> Color.White
                                        else -> PlRed.copy(alpha = 0.04f)
                                    },
                                ),
                                border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) PlRed else PlBorder),
                                shape = RoundedCornerShape(7.dp),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = selected,
                                        onCheckedChange = {
                                            selectedIds = if (selected) {
                                                selectedIds - record.id
                                            } else if (selectedIds.size < PrinterStatusProbeComparisonPolicy.MAX_SELECTION) {
                                                selectedIds + record.id
                                            } else {
                                                selectedIds
                                            }
                                            deleteConfirm = false
                                        },
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text("ID ${record.id}", fontWeight = FontWeight.Bold, color = PlNavy)
                                            Spacer(Modifier.weight(1f))
                                            Text(
                                                if (record.success) "成功" else "失敗",
                                                color = if (record.success) PlGreen else PlRed,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                        Text("${record.profile.displayName} / ${formatLabTime(record.startedAt)}", fontSize = 12.sp)
                                        Text(
                                            if (record.success) {
                                                "受信${record.responseSize}B ${record.responseHex.ifBlank { "応答なし" }}"
                                            } else {
                                                record.errorMessage.orEmpty()
                                            },
                                            color = Color.DarkGray,
                                            fontFamily = if (record.success) FontFamily.Monospace else FontFamily.Default,
                                            fontSize = 11.sp,
                                            maxLines = 2,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = retentionText,
                            onValueChange = { value ->
                                if (value.length <= 3 && value.all(Char::isDigit)) retentionText = value
                            },
                            label = { Text("保持日数") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.width(130.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        OutlinedButton(
                            onClick = {
                                val days = retentionText.toIntOrNull() ?: PrinterStatusProbeRetentionPolicy.DEFAULT_DAYS
                                historyStore.saveRetentionDays(days, actor)
                                retentionText = historyStore.retentionDays().toString()
                                reloadHistory()
                                operationMessage = "RAW履歴保持を${retentionText}日に設定しました"
                                operationColor = PlGreen
                            },
                            modifier = Modifier.weight(1f).height(54.dp),
                        ) { Text("保持設定を保存") }
                        Spacer(Modifier.width(6.dp))
                        Button(
                            onClick = {
                                if (!deleteConfirm) {
                                    deleteConfirm = true
                                    operationMessage = "選択${selectedIds.size}件を削除する場合は、もう一度削除を押してください"
                                    operationColor = PlRed
                                } else {
                                    val deleted = historyStore.delete(selectedIds, actor)
                                    selectedIds = emptySet()
                                    deleteConfirm = false
                                    reloadHistory()
                                    operationMessage = "RAWプローブ履歴${deleted}件を削除しました"
                                    operationColor = PlGreen
                                }
                            },
                            enabled = selectedIds.isNotEmpty(),
                            modifier = Modifier.width(120.dp).height(54.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PlRed),
                        ) { Text(if (deleteConfirm) "削除確定" else "選択削除", fontWeight = FontWeight.Bold) }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().height(68.dp).background(Color.White).padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onClose, enabled = !running, modifier = Modifier.width(220.dp).fillMaxHeight()) {
                    Text("閉じる", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "STAR／汎用のRAW応答採取は互換性確認完了を意味しません",
                    color = PlRed,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun LabFilterButton(label: String, selected: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.height(36.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) PlRed else PlBorder),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 9.dp, vertical = 2.dp),
    ) {
        Text(label, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun LabPanel(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, PlBorder),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(13.dp), content = content)
    }
}

@Composable
private fun LabValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = Color.DarkGray, fontSize = 13.sp)
        Text(value, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, fontSize = 13.sp)
    }
}

@Composable
private fun LabCode(label: String, value: String) {
    Spacer(Modifier.height(6.dp))
    Text(label, fontWeight = FontWeight.Bold, color = PlNavy, fontSize = 13.sp)
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F9FB)),
        border = BorderStroke(1.dp, PlBorder),
        shape = RoundedCornerShape(5.dp),
    ) {
        Text(
            value,
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
    }
}

private fun formatLabTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date(epochMillis))
