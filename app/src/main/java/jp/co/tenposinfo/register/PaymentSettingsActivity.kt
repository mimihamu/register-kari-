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
            MaterialTheme {
                PaymentSettingsScreen(
                    actor = intent.getStringExtra(EXTRA_ACTOR).orEmpty().ifBlank { "責任者" },
                    onClose = { finish() },
                )
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
            Row(
                Modifier.fillMaxWidth().height(62.dp).background(PsNavy).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("つぐレジ", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(22.dp))
                Text("SCR-630B  支払設定", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("認証：$actor", color = Color.White, fontSize = 13.sp)
            }

            Row(
                Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Card(
                    Modifier.weight(1f).fillMaxHeight(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, PsBorder),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Text("支払方法・表示順", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = PsNavy)
                        Text(
                            "現金は常時有効です。その他の支払方法は有効/無効と会計画面の表示順を設定できます。",
                            color = Color.DarkGray,
                            fontSize = 13.sp,
                        )
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
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Checkbox(
                                                checked = row.allowOverpaymentWithChange,
                                                onCheckedChange = { checked ->
                                                    if (row.method != PaymentMethod.CASH) {
                                                        update(row.method) {
                                                            it.copy(allowOverpaymentWithChange = checked)
                                                        }
                                                    }
                                                },
                                                enabled = row.method != PaymentMethod.CASH && row.enabled,
                                            )
                                            Text(
                                                if (row.method == PaymentMethod.CASH) {
                                                    "残額超過を許可し、お釣りを計算（固定）"
                                                } else {
                                                    "残額超過を許可し、超過分をお釣りとして扱う"
                                                },
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

                Column(
                    Modifier.width(360.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = PsPaleGreen),
                        border = BorderStroke(1.dp, PsBorder),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text("複合支払", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PsNavy)
                            Text(
                                "有効な支払方法を複数回追加して、1会計を複数支払へ分割できます。",
                                fontSize = 13.sp,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text("状態：利用可能", color = PsNavy, fontWeight = FontWeight.Bold)
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, PsBorder),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text("決済端末アダプタ", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PsNavy)
                            Text("現在：${initial.terminalAdapter.displayName}", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "自動決済端末連携は未接続です。カード・電子マネー・QRは、外部端末側で決済結果を確認してからレジへ支払を登録してください。",
                                fontSize = 13.sp,
                                color = Color.DarkGray,
                            )
                            Text(
                                "未実装の端末連携を有効として扱いません。",
                                fontSize = 12.sp,
                                color = PsDanger,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    message?.let {
                        Text(
                            it,
                            color = if (it.startsWith("保存")) PsNavy else PsDanger,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.weight(1f))
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
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PsBlue),
                    ) {
                        Text("保存", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onClose,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                    ) {
                        Text("戻る")
                    }
                }
            }
        }
    }
}
