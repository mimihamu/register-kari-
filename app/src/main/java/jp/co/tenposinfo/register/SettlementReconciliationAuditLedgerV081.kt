package jp.co.tenposinfo.register

import android.database.sqlite.SQLiteDatabase
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val AuditNavyV081 = Color(0xFF173F6B)
private val AuditBlueV081 = Color(0xFF1976B9)
private val AuditBackgroundV081 = Color(0xFFF4F7FA)
private val AuditBorderV081 = Color(0xFFD5DEE7)
private val AuditDangerV081 = Color(0xFFC62828)
private val AuditGreenV081 = Color(0xFF2E7D32)
private val AuditInfoV081 = Color(0xFF6D4C41)
private val AuditSelectedV081 = Color(0xFFE3F2FD)

enum class SettlementReconciliationAuditFilterV081(val displayName: String, val eventType: String?) {
    ALL("すべて", null),
    OK("OK", "SETTLEMENT_RECONCILIATION_OK"),
    INFO("INFO", "SETTLEMENT_RECONCILIATION_INFO"),
    ALERT("ALERT", "SETTLEMENT_RECONCILIATION_ALERT"),
}

data class SettlementReconciliationAuditRecordV081(
    val id: Long,
    val eventType: String,
    val reportId: Long,
    val detail: String,
    val operatorName: String,
    val createdAt: Long,
) {
    val severity: SettlementReconciliationSeverity
        get() = when (eventType) {
            "SETTLEMENT_RECONCILIATION_ALERT" -> SettlementReconciliationSeverity.ALERT
            "SETTLEMENT_RECONCILIATION_INFO" -> SettlementReconciliationSeverity.INFO
            else -> SettlementReconciliationSeverity.OK
        }
}

data class SettlementReconciliationAuditSummaryV081(
    val ok: Int,
    val info: Int,
    val alert: Int,
) {
    val total: Int get() = ok + info + alert
}

data class SettlementReconciliationAuditQueryV081(
    val selection: String,
    val args: List<String>,
)

object SettlementReconciliationAuditLedgerPolicyV081 {
    const val EVENT_PREFIX = "SETTLEMENT_RECONCILIATION_"
    const val DEFAULT_LIMIT = 500

    fun canView(permissions: Set<RegisterPermission>): Boolean =
        RegisterPermission.VIEW_SALES in permissions &&
            (RegisterPermission.X_INSPECTION in permissions || RegisterPermission.Z_SETTLEMENT in permissions)

    fun query(filter: SettlementReconciliationAuditFilterV081, searchText: String): SettlementReconciliationAuditQueryV081 {
        val selectionParts = mutableListOf("event_type LIKE ?")
        val args = mutableListOf("$EVENT_PREFIX%")
        filter.eventType?.let {
            selectionParts += "event_type = ?"
            args += it
        }
        val search = searchText.trim().take(80)
        if (search.isNotEmpty()) {
            selectionParts += "(CAST(reference_id AS TEXT) LIKE ? OR operator_name LIKE ? OR detail LIKE ?)"
            val pattern = "%$search%"
            args.add(pattern)
            args.add(pattern)
            args.add(pattern)
        }
        return SettlementReconciliationAuditQueryV081(selectionParts.joinToString(" AND "), args)
    }
}

class SettlementReconciliationAuditLedgerStoreV081(context: android.content.Context) : AutoCloseable {
    private val baseDatabase = RegisterDatabase(context.applicationContext)
    private val db: SQLiteDatabase = baseDatabase.readableDatabase

    fun search(
        filter: SettlementReconciliationAuditFilterV081,
        searchText: String,
        limit: Int = SettlementReconciliationAuditLedgerPolicyV081.DEFAULT_LIMIT,
    ): List<SettlementReconciliationAuditRecordV081> {
        val query = SettlementReconciliationAuditLedgerPolicyV081.query(filter, searchText)
        return db.query(
            "operation_audit",
            arrayOf("id", "event_type", "reference_id", "detail", "operator_name", "created_at"),
            query.selection,
            query.args.toTypedArray(),
            null,
            null,
            "created_at DESC, id DESC",
            limit.coerceIn(1, 500).toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        SettlementReconciliationAuditRecordV081(
                            id = cursor.getLong(0),
                            eventType = cursor.getString(1),
                            reportId = cursor.getLong(2),
                            detail = cursor.getString(3),
                            operatorName = cursor.getString(4),
                            createdAt = cursor.getLong(5),
                        ),
                    )
                }
            }
        }
    }

    fun summary(): SettlementReconciliationAuditSummaryV081 {
        val counts = mutableMapOf<String, Int>()
        db.rawQuery(
            "SELECT event_type, COUNT(*) FROM operation_audit WHERE event_type LIKE ? GROUP BY event_type",
            arrayOf("${SettlementReconciliationAuditLedgerPolicyV081.EVENT_PREFIX}%"),
        ).use { cursor ->
            while (cursor.moveToNext()) counts[cursor.getString(0)] = cursor.getInt(1)
        }
        return SettlementReconciliationAuditSummaryV081(
            ok = counts["SETTLEMENT_RECONCILIATION_OK"] ?: 0,
            info = counts["SETTLEMENT_RECONCILIATION_INFO"] ?: 0,
            alert = counts["SETTLEMENT_RECONCILIATION_ALERT"] ?: 0,
        )
    }

    override fun close() = baseDatabase.close()
}

