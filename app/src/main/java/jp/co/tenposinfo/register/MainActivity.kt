package jp.co.tenposinfo.register

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Navy = Color(0xFF173F6B)
private val Blue = Color(0xFF1976B9)
private val PaleBlue = Color(0xFFEAF3FA)
private val PaleGreen = Color(0xFFEAF5EC)
private val PaleYellow = Color(0xFFFFF4D9)
private val PaleRed = Color(0xFFFFEBEE)
private val Danger = Color(0xFFC62828)
private val Background = Color(0xFFF4F7FA)
private val Border = Color(0xFFD5DEE7)

class MainActivity : ComponentActivity() {
    private val database by lazy { RegisterDatabase(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                RegisterApp(database)
            }
        }
    }

    override fun onDestroy() {
        database.close()
        super.onDestroy()
    }
}

private enum class AppScreen {
    DIAGNOSTIC,
    LOGIN,
    SALES,
    TICKETS,
    PAYMENT,
    COMPLETE,
}

@Composable
private fun RegisterApp(database: RegisterDatabase) {
    var screen by remember { mutableStateOf(AppScreen.DIAGNOSTIC) }
    var operatorName by remember { mutableStateOf("未選択") }
    val products = remember { database.loadProducts() }
    val cart = remember {
        mutableStateListOf<CartItem>().apply { addAll(database.loadCart()) }
    }
    var heldTicketRevision by remember { mutableIntStateOf(0) }
    var completedTotal by remember { mutableStateOf(0L) }
    var completedChange by remember { mutableStateOf(0L) }
    var completedPayment by remember { mutableStateOf("現金") }
    var completedSaleId by remember { mutableStateOf(0L) }

    fun persistCart() {
        database.saveCart(cart.toList())
    }

    fun addProduct(product: Product) {
        val index = cart.indexOfFirst { it.product.id == product.id }
        if (index >= 0) {
            cart[index] = cart[index].copy(quantity = cart[index].quantity + 1)
        } else {
            cart += CartItem(product, 1)
        }
        persistCart()
    }

    fun changeQuantity(productId: String, delta: Int) {
        val index = cart.indexOfFirst { it.product.id == productId }
        if (index < 0) return
        val next = cart[index].quantity + delta
        if (next <= 0) cart.removeAt(index) else cart[index] = cart[index].copy(quantity = next)
        persistCart()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Background) {
        when (screen) {
            AppScreen.DIAGNOSTIC -> DiagnosticScreen(
                restoredRows = cart.sumOf { it.quantity },
                onComplete = { screen = AppScreen.LOGIN },
            )

            AppScreen.LOGIN -> LoginScreen(
                onLogin = {
                    operatorName = it
                    screen = AppScreen.SALES
                },
            )

            AppScreen.SALES -> SalesScreen(
                operatorName = operatorName,
                products = products,
                cart = cart,
                onAddProduct = ::addProduct,
                onChangeQuantity = ::changeQuantity,
                onCancelTransaction = {
                    cart.clear()
                    persistCart()
                },
                onHold = {
                    if (cart.isNotEmpty()) {
                        val name = "伝票 ${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))}"
                        database.holdCart(name, operatorName, cart.toList())
                        cart.clear()
                        persistCart()
                        heldTicketRevision++
                    }
                },
                onTickets = { screen = AppScreen.TICKETS },
                onPayment = { screen = AppScreen.PAYMENT },
            )

            AppScreen.TICKETS -> TicketListScreen(
                tickets = remember(heldTicketRevision) { database.listHeldTickets() },
                onBack = { screen = AppScreen.SALES },
                onRetrieve = { ticketId ->
                    cart.clear()
                    cart.addAll(database.loadHeldTicket(ticketId))
                    database.deleteHeldTicket(ticketId)
                    persistCart()
                    heldTicketRevision++
                    screen = AppScreen.SALES
                },
            )

            AppScreen.PAYMENT -> PaymentScreen(
                cart = cart,
                onBack = { screen = AppScreen.SALES },
                onComplete = { paymentMethod, deposit, change ->
                    val summary = TaxEngine.calculate(cart)
                    completedTotal = summary.grossAmount
                    completedChange = change
                    completedPayment = paymentMethod
                    completedSaleId = database.saveSale(
                        operatorName = operatorName,
                        paymentMethod = paymentMethod,
                        items = cart.toList(),
                        deposit = deposit,
                        change = change,
                    )
                    cart.clear()
                    persistCart()
                    screen = AppScreen.COMPLETE
                },
            )

            AppScreen.COMPLETE -> CompleteScreen(
                saleId = completedSaleId,
                total = completedTotal,
                paymentMethod = completedPayment,
                change = completedChange,
                onNext = { screen = AppScreen.SALES },
            )
        }
    }
}

