package jp.co.tenposinfo.register

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.util.Locale

private val Uc08Navy = Color(0xFF173F6B)
private val Uc08Blue = Color(0xFF1976B9)
private val Uc08PaleBlue = Color(0xFFEAF3FA)
private val Uc08Danger = Color(0xFFC62828)
private val Uc08Warning = Color(0xFFFFE0B2)
private val Uc08Border = Color(0xFFD5DEE7)

/**
 * UC-08 payment screen overload.
 *
 * RegisterApp owns a SnapshotStateList cart, so this overload is more specific than the legacy
 * List<CartItem> overload in MainActivity.kt. Keeping the v1.35 payment closure isolated avoids
 * destabilising the large cumulative screen source while exposing every initial-release tender.
 */
@Composable
internal fun PaymentScreen(
    items: SnapshotStateList<CartItem>,
    state: PaymentState,
    completing: Boolean,
    externalMessage: String?,
    onStateChange: (PaymentState) -> Unit,
    onBack: () -> Unit,
    onComplete: () -> Unit,
) {
    val summary = TaxEngine.calculate(items)
    val remaining = state.remaining(summary.grossAmount)
    val context = LocalContext.current
    val mixedPolicy = remember { TaxInvoiceSettingsStore(context.applicationContext).load().mixedTaxPolicy }
    var input by remember { mutableStateOf("") }
    var operationMessage by remember { mutableStateOf<String?>(null) }
    var acknowledgedMixedTax by remember { mutableStateOf(false) }
    val mixed = TaxEngine.validateMixedTax(items, MixedTaxPolicy.ALLOW)
    val mixedBlocked = mixed.hasMixedTax && mixedPolicy == MixedTaxPolicy.BLOCK
    val mixedNeedsAcknowledgement = mixed.hasMixedTax && mixedPolicy == MixedTaxPolicy.WARN

    fun add(method: PaymentMethod) {
        if (completing) return
        val amount = input.toLongOrNull()
        runCatching { PaymentEngine.addPayment(state, summary.grossAmount, method, amount) }
            .onSuccess {
                onStateChange(it)
                input = ""
                operationMessage = null
            }
            .onFailure { error ->
                operationMessage = error.message ?: "支払を追加できませんでした"
            }
    }

    Column(Modifier.fillMaxSize()) {
        Uc08Header()
        Row(
            Modifier.weight(1f).fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Uc08Card(Modifier.weight(1.2f).fillMaxHeight()) {
                Text("会計内訳", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Uc08Navy)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(items) { _, item ->
                        Uc08AmountRow(
                            "${item.product.name} × ${item.quantity} ${item.product.taxSymbol}",
                            uc08Yen(item.amountBeforeDiscount),
                        )
                        if (item.discountAmount > 0L) {
                            Uc08AmountRow("  値引", "-${uc08Yen(item.discountAmount)}")
                        }
                    }
                }
                Uc08AmountRow("商品計", uc08Yen(items.sumOf { it.baseAmount }))
                summary.buckets.forEach { bucket ->
                    if (bucket.taxable) {
                        Uc08AmountRow(
                            "${bucket.ratePercent}%対象",
                            "${uc08Yen(bucket.grossAmount)} / 税 ${uc08Yen(bucket.taxAmount)}",
                        )
                    } else {
                        Uc08AmountRow("非課税", uc08Yen(bucket.grossAmount))
                    }
                }
                Uc08AmountRow("合計", uc08Yen(summary.grossAmount), emphasized = true)
                if (mixed.hasMixedTax) {
                    val instruction = when (mixedPolicy) {
                        MixedTaxPolicy.ALLOW -> "設定により許可されています。税率単位で一度だけ端数処理します。"
                        MixedTaxPolicy.WARN -> if (acknowledgedMixedTax) {
                            "確認済みです。会計確定できます。"
                        } else {
                            "内容を確認し、この表示を押して確認済みにしてください。"
                        }
                        MixedTaxPolicy.BLOCK -> "設定により禁止されています。商品税区分を修正してください。"
                    }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Uc08Warning),
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (mixedPolicy == MixedTaxPolicy.WARN && !acknowledgedMixedTax) {
                                    Modifier.clickable {
                                        if (MixedTaxPaymentAcknowledgementV135.record(context.applicationContext, items.toList())) {
                                            acknowledgedMixedTax = true
                                            operationMessage = null
                                        } else {
                                            acknowledgedMixedTax = false
                                            operationMessage = "税混在の確認履歴を保存できないため、会計確定できません。"
                                        }
                                    }
                                } else Modifier,
                            ),
                    ) {
                        Text("${mixed.message}\n$instruction", Modifier.padding(10.dp), fontWeight = FontWeight.Bold)
                    }
                }
            }

            Uc08Card(Modifier.weight(0.9f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Uc08PaymentRow("合計", uc08Yen(summary.grossAmount), true)
                        Uc08PaymentRow("支払済", uc08Yen(state.paidAmount))
                    }
                    Column(Modifier.weight(1f)) {
                        Uc08PaymentRow("残額", uc08Yen(remaining), true)
                        Uc08PaymentRow("お釣り", uc08Yen(state.changeAmount))
                    }
                }
                Spacer(Modifier.height(6.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 72.dp)) {
                    itemsIndexed(state.allocations) { index, payment ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${payment.method.displayName} ${uc08Yen(payment.appliedAmount)}",
                                Modifier.weight(1f),
                                maxLines = 1,
                            )
                            OutlinedButton(
                                onClick = { onStateChange(PaymentEngine.removeAt(state, index)) },
                                enabled = !completing,
                                modifier = Modifier.height(34.dp),
                            ) { Text("取消", maxLines = 1, fontSize = 12.sp) }
                        }
                    }
                }
                val visibleMessage = externalMessage ?: operationMessage
                if (!visibleMessage.isNullOrBlank()) {
                    Text(
                        visibleMessage,
                        color = if (completing) Color(0xFF2E7D32) else Uc08Danger,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                    )
                }
                Uc08ValueBox(if (input.isBlank()) "残額全額" else input)
                Spacer(Modifier.height(6.dp))
                Uc08NumberPad(
                    onDigit = { if (input.length < 10) input += it },
                    onClear = { input = "" },
                    onCash = { add(PaymentMethod.CASH) },
                    enabled = remaining > 0 && !completing,
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Uc08TenderButton("クレジット", remaining > 0 && !completing, Modifier.weight(1f)) {
                        add(PaymentMethod.CARD)
                    }
                    Uc08TenderButton("商品券", remaining > 0 && !completing, Modifier.weight(1f)) {
                        add(PaymentMethod.GIFT_CERTIFICATE)
                    }
                    Uc08TenderButton("掛売", remaining > 0 && !completing, Modifier.weight(1f)) {
                        add(PaymentMethod.ACCOUNT_RECEIVABLE)
                    }
                    Uc08TenderButton("その他", remaining > 0 && !completing, Modifier.weight(1f)) {
                        add(PaymentMethod.OTHER)
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 12.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onBack, enabled = !completing, modifier = Modifier.width(150.dp).fillMaxHeight()) {
                Text("戻る")
            }
            Button(
                onClick = onComplete,
                enabled = remaining == 0L && !mixedBlocked && (!mixedNeedsAcknowledgement || acknowledgedMixedTax) && !completing,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = ButtonDefaults.buttonColors(containerColor = Uc08Blue),
            ) {
                Text(if (completing) "会計確定中…" else "会計確定", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Uc08Header() {
    Row(
        Modifier.fillMaxWidth().height(58.dp).background(Uc08Navy).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("つぐレジ", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(20.dp))
        Text("SCR-300 / SCR-310  会計・支払追加", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Uc08Card(modifier: Modifier, content: @Composable Column.() -> Unit) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Uc08Border),
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp), content = content)
    }
}

@Composable
private fun Uc08AmountRow(label: String, value: String, emphasized: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = if (emphasized) 18.sp else 14.sp, fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal)
        Text(value, fontSize = if (emphasized) 20.sp else 14.sp, fontWeight = FontWeight.Bold, color = if (emphasized) Uc08Navy else Color.Unspecified)
    }
}

