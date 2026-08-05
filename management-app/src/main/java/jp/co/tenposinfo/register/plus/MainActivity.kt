package jp.co.tenposinfo.register.plus

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ManagementUiState(
    val loading: Boolean = true,
    val importing: Boolean = false,
    val dashboard: ImportDashboard = ImportDashboard(
        totalImported = 0,
        totalRejected = 0,
        distinctStores = 0,
        latestImportedAt = null,
        eventTypeCounts = emptyMap(),
    ),
    val reportFilterOptions: SalesReportFilterOptions = SalesReportFilterOptions(),
    val reportFilter: SalesReportFilter = SalesReportFilter(),
    val reportFilterInitialized: Boolean = false,
    val salesReport: SalesReport = SalesReport.empty(),
    val recentImports: List<ImportedJournalSummary> = emptyList(),
    val recentRuns: List<ImportRunSummary> = emptyList(),
    val recentRejections: List<ImportRejectionSummary> = emptyList(),
    val lastBatch: ImportBatchResult? = null,
    val message: String? = null,
)

private data class ManagementSnapshot(
    val dashboard: ImportDashboard,
    val reportFilterOptions: SalesReportFilterOptions,
    val reportFilter: SalesReportFilter,
    val salesReport: SalesReport,
    val recentImports: List<ImportedJournalSummary>,
    val recentRuns: List<ImportRunSummary>,
    val recentRejections: List<ImportRejectionSummary>,
)

