package jp.co.tenposinfo.register

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID

private val VoucherNavy = Color(0xFF173F6B)
private val VoucherBlue = Color(0xFF1976B9)
private val VoucherBackground = Color(0xFFF4F7FA)
private val VoucherBorder = Color(0xFFD5DEE7)
private val VoucherPaleBlue = Color(0xFFEAF3FA)
private val VoucherPaleGreen = Color(0xFFEAF5EC)
private val VoucherPaleYellow = Color(0xFFFFF4D9)
private val VoucherDanger = Color(0xFFC62828)

internal data class ReceiptVoucherUiCalculation(
    val unitAmount: Long,
    val copies: Int,
    val totalAmount: Long,
)

internal object ReceiptVoucherUiPolicy {
    fun calculate(unitAmountText: String, copiesText: String): ReceiptVoucherUiCalculation? {
        val unitAmount = unitAmountText.trim().toLongOrNull()?.takeIf { it > 0L } ?: return null
        val copies = copiesText.trim().toIntOrNull()?.takeIf { it in 1..ReceiptVoucherPolicy.MAX_COPIES } ?: return null
        val total = runCatching { Math.multiplyExact(unitAmount, copies.toLong()) }.getOrNull() ?: return null
        return ReceiptVoucherUiCalculation(unitAmount, copies, total)
    }

    fun canIssue(calculation: ReceiptVoucherUiCalculation?, remainingAmount: Long): Boolean =
        calculation != null && calculation.totalAmount <= remainingAmount && remainingAmount > 0L

    fun confirmationText(calculation: ReceiptVoucherUiCalculation): String =
        "${yen(calculation.unitAmount)} × ${calculation.copies}枚 = ${yen(calculation.totalAmount)}"

    private fun yen(value: Long): String = NumberFormat.getCurrencyInstance(Locale.JAPAN).format(value)
}

class ReceiptVoucherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureRegisterSystemBars(window)
        val requestedSaleId = ReceiptVoucherNavigation.requestedSaleId(intent)
        setContent {
            MaterialTheme {
                ReceiptVoucherRoute(
                    requestedSaleId = requestedSaleId,
                    onClose = { finish() },
                )
            }
        }
    }
}

@Composable
private fun ReceiptVoucherRoute(
    requestedSaleId: Long?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val database = remember { RegisterDatabase(context.applicationContext) }
    val voucherStore = remember { ReceiptVoucherStore(context.applicationContext) }
    val operator = remember { OperatorSessionRegistry.current(context.applicationContext) }
    var refreshEpoch by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        onDispose {
            voucherStore.close()
            database.close()
        }
    }

    Surface(Modifier.fillMaxSize(), color = VoucherBackground) {
        if (operator == null || !operator.allows(RegisterPermission.VIEW_SALES)) {
            VoucherDenied(onClose)
            return@Surface
        }
        OperatorSessionRegistry.touch(context.applicationContext)
        val sales = remember(refreshEpoch) { database.listSales() }
        val saleContext = ReceiptVoucherNavigation.resolveSaleContext(
            requestedSaleId = requestedSaleId,
            availableSaleIds = sales.map { it.id },
        )
        ReceiptVoucherOperationsScreen(
            sales = sales,
            operatorName = operator.name,
            voucherStore = voucherStore,
            initialSaleId = saleContext.selectedSaleId,
            lockedSaleId = saleContext.selectedSaleId.takeIf { saleContext.selectionLocked },
            requestedSaleUnavailable = saleContext.requestedSaleUnavailable,
            onRefresh = { refreshEpoch++ },
            onClose = onClose,
        )
    }
}