@Composable
private fun Uc08PaymentRow(label: String, value: String, emphasized: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = if (emphasized) 17.sp else 13.sp, fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal)
        Text(value, fontSize = if (emphasized) 18.sp else 14.sp, fontWeight = FontWeight.Bold, color = if (emphasized) Uc08Navy else Color.Unspecified)
    }
}

@Composable
private fun Uc08ValueBox(value: String) {
    Box(
        Modifier.fillMaxWidth().height(44.dp).background(Uc08PaleBlue, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(value, fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Uc08Navy, maxLines = 1)
    }
}

@Composable
private fun Uc08NumberPad(
    onDigit: (String) -> Unit,
    onClear: () -> Unit,
    onCash: () -> Unit,
    enabled: Boolean,
) {
    val rows = listOf(listOf("7", "8", "9"), listOf("4", "5", "6"), listOf("1", "2", "3"))
    rows.forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            row.forEach { digit ->
                OutlinedButton(onClick = { onDigit(digit) }, enabled = enabled, modifier = Modifier.weight(1f).height(39.dp)) {
                    Text(digit, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        OutlinedButton(onClick = onClear, enabled = !enabled || enabled, modifier = Modifier.weight(1f).height(39.dp)) {
            Text("C", color = Uc08Danger, fontSize = 17.sp)
        }
        OutlinedButton(onClick = { onDigit("0") }, enabled = enabled, modifier = Modifier.weight(1f).height(39.dp)) {
            Text("0", fontSize = 17.sp)
        }
        Button(
            onClick = onCash,
            enabled = enabled,
            modifier = Modifier.weight(1f).height(39.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Uc08Blue),
        ) { Text("現金", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun Uc08TenderButton(label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.height(39.dp)) {
        Text(label, fontSize = 12.sp, maxLines = 1, textAlign = TextAlign.Center)
    }
}

private fun uc08Yen(amount: Long): String = NumberFormat.getCurrencyInstance(Locale.JAPAN).format(amount)
