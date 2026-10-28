package jp.co.tenposinfo.register

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import java.util.UUID

/**
 * レジマイナス。販売と同じ商品グリッド/テンキーを再利用するが、
 * 通常販売カートとは完全分離して ManualReturnStore に負数を確定する。
 */
class ManualReturnActivityV135 : ComponentActivity() {
    private lateinit var coordinator: ManualReturnCoordinatorV135

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureRegisterSystemBars(window)
        window.statusBarColor = android.graphics.Color.rgb(180, 35, 24)
        window.navigationBarColor = android.graphics.Color.rgb(180, 35, 24)
        coordinator = ManualReturnCoordinatorV135(applicationContext)
        setContent {
            RegisterSafeAreaV141(topBarColor = Color(0xFFB42318)) {
                MaterialTheme {
                    RegisterMinusSalesV158(coordinator = coordinator, onExit = ::finish)
                }
            }
        }
    }

    override fun onDestroy() {
        coordinator.close()
        super.onDestroy()
    }
}

@Composable
private fun RegisterMinusSalesV158(
    coordinator: ManualReturnCoordinatorV135,
    onExit: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val products = remember { coordinator.products() }
    val cart = remember { mutableStateListOf<CartItem>() }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var showCheckout by remember { mutableStateOf(false) }
    // Stable for retries of the same checkout attempt, including activity recreation.
    var checkoutRequestId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var showExit by remember { mutableStateOf(false) }
    var refundMethod by remember { mutableStateOf(ManualRefundMethodV135.CASH) }
    var reason by remember { mutableStateOf("") }
    var managerPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var completed by remember { mutableStateOf<ManualReturnResultV135?>(null) }
    val submissionGate = remember { ManualReturnSubmissionGateV135() }
    val session = OperatorSessionRegistry.current(context.applicationContext)
    val canApproveWithoutPin = ManagerApprovalV145.canUseCurrentManagerSession(context.applicationContext)
    val total = remember(cart.toList()) { TaxEngine.calculate(cart.toList()).grossAmount }

    fun exitSafely() {
        if (cart.isNotEmpty()) showExit = true else onExit()
    }

    BackHandler(enabled = cart.isNotEmpty() && !showCheckout && completed == null) {
        exitSafely()
    }

    if (session == null || !session.allows(RegisterPermission.REVERSAL)) {
        RegisterModalV154(
            onDismissRequest = {},
            title = { Text("レジマイナスを利用できません") },
            text = { Text("担当者の返品・取消権限とログイン状態を確認してください") },
            confirmButton = { RegisterModalConfirmV154("販売に戻る", onExit) },
            dismissButton = {},
            destructive = true,
        )
        return
    }

    SalesScreen(
        operatorName = session.name,
        products = products,
        cart = cart,
        corrections = emptyList(),
        selectedIndex = selectedIndex,
        printerHealth = PrinterHealthSnapshot.checking(),
        onSelect = { selectedIndex = it },
        onEdit = { selectedIndex = it },
        onAddProduct = { product, requested ->
            runCatching {
                product.quantityMode.requireAllowed(requested)
                val index = cart.indexOfFirst { it.product.id == product.id }
                val newQty = Math.addExact(if (index >= 0) cart[index].quantityHundredths else 0L, requested)
                product.quantityMode.requireAllowed(newQty)
                val newLine = CartItem(
                    product = product,
                    quantity = QuantityCompatibilityV136.legacyPositiveInt(newQty),
                    quantityHundredths = newQty,
                    lineId = if (index >= 0) cart[index].lineId else CartLineIdentityV135.newId(),
                )
                if (index >= 0) cart[index] = newLine else cart.add(newLine)
                selectedIndex = null
            }.onFailure { error = it.message ?: "商品を追加できませんでした" }
        },
        onChangeQuantity = { requested ->
            selectedIndex?.let { index ->
                cart.getOrNull(index)?.let { line ->
                    runCatching {
                        line.product.quantityMode.requireAllowed(requested)
                        cart[index] = line.copy(
                            quantity = QuantityCompatibilityV136.legacyPositiveInt(requested),
                            quantityHundredths = requested,
                        )
                    }.onFailure { error = it.message ?: "数量を変更できませんでした" }
                }
            }
        },
        onRemove = {
            if (cart.isNotEmpty()) cart.removeAt(cart.lastIndex)
            selectedIndex = null
        },
        onCancelSelected = { requested ->
            selectedIndex?.let { index ->
                cart.getOrNull(index)?.let { line ->
                    val remaining = line.quantityHundredths - requested
                    if (remaining < 0L) {
                        error = "取消数量が登録数量を超えています"
                    } else if (remaining == 0L) {
                        cart.removeAt(index)
                    } else {
                        runCatching {
                            line.product.quantityMode.requireAllowed(remaining)
                            cart[index] = line.copy(
                                quantity = QuantityCompatibilityV136.legacyPositiveInt(remaining),
                                quantityHundredths = remaining,
                            )
                        }.onFailure { error = it.message ?: "数量を変更できませんでした" }
                    }
                }
            }
            selectedIndex = null
        },
        onCancelTransaction = {
            cart.clear()
            selectedIndex = null
            checkoutRequestId = UUID.randomUUID().toString()
        },
        onDiscount = {},
        onTickets = {},
        onHold = {},
        onPayment = {
            if (cart.isNotEmpty()) {
                // Reopening a failed/uncertain checkout must retry the same operation.
                // A new ID is created only when the operator explicitly clears the cart.
                showCheckout = true
            }
        },
        onSalesHistory = {},
        onPrintQueue = {},
        onPrinterStatus = {},
        canOpenSettings = false,
        canOpenManagement = false,
        onOpenSettings = {},
        onOpenCatalogSettings = {},
        onOpenManagement = {},
        accessMessage = error,
        onLogout = ::exitSafely,
        minusMode = true,
    )

    if (showCheckout) {
        RegisterModalV154(
            onDismissRequest = {},
            title = { Text("レジマイナス会計", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 310.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("商品 " + cart.size + "種類 ／ マイナス数量 " +
                        QuantityCompatibilityV136.textOrZero(cart.sumOf { it.quantityHundredths }) + "点")
                    Text("返金額  -" + java.text.NumberFormat.getCurrencyInstance(java.util.Locale.JAPAN).format(total),
                        color = Color(0xFFB42318), fontWeight = FontWeight.Bold)
                    Text("返金方法", fontWeight = FontWeight.Bold)
                    ManualRefundMethodV135.entries.forEach { method ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = refundMethod == method, onClick = { refundMethod = method })
                            Text(method.displayName)
                        }
                    }
                    OutlinedTextField(
                        value = reason, onValueChange = { reason = it },
                        label = { Text("理由（任意）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!canApproveWithoutPin) {
                        OutlinedTextField(
                            value = managerPin, onValueChange = { managerPin = it },
                            label = { Text("責任者PIN") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (refundMethod != ManualRefundMethodV135.CASH) {
                        Text("外部決済端末の返金操作は別途必要です。端末側の返金完了を確認してください。",
                            color = Color(0xFFB42318))
                    }
                    if (error != null) Text(error.orEmpty(), color = Color(0xFFB42318))
                }
            },
            confirmButton = {
                RegisterModalConfirmV154(
                    label = "マイナスを確定",
                    destructive = true,
                    enabled = !submissionGate.isSubmitting() && total > 0L,
                    onClick = {
                        if (submissionGate.tryStart()) {
                            runCatching {
                                val lines = cart.toList().map { line ->
                                    ManualReturnLineRequestV135(
                                        product = line.product,
                                        quantity = line.quantity,
                                        quantityHundredths = line.quantityHundredths,
                                    )
                                }
                                val request = ManualReturnRequestV135(lines, reason, refundMethod)
                                ManualReturnPolicyV135.toPositiveCartItems(request, reasonRequired = false)
                                coordinator.create(request, managerPin, checkoutRequestId)
                            }.onSuccess {
                                completed = it
                                showCheckout = false
                                cart.clear()
                                selectedIndex = null
                                error = null
                            }.onFailure {
                                error = it.message ?: "マイナスを確定できませんでした"
                                submissionGate.release()
                            }
                        }
                    },
                )
            },
            dismissButton = {
                RegisterModalCancelV154(onClick = { showCheckout = false; error = null })
            },
            destructive = true,
        )
    }

    if (showExit) {
        RegisterModalV154(
            onDismissRequest = {},
            title = { Text("レジマイナスを終了しますか？") },
            text = { Text("入力途中の商品は破棄されます。通常販売のカートは変更しません。") },
            confirmButton = {
                RegisterModalConfirmV154(label = "終了する", destructive = true, onClick = onExit)
            },
            dismissButton = { RegisterModalCancelV154(onClick = { showExit = false }) },
            destructive = true,
        )
    }

    completed?.let { result ->
        RegisterModalV154(
            onDismissRequest = {},
            title = { Text("レジマイナス完了") },
            text = {
                Text(
                    "マイナスNo. MR-" + result.manualReturnId +
                    "\n返金額 " + java.text.NumberFormat.getCurrencyInstance(java.util.Locale.JAPAN)
                        .format(result.signedGrossAmount) +
                    "\n印刷ジョブ No." + result.printJobId +
                    (if (refundMethod == ManualRefundMethodV135.CASH) {
                        "\n現金返金額とドロアを確認してください。"
                    } else {
                        "\n外部端末の返金操作は別途確認してください。"
                    })
                )
            },
            confirmButton = {
                RegisterModalConfirmV154(label = "販売へ戻る", onClick = onExit)
            },
            dismissButton = {},
            destructive = true,
        )
    }
}
