package jp.co.tenposinfo.register

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val OopNavy = Color(0xFF173F6B)
private val OopBlue = Color(0xFF1976B9)
private val OopGreen = Color(0xFF2E7D32)
private val OopOrange = Color(0xFFEF6C00)
private val OopRed = Color(0xFFC62828)
private val OopGray = Color(0xFF546E7A)
private val OopLightBlue = Color(0xFFEAF4FB)
private val OopLightRed = Color(0xFFFFEBEE)

@Composable
internal fun OutboxDeliveryOperationsPanel(
    treeUriText: String?,
    destinationPermission: Boolean,
    onChanged: (String) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val store = remember { OutboxDeliveryOperationsStore(context) }
    var dashboard by remember { mutableStateOf(runCatching(store::dashboard).getOrDefault(OutboxDeliveryDashboard())) }
    var items by remember { mutableStateOf(runCatching { store.recentItems() }.getOrDefault(emptyList())) }
    var audits by remember { mutableStateOf(runCatching { store.recentAudit() }.getOrDefault(emptyList())) }
    var preview by remember { mutableStateOf<OutboxDeliveryJsonPreview?>(null) }
    var operationMessage by remember { mutableStateOf<String?>(null) }

    fun refresh(message: String? = null) {
        dashboard = runCatching(store::dashboard).getOrDefault(OutboxDeliveryDashboard())
        items = runCatching { store.recentItems() }.getOrDefault(emptyList())
        audits = runCatching { store.recentAudit() }.getOrDefault(emptyList())
        if (message != null) operationMessage = message
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("送信運用ダッシュボード", color = OopNavy, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "状態別件数、個別再試行、端末内JSON、送信先テスト、監査履歴を確認します。",
                        color = Color.DarkGray,
                        fontSize = 13.sp,
                    )
                }
                OutlinedButton(onClick = { refresh("運用状態を更新しました。") }) {
                    Text("一覧更新")
                }
                Button(
                    onClick = {
                        val result = store.testDestination(treeUriText)
                        refresh(if (result.success) "送信先テスト成功：${result.message}" else "送信先テスト失敗：${result.message}")
                        onChanged(if (result.success) "送信先テストに成功しました。" else "送信先テストに失敗しました。")
                    },
                    enabled = treeUriText != null && destinationPermission,
                    colors = ButtonDefaults.buttonColors(containerColor = OopBlue),
                ) { Text("送信先テスト") }
            }

            OutboxStatusMetrics(dashboard.counts)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OperationInformationCard(
                    title = "最終送信成功",
                    primary = dashboard.lastSuccessAt?.let(::formatOutboxOperationTime) ?: "未送信",
                    secondary = dashboard.lastSuccessDetail ?: "送信成功または重複確認の監査記録はありません。",
                    color = OopGreen,
                    modifier = Modifier.weight(1f),
                )
                OperationInformationCard(
                    title = "直近エラー",
                    primary = dashboard.latestErrorAt?.let(::formatOutboxOperationTime) ?: "エラーなし",
                    secondary = dashboard.latestError ?: "現在記録されている送信エラーはありません。",
                    color = if (dashboard.latestError == null) OopGreen else OopRed,
                    modifier = Modifier.weight(1f),
                )
            }

            operationMessage?.let {
                Text(
                    it,
                    color = if (it.contains("失敗") || it.contains("できません")) OopRed else OopGreen,
                    fontWeight = FontWeight.Bold,
                )
            }

            HorizontalDivider()
            Text("直近の送信データ", color = OopNavy, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (items.isEmpty()) {
                Text("送信対象はまだありません。", color = OopGray)
            } else {
                items.take(20).forEach { item ->
                    OutboxOperationItemCard(
                        item = item,
                        onPreview = {
                            preview = runCatching { store.preview(item.id) }
                                .onFailure { operationMessage = "JSON表示失敗：${it.message}" }
                                .getOrNull()
                        },
                        onRetry = {
                            val result = runCatching { store.retryItem(item.id) }
                            if (result.isSuccess) {
                                DriveOutboxScheduler.enqueueNow(context)
                                val target = result.getOrThrow()
                                val text = "Outbox No.${item.id}を${target.name}へ戻し、送信処理を要求しました。"
                                refresh(text)
                                onChanged(text)
                            } else {
                                operationMessage = "個別再試行失敗：${result.exceptionOrNull()?.message}"
                            }
                        },
                    )
                }
            }

            preview?.let { value ->
                JsonPreviewCard(value, onClose = { preview = null })
            }

            HorizontalDivider()
            Text("外部送信の監査履歴", color = OopNavy, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (audits.isEmpty()) {
                Text("外部送信の監査記録はまだありません。", color = OopGray)
            } else {
                audits.take(20).forEach { audit ->
                    Column(
                        Modifier.fillMaxWidth()
                            .background(if (audit.eventType.contains("FAILED")) OopLightRed else OopLightBlue)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(audit.eventType, color = OopNavy, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(formatOutboxOperationTime(audit.createdAt), color = OopGray, fontSize = 12.sp)
                        }
                        Text(audit.detail, color = Color.DarkGray, fontSize = 12.sp)
                        Text("担当：${audit.operatorName} / 参照No.${audit.referenceId}", color = OopGray, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun OutboxStatusMetrics(counts: OutboxDeliveryDashboardCounts) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 980.dp
        val metrics = listOf(
            Triple("生成待ち", counts.pending, OopGray),
            Triple("生成中", counts.processing, OopOrange),
            Triple("生成再試行", counts.retry, OopOrange),
            Triple("外部送信待ち", counts.staged, OopBlue),
            Triple("送信済み", counts.sent, OopGreen),
            Triple("要手動対応", counts.failed, OopRed),
        )
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                metrics.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { metric ->
                            OutboxStatusMetricCard(metric.first, metric.second, metric.third, Modifier.weight(1f))
                        }
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                metrics.forEach { metric ->
                    OutboxStatusMetricCard(metric.first, metric.second, metric.third, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun OutboxStatusMetricCard(label: String, value: Int, color: Color, modifier: Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f))) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp)) {
            Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("${value}件", color = color, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun OperationInformationCard(
    title: String,
    primary: String,
    secondary: String,
    color: Color,
    modifier: Modifier,
) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f))) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = color, fontWeight = FontWeight.Bold)
            Text(primary, color = color, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(secondary, color = Color.DarkGray, fontSize = 12.sp, maxLines = 3)
        }
    }
}