class MainActivity : ComponentActivity() {
    private val database by lazy { ManagementDatabase(this) }
    private val repository by lazy { SalesJournalImportRepository(database) }
    private val uiState = mutableStateOf(ManagementUiState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TsuguRegiPlusScreen(
                        state = uiState,
                        onImport = ::importUris,
                        onRefresh = ::refresh,
                        onReportFilterChanged = ::changeReportFilter,
                    )
                }
            }
        }
        refresh()
    }

    override fun onDestroy() {
        database.close()
        super.onDestroy()
    }

    private fun refresh() {
        val current = uiState.value
        loadSnapshot(
            requestedFilter = current.reportFilter,
            chooseLatestBusinessDate = !current.reportFilterInitialized,
        )
    }

    private fun changeReportFilter(filter: SalesReportFilter) {
        uiState.value = uiState.value.copy(
            loading = true,
            reportFilter = filter,
            reportFilterInitialized = true,
        )
        loadSnapshot(
            requestedFilter = filter,
            chooseLatestBusinessDate = false,
        )
    }

    private fun loadSnapshot(
        requestedFilter: SalesReportFilter,
        chooseLatestBusinessDate: Boolean,
    ) {
        lifecycleScope.launch {
            uiState.value = uiState.value.copy(loading = true)
            val snapshot = withContext(Dispatchers.IO) {
                buildSnapshot(requestedFilter, chooseLatestBusinessDate)
            }
            uiState.value = uiState.value.copy(
                loading = false,
                dashboard = snapshot.dashboard,
                reportFilterOptions = snapshot.reportFilterOptions,
                reportFilter = snapshot.reportFilter,
                reportFilterInitialized = true,
                salesReport = snapshot.salesReport,
                recentImports = snapshot.recentImports,
                recentRuns = snapshot.recentRuns,
                recentRejections = snapshot.recentRejections,
            )
        }
    }

    private fun importUris(uris: List<Uri>) {
        if (uris.isEmpty() || uiState.value.importing) return
        lifecycleScope.launch {
            uiState.value = uiState.value.copy(
                importing = true,
                message = "${uris.size}件を読み込んでいます",
            )
            val documents = withContext(Dispatchers.IO) {
                uris.map(::readDocument)
            }
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    repository.importDocuments(documents)
                }
            }
            val currentFilter = uiState.value.reportFilter
            val snapshot = withContext(Dispatchers.IO) {
                buildSnapshot(
                    requestedFilter = currentFilter,
                    chooseLatestBusinessDate = false,
                )
            }
            uiState.value = uiState.value.copy(
                loading = false,
                importing = false,
                dashboard = snapshot.dashboard,
                reportFilterOptions = snapshot.reportFilterOptions,
                reportFilter = snapshot.reportFilter,
                reportFilterInitialized = true,
                salesReport = snapshot.salesReport,
                recentImports = snapshot.recentImports,
                recentRuns = snapshot.recentRuns,
                recentRejections = snapshot.recentRejections,
                lastBatch = result.getOrNull(),
                message = result.fold(
                    onSuccess = {
                        "取込完了：新規${it.importedCount}件／重複${it.duplicateCount}件／隔離${it.rejectedCount}件"
                    },
                    onFailure = { "取込処理に失敗しました：${it.message ?: it.javaClass.simpleName}" },
                ),
            )
        }
    }

    private fun buildSnapshot(
        requestedFilter: SalesReportFilter,
        chooseLatestBusinessDate: Boolean,
    ): ManagementSnapshot {
        val options = repository.reportFilterOptions()
        val normalized = normalizeFilter(requestedFilter, options)
        val resolvedFilter = if (
            chooseLatestBusinessDate &&
            normalized.businessDate == null &&
            options.businessDates.isNotEmpty()
        ) {
            normalized.copy(businessDate = options.businessDates.first())
        } else {
            normalized
        }
        return ManagementSnapshot(
            dashboard = repository.dashboard(),
            reportFilterOptions = options,
            reportFilter = resolvedFilter,
            salesReport = repository.salesReport(resolvedFilter),
            recentImports = repository.recentImports(),
            recentRuns = repository.recentRuns(),
            recentRejections = repository.recentRejections(),
        )
    }

    private fun normalizeFilter(
        filter: SalesReportFilter,
        options: SalesReportFilterOptions,
    ): SalesReportFilter = filter.copy(
        businessDate = filter.businessDate?.takeIf(options.businessDates::contains),
        storeId = filter.storeId?.takeIf(options.storeIds::contains),
        terminalId = filter.terminalId?.takeIf(options.terminalIds::contains),
    )

    private fun readDocument(uri: Uri): SalesJournalImportDocument {
        val sourceName = queryDisplayName(uri)
            ?: uri.lastPathSegment
            ?: "名称不明.json"
        return try {
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > SalesJournalImportContract.MAX_DOCUMENT_BYTES) {
                        throw DocumentTooLargeException()
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            } ?: error("入力ストリームを開けません")
            SalesJournalImportDocument(
                sourceName = sourceName,
                sourceUri = uri.toString(),
                rawJson = bytes.toString(Charsets.UTF_8),
            )
        } catch (_: DocumentTooLargeException) {
            SalesJournalImportDocument(
                sourceName = sourceName,
                sourceUri = uri.toString(),
                rawJson = null,
                loadErrorCode = ImportRejectionCode.DOCUMENT_TOO_LARGE,
                loadErrorMessage = "JSONファイルが20MiBを超えています",
            )
        } catch (error: Exception) {
            SalesJournalImportDocument(
                sourceName = sourceName,
                sourceUri = uri.toString(),
                rawJson = null,
                loadErrorCode = ImportRejectionCode.READ_ERROR,
                loadErrorMessage = error.message ?: "ファイルを読み込めませんでした",
            )
        }
    }

    private fun queryDisplayName(uri: Uri): String? = contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

    private class DocumentTooLargeException : IllegalArgumentException()
}

@Composable
private fun TsuguRegiPlusScreen(
    state: State<ManagementUiState>,
    onImport: (List<Uri>) -> Unit,
    onRefresh: () -> Unit,
    onReportFilterChanged: (SalesReportFilter) -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
        onImport,
    )
    val current = state.value

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "つぐレジ＋",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "売上ジャーナル取込・営業日集計・取消相殺・取引確認",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(
                onClick = {
                    launcher.launch(
                        arrayOf(
                            "application/json",
                            "text/json",
                            "application/octet-stream",
                        ),
                    )
                },
                enabled = !current.importing,
            ) {
                Text(if (current.importing) "取込中…" else "JSONファイルを選択")
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onRefresh, enabled = !current.loading && !current.importing) {
                Text("更新")
            }
        }

        current.message?.let {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = it,
                    modifier = Modifier.padding(14.dp),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        if (current.loading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            DashboardSection(current.dashboard)
            val lastBatch = current.lastBatch
            if (lastBatch != null) {
                LastBatchSection(lastBatch)
            }
            SalesReportSection(
                options = current.reportFilterOptions,
                filter = current.reportFilter,
                report = current.salesReport,
                onFilterChanged = onReportFilterChanged,
            )
            EventCountSection(current.dashboard.eventTypeCounts)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                RecentImportsSection(
                    rows = current.recentImports,
                    modifier = Modifier.weight(1.35f),
                )
                RejectionSection(
                    rows = current.recentRejections,
                    modifier = Modifier.weight(1f),
                )
            }
            RunHistorySection(current.recentRuns)
        }
    }
}

