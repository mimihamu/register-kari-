package jp.co.tenposinfo.register.plus

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ManagementSection(
    val label: String,
    val shortLabel: String,
) {
    SALES("売上", "売"),
    TRANSACTIONS("取引", "取"),
    IMPORTS("取込", "込"),
}

object ManagementMobileUiPolicy {
    val defaultSection: ManagementSection = ManagementSection.SALES
    const val TRANSACTION_VISIBLE_LIMIT = 50
    const val PAYLOAD_PREVIEW_CHARACTERS = 1_200
}

@Composable
fun TsuguRegiPlusMobileScreen(
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
    var sectionName by rememberSaveable {
        mutableStateOf(ManagementMobileUiPolicy.defaultSection.name)
    }
    val selectedSection = ManagementSection.entries
        .firstOrNull { it.name == sectionName }
        ?: ManagementMobileUiPolicy.defaultSection

    Scaffold(
        topBar = {
            MobileHeader(
                importing = current.importing,
                loading = current.loading,
                message = current.message,
                onImport = {
                    launcher.launch(
                        arrayOf(
                            "application/json",
                            "text/json",
                            "application/octet-stream",
                        ),
                    )
                },
                onRefresh = onRefresh,
            )
        },
        bottomBar = {
            NavigationBar {
                ManagementSection.entries.forEach { section ->
                    NavigationBarItem(
                        selected = section == selectedSection,
                        onClick = { sectionName = section.name },
                        icon = {
                            Text(
                                text = section.shortLabel,
                                fontWeight = FontWeight.Bold,
                            )
                        },
                        label = { Text(section.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                .padding(innerPadding),
        ) {
            if (current.loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                when (selectedSection) {
                    ManagementSection.SALES -> SalesOverviewScreen(
                        options = current.reportFilterOptions,
                        filter = current.reportFilter,
                        report = current.salesReport,
                        onFilterChanged = onReportFilterChanged,
                    )

                    ManagementSection.TRANSACTIONS -> TransactionsScreen(
                        details = current.salesReport.details,
                    )

                    ManagementSection.IMPORTS -> ImportOperationsScreen(
                        dashboard = current.dashboard,
                        lastBatch = current.lastBatch,
                        recentImports = current.recentImports,
                        recentRejections = current.recentRejections,
                        recentRuns = current.recentRuns,
                    )
                }
            }
        }
    }
}

@Composable
private fun MobileHeader(
    importing: Boolean,
    loading: Boolean,
    message: String?,
    onImport: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column {
            Text(
                text = "つぐレジ＋",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "スマホ縦画面で売上確認・取引確認・JSON取込",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onImport,
                enabled = !importing,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (importing) "取込中…" else "JSON取込")
            }
            OutlinedButton(
                onClick = onRefresh,
                enabled = !loading && !importing,
                modifier = Modifier.weight(1f),
            ) {
                Text("更新")
            }
        }
        if (!message.isNullOrBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Text(
                    text = message,
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun SalesOverviewScreen(
    options: SalesReportFilterOptions,
    filter: SalesReportFilter,
    report: SalesReport,
    onFilterChanged: (SalesReportFilter) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionTitle(
                title = "売上概要",
                description = "営業日・店舗・端末を絞って純売上を確認します",
            )
        }
        item {
            FilterCard(
                options = options,
                filter = filter,
                onFilterChanged = onFilterChanged,
            )
        }
        item { NetSalesHero(report) }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CompactMetricCard(
                    label = "売上総額",
                    value = formatAmount(report.grossSales),
                    modifier = Modifier.weight(1f),
                )
                CompactMetricCard(
                    label = "取消額",
                    value = formatAmount(report.reversalAmount),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CompactMetricCard(
                    label = "有効取引",
                    value = "${report.activeTransactionCount}件",
                    modifier = Modifier.weight(1f),
                )
                CompactMetricCard(
                    label = "客単価",
                    value = report.averageTicket?.let(::formatAmount) ?: "算出不可",
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item { ReportWarnings(report) }
        item {
            BreakdownCard(
                title = "支払方法別",
                rows = report.paymentBreakdown,
                complete = report.paymentBreakdownComplete,
            )
        }
        item {
            BreakdownCard(
                title = "税額内訳",
                rows = report.taxBreakdown,
                complete = report.taxBreakdownComplete,
            )
        }
        item { SalesDataQualityCard(report) }
    }
}

@Composable
private fun FilterCard(
    options: SalesReportFilterOptions,
    filter: SalesReportFilter,
    onFilterChanged: (SalesReportFilter) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("集計条件", fontWeight = FontWeight.Bold)
            MobileFilterMenu(
                label = "営業日",
                selected = filter.businessDate,
                values = listOf(null) + options.businessDates,
                allLabel = "全営業日",
                onSelected = { onFilterChanged(filter.copy(businessDate = it)) },
            )
            MobileFilterMenu(
                label = "店舗",
                selected = filter.storeId,
                values = listOf(null) + options.storeIds,
                allLabel = "全店舗",
                onSelected = { onFilterChanged(filter.copy(storeId = it)) },
            )
            MobileFilterMenu(
                label = "端末",
                selected = filter.terminalId,
                values = listOf(null) + options.terminalIds,
                allLabel = "全端末",
                onSelected = { onFilterChanged(filter.copy(terminalId = it)) },
            )
        }
    }
}

@Composable
private fun MobileFilterMenu(
    label: String,
    selected: String?,
    values: List<String?>,
    allLabel: String,
    onSelected: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "$label：${selected ?: allLabel}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
private fun NetSalesHero(report: SalesReport) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("純売上", style = MaterialTheme.typography.labelLarge)
            Text(
                text = formatAmount(report.netSales),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "SALE ${report.saleCount}件 − REVERSAL ${report.reversalCount}件",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CompactMetricCard(
    label: String,
    value: String,
    modifier: Modifier,
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ReportWarnings(report: SalesReport) {
    val warnings = buildList {
        if (!report.totalsComplete) {
            add("金額を取得できない売上・取消が${report.missingAmountCount}件あります。合計は参考値です。")
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
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("確認事項", fontWeight = FontWeight.Bold)
                warnings.forEach { warning ->
                    Text("・$warning", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun BreakdownCard(
    title: String,
    rows: List<SalesAmountBreakdown>,
    complete: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, fontWeight = FontWeight.Bold)
            if (rows.isEmpty()) {
                Text("内訳データなし", style = MaterialTheme.typography.bodySmall)
            } else {
                rows.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider()
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(row.label, modifier = Modifier.weight(1f))
                        Text(formatAmount(row.amount), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (!complete) {
                Text(
                    text = "現行JSONに内訳がない取引を含むため、取得できた分だけを表示しています。",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun SalesDataQualityCard(report: SalesReport) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("集計データの状態", fontWeight = FontWeight.Bold)
            KeyValueRow("売上イベント", "${report.saleCount}件")
            KeyValueRow("取消イベント", "${report.reversalCount}件")
            KeyValueRow("金額不足", "${report.missingAmountCount}件")
            KeyValueRow("照合不能取消", "${report.unmatchedReversalCount}件")
            KeyValueRow("集計対象外", "${report.ignoredEventCount}件")
        }
    }
}

@Composable
private fun TransactionsScreen(details: List<SalesReportDetail>) {
    var expandedKey by remember(details) { mutableStateOf<String?>(null) }
    val visibleDetails = details.take(ManagementMobileUiPolicy.TRANSACTION_VISIBLE_LIMIT)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SectionTitle(
                title = "取引確認",
                description = "現在の売上集計条件に該当するイベントを新しい順で表示します",
            )
        }
        if (visibleDetails.isEmpty()) {
            item { EmptyCard("対象データはありません") }
        } else {
            items(
                items = visibleDetails,
                key = { it.duplicateImportKey },
            ) { detail ->
                TransactionCard(
                    detail = detail,
                    expanded = expandedKey == detail.duplicateImportKey,
                    onToggle = {
                        expandedKey = if (expandedKey == detail.duplicateImportKey) {
                            null
                        } else {
                            detail.duplicateImportKey
                        }
                    },
                )
            }
        }
        if (details.size > ManagementMobileUiPolicy.TRANSACTION_VISIBLE_LIMIT) {
            item {
                Text(
                    text = "※ 新しい順で${ManagementMobileUiPolicy.TRANSACTION_VISIBLE_LIMIT}件まで表示しています。",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun TransactionCard(
    detail: SalesReportDetail,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = eventDisplayName(detail.eventType),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = formatDateTime(detail.occurredAt),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = detail.signedAmount?.let(::formatAmount)
                        ?: if (
                            detail.eventType == SalesReportCalculator.EVENT_SALE ||
                            detail.eventType == SalesReportCalculator.EVENT_REVERSAL
                        ) {
                            "金額なし"
                        } else {
                            "集計対象外"
                        },
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = "営業日 ${detail.businessDate}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "${detail.storeId} / ${detail.terminalId}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "取引ID #${detail.aggregateId}",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick = onToggle,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(if (expanded) "詳細を閉じる" else "詳細を見る")
            }
            if (expanded) {
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("schema：${detail.payloadSchema}", style = MaterialTheme.typography.bodySmall)
                    Text("取込元：${detail.sourceName}", style = MaterialTheme.typography.bodySmall)
                    detail.originalSaleId?.let {
                        Text("元売上ID：$it", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        text = detail.payloadJson.take(ManagementMobileUiPolicy.PAYLOAD_PREVIEW_CHARACTERS),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (detail.payloadJson.length > ManagementMobileUiPolicy.PAYLOAD_PREVIEW_CHARACTERS) {
                        Text(
                            text = "※ payloadは先頭${ManagementMobileUiPolicy.PAYLOAD_PREVIEW_CHARACTERS}文字まで表示",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportOperationsScreen(
    dashboard: ImportDashboard,
    lastBatch: ImportBatchResult?,
    recentImports: List<ImportedJournalSummary>,
    recentRejections: List<ImportRejectionSummary>,
    recentRuns: List<ImportRunSummary>,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionTitle(
                title = "取込管理",
                description = "JSON取込状況、重複、隔離データを確認します",
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CompactMetricCard("取込済", dashboard.totalImported.toString(), Modifier.weight(1f))
                CompactMetricCard("隔離", dashboard.totalRejected.toString(), Modifier.weight(1f))
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CompactMetricCard("店舗数", dashboard.distinctStores.toString(), Modifier.weight(1f))
                CompactMetricCard(
                    "最終取込",
                    dashboard.latestImportedAt?.let(::formatDateTimeShort) ?: "未取込",
                    Modifier.weight(1f),
                )
            }
        }
        if (lastBatch != null) {
            item { LastBatchCard(lastBatch) }
        }
        item { EventCountCard(dashboard.eventTypeCounts) }
        item { RecentImportsCard(recentImports) }
        item { RejectionCard(recentRejections) }
        item { RunHistoryCard(recentRuns) }
    }
}

@Composable
private fun LastBatchCard(batch: ImportBatchResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("直前取込 #${batch.runId}", fontWeight = FontWeight.Bold)
            KeyValueRow("対象", "${batch.sourceCount}件")
            KeyValueRow("新規", "${batch.importedCount}件")
            KeyValueRow("重複", "${batch.duplicateCount}件")
            KeyValueRow("隔離", "${batch.rejectedCount}件")
            Text(formatDateTime(batch.completedAt), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun EventCountCard(counts: Map<String, Int>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("イベント別取込件数", fontWeight = FontWeight.Bold)
            if (counts.isEmpty()) {
                Text("取込データはありません")
            } else {
                counts.forEach { (eventType, count) ->
                    KeyValueRow(eventDisplayName(eventType), "${count}件")
                }
            }
        }
    }
}

@Composable
private fun RecentImportsCard(rows: List<ImportedJournalSummary>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("最近の取込", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (rows.isEmpty()) {
                Text("取込データはありません")
            }
            rows.take(12).forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${row.businessDate}  ${eventDisplayName(row.eventType)}",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "${row.storeId} / ${row.terminalId}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = row.sourceName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(row.totalAmount?.let(::formatAmount) ?: "金額なし")
                }
            }
        }
    }
}

@Composable
private fun RejectionCard(rows: List<ImportRejectionSummary>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("隔離データ", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (rows.isEmpty()) {
                Text("隔離データはありません")
            }
            rows.take(12).forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = row.rejectionCode,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = row.sourceName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = row.message,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun RunHistoryCard(rows: List<ImportRunSummary>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("取込履歴", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (rows.isEmpty()) {
                Text("取込履歴はありません")
            }
            rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = "#${row.id}  ${formatDateTime(row.startedAt)}",
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "対象${row.sourceCount} / 新規${row.importedCount} / 重複${row.duplicateCount} / 隔離${row.rejectedCount}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(row.status, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun SectionTitle(
    title: String,
    description: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(description, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun KeyValueRow(
    label: String,
    value: String,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EmptyCard(message: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = message,
            modifier = Modifier.padding(20.dp),
        )
    }
}

private val dateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

private val dateTimeShortFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM/dd HH:mm")

private fun formatDateTime(value: Long): String = Instant.ofEpochMilli(value)
    .atZone(ZoneId.systemDefault())
    .format(dateTimeFormatter)

private fun formatDateTimeShort(value: Long): String = Instant.ofEpochMilli(value)
    .atZone(ZoneId.systemDefault())
    .format(dateTimeShortFormatter)

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