@Composable
private fun OutboxOperationItemCard(
    item: OutboxDeliveryOperationItem,
    onPreview: () -> Unit,
    onRetry: () -> Unit,
) {
    val statusColor = when (item.status) {
        SyncOutboxStatus.SENT -> OopGreen
        SyncOutboxStatus.FAILED -> OopRed
        SyncOutboxStatus.PROCESSING,
        SyncOutboxStatus.RETRY -> OopOrange
        SyncOutboxStatus.STAGED -> OopBlue
        SyncOutboxStatus.PENDING -> OopGray
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (item.status == SyncOutboxStatus.FAILED) OopLightRed else Color(0xFFF8FAFC),
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("No.${item.id}", color = OopNavy, fontWeight = FontWeight.Bold)
                Text(item.status.name, color = statusColor, fontWeight = FontWeight.Bold)
                Text("${item.eventType} / 営業日 ${item.businessDate}", color = Color.DarkGray, modifier = Modifier.weight(1f))
                Text(formatOutboxOperationTime(item.updatedAt), color = OopGray, fontSize = 12.sp)
            }
            Text(item.objectKey, color = OopNavy, fontSize = 13.sp)
            Text(
                "eventId=${item.eventId} / aggregate=${item.aggregateId} / 試行 ${item.attemptCount}回",
                color = OopGray,
                fontSize = 11.sp,
            )
            item.lastError?.let { Text("エラー：$it", color = OopRed, fontSize = 12.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onPreview, enabled = item.hasLocalJson) {
                    Text(if (item.hasLocalJson) "JSON表示" else "JSON未生成")
                }
                if (OutboxItemRetryPolicy.canRetry(item.status)) {
                    Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = OopOrange)) {
                        Text("この1件を再試行")
                    }
                }
            }
        }
    }
}

@Composable
private fun JsonPreviewCard(value: OutboxDeliveryJsonPreview, onClose: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF102333)),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("端末内JSONプレビュー", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text(value.objectKey, color = Color(0xFFB8C7D4), fontSize = 12.sp)
                    Text(
                        "${value.byteSize} bytes / SHA-256 ${value.sha256}${if (value.truncated) " / 先頭64KiBのみ表示" else ""}",
                        color = Color(0xFF90CAF9),
                        fontSize = 11.sp,
                    )
                }
                OutlinedButton(onClick = onClose) { Text("閉じる", color = Color.White) }
            }
            Text(
                value.text,
                color = Color(0xFFE8F2F7),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

private fun formatOutboxOperationTime(value: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date(value))
