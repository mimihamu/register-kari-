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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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

private val TiNavy = Color(0xFF173F6B)
private val TiBlue = Color(0xFF1976B9)
private val TiDanger = Color(0xFFC62828)
private val TiBorder = Color(0xFFD5DEE7)
private val TiBackground = Color(0xFFF4F7FA)

class TaxInvoiceSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureRegisterSystemBars(window)
        setContent {
            MaterialTheme {
                TaxInvoiceSettingsScreen(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun TaxInvoiceSettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember { TaxInvoiceSettingsStore(context.applicationContext) }
    val initial = remember { store.load() }
    var policy by remember { mutableStateOf(initial.mixedTaxPolicy) }
    var storeName by remember { mutableStateOf(initial.issuer.storeName) }
    var address by remember { mutableStateOf(initial.issuer.address) }
    var phone by remember { mutableStateOf(initial.issuer.phone) }
    var registration by remember { mutableStateOf(initial.issuer.registrationNumber) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Surface(Modifier.fillMaxSize(), color = TiBackground) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(62.dp).background(TiNavy).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("つぐレジ", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(22.dp))
                Text("SCR-275  税計算・インボイス設定", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onClose) { Text("閉じる", color = Color.White) }
            }

            Row(
                Modifier.weight(1f).fillMaxWidth().padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Card(
                    Modifier.weight(0.92f).fillMaxSize(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, TiBorder),
                ) {
                    Column(Modifier.fillMaxSize().padding(18.dp)) {
                        Text("同率の内税・外税混在", color = TiNavy, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        MixedPolicyButton("許可", "警告なしで会計できます", MixedTaxPolicy.ALLOW, policy) { policy = it }
                        Spacer(Modifier.height(8.dp))
                        MixedPolicyButton("警告", "会計画面で確認操作後に確定できます", MixedTaxPolicy.WARN, policy) { policy = it }
                        Spacer(Modifier.height(8.dp))
                        MixedPolicyButton("禁止", "商品税区分を直すまで会計確定できません（既定）", MixedTaxPolicy.BLOCK, policy) { policy = it }
                        Spacer(Modifier.height(18.dp))
                        Text(
                            "混在時も税率ごとの税額は、値引後金額を集計し、税率単位で最後に1回だけ端数処理します。",
                            color = Color.DarkGray,
                        )
                    }
                }

                Card(
                    Modifier.weight(1.35f).fillMaxSize(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, TiBorder),
                ) {
                    Column(Modifier.fillMaxSize().padding(18.dp)) {
                        Text("適格請求書発行者・店舗情報", color = TiNavy, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = storeName,
                            onValueChange = { storeName = it.take(80) },
                            label = { Text("店舗名（必須）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it.take(160) },
                            label = { Text("住所") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = phone,
                                onValueChange = { phone = it.take(40) },
                                label = { Text("電話番号") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = registration,
                                onValueChange = { registration = it.uppercase().take(16) },
                                label = { Text("登録番号（T＋13桁）") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("登録番号が空欄の場合はレシートへ印字しません。実際に交付する前に正しい情報を登録してください。", color = TiDanger, fontSize = 13.sp)
                        if (!error.isNullOrBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(error!!, color = TiDanger, fontWeight = FontWeight.Bold)
                        }
                        if (!message.isNullOrBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(message!!, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = {
                                runCatching {
                                    store.save(
                                        TaxInvoiceSettings(
                                            mixedTaxPolicy = policy,
                                            issuer = InvoiceIssuerProfile(storeName, address, phone, registration),
                                        ),
                                    )
                                }.onSuccess {
                                    error = null
                                    message = "税計算・インボイス設定を保存しました"
                                }.onFailure {
                                    message = null
                                    error = it.message ?: "保存できませんでした"
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = TiBlue),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            Text("保存", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MixedPolicyButton(
    title: String,
    description: String,
    value: MixedTaxPolicy,
    selected: MixedTaxPolicy,
    onSelect: (MixedTaxPolicy) -> Unit,
) {
    OutlinedButton(
        onClick = { onSelect(value) },
        modifier = Modifier.fillMaxWidth().height(58.dp),
        border = BorderStroke(if (selected == value) 3.dp else 1.dp, if (selected == value) TiDanger else TiBorder),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.width(62.dp), color = TiNavy, fontWeight = FontWeight.Bold)
            Text(description, color = Color.DarkGray, fontSize = 13.sp)
        }
    }
}