@Composable
private fun Header(screenId: String, title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(62.dp)
            .background(Navy)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("REGISTER", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(24.dp))
        Text("$screenId  $title", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(
            "営業日 ${LocalDate.now()}  ${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))}",
            color = Color.White,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun DiagnosticScreen(restoredRows: Int, onComplete: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Header("SCR-001", "起動・自己診断")
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("起動チェックを実行しました", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Navy)
            Spacer(Modifier.height(24.dp))
            val checks = listOf(
                "データベース" to "正常（SQLite）",
                "作業中カート" to if (restoredRows == 0) "なし" else "$restoredRows 点を復元",
                "営業日" to "未開始",
                "プリンタ" to "未設定（販売可能）",
                "Google Drive" to "未接続（販売可能）",
            )
            Card(
                modifier = Modifier.width(680.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Border),
            ) {
                checks.forEach { (label, value) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, modifier = Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Text(value, color = Blue, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(26.dp))
            Button(
                onClick = onComplete,
                modifier = Modifier.width(320.dp).height(58.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Blue),
            ) {
                Text("診断完了・担当者選択へ", fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun LoginScreen(onLogin: (String) -> Unit) {
    var selected by remember { mutableStateOf("山田") }
    var pin by remember { mutableStateOf("") }
    val operators = listOf("山田", "佐藤", "鈴木", "田中", "責任者", "管理者")

    Column(modifier = Modifier.fillMaxSize()) {
        Header("SCR-010", "担当者選択／ログイン")
        Row(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("担当者を選択", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Navy)
                Spacer(Modifier.height(18.dp))
                operators.chunked(3).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        row.forEach { name ->
                            OutlinedButton(
                                onClick = { selected = name },
                                modifier = Modifier.weight(1f).height(82.dp),
                                border = BorderStroke(if (selected == name) 3.dp else 1.dp, if (selected == name) Danger else Border),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text(name, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Navy)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }

            Card(
                modifier = Modifier.width(390.dp).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Border),
            ) {
                Column(modifier = Modifier.padding(22.dp)) {
                    Text("PIN入力", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Navy)
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier.fillMaxWidth().height(64.dp).background(PaleBlue, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (pin.isEmpty()) "選択のみでもログイン可" else "●".repeat(pin.length), fontSize = 24.sp, color = Navy)
                    }
                    Spacer(Modifier.height(14.dp))
                    (1..9).toList().chunked(3).forEach { row ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { number ->
                                OutlinedButton(
                                    onClick = { if (pin.length < 8) pin += number.toString() },
                                    modifier = Modifier.weight(1f).height(58.dp),
                                ) { Text(number.toString(), fontSize = 21.sp) }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { pin = "" }, modifier = Modifier.weight(1f).height(58.dp)) {
                            Text("C", color = Danger, fontSize = 20.sp)
                        }
                        OutlinedButton(onClick = { if (pin.length < 8) pin += "0" }, modifier = Modifier.weight(1f).height(58.dp)) {
                            Text("0", fontSize = 20.sp)
                        }
                        Button(
                            onClick = { onLogin(selected) },
                            modifier = Modifier.weight(1.3f).height(58.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Blue),
                        ) { Text("ログイン", fontSize = 17.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SalesScreen(
    operatorName: String,
    products: List<Product>,
    cart: List<CartItem>,
    onAddProduct: (Product) -> Unit,
    onChangeQuantity: (String, Int) -> Unit,
    onCancelTransaction: () -> Unit,
    onHold: () -> Unit,
    onTickets: () -> Unit,
    onPayment: () -> Unit,
) {
    val summary = TaxEngine.calculate(cart)
    val mixedResult = TaxEngine.validateMixedTax(cart, MixedTaxPolicy.WARN)

    Column(modifier = Modifier.fillMaxSize()) {
        Header("SCR-100", "販売画面")
        Row(
            modifier = Modifier.fillMaxWidth().height(38.dp).background(Color.White).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("店舗：サンプル居酒屋  |  担当：$operatorName  |  伝票：作業中", color = Navy, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text("DB保存：正常", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
        }

        Row(
            modifier = Modifier.weight(1f).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                modifier = Modifier.weight(0.38f).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Border),
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                    Text("注文一覧", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Navy)
                    Spacer(Modifier.height(8.dp))
                    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        if (cart.isEmpty()) {
                            Text("商品ボタンを押して登録してください", color = Color.Gray, modifier = Modifier.padding(12.dp))
                        }
                        cart.forEach { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.product.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "${item.quantity} × ${yen(item.product.unitPrice)}  ${item.product.taxCategory.symbol}",
                                        color = Color.Gray,
                                        fontSize = 13.sp,
                                    )
                                }
                                OutlinedButton(onClick = { onChangeQuantity(item.product.id, -1) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(42.dp)) {
                                    Text("－", fontSize = 18.sp)
                                }
                                Spacer(Modifier.width(6.dp))
                                OutlinedButton(onClick = { onChangeQuantity(item.product.id, 1) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(42.dp)) {
                                    Text("＋", fontSize = 18.sp)
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(yen(item.baseAmount), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    if (mixedResult.message != null) {
                        Text(
                            mixedResult.message,
                            color = Danger,
                            fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().background(PaleRed, RoundedCornerShape(6.dp)).padding(8.dp),
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${cart.sumOf { it.quantity }}点", fontSize = 17.sp)
                        Spacer(Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End) {
                            Text("消費税 ${yen(summary.taxAmount)}", fontSize = 14.sp, color = Color.Gray)
                            Text("合計 ${yen(summary.grossAmount)}", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Navy)
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.weight(0.62f).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Border),
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("おすすめ", "ドリンク", "料理", "物販").forEachIndexed { index, label ->
                            Button(
                                onClick = {},
                                modifier = Modifier.weight(1f).height(42.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (index == 0) Blue else Color(0xFFECEFF1),
                                    contentColor = if (index == 0) Color.White else Navy,
                                ),
                            ) { Text(label, fontSize = 14.sp) }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        products.chunked(3).forEach { row ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { product ->
                                    Button(
                                        onClick = { onAddProduct(product) },
                                        modifier = Modifier.weight(1f).height(92.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = productColor(product.taxCategory),
                                            contentColor = Navy,
                                        ),
                                        border = BorderStroke(1.dp, Border),
                                    ) {
                                        Text(
                                            "${product.name}\n${yen(product.unitPrice)} ${product.taxCategory.symbol}",
                                            textAlign = TextAlign.Center,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = onCancelTransaction,
                            enabled = cart.isNotEmpty(),
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) { Text("取引中止", color = Danger, fontWeight = FontWeight.Bold) }
                        OutlinedButton(
                            onClick = onHold,
                            enabled = cart.isNotEmpty(),
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) { Text("保留", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(74.dp).padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onTickets, modifier = Modifier.width(230.dp).fillMaxHeight()) {
                Text("伝票一覧", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onPayment,
                enabled = cart.isNotEmpty(),
                modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = ButtonDefaults.buttonColors(containerColor = Blue),
            ) {
                Text("小計／会計  ${yen(summary.grossAmount)}", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun TicketListScreen(
    tickets: List<HeldTicket>,
    onBack: () -> Unit,
    onRetrieve: (Long) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Header("SCR-200", "伝票一覧")
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            Text("保留伝票", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Navy)
            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Border),
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(14.dp).verticalScroll(rememberScrollState())) {
                    if (tickets.isEmpty()) {
                        Text("保留伝票はありません", color = Color.Gray, modifier = Modifier.padding(20.dp))
                    }
                    tickets.forEach { ticket ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(ticket.name, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    "担当 ${ticket.operatorName}  ${formatDateTime(ticket.createdAt)}  ${ticket.itemCount}点",
                                    color = Color.Gray,
                                    fontSize = 14.sp,
                                )
                            }
                            Text(yen(ticket.totalAmount), fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Navy)
                            Spacer(Modifier.width(20.dp))
                            Button(onClick = { onRetrieve(ticket.id) }, colors = ButtonDefaults.buttonColors(containerColor = Blue)) {
                                Text("呼出")
                            }
                        }
                        HorizontalDivider(color = Border)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onBack, modifier = Modifier.width(200.dp).height(54.dp)) {
                Text("販売画面へ戻る", fontSize = 17.sp)
            }
        }
    }
}

@Composable
private fun PaymentScreen(
    cart: List<CartItem>,
    onBack: () -> Unit,
    onComplete: (paymentMethod: String, deposit: Long, change: Long) -> Unit,
) {
    val summary = TaxEngine.calculate(cart)
    var input by remember { mutableStateOf("") }
    var deposit by remember { mutableStateOf(0L) }
    val change = (deposit - summary.grossAmount).coerceAtLeast(0)

    fun appendDigit(digit: Int) {
        if (input.length < 10) input += digit.toString()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Header("SCR-300", "会計画面")
        Row(
            modifier = Modifier.weight(1f).padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Card(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Border),
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                    Text("税率別内訳", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Navy)
                    Spacer(Modifier.height(14.dp))
                    summary.buckets.forEach { bucket ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                            Text(bucket.category.displayName, modifier = Modifier.weight(1f), fontSize = 17.sp)
                            Text("対象 ${yen(bucket.netAmount)}", modifier = Modifier.width(170.dp), fontSize = 16.sp)
                            Text("税 ${yen(bucket.taxAmount)}", modifier = Modifier.width(140.dp), fontSize = 16.sp)
                            Text(yen(bucket.grossAmount), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Border)
                    PaymentAmountRow("税抜等合計", yen(summary.netAmount), 20.sp)
                    PaymentAmountRow("消費税等", yen(summary.taxAmount), 20.sp)
                    PaymentAmountRow("合計", yen(summary.grossAmount), 30.sp)
                    Spacer(Modifier.weight(1f))
                    Text("税区分別合計に対して1円未満切捨て", color = Color.Gray, fontSize = 13.sp)
                }
            }

            Column(modifier = Modifier.width(420.dp).fillMaxHeight()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Border),
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        PaymentAmountRow("合計", yen(summary.grossAmount), 30.sp)
                        PaymentAmountRow("お預り", yen(deposit), 23.sp)
                        PaymentAmountRow("お釣り", yen(change), 28.sp)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Card(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Border),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(54.dp).background(PaleBlue, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            Text(
                                if (input.isEmpty()) "0" else input,
                                modifier = Modifier.padding(horizontal = 14.dp),
                                fontSize = 27.sp,
                                fontWeight = FontWeight.Bold,
                                color = Navy,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        (1..9).toList().chunked(3).forEach { row ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { digit ->
                                    OutlinedButton(onClick = { appendDigit(digit) }, modifier = Modifier.weight(1f).height(48.dp)) {
                                        Text(digit.toString(), fontSize = 18.sp)
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { input = "" }, modifier = Modifier.weight(1f).height(48.dp)) {
                                Text("C", color = Danger)
                            }
                            OutlinedButton(onClick = { appendDigit(0) }, modifier = Modifier.weight(1f).height(48.dp)) {
                                Text("0")
                            }
                            Button(
                                onClick = {
                                    deposit = input.toLongOrNull() ?: summary.grossAmount
                                    input = ""
                                },
                                modifier = Modifier.weight(1.5f).height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Blue),
                            ) { Text("現金") }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { onComplete("カード", summary.grossAmount, 0) },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) {
                            Text("カード（残額全額）", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 18.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.width(180.dp).fillMaxHeight()) {
                Text("戻る", fontSize = 18.sp)
            }
            Button(
                onClick = { onComplete("現金", deposit, change) },
                enabled = deposit >= summary.grossAmount,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = ButtonDefaults.buttonColors(containerColor = Blue),
            ) {
                Text("現金で会計確定", fontSize = 23.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PaymentAmountRow(label: String, value: String, fontSize: androidx.compose.ui.unit.TextUnit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Bottom) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 17.sp, color = Color.Gray)
        Text(value, fontSize = fontSize, fontWeight = FontWeight.Bold, color = Navy)
    }
}

@Composable
private fun CompleteScreen(
    saleId: Long,
    total: Long,
    paymentMethod: String,
    change: Long,
    onNext: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Header("SCR-320", "会計完了")
        Column(
            modifier = Modifier.fillMaxSize().padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("会計を確定しました", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Navy)
            Spacer(Modifier.height(14.dp))
            Text("売上番号 $saleId  ／  $paymentMethod", color = Color.Gray, fontSize = 17.sp)
            Spacer(Modifier.height(28.dp))
            Card(
                modifier = Modifier.width(560.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Border),
            ) {
                Column(modifier = Modifier.padding(28.dp)) {
                    PaymentAmountRow("合計", yen(total), 30.sp)
                    if (paymentMethod == "現金") PaymentAmountRow("お釣り", yen(change), 34.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("売上と明細スナップショットをSQLiteへ保存済み", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onNext,
                modifier = Modifier.width(320.dp).height(60.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Blue),
            ) {
                Text("次の取引", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun productColor(category: TaxCategory): Color = when (category) {
    TaxCategory.NON_TAXABLE -> Color(0xFFECEFF1)
    TaxCategory.INCLUDED_10 -> PaleGreen
    TaxCategory.EXCLUDED_10 -> PaleBlue
    TaxCategory.INCLUDED_8 -> PaleYellow
    TaxCategory.EXCLUDED_8 -> Color(0xFFFFE0B2)
}

private fun formatDateTime(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis)
    .atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("MM/dd HH:mm"))

private fun yen(value: Long): String = NumberFormat.getCurrencyInstance(Locale.JAPAN).format(value)
