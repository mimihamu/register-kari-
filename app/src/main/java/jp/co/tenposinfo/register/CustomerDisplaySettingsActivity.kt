package jp.co.tenposinfo.register

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

class CustomerDisplaySettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CustomerDisplaySettingsScreen(
                    context = this,
                    onClose = ::finish,
                )
            }
        }
    }
}

@Composable
private fun CustomerDisplaySettingsScreen(
    context: Context,
    onClose: () -> Unit,
) {
    val store = remember { CustomerDisplaySettingsStore(context) }
    val initial = remember { store.load() }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var portText by remember { mutableStateOf(initial.port.toString()) }
    var storeName by remember { mutableStateOf(initial.storeName) }
    var token by remember { mutableStateOf(initial.token) }
    var completeSecondsText by remember { mutableStateOf(initial.completeSeconds.toString()) }
    var changeSecondsText by remember { mutableStateOf(initial.changeSeconds.toString()) }
    var displayTheme by remember { mutableStateOf(initial.presentation.theme) }
    var showLogo by remember { mutableStateOf(initial.presentation.showLogo) }
    var logoText by remember { mutableStateOf(initial.presentation.logoText) }
    var textScaleText by remember { mutableStateOf(initial.presentation.textScalePercent.toString()) }
    var maxRowsText by remember { mutableStateOf(initial.presentation.maxVisibleRows.toString()) }
    var rowSpacingText by remember { mutableStateOf(initial.presentation.rowSpacingDp.toString()) }
    var showCancelled by remember { mutableStateOf(initial.presentation.showCancelledItems) }
    var showTax by remember { mutableStateOf(initial.presentation.showTaxSymbol) }
    var standbyMessage by remember { mutableStateOf(initial.presentation.standbyMessage) }
    var showDisplayDetails by remember { mutableStateOf(false) }
    var showConnectionDetails by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pairingState by remember { mutableStateOf(CustomerDisplayPairingState()) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var runtimeStatus by remember { mutableStateOf(CustomerDisplayRuntime.status()) }
    val advertiser = remember {
        CustomerDisplayPairingAdvertiser(context) { state -> pairingState = state }
    }
    val addresses = remember { CustomerDisplaySettingsStore.localIpv4Addresses() }
    val previewPort = portText.toIntOrNull()?.takeIf { it in 1024..65535 } ?: initial.port
    val previewUrl = addresses.firstOrNull()?.let { host ->
        "ws://$host:$previewPort$CUSTOMER_DISPLAY_PATH?token=$token"
    }
    val pairingSecondsRemaining = if (pairingState.status == CustomerDisplayPairingStatus.ACTIVE) {
        ((pairingState.endsAtMillis - nowMillis + 999L) / 1_000L).coerceAtLeast(0L)
    } else {
        0L
    }

    DisposableEffect(advertiser) {
        onDispose { advertiser.stop(null) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            runtimeStatus = CustomerDisplayRuntime.status()
            kotlinx.coroutines.delay(1_000L)
        }
    }

    LaunchedEffect(pairingState.status, pairingState.endsAtMillis) {
        while (
            pairingState.status == CustomerDisplayPairingStatus.ACTIVE &&
            System.currentTimeMillis() < pairingState.endsAtMillis
        ) {
            nowMillis = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000L)
        }
        nowMillis = System.currentTimeMillis()
    }

    fun buildConfig(requestedEnabled: Boolean): CustomerDisplayServerConfig? {
        val port = portText.toIntOrNull()
        val seconds = completeSecondsText.toIntOrNull()
        val changeSeconds = changeSecondsText.toIntOrNull()
        val textScale = textScaleText.toIntOrNull()
        val maxRows = maxRowsText.toIntOrNull()
        val rowSpacing = rowSpacingText.toIntOrNull()
        return when {
            port == null || port !in 1024..65535 -> {
                message = "ポートは1024～65535で入力してください"
                null
            }
            seconds == null || seconds !in 1..30 -> {
                message = "完了表示秒数は1～30で入力してください"
                null
            }
            changeSeconds == null || changeSeconds !in 1..30 -> {
                message = "お釣り表示秒数は1～30で入力してください"
                null
            }
            token.length < 16 -> {
                message = "接続トークンが不正です。再発行してください"
                null
            }
            textScale == null || textScale !in 80..150 -> {
                message = "文字倍率は80～150で入力してください"
                null
            }
            maxRows == null || maxRows !in 3..20 -> {
                message = "表示行数は3～20で入力してください"
                null
            }
            rowSpacing == null || rowSpacing !in 0..24 -> {
                message = "行間は0～24で入力してください"
                null
            }
            else -> CustomerDisplayServerConfig(
                enabled = requestedEnabled,
                port = port,
                path = CUSTOMER_DISPLAY_PATH,
                token = token,
                storeId = initial.storeId,
                registerTerminalId = initial.registerTerminalId,
                storeName = storeName.trim().ifEmpty { CustomerDisplaySettingsStore.DEFAULT_STORE_NAME },
                completeSeconds = seconds,
                changeSeconds = changeSeconds,
                presentation = CustomerDisplayPresentation(
                    theme = displayTheme,
                    showLogo = showLogo,
                    logoText = logoText.trim().take(12),
                    textScalePercent = textScale,
                    maxVisibleRows = maxRows,
                    rowSpacingDp = rowSpacing,
                    showCancelledItems = showCancelled,
                    showTaxSymbol = showTax,
                    standbyMessage = standbyMessage.trim().ifEmpty { "いらっしゃいませ" }.take(80),
                ),
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF4F7FA))
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("顧客表示", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = onClose) { Text("閉じる") }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    !runtimeStatus.enabled -> Color(0xFFF1F3F5)
                    runtimeStatus.running && runtimeStatus.connectedClients > 0 -> Color(0xFFE8F5E9)
                    runtimeStatus.running -> Color(0xFFEAF3FA)
                    else -> Color(0xFFFFEBEE)
                },
            ),
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when {
                            !runtimeStatus.enabled -> "停止中"
                            runtimeStatus.running -> "待受中"
                            else -> "起動エラー"
                        },
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF173F6B),
                    )
                    Text("${runtimeStatus.connectedClients}台接続", fontWeight = FontWeight.Bold)
                }
                runtimeStatus.lastError?.let {
                    Text(it, color = Color(0xFFC62828))
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                    Text("顧客表示", fontWeight = FontWeight.Bold)
                }
                OutlinedTextField(
                    value = storeName,
                    onValueChange = { storeName = it.take(40) },
                    label = { Text("店舗名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                when (pairingState.status) {
                    CustomerDisplayPairingStatus.IDLE -> {
                        Button(
                            onClick = {
                                buildConfig(true)?.let { config ->
                                    enabled = true
                                    store.save(config)
                                    CustomerDisplayRuntime.applySettings(context, config)
                                    runtimeStatus = CustomerDisplayRuntime.status()
                                    advertiser.start(config)
                                    message = null
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("ペアリング受付を開始（2分）") }
                    }
                    CustomerDisplayPairingStatus.STARTING -> {
                        Text("受付準備中…", color = Color(0xFF1565C0))
                    }
                    CustomerDisplayPairingStatus.ACTIVE -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("受付中　残り ${pairingSecondsRemaining}秒", color = Color(0xFF1B5E20), fontWeight = FontWeight.Bold)
                            OutlinedButton(onClick = { advertiser.stop() }) { Text("停止") }
                        }
                    }
                }
                pairingState.message?.takeIf {
                    it.contains("できません") || it.contains("エラー") || it.contains("失敗")
                }?.let {
                    Text(it, color = Color(0xFFC62828))
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("表示デザイン", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CustomerDisplayTheme.entries.forEach { theme ->
                        OutlinedButton(onClick = { displayTheme = theme }) {
                            Text(
                                when (theme) {
                                    CustomerDisplayTheme.NAVY -> if (displayTheme == theme) "● 紺" else "紺"
                                    CustomerDisplayTheme.LIGHT -> if (displayTheme == theme) "● 明るい" else "明るい"
                                    CustomerDisplayTheme.WARM -> if (displayTheme == theme) "● 和風" else "和風"
                                },
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Switch(checked = showLogo, onCheckedChange = { showLogo = it })
                    Text("ロゴ表示")
                }
                if (showLogo) {
                    OutlinedTextField(
                        value = logoText,
                        onValueChange = { logoText = it.take(12) },
                        label = { Text("ロゴ文字") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = standbyMessage,
                    onValueChange = { standbyMessage = it.take(80) },
                    label = { Text("待機メッセージ") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { showDisplayDetails = !showDisplayDetails }) {
                    Text(if (showDisplayDetails) "詳細を閉じる" else "表示の詳細")
                }
                if (showDisplayDetails) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = textScaleText,
                            onValueChange = { textScaleText = it.filter(Char::isDigit).take(3) },
                            label = { Text("文字倍率 %") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = maxRowsText,
                            onValueChange = { maxRowsText = it.filter(Char::isDigit).take(2) },
                            label = { Text("表示行数") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = rowSpacingText,
                            onValueChange = { rowSpacingText = it.filter(Char::isDigit).take(2) },
                            label = { Text("行間 dp") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = changeSecondsText,
                            onValueChange = { changeSecondsText = it.filter(Char::isDigit).take(2) },
                            label = { Text("お釣り (秒)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = completeSecondsText,
                            onValueChange = { completeSecondsText = it.filter(Char::isDigit).take(2) },
                            label = { Text("完了 (秒)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Switch(checked = showCancelled, onCheckedChange = { showCancelled = it })
                        Text("取消商品")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Switch(checked = showTax, onCheckedChange = { showTax = it })
                        Text("税記号")
                    }
                }
            }
        }

        OutlinedButton(onClick = { showConnectionDetails = !showConnectionDetails }) {
            Text(if (showConnectionDetails) "接続の詳細を閉じる" else "接続の詳細")
        }
        if (showConnectionDetails) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                        label = { Text("待受ポート") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (addresses.isEmpty()) {
                        Text("IPを取得できません。Wi-Fi接続を確認してください。", color = Color(0xFFC62828))
                    } else {
                        addresses.forEach { Text(it, color = Color(0xFF173F6B)) }
                        Text("ポート $previewPort", color = Color(0xFF607D8B))
                    }
                    OutlinedTextField(
                        value = token,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("トークン") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = {
                            advertiser.stop(null)
                            val newToken = store.regenerateToken()
                            token = newToken
                            val runningConfig = store.load().copy(token = newToken)
                            store.save(runningConfig)
                            CustomerDisplayRuntime.applySettings(context, runningConfig)
                            runtimeStatus = CustomerDisplayRuntime.status()
                            message = "旧端末の接続を切断しました。CD側で再設定してください。"
                        }) { Text("トークン再発行") }
                        if (previewUrl != null) {
                            OutlinedButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("つぐレジ CD 接続先", previewUrl))
                                message = "URLをコピーしました"
                            }) { Text("URLコピー") }
                        }
                    }
                }
            }
        }

        message?.let {
            Text(it, color = if (it.startsWith("保存") || it.contains("コピー"))
                Color(0xFF1B5E20) else Color(0xFFC62828), fontWeight = FontWeight.Bold)
        }
        Button(
            onClick = {
                buildConfig(enabled)?.let { config ->
                    store.save(config)
                    CustomerDisplayRuntime.applySettings(context, config)
                    runtimeStatus = CustomerDisplayRuntime.status()
                    message = if (enabled) "保存しました" else "停止しました"
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("保存") }
    }
}

private fun CustomerDisplayMode.displayLabel(): String = when (this) {
    CustomerDisplayMode.STANDBY -> "待機"
    CustomerDisplayMode.SALES -> "販売中"
    CustomerDisplayMode.SUBTOTAL -> "小計"
    CustomerDisplayMode.ACCOUNTING -> "会計中"
    CustomerDisplayMode.COMPLETE -> "会計完了"
    CustomerDisplayMode.DISCONNECTED -> "切断"
}