@Composable
private fun ReceiptVoucherOperationsScreen(
    sales: List<SaleSummaryRecord>,
    operatorName: String,
    voucherStore: ReceiptVoucherStore,
    initialSaleId: Long?,
    lockedSaleId: Long?,
    requestedSaleUnavailable: Boolean,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var selectedSaleId by remember(initialSaleId) { mutableStateOf<Long?>(initialSaleId) }
    var addressee by remember { mutableStateOf("") }
    var purpose by remember { mutableStateOf("お食事代") }
    var unitAmountText by remember { mutableStateOf("") }
    var copiesText by remember { mutableStateOf("1") }
    var requestId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var issueConfirmation by remember { mutableStateOf(false) }
    var reprintConfirmationId by remember { mutableStateOf<Long?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val selectedSale = sales.firstOrNull { it.id == selectedSaleId }
    val availability = selectedSaleId?.let { id -> runCatching { voucherStore.availability(id) }.getOrNull() }
    val issued = selectedSaleId?.let { id -> runCatching { voucherStore.listForSale(id) }.getOrDefault(emptyList()) }.orEmpty()
    val calculation = ReceiptVoucherUiPolicy.calculate(unitAmountText, copiesText)
    val remaining = availability?.remainingAmount ?: 0L
    val canIssue = selectedSale != null && ReceiptVoucherUiPolicy.canIssue(calculation, remaining) &&
        addressee.isNotBlank() && purpose.isNotBlank()

    fun resetConfirmation() {
        issueConfirmation = false
        message = null
    }

    Column(Modifier.fillMaxSize()) {
        VoucherHeader()
        Row(
            Modifier.weight(1f).fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                modifier = Modifier.weight(0.9f).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, VoucherBorder),
            ) {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Text("売上を選択", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = VoucherNavy)
                    Spacer(Modifier.height(6.dp))
                    if (lockedSaleId != null) {
                        Text(
                            "売上No.$lockedSaleId から開いています。対象売上は固定されています。",
                            color = VoucherBlue,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(6.dp))
                    } else if (requestedSaleUnavailable) {
                        Text(
                            "指定された売上が見つからないため、売上一覧から選択してください。",
                            color = VoucherDanger,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    if (sales.isEmpty()) {
                        Text("領収書を発行できる売上がありません", color = Color.Gray)
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(sales, key = { it.id }) { sale ->
                                val selected = sale.id == selectedSaleId
                                val saleSelectionEnabled = lockedSaleId == null || lockedSaleId == sale.id
                                Card(
                                    modifier = Modifier.fillMaxWidth().clickable(
                                        enabled = saleSelectionEnabled,
                                        onClick = {
                                            selectedSaleId = sale.id
                                            unitAmountText = ""
                                            copiesText = "1"
                                            requestId = UUID.randomUUID().toString()
                                            issueConfirmation = false
                                            reprintConfirmationId = null
                                            message = null
                                        },
                                    ),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (selected) VoucherPaleBlue else Color.White,
                                    ),
                                    border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) VoucherBlue else VoucherBorder),
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text("売上No.${sale.id}", fontWeight = FontWeight.Bold, color = VoucherNavy)
                                            Text("担当 ${sale.operatorName} / ${sale.paymentLabel}", fontSize = 13.sp, color = Color.DarkGray)
                                        }
                                        Text(voucherYen(sale.totalAmount), fontSize = 19.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Column(
                Modifier.weight(1.35f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, VoucherBorder),
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text("領収書発行", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = VoucherNavy)
                        selectedSale?.let {
                            Text(
                                "対象 売上No.${it.id}${if (lockedSaleId == it.id) "（固定）" else ""}",
                                color = if (lockedSaleId == it.id) VoucherBlue else Color.DarkGray,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        VoucherAmountRow("売上合計", selectedSale?.let { voucherYen(it.totalAmount) } ?: "-")
                        VoucherAmountRow("発行済み", availability?.let { voucherYen(it.allocatedAmount) } ?: "-")
                        VoucherAmountRow("発行可能残額", availability?.let { voucherYen(it.remainingAmount) } ?: "-", emphasized = true)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = addressee,
                                onValueChange = { addressee = it; resetConfirmation() },
                                label = { Text("宛名") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = purpose,
                                onValueChange = { purpose = it; resetConfirmation() },
                                label = { Text("但し書き") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = unitAmountText,
                                onValueChange = { raw -> unitAmountText = raw.filter(Char::isDigit).take(12); resetConfirmation() },
                                label = { Text("1枚の金額") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = copiesText,
                                onValueChange = { raw -> copiesText = raw.filter(Char::isDigit).take(3); resetConfirmation() },
                                label = { Text("枚数") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.width(130.dp),
                            )
                            OutlinedButton(
                                onClick = {
                                    unitAmountText = remaining.toString()
                                    copiesText = "1"
                                    resetConfirmation()
                                },
                                enabled = remaining > 0,
                                modifier = Modifier.heightIn(min = 56.dp),
                            ) { Text("残額全額") }
                        }

                        val calculationText = calculation?.let(ReceiptVoucherUiPolicy::confirmationText) ?: "金額と枚数を入力してください"
                        Text(
                            calculationText,
                            modifier = Modifier.fillMaxWidth().background(VoucherPaleYellow, RoundedCornerShape(8.dp)).padding(10.dp),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                        if (calculation != null && calculation.totalAmount > remaining) {
                            Text("発行合計が発行可能残額を超えています", color = VoucherDanger, fontWeight = FontWeight.Bold)
                        }

                        message?.let {
                            Text(
                                it,
                                modifier = Modifier.fillMaxWidth().background(VoucherPaleGreen, RoundedCornerShape(8.dp)).padding(10.dp),
                                color = VoucherNavy,
                            )
                        }

                        if (!issueConfirmation) {
                            Button(
                                onClick = { issueConfirmation = true; message = "内容を確認し、もう一度『発行を確定』を押してください" },
                                enabled = canIssue,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = VoucherBlue),
                            ) { Text("発行内容を確認", fontWeight = FontWeight.Bold) }
                        } else {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = VoucherPaleYellow),
                                border = BorderStroke(2.dp, VoucherBlue),
                            ) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Text("発行前の最終確認", fontWeight = FontWeight.Bold, color = VoucherNavy)
                                    Text("売上No.${selectedSale?.id} / $addressee / $purpose")
                                    Text(calculation?.let(ReceiptVoucherUiPolicy::confirmationText).orEmpty(), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { issueConfirmation = false; message = null }, modifier = Modifier.weight(1f)) {
                                            Text("戻る")
                                        }
                                        Button(
                                            onClick = {
                                                val sale = selectedSale ?: return@Button
                                                val calc = calculation ?: return@Button
                                                runCatching {
                                                    voucherStore.issueBatch(
                                                        ReceiptVoucherBatchRequest(
                                                            requestId = requestId,
                                                            saleId = sale.id,
                                                            unitAmount = calc.unitAmount,
                                                            copies = calc.copies,
                                                            addressee = addressee,
                                                            purpose = purpose,
                                                            operatorName = operatorName,
                                                        ),
                                                    )
                                                }.onSuccess { result ->
                                                    message = "領収書${result.issuanceIds.size}枚を印刷キューへ登録しました。残額 ${voucherYen(result.remainingAmount)}"
                                                    requestId = UUID.randomUUID().toString()
                                                    issueConfirmation = false
                                                    onRefresh()
                                                }.onFailure { error ->
                                                    message = error.message ?: "領収書を発行できませんでした"
                                                    issueConfirmation = false
                                                }
                                            },
                                            enabled = canIssue,
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(containerColor = VoucherBlue),
                                        ) { Text("発行を確定", fontWeight = FontWeight.Bold) }
                                    }
                                }
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, VoucherBorder),
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text("発行履歴", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = VoucherNavy)
                        if (issued.isEmpty()) {
                            Text("この売上の領収書発行履歴はありません", color = Color.Gray)
                        } else {
                            issued.forEach { record ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                                    colors = CardDefaults.cardColors(containerColor = VoucherPaleBlue),
                                    border = BorderStroke(1.dp, VoucherBorder),
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text("領収書No.R${record.id}  ${voucherYen(record.amount)}", fontWeight = FontWeight.Bold)
                                            Text("${record.addressee} / ${record.purpose}", fontSize = 13.sp)
                                            if (record.sequenceCount > 1) Text("一括 ${record.sequenceNo}/${record.sequenceCount}", fontSize = 12.sp, color = Color.DarkGray)
                                        }
                                        if (reprintConfirmationId == record.id) {
                                            OutlinedButton(onClick = { reprintConfirmationId = null }) { Text("中止") }
                                            Spacer(Modifier.width(6.dp))
                                            Button(
                                                onClick = {
                                                    runCatching { voucherStore.reprint(record.id, operatorName) }
                                                        .onSuccess {
                                                            message = "領収書No.R${record.id}を【再発行】として印刷キューへ登録しました"
                                                            reprintConfirmationId = null
                                                            onRefresh()
                                                        }
                                                        .onFailure { error ->
                                                            message = error.message ?: "再発行できませんでした"
                                                            reprintConfirmationId = null
                                                        }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = VoucherBlue),
                                            ) { Text("再発行確定") }
                                        } else {
                                            OutlinedButton(onClick = { reprintConfirmationId = record.id; issueConfirmation = false }) {
                                                Text("再発行")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(64.dp).background(Color.White).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("領収書履歴は削除せず、再発行も履歴へ追記します", color = Color.DarkGray)
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = { context.startActivity(ReceiptVoucherNavigation.ledgerIntent(context, selectedSaleId)) },
                modifier = Modifier.heightIn(min = 46.dp),
            ) { Text("運用台帳・印刷状態") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onClose, modifier = Modifier.heightIn(min = 46.dp)) { Text("管理メニューへ戻る") }
        }
    }
}

@Composable
private fun VoucherAmountRow(label: String, value: String, emphasized: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = Color.DarkGray)
        Text(
            value,
            fontSize = if (emphasized) 21.sp else 17.sp,
            fontWeight = FontWeight.Bold,
            color = if (emphasized) VoucherNavy else Color.Black,
        )
    }
}

@Composable
private fun VoucherHeader() {
    Row(
        Modifier.fillMaxWidth().height(58.dp).background(VoucherNavy).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("つぐレジ", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(20.dp))
        Text("領収書発行・再発行", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun VoucherDenied(onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("領収書発行を利用できません", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = VoucherDanger)
        Spacer(Modifier.height(10.dp))
        Text("売上確認権限がないか、ログインセッションが失効しています。")
        Spacer(Modifier.height(20.dp))
        Button(onClick = onClose, colors = ButtonDefaults.buttonColors(containerColor = VoucherBlue)) { Text("戻る") }
    }
}

private fun voucherYen(value: Long): String = NumberFormat.getCurrencyInstance(Locale.JAPAN).format(value)
