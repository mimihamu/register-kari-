package jp.co.tenposinfo.register

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    PAYMENT,
    COMPLETE,
}

private data class Product(
    val id: String,
    val name: String,
    val price: Long,
    val taxSymbol: String,
    val buttonColor: Color,
)

private data class CartRow(
    val product: Product,
    val quantity: Int,
)

@Composable
private fun RegisterApp() {
    var screen by remember { mutableStateOf(AppScreen.DIAGNOSTIC) }
    var operatorName by remember { mutableStateOf("未選択") }
    val cart = remember { mutableStateListOf<Product>() }
    var deposit by remember { mutableStateOf(0L) }
    var completedTotal by remember { mutableStateOf(0L) }
    var completedChange by remember { mutableStateOf(0L) }

    val products = remember {
        listOf(
            Product("P0001", "生ビール", 600, "内", PaleGreen),
            Product("P0002", "ハイボール", 520, "内", PaleYellow),
            Product("P0003", "ウーロン茶", 300, "内", PaleBlue),
            Product("P0010", "枝豆", 420, "内", PaleGreen),
            Product("P0011", "唐揚げ", 680, "内", PaleYellow),
            Product("P0012", "刺身盛合せ", 1680, "内", PaleBlue),
            Product("P0020", "焼き鳥", 180, "内", PaleGreen),
            Product("P0021", "弁当", 800, "外※", PaleYellow),
            Product("P0022", "お土産", 1200, "外", PaleBlue),
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Background) {
        when (screen) {
            AppScreen.DIAGNOSTIC -> DiagnosticScreen(
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
                onAddProduct = { cart.add(it) },
                onRemoveLast = { if (cart.isNotEmpty()) cart.removeAt(cart.lastIndex) },
                onCancelTransaction = { cart.clear() },
                onPayment = {
                    deposit = 0
                    screen = AppScreen.PAYMENT
                },
            )

            AppScreen.PAYMENT -> PaymentScreen(
                cart = cart,
                deposit = deposit,
                onDepositChange = { deposit = it },
                onBack = { screen = AppScreen.SALES },
                onComplete = { total, change ->
                    completedTotal = total
                    completedChange = change
                    cart.clear()
                    screen = AppScreen.COMPLETE
                },
            )

            AppScreen.COMPLETE -> CompleteScreen(
                total = completedTotal,
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
        Text(
            text = "REGISTER",
            color = Color.White,
            fontSize = 23.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(24.dp))
        Text(
            text = "$screenId  $title",
            color = Color.White,
            fontSize = 21.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "営業日 ${LocalDate.now()}  ${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))}",
            color = Color.White,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun DiagnosticScreen(onComplete: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Header("SCR-001", "起動・自己診断")
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("起動チェックを実行しました", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Navy)
            Spacer(Modifier.height(24.dp))
            val checks = listOf(
                "データベース" to "正常",
                "営業日" to "未開始",
                "プリンタ" to "未設定（販売可能）",
                "バックアップ" to "初回作成前",
                "Google Drive" to "未接続（販売可能）",
            )
            Card(
                modifier = Modifier.width(680.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Border),
            ) {
                checks.forEach { (label, value) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 22.dp, vertical = 14.dp),
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
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
    cart: List<Product>,
    onAddProduct: (Product) -> Unit,
    onRemoveLast: () -> Unit,
    onCancelTransaction: () -> Unit,
    onPayment: () -> Unit,
) {
    val grouped = cart.groupingBy { it.id }.eachCount().map { (id, count) ->
        CartRow(cart.first { it.id == id }, count)
    }
    val total = cart.sumOf { it.price }

    Column(modifier = Modifier.fillMaxSize()) {
        Header("SCR-100", "販売画面")
        Row(
            modifier = Modifier.fillMaxWidth().height(38.dp).background(Color.White).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("店舗：サンプル居酒屋  |  担当：$operatorName  |  伝票：未選択", color = Navy, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text("印刷・同期：販売継続可能", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
        }

        Row(
            modifier = Modifier.weight(1f).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                modifier = Modifier.weight(0.32f).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Border),
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                    Text("注文一覧", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Navy)
                    Spacer(Modifier.height(10.dp))
                    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        if (grouped.isEmpty()) {
                            Text("商品ボタンを押して登録してください", color = Color.Gray, modifier = Modifier.padding(12.dp))
                        }
                        grouped.forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(row.product.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                    Text("${row.quantity} × ${yen(row.product.price)}", color = Color.Gray, fontSize = 13.sp)
                                }
                                Text(row.product.taxSymbol, color = Blue, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                                Text(yen(row.product.price * row.quantity), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${cart.size}点", fontSize = 17.sp)
                        Spacer(Modifier.weight(1f))
                        Text("小計 ${yen(total)}", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Navy)
                    }
                }
            }

            Card(
                modifier = Modifier.weight(0.24f).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Border),
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    Text("置数・機能", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Navy)
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier.fillMaxWidth().height(54.dp).background(PaleBlue, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Text("0", modifier = Modifier.padding(horizontal = 16.dp), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Navy)
                    }
                    Spacer(Modifier.height(10.dp))
                    (1..9).toList().chunked(3).forEach { row ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { number ->
                                OutlinedButton(onClick = {}, modifier = Modifier.weight(1f).height(48.dp)) {
                                    Text(number.toString(), fontSize = 18.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("数量", "訂正", "値引").forEach { label ->
                            OutlinedButton(
                                onClick = { if (label == "訂正") onRemoveLast() },
                                modifier = Modifier.weight(1f).height(46.dp),
                            ) { Text(label, fontSize = 14.sp) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("客数", "伝票", "検索").forEach { label ->
                            OutlinedButton(onClick = {}, modifier = Modifier.weight(1f).height(46.dp)) {
                                Text(label, fontSize = 14.sp)
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = onCancelTransaction,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFBE9E7), contentColor = Danger),
                    ) { Text("取引中止", fontWeight = FontWeight.Bold) }
                }
            }

            Card(
                modifier = Modifier.weight(0.44f).fillMaxHeight(),
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
                                            containerColor = product.buttonColor,
                                            contentColor = Navy,
                                        ),
                                        border = BorderStroke(1.dp, Border),
                                    ) {
                                        Text(
                                            text = "${product.name}\n${yen(product.price)} ${product.taxSymbol}",
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
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(74.dp).padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = {}, modifier = Modifier.width(210.dp).fillMaxHeight()) {
                Text("伝票一覧", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = {}, modifier = Modifier.width(180.dp).fillMaxHeight()) {
                Text("保留", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onPayment,
                enabled = cart.isNotEmpty(),
                modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = ButtonDefaults.buttonColors(containerColor = Blue),
            ) {
                Text("小計／会計  ${yen(total)}", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PaymentScreen(
    cart: List<Product>,
    deposit: Long,
    onDepositChange: (Long) -> Unit,
    onBack: () -> Unit,
    onComplete: (Long, Long) -> Unit,
) {
    val total = cart.sumOf { it.price }
    val change = (deposit - total).coerceAtLeast(0)
    var input by remember { mutableStateOf("") }

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
                    Text("会計内訳", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Navy)
                    Spacer(Modifier.height(14.dp))
                    cart.groupingBy { it.name }.eachCount().forEach { (name, qty) ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                            Text(name, modifier = Modifier.weight(1f), fontSize = 17.sp)
                            Text("$qty 点", fontSize = 17.sp)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("税込・税率別計算は次段階でDB確定処理へ接続", color = Color.Gray, fontSize = 13.sp)
                }
            }

            Column(modifier = Modifier.width(400.dp).fillMaxHeight()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Border),
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        PaymentAmountRow("合計", yen(total), 30.sp)
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
                                    val value = input.toLongOrNull() ?: total
                                    onDepositChange(value)
                                    input = ""
                                },
                                modifier = Modifier.weight(1.5f).height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Blue),
                            ) { Text("現金") }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                onDepositChange(total)
                                input = ""
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) {
                            Text("金額未入力：ちょうど現金", fontWeight = FontWeight.Bold)
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
                onClick = { onComplete(total, change) },
                enabled = deposit >= total,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = ButtonDefaults.buttonColors(containerColor = Blue),
            ) {
                Text("会計確定", fontSize = 23.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PaymentAmountRow(label: String, value: String, size: androidx.compose.ui.unit.TextUnit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 18.sp, color = Color.Gray)
        Text(value, fontSize = size, fontWeight = FontWeight.Bold, color = Navy)
    }
}

@Composable
private fun CompleteScreen(total: Long, change: Long, onNext: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Header("SCR-320", "会計完了")
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("会計が完了しました", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Navy)
            Spacer(Modifier.height(28.dp))
            Card(
                modifier = Modifier.width(620.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Border),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("合計 ${yen(total)}", fontSize = 26.sp, color = Color.Gray)
                    Spacer(Modifier.height(16.dp))
                    Text("お釣り ${yen(change)}", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = Navy)
                    Spacer(Modifier.height(12.dp))
                    Text("レシート印刷：開発用ダミー完了", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onNext,
                modifier = Modifier.width(340.dp).height(62.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Blue),
            ) {
                Text("次の取引へ", fontSize = 21.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun yen(value: Long): String {
    val formatter = NumberFormat.getCurrencyInstance(Locale.JAPAN)
    formatter.maximumFractionDigits = 0
    return formatter.format(value)
}
