package jp.co.tenposinfo.register

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.getValue
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

private val PsNavy = Color(0xFF173F6B)
private val PsBlue = Color(0xFF1976B9)
private val PsBackground = Color(0xFFF4F7FA)
private val PsBorder = Color(0xFFD5DEE7)
private val PsPaleBlue = Color(0xFFEAF3FA)
private val PsPaleGreen = Color(0xFFEAF5EC)
private val PsDanger = Color(0xFFC62828)

class PaymentSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureRegisterSystemBars(window)
        setContent {
            RegisterSafeAreaV141 {
            MaterialTheme {
                PaymentSettingsScreen(
                    actor = intent.getStringExtra(EXTRA_ACTOR).orEmpty().ifBlank { "責任者" },
                    onClose = { finish() },
                )
            }
        
            }
        }
    }

    companion object {
        const val EXTRA_ACTOR = "jp.co.tenposinfo.register.extra.PAYMENT_SETTINGS_ACTOR"
    }
}

@Composable
private fun PaymentSettingsScreen(
    actor: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { PaymentSettingsStoreV136(context.applicationContext) }
    val initial = remember { store.load() }
    var rows by remember { mutableStateOf(initial.methods) }
    var message by remember { mutableStateOf<String?>(null) }
    var expandedMethod by remember { mutableStateOf<PaymentMethod?>(null) }

    fun normalizeOrder(input: List<PaymentMethodSettingV136>): List<PaymentMethodSettingV136> =
        input.mapIndexed { index, row -> row.copy(displayOrder = index * 10) }

    fun move(method: PaymentMethod, delta: Int) {
        val index = rows.indexOfFirst { it.method == method }
        val target = index + delta
        if (index <= 0 || target <= 0 || target !in rows.indices) return
        val copy = rows.toMutableList()
        val tmp = copy[index]
        copy[index] = copy[target]
        copy[target] = tmp
        rows = normalizeOrder(copy)
        message = null
    }

    fun update(method: PaymentMethod, transform: (PaymentMethodSettingV136) -> PaymentMethodSettingV136) {
        rows = rows.map { if (it.method == method) transform(it) else it }
        message = null
    }

    Surface(Modifier.fillMaxSize(), color = PsBackground) {
        Column(Modifier.fillMaxSize()) {
            RegisterTopBarV141(title = "支払設定", onBack = onClose)

            Column(
                Modifier.weight(1f).fillMaxWidth().padding(16.dp),
            ) {
                Card(
                    Modifier.weight(1f).fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, PsBorder),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Text("支払方法・表示順", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = PsNavy)
                        Text("現金は常時有効です。", color = PsNavy)
                        Spacer(Modifier.height(10.dp))
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            items(rows, key = { it.method.name }) { row ->
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (row.enabled) PsPaleBlue else Color(0xFFF7F7F7),
                                    ),
                                    border = BorderStroke(1.dp, PsBorder),
                                ) {
                                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Checkbox(
                                                checked = row.enabled,
                                                onCheckedChange = { checked ->
                                                    if (row.method != PaymentMethod.CASH) {
                                                        update(row.method) { it.copy(enabled = checked) }
                                                    }
                                                },
                                                enabled = row.method != PaymentMethod.CASH,
                                            )
                                            Text(
                                                row.method.displayName,
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.weight(1f),
                                            )
                                            OutlinedButton(
                                                onClick = { move(row.method, -1) },
                                                enabled = row.method != PaymentMethod.CASH && rows.indexOf(row) > 1,
                                            ) { Text("上へ") }
                                            Spacer(Modifier.width(6.dp))
                                            OutlinedButton(
                                                onClick = { move(row.method, 1) },
                                                enabled = row.method != PaymentMethod.CASH && rows.indexOf(row) in 1 until rows.lastIndex,
                                            ) { Text("下へ") }
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                expandedMethod = if (expandedMethod == row.method) null else row.method
                                            },
                                        ) {
                                            Text(if (expandedMethod == row.method) "詳細を閉じる" else "詳細")
                                        }
                                        if (expandedMethod == row.method) {
                                        OutlinedTextField(
                                            value = row.receiptName,
                                            onValueChange = { value ->
                                                update(row.method) {
                                                    it.copy(receiptName = value.take(16))
                                                }
                                            },
                                            label = { Text("レシート表示名（1～16文字）") },
                                            singleLine = true,
                                            enabled = row.enabled,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Checkbox(
                                                checked = row.allowOverpay,
                                                onCheckedChange = { checked ->
                                                    if (row.method != PaymentMethod.CASH) {
                                                        update(row.method) {
                                                            it.copy(
                                                                allowOverpay = checked,
                                                                allowOverpaymentWithChange = checked && it.givesChange,
                                                                givesChange = if (checked) it.givesChange else false,
                                                            )
                                                        }
                                                    }
                                                },
                                                enabled = row.method != PaymentMethod.CASH && row.enabled,
                                            )
                                            Text(
                                                if (row.method == PaymentMethod.CASH) {
                                                    "残額を超える預りを許可（現金は固定）"
                                                } else {
                                                    "残額を超える支払を許可"
                                                },
                                                fontSize = 12.sp,
                                                color = Color.DarkGray,
                                            )
                                        }
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Checkbox(
                                                checked = row.givesChange,
                                                onCheckedChange = { checked ->
                                                    if (row.method != PaymentMethod.CASH) {
                                                        update(row.method) {
                                                            it.copy(
                                                                givesChange = checked,
                                                                allowOverpay = if (checked) true else it.allowOverpay,
                                                                allowOverpaymentWithChange = checked,
                                                            )
                                                        }
                                                    }
                                                },
                                                enabled = row.method != PaymentMethod.CASH && row.enabled && row.allowOverpay,
                                            )
                                            Text(
                                                if (row.method == PaymentMethod.CASH) {
                                                    "超過分をお釣りとして返す（現金は固定）"
                                                } else {
                                                    "超過分を現金の釣銭として返す"
                                                },
                                                fontSize = 12.sp,
                                                color = Color.DarkGray,
                                            )
                                        }
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Checkbox(
                                                checked = row.drawerOpen,
                                                onCheckedChange = { checked ->
                                                    if (row.method == PaymentMethod.CASH) {
                                                        update(row.method) { it.copy(drawerOpen = checked) }
                                                    }
                                                },
                                                enabled = row.method == PaymentMethod.CASH && row.enabled,
                                            )
                                            Text(
                                                if (row.method == PaymentMethod.CASH) {
                                                    "会計確定時にドロアを開く"
                                                } else {
                                                    "ドロア開放なし（現金会計のみ）"
                                                },
                                                fontSize = 12.sp,
                                                color = Color.DarkGray,
                                            )
                                        }
                                        Text(
                                            "決済端末連携：${row.gateway.displayName}",
                                            fontSize = 12.sp,
                                            color = Color.DarkGray,
                                        )

                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "カード・電子マネー・QRは外部端末の決済結果を確認してください",
                            fontSize = 12.sp,
                            color = PsNavy,
                        )
                        Text(
                            "未実装の端末連携を有効として扱いません",
                            fontSize = 12.sp,
                            color = PsDanger,
                        )
                        message?.let {
                            Text(
                                it,
                                color = if (it.startsWith("保存")) PsNavy else PsDanger,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Button(
                        onClick = {
                            runCatching {
                                store.save(
                                    PaymentSettingsV136(rows, PaymentTerminalAdapterV136.NONE),
                                    actor,
                                )
                            }.onSuccess {
                                rows = it.methods
                                message = "保存しました。次に開く会計画面から反映します"
                            }.onFailure {
                                message = it.message ?: "保存できませんでした"
                            }
                        },
                        modifier = Modifier.width(128.dp).height(54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PsBlue),
                    ) {
                        Text("保存", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
