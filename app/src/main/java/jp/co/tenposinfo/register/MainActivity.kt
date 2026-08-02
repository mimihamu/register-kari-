package jp.co.tenposinfo.register

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Date
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

internal object RegisterLayoutPolicy {
    const val DIAGNOSTIC_CARD_HEIGHT_DP = 280
    const val COMPACT_VALUE_HEIGHT_DP = 40
    const val COMPACT_KEY_HEIGHT_DP = 36
    const val COMPACT_KEY_GAP_DP = 2
    const val COMPACT_FUNCTION_HEIGHT_DP = 34

    fun salesUtilityRequiredHeightDp(panelPaddingDp: Int = 32): Int =
        40 + 4 +
            (COMPACT_KEY_HEIGHT_DP * 4 + COMPACT_KEY_GAP_DP * 3) + 4 +
            COMPACT_FUNCTION_HEIGHT_DP + 4 +
            COMPACT_FUNCTION_HEIGHT_DP +
            panelPaddingDp

    fun paymentControlsRequiredHeightDp(panelPaddingDp: Int = 32): Int =
        48 + 4 + 36 + COMPACT_VALUE_HEIGHT_DP + 4 +
            (COMPACT_KEY_HEIGHT_DP * 4 + COMPACT_KEY_GAP_DP * 3) + 4 +
            COMPACT_FUNCTION_HEIGHT_DP +
            panelPaddingDp
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(23, 63, 107)
        window.navigationBarColor = android.graphics.Color.WHITE
        WindowCompat.getInsetsController(window, window.decorView).apply {
  isAppearanceLightStatusBars = false
  isAppearanceLightNavigationBars = true
        }
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
    SALES_HISTORY,
    SALE_DETAIL,
    RECEIPT_PREVIEW,
    PRINT_QUEUE,
}

