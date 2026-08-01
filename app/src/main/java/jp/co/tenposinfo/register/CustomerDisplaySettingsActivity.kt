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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardOptions
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
    var message by remember { mutableStateOf<String?>(null) }
    val addresses = remember { CustomerDisplaySettingsStore.localIpv4Addresses() }
    val previewPort = portText.toIntOrNull()?.takeIf { it in 1024..65535 } ?: initial.port
    val previewUrl = addresses.firstOrNull()?.let { host ->
        "ws://$host:$previewPort$CUSTOMER_DISPLAY_PATH?token=$token"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF4F7FA))
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("つぐレジ CD 接続設定", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("顧客表示は任意機能です。OFFでも販売・会計は継続します。")
            }
            OutlinedButton(onClick = onClose) { Text("閉じる") }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                    Text(if (enabled) "顧客表示サーバー：ON" else "顧客表示サーバー：OFF", fontWeight = FontWeight.Bold)
                }

                OutlinedTextField(
                    value = storeName,
                    onValueChange = { storeName = it.take(40) },
                    label = { Text("表示する店舗名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                    label = { Text("待受ポート（1024～65535）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = completeSecondsText,
                    onValueChange = { completeSecondsText = it.filter(Char::isDigit).take(2) },
                    label = { Text("お釣り・完了表示秒数（1～30秒）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = token,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("接続トークン") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = {
                        token = store.regenerateToken()
                        message = "接続トークンを再発行しました。表示端末側も再設定してください。"
                    }) { Text("トークン再発行") }
                    if (previewUrl != null) {
                        OutlinedButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("つぐレジ CD 接続先", previewUrl))
                            message = "接続URLをコピーしました"
                        }) { Text("接続URLをコピー") }
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF3FA)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("表示端末へ入力する接続情報", fontWeight = FontWeight.Bold)
                if (addresses.isEmpty()) {
                    Text("端末のIPv4アドレスを取得できません。Wi-Fi接続を確認してください。")
                } else {
                    addresses.forEach { Text("レジIP：$it") }
                    Text("ポート：$previewPort")
                    Text("パス：$CUSTOMER_DISPLAY_PATH")
                    Text("トークン：$token")
                }
                Text("初版は手動接続です。mDNS探索・QRペアリングは後続工程で追加します。")
            }
        }

        message?.let {
            Text(it, color = if (it.startsWith("保存")) Color(0xFF1B5E20) else Color(0xFF9A5B00), fontWeight = FontWeight.Bold)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                val port = portText.toIntOrNull()
                val seconds = completeSecondsText.toIntOrNull()
                when {
                    port == null || port !in 1024..65535 -> message = "ポートは1024～65535で入力してください"
                    seconds == null || seconds !in 1..30 -> message = "完了表示秒数は1～30で入力してください"
                    token.length < 16 -> message = "接続トークンが不正です。再発行してください"
                    else -> {
                        val config = CustomerDisplayServerConfig(
                            enabled = enabled,
                            port = port,
                            path = CUSTOMER_DISPLAY_PATH,
                            token = token,
                            storeName = storeName.trim().ifEmpty { CustomerDisplaySettingsStore.DEFAULT_STORE_NAME },
                            completeSeconds = seconds,
                        )
                        store.save(config)
                        CustomerDisplayRuntime.applySettings(context, config)
                        message = if (enabled) "保存しました。顧客表示サーバーを起動しました。" else "保存しました。顧客表示サーバーを停止しました。"
                    }
                }
            }) { Text("保存") }
            Spacer(Modifier.height(1.dp))
        }
    }
}