@Composable
private fun DashboardSection(dashboard: ImportDashboard) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MetricCard("取込済", dashboard.totalImported.toString(), Modifier.weight(1f))
        MetricCard("隔離", dashboard.totalRejected.toString(), Modifier.weight(1f))
        MetricCard("店舗数", dashboard.distinctStores.toString(), Modifier.weight(1f))
        MetricCard(
            "最終取込",
            dashboard.latestImportedAt?.let(::formatDateTime) ?: "未取込",
            Modifier.weight(1.6f),
        )
    }
}

@Composable
private fun SalesReportSection(
    options: SalesReportFilterOptions,
    filter: SalesReportFilter,
    report: SalesReport,
    onFilterChanged: (SalesReportFilter) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("売上集計", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterMenu(
                    label = "営業日",
                    selected = filter.businessDate,
                    values = listOf(null) + options.businessDates,
                    allLabel = "全営業日",
                    onSelected = { onFilterChanged(filter.copy(businessDate = it)) },
                )
                FilterMenu(
                    label = "店舗",
                    selected = filter.storeId,
                    values = listOf(null) + options.storeIds,
                    allLabel = "全店舗",
                    onSelected = { onFilterChanged(filter.copy(storeId = it)) },
                )
                FilterMenu(
                    label = "端末",
                    selected = filter.terminalId,
                    values = listOf(null) + options.terminalIds,
                    allLabel = "全端末",
                    onSelected = { onFilterChanged(filter.copy(terminalId = it)) },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MetricCard("純売上", formatAmount(report.netSales), Modifier.weight(1.25f))
                MetricCard("売上総額", formatAmount(report.grossSales), Modifier.weight(1f))
                MetricCard("取消額", formatAmount(report.reversalAmount), Modifier.weight(1f))
                MetricCard("有効取引", report.activeTransactionCount.toString(), Modifier.weight(0.8f))
                MetricCard(
                    "客単価",
                    report.averageTicket?.let(::formatAmount) ?: "算出不可",
                    Modifier.weight(1f),
                )
            }

            ReportWarnings(report)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BreakdownCard(
                    title = "支払方法別",
                    rows = report.paymentBreakdown,
                    complete = report.paymentBreakdownComplete,
                    modifier = Modifier.weight(1f),
                )
                BreakdownCard(
                    title = "税額内訳",
                    rows = report.taxBreakdown,
                    complete = report.taxBreakdownComplete,
                    modifier = Modifier.weight(1f),
                )
            }

            TransactionDetailSection(report.details)
        }
    }
}

@Composable
private fun FilterMenu(
    label: String,
    selected: String?,
    values: List<String?>,
    allLabel: String,
    onSelected: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("$label：${selected ?: allLabel}")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            values.distinct().forEach { value ->
                DropdownMenuItem(
                    text = { Text(value ?: allLabel) },
                    onClick = {
                        expanded = false
                        onSelected(value)
                    },
                )
            }
        }
    }
}