@Composable
private fun RegisterApp() {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val database = remember { RegisterDatabase(context.applicationContext) }
    val authStore = remember { OperatorAuthenticationStore(context.applicationContext) }
    var currentOperator by remember { mutableStateOf(OperatorSessionRegistry.current(context.applicationContext)) }
    var screen by remember { mutableStateOf(if (currentOperator == null) AppScreen.DIAGNOSTIC else AppScreen.SALES) }
    var operatorName by remember { mutableStateOf(currentOperator?.name ?: "未選択") }
    var loginMessage by remember { mutableStateOf<String?>(null) }
    var accessMessage by remember { mutableStateOf<String?>(null) }
    var printerHealth by remember { mutableStateOf(PrinterHealthSnapshot.checking()) }
    var catalogEpoch by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var activityResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
    }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> activityResumed = true
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE,
                androidx.lifecycle.Lifecycle.Event.ON_STOP,
                androidx.lifecycle.Lifecycle.Event.ON_DESTROY,
                -> activityResumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        DriveOutboxScheduler.ensurePeriodic(context.applicationContext)
        while (true) {
            kotlinx.coroutines.delay(5_000L)
            val refreshed = OperatorSessionRegistry.current(context.applicationContext)
            when {
                currentOperator != null && refreshed == null -> {
                    currentOperator = null
                    operatorName = "未選択"
                    loginMessage = "セッションが失効したか、担当者が停止・権限変更されました"
                    screen = AppScreen.LOGIN
                }
                refreshed != null -> {
                    currentOperator = refreshed
                    operatorName = refreshed.name
                }
            }
            catalogEpoch++
        }
    }
    androidx.compose.runtime.LaunchedEffect(screen, activityResumed) {
        if (!PrinterHealthUiPolicy.shouldPoll(screen == AppScreen.SALES, activityResumed)) {
            return@LaunchedEffect
        }
        while (true) {
            printerHealth = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                PrinterHealthMonitor.check(context.applicationContext)
            }
            kotlinx.coroutines.delay(10_000L)
        }
    }
    val products = remember(catalogEpoch) {
        runCatching {
            CatalogMasterStore(context.applicationContext).use { it.synchronizeEffectiveProducts() }
        }
        V11CatalogRuntime.visibleProducts(context.applicationContext, database.loadProducts())
    }
    val cart = remember { mutableStateListOf<CartItem>().apply { addAll(database.loadCart()) } }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var paymentState by remember { mutableStateOf(PaymentState()) }
    var lastSaleId by remember { mutableStateOf<Long?>(null) }
    var selectedSaleId by remember { mutableStateOf<Long?>(null) }
    var receiptPaper by remember { mutableStateOf(ReceiptPaper.MM80) }
    var queueMessage by remember { mutableStateOf<String?>(null) }
    var ticketMessage by remember { mutableStateOf<String?>(null) }
    var paymentMessage by remember { mutableStateOf<String?>(null) }
    var paymentCommitKey by remember { mutableStateOf<String?>(null) }
    var saleCommitInProgress by remember { mutableStateOf(false) }
    val heldTicketCoordinator = remember { HeldTicketSafetyCoordinator(database) }
    val paymentDraftStore = remember { PaymentDraftStore(database) }
    val saleCommitGuard = remember { SaleCommitGuard() }

    fun replaceCart(items: List<CartItem>) {
        cart.clear()
        cart.addAll(items)
        selectedIndex = null
        database.saveCart(cart.toList())
        paymentDraftStore.clear()
        paymentCommitKey = null
    }

    fun updateCartItem(index: Int, item: CartItem) {
        if (index !in cart.indices) return
        cart[index] = item
        selectedIndex = index
        database.saveCart(cart.toList())
    }

    fun openUnifiedPrintQueue() {
        context.startActivity(Intent(context, UnifiedPrintQueueActivity::class.java))
    }

    Surface(
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent()
                    OperatorSessionRegistry.touch(context.applicationContext)
                }
            }
        },
        color = Background,
    ) {
        when (screen) {
            AppScreen.DIAGNOSTIC -> DiagnosticScreen(
                restoredCount = cart.sumOf { it.quantity },
                pendingPrints = database.listPrintJobs().count {
                    it.status == PrintJobStatus.PENDING || it.status == PrintJobStatus.RETRY
                },
                onComplete = { screen = AppScreen.LOGIN },
            )

            AppScreen.LOGIN -> LoginScreen(
                operators = authStore.listEnabledOperators(),
                message = loginMessage,
                onLogin = { operatorId, pin ->
                    val result = authStore.authenticate(operatorId, pin)
                    val authenticated = result.getOrNull()
                    if (authenticated == null) {
                        loginMessage = result.exceptionOrNull()?.message ?: "ログインに失敗しました"
                    } else {
                        OperatorSessionRegistry.login(context.applicationContext, authenticated)
                        currentOperator = authenticated
                        operatorName = authenticated.name
                        loginMessage = null
                        accessMessage = null
                        (context as? android.app.Activity)?.recreate()
                    }
                },
            )

            AppScreen.SALES -> SalesScreen(
                operatorName = operatorName,
                products = products,
                cart = cart,
                selectedIndex = selectedIndex,
                printerHealth = printerHealth,
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
                onTickets = {
                    if (currentOperator?.allows(RegisterPermission.HOLD_TICKET) == true) {
                        accessMessage = null
                        screen = AppScreen.TICKETS
                    } else {
                        accessMessage = "保留伝票の権限がありません"
                    }
                },
                onHold = {
                    if (currentOperator?.allows(RegisterPermission.HOLD_TICKET) != true) {
                        accessMessage = "保留伝票の権限がありません"
                    } else if (cart.isNotEmpty()) {
                        accessMessage = null
                        val existing = database.listHeldTickets()
                        val name = HeldTicketSafetyPolicy.defaultName(existing.map { it.name })
                        database.holdCart(name, operatorName, cart.toList())
                        replaceCart(emptyList())
                        ticketMessage = "$name として保留しました"
                    }
                },
                onPayment = {
                    val draft = paymentDraftStore.loadOrCreate(cart.toList())
                    paymentState = draft.state
                    paymentCommitKey = draft.commitKey
                    paymentMessage = if (draft.restored) {
                        "前回中断した支払入力を復元しました"
                    } else {
                        null
                    }
                    saleCommitInProgress = false
                    saleCommitGuard.resetForNewPayment()
                    CustomerDisplayRuntime.publish(
                        CustomerDisplaySnapshotFactory.accounting(
                            cart.toList(),
                            paymentState,
                            CustomerDisplaySettingsStore(context.applicationContext).load().storeName,
                        ),
                    )
                    screen = AppScreen.PAYMENT
                },
                onSalesHistory = {
                    if (currentOperator?.allows(RegisterPermission.VIEW_SALES) == true) {
                        accessMessage = null
                        screen = AppScreen.SALES_HISTORY
                    } else {
                        accessMessage = "売上確認の権限がありません"
                    }
                },
                onPrintQueue = {
                    if (currentOperator?.allows(RegisterPermission.VIEW_SALES) == true) {
                        accessMessage = null
                        openUnifiedPrintQueue()
                    } else {
                        accessMessage = "印刷キュー確認の権限がありません"
                    }
                },
                onPrinterStatus = {
                    context.startActivity(Intent(context, PrinterStatusActivity::class.java))
                },
                canOpenSettings = currentOperator?.isManager == true && currentOperator?.allows(RegisterPermission.SETTINGS) == true,
                canOpenManagement = currentOperator?.permissions?.any {
                    it == RegisterPermission.VIEW_SALES ||
                        it == RegisterPermission.CASH_MOVEMENT ||
                        it == RegisterPermission.SETTLEMENT ||
                        it == RegisterPermission.REVERSAL
                } == true,
                onOpenSettings = { context.startActivity(Intent(context, AdminSettingsActivity::class.java)) },
                onOpenManagement = { context.startActivity(Intent(context, OperationsActivity::class.java)) },
                accessMessage = accessMessage,
                onLogout = {
                    OperatorSessionRegistry.logout(context.applicationContext)
                    currentOperator = null
                    operatorName = "未選択"
                    accessMessage = null
                    (context as? android.app.Activity)?.recreate()
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
                currentCartCount = cart.sumOf { it.quantity },
                message = ticketMessage,
                onLoad = { ticket ->
                    runCatching {
                        heldTicketCoordinator.loadSafely(
                            ticket = ticket,
                            currentCart = cart.toList(),
                            operatorName = operatorName,
                        )
                    }.onSuccess { result ->
                        replaceCart(result.loadedItems)
                        ticketMessage = result.message
                        screen = AppScreen.SALES
                    }.onFailure { error ->
                        ticketMessage = error.message ?: "伝票を呼び出せませんでした"
                    }
                },
                onRename = { ticket, newName ->
                    val renamed = heldTicketCoordinator.rename(ticket.id, newName, ticket.name)
                    ticketMessage = if (renamed) "伝票名を変更しました" else "伝票名を変更できませんでした"
                    screen = AppScreen.SALES
                    screen = AppScreen.TICKETS
                },
                onDelete = { ticket ->
                    database.deleteHeldTicket(ticket.id)
                    ticketMessage = "${ticket.name}を削除しました"
                    screen = AppScreen.SALES
                    screen = AppScreen.TICKETS
                },
                onBack = { screen = AppScreen.SALES },
            )

            AppScreen.PAYMENT -> PaymentScreen(
                items = cart,
                state = paymentState,
                completing = saleCommitInProgress,
                externalMessage = paymentMessage,
                onStateChange = {
                    paymentState = it
                    val commitKey = paymentCommitKey
                        ?: PaymentCommitKey.newKey().also { generated -> paymentCommitKey = generated }
                    paymentDraftStore.save(cart.toList(), it, commitKey)
                    CustomerDisplayRuntime.publish(
                        CustomerDisplaySnapshotFactory.accounting(
                            cart.toList(),
                            it,
                            CustomerDisplaySettingsStore(context.applicationContext).load().storeName,
                        ),
                    )
                },
                onBack = {
                    saleCommitGuard.resetForNewPayment()
                    saleCommitInProgress = false
                    paymentMessage = null
                    paymentDraftStore.clear()
                    paymentCommitKey = null
                    CustomerDisplayRuntime.publish(
                        CustomerDisplaySnapshotFactory.sales(
                            cart.toList(),
                            CustomerDisplaySettingsStore(context.applicationContext).load().storeName,
                        ),
                    )
                    screen = AppScreen.SALES
                },
                onComplete = complete@{
                    if (!saleCommitGuard.tryBegin()) {
                        paymentMessage = "会計確定処理中です。完了まで操作しないでください"
                        return@complete
                    }
                    saleCommitInProgress = true
                    paymentMessage = "会計を確定しています"
                    runCatching {
                        val commitKey = paymentCommitKey
                            ?: paymentDraftStore.loadOrCreate(cart.toList()).commitKey
                            ?: error("会計キーを作成できませんでした")
                        paymentCommitKey = commitKey
                        database.saveSale(
                            operatorName = operatorName,
                            items = cart.toList(),
                            paymentState = paymentState,
                            paperWidthMm = receiptPaper.widthMm,
                            commitKey = commitKey,
                        )
                    }.onSuccess { saleId ->
                        database.loadSaleDetail(saleId)?.let { detail ->
                            CustomerDisplayRuntime.publish(
                                CustomerDisplaySnapshotFactory.complete(
                                    detail,
                                    CustomerDisplaySettingsStore(context.applicationContext).load().storeName,
                                ),
                            )
                        }
                        AutomaticPrintScheduler.enqueueNow(context.applicationContext)
                        DriveOutboxScheduler.enqueueNow(context.applicationContext)
                        lastSaleId = saleId
                        selectedSaleId = saleId
                        replaceCart(emptyList())
                        paymentMessage = null
                        screen = AppScreen.COMPLETE
                    }.onFailure { error ->
                        saleCommitGuard.releaseAfterFailure()
                        saleCommitInProgress = false
                        paymentMessage = error.message ?: "会計を確定できませんでした"
                    }
                },
            )

            AppScreen.COMPLETE -> CompleteScreen(
                detail = lastSaleId?.let { database.loadSaleDetail(it) },
                onReceipt = {
                    selectedSaleId = lastSaleId
                    screen = AppScreen.RECEIPT_PREVIEW
                },
                onHistory = { screen = AppScreen.SALES_HISTORY },
                onQueue = { openUnifiedPrintQueue() },
                onNext = { screen = AppScreen.SALES },
            )

            AppScreen.SALES_HISTORY -> SalesHistoryScreen(
                sales = database.listSales(),
                onOpen = {
                    selectedSaleId = it.id
                    screen = AppScreen.SALE_DETAIL
                },
                onQueue = { openUnifiedPrintQueue() },
                onBack = { screen = AppScreen.SALES },
            )

            AppScreen.SALE_DETAIL -> {
                val detail = selectedSaleId?.let { database.loadSaleDetail(it) }
                if (detail == null) {
                    screen = AppScreen.SALES_HISTORY
                } else {
                    SaleDetailScreen(
                        detail = detail,
                        onReceipt = { screen = AppScreen.RECEIPT_PREVIEW },
                        onBack = { screen = AppScreen.SALES_HISTORY },
                    )
                }
            }

            AppScreen.RECEIPT_PREVIEW -> {
                val detail = selectedSaleId?.let { database.loadSaleDetail(it) }
                if (detail == null) {
                    screen = AppScreen.SALES_HISTORY
                } else {
                    ReceiptPreviewScreen(
                        detail = detail,
                        paper = receiptPaper,
                        onPaperChange = { receiptPaper = it },
                        onEnqueue = {
                            database.enqueueReprint(detail.summary.id, receiptPaper.widthMm)
                            AutomaticPrintScheduler.enqueueNow(context.applicationContext)
                            queueMessage = "再印字をキューへ登録しました"
                        },
                        message = queueMessage,
                        onQueue = { openUnifiedPrintQueue() },
                        onBack = { screen = AppScreen.SALE_DETAIL },
                    )
                }
            }

            AppScreen.PRINT_QUEUE -> PrintQueueScreen(
                jobs = database.listPrintJobs(),
                message = queueMessage,
                onProcessOne = {
                    queueMessage = "旧テスト送信は廃止しました。統合印刷キューを開きます"
                    openUnifiedPrintQueue()
                    screen = AppScreen.SALES
                },
                onRetry = {
                    database.retryPrintJob(it.id)
                    queueMessage = "再試行待ちへ戻しました"
                    screen = AppScreen.SALES
                    screen = AppScreen.PRINT_QUEUE
                },
                onBack = { screen = AppScreen.SALES },
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
        Text("つぐレジ", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
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
private fun DiagnosticScreen(restoredCount: Int, pendingPrints: Int, onComplete: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Header("SCR-001", "起動・自己診断")
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("起動チェックを実行しました", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Navy)
            Spacer(Modifier.height(14.dp))
            CardPanel(Modifier.width(700.dp).height(RegisterLayoutPolicy.DIAGNOSTIC_CARD_HEIGHT_DP.dp)) {
                StatusRow("データベース", "正常（税率・商品改定・同期保護対応）")
                StatusRow("作業中取引", if (restoredCount > 0) "${restoredCount}点を復元" else "なし")
                StatusRow("印刷キュー", if (pendingPrints > 0) "${pendingPrints}件待機" else "待機なし")
                StatusRow("プリンタ", "未設定でも販売可能")
                StatusRow("Google Drive", "未接続でも販売可能")
            }
            Spacer(Modifier.height(14.dp))
            BlueButton("診断完了・担当者選択へ", onComplete, Modifier.width(340.dp).height(54.dp))
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Text(value, color = Blue, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LoginScreen(
    operators: List<OperatorRecord>,
    message: String?,
    onLogin: (Long, String) -> Unit,
) {
    var selectedId by remember(operators) { mutableStateOf(operators.firstOrNull()?.id) }
    var pin by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize()) {
        Header("SCR-010", "担当者選択／ログイン")
        Row(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("担当者を選択", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Navy)
                Spacer(Modifier.height(18.dp))
                if (operators.isEmpty()) {
                    Text("有効な担当者が登録されていません", color = Danger, fontWeight = FontWeight.Bold)
                }
                for (rowStart in operators.indices step 3) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (index in rowStart until minOf(rowStart + 3, operators.size)) {
                            val operator = operators[index]
                            OutlinedButton(
                                onClick = { selectedId = operator.id; pin = "" },
                                modifier = Modifier.weight(1f).height(82.dp),
                                border = BorderStroke(
                                    if (selectedId == operator.id) 3.dp else 1.dp,
                                    if (selectedId == operator.id) Danger else Border,
                                ),
                            ) {
                                Text(
                                    if (operator.name == operator.role.displayName) operator.name else "${operator.name}\n${operator.role.displayName}",
                                    fontSize = 19.sp,
                                    textAlign = TextAlign.Center,
                                    fontWeight = FontWeight.Bold,
                                    color = Navy,
                                )
                            }
                        }
                        for (unused in minOf(rowStart + 3, operators.size) until rowStart + 3) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
            CardPanel(Modifier.width(390.dp).fillMaxHeight()) {
                Text("PIN入力", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Navy)
                Spacer(Modifier.height(12.dp))
                ValueBox(if (pin.isEmpty()) "PINを入力" else "●".repeat(pin.length))
                if (message != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(message, color = Danger, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(14.dp))
                NumberPad(
                    onDigit = { if (pin.length < 8) pin += it },
                    onClear = { pin = "" },
                    bottomActionLabel = "ログイン",
                    onBottomAction = { selectedId?.let { onLogin(it, pin) } },
                )
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
    printerHealth: PrinterHealthSnapshot,
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
    onSalesHistory: () -> Unit,
    onPrintQueue: () -> Unit,
    onPrinterStatus: () -> Unit,
    canOpenSettings: Boolean,
    canOpenManagement: Boolean,
    onOpenSettings: () -> Unit,
    onOpenManagement: () -> Unit,
    accessMessage: String?,
    onLogout: () -> Unit,
) {
    val summary = TaxEngine.calculate(cart)
    var numericInput by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Header("SCR-100", "販売画面")
        PrinterHealthBanner(printerHealth, onPrinterStatus)
        Row(
            Modifier.fillMaxWidth().height(48.dp).background(Color.White).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("店舗：サンプル居酒屋  |  担当：$operatorName", color = Navy, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            if (accessMessage != null) {
                Text(accessMessage, color = Danger, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
            } else {
                Text("SQLite保存・オフライン販売", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
            }
            if (canOpenManagement) {
      OutlinedButton(onClick = onOpenManagement, modifier = Modifier.height(40.dp)) { Text("レジ管理") }
      Spacer(Modifier.width(8.dp))
  }
  if (canOpenSettings) {
      OutlinedButton(onClick = onOpenSettings, modifier = Modifier.height(40.dp)) { Text("設定") }
      Spacer(Modifier.width(8.dp))
  }
  OutlinedButton(onClick = onLogout, modifier = Modifier.height(40.dp)) { Text("担当者切替") }
        }

        Row(Modifier.weight(1f).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CardPanel(Modifier.weight(0.36f).fillMaxHeight()) {
                Text("注文一覧", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Navy)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(cart) { index, item ->
                        val selected = selectedIndex == index
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (selected) PaleBlue else Color.Transparent, RoundedCornerShape(6.dp))
                                .combinedClickable(onClick = { onSelect(index) }, onLongClick = { onEdit(index) })
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.product.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${item.quantity} × ${yen(item.unitPrice)}  ${item.product.taxSymbol}",
                                    fontSize = 13.sp,
                                    color = Color.Gray,
                                )
                                if (item.discountAmount > 0) Text("値引 -${yen(item.discountAmount)}", color = Danger, fontSize = 12.sp)
                            }
                            Text(yen(item.baseAmount), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${cart.sumOf { it.quantity }}点")
                    Spacer(Modifier.weight(1f))
                    Text("合計 ${yen(summary.grossAmount)}", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Navy)
                }
            }

            CardPanel(Modifier.weight(0.24f).fillMaxHeight()) {
                Row(
                    Modifier.fillMaxWidth().height(40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("置数・機能", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Navy)
                    Spacer(Modifier.width(8.dp))
                    ValueBox(
                        if (numericInput.isBlank()) "0" else numericInput,
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(4.dp))
                NumberPad(
                    onDigit = { if (numericInput.length < 5) numericInput += it },
                    onClear = { numericInput = "" },
                    bottomActionLabel = "数量",
                    onBottomAction = {
                        numericInput.toIntOrNull()?.let(onChangeQuantity)
                        numericInput = ""
                    },
                    compact = true,
                )
                Spacer(Modifier.height(4.dp))
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = onRemove,
                            modifier = Modifier.weight(1f).height(RegisterLayoutPolicy.COMPACT_FUNCTION_HEIGHT_DP.dp),
                        ) { Text("訂正", fontSize = 12.sp) }
                        OutlinedButton(
                            onClick = { selectedIndex?.let(onEdit) },
                            enabled = selectedIndex != null,
                            modifier = Modifier.weight(1f).height(RegisterLayoutPolicy.COMPACT_FUNCTION_HEIGHT_DP.dp),
                        ) { Text("行編集", fontSize = 12.sp) }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = onDiscount,
                            enabled = cart.isNotEmpty(),
                            modifier = Modifier.weight(1f).height(RegisterLayoutPolicy.COMPACT_FUNCTION_HEIGHT_DP.dp),
                        ) { Text("値引・割引", fontSize = 12.sp) }
                        Button(
                            onClick = onCancelTransaction,
                            modifier = Modifier.weight(1f).height(RegisterLayoutPolicy.COMPACT_FUNCTION_HEIGHT_DP.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFBE9E7), contentColor = Danger),
                        ) { Text("取引中止", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }

            CardPanel(Modifier.weight(0.40f).fillMaxHeight()) {
                val salesContext = LocalContext.current
                val pages = products.map { it.pageNo }.distinct().sorted().ifEmpty { listOf(1) }
                var currentPage by remember(products) { mutableStateOf(pages.first()) }
                androidx.compose.runtime.LaunchedEffect(pages) {
                    if (currentPage !in pages) currentPage = pages.first()
                }
                val pageIndex = pages.indexOf(currentPage).coerceAtLeast(0)
                val pageProducts = products.filter { it.pageNo == currentPage }.associateBy { it.slotNo }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("商品", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Navy)
                        Text(V11CatalogRuntime.status(salesContext), fontSize = 12.sp, color = Color.Gray)
                    }
                    OutlinedButton(
                        onClick = { currentPage = pages[(pageIndex - 1 + pages.size) % pages.size] },
                        modifier = Modifier.width(54.dp).height(38.dp),
                    ) { Text("＜") }
                    Text(" ${currentPage}/${pages.maxOrNull() ?: 1} ", color = Navy, fontWeight = FontWeight.Bold)
                    OutlinedButton(
                        onClick = { currentPage = pages[(pageIndex + 1) % pages.size] },
                        modifier = Modifier.width(54.dp).height(38.dp),
                    ) { Text("＞") }
                }
                Spacer(Modifier.height(7.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    for (row in 0 until 8) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (column in 0 until 3) {
                                val slot = row * 3 + column + 1
                                val product = pageProducts[slot]
                                if (product == null) {
                                    Spacer(Modifier.weight(1f).height(72.dp))
                                } else {
                                    Button(
                                        onClick = { onAddProduct(product) },
                                        modifier = Modifier.weight(1f).height(72.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = ProductButtonPalette.background(product.buttonColor),
                                            contentColor = ProductButtonPalette.foreground(product.buttonColor),
                                        ),
                                        border = BorderStroke(1.dp, Border),
                                    ) {
                                        Text(
                                            "${product.name}\n${yen(product.unitPrice)} ${product.taxSymbol}",
                                            textAlign = TextAlign.Center,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            maxLines = 3,
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(7.dp))
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(onClick = onTickets, modifier = Modifier.width(130.dp).fillMaxHeight()) { Text("伝票一覧") }
            OutlinedButton(onClick = onHold, enabled = cart.isNotEmpty(), modifier = Modifier.width(100.dp).fillMaxHeight()) { Text("保留") }
            OutlinedButton(onClick = onSalesHistory, modifier = Modifier.width(120.dp).fillMaxHeight()) { Text("売上一覧") }
            OutlinedButton(onClick = onPrinterStatus, modifier = Modifier.width(115.dp).fillMaxHeight()) { Text("プリンター") }
            OutlinedButton(onClick = onPrintQueue, modifier = Modifier.width(120.dp).fillMaxHeight()) { Text("印刷管理") }
            BlueButton("小計／会計  ${yen(summary.grossAmount)}", onPayment, Modifier.weight(1f).fillMaxHeight(), cart.isNotEmpty())
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
    var quantity by remember { mutableStateOf(item.quantity.toString()) }
    var unitPrice by remember { mutableStateOf(item.unitPrice.toString()) }
    var discount by remember { mutableStateOf(item.discountAmount.toString()) }
    var note by remember { mutableStateOf(item.note) }
    var category by remember { mutableStateOf(item.product.taxCategory) }
    val parsedQuantity = quantity.toIntOrNull()?.coerceAtLeast(1) ?: 1
    val parsedUnitPrice = unitPrice.toLongOrNull()?.coerceAtLeast(0) ?: 0
    val maxDiscount = parsedUnitPrice * parsedQuantity
    val parsedDiscount = discount.toLongOrNull()?.coerceIn(0, maxDiscount) ?: 0

    Column(Modifier.fillMaxSize()) {
        Header("SCR-120", "行編集")
        Row(Modifier.weight(1f).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
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
                val taxes = TaxCategory.entries
                for (rowStart in taxes.indices step 3) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (index in rowStart until minOf(rowStart + 3, taxes.size)) {
                            val tax = taxes[index]
                            OutlinedButton(
                                onClick = { category = tax },
                                modifier = Modifier.weight(1f),
                                border = BorderStroke(if (category == tax) 3.dp else 1.dp, if (category == tax) Danger else Border),
                            ) { Text("${tax.displayName} ${tax.symbol}") }
                        }
                        for (unused in minOf(rowStart + 3, taxes.size) until rowStart + 3) {
                            Spacer(Modifier.weight(1f))
                        }
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
            }
            CardPanel(Modifier.width(330.dp).fillMaxHeight()) {
                Text("変更後", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Navy)
                Spacer(Modifier.height(18.dp))
                AmountRow("数量", "${parsedQuantity}点")
                AmountRow("単価", yen(parsedUnitPrice))
                AmountRow("値引", "-${yen(parsedDiscount)}")
                AmountRow("金額", yen(maxDiscount - parsedDiscount), emphasized = true)
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onDiscount, modifier = Modifier.fillMaxWidth()) { Text("値引・割引画面") }
            }
        }
        BottomActions(
            onBack = onBack,
            confirmLabel = "保存",
            onConfirm = {
                onSave(
                    item.copy(
                        product = item.product.withLegacyTaxCategory(category),
                        quantity = parsedQuantity,
                        unitPrice = parsedUnitPrice,
                        discountAmount = parsedDiscount,
                        note = note.trim(),
                    ),
                )
            },
        )
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
    var value by remember { mutableStateOf("") }
    val numericValue = value.toLongOrNull()?.coerceAtLeast(0) ?: 0
    val preview = runCatching {
        when (scope) {
            DiscountScope.ITEM -> {
                val index = selectedIndex ?: 0
                items.mapIndexed { rowIndex, item ->
                    if (rowIndex == index) {
                        DiscountEngine.applyToItem(item, type, if (type == DiscountType.PERCENT) numericValue * 100 else numericValue)
                    } else item
                }
            }
            DiscountScope.TRANSACTION -> DiscountEngine.applyToTransaction(
                items,
                type,
                if (type == DiscountType.PERCENT) numericValue * 100 else numericValue,
            )
        }
    }.getOrElse { items }
    val before = TaxEngine.calculate(items)
    val after = TaxEngine.calculate(preview)

    Column(Modifier.fillMaxSize()) {
        Header("SCR-121", "値引・割引")
        Row(Modifier.weight(1f).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            CardPanel(Modifier.weight(1f).fillMaxHeight()) {
                Text("適用範囲", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Navy)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChoiceButton("選択商品", scope == DiscountScope.ITEM, selectedIndex != null, Modifier.weight(1f)) { scope = DiscountScope.ITEM }
                    ChoiceButton("伝票全体", scope == DiscountScope.TRANSACTION, true, Modifier.weight(1f)) { scope = DiscountScope.TRANSACTION }
                }
                Spacer(Modifier.height(18.dp))
                Text("方式", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Navy)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChoiceButton("定額値引", type == DiscountType.FIXED, true, Modifier.weight(1f)) { type = DiscountType.FIXED }
                    ChoiceButton("率割引", type == DiscountType.PERCENT, true, Modifier.weight(1f)) { type = DiscountType.PERCENT }
                }
                Spacer(Modifier.height(18.dp))
                NumericField(if (type == DiscountType.FIXED) "値引額" else "割引率（%）", value, { value = it }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Text("伝票値引は各明細へ比例配賦し、端数を最終行へ配賦します", color = Color.Gray)
            }
            CardPanel(Modifier.width(390.dp).fillMaxHeight()) {
                Text("税率別プレビュー", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Navy)
                Spacer(Modifier.height(14.dp))
                AmountRow("変更前合計", yen(before.grossAmount))
                AmountRow("変更後合計", yen(after.grossAmount), emphasized = true)
                AmountRow("値引合計", "-${yen((before.grossAmount - after.grossAmount).coerceAtLeast(0))}")
                Spacer(Modifier.height(12.dp))
                after.buckets.forEach { bucket ->
                    val label = if (bucket.taxable) "${bucket.ratePercent}%対象" else "非課税"
                    AmountRow(label, "${yen(bucket.grossAmount)} / 税 ${yen(bucket.taxAmount)}")
                }
            }
        }
        BottomActions(onBack, "適用", { onApply(preview) }, items.isNotEmpty() && numericValue > 0)
    }
}

@Composable
private fun TicketListScreen(
    tickets: List<HeldTicket>,
    currentCartCount: Int,
    message: String?,
    onLoad: (HeldTicket) -> Unit,
    onRename: (HeldTicket, String) -> Unit,
    onDelete: (HeldTicket) -> Unit,
    onBack: () -> Unit,
) {
    var editingTicketId by remember { mutableStateOf<Long?>(null) }
    var editingName by remember { mutableStateOf("") }
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }

    Column(Modifier.fillMaxSize()) {
        Header("SCR-200", "伝票一覧")
        if (currentCartCount > 0) {
            Card(
                colors = CardDefaults.cardColors(containerColor = PaleYellow),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
            ) {
                Text(
                    "作業中の$currentCartCount 点は、別伝票を呼び出す前に自動で保留へ退避します。",
                    modifier = Modifier.padding(12.dp),
                    color = Navy,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (!message.isNullOrBlank()) {
            Text(
                message,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp),
                color = if (message.contains("できません")) Danger else Color(0xFF2E7D32),
                fontWeight = FontWeight.Bold,
            )
        }
        CardPanel(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp)) {
            if (tickets.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("保留伝票はありません", fontSize = 24.sp, color = Color.Gray)
                }
            } else {
                LazyColumn {
                    itemsIndexed(tickets, key = { _, ticket -> ticket.id }) { _, ticket ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 7.dp)
                                .background(
                                    if (pendingDeleteId == ticket.id) Color(0xFFFFEBEE) else Color.Transparent,
                                    RoundedCornerShape(8.dp),
                                )
                                .padding(8.dp),
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(ticket.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Navy)
                                    Text(
                                        "${formatDate(ticket.createdAt)} / 担当 ${ticket.operatorName} / ${ticket.itemCount}点",
                                        color = Color.Gray,
                                    )
                                }
                                Text(yen(ticket.totalAmount), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(10.dp))
                                OutlinedButton(onClick = {
                                    editingTicketId = ticket.id
                                    editingName = ticket.name
                                    pendingDeleteId = null
                                }) { Text("名称変更") }
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(onClick = {
                                    if (pendingDeleteId == ticket.id) {
                                        pendingDeleteId = null
                                        onDelete(ticket)
                                    } else {
                                        pendingDeleteId = ticket.id
                                        editingTicketId = null
                                    }
                                }) {
                                    Text(
                                        if (pendingDeleteId == ticket.id) "削除確定" else "削除",
                                        color = Danger,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                BlueButton(
                                    if (currentCartCount > 0) "退避して呼出" else "呼出",
                                    { onLoad(ticket) },
                                    Modifier.width(if (currentCartCount > 0) 145.dp else 105.dp),
                                )
                            }
                            if (pendingDeleteId == ticket.id) {
                                Text(
                                    "もう一度［削除確定］を押すと、この伝票を完全に削除します。",
                                    color = Danger,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                            if (editingTicketId == ticket.id) {
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedTextField(
                                        value = editingName,
                                        onValueChange = {
                                            editingName = it.take(HeldTicketSafetyPolicy.MAX_NAME_LENGTH)
                                        },
                                        label = { Text("伝票名") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                    OutlinedButton(onClick = {
                                        editingTicketId = null
                                        editingName = ""
                                    }) { Text("取消") }
                                    BlueButton(
                                        "保存",
                                        {
                                            onRename(ticket, editingName)
                                            editingTicketId = null
                                        },
                                        Modifier.width(100.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        BottomActions(onBack, "販売へ戻る", onBack)
    }
}

@Composable
private fun PaymentScreen(
    items: List<CartItem>,
    state: PaymentState,
    completing: Boolean,
    externalMessage: String?,
    onStateChange: (PaymentState) -> Unit,
    onBack: () -> Unit,
    onComplete: () -> Unit,
) {
    val summary = TaxEngine.calculate(items)
    val remaining = state.remaining(summary.grossAmount)
    val paymentContext = LocalContext.current
    val mixedPolicy = remember { TaxInvoiceSettingsStore(paymentContext.applicationContext).load().mixedTaxPolicy }
    var input by remember { mutableStateOf("") }
    var operationMessage by remember { mutableStateOf<String?>(null) }
    var acknowledgedMixedTax by remember { mutableStateOf(false) }
    val mixed = TaxEngine.validateMixedTax(items, MixedTaxPolicy.ALLOW)
    val mixedBlocked = mixed.hasMixedTax && mixedPolicy == MixedTaxPolicy.BLOCK
    val mixedNeedsAcknowledgement = mixed.hasMixedTax && mixedPolicy == MixedTaxPolicy.WARN

    fun add(method: PaymentMethod) {
        val amount = input.toLongOrNull()
        if (completing) return
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
        Header("SCR-300 / SCR-310", "会計・支払追加")
        Row(Modifier.weight(1f).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            CardPanel(Modifier.weight(1f).fillMaxHeight()) {
                Text("会計内訳", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Navy)
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(items) { _, item ->
                        AmountRow("${item.product.name} × ${item.quantity} ${item.product.taxSymbol}", yen(item.baseAmount))
                    }
                }
                summary.buckets.forEach { bucket ->
                    val label = if (bucket.taxable) "${bucket.ratePercent}% 消費税" else "非課税"
                    AmountRow(label, yen(bucket.taxAmount))
                }
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
                        colors = CardDefaults.cardColors(containerColor = Warning),
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (mixedPolicy == MixedTaxPolicy.WARN) {
                                    Modifier.clickable { acknowledgedMixedTax = !acknowledgedMixedTax }
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        Text(
                            "${mixed.message}\n$instruction",
                            modifier = Modifier.padding(12.dp),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            CardPanel(Modifier.width(430.dp).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        PaymentAmountRow("合計", yen(summary.grossAmount), emphasized = true)
                        PaymentAmountRow("支払済", yen(state.paidAmount))
                    }
                    Column(Modifier.weight(1f)) {
                        PaymentAmountRow("残額", yen(remaining), emphasized = true)
                        PaymentAmountRow("お釣り", yen(state.changeAmount))
                    }
                }
                Spacer(Modifier.height(4.dp))
                LazyColumn(Modifier.height(36.dp)) {
                    itemsIndexed(state.allocations) { index, payment ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${payment.method.displayName} ${yen(payment.appliedAmount)}", Modifier.weight(1f))
                            OutlinedButton(onClick = { onStateChange(PaymentEngine.removeAt(state, index)) }) { Text("取消") }
                        }
                    }
                }
                val visibleMessage = externalMessage ?: operationMessage
                if (!visibleMessage.isNullOrBlank()) {
                    Text(
                        visibleMessage,
                        color = if (completing) Color(0xFF2E7D32) else Danger,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                    )
                }
                ValueBox(if (input.isBlank()) "残額全額" else input, compact = true)
                Spacer(Modifier.height(4.dp))
                NumberPad(
                    onDigit = { if (input.length < 10) input += it },
                    onClear = { input = "" },
                    bottomActionLabel = "現金",
                    onBottomAction = { add(PaymentMethod.CASH) },
                    compact = true,
                )
                Spacer(Modifier.height(4.dp))
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { add(PaymentMethod.CARD) },
                            enabled = remaining > 0 && !completing,
                            modifier = Modifier.weight(1f).height(RegisterLayoutPolicy.COMPACT_FUNCTION_HEIGHT_DP.dp),
                        ) { Text("カード", fontSize = 12.sp) }
                        OutlinedButton(
                            onClick = { add(PaymentMethod.GIFT_CERTIFICATE) },
                            enabled = remaining > 0 && !completing,
                            modifier = Modifier.weight(1f).height(RegisterLayoutPolicy.COMPACT_FUNCTION_HEIGHT_DP.dp),
                        ) { Text("商品券", fontSize = 12.sp) }
                        OutlinedButton(
                            onClick = { add(PaymentMethod.ACCOUNT_RECEIVABLE) },
                            enabled = remaining > 0 && !completing,
                            modifier = Modifier.weight(1f).height(RegisterLayoutPolicy.COMPACT_FUNCTION_HEIGHT_DP.dp),
                        ) { Text("掛売", fontSize = 12.sp) }
                    }
                }
            }
        }
        BottomActions(
            onBack = onBack,
            confirmLabel = if (completing) "会計確定中…" else "会計確定",
            onConfirm = onComplete,
            confirmEnabled = remaining == 0L && !mixedBlocked && (!mixedNeedsAcknowledgement || acknowledgedMixedTax) && !completing,
        )
    }
}

@Composable
private fun CompleteScreen(
    detail: SaleDetailRecord?,
    onReceipt: () -> Unit,
    onHistory: () -> Unit,
    onQueue: () -> Unit,
    onNext: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Header("SCR-320", "会計完了")
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("会計が完了しました", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Navy)
            Spacer(Modifier.height(20.dp))
            CardPanel(Modifier.width(620.dp).height(200.dp)) {
                AmountRow("売上番号", detail?.summary?.id?.toString() ?: "-")
                AmountRow("合計", yen(detail?.summary?.totalAmount ?: 0), emphasized = true)
                AmountRow("お釣り", yen(detail?.summary?.changeAmount ?: 0), emphasized = true)
                Text("売上・支払・印刷キューを同一トランザクションで保存済み", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onReceipt, enabled = detail != null) { Text("レシート確認") }
                OutlinedButton(onClick = onQueue) { Text("統合印刷キュー") }
                OutlinedButton(onClick = onHistory) { Text("売上一覧") }
                BlueButton("次の取引", onNext, Modifier.width(180.dp).height(54.dp))
            }
        }
    }
}

@Composable
private fun SalesHistoryScreen(
    sales: List<SaleSummaryRecord>,
    onOpen: (SaleSummaryRecord) -> Unit,
    onQueue: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Header("SCR-400", "売上一覧")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("確定売上 ${sales.size}件", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Navy)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onQueue) { Text("統合印刷キュー") }
        }
        CardPanel(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp)) {
            if (sales.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("売上はまだありません", color = Color.Gray, fontSize = 22.sp) }
            } else {
                LazyColumn {
                    itemsIndexed(sales) { _, sale ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpen(sale) }.padding(vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("#${sale.id}", Modifier.width(80.dp), fontWeight = FontWeight.Bold)
                            Text(formatDate(sale.createdAt), Modifier.width(165.dp))
                            Text(sale.operatorName, Modifier.width(90.dp))
                            Text(sale.paymentLabel, Modifier.weight(1f))
                            Text("印字 ${sale.printCount}回", Modifier.width(85.dp), color = Color.Gray)
                            Text(yen(sale.totalAmount), Modifier.width(130.dp), textAlign = TextAlign.End, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        BottomActions(onBack, "販売へ戻る", onBack)
    }
}

@Composable
private fun SaleDetailScreen(
    detail: SaleDetailRecord,
    onReceipt: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Header("SCR-410", "売上詳細")
        Row(Modifier.weight(1f).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            CardPanel(Modifier.weight(1f).fillMaxHeight()) {
                Text("売上 #${detail.summary.id}", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Navy)
                Text("${formatDate(detail.summary.createdAt)} / 担当 ${detail.summary.operatorName}", color = Color.Gray)
                Spacer(Modifier.height(12.dp))
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(detail.items) { _, item ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            AmountRow("${item.product.name} ${item.product.taxSymbol} × ${item.quantity}", yen(item.baseAmount))
                            if (item.discountAmount > 0) Text("値引 -${yen(item.discountAmount)}", color = Danger, fontSize = 12.sp)
                            if (item.note.isNotBlank()) Text("メモ ${item.note}", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
            }
            CardPanel(Modifier.width(380.dp).fillMaxHeight()) {
                AmountRow("税抜", yen(detail.taxSummary.netAmount))
                AmountRow("消費税", yen(detail.taxSummary.taxAmount))
                AmountRow("合計", yen(detail.taxSummary.grossAmount), emphasized = true)
                Spacer(Modifier.height(10.dp))
                detail.taxSummary.buckets.forEach { bucket ->
                    val label = if (bucket.taxable) "${bucket.ratePercent}%対象" else "非課税"
                    AmountRow(label, "${yen(bucket.grossAmount)} / 税 ${yen(bucket.taxAmount)}")
                }
                Spacer(Modifier.height(14.dp))
                Text("支払", fontWeight = FontWeight.Bold)
                detail.payments.forEach { payment ->
                    AmountRow(payment.method.displayName, yen(payment.receivedAmount))
                }
                AmountRow("お釣り", yen(detail.summary.changeAmount))
                Spacer(Modifier.weight(1f))
                BlueButton("レシート／再印字", onReceipt, Modifier.fillMaxWidth().height(52.dp))
            }
        }
        BottomActions(onBack, "一覧へ戻る", onBack)
    }
}

@Composable
private fun ReceiptPreviewScreen(
    detail: SaleDetailRecord,
    paper: ReceiptPaper,
    onPaperChange: (ReceiptPaper) -> Unit,
    onEnqueue: () -> Unit,
    message: String?,
    onQueue: () -> Unit,
    onBack: () -> Unit,
) {
    val data = ReceiptFactory.fromSale(detail, reprint = detail.summary.printCount > 0)
    val receipt = ReceiptRenderer.render(data, paper)
    Column(Modifier.fillMaxSize()) {
        Header("SCR-645 / SCR-646", "レシートプレビュー・後レシート")
        Row(Modifier.weight(1f).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            CardPanel(Modifier.weight(1f).fillMaxHeight()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("用紙幅", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(12.dp))
                    ChoiceButton("58mm", paper == ReceiptPaper.MM58, true, Modifier.width(120.dp)) { onPaperChange(ReceiptPaper.MM58) }
                    Spacer(Modifier.width(8.dp))
                    ChoiceButton("80mm", paper == ReceiptPaper.MM80, true, Modifier.width(120.dp)) { onPaperChange(ReceiptPaper.MM80) }
                    Spacer(Modifier.weight(1f))
                    Text("構造化データから生成", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.weight(1f).fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp)).padding(18.dp),
                ) {
                    Text(
                        receipt,
                        fontFamily = FontFamily.Monospace,
                        fontSize = if (paper == ReceiptPaper.MM58) 14.sp else 15.sp,
                        lineHeight = 20.sp,
                    )
                }
            }
            CardPanel(Modifier.width(310.dp).fillMaxHeight()) {
                Text("印刷操作", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Navy)
                Spacer(Modifier.height(14.dp))
                Text("ESC/POSデータは同じレシート構造から生成されます。印刷失敗時も売上は確定済みのまま、統合印刷キューから安全に確認します。")
                Spacer(Modifier.height(14.dp))
                if (!message.isNullOrBlank()) {
                    Text(message, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                }
                BlueButton("再印字をキュー登録", onEnqueue, Modifier.fillMaxWidth().height(52.dp))
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onQueue, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("統合印刷キューを開く") }
                Spacer(Modifier.weight(1f))
                Text("FAILEDは自動再送しません。紙確認後に統合印刷キューから操作します。", color = Danger, fontSize = 13.sp)
            }
        }
        BottomActions(onBack, "詳細へ戻る", onBack)
    }
}

@Composable
private fun PrintQueueScreen(
    jobs: List<PrintJobRecord>,
    message: String?,
    onProcessOne: () -> Unit,
    onRetry: (PrintJobRecord) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Header("SCR-700", "旧印刷キュー・統合済み")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("印刷ジョブ ${jobs.size}件", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Navy)
            Spacer(Modifier.weight(1f))
            if (!message.isNullOrBlank()) Text(message, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(14.dp))
            BlueButton("統合印刷キューを開く", onProcessOne, Modifier.width(210.dp).height(46.dp))
        }
        CardPanel(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp)) {
            if (jobs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("印刷ジョブはありません", color = Color.Gray, fontSize = 22.sp) }
            } else {
                LazyColumn {
                    itemsIndexed(jobs) { _, job ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("#${job.id}", Modifier.width(70.dp), fontWeight = FontWeight.Bold)
                            Text("売上 ${job.saleId}", Modifier.width(100.dp))
                            Text("${job.paperWidthMm}mm", Modifier.width(70.dp))
                            Text(job.status.name, Modifier.width(110.dp), color = statusColor(job.status), fontWeight = FontWeight.Bold)
                            Text("試行 ${job.attemptCount}", Modifier.width(80.dp))
                            Text(job.lastError ?: "", Modifier.weight(1f), color = Danger, maxLines = 2)
                            if (job.status == PrintJobStatus.FAILED || job.status == PrintJobStatus.RETRY) {
                                OutlinedButton(onClick = { onRetry(job) }) { Text("再試行") }
                            }
                        }
                    }
                }
            }
        }
        BottomActions(onBack, "販売へ戻る", onBack)
    }
}

@Composable
private fun PrinterHealthBanner(snapshot: PrinterHealthSnapshot, onOpen: () -> Unit) {
    var nowMillis by remember(snapshot.checkedAt) { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(snapshot.checkedAt) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000L)
        }
    }
    val background = when (snapshot.level) {
        PrinterHealthLevel.CHECKING -> PaleBlue
        PrinterHealthLevel.READY -> PaleGreen
        PrinterHealthLevel.WARNING -> PaleYellow
        PrinterHealthLevel.ERROR,
        PrinterHealthLevel.UNCONFIGURED,
        -> Color(0xFFFFEBEE)
        PrinterHealthLevel.DISABLED -> Color(0xFFF1F3F5)
    }
    val foreground = when (snapshot.level) {
        PrinterHealthLevel.CHECKING -> Blue
        PrinterHealthLevel.READY -> Color(0xFF2E7D32)
        PrinterHealthLevel.WARNING -> Color(0xFFEF6C00)
        PrinterHealthLevel.ERROR,
        PrinterHealthLevel.UNCONFIGURED,
        -> Danger
        PrinterHealthLevel.DISABLED -> Color.DarkGray
    }
    val prefix = when (snapshot.level) {
        PrinterHealthLevel.CHECKING -> "確認中"
        PrinterHealthLevel.READY -> "正常"
        PrinterHealthLevel.WARNING -> "注意"
        PrinterHealthLevel.ERROR -> "異常"
        PrinterHealthLevel.DISABLED -> "未使用"
        PrinterHealthLevel.UNCONFIGURED -> "未設定"
    }
    val stale = PrinterHealthUiPolicy.isStale(snapshot, nowMillis)
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (PrinterHealthPolicy.requiresAttention(snapshot)) 50.dp else 42.dp)
            .background(background)
            .clickable(onClick = onOpen)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("プリンター $prefix", color = foreground, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(14.dp))
        Text(snapshot.title, color = foreground, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(14.dp))
        Text(snapshot.detail, modifier = Modifier.weight(1f), color = Color.DarkGray, fontSize = 12.sp, maxLines = 1)
        Spacer(Modifier.width(14.dp))
        Text(
            PrinterHealthUiPolicy.checkedAtLabel(snapshot, nowMillis),
            color = if (stale) foreground else Color.DarkGray,
            fontSize = 12.sp,
            fontWeight = if (stale) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
        Spacer(Modifier.width(14.dp))
        Text("診断を開く ＞", color = foreground, fontWeight = FontWeight.Bold)
    }
}

private fun statusColor(status: PrintJobStatus): Color = when (status) {
    PrintJobStatus.COMPLETED -> Color(0xFF2E7D32)
    PrintJobStatus.FAILED -> Danger
    PrintJobStatus.RETRY -> Color(0xFFEF6C00)
    PrintJobStatus.PRINTING -> Blue
    PrintJobStatus.PENDING -> Navy
}

@Composable
private fun NumericField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> if (text.all { it.isDigit() } && text.length <= 10) onValueChange(text) },
        label = { Text(label) },
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
    )
}

@Composable
private fun NumberPad(
    onDigit: (String) -> Unit,
    onClear: () -> Unit,
    bottomActionLabel: String,
    onBottomAction: () -> Unit,
    compact: Boolean = false,
) {
    val buttonHeight = if (compact) RegisterLayoutPolicy.COMPACT_KEY_HEIGHT_DP.dp else 44.dp
    val rowGap = if (compact) RegisterLayoutPolicy.COMPACT_KEY_GAP_DP.dp else 6.dp
    val content: @Composable () -> Unit = {
    for (rowStart in 1..9 step 3) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (digit in rowStart until rowStart + 3) {
                OutlinedButton(onClick = { onDigit(digit.toString()) }, modifier = Modifier.weight(1f).height(buttonHeight)) {
                    Text(digit.toString())
                }
            }
        }
        Spacer(Modifier.height(rowGap))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f).height(buttonHeight)) { Text("C", color = Danger) }
        OutlinedButton(onClick = { onDigit("0") }, modifier = Modifier.weight(1f).height(buttonHeight)) { Text("0") }
        BlueButton(bottomActionLabel, onBottomAction, Modifier.weight(1.4f).height(buttonHeight))
    }
    }
    if (compact) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) { content() }
    } else {
        content()
    }
}

