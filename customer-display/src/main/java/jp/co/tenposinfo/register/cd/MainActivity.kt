package jp.co.tenposinfo.register.cd

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.util.Locale

private val Background = Color(0xFF081522)
private val Panel = Color(0xFF10283D)
private val PanelLight = Color(0xFF173B58)
private val Accent = Color(0xFF64B5F6)
private val Success = Color(0xFF81C784)
private val Warning = Color(0xFFFFD54F)
private val TextPrimary = Color(0xFFF8FBFF)
private val TextSecondary = Color(0xFFB8C7D4)
private val Border = Color(0xFF36556E)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                val settingsStore = remember { CustomerDisplayConnectionSettingsStore(this) }
                var settings by remember { mutableStateOf(settingsStore.load()) }
                var uiState by remember { mutableStateOf(CustomerDisplayUiState()) }
                var settingsOpen by remember { mutableStateOf(!settings.isConfigured) }

                DisposableEffect(settings) {
                    val client = if (settings.autoConnect && settings.isConfigured) {
                        CustomerDisplayWebSocketClient(
                            settings = settings,
                            onConnected = {
                                runOnUiThread { uiState = CustomerDisplayStateReducer.connected(uiState) }
                            },
                            onSnapshot = { snapshot ->
                                runOnUiThread { uiState = CustomerDisplayStateReducer.received(uiState, snapshot) }
                            },
                            onDisconnected = { reason ->
                                runOnUiThread { uiState = CustomerDisplayStateReducer.disconnected(uiState, reason) }
                            },
                        ).also { it.start() }
                    } else {
                        uiState = CustomerDisplayStateReducer.disconnected(
                            uiState,
                            if (settings.isConfigured) "自動接続がOFFです" else "接続先を設定してください",
                        )
                        null
                    }
                    onDispose { client?.stop() }
                }

                Surface(modifier = Modifier.fillMaxSize(), color = Background) {
                    if (settingsOpen) {
                        CustomerDisplayConnectionSettingsScreen(
                            initial = settings,
                            onSave = { updated ->
                                settingsStore.save(updated)
                                settings = updated
                                uiState = CustomerDisplayUiState(statusMessage = "接続準備中")
                                settingsOpen = false
                            },
                            onCancel = { if (settings.isConfigured) settingsOpen = false },
                        )
                    } else {
                        CustomerDisplayScreen(
                            state = uiState,
                            connectionLabel = settings.displayAddress,
                            onSettings = { settingsOpen = true },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomerDisplayScreen(
    state: CustomerDisplayUiState,
    connectionLabel: String,
    onSettings: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val layoutMode = CustomerDisplayLayoutPolicy.select(maxWidth.value, maxHeight.value)
        Column(modifier = Modifier.fillMaxSize()) {
            CustomerDisplayHeader(
                storeName = state.snapshot.storeName,
                connected = state.connected,
                statusMessage = state.statusMessage,
                connectionLabel = connectionLabel,
                layoutMode = layoutMode,
                onSettings = onSettings,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(if (layoutMode.compact) 10.dp else 20.dp),
            ) {
                if (!state.connected) {
                    DisconnectedScreen(state.lastError, layoutMode)
                } else {
                    when (state.snapshot.mode) {
                        CustomerDisplayMode.STANDBY -> StandbyScreen(state.snapshot, layoutMode)
                        CustomerDisplayMode.SALES -> SalesScreen(state.snapshot, layoutMode)
                        CustomerDisplayMode.ACCOUNTING -> AccountingScreen(state.snapshot, layoutMode)
                        CustomerDisplayMode.COMPLETE -> CompleteScreen(state.snapshot, layoutMode)
                        CustomerDisplayMode.DISCONNECTED -> DisconnectedScreen(state.snapshot.message, layoutMode)
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomerDisplayHeader(
    storeName: String,
    connected: Boolean,
    statusMessage: String,
    connectionLabel: String,
    layoutMode: CustomerDisplayLayoutMode,
    onSettings: () -> Unit,
) {
    if (layoutMode.compact) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = storeName,
                    color = TextPrimary,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onSettings, modifier = Modifier.height(40.dp)) {
                    Text("設定", color = TextPrimary, fontSize = 13.sp)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (connected) "● $statusMessage" else "○ $statusMessage",
                    color = if (connected) Success else Warning,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = connectionLabel,
                    color = TextSecondary,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = storeName,
                color = TextPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (connected) "● $statusMessage" else "○ $statusMessage",
                    color = if (connected) Success else Warning,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(connectionLabel, color = TextSecondary, fontSize = 11.sp)
            }
            OutlinedButton(onClick = onSettings) {
                Text("設定", color = TextPrimary)
            }
        }
    }
}

@Composable
private fun StandbyScreen(
    snapshot: CustomerDisplaySnapshot,
    layoutMode: CustomerDisplayLayoutMode,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (layoutMode.compact) 12.dp else 18.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                snapshot.storeName,
                color = Accent,
                fontSize = if (layoutMode.compact) 31.sp else 44.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                snapshot.message ?: "いらっしゃいませ",
                color = TextPrimary,
                fontSize = if (layoutMode.compact) 25.sp else 34.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                "商品を登録するとこちらに表示されます",
                color = TextSecondary,
                fontSize = if (layoutMode.compact) 14.sp else 18.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SalesScreen(
    snapshot: CustomerDisplaySnapshot,
    layoutMode: CustomerDisplayLayoutMode,
) {
    if (layoutMode.stacked) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TotalSummaryCard(snapshot, layoutMode.compact, horizontal = true, modifier = Modifier.fillMaxWidth())
            OrderItemsCard(snapshot, layoutMode.compact, Modifier.weight(1f).fillMaxWidth())
        }
    } else {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(if (layoutMode.compact) 10.dp else 18.dp),
        ) {
            OrderItemsCard(snapshot, layoutMode.compact, Modifier.weight(1.45f).fillMaxHeight())
            TotalSummaryCard(snapshot, layoutMode.compact, horizontal = false, modifier = Modifier.weight(0.75f).fillMaxHeight())
        }
    }
}

@Composable
private fun OrderItemsCard(
    snapshot: CustomerDisplaySnapshot,
    compact: Boolean,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    val targetIndex = CustomerDisplayScrollPolicy.targetIndex(snapshot.orderItems)
    LaunchedEffect(snapshot.sequence, targetIndex, snapshot.orderItems.size) {
        if (targetIndex >= 0) listState.animateScrollToItem(targetIndex)
    }
    val listState = rememberLazyListState()
    val targetIndex = CustomerDisplayScrollPolicy.targetIndex(snapshot.orderItems)
    LaunchedEffect(snapshot.sequence, targetIndex, snapshot.orderItems.size) {
        if (targetIndex >= 0) listState.animateScrollToItem(targetIndex)
    }
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(modifier = Modifier.fillMaxSize().padding(if (compact) 10.dp else 18.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("商品", color = TextSecondary, fontSize = if (compact) 12.sp else 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("数量", color = TextSecondary, fontSize = if (compact) 12.sp else 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(0.24f))
                Text("金額", color = TextSecondary, fontSize = if (compact) 12.sp else 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(0.46f))
            }
            HorizontalDivider(color = Border, modifier = Modifier.padding(vertical = if (compact) 5.dp else 8.dp))
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
            ) {
                items(snapshot.orderItems, key = { "${it.productId}-${it.name}" }) { item ->
                    CustomerDisplayItemRow(item, compact)
                }
            }
        }
    }
}

@Composable
private fun TotalSummaryCard(
    snapshot: CustomerDisplaySnapshot,
    compact: Boolean,
    horizontal: Boolean,
    modifier: Modifier,
) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = PanelLight)) {
        if (horizontal) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(if (compact) 12.dp else 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("お買い上げ", color = TextSecondary, fontSize = if (compact) 13.sp else 18.sp)
                    Text("${snapshot.numberOfProducts} 点", color = TextPrimary, fontSize = if (compact) 21.sp else 28.sp, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("合計", color = TextSecondary, fontSize = if (compact) 15.sp else 21.sp)
                    Text(yen(snapshot.totalAmount), color = TextPrimary, fontSize = if (compact) 31.sp else 44.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(if (compact) 14.dp else 22.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("お買い上げ", color = TextSecondary, fontSize = if (compact) 15.sp else 20.sp)
                    Text("${snapshot.numberOfProducts} 点", color = TextPrimary, fontSize = if (compact) 23.sp else 30.sp, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
                    Text("合計", color = TextSecondary, fontSize = if (compact) 18.sp else 24.sp)
                    Text(yen(snapshot.totalAmount), color = TextPrimary, fontSize = if (compact) 34.sp else 52.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun CustomerDisplayItemRow(item: CustomerDisplayOrderItem, compact: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (item.latest) Color(0xFF244F6D) else Color.Transparent,
                shape = RoundedCornerShape(if (compact) 7.dp else 10.dp),
            )
            .border(
                width = if (item.latest) 2.dp else 0.dp,
                color = if (item.latest) Accent else Color.Transparent,
                shape = RoundedCornerShape(if (compact) 7.dp else 10.dp),
            )
            .padding(horizontal = if (compact) 8.dp else 12.dp, vertical = if (compact) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.name,
                color = if (item.cancelled) TextSecondary else TextPrimary,
                fontSize = if (compact) 17.sp else 22.sp,
                fontWeight = if (item.latest) FontWeight.Bold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text("${yen(item.unitPrice)} × ${item.quantity}", color = TextSecondary, fontSize = if (compact) 11.sp else 14.sp)
        }
        Text("${item.quantity}", color = TextPrimary, fontSize = if (compact) 17.sp else 22.sp, textAlign = TextAlign.End, modifier = Modifier.weight(0.24f))
        Text(yen(item.amount), color = TextPrimary, fontSize = if (compact) 18.sp else 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(0.46f))
    }
}

@Composable
private fun AccountingScreen(
    snapshot: CustomerDisplaySnapshot,
    layoutMode: CustomerDisplayLayoutMode,
) {
    if (layoutMode.stacked) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PaymentSummaryCard(snapshot, layoutMode.compact, Modifier.fillMaxWidth())
            OrderItemsCard(snapshot, layoutMode.compact, Modifier.weight(1f).fillMaxWidth())
        }
    } else {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(if (layoutMode.compact) 10.dp else 18.dp),
        ) {
            OrderItemsCard(snapshot, layoutMode.compact, Modifier.weight(1f).fillMaxHeight())
            PaymentSummaryCard(snapshot, layoutMode.compact, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun PaymentSummaryCard(
    snapshot: CustomerDisplaySnapshot,
    compact: Boolean,
    modifier: Modifier,
) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = PanelLight)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(if (compact) 13.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 16.dp),
        ) {
            AmountLine("合計", snapshot.totalAmount, if (compact) 25 else 30, compact = compact)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("お支払い", color = TextSecondary, fontSize = if (compact) 13.sp else 20.sp)
                Text(snapshot.paymentMethod ?: "未選択", color = TextPrimary, fontSize = if (compact) 16.sp else 22.sp, fontWeight = FontWeight.Bold)
            }
            AmountLine("お預り", snapshot.receivedAmount, if (compact) 22 else 30, compact = compact)
            if (snapshot.shortageAmount > 0L) {
                AmountLine("不足", snapshot.shortageAmount, if (compact) 27 else 34, Warning, compact)
            } else {
                AmountLine("お釣り", snapshot.changeAmount, if (compact) 29 else 38, Success, compact)
            }
        }
    }
}

@Composable
private fun CompleteScreen(
    snapshot: CustomerDisplaySnapshot,
    layoutMode: CustomerDisplayLayoutMode,
) {
    if (layoutMode.stacked) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CompleteTotalCard(snapshot, layoutMode.compact, Modifier.weight(0.8f).fillMaxWidth())
            ChangeCard(snapshot, layoutMode.compact, Modifier.weight(1.2f).fillMaxWidth())
        }
    } else {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(if (layoutMode.compact) 10.dp else 18.dp),
        ) {
            CompleteTotalCard(snapshot, layoutMode.compact, Modifier.weight(0.9f).fillMaxHeight())
            ChangeCard(snapshot, layoutMode.compact, Modifier.weight(1.1f).fillMaxHeight())
        }
    }
}

