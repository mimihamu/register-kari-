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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val AuditCsvNavyV082 = Color(0xFF173F6B)
private val AuditCsvBlueV082 = Color(0xFF1976B9)
private val AuditCsvBackgroundV082 = Color(0xFFF4F7FA)
private val AuditCsvBorderV082 = Color(0xFFD5DEE7)
private val AuditCsvDangerV082 = Color(0xFFC62828)
private val AuditCsvGreenV082 = Color(0xFF2E7D32)

class SettlementReconciliationAuditCsvExportActivityV082 : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureRegisterSystemBars(window)
        setContent {
            MaterialTheme {
                SettlementReconciliationAuditCsvExportRouteV082(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun SettlementReconciliationAuditCsvExportRouteV082(onClose: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val exporter = remember { SettlementReconciliationAuditCsvExporterV082(appContext) }
    val scope = rememberCoroutineScope()
    var operator by remember { mutableStateOf(OperatorSessionRegistry.current(appContext)) }
    var filter by remember { mutableStateOf(SettlementReconciliationAuditFilterV081.ALL) }
    var searchText by remember { mutableStateOf("") }
    var appliedSearch by remember { mutableStateOf("") }
    var matchingCount by remember { mutableIntStateOf(0) }
    var countKnown by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingCriteria by remember { mutableStateOf<SettlementReconciliationAuditExportCriteriaV082?>(null) }

    DisposableEffect(Unit) {
        onDispose { exporter.close() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            operator = OperatorSessionRegistry.current(appContext)
        }
    }

    val csvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        val criteria = pendingCriteria
        pendingCriteria = null
        if (uri != null) {
            val current = OperatorSessionRegistry.current(appContext)
            if (criteria == null) {
                message = "CSV出力条件を取得できませんでした"
            } else if (current == null || !SettlementReconciliationAuditLedgerPolicyV081.canView(current.permissions)) {
                message = "閲覧権限が失効したためCSV出力を中止しました"
            } else {
                scope.launch {
                    val result: Result<Int> = withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                                exporter.exportSnapshot(criteria, output)
                            } ?: error("CSV保存先を開けませんでした")
                        }
                    }
                    message = result.fold(
                        onSuccess = { count -> "CSV出力完了: ${count}件" },
                        onFailure = { error -> "CSV出力失敗: ${error.message ?: "書き込みエラー"}" },
                    )
                }
            }
        }
        Unit
    }

    val current = operator
    Surface(Modifier.fillMaxSize(), color = AuditCsvBackgroundV082) {
        if (current == null || !SettlementReconciliationAuditLedgerPolicyV081.canView(current.permissions)) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("整合確認監査CSV出力を利用できません", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = AuditCsvDangerV082)
                Spacer(Modifier.height(12.dp))
                Text("売上参照権限と、X点検またはZ精算権限が必要です。")
                Spacer(Modifier.height(24.dp))
                Button(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("閉じる") }
            }
            return@Surface
        }

        OperatorSessionRegistry.touch(appContext)
        val metrics = rememberRegisterResponsiveMetrics()
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(metrics.headerHeightDp.dp).background(AuditCsvNavyV082)
                    .padding(horizontal = metrics.screenPaddingDp.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("つぐレジ", color = Color.White, fontSize = if (metrics.isCompact) 19.sp else 23.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(20.dp))
                Text("SCR-522  整合確認監査 CSV出力", color = Color.White, fontSize = if (metrics.isCompact) 17.sp else 21.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("担当 ${current.name}", color = Color.White, fontSize = 13.sp)
            }

            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(metrics.screenPaddingDp.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, AuditCsvBorderV082),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("出力条件", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AuditCsvNavyV082)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettlementReconciliationAuditFilterV081.entries.forEach { item ->
                                OutlinedButton(
                                    onClick = {
                                        filter = item
                                        countKnown = false
                                        message = null
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    border = BorderStroke(if (filter == item) 3.dp else 1.dp, if (filter == item) AuditCsvDangerV082 else AuditCsvBorderV082),
                                ) { Text(item.displayName, fontWeight = FontWeight.Bold) }
                            }
                        }
                        OutlinedTextField(
                            value = searchText,
                            onValueChange = {
                                searchText = it.take(80)
                                countKnown = false
                                message = null
                            },
                            singleLine = true,
                            label = { Text("レポートNo.・担当者・営業日・セッション") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "検索文字はレポートNo.、担当者、比較証跡内の営業日・営業セッションなどに適用します。",
                            color = Color.DarkGray,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    appliedSearch = searchText.trim()
                                    val result = runCatching { exporter.count(filter, appliedSearch) }
                                    result.onSuccess {
                                        matchingCount = it
                                        countKnown = true
                                        message = "対象件数: ${it}件"
                                    }.onFailure {
                                        countKnown = false
                                        message = "件数確認失敗: ${it.message ?: "読込エラー"}"
                                    }
                                },
                                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                            ) { Text("この条件の件数を確認", fontWeight = FontWeight.Bold) }
                            OutlinedButton(
                                onClick = {
                                    searchText = ""
                                    appliedSearch = ""
                                    filter = SettlementReconciliationAuditFilterV081.ALL
                                    matchingCount = 0
                                    countKnown = false
                                    message = null
                                },
                                modifier = Modifier.weight(0.6f).heightIn(min = 52.dp),
                            ) { Text("条件クリア") }
                        }
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, AuditCsvBorderV082),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("CSV保存", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AuditCsvNavyV082)
                        Text(
                            if (countKnown) "直近確認件数: ${matchingCount}件" else "件数確認は任意です。CSV保存時に現在条件を確定します。",
                            color = if (countKnown) AuditCsvGreenV082 else Color.DarkGray,
                            fontWeight = if (countKnown) FontWeight.Bold else FontWeight.Normal,
                        )
                        Text(
                            "CSVボタンを押した時点の最新監査を上限として固定します。保存先選択中に新しい監査が追加されても、そのCSVには混入しません。",
                            color = Color.DarkGray,
                        )
                        Button(
                            onClick = {
                                val currentOperator = OperatorSessionRegistry.current(appContext)
                                if (currentOperator == null || !SettlementReconciliationAuditLedgerPolicyV081.canView(currentOperator.permissions)) {
                                    message = "閲覧権限が失効したためCSV出力を開始できません"
                                } else {
                                    appliedSearch = searchText.trim()
                                    val snapshotResult = runCatching { exporter.captureSnapshot(filter, appliedSearch) }
                                    snapshotResult.onSuccess { snapshot ->
                                        pendingCriteria = SettlementReconciliationAuditExportCriteriaV082(
                                            filter = filter,
                                            searchText = appliedSearch,
                                            snapshot = snapshot,
                                        )
                                        message = null
                                        csvLauncher.launch(SettlementReconciliationAuditCsvPolicyV082.fileName())
                                    }.onFailure {
                                        message = "CSV出力条件の固定に失敗しました: ${it.message ?: "読込エラー"}"
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AuditCsvBlueV082),
                        ) {
                            Text("現在条件をCSV保存", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        message?.let {
                            Text(
                                it,
                                color = if (it.startsWith("CSV出力完了") || it.startsWith("対象件数")) AuditCsvGreenV082 else AuditCsvDangerV082,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, AuditCsvBorderV082),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("出力内容・安全性", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AuditCsvNavyV082)
                        Text("監査No.、実行日時、判定、レポートNo.、点検/精算種別、営業日、営業セッション、snapshot種別、差異件数、担当者、イベント種別、比較証跡を出力します。")
                        Text("UTF-8 BOM + CRLF、全セルquoted。Excel等で数式と解釈され得る文字列は先頭を保護します。")
                        Text("operation_audit、売上、税、支払、点検精算snapshotへのINSERT / UPDATE / DELETEは行いません。")
                        Text("OAuth token、Authorization header、content URI、生の売上JSONはCSVへ含めません。", color = Color.DarkGray)
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().height(metrics.bottomBarHeightDp.dp).background(Color.White)
                    .padding(horizontal = metrics.screenPaddingDp.dp, vertical = 8.dp),
            ) {
                OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Text("点検・精算履歴へ戻る", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
