package jp.co.tenposinfo.register.cd

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
    Column(modifier = Modifier.fillMaxSize()) {
        CustomerDisplayHeader(
            storeName = state.snapshot.storeName,
            connected = state.connected,
            statusMessage = state.statusMessage,
            connectionLabel = connectionLabel,
            onSettings = onSettings,
        )
        Box(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            if (!state.connected) {
                DisconnectedScreen(state.lastError)
            } else {
                when (state.snapshot.mode) {
                    CustomerDisplayMode.STANDBY -> StandbyScreen(state.snapshot)
                    CustomerDisplayMode.SALES -> SalesScreen(state.snapshot)
                    CustomerDisplayMode.ACCOUNTING -> AccountingScreen(state.snapshot)
                    CustomerDisplayMode.COMPLETE -> CompleteScreen(state.snapshot)
                    CustomerDisplayMode.DISCONNECTED -> DisconnectedScreen(state.snapshot.message)
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
    onSettings: () -> Unit,
) {
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

@Composable
private fun StandbyScreen(snapshot: CustomerDisplaySnapshot) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(snapshot.storeName, color = Accent, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            Text(snapshot.message ?: "いらっしゃいませ", color = TextPrimary, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text("商品を登録するとこちらに表示されます", color = TextSecondary, fontSize = 18.sp)
        }
    }
}

@Composable
private fun SalesScreen(snapshot: CustomerDisplaySnapshot) {
    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Card(
            modifier = Modifier.weight(1.45f).fillMaxHeight(),
            colors = CardDefaults.cardColors(containerColor = Panel),
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(18.dp)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("商品", color = TextSecondary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("数量", color = TextSecondary, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(0.25f))
                    Text("金額", color = TextSecondary, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(0.45f))
                }
                HorizontalDivider(color = Border, modifier = Modifier.padding(vertical = 8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(snapshot.orderItems, key = { "${it.productId}-${it.name}" }) { item ->
                        CustomerDisplayItemRow(item)
                    }
                }
            }
        }

        Card(
            modifier = Modifier.weight(0.75f).fillMaxHeight(),
            colors = CardDefaults.cardColors(containerColor = PanelLight),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("お買い上げ", color = TextSecondary, fontSize = 20.sp)
                    Text("${snapshot.numberOfProducts} 点", color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
                    Text("合計", color = TextSecondary, fontSize = 24.sp)
                    Text(yen(snapshot.totalAmount), color = TextPrimary, fontSize = 52.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun CustomerDisplayItemRow(item: CustomerDisplayOrderItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (item.latest) Color(0xFF244F6D) else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .border(
                width = if (item.latest) 2.dp else 0.dp,
                color = if (item.latest) Accent else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.name,
                color = if (item.cancelled) TextSecondary else TextPrimary,
                fontSize = 22.sp,
                fontWeight = if (item.latest) FontWeight.Bold else FontWeight.Medium,
            )
            Text("${yen(item.unitPrice)} × ${item.quantity}", color = TextSecondary, fontSize = 14.sp)
        }
        Text("${item.quantity}", color = TextPrimary, fontSize = 22.sp, textAlign = TextAlign.End, modifier = Modifier.weight(0.25f))
        Text(yen(item.amount), color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(0.45f))
    }
}

@Composable
private fun AccountingScreen(snapshot: CustomerDisplaySnapshot) {
    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        SalesSummaryCard(snapshot, Modifier.weight(1f).fillMaxHeight())
        Card(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            colors = CardDefaults.cardColors(containerColor = PanelLight),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                AmountLine("合計", snapshot.totalAmount, 30)
                Text(snapshot.paymentMethod ?: "お支払い", color = TextSecondary, fontSize = 22.sp)
                AmountLine("お預り", snapshot.receivedAmount, 30)
                if (snapshot.shortageAmount > 0L) {
                    AmountLine("不足", snapshot.shortageAmount, 34, Warning)
                } else {
                    AmountLine("お釣り", snapshot.changeAmount, 38, Success)
                }
            }
        }
    }
}

@Composable
private fun SalesSummaryCard(snapshot: CustomerDisplaySnapshot, modifier: Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(modifier = Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("ご注文内容", color = TextPrimary, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(snapshot.orderItems) { item -> CustomerDisplayItemRow(item) }
            }
        }
    }
}

@Composable
private fun CompleteScreen(snapshot: CustomerDisplaySnapshot) {
    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Card(
            modifier = Modifier.weight(0.9f).fillMaxHeight(),
            colors = CardDefaults.cardColors(containerColor = Panel),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("お会計完了", color = Success, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(18.dp))
                Text(snapshot.paymentMethod ?: "お支払い", color = TextSecondary, fontSize = 22.sp)
                Text(yen(snapshot.totalAmount), color = TextPrimary, fontSize = 46.sp, fontWeight = FontWeight.Bold)
            }
        }
        Card(
            modifier = Modifier.weight(1.1f).fillMaxHeight(),
            colors = CardDefaults.cardColors(containerColor = PanelLight),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (snapshot.changeAmount > 0L) {
                    Text("お釣り", color = TextSecondary, fontSize = 30.sp)
                    Text(yen(snapshot.changeAmount), color = Success, fontSize = 70.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text("ありがとうございました", color = TextPrimary, fontSize = 42.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
                snapshot.message?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(it, color = TextSecondary, fontSize = 20.sp, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun DisconnectedScreen(reason: String?) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth(0.72f)) {
            Column(
                modifier = Modifier.padding(34.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("レジへ接続できません", color = Warning, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text("接続が戻るまで金額は表示しません", color = TextPrimary, fontSize = 22.sp)
                reason?.let { Text(it, color = TextSecondary, fontSize = 16.sp, textAlign = TextAlign.Center) }
                Text("右上の［設定］でレジIP・ポート・トークンを確認してください", color = TextSecondary, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun AmountLine(label: String, amount: Long, fontSize: Int, color: Color = TextPrimary) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Text(label, color = TextSecondary, fontSize = 22.sp)
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

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF4F7FA)).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("つぐレジ CD 接続設定", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color(0xFF173F6B))
                Text("レジの［設定］→［顧客表示］に表示される内容を入力します。", color = Color(0xFF455A64))
            }
            if (initial.isConfigured) OutlinedButton(onClick = onCancel) { Text("戻る") }
        }

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                Text("通信パス：$CUSTOMER_DISPLAY_PATH", color = Color(0xFF607D8B))
            }
        }

        message?.let { Text(it, color = Color(0xFFC62828), fontWeight = FontWeight.Bold) }

        Button(onClick = {
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
        }) { Text("保存して接続") }
    }
}

private fun yen(value: Long): String = "¥${NumberFormat.getIntegerInstance(Locale.JAPAN).format(value)}"