@Composable
private fun CompleteTotalCard(
    snapshot: CustomerDisplaySnapshot,
    compact: Boolean,
    modifier: Modifier,
) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(if (compact) 14.dp else 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("お会計完了", color = Success, fontSize = if (compact) 25.sp else 36.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(if (compact) 7.dp else 18.dp))
            Text(snapshot.paymentMethod ?: "お支払い", color = TextSecondary, fontSize = if (compact) 15.sp else 22.sp)
            Text(yen(snapshot.totalAmount), color = TextPrimary, fontSize = if (compact) 35.sp else 46.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ChangeCard(
    snapshot: CustomerDisplaySnapshot,
    compact: Boolean,
    modifier: Modifier,
) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = PanelLight)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(if (compact) 16.dp else 28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (snapshot.changeAmount > 0L) {
                Text("お釣り", color = TextSecondary, fontSize = if (compact) 21.sp else 30.sp)
                Text(yen(snapshot.changeAmount), color = Success, fontSize = if (compact) 48.sp else 70.sp, fontWeight = FontWeight.Bold)
            } else {
                Text(
                    "ありがとうございました",
                    color = TextPrimary,
                    fontSize = if (compact) 28.sp else 42.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
            snapshot.message?.let {
                Spacer(Modifier.height(if (compact) 7.dp else 16.dp))
                Text(it, color = TextSecondary, fontSize = if (compact) 14.sp else 20.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun DisconnectedScreen(
    reason: String?,
    layoutMode: CustomerDisplayLayoutMode,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Panel),
            modifier = if (layoutMode.compact) Modifier.fillMaxWidth() else Modifier.fillMaxWidth(0.72f),
        ) {
            Column(
                modifier = Modifier.padding(if (layoutMode.compact) 20.dp else 34.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (layoutMode.compact) 9.dp else 14.dp),
            ) {
                Text("レジへ接続できません", color = Warning, fontSize = if (layoutMode.compact) 25.sp else 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text("接続が戻るまで金額は表示しません", color = TextPrimary, fontSize = if (layoutMode.compact) 16.sp else 22.sp, textAlign = TextAlign.Center)
                reason?.let { Text(it, color = TextSecondary, fontSize = if (layoutMode.compact) 12.sp else 16.sp, textAlign = TextAlign.Center) }
                Text("右上の［設定］でレジIP・ポート・トークンを確認してください", color = TextSecondary, fontSize = if (layoutMode.compact) 12.sp else 16.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun AmountLine(
    label: String,
    amount: Long,
    fontSize: Int,
    color: Color = TextPrimary,
    compact: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(label, color = TextSecondary, fontSize = if (compact) 14.sp else 22.sp)
        Text(yen(amount), color = color, fontSize = fontSize.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CustomerDisplayConnectionSettingsScreen(
    initial: CustomerDisplayConnectionSettings,
    onSave: (CustomerDisplayConnectionSettings) -> Unit,
    onCancel: () -> Unit,
) {
    var host by remember(initial) { mutableStateOf(initial.host) }
    var portText by remember(initial) { mutableStateOf(initial.port.toString()) }
    var token by remember(initial) { mutableStateOf(initial.token) }
    var autoConnect by remember(initial) { mutableStateOf(initial.autoConnect) }
    var message by remember { mutableStateOf<String?>(null) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val layoutMode = CustomerDisplayLayoutPolicy.select(maxWidth.value, maxHeight.value)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF4F7FA))
                .verticalScroll(rememberScrollState())
                .padding(if (layoutMode.compact) 16.dp else 28.dp),
            verticalArrangement = Arrangement.spacedBy(if (layoutMode.compact) 11.dp else 16.dp),
        ) {
            if (layoutMode.compact) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "つぐレジ CD 接続設定",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF173F6B),
                            modifier = Modifier.weight(1f),
                        )
                        if (initial.isConfigured) OutlinedButton(onClick = onCancel) { Text("戻る") }
                    }
                    Text("レジの［設定］→［顧客表示］に表示される内容を入力します。", color = Color(0xFF455A64), fontSize = 13.sp)
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("つぐレジ CD 接続設定", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color(0xFF173F6B))
                        Text("レジの［設定］→［顧客表示］に表示される内容を入力します。", color = Color(0xFF455A64))
                    }
                    if (initial.isConfigured) OutlinedButton(onClick = onCancel) { Text("戻る") }
                }
            }

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(
                    modifier = Modifier.padding(if (layoutMode.compact) 14.dp else 22.dp),
                    verticalArrangement = Arrangement.spacedBy(if (layoutMode.compact) 10.dp else 14.dp),
                ) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it.trim().take(255) },
                        label = { Text("レジIPアドレス／ホスト名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                        label = { Text("ポート（初期値 18080）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it.trim().take(128) },
                        label = { Text("接続トークン") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Switch(checked = autoConnect, onCheckedChange = { autoConnect = it })
                        Text("起動時に自動接続")
                    }
                    Text("通信パス：$CUSTOMER_DISPLAY_PATH", color = Color(0xFF607D8B), fontSize = if (layoutMode.compact) 12.sp else 14.sp)
                }
            }

            message?.let { Text(it, color = Color(0xFFC62828), fontWeight = FontWeight.Bold) }

            Button(
                onClick = {
                    val port = portText.toIntOrNull()
                    when {
                        host.isBlank() -> message = "レジIPアドレスを入力してください"
                        port == null || port !in 1024..65535 -> message = "ポートは1024～65535で入力してください"
                        token.length < 16 -> message = "接続トークンを正しく入力してください"
                        else -> onSave(
                            CustomerDisplayConnectionSettings(
                                host = host,
                                port = port,
                                token = token,
                                autoConnect = autoConnect,
                            ),
                        )
                    }
                },
                modifier = if (layoutMode.compact) Modifier.fillMaxWidth() else Modifier,
            ) {
                Text("保存して接続")
            }
        }
    }
}

private fun yen(value: Long): String = "¥${NumberFormat.getIntegerInstance(Locale.JAPAN).format(value)}"