class SettlementReconciliationAuditLedgerActivityV081 : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureRegisterSystemBars(window)
        setContent {
            MaterialTheme {
                SettlementReconciliationAuditLedgerRouteV081(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun SettlementReconciliationAuditLedgerRouteV081(onClose: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val store = remember { SettlementReconciliationAuditLedgerStoreV081(appContext) }
    var operator by remember { mutableStateOf(OperatorSessionRegistry.current(appContext)) }
    var filter by remember { mutableStateOf(SettlementReconciliationAuditFilterV081.ALL) }
    var searchText by remember { mutableStateOf("") }
    var appliedSearch by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    var selectedId by remember { mutableStateOf<Long?>(null) }

    DisposableEffect(Unit) {
        onDispose { store.close() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            operator = OperatorSessionRegistry.current(appContext)
        }
    }

    val current = operator
    Surface(Modifier.fillMaxSize(), color = AuditBackgroundV081) {
        if (current == null || !SettlementReconciliationAuditLedgerPolicyV081.canView(current.permissions)) {
            AuditDeniedV081(onClose)
            return@Surface
        }
        OperatorSessionRegistry.touch(appContext)
        @Suppress("UNUSED_VARIABLE") val refresh = revision
        val summaryResult = remember(revision) { runCatching { store.summary() } }
        val recordsResult = remember(filter, appliedSearch, revision) {
            runCatching { store.search(filter, appliedSearch) }
        }
        val records = recordsResult.getOrDefault(emptyList())
        val selected = records.firstOrNull { it.id == selectedId } ?: records.firstOrNull()

        SettlementReconciliationAuditLedgerScreenV081(
            operatorName = current.name,
            summary = summaryResult.getOrNull() ?: SettlementReconciliationAuditSummaryV081(0, 0, 0),
            records = records,
            selected = selected,
            filter = filter,
            searchText = searchText,
            error = recordsResult.exceptionOrNull()?.message ?: summaryResult.exceptionOrNull()?.message,
            onFilterChanged = {
                filter = it
                selectedId = null
            },
            onSearchTextChanged = { searchText = it.take(80) },
            onSearch = {
                appliedSearch = searchText.trim()
                selectedId = null
                operator = OperatorSessionRegistry.current(appContext)
                revision++
            },
            onClear = {
                searchText = ""
                appliedSearch = ""
                filter = SettlementReconciliationAuditFilterV081.ALL
                selectedId = null
                revision++
            },
            onRefresh = {
                operator = OperatorSessionRegistry.current(appContext)
                revision++
            },
            onSelect = { selectedId = it },
            onClose = onClose,
        )
    }
}

@Composable
private fun SettlementReconciliationAuditLedgerScreenV081(
    operatorName: String,
    summary: SettlementReconciliationAuditSummaryV081,
    records: List<SettlementReconciliationAuditRecordV081>,
    selected: SettlementReconciliationAuditRecordV081?,
    filter: SettlementReconciliationAuditFilterV081,
    searchText: String,
    error: String?,
    onFilterChanged: (SettlementReconciliationAuditFilterV081) -> Unit,
    onSearchTextChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (Long) -> Unit,
    onClose: () -> Unit,
) {
    val metrics = rememberRegisterResponsiveMetrics()
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(metrics.headerHeightDp.dp).background(AuditNavyV081)
                .padding(horizontal = metrics.screenPaddingDp.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("つぐレジ", color = Color.White, fontSize = if (metrics.isCompact) 19.sp else 23.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(20.dp))
            Text("SCR-521  整合確認監査台帳", color = Color.White, fontSize = if (metrics.isCompact) 17.sp else 21.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("閲覧担当 $operatorName", color = Color.White, fontSize = 13.sp)
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = metrics.screenPaddingDp.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                AuditSummaryCardV081("全件", summary.total, AuditNavyV081, Modifier.weight(1f))
                AuditSummaryCardV081("OK", summary.ok, AuditGreenV081, Modifier.weight(1f))
                AuditSummaryCardV081("INFO", summary.info, AuditInfoV081, Modifier.weight(1f))
                AuditSummaryCardV081("ALERT", summary.alert, AuditDangerV081, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SettlementReconciliationAuditFilterV081.entries.forEach { item ->
                    OutlinedButton(
                        onClick = { onFilterChanged(item) },
                        modifier = Modifier.heightIn(min = 48.dp),
                        border = BorderStroke(if (filter == item) 3.dp else 1.dp, if (filter == item) AuditDangerV081 else AuditBorderV081),
                    ) { Text(item.displayName, fontWeight = FontWeight.Bold) }
                }
                OutlinedTextField(
                    value = searchText,
                    onValueChange = onSearchTextChanged,
                    singleLine = true,
                    label = { Text("レポートNo.・担当者・営業日・セッション") },
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onSearch, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors(containerColor = AuditBlueV081)) { Text("検索") }
                OutlinedButton(onClick = onClear, modifier = Modifier.heightIn(min = 48.dp)) { Text("クリア") }
                OutlinedButton(onClick = onRefresh, modifier = Modifier.heightIn(min = 48.dp)) { Text("更新") }
            }
            if (error != null) Text("監査履歴の読込に失敗しました: $error", color = AuditDangerV081, fontWeight = FontWeight.Bold)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val compact = metrics.isCompact || maxWidth < 1_000.dp || maxHeight < 520.dp
            if (compact) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(metrics.screenPaddingDp.dp),
                    verticalArrangement = Arrangement.spacedBy(metrics.panelGapDp.dp),
                ) {
                    AuditListV081(Modifier.fillMaxWidth().heightIn(min = 300.dp), records, selected?.id, onSelect)
                    AuditDetailV081(Modifier.fillMaxWidth().heightIn(min = 300.dp), selected)
                }
            } else {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = metrics.screenPaddingDp.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(metrics.panelGapDp.dp),
                ) {
                    AuditListV081(Modifier.weight(1.1f).fillMaxHeight(), records, selected?.id, onSelect)
                    AuditDetailV081(Modifier.weight(1f).fillMaxHeight(), selected)
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

@Composable
private fun AuditSummaryCardV081(label: String, count: Int, accent: Color, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, AuditBorderV081)) {
        Row(Modifier.fillMaxWidth().padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = accent, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("${count}件", color = accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AuditListV081(
    modifier: Modifier,
    records: List<SettlementReconciliationAuditRecordV081>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, AuditBorderV081), shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Text("監査履歴（新しい順・最大500件）", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AuditNavyV081)
            Spacer(Modifier.height(6.dp))
            if (records.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("該当する監査履歴はありません", color = Color.Gray) }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(records, key = { it.id }) { record ->
                        Card(
                            Modifier.fillMaxWidth().clickable { onSelect(record.id) },
                            colors = CardDefaults.cardColors(containerColor = if (record.id == selectedId) AuditSelectedV081 else Color.White),
                            border = BorderStroke(1.dp, AuditBorderV081),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(9.dp)) {
                                Row(Modifier.fillMaxWidth()) {
                                    Text(record.severity.name, color = auditSeverityColorV081(record.severity), fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.width(12.dp))
                                    Text("レポートNo.${record.reportId}", fontWeight = FontWeight.Bold, color = AuditNavyV081)
                                    Spacer(Modifier.weight(1f))
                                    Text(auditDateTimeV081(record.createdAt), color = Color.Gray, fontSize = 12.sp)
                                }
                                Text("担当 ${record.operatorName}", fontSize = 13.sp)
                                Text(record.detail, maxLines = 2, fontSize = 12.sp, color = Color.DarkGray)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditDetailV081(modifier: Modifier, selected: SettlementReconciliationAuditRecordV081?) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, AuditBorderV081), shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
            Text("監査詳細", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AuditNavyV081)
            Spacer(Modifier.height(8.dp))
            if (selected == null) {
                Text("履歴を選択してください", color = Color.Gray)
            } else {
                Text("判定 ${selected.severity.name}", color = auditSeverityColorV081(selected.severity), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                AuditDetailLineV081("監査No.", selected.id.toString())
                AuditDetailLineV081("レポートNo.", selected.reportId.toString())
                AuditDetailLineV081("実行担当", selected.operatorName)
                AuditDetailLineV081("実行日時", auditDateTimeV081(selected.createdAt))
                AuditDetailLineV081("イベント", selected.eventType)
                Spacer(Modifier.height(10.dp))
                Text("比較証跡", fontWeight = FontWeight.Bold, color = AuditNavyV081)
                Spacer(Modifier.height(5.dp))
                Text(selected.detail, fontSize = 14.sp, lineHeight = 21.sp)
                Spacer(Modifier.height(12.dp))
                Text("この画面は追記済み監査の読み取り専用です。既存監査・売上・税・支払・点検精算データを変更しません。", color = Color.Gray, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun AuditDetailLineV081(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, Modifier.width(120.dp), color = Color.DarkGray)
        Text(value, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AuditDeniedV081(onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("整合確認監査台帳を利用できません", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = AuditDangerV081)
        Spacer(Modifier.height(12.dp))
        Text("売上参照権限と、X点検またはZ精算権限が必要です。")
        Spacer(Modifier.height(24.dp))
        Button(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("閉じる") }
    }
}

private fun auditSeverityColorV081(severity: SettlementReconciliationSeverity): Color = when (severity) {
    SettlementReconciliationSeverity.OK -> AuditGreenV081
    SettlementReconciliationSeverity.INFO -> AuditInfoV081
    SettlementReconciliationSeverity.ALERT -> AuditDangerV081
}

private fun auditDateTimeV081(value: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date(value))
