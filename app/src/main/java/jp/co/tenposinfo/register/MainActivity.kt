package jp.co.tenposinfo.register

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Navy = Color(0xFF173F6B)
private val Blue = Color(0xFF1976B9)
private val PaleBlue = Color(0xFFEAF3FA)
private val PaleGreen = Color(0xFFEAF5EC)
private val PaleYellow = Color(0xFFFFF4D9)
private val Danger = Color(0xFFC62828)
private val Warning = Color(0xFFFFE0B2)
private val Background = Color(0xFFF4F7FA)
private val Border = Color(0xFFD5DEE7)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                RegisterApp()
            }
        }
    }
}

private enum class AppScreen {
    DIAGNOSTIC,
    LOGIN,
    SALES,
    LINE_EDIT,
    DISCOUNT,
    TICKETS,
    PAYMENT,
    COMPLETE,
}

@Composable
private fun RegisterApp() {
    val context = LocalContext.current
    val database = remember { RegisterDatabase(context.applicationContext) }
    var screen by remember { mutableStateOf(AppScreen.DIAGNOSTIC) }
    var operatorName by remember { mutableStateOf("未選択") }
    val products = remember { database.loadProducts() }
    val cart = remember { mutableStateListOf<CartItem>().apply { addAll(database.loadCart()) } }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var paymentState by remember { mutableStateOf(PaymentState()) }
    var completedTotal by remember { mutableStateOf(0L) }
    var completedChange by remember { mutableStateOf(0L) }
    var completedPayments by remember { mutableStateOf(emptyList<PaymentAllocation>()) }

    fun replaceCart(items: List<CartItem>) {
        cart.clear()
        cart.addAll(items)
        selectedIndex = null
        database.saveCart(cart.toList())
    }

    fun updateCartItem(index: Int, item: CartItem) {
        if (index !in cart.indices) return
        cart[index] = item
        selectedIndex = index
        database.saveCart(cart.toList())
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Background) {
        when (screen) {
            AppScreen.DIAGNOSTIC -> DiagnosticScreen(
                restoredCount = cart.sumOf { it.quantity },
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
                selectedIndex = selectedIndex,
                onSelect = { selectedIndex = it },
                onEdit = {
                    selectedIndex = it
                    screen = AppScreen.LINE_EDIT
                },
                onAddProduct = { product ->
                    val index = cart.indexOfFirst {
                        it.product.id == product.id &&
                            it.unitPrice == product.unitPrice &&
                            it.discountAmount == 0L &&
                            it.note.isEmpty()
                    }
                    if (index >= 0) {
                        cart[index] = cart[index].copy(quantity = cart[index].quantity + 1)
                    } else {
                        cart += CartItem(product = product, quantity = 1)
                    }
                    database.saveCart(cart.toList())
                },
                onChangeQuantity = { quantity ->
                    val index = selectedIndex
                    if (index != null && index in cart.indices && quantity > 0) {
                        updateCartItem(index, cart[index].copy(quantity = quantity))
                    }
                },
                onRemove = {
                    val index = selectedIndex ?: cart.lastIndex
                    if (index in cart.indices) {
                        cart.removeAt(index)
                        selectedIndex = null
                        database.saveCart(cart.toList())
                    }
                },
                onCancelTransaction = { replaceCart(emptyList()) },
                onDiscount = { screen = AppScreen.DISCOUNT },
                onTickets = { screen = AppScreen.TICKETS },
                onHold = {
                    if (cart.isNotEmpty()) {
                        val sequence = database.listHeldTickets().size + 1
                        database.holdCart("伝票$sequence", operatorName, cart.toList())
                        replaceCart(emptyList())
                    }
                },
                onPayment = {
                    paymentState = PaymentState()
                    screen = AppScreen.PAYMENT
                },
            )

            AppScreen.LINE_EDIT -> {
                val index = selectedIndex
                val item = index?.let { cart.getOrNull(it) }
                if (index == null || item == null) {
                    screen = AppScreen.SALES
                } else {
                    LineEditScreen(
                        item = item,
                        onSave = {
                            updateCartItem(index, it)
                            screen = AppScreen.SALES
                        },
                        onDiscount = { screen = AppScreen.DISCOUNT },
                        onBack = { screen = AppScreen.SALES },
                    )
                }
            }

            AppScreen.DISCOUNT -> DiscountScreen(
                items = cart,
                selectedIndex = selectedIndex,
                onApply = {
                    replaceCart(it)
                    screen = AppScreen.SALES
                },
                onBack = { screen = AppScreen.SALES },
            )

            AppScreen.TICKETS -> TicketListScreen(
                tickets = database.listHeldTickets(),
                onLoad = { ticket ->
                    replaceCart(database.loadHeldTicket(ticket.id))
                    database.deleteHeldTicket(ticket.id)
                    screen = AppScreen.SALES
                },
                onDelete = {
                    database.deleteHeldTicket(it.id)
                    screen = AppScreen.SALES
                    screen = AppScreen.TICKETS
                },
                onBack = { screen = AppScreen.SALES },
            )

            AppScreen.PAYMENT -> PaymentScreen(
                items = cart,
                state = paymentState,
                onStateChange = { paymentState = it },
                onBack = { screen = AppScreen.SALES },
                onComplete = {
                    val summary = TaxEngine.calculate(cart)
                    database.saveSale(operatorName, cart.toList(), paymentState)
                    completedTotal = summary.grossAmount
                    completedChange = paymentState.changeAmount
                    completedPayments = paymentState.allocations
                    replaceCart(emptyList())
                    screen = AppScreen.COMPLETE
                },
            )

            AppScreen.COMPLETE -> CompleteScreen(
                total = completedTotal,
                change = completedChange,
                payments = completedPayments,
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
private fun DiagnosticScreen(restoredCount: Int, onComplete: () -> Unit) {
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
                "データベース" to "正常（Schema v3）",
                "作業中取引" to if (restoredCount > 0) "${restoredCount}点を復元" else "なし",
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
            BlueButton("診断完了・担当者選択へ", onComplete, Modifier.width(320.dp).height(58.dp))
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
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        row.forEach { name ->
                            OutlinedButton(
                                onClick = { selected = name },
                                modifier = Modifier.weight(1f).height(82.dp),
                                border = BorderStroke(if (selected == name) 3.dp else 1.dp, if (selected == name) Danger else Border),
                            ) { Text(name, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Navy) }
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
                Column(Modifier.padding(22.dp)) {
                    Text("PIN入力", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Navy)
                    Spacer(Modifier.height(12.dp))
                    ValueBox(if (pin.isEmpty()) "選択のみでもログイン可" else "●".repeat(pin.length))
                    Spacer(Modifier.height(14.dp))
                    NumberPad(
                        onDigit = { if (pin.length < 8) pin += it },
                        onClear = { pin = "" },
                        bottomActionLabel = "ログイン",
                        onBottomAction = { onLogin(selected) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SalesScreen(
    operatorName: String,
    products: List<Product>,
    cart: List<CartItem>,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    onEdit: (Int) -> Unit,
    onAddProduct: (Product) -> Unit,
    onChangeQuantity: (Int) -> Unit,
    onRemove: () -> Unit,
    onCancelTransaction: () -> Unit,
    onDiscount: () -> Unit,
    onTickets: () -> Unit,
    onHold: () -> Unit,
    onPayment: () -> Unit,
) {
    val summary = TaxEngine.calculate(cart)
    var numberBuffer by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize()) {
        Header("SCR-100", "販売画面")
        Row(
            modifier = Modifier.fillMaxWidth().height(38.dp).background(Color.White).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("店舗：サンプル居酒屋  |  担当：$operatorName", color = Navy, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text("長押し：行編集", color = Blue, fontWeight = FontWeight.Bold)
        }
        Row(
            modifier = Modifier.weight(1f).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CardPanel(Modifier.weight(0.34f).fillMaxHeight()) {
                Text("注文一覧", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Navy)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    if (cart.isEmpty()) Text("商品ボタンを押して登録してください", color = Color.Gray, modifier = Modifier.padding(12.dp))
                    cart.forEachIndexed { index, item ->
                        val selected = selectedIndex == index
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (selected) PaleBlue else Color.Transparent, RoundedCornerShape(8.dp))
                                .combinedClickable(onClick = { onSelect(index) }, onLongClick = { onEdit(index) })
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.product.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                Text("${item.quantity} × ${yen(item.unitPrice)}  ${item.product.taxCategory.symbol}", color = Color.Gray, fontSize = 13.sp)
                                if (item.discountAmount > 0) Text("値引 -${yen(item.discountAmount)}", color = Danger, fontSize = 13.sp)
                                if (item.note.isNotBlank()) Text("メモ：${item.note}", color = Blue, fontSize = 12.sp)
                            }
                            Text(yen(item.baseAmount), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${cart.sumOf { it.quantity }}点", fontSize = 17.sp)
                    Spacer(Modifier.weight(1f))
                    Text("合計 ${yen(summary.grossAmount)}", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Navy)
                }
            }

            CardPanel(Modifier.weight(0.23f).fillMaxHeight()) {
                Text("置数・機能", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Navy)
                Spacer(Modifier.height(8.dp))
                ValueBox(if (numberBuffer.isEmpty()) "0" else numberBuffer)
                Spacer(Modifier.height(8.dp))
                CompactNumberPad(
                    value = numberBuffer,
                    onValueChange = { numberBuffer = it },
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = {
                            numberBuffer.toIntOrNull()?.let(onChangeQuantity)
                            numberBuffer = ""
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("数量") }
                    OutlinedButton(onClick = onRemove, modifier = Modifier.weight(1f)) { Text("訂正") }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onDiscount, modifier = Modifier.weight(1f)) { Text("値引") }
                    OutlinedButton(
                        onClick = { selectedIndex?.let(onEdit) },
                        enabled = selectedIndex != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("行編集") }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onCancelTransaction,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFBE9E7), contentColor = Danger),
                ) { Text("取引中止", fontWeight = FontWeight.Bold) }
            }

            CardPanel(Modifier.weight(0.43f).fillMaxHeight()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("おすすめ", "ドリンク", "料理", "物販").forEachIndexed { index, label ->
                        Button(
                            onClick = {},
                            modifier = Modifier.weight(1f).height(42.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (index == 0) Blue else Color(0xFFECEFF1),
                                contentColor = if (index == 0) Color.White else Navy,
                            ),
                        ) { Text(label, fontSize = 13.sp) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    products.chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { product ->
                                Button(
                                    onClick = { onAddProduct(product) },
                                    modifier = Modifier.weight(1f).height(90.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = when (product.displayOrder % 3) {
                                            0 -> PaleBlue
                                            1 -> PaleGreen
                                            else -> PaleYellow
                                        },
                                        contentColor = Navy,
                                    ),
                                    border = BorderStroke(1.dp, Border),
                                ) {
                                    Text(
                                        "${product.name}\n${yen(product.unitPrice)} ${product.taxCategory.symbol}",
                                        textAlign = TextAlign.Center,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(74.dp).padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onTickets, modifier = Modifier.width(190.dp).fillMaxHeight()) { Text("伝票一覧", fontSize = 18.sp) }
            OutlinedButton(onClick = onHold, enabled = cart.isNotEmpty(), modifier = Modifier.width(160.dp).fillMaxHeight()) { Text("保留", fontSize = 18.sp) }
            Button(
                onClick = onPayment,
                enabled = cart.isNotEmpty(),
                modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = ButtonDefaults.buttonColors(containerColor = Blue),
            ) { Text("小計／会計  ${yen(summary.grossAmount)}", fontSize = 23.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun LineEditScreen(
    item: CartItem,
    onSave: (CartItem) -> Unit,
    onDiscount: () -> Unit,
    onBack: () -> Unit,
) {
    var quantity by remember(item) { mutableStateOf(item.quantity.toString()) }
    var unitPrice by remember(item) { mutableStateOf(item.unitPrice.toString()) }
    var discount by remember(item) { mutableStateOf(item.discountAmount.toString()) }
    var note by remember(item) { mutableStateOf(item.note) }
    var category by remember(item) { mutableStateOf(item.product.taxCategory) }
    val q = quantity.toIntOrNull() ?: 0
    val price = unitPrice.toLongOrNull() ?: 0
    val disc = discount.toLongOrNull() ?: 0
    val valid = q > 0 && price >= 0 && disc in 0..(price * q)

    Column(Modifier.fillMaxSize()) {
        Header("SCR-120", "行編集")
        Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            CardPanel(Modifier.weight(1f).fillMaxHeight()) {
                Text(item.product.name, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Navy)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    NumericField("数量", quantity, { quantity = it }, Modifier.weight(1f))
                    NumericField("単価", unitPrice, { unitPrice = it }, Modifier.weight(1f))
                    NumericField("行値引", discount, { discount = it }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(18.dp))
                Text("税区分", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                TaxCategory.values().chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { tax ->
                            OutlinedButton(
                                onClick = { category = tax },
                                modifier = Modifier.weight(1f),
                                border = BorderStroke(if (category == tax) 3.dp else 1.dp, if (category == tax) Danger else Border),
                            ) { Text("${tax.displayName} ${tax.symbol}") }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { if (it.length <= 100) note = it },
                    label = { Text("行メモ") },
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "変更後金額：${yen((price * q - disc).coerceAtLeast(0))}",
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Bold,
                    color = Navy,
                )
            }
            Column(Modifier.width(260.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDiscount, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("値引・割引設定") }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("戻る") }
                BlueButton(
                    "保存",
                    {
                        onSave(
                            item.copy(
                                product = item.product.copy(taxCategory = category),
                                quantity = q,
                                unitPrice = price,
                                discountAmount = disc,
                                note = note.trim(),
                            ),
                        )
                    },
                    Modifier.fillMaxWidth().height(64.dp),
                    enabled = valid,
                )
            }
        }
    }
}

@Composable
private fun DiscountScreen(
    items: List<CartItem>,
    selectedIndex: Int?,
    onApply: (List<CartItem>) -> Unit,
    onBack: () -> Unit,
) {
    var scope by remember { mutableStateOf(if (selectedIndex != null) DiscountScope.ITEM else DiscountScope.TRANSACTION) }
    var type by remember { mutableStateOf(DiscountType.FIXED) }
    var input by remember { mutableStateOf("") }
    val value = if (type == DiscountType.FIXED) input.toLongOrNull() else percentToBasisPoints(input)
    val preview = try {
        when {
            value == null -> items
            scope == DiscountScope.ITEM && selectedIndex != null && selectedIndex in items.indices ->
                items.toMutableList().also { it[selectedIndex] = DiscountEngine.applyToItem(it[selectedIndex], type, value) }
            scope == DiscountScope.TRANSACTION -> DiscountEngine.applyToTransaction(items, type, value)
            else -> items
        }
    } catch (_: IllegalArgumentException) {
        items
    }
    val before = TaxEngine.calculate(items)
    val after = TaxEngine.calculate(preview)

    Column(Modifier.fillMaxSize()) {
        Header("SCR-121", "値引・割引設定")
        Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            CardPanel(Modifier.weight(1f).fillMaxHeight()) {
                Text("対象", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Navy)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChoiceButton("商品", scope == DiscountScope.ITEM, selectedIndex != null, Modifier.weight(1f)) { scope = DiscountScope.ITEM }
                    ChoiceButton("伝票", scope == DiscountScope.TRANSACTION, true, Modifier.weight(1f)) { scope = DiscountScope.TRANSACTION }
                    ChoiceButton("部門（次版）", false, false, Modifier.weight(1f)) {}
                }
                Spacer(Modifier.height(18.dp))
                Text("方式", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Navy)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChoiceButton("定額値引", type == DiscountType.FIXED, true, Modifier.weight(1f)) { type = DiscountType.FIXED; input = "" }
                    ChoiceButton("率割引", type == DiscountType.PERCENT, true, Modifier.weight(1f)) { type = DiscountType.PERCENT; input = "" }
                }
                Spacer(Modifier.height(18.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { if (it.length <= 10) input = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text(if (type == DiscountType.FIXED) "値引額（円）" else "割引率（%）") },
                    keyboardOptions = KeyboardOptions(keyboardType = if (type == DiscountType.FIXED) KeyboardType.Number else KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(18.dp))
                Text("税率別プレビュー", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Navy)
                Spacer(Modifier.height(8.dp))
                TaxPreview(after)
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth()) {
                    Text("変更前 ${yen(before.grossAmount)}", fontSize = 20.sp)
                    Spacer(Modifier.weight(1f))
                    Text("変更後 ${yen(after.grossAmount)}", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Navy)
                }
            }
            Column(Modifier.width(260.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CardPanel(Modifier.fillMaxWidth()) {
                    Text("権限確認", fontWeight = FontWeight.Bold, color = Navy)
                    Text("v0.3：販売担当に許可", color = Color.Gray)
                    Text("上限・責任者PINは設定画面実装時に接続", color = Color.Gray, fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("キャンセル") }
                BlueButton("適用", { onApply(preview) }, Modifier.fillMaxWidth().height(64.dp), enabled = value != null && preview != items)
            }
        }
    }
}

@Composable
private fun TicketListScreen(
    tickets: List<HeldTicket>,
    onLoad: (HeldTicket) -> Unit,
    onDelete: (HeldTicket) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Header("SCR-200", "伝票一覧")
        Column(Modifier.weight(1f).padding(24.dp).verticalScroll(rememberScrollState())) {
            if (tickets.isEmpty()) Text("保留伝票はありません", fontSize = 22.sp, color = Color.Gray)
            tickets.forEach { ticket ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Border),
                ) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("No.${ticket.id}  ${ticket.name}", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Navy)
                            Text("担当 ${ticket.operatorName}　${ticket.itemCount}点", color = Color.Gray)
                        }
                        Text(yen(ticket.totalAmount), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(18.dp))
                        OutlinedButton(onClick = { onDelete(ticket) }) { Text("削除", color = Danger) }
                        Spacer(Modifier.width(8.dp))
                        BlueButton("呼出", { onLoad(ticket) }, Modifier.width(130.dp).height(52.dp))
                    }
                }
            }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.padding(18.dp).width(180.dp).height(58.dp)) { Text("戻る") }
    }
}

@Composable
private fun PaymentScreen(
    items: List<CartItem>,
    state: PaymentState,
    onStateChange: (PaymentState) -> Unit,
    onBack: () -> Unit,
    onComplete: () -> Unit,
) {
    val summary = TaxEngine.calculate(items)
    val mixed = TaxEngine.validateMixedTax(items, MixedTaxPolicy.WARN)
    var input by remember { mutableStateOf("") }
    var mixedConfirmed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val remaining = state.remaining(summary.grossAmount)

    fun addPayment(method: PaymentMethod) {
        try {
            onStateChange(PaymentEngine.addPayment(state, summary.grossAmount, method, input.toLongOrNull()))
            input = ""
            error = null
        } catch (e: IllegalArgumentException) {
            error = e.message
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header("SCR-300 / SCR-310", "会計・支払追加")
        if (mixed.message != null) {
            Row(
                Modifier.fillMaxWidth().background(Warning).padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(mixed.message, modifier = Modifier.weight(1f), color = Danger, fontWeight = FontWeight.Bold)
                Button(onClick = { mixedConfirmed = true }, enabled = !mixedConfirmed) {
                    Text(if (mixedConfirmed) "確認済み" else "内容を確認")
                }
            }
        }
        Row(Modifier.weight(1f).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CardPanel(Modifier.weight(0.42f).fillMaxHeight()) {
                Text("会計内訳", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Navy)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    items.forEach { item ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Text("${item.product.name} ×${item.quantity}", Modifier.weight(1f))
                            if (item.discountAmount > 0) Text("-${yen(item.discountAmount)}  ", color = Danger)
                            Text(yen(item.baseAmount), fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TaxPreview(summary)
                }
                AmountRow("合計", summary.grossAmount, 29.sp)
            }

            CardPanel(Modifier.weight(0.30f).fillMaxHeight()) {
                Text("支払明細", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Navy)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    if (state.allocations.isEmpty()) Text("支払方法を選択してください", color = Color.Gray)
                    state.allocations.forEachIndexed { index, payment ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = PaleBlue),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(payment.method.displayName, fontWeight = FontWeight.Bold)
                                    Text("充当 ${yen(payment.appliedAmount)}", fontSize = 13.sp)
                                    if (payment.receivedAmount != payment.appliedAmount) Text("お預り ${yen(payment.receivedAmount)}", fontSize = 13.sp)
                                }
                                OutlinedButton(onClick = { onStateChange(PaymentEngine.removeAt(state, index)) }) { Text("取消", color = Danger) }
                            }
                        }
                    }
                }
                AmountRow("支払済", state.paidAmount, 20.sp)
                AmountRow("残額", remaining, 26.sp)
                AmountRow("お釣り", state.changeAmount, 26.sp)
            }

            CardPanel(Modifier.weight(0.28f).fillMaxHeight()) {
                Text("金額入力", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Navy)
                Spacer(Modifier.height(6.dp))
                ValueBox(if (input.isEmpty()) "残額全額" else input)
                Spacer(Modifier.height(8.dp))
                CompactNumberPad(input) { input = it }
                Spacer(Modifier.height(8.dp))
                PaymentMethod.values().forEach { method ->
                    Button(
                        onClick = { addPayment(method) },
                        enabled = remaining > 0,
                        modifier = Modifier.fillMaxWidth().height(48.dp).padding(vertical = 2.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (method == PaymentMethod.CASH) Blue else Navy),
                    ) { Text(method.displayName, fontWeight = FontWeight.Bold) }
                }
                if (error != null) Text(error.orEmpty(), color = Danger, fontSize = 12.sp)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 18.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.width(180.dp).fillMaxHeight()) { Text("戻る", fontSize = 18.sp) }
            Button(
                onClick = onComplete,
                enabled = remaining == 0L && (!mixed.hasMixedTax || mixedConfirmed),
                modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = ButtonDefaults.buttonColors(containerColor = Blue),
            ) { Text("会計確定", fontSize = 23.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun CompleteScreen(
    total: Long,
    change: Long,
    payments: List<PaymentAllocation>,
    onNext: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Header("SCR-320", "会計完了")
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("会計が完了しました", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Navy)
            Spacer(Modifier.height(20.dp))
            CardPanel(Modifier.width(650.dp)) {
                AmountRow("合計", total, 27.sp)
                payments.forEach { AmountRow(it.method.displayName, it.appliedAmount, 20.sp) }
                AmountRow("お釣り", change, 34.sp)
                Spacer(Modifier.height(10.dp))
                Text("売上・明細・支払内訳をSQLiteへ保存しました", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                Text("レシート印刷Gatewayは次段階で接続します", color = Color.Gray)
            }
            Spacer(Modifier.height(24.dp))
            BlueButton("次の取引", onNext, Modifier.width(320.dp).height(64.dp))
        }
    }
}

@Composable
private fun CardPanel(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Border),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), content = content)
    }
}

@Composable
private fun ValueBox(value: String) {
    Box(
        modifier = Modifier.fillMaxWidth().height(56.dp).background(PaleBlue, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(value, modifier = Modifier.padding(horizontal = 14.dp), fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Navy)
    }
}

@Composable
private fun CompactNumberPad(value: String, onValueChange: (String) -> Unit) {
    (1..9).toList().chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { digit ->
                OutlinedButton(
                    onClick = { if (value.length < 10) onValueChange(value + digit) },
                    modifier = Modifier.weight(1f).height(42.dp),
                ) { Text(digit.toString()) }
            }
        }
        Spacer(Modifier.height(5.dp))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { onValueChange("") }, modifier = Modifier.weight(1f).height(42.dp)) { Text("C", color = Danger) }
        OutlinedButton(onClick = { if (value.length < 10) onValueChange(value + "0") }, modifier = Modifier.weight(1f).height(42.dp)) { Text("0") }
        OutlinedButton(onClick = { if (value.length < 9) onValueChange(value + "00") }, modifier = Modifier.weight(1f).height(42.dp)) { Text("00") }
    }
}

@Composable
private fun NumberPad(
    onDigit: (String) -> Unit,
    onClear: () -> Unit,
    bottomActionLabel: String,
    onBottomAction: () -> Unit,
) {
    (1..9).toList().chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { digit ->
                OutlinedButton(onClick = { onDigit(digit.toString()) }, modifier = Modifier.weight(1f).height(50.dp)) { Text(digit.toString()) }
            }
        }
        Spacer(Modifier.height(7.dp))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f).height(50.dp)) { Text("C", color = Danger) }
        OutlinedButton(onClick = { onDigit("0") }, modifier = Modifier.weight(1f).height(50.dp)) { Text("0") }
        BlueButton(bottomActionLabel, onBottomAction, Modifier.weight(1.5f).height(50.dp))
    }
}