@Composable
private fun ReportWarnings(report: SalesReport) {
    val warnings = buildList {
        if (!report.totalsComplete) {
            add("金額を取得できないSALE/REVERSALが${report.missingAmountCount}件あります。合計は参考値です。")
        }
        if (report.unmatchedReversalCount > 0) {
            add("元売上に結び付かない取消が${report.unmatchedReversalCount}件あります。客単価は算出しません。")
        }
        if (report.ignoredEventCount > 0) {
            add("点検・精算・入出金など${report.ignoredEventCount}件は売上集計から除外しています。")
        }
    }
    if (warnings.isNotEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun BreakdownCard(
    title: String,
    rows: List<SalesAmountBreakdown>,
    complete: Boolean,
    modifier: Modifier,
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            if (rows.isEmpty()) {
                Text("内訳データなし", style = MaterialTheme.typography.bodySmall)
            } else {
                rows.forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(row.label, modifier = Modifier.weight(1f))
                        Text(formatAmount(row.amount), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (!complete) {
                Text(
                    "現行JSONに内訳がない取引を含むため、表示分だけを集計しています。",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun TransactionDetailSection(details: List<SalesReportDetail>) {
    var expandedKey by remember(details) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("取引詳細", fontWeight = FontWeight.Bold)
        if (details.isEmpty()) {
            Text("対象データはありません")
        }
        details.take(50).forEachIndexed { index, detail ->
            if (index > 0) HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${formatDateTime(detail.occurredAt)}  ${eventDisplayName(detail.eventType)}",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${detail.businessDate} / ${detail.storeId} / ${detail.terminalId} / #${detail.aggregateId}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    detail.signedAmount?.let(::formatAmount)
                        ?: if (detail.eventType == SalesReportCalculator.EVENT_SALE || detail.eventType == SalesReportCalculator.EVENT_REVERSAL) {
                            "金額なし"
                        } else {
                            "集計対象外"
                        },
                    fontWeight = FontWeight.Bold,
                )
                TextButton(
                    onClick = {
                        expandedKey = if (expandedKey == detail.duplicateImportKey) null else detail.duplicateImportKey
                    },
                ) {
                    Text(if (expandedKey == detail.duplicateImportKey) "閉じる" else "詳細")
                }
            }
            if (expandedKey == detail.duplicateImportKey) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("schema：${detail.payloadSchema}")
                        Text("取込元：${detail.sourceName}")
                        detail.originalSaleId?.let { Text("元売上ID：$it") }
                        Text(
                            text = detail.payloadJson.take(1_200),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (detail.payloadJson.length > 1_200) {
                            Text("※ payloadは先頭1,200文字まで表示")
                        }
                    }
                }
            }
        }
        if (details.size > 50) {
            Text("※ 画面には新しい順で50件まで表示しています。", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
    modifier: Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun LastBatchSection(batch: ImportBatchResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text("直前取込 #${batch.runId}", fontWeight = FontWeight.Bold)
            Text("対象 ${batch.sourceCount}")
            Text("新規 ${batch.importedCount}")
            Text("重複 ${batch.duplicateCount}")
            Text("隔離 ${batch.rejectedCount}")
            Text(formatDateTime(batch.completedAt))
        }
    }
}

@Composable
private fun EventCountSection(counts: Map<String, Int>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("イベント別取込件数", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (counts.isEmpty()) {
                Text("取込データはありません")
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    counts.forEach { (eventType, count) ->
                        Text("$eventType：$count")
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentImportsSection(
    rows: List<ImportedJournalSummary>,
    modifier: Modifier,
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("最近の取込", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (rows.isEmpty()) {
                Text("取込データはありません")
            }
            rows.take(12).forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 7.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${row.businessDate}  ${row.eventType}",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "${row.storeId} / ${row.terminalId} / ${row.sourceName}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(row.totalAmount?.let(::formatAmount) ?: "金額なし")
                }
            }
        }
    }
}

@Composable
private fun RejectionSection(
    rows: List<ImportRejectionSummary>,
    modifier: Modifier,
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("隔離データ", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (rows.isEmpty()) {
                Text("隔離データはありません")
            }
            rows.take(12).forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 7.dp))
                Text(
                    "${row.rejectionCode} / ${row.sourceName}",
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    row.message,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RunHistorySection(rows: List<ImportRunSummary>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("取込履歴", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (rows.isEmpty()) {
                Text("取込履歴はありません")
            }
            rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 7.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Text("#${row.id}", fontWeight = FontWeight.SemiBold)
                    Text(formatDateTime(row.startedAt))
                    Text("対象 ${row.sourceCount}")
                    Text("新規 ${row.importedCount}")
                    Text("重複 ${row.duplicateCount}")
                    Text("隔離 ${row.rejectedCount}")
                    Text(row.status)
                }
            }
        }
    }
}

private val dateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

private fun formatDateTime(value: Long): String = Instant.ofEpochMilli(value)
    .atZone(ZoneId.systemDefault())
    .format(dateTimeFormatter)

private fun formatAmount(value: Long): String =
    NumberFormat.getCurrencyInstance(Locale.JAPAN).format(value)

private fun eventDisplayName(eventType: String): String = when (eventType) {
    SalesReportCalculator.EVENT_SALE -> "売上"
    SalesReportCalculator.EVENT_REVERSAL -> "取消"
    "INSPECTION" -> "点検"
    "Z_SETTLEMENT" -> "精算"
    "CASH_MOVEMENT" -> "入出金"
    "BUSINESS_OPEN" -> "営業開始"
    "BUSINESS_STATE" -> "営業状態"
    "MENU_REVISION" -> "メニュー改定"
    else -> eventType
}