@Composable
private fun ValueBox(value: String, compact: Boolean = false, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(if (compact) RegisterLayoutPolicy.COMPACT_VALUE_HEIGHT_DP.dp else 54.dp)
            .background(PaleBlue, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(value, fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Navy)
    }
}

@Composable
private fun CardPanel(modifier: Modifier = Modifier, content: @Composable Column.() -> Unit) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Border),
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), content = content)
    }
}

@Composable
private fun BlueButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = Blue),
    ) { Text(label, fontWeight = FontWeight.Bold) }
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
        modifier = modifier,
        border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) Danger else Border),
    ) { Text(label, color = Navy, fontWeight = FontWeight.Bold) }
}

@Composable
private fun PaymentAmountRow(label: String, value: String, emphasized: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = if (emphasized) 18.sp else 14.sp, fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal)
        Text(value, fontSize = if (emphasized) 20.sp else 15.sp, fontWeight = FontWeight.Bold, color = if (emphasized) Navy else Color.Unspecified)
    }
}

@Composable
private fun AmountRow(label: String, value: String, emphasized: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = if (emphasized) 20.sp else 16.sp, fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal)
        Text(value, fontSize = if (emphasized) 23.sp else 16.sp, fontWeight = FontWeight.Bold, color = if (emphasized) Navy else Color.Unspecified)
    }
}

@Composable
private fun BottomActions(
    onBack: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 18.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = onBack, modifier = Modifier.width(190.dp).fillMaxHeight()) { Text("戻る") }
        BlueButton(confirmLabel, onConfirm, Modifier.weight(1f).fillMaxHeight(), confirmEnabled)
    }
}

private fun yen(amount: Long): String = NumberFormat.getCurrencyInstance(Locale.JAPAN).format(amount)

private fun formatDate(epochMillis: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(Date(epochMillis))