@Composable
private fun NumericField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 10) onValueChange(it.filter(Char::isDigit)) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun ChoiceButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(54.dp),
        border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) Danger else Border),
    ) { Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) }
}

@Composable
private fun TaxPreview(summary: TaxSummary) {
    summary.buckets.forEach { bucket ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(if (bucket.taxable) "${bucket.ratePercent}%対象" else "非課税", Modifier.weight(1f))
            Text("税 ${yen(bucket.taxAmount)}", color = Color.Gray)
            Spacer(Modifier.width(12.dp))
            Text(yen(bucket.grossAmount), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AmountRow(label: String, amount: Long, fontSize: androidx.compose.ui.unit.TextUnit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(label, modifier = Modifier.weight(1f), fontSize = (fontSize.value * 0.72f).sp)
        Text(yen(amount), fontSize = fontSize, fontWeight = FontWeight.Bold, color = Navy)
    }
}

@Composable
private fun BlueButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = Blue),
    ) { Text(label, fontWeight = FontWeight.Bold) }
}

private fun percentToBasisPoints(text: String): Long? = try {
    BigDecimal(text)
        .multiply(BigDecimal(100))
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact()
        .takeIf { it in 1..10_000 }
} catch (_: Exception) {
    null
}

private fun yen(value: Long): String = NumberFormat.getCurrencyInstance(Locale.JAPAN).format(value)
