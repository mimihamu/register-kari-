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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch

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
    const val COMPACT_VALUE_HEIGHT_DP = 46
    const val COMPACT_KEY_HEIGHT_DP = 42
    const val COMPACT_KEY_GAP_DP = 5
    const val COMPACT_FUNCTION_HEIGHT_DP = 40

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
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (BarcodeScannerRuntimeV135.handle(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureRegisterSystemBars(window)
        DeviceAppRuntimeV135.applyWindowPolicy(
            window,
            InitialReleaseSettingsStoreV135(applicationContext).loadDevice(),
        )
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
    TICKET_SPLIT,
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
    val initialReleaseSettingsStore = remember { InitialReleaseSettingsStoreV135(context.applicationContext) }
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
    val corrections = remember {
        mutableStateListOf<CartCorrectionRecordV135>().apply { addAll(database.loadCartCorrections()) }
    }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var paymentState by remember { mutableStateOf(PaymentState()) }
    var lastSaleId by remember { mutableStateOf<Long?>(null) }
    var selectedSaleId by remember { mutableStateOf<Long?>(null) }
    var queueMessage by remember { mutableStateOf<String?>(null) }
    var ticketMessage by remember { mutableStateOf<String?>(null) }
    var selectedHeldTicketId by remember { mutableStateOf<Long?>(null) }
    var paymentMessage by remember { mutableStateOf<String?>(null) }
    var paymentCommitKey by remember { mutableStateOf<String?>(null) }
    var saleCommitInProgress by remember { mutableStateOf(false) }
    val heldTicketCoordinator = remember { HeldTicketSafetyCoordinator(database) }
    val paymentDraftStore = remember { PaymentDraftStore(database) }
    val saleCommitGuard = remember { SaleCommitGuard() }
    val checkoutScope = rememberCoroutineScope()

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

    fun applyCartCorrection(index: Int, quantity: Int, type: CartCorrectionTypeV135) {
        if (index !in cart.indices) return
        runCatching {
            database.applyCartCorrection(
                targetIndex = index,
                cancelQuantity = quantity,
                correctionType = type,
                operatorName = operatorName,
            )
        }.onSuccess { result ->
            cart.clear()
            cart.addAll(result.items)
            corrections.clear()
            corrections.addAll(database.loadCartCorrections())
            selectedIndex = null
            paymentDraftStore.clear()
            paymentCommitKey = null
        }.onFailure { error ->
            accessMessage = error.message ?: "訂正できませんでした"
        }
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
                    it.status != PrintJobStatus.COMPLETED && it.status != PrintJobStatus.DISCARDED
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
                corrections = corrections,
                selectedIndex = selectedIndex,
                printerHealth = printerHealth,
                onSelect = { selectedIndex = it },
                onEdit = {
                    selectedIndex = it
                    screen = AppScreen.LINE_EDIT
                },
                onAddProduct = { product, quantity ->
                    require(quantity > 0) { "数量は1以上で指定してください" }
                    val mergeSameItem = initialReleaseSettingsStore.loadSales().mergeSameItem
                    val index = if (mergeSameItem) cart.indexOfFirst {
                        it.product.id == product.id &&
                            it.unitPrice == product.unitPrice &&
                            it.discountAmount == 0L &&
                            it.note.isEmpty()
                    } else -1
                    if (index >= 0) {
                        val updated = cart[index].copy(quantity = cart[index].quantity + quantity)
                        cart.removeAt(index)
                        cart += updated
                    } else {
                        cart += CartItem(
                            product = product,
                            quantity = quantity,
                            lineId = CartLineIdentityV135.newId(),
                        )
                    }
                    selectedIndex = null
                    database.saveCart(cart.toList())
                },
                onChangeQuantity = { quantity ->
                    val index = selectedIndex
                    if (index != null && index in cart.indices && quantity > 0) {
                        val current = cart[index]
                        if (quantity < current.quantity) {
                            applyCartCorrection(
                                index,
                                current.quantity - quantity,
                                CartCorrectionTypeV135.SELECTED_LINE,
                            )
                        } else {
                            updateCartItem(index, current.copy(quantity = quantity))
                        }
                    }
                },
                onRemove = {
                    val index = cart.lastIndex
                    if (index in cart.indices) {
                        applyCartCorrection(
                            index,
                            cart[index].quantity,
                            CartCorrectionTypeV135.LAST_LINE,
                        )
                    }
                },
                onCancelSelected = { quantity ->
                    val index = selectedIndex
                    if (index != null && index in cart.indices) {
                        applyCartCorrection(index, quantity, CartCorrectionTypeV135.SELECTED_LINE)
                    }
                },
                onCancelTransaction = {
                    database.clearCartCorrections()
                    corrections.clear()
                    replaceCart(emptyList())
                },
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
                        val heldTicketId = database.holdCart(name, operatorName, cart.toList())
                        val automaticProvisional = runCatching {
                            val service = HeldTicketProvisionalPrintServiceV135(context.applicationContext)
                            try {
                                service.enqueueIfAutomatic(heldTicketId, operatorName)
                            } finally {
                                service.close()
                            }
                        }.getOrNull()
                        database.clearCartCorrections()
                        corrections.clear()
                        replaceCart(emptyList())
                        if (automaticProvisional != null) {
                            runCatching { AutomaticPrintScheduler.enqueueNow(context.applicationContext) }
                            ticketMessage = "$name として保留し、仮締め票を自動印刷キューへ登録しました"
                        } else {
                            ticketMessage = "$name として保留しました"
                        }
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
                        CustomerDisplaySnapshotFactory.subtotal(
                            cart.toList(),
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
                canOpenManagement = currentOperator?.permissions?.let(
                    ManagementNavigationPolicyV030::canOpenManagement,
                ) == true,
                onOpenSettings = { context.startActivity(Intent(context, AdminSettingsActivity::class.java)) },
                onOpenManagement = { context.startActivity(Intent(context, OperationsHubActivityV030::class.java)) },
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
                        onSave = { edited ->
                            val original = cart.getOrNull(index)
                            if (original != null && edited.quantity < original.quantity) {
                                applyCartCorrection(
                                    index,
                                    original.quantity - edited.quantity,
                                    CartCorrectionTypeV135.SELECTED_LINE,
                                )
                                val remainingIndex = cart.indexOfFirst { it.lineId == original.lineId }
                                if (remainingIndex >= 0) {
                                    updateCartItem(
                                        remainingIndex,
                                        edited.copy(
                                            quantity = edited.quantity,
                                            lineId = original.lineId,
                                        ),
                                    )
                                }
                            } else {
                                updateCartItem(index, edited)
                            }
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
                        database.clearCartCorrections()
                        corrections.clear()
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
                onMerge = { source, target ->
                    runCatching {
                        heldTicketCoordinator.merge(source, target)
                    }.onSuccess { result ->
                        ticketMessage = result.message
                    }.onFailure { error ->
                        ticketMessage = error.message ?: "伝票を結合できませんでした"
                    }
                },
                onSplit = { ticket ->
                    selectedHeldTicketId = ticket.id
                    ticketMessage = null
                    screen = AppScreen.TICKET_SPLIT
                },
                onPrint = { ticket ->
                    runCatching {
                        val service = HeldTicketProvisionalPrintServiceV135(context.applicationContext)
                        try {
                            service.enqueue(ticket.id, operatorName)
                        } finally {
                            service.close()
                        }
                    }.onSuccess { result ->
                        ticketMessage = "${ticket.name}の仮締め票を印刷キューへ登録しました（Job.${result.jobId}）"
                        runCatching { AutomaticPrintScheduler.enqueueNow(context.applicationContext) }
                    }.onFailure { error ->
                        ticketMessage = error.message ?: "仮締め票を登録できませんでした"
                    }
                },
                onBack = { screen = AppScreen.SALES },
            )

            AppScreen.TICKET_SPLIT -> {
                val tickets = database.listHeldTickets()
                val ticket = selectedHeldTicketId?.let { selectedId ->
                    tickets.firstOrNull { it.id == selectedId }
                }
                if (ticket == null) {
                    selectedHeldTicketId = null
                    ticketMessage = "分割対象の伝票が見つかりませんでした"
                    screen = AppScreen.TICKETS
                } else {
                    TicketSplitScreen(
                        ticket = ticket,
                        items = database.loadHeldTicket(ticket.id),
                        suggestedName = HeldTicketSafetyPolicy.splitName(ticket.name, tickets.map { it.name }),
                        externalMessage = ticketMessage,
                        onConfirm = { movedQuantities, newName ->
                            runCatching {
                                heldTicketCoordinator.split(
                                    ticket = ticket,
                                    movedQuantities = movedQuantities,
                                    newTicketName = newName,
                                    operatorName = operatorName,
                                )
                            }.onSuccess { result ->
                                ticketMessage = result.message
                                selectedHeldTicketId = null
                                screen = AppScreen.TICKETS
                            }.onFailure { error ->
                                ticketMessage = error.message ?: "伝票を分割できませんでした"
                            }
                        },
                        onBack = {
                            selectedHeldTicketId = null
                            ticketMessage = null
                            screen = AppScreen.TICKETS
                        },
                    )
                }
            }

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
                        val completedPaymentState = paymentState
                        val completedOperatorName = operatorName
                        checkoutScope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                ReceiptAutoPrintRuntimeV136.dispatchDrawerIfNeeded(
                                    context = context.applicationContext,
                                    paymentState = completedPaymentState,
                                    saleId = saleId,
                                    actor = completedOperatorName,
                                )
                            }
                        }
                        AutomaticPrintScheduler.enqueueNow(context.applicationContext)
                        DriveOutboxScheduler.enqueueNow(context.applicationContext)
                        lastSaleId = saleId
                        selectedSaleId = saleId
                        corrections.clear()
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
                    lastSaleId?.let { saleId ->
                        context.startActivity(SaleReceiptNavigation.intent(context, saleId))
                    }
                },
                onVoucher = {
                    context.startActivity(ReceiptVoucherNavigation.issuanceIntent(context, lastSaleId))
                },
                onHistory = { screen = AppScreen.SALES_HISTORY },
                onQueue = { openUnifiedPrintQueue() },
                onNext = { screen = AppScreen.SALES },
            )

            AppScreen.SALES_HISTORY -> SalesHistoryScreen(
                sales = database.listSales(SalesHistoryLookupPolicy.RECENT_LOAD_LIMIT),
                onOpen = {
                    selectedSaleId = it.id
                    screen = AppScreen.SALE_DETAIL
                },
                onDirectLookup = { saleId ->
                    val detail = database.loadSaleDetail(saleId)
                    if (detail == null) {
                        false
                    } else {
                        selectedSaleId = detail.summary.id
                        screen = AppScreen.SALE_DETAIL
                        true
                    }
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
                        canReverse = currentOperator?.allows(RegisterPermission.REVERSAL) == true,
                        onReceipt = {
                            context.startActivity(SaleReceiptNavigation.intent(context, detail.summary.id))
                        },
                        onVoucher = {
                            context.startActivity(ReceiptVoucherNavigation.issuanceIntent(context, detail.summary.id))
                        },
                        onReverse = {
                            context.startActivity(ReversalNavigation.intent(context, detail.summary.id))
                        },
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
                        paper = PrinterPaperSettingPolicy.currentPaper(context.applicationContext),
                        onEnqueue = {
                            database.enqueueReprint(detail.summary.id, operatorName)
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
    val responsive = rememberRegisterResponsiveMetrics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(responsive.headerHeightDp.dp)
            .background(Navy)
            .padding(horizontal = responsive.screenPaddingDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "つぐレジ",
            color = Color.White,
            fontSize = if (responsive.isCompact) 20.sp else 23.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Spacer(Modifier.width(if (responsive.isCompact) 12.dp else 24.dp))
        Text(
            "$screenId  $title",
            modifier = Modifier.weight(1f),
            color = Color.White,
            fontSize = if (responsive.isCompact) 17.sp else 21.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        if (!responsive.isCompact) {
            Text(
                "営業日 ${LocalDate.now()}  ${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))}",
                color = Color.White,
                fontSize = 14.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun DiagnosticScreen(restoredCount: Int, pendingPrints: Int, onComplete: () -> Unit) {
    val responsive = rememberRegisterResponsiveMetrics()
    val diagnosticScroll = rememberScrollState()
    Column(modifier = Modifier.fillMaxSize()) {
        Header("SCR-001", "起動・自己診断")
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(diagnosticScroll)
                .padding(responsive.screenPaddingDp.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (responsive.isCompact) Arrangement.Top else Arrangement.Center,
        ) {
            Text(
                "起動チェックを実行しました",
                fontSize = if (responsive.isCompact) 22.sp else 30.sp,
                fontWeight = FontWeight.Bold,
                color = Navy,
                maxLines = 1,
            )
            Spacer(Modifier.height(if (responsive.isCompact) 8.dp else 14.dp))
            CardPanel(
                if (responsive.isCompact) {
                    Modifier.fillMaxWidth().heightIn(min = 250.dp)
                } else {
                    Modifier.width(700.dp).height(RegisterLayoutPolicy.DIAGNOSTIC_CARD_HEIGHT_DP.dp)
                },
            ) {
                StatusRow("データベース", "正常（税率・商品改定・同期保護対応）")
                StatusRow("作業中取引", if (restoredCount > 0) "${restoredCount}点を復元" else "なし")
                StatusRow("印刷キュー", if (pendingPrints > 0) "${pendingPrints}件待機" else "待機なし")
                StatusRow("プリンタ", "未設定でも販売可能")
                StatusRow("Google Drive", "未接続でも販売可能")
            }
            Spacer(Modifier.height(if (responsive.isCompact) 8.dp else 14.dp))
            BlueButton(
                "診断完了・担当者選択へ",
                onComplete,
                if (responsive.isCompact) {
                    Modifier.fillMaxWidth().height(54.dp)
                } else {
                    Modifier.width(340.dp).height(54.dp)
                },
            )
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
    val responsive = rememberRegisterResponsiveMetrics()
    val operatorScroll = rememberScrollState()
    val pinScroll = rememberScrollState()
    val outerPaddingDp = if (responsive.isCompact) responsive.screenPaddingDp else 28
    val panelGapDp = if (responsive.isCompact) responsive.panelGapDp else 28
    val operatorGapDp = if (responsive.isCompact) 6 else 16
    val operatorButtonHeightDp = if (responsive.isCompact) 58 else 82
    val pinPanelWidthDp = if (responsive.isCompact) 260 else 390
    Column(modifier = Modifier.fillMaxSize()) {
        Header("SCR-010", "担当者選択／ログイン")
        Row(
            modifier = Modifier.fillMaxSize().padding(outerPaddingDp.dp),
            horizontalArrangement = Arrangement.spacedBy(panelGapDp.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(operatorScroll),
            ) {
                Text(
                    "担当者を選択",
                    fontSize = if (responsive.isCompact) 19.sp else 25.sp,
                    fontWeight = FontWeight.Bold,
                    color = Navy,
                    maxLines = 1,
                )
                Spacer(Modifier.height(if (responsive.isCompact) 8.dp else 18.dp))
                if (operators.isEmpty()) {
                    Text("有効な担当者が登録されていません", color = Danger, fontWeight = FontWeight.Bold)
                }
                for (rowStart in operators.indices step 3) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(operatorGapDp.dp),
                    ) {
                        for (index in rowStart until minOf(rowStart + 3, operators.size)) {
                            val operator = operators[index]
                            OutlinedButton(
                                onClick = { selectedId = operator.id; pin = "" },
                                modifier = Modifier.weight(1f).height(operatorButtonHeightDp.dp),
                                border = BorderStroke(
                                    if (selectedId == operator.id) 3.dp else 1.dp,
                                    if (selectedId == operator.id) Danger else Border,
                                ),
                            ) {
                                Text(
                                    if (operator.name == operator.role.displayName) operator.name else "${operator.name}\n${operator.role.displayName}",
                                    fontSize = if (responsive.isCompact) 14.sp else 19.sp,
                                    textAlign = TextAlign.Center,
                                    fontWeight = FontWeight.Bold,
                                    color = Navy,
                                    maxLines = 2,
                                )
                            }
                        }
                        for (unused in minOf(rowStart + 3, operators.size) until rowStart + 3) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(operatorGapDp.dp))
                }
            }
            CardPanel(Modifier.width(pinPanelWidthDp.dp).fillMaxHeight()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .then(
                            if (responsive.isCompact) Modifier.verticalScroll(pinScroll) else Modifier,
                        ),
                ) {
                    Text(
                        "PIN入力",
                        fontSize = if (responsive.isCompact) 18.sp else 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Navy,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(if (responsive.isCompact) 6.dp else 12.dp))
                    ValueBox(
                        if (pin.isEmpty()) "PINを入力" else "●".repeat(pin.length),
                        compact = responsive.isCompact,
                        heightDp = if (responsive.isCompact) RegisterLayoutPolicy.COMPACT_VALUE_HEIGHT_DP else null,
                    )
                    if (message != null) {
                        Spacer(Modifier.height(if (responsive.isCompact) 6.dp else 10.dp))
                        Text(
                            message,
                            color = Danger,
                            fontSize = if (responsive.isCompact) 12.sp else 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                        )
                    }
                    Spacer(Modifier.height(if (responsive.isCompact) 6.dp else 14.dp))
                    NumberPad(
                        onDigit = { if (pin.length < 8) pin += it },
                        onClear = { pin = "" },
                        bottomActionLabel = "ログイン",
                        onBottomAction = { selectedId?.let { onLogin(it, pin) } },
                        compact = responsive.isCompact,
                        buttonHeightDp = if (responsive.isCompact) RegisterLayoutPolicy.COMPACT_KEY_HEIGHT_DP else null,
                        rowGapDp = if (responsive.isCompact) RegisterLayoutPolicy.COMPACT_KEY_GAP_DP else null,
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
    corrections: List<CartCorrectionRecordV135>,
    selectedIndex: Int?,
    printerHealth: PrinterHealthSnapshot,
    onSelect: (Int) -> Unit,
    onEdit: (Int) -> Unit,
    onAddProduct: (Product, Int) -> Unit,
    onChangeQuantity: (Int) -> Unit,
    onRemove: () -> Unit,
    onCancelSelected: (Int) -> Unit,
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
    var pendingQuantity by remember { mutableStateOf<Int?>(null) }
    var showProductSearch by remember { mutableStateOf(false) }
    var lookupMessage by remember { mutableStateOf<String?>(null) }
    val responsive = rememberRegisterResponsiveMetrics()

    androidx.compose.runtime.DisposableEffect(products, pendingQuantity, onAddProduct) {
        val listener: (String) -> Unit = { scanned ->
            val product = ProductLookupPolicyV135.findExact(products, scanned)
            if (product == null) {
                lookupMessage = "商品未登録: ${scanned.take(20)}"
            } else {
                onAddProduct(product, pendingQuantity ?: 1)
                pendingQuantity = null
                numericInput = ""
                lookupMessage = null
            }
        }
        BarcodeScannerRuntimeV135.setListener(listener)
        onDispose { BarcodeScannerRuntimeV135.clearListener(listener) }
    }

    if (showProductSearch) {
        SalesProductSearchDialogV135(
            products = products,
            onDismiss = { showProductSearch = false },
            onRegister = { product ->
                onAddProduct(product, pendingQuantity ?: 1)
                pendingQuantity = null
                numericInput = ""
                lookupMessage = null
                showProductSearch = false
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Header("SCR-100", "販売画面")
        PrinterHealthBanner(printerHealth, onPrinterStatus)
        Row(
            Modifier.fillMaxWidth().height(48.dp).background(Color.White).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (responsive.isCompact) "担当：$operatorName" else "店舗：サンプル居酒屋  |  担当：$operatorName",
                color = Navy,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            if (accessMessage != null) {
                Text(accessMessage, color = Danger, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
            } else if (!responsive.isCompact) {
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

        Row(
            Modifier.weight(1f).padding(responsive.screenPaddingDp.dp),
            horizontalArrangement = Arrangement.spacedBy(responsive.panelGapDp.dp),
        ) {
            CardPanel(Modifier.weight(responsive.salesListWeight).fillMaxHeight()) {
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
                                if (selected) {
                                    Text(
                                        "選択中",
                                        color = Navy,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                    )
                                }
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
                    if (corrections.isNotEmpty()) {
                        item {
                            Text(
                                "訂正履歴",
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                                color = Danger,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        itemsIndexed(corrections) { _, correction ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("取消 ${correction.productName}", color = Danger, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "${correction.cancelledQuantity} × ${yen(correction.unitPrice)} / 元行 ${correction.lineId.takeLast(8)}",
                                        fontSize = 12.sp,
                                        color = Color.Gray,
                                    )
                                }
                                Text("-${yen(correction.cancelledAmount)}", color = Danger, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${cart.sumOf { it.quantity }}点")
                    Spacer(Modifier.weight(1f))
                    Text("合計 ${yen(summary.grossAmount)}", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Navy)
                }
            }

            CardPanel(Modifier.weight(responsive.salesKeypadWeight).fillMaxHeight()) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val keypad = RegisterResponsiveLayoutPolicy.keypadMetrics(
                        availableHeightDp = maxHeight.value.toInt(),
                        functionRows = 2,
                    )
                    val keypadScroll = rememberScrollState()
                    Column(
                        Modifier
                            .fillMaxSize()
                            .then(
                                if (keypad.scrollRequired) Modifier.verticalScroll(keypadScroll) else Modifier,
                            ),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().height(keypad.valueHeightDp.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                lookupMessage ?: pendingQuantity?.let { "次商品 ${it}点" } ?: "置数・機能",
                                fontSize = if (responsive.isCompact) 16.sp else 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Navy,
                                maxLines = 1,
                            )
                            Spacer(Modifier.width(keypad.gapDp.dp))
                            ValueBox(
                                if (numericInput.isBlank()) "0" else numericInput,
                                compact = true,
                                modifier = Modifier.weight(1f),
                                heightDp = keypad.valueHeightDp,
                            )
                        }
                        Spacer(Modifier.height(keypad.gapDp.dp))
                        NumberPad(
                            onDigit = { if (numericInput.length < 5) numericInput += it },
                            onClear = { numericInput = "" },
                            bottomActionLabel = "数量",
                            onBottomAction = {
                                ProductQuantityKeyPolicyV135.decide(numericInput, selectedIndex != null)?.let { decision ->
                                    decision.selectedLineQuantity?.let(onChangeQuantity)
                                    decision.pendingProductQuantity?.let { pendingQuantity = it }
                                    lookupMessage = null
                                }
                                numericInput = ""
                            },
                            compact = true,
                            buttonHeightDp = keypad.keyHeightDp,
                            rowGapDp = keypad.gapDp,
                        )
                        Spacer(Modifier.height(keypad.gapDp.dp))
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(keypad.gapDp.dp),
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        if (NumericCorrectionPolicyV135.shouldClearInput(numericInput)) {
                                            numericInput = ""
                                        } else {
                                            onRemove()
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(keypad.functionHeightDp.dp),
                                ) { Text("訂正", fontSize = 13.sp, maxLines = 1) }
                                OutlinedButton(
                                    onClick = {
                                        val selected = selectedIndex?.let { cart.getOrNull(it) }
                                        if (selected != null) {
                                            val quantity = numericInput.toIntOrNull() ?: selected.quantity
                                            onCancelSelected(quantity)
                                            numericInput = ""
                                        }
                                    },
                                    enabled = selectedIndex != null,
                                    modifier = Modifier.weight(1f).height(keypad.functionHeightDp.dp),
                                ) { Text("行取消", fontSize = 13.sp, maxLines = 1) }
                                OutlinedButton(
                                    onClick = { selectedIndex?.let(onEdit) },
                                    enabled = selectedIndex != null,
                                    modifier = Modifier.weight(1f).height(keypad.functionHeightDp.dp),
                                ) { Text("行編集", fontSize = 13.sp, maxLines = 1) }
                            }
                            Spacer(Modifier.height(keypad.gapDp.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(keypad.gapDp.dp),
                            ) {
                                OutlinedButton(
                                    onClick = onDiscount,
                                    enabled = cart.isNotEmpty(),
                                    modifier = Modifier.weight(1f).height(keypad.functionHeightDp.dp),
                                ) { Text("値引・割引", fontSize = 13.sp, maxLines = 1) }
                                OutlinedButton(
                                    onClick = { showProductSearch = true; lookupMessage = null },
                                    modifier = Modifier.weight(1f).height(keypad.functionHeightDp.dp),
                                ) { Text("商品検索", fontSize = 13.sp, maxLines = 1) }
                                TransactionAbortButtonV135(
                                    items = cart,
                                    modifier = Modifier.weight(1f).height(keypad.functionHeightDp.dp),
                                    onAbortCommitted = onCancelTransaction,
                                )
                            }
                        }
                    }
                }
            }

            CardPanel(Modifier.weight(responsive.salesProductsWeight).fillMaxHeight()) {
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
                                        onClick = {
                                            onAddProduct(product, pendingQuantity ?: 1)
                                            pendingQuantity = null
                                            numericInput = ""
                                            lookupMessage = null
                                        },
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
            Modifier
                .fillMaxWidth()
                .height(responsive.bottomBarHeightDp.dp)
                .padding(horizontal = responsive.screenPaddingDp.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(responsive.panelGapDp.dp),
        ) {
            OutlinedButton(onClick = onTickets, modifier = Modifier.weight(1f).fillMaxHeight()) { Text("伝票一覧", maxLines = 1) }
            OutlinedButton(onClick = onHold, enabled = cart.isNotEmpty(), modifier = Modifier.weight(0.8f).fillMaxHeight()) { Text("保留", maxLines = 1) }
            OutlinedButton(onClick = onSalesHistory, modifier = Modifier.weight(1f).fillMaxHeight()) { Text("売上一覧", maxLines = 1) }
            OutlinedButton(onClick = onPrinterStatus, modifier = Modifier.weight(1f).fillMaxHeight()) { Text("プリンター", maxLines = 1) }
            OutlinedButton(onClick = onPrintQueue, modifier = Modifier.weight(1f).fillMaxHeight()) { Text("印刷管理", maxLines = 1) }
            BlueButton(
                "小計／会計  ${yen(summary.grossAmount)}",
                onPayment,
                Modifier.weight(if (responsive.isCompact) 2.2f else 2.8f).fillMaxHeight(),
                cart.isNotEmpty(),
            )
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
    val responsive = rememberRegisterResponsiveMetrics()
    val editScroll = rememberScrollState()
    val editSummaryScroll = rememberScrollState()

    Column(Modifier.fillMaxSize()) {
        Header("SCR-120", "行編集")
        Row(
            Modifier.weight(1f).padding(responsive.screenPaddingDp.dp),
            horizontalArrangement = Arrangement.spacedBy(responsive.panelGapDp.dp),
        ) {
            CardPanel(
                Modifier
                    .weight(if (responsive.isCompact) 1.28f else 1f)
                    .fillMaxHeight(),
            ) {
                Column(Modifier.fillMaxSize().verticalScroll(editScroll)) {
                    Text(
                        item.product.name,
                        fontSize = if (responsive.isCompact) 22.sp else 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Navy,
                    )
                    Spacer(Modifier.height(if (responsive.isCompact) 10.dp else 18.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(if (responsive.isCompact) 8.dp else 14.dp),
                    ) {
                        NumericField("数量", quantity, { quantity = it }, Modifier.weight(1f))
                        NumericField("単価", unitPrice, { unitPrice = it }, Modifier.weight(1f))
                        NumericField("行値引", discount, { discount = it }, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(if (responsive.isCompact) 10.dp else 18.dp))
                    Text("税区分", fontSize = if (responsive.isCompact) 16.sp else 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(if (responsive.isCompact) 5.dp else 8.dp))
                    val taxes = TaxCategory.entries
                    for (rowStart in taxes.indices step 3) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(if (responsive.isCompact) 5.dp else 8.dp),
                        ) {
                            for (index in rowStart until minOf(rowStart + 3, taxes.size)) {
                                val tax = taxes[index]
                                OutlinedButton(
                                    onClick = { category = tax },
                                    modifier = Modifier.weight(1f),
                                    border = BorderStroke(
                                        if (category == tax) 3.dp else 1.dp,
                                        if (category == tax) Danger else Border,
                                    ),
                                ) {
                                    Text(
                                        "${tax.displayName} ${tax.symbol}",
                                        fontSize = if (responsive.isCompact) 12.sp else 14.sp,
                                    )
                                }
                            }
                            for (unused in minOf(rowStart + 3, taxes.size) until rowStart + 3) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                        Spacer(Modifier.height(if (responsive.isCompact) 5.dp else 8.dp))
                    }
                    Spacer(Modifier.height(if (responsive.isCompact) 6.dp else 12.dp))
                    OutlinedTextField(
                        value = note,
                        onValueChange = { if (it.length <= 100) note = it },
                        label = { Text("行メモ") },
                        modifier = Modifier.fillMaxWidth().height(if (responsive.isCompact) 88.dp else 110.dp),
                    )
                }
            }
            val summaryModifier = if (responsive.isCompact) {
                Modifier.weight(0.72f)
            } else {
                Modifier.width(330.dp)
            }
            CardPanel(summaryModifier.fillMaxHeight()) {
                Column(Modifier.weight(1f).verticalScroll(editSummaryScroll)) {
                    Text(
                        "変更後",
                        fontSize = if (responsive.isCompact) 18.sp else 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Navy,
                    )
                    Spacer(Modifier.height(if (responsive.isCompact) 8.dp else 18.dp))
                    AmountRow("数量", "${parsedQuantity}点")
                    AmountRow("単価", yen(parsedUnitPrice))
                    AmountRow("値引", "-${yen(parsedDiscount)}")
                    AmountRow("金額", yen(maxDiscount - parsedDiscount), emphasized = true)
                }
                Spacer(Modifier.height(responsive.panelGapDp.dp))
                OutlinedButton(
                    onClick = onDiscount,
                    modifier = Modifier.fillMaxWidth().height(if (responsive.isCompact) 46.dp else 52.dp),
                ) { Text("値引・割引画面", maxLines = 1) }
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
    val responsive = rememberRegisterResponsiveMetrics()
    val discountEditScroll = rememberScrollState()
    val discountPreviewScroll = rememberScrollState()

    Column(Modifier.fillMaxSize()) {
        Header("SCR-121", "値引・割引")
        Row(
            Modifier.weight(1f).padding(responsive.screenPaddingDp.dp),
            horizontalArrangement = Arrangement.spacedBy(responsive.panelGapDp.dp),
        ) {
            CardPanel(
                Modifier
                    .weight(if (responsive.isCompact) 1.08f else 1f)
                    .fillMaxHeight(),
            ) {
                Column(Modifier.fillMaxSize().verticalScroll(discountEditScroll)) {
                    Text(
                        "適用範囲",
                        fontSize = if (responsive.isCompact) 17.sp else 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Navy,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(if (responsive.isCompact) 6.dp else 10.dp),
                    ) {
                        ChoiceButton("選択商品", scope == DiscountScope.ITEM, selectedIndex != null, Modifier.weight(1f)) { scope = DiscountScope.ITEM }
                        ChoiceButton("伝票全体", scope == DiscountScope.TRANSACTION, true, Modifier.weight(1f)) { scope = DiscountScope.TRANSACTION }
                    }
                    Spacer(Modifier.height(if (responsive.isCompact) 10.dp else 18.dp))
                    Text(
                        "方式",
                        fontSize = if (responsive.isCompact) 17.sp else 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Navy,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(if (responsive.isCompact) 6.dp else 10.dp),
                    ) {
                        ChoiceButton("定額値引", type == DiscountType.FIXED, true, Modifier.weight(1f)) { type = DiscountType.FIXED }
                        ChoiceButton("率割引", type == DiscountType.PERCENT, true, Modifier.weight(1f)) { type = DiscountType.PERCENT }
                    }
                    Spacer(Modifier.height(if (responsive.isCompact) 10.dp else 18.dp))
                    NumericField(
                        if (type == DiscountType.FIXED) "値引額" else "割引率（%）",
                        value,
                        { value = it },
                        Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(if (responsive.isCompact) 7.dp else 12.dp))
                    Text(
                        "伝票値引は各明細へ比例配賦し、端数を最終行へ配賦します",
                        color = Color.Gray,
                        fontSize = if (responsive.isCompact) 12.sp else 14.sp,
                    )
                }
            }
            val previewModifier = if (responsive.isCompact) {
                Modifier.weight(0.92f)
            } else {
                Modifier.width(390.dp)
            }
            CardPanel(previewModifier.fillMaxHeight()) {
                Column(Modifier.fillMaxSize().verticalScroll(discountPreviewScroll)) {
                    Text(
                        "税率別プレビュー",
                        fontSize = if (responsive.isCompact) 18.sp else 21.sp,
                        fontWeight = FontWeight.Bold,
                        color = Navy,
                    )
                    Spacer(Modifier.height(if (responsive.isCompact) 8.dp else 14.dp))
                    AmountRow("変更前合計", yen(before.grossAmount))
                    AmountRow("変更後合計", yen(after.grossAmount), emphasized = true)
                    AmountRow("値引合計", "-${yen((before.grossAmount - after.grossAmount).coerceAtLeast(0))}")
                    Spacer(Modifier.height(if (responsive.isCompact) 7.dp else 12.dp))
                    after.buckets.forEach { bucket ->
                        val label = if (bucket.taxable) "${bucket.ratePercent}%対象" else "非課税"
                        AmountRow(label, "${yen(bucket.grossAmount)} / 税 ${yen(bucket.taxAmount)}")
                    }
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
    onMerge: (HeldTicket, HeldTicket) -> Unit,
    onSplit: (HeldTicket) -> Unit,
    onPrint: (HeldTicket) -> Unit,
    onBack: () -> Unit,
) {
    var editingTicketId by remember { mutableStateOf<Long?>(null) }
    var editingName by remember { mutableStateOf("") }
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }
    var mergeSourceId by remember { mutableStateOf<Long?>(null) }
    var pendingMergeTargetId by remember { mutableStateOf<Long?>(null) }
    val mergeSource = mergeSourceId?.let { sourceId -> tickets.firstOrNull { it.id == sourceId } }
    val ticketResponsive = rememberRegisterResponsiveMetrics()

    Column(Modifier.fillMaxSize()) {
        Header("SCR-200", "伝票一覧")
        if (currentCartCount > 0) {
  Card(
      colors = CardDefaults.cardColors(containerColor = PaleYellow),
      modifier = Modifier.fillMaxWidth().padding(
          horizontal = ticketResponsive.screenPaddingDp.dp,
          vertical = if (ticketResponsive.isCompact) 4.dp else 8.dp,
      ),
  ) {
      Text(
          "作業中の$currentCartCount 点は、別伝票を呼び出す前に自動で保留へ退避します。",
          modifier = Modifier.padding(if (ticketResponsive.isCompact) 8.dp else 12.dp),
          color = Navy,
          fontWeight = FontWeight.Bold,
          fontSize = if (ticketResponsive.isCompact) 12.sp else 14.sp,
      )
  }
        }
        if (mergeSource != null) {
  Card(
      colors = CardDefaults.cardColors(containerColor = PaleBlue),
      modifier = Modifier.fillMaxWidth().padding(
          horizontal = ticketResponsive.screenPaddingDp.dp,
          vertical = if (ticketResponsive.isCompact) 3.dp else 6.dp,
      ),
  ) {
      if (ticketResponsive.isCompact) {
          Column(Modifier.fillMaxWidth().padding(8.dp)) {
              Text(
                  "${mergeSource.name} を結合元として選択中です。結合先を選び、もう一度［結合確定］を押してください。",
                  color = Navy,
                  fontWeight = FontWeight.Bold,
                  fontSize = 12.sp,
              )
              Spacer(Modifier.height(5.dp))
              CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                  OutlinedButton(
                      onClick = {
                          mergeSourceId = null
                          pendingMergeTargetId = null
                      },
                      modifier = Modifier.fillMaxWidth().height(40.dp),
                  ) { Text("結合を取消", fontSize = 12.sp, maxLines = 1) }
              }
          }
      } else {
          Row(
              modifier = Modifier.fillMaxWidth().padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
              Text(
                  "${mergeSource.name} を結合元として選択中です。結合先を選び、もう一度［結合確定］を押してください。",
                  modifier = Modifier.weight(1f),
                  color = Navy,
                  fontWeight = FontWeight.Bold,
              )
              OutlinedButton(onClick = {
                  mergeSourceId = null
                  pendingMergeTargetId = null
              }) { Text("結合を取消") }
          }
      }
  }
        }
        if (!message.isNullOrBlank()) {
  Text(
      message,
      modifier = Modifier.fillMaxWidth().padding(
          horizontal = ticketResponsive.screenPaddingDp.dp,
          vertical = if (ticketResponsive.isCompact) 3.dp else 6.dp,
      ),
      color = if (message.contains("できません") || message.contains("見つかりません")) Danger else Color(0xFF2E7D32),
      fontWeight = FontWeight.Bold,
      fontSize = if (ticketResponsive.isCompact) 12.sp else 14.sp,
  )
        }
        CardPanel(
  Modifier
      .weight(1f)
      .fillMaxWidth()
      .padding(horizontal = ticketResponsive.screenPaddingDp.dp),
        ) {
  if (tickets.isEmpty()) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text(
              "保留伝票はありません",
              fontSize = if (ticketResponsive.isCompact) 20.sp else 24.sp,
              color = Color.Gray,
          )
      }
  } else {
      LazyColumn {
          itemsIndexed(tickets, key = { _, ticket -> ticket.id }) { _, ticket ->
              Column(
                  Modifier
                      .fillMaxWidth()
                      .padding(vertical = if (ticketResponsive.isCompact) 4.dp else 7.dp)
                      .background(
                          when {
                              pendingDeleteId == ticket.id -> Color(0xFFFFEBEE)
                              mergeSourceId == ticket.id -> PaleBlue
                              pendingMergeTargetId == ticket.id -> PaleYellow
                              else -> Color.Transparent
                          },
                          RoundedCornerShape(8.dp),
                      )
                      .padding(if (ticketResponsive.isCompact) 6.dp else 8.dp),
              ) {
                  if (ticketResponsive.isCompact) {
                      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                          Column(Modifier.weight(1f)) {
                              Text(ticket.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Navy, maxLines = 1)
                              Text(
                                  "${formatDate(ticket.createdAt)} / ${ticket.operatorName}${if (ticket.guestCount > 0) " / ${ticket.guestCount}名" else ""} / ${ticket.itemCount}点",
                                  color = Color.Gray,
                                  fontSize = 11.sp,
                                  maxLines = 1,
                              )
                          }
                          Spacer(Modifier.width(6.dp))
                          Text(yen(ticket.totalAmount), fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                      }
                      Spacer(Modifier.height(5.dp))
                      CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                          Row(
                              Modifier.fillMaxWidth(),
                              horizontalArrangement = Arrangement.spacedBy(5.dp),
                          ) {
                              OutlinedButton(
                                  onClick = {
                                      editingTicketId = ticket.id
                                      editingName = ticket.name
                                      pendingDeleteId = null
                                      mergeSourceId = null
                                      pendingMergeTargetId = null
                                  },
                                  modifier = Modifier.weight(1f).height(42.dp),
                              ) { Text("名称変更", fontSize = 12.sp, maxLines = 1) }
                              OutlinedButton(
                                  onClick = {
                                      if (pendingDeleteId == ticket.id) {
                                          pendingDeleteId = null
                                          onDelete(ticket)
                                      } else {
                                          pendingDeleteId = ticket.id
                                          editingTicketId = null
                                          mergeSourceId = null
                                          pendingMergeTargetId = null
                                      }
                                  },
                                  modifier = Modifier.weight(1f).height(42.dp),
                              ) {
                                  Text(
                                      if (pendingDeleteId == ticket.id) "削除確定" else "削除",
                                      color = Danger,
                                      fontWeight = FontWeight.Bold,
                                      fontSize = 12.sp,
                                      maxLines = 1,
                                  )
                              }
                              OutlinedButton(
                                  onClick = { onPrint(ticket) },
                                  modifier = Modifier.weight(0.9f).height(42.dp),
                              ) { Text("仮締め", fontSize = 12.sp, maxLines = 1) }
                              BlueButton(
                                  if (currentCartCount > 0) "退避して呼出" else "呼出",
                                  { onLoad(ticket) },
                                  Modifier.weight(1.25f).height(42.dp),
                              )
                          }
                      }
                  } else {
                      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                          Column(Modifier.weight(1f)) {
                              Text(ticket.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Navy)
                              Text(
                                  "${formatDate(ticket.createdAt)} / 担当 ${ticket.operatorName}${if (ticket.guestCount > 0) " / ${ticket.guestCount}名" else ""} / ${ticket.itemCount}点",
                                  color = Color.Gray,
                              )
                          }
                          Text(yen(ticket.totalAmount), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                          Spacer(Modifier.width(10.dp))
                          OutlinedButton(onClick = {
                              editingTicketId = ticket.id
                              editingName = ticket.name
                              pendingDeleteId = null
                              mergeSourceId = null
                              pendingMergeTargetId = null
                          }) { Text("名称変更") }
                          Spacer(Modifier.width(8.dp))
                          OutlinedButton(onClick = {
                              if (pendingDeleteId == ticket.id) {
                                  pendingDeleteId = null
                                  onDelete(ticket)
                              } else {
                                  pendingDeleteId = ticket.id
                                  editingTicketId = null
                                  mergeSourceId = null
                                  pendingMergeTargetId = null
                              }
                          }) {
                              Text(
                                  if (pendingDeleteId == ticket.id) "削除確定" else "削除",
                                  color = Danger,
                                  fontWeight = FontWeight.Bold,
                              )
                          }
                          Spacer(Modifier.width(8.dp))
                          OutlinedButton(
                              onClick = { onPrint(ticket) },
                              modifier = Modifier.width(92.dp),
                          ) { Text("仮締め") }
                          Spacer(Modifier.width(8.dp))
                          BlueButton(
                              if (currentCartCount > 0) "退避して呼出" else "呼出",
                              { onLoad(ticket) },
                              Modifier.width(if (currentCartCount > 0) 145.dp else 105.dp),
                          )
                      }
                  }
                  CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides if (ticketResponsive.isCompact) 0.dp else LocalMinimumInteractiveComponentSize.current) {
                      Row(
                          modifier = Modifier.fillMaxWidth().padding(top = if (ticketResponsive.isCompact) 5.dp else 6.dp),
                          horizontalArrangement = if (ticketResponsive.isCompact) {
                              Arrangement.spacedBy(5.dp)
                          } else {
                              Arrangement.End
                          },
                          verticalAlignment = Alignment.CenterVertically,
                      ) {
                          if (mergeSource == null) {
                              OutlinedButton(
                                  onClick = {
                                      mergeSourceId = ticket.id
                                      pendingMergeTargetId = null
                                      editingTicketId = null
                                      pendingDeleteId = null
                                  },
                                  modifier = if (ticketResponsive.isCompact) Modifier.weight(1f).height(40.dp) else Modifier,
                              ) { Text("結合", fontSize = if (ticketResponsive.isCompact) 12.sp else 14.sp) }
                              if (!ticketResponsive.isCompact) Spacer(Modifier.width(8.dp))
                              OutlinedButton(
                                  onClick = { onSplit(ticket) },
                                  modifier = if (ticketResponsive.isCompact) Modifier.weight(1f).height(40.dp) else Modifier,
                              ) { Text("分割", fontSize = if (ticketResponsive.isCompact) 12.sp else 14.sp) }
                          } else if (mergeSource.id == ticket.id) {
                              OutlinedButton(
                                  onClick = {
                                      mergeSourceId = null
                                      pendingMergeTargetId = null
                                  },
                                  modifier = if (ticketResponsive.isCompact) Modifier.fillMaxWidth().height(40.dp) else Modifier,
                              ) { Text("結合元を取消", fontSize = if (ticketResponsive.isCompact) 12.sp else 14.sp) }
                          } else if (pendingMergeTargetId == ticket.id) {
                              Button(
                                  onClick = {
                                      val source = mergeSource
                                      mergeSourceId = null
                                      pendingMergeTargetId = null
                                      onMerge(source, ticket)
                                  },
                                  modifier = if (ticketResponsive.isCompact) Modifier.fillMaxWidth().height(40.dp) else Modifier,
                                  colors = ButtonDefaults.buttonColors(containerColor = Danger),
                              ) { Text("結合確定", fontSize = if (ticketResponsive.isCompact) 12.sp else 14.sp) }
                          } else {
                              OutlinedButton(
                                  onClick = {
                                      pendingMergeTargetId = ticket.id
                                      editingTicketId = null
                                      pendingDeleteId = null
                                  },
                                  modifier = if (ticketResponsive.isCompact) Modifier.fillMaxWidth().height(40.dp) else Modifier,
                              ) { Text("この伝票へ結合", fontSize = if (ticketResponsive.isCompact) 12.sp else 14.sp) }
                          }
                      }
                  }
                  if (pendingMergeTargetId == ticket.id && mergeSource != null) {
                      Text(
                          "${mergeSource.name} の全明細を ${ticket.name} の末尾へ結合します。元伝票は結合成功時のみ削除されます。",
                          color = Danger,
                          fontWeight = FontWeight.Bold,
                          fontSize = if (ticketResponsive.isCompact) 12.sp else 14.sp,
                          modifier = Modifier.padding(top = 6.dp),
                      )
                  }
                  if (pendingDeleteId == ticket.id) {
                      Text(
                          "もう一度［削除確定］を押すと、この伝票を完全に削除します。",
                          color = Danger,
                          fontWeight = FontWeight.Bold,
                          fontSize = if (ticketResponsive.isCompact) 12.sp else 14.sp,
                          modifier = Modifier.padding(top = 6.dp),
                      )
                  }
                  if (editingTicketId == ticket.id) {
                      if (ticketResponsive.isCompact) {
                          Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                              OutlinedTextField(
                                  value = editingName,
                                  onValueChange = {
                                      editingName = it.take(HeldTicketSafetyPolicy.MAX_NAME_LENGTH)
                                  },
                                  label = { Text("伝票名") },
                                  singleLine = true,
                                  modifier = Modifier.fillMaxWidth(),
                              )
                              Spacer(Modifier.height(5.dp))
                              CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                                  Row(
                                      Modifier.fillMaxWidth(),
                                      horizontalArrangement = Arrangement.spacedBy(5.dp),
                                  ) {
                                      OutlinedButton(
                                          onClick = {
                                              editingTicketId = null
                                              editingName = ""
                                          },
                                          modifier = Modifier.weight(1f).height(42.dp),
                                      ) { Text("取消", fontSize = 12.sp) }
                                      BlueButton(
                                          "保存",
                                          {
                                              onRename(ticket, editingName)
                                              editingTicketId = null
                                          },
                                          Modifier.weight(1f).height(42.dp),
                                      )
                                  }
                              }
                          }
                      } else {
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
        }
        BottomActions(onBack, "販売へ戻る", onBack)
    }
}

@Composable
private fun TicketSplitScreen(
    ticket: HeldTicket,
    items: List<CartItem>,
    suggestedName: String,
    externalMessage: String?,
    onConfirm: (Map<Int, Int>, String) -> Unit,
    onBack: () -> Unit,
) {
    var newName by remember(ticket.id, suggestedName) { mutableStateOf(suggestedName) }
    var rawQuantities by remember(ticket.id, items.size) { mutableStateOf(List(items.size) { "0" }) }
    val validation = HeldTicketOperationsUiPolicy.validateSplit(items, rawQuantities, newName)
    val responsive = rememberRegisterResponsiveMetrics()
    val splitSummaryScroll = rememberScrollState()

    Column(Modifier.fillMaxSize()) {
        Header("SCR-201", "伝票分割")
        if (!externalMessage.isNullOrBlank()) {
  Text(
      externalMessage,
      modifier = Modifier.fillMaxWidth().padding(
          horizontal = responsive.screenPaddingDp.dp,
          vertical = if (responsive.isCompact) 3.dp else 6.dp,
      ),
      color = Danger,
      fontWeight = FontWeight.Bold,
      fontSize = if (responsive.isCompact) 12.sp else 14.sp,
  )
        }
        Row(
  modifier = Modifier
      .weight(1f)
      .fillMaxWidth()
      .padding(responsive.screenPaddingDp.dp),
  horizontalArrangement = Arrangement.spacedBy(responsive.panelGapDp.dp),
        ) {
  CardPanel(
      Modifier
          .weight(if (responsive.isCompact) 1.2f else 1f)
          .fillMaxHeight(),
  ) {
      Text(
          "分割元: ${ticket.name}",
          fontSize = if (responsive.isCompact) 18.sp else 22.sp,
          fontWeight = FontWeight.Bold,
          color = Navy,
          maxLines = 1,
      )
      Text(
          "明細ごとに新しい伝票へ移す数量を入力します。元伝票を空にはできません。",
          color = Color.Gray,
          fontSize = if (responsive.isCompact) 11.sp else 14.sp,
      )
      Spacer(Modifier.height(if (responsive.isCompact) 6.dp else 10.dp))
      LazyColumn(modifier = Modifier.weight(1f)) {
          itemsIndexed(items) { index, item ->
              Row(
                  modifier = Modifier.fillMaxWidth().padding(vertical = if (responsive.isCompact) 3.dp else 6.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(if (responsive.isCompact) 6.dp else 10.dp),
              ) {
                  Column(Modifier.weight(1f)) {
                      Text(
                          item.product.name,
                          fontWeight = FontWeight.Bold,
                          color = Navy,
                          fontSize = if (responsive.isCompact) 13.sp else 14.sp,
                          maxLines = 1,
                      )
                      Text(
                          "元数量 ${item.quantity}点 / 単価 ${yen(item.unitPrice)} / 税 ${item.product.taxSymbol}",
                          color = Color.Gray,
                          fontSize = if (responsive.isCompact) 11.sp else 14.sp,
                          maxLines = if (responsive.isCompact) 2 else 1,
                      )
                      if (item.discountAmount != 0L) {
                          Text(
                              "行値引 ${yen(item.discountAmount)}",
                              color = Color.Gray,
                              fontSize = if (responsive.isCompact) 11.sp else 14.sp,
                          )
                      }
                  }
                  OutlinedTextField(
                      value = rawQuantities.getOrElse(index) { "0" },
                      onValueChange = { raw ->
                          val sanitized = raw.filter(Char::isDigit).take(6)
                          rawQuantities = rawQuantities.toMutableList().also { rows ->
                              rows[index] = sanitized
                          }
                      },
                      label = { Text("移動数量") },
                      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                      singleLine = true,
                      modifier = Modifier.width(if (responsive.isCompact) 108.dp else 130.dp),
                  )
              }
          }
      }
  }
  val splitSummaryModifier = if (responsive.isCompact) {
      Modifier.weight(0.8f)
  } else {
      Modifier.width(350.dp)
  }
  CardPanel(splitSummaryModifier.fillMaxHeight()) {
      Column(Modifier.fillMaxSize().verticalScroll(splitSummaryScroll)) {
          Text(
              "分割先",
              fontSize = if (responsive.isCompact) 18.sp else 22.sp,
              fontWeight = FontWeight.Bold,
              color = Navy,
          )
          Spacer(Modifier.height(if (responsive.isCompact) 6.dp else 10.dp))
          OutlinedTextField(
              value = newName,
              onValueChange = { newName = it.take(HeldTicketSafetyPolicy.MAX_NAME_LENGTH) },
              label = { Text("新しい伝票名") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
          )
          Spacer(Modifier.height(if (responsive.isCompact) 8.dp else 14.dp))
          AmountRow("移動点数", "${validation.movedCount}点", emphasized = validation.canConfirm)
          AmountRow("元伝票残数", "${validation.remainingCount}点")
          Spacer(Modifier.height(if (responsive.isCompact) 8.dp else 14.dp))
          Text(
              "数量の一部を分ける場合、行値引は数量比で按分し、税スナップショットは双方へ維持します。",
              color = Color.Gray,
              fontSize = if (responsive.isCompact) 12.sp else 14.sp,
          )
          if (validation.message != null) {
              Spacer(Modifier.height(if (responsive.isCompact) 7.dp else 12.dp))
              Text(
                  validation.message,
                  color = if (validation.canConfirm) Color(0xFF2E7D32) else Danger,
                  fontWeight = FontWeight.Bold,
                  fontSize = if (responsive.isCompact) 12.sp else 14.sp,
              )
          }
      }
  }
        }
        BottomActions(
  onBack = onBack,
  confirmLabel = "分割実行",
  onConfirm = { onConfirm(validation.movedQuantities, newName) },
  confirmEnabled = validation.canConfirm,
        )
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
    val responsive = rememberRegisterResponsiveMetrics()

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
        Row(
            Modifier.weight(1f).padding(responsive.screenPaddingDp.dp),
            horizontalArrangement = Arrangement.spacedBy(responsive.panelGapDp.dp),
        ) {
            CardPanel(Modifier.weight(responsive.paymentDetailWeight).fillMaxHeight()) {
                Text("会計内訳", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Navy)
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(items) { _, item ->
                        AmountRow(
                            "${item.product.name} × ${item.quantity} ${item.product.taxSymbol}",
                            yen(item.amountBeforeDiscount),
                        )
                        if (item.discountAmount > 0L) {
                            AmountRow("  値引", "-${yen(item.discountAmount)}")
                        }
                    }
                }
                AmountRow("商品計", yen(items.sumOf { it.baseAmount }))
                summary.buckets.forEach { bucket ->
                    if (bucket.taxable) {
                        AmountRow(
                            "${bucket.ratePercent}%対象",
                            "${yen(bucket.grossAmount)} / 税 ${yen(bucket.taxAmount)}",
                        )
                    } else {
                        AmountRow("非課税", yen(bucket.grossAmount))
                    }
                }
                AmountRow("合計", yen(summary.grossAmount), emphasized = true)
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
            CardPanel(Modifier.weight(responsive.paymentKeypadWeight).fillMaxHeight()) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val keypad = RegisterResponsiveLayoutPolicy.keypadMetrics(
                        availableHeightDp = maxHeight.value.toInt(),
                        functionRows = 1,
                        reservedTopDp = if (responsive.isCompact) 104 else 126,
                    )
                    val keypadScroll = rememberScrollState()
                    Column(
                        Modifier
                            .fillMaxSize()
                            .then(
                                if (keypad.scrollRequired) Modifier.verticalScroll(keypadScroll) else Modifier,
                            ),
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(responsive.panelGapDp.dp)) {
                            Column(Modifier.weight(1f)) {
                                PaymentAmountRow("合計", yen(summary.grossAmount), emphasized = true)
                                PaymentAmountRow("支払済", yen(state.paidAmount))
                            }
                            Column(Modifier.weight(1f)) {
                                PaymentAmountRow("残額", yen(remaining), emphasized = true)
                                PaymentAmountRow("お釣り", yen(state.changeAmount))
                            }
                        }
                        Spacer(Modifier.height(keypad.gapDp.dp))
                        LazyColumn(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = keypad.allocationListMaxHeightDp.dp),
                        ) {
                            itemsIndexed(state.allocations) { index, payment ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${payment.method.displayName} ${yen(payment.appliedAmount)}",
                                        Modifier.weight(1f),
                                        maxLines = 1,
                                    )
                                    OutlinedButton(onClick = { onStateChange(PaymentEngine.removeAt(state, index)) }) {
                                        Text("取消", maxLines = 1)
                                    }
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
                        ValueBox(
                            if (input.isBlank()) "残額全額" else input,
                            compact = true,
                            heightDp = keypad.valueHeightDp,
                        )
                        Spacer(Modifier.height(keypad.gapDp.dp))
                        NumberPad(
                            onDigit = { if (input.length < 10) input += it },
                            onClear = { input = "" },
                            bottomActionLabel = "現金",
                            onBottomAction = { add(PaymentMethod.CASH) },
                            compact = true,
                            buttonHeightDp = keypad.keyHeightDp,
                            rowGapDp = keypad.gapDp,
                        )
                        Spacer(Modifier.height(keypad.gapDp.dp))
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(keypad.gapDp.dp),
                            ) {
                                OutlinedButton(
                                    onClick = { add(PaymentMethod.CARD) },
                                    enabled = remaining > 0 && !completing,
                                    modifier = Modifier.weight(1f).height(keypad.functionHeightDp.dp),
                                ) { Text("カード", fontSize = 13.sp, maxLines = 1) }
                                OutlinedButton(
                                    onClick = { add(PaymentMethod.GIFT_CERTIFICATE) },
                                    enabled = remaining > 0 && !completing,
                                    modifier = Modifier.weight(1f).height(keypad.functionHeightDp.dp),
                                ) { Text("商品券", fontSize = 13.sp, maxLines = 1) }
                                OutlinedButton(
                                    onClick = { add(PaymentMethod.ACCOUNT_RECEIVABLE) },
                                    enabled = remaining > 0 && !completing,
                                    modifier = Modifier.weight(1f).height(keypad.functionHeightDp.dp),
                                ) { Text("掛売", fontSize = 13.sp, maxLines = 1) }
                            }
                        }
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
    onVoucher: () -> Unit,
    onHistory: () -> Unit,
    onQueue: () -> Unit,
    onNext: () -> Unit,
) {
    val responsive = rememberRegisterResponsiveMetrics()
    val compactScroll = rememberScrollState()
    val bodyModifier = Modifier
        .fillMaxWidth()
        .padding(if (responsive.isCompact) responsive.screenPaddingDp.dp else 24.dp)
        .then(
            if (responsive.isCompact) Modifier.verticalScroll(compactScroll) else Modifier,
        )
    Column(Modifier.fillMaxSize()) {
        Header("SCR-320", "会計完了")
        Column(
            Modifier.weight(1f).then(bodyModifier),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (responsive.isCompact) Arrangement.Top else Arrangement.Center,
        ) {
            Text(
                "会計が完了しました",
                fontSize = if (responsive.isCompact) 22.sp else 32.sp,
                fontWeight = FontWeight.Bold,
                color = Navy,
                maxLines = 1,
            )
            Spacer(Modifier.height(if (responsive.isCompact) 8.dp else 20.dp))
            CardPanel(
                if (responsive.isCompact) {
                    Modifier.fillMaxWidth().height(190.dp)
                } else {
                    Modifier.width(620.dp).height(200.dp)
                },
            ) {
                AmountRow("売上番号", detail?.summary?.id?.toString() ?: "-")
                AmountRow("合計", yen(detail?.summary?.totalAmount ?: 0), emphasized = true)
                AmountRow("お釣り", yen(detail?.summary?.changeAmount ?: 0), emphasized = true)
                Text(
                    "売上・支払・印刷キューを同一トランザクションで保存済み",
                    color = Color(0xFF2E7D32),
                    fontSize = if (responsive.isCompact) 12.sp else 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.height(if (responsive.isCompact) 8.dp else 20.dp))
            if (responsive.isCompact) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(responsive.panelGapDp.dp),
                    ) {
                        OutlinedButton(
                            onClick = onReceipt,
                            enabled = detail != null,
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) { Text("レシート", maxLines = 1) }
                        OutlinedButton(
                            onClick = onVoucher,
                            enabled = detail != null,
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) { Text("領収書", maxLines = 1) }
                    }
                    Spacer(Modifier.height(responsive.panelGapDp.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(responsive.panelGapDp.dp),
                    ) {
                        OutlinedButton(
                            onClick = onQueue,
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) { Text("印刷キュー", maxLines = 1) }
                        OutlinedButton(
                            onClick = onHistory,
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) { Text("売上一覧", maxLines = 1) }
                    }
                    Spacer(Modifier.height(responsive.panelGapDp.dp))
                    BlueButton(
                        "次の取引",
                        onNext,
                        Modifier.fillMaxWidth().height(54.dp),
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onReceipt, enabled = detail != null) { Text("レシート確認") }
                    OutlinedButton(onClick = onVoucher, enabled = detail != null) { Text("領収書発行") }
                    OutlinedButton(onClick = onQueue) { Text("統合印刷キュー") }
                    OutlinedButton(onClick = onHistory) { Text("売上一覧") }
                    BlueButton("次の取引", onNext, Modifier.width(180.dp).height(54.dp))
                }
            }
        }
    }
}

@Composable
private fun SalesHistoryScreen(
    sales: List<SaleSummaryRecord>,
    onOpen: (SaleSummaryRecord) -> Unit,
    onDirectLookup: (Long) -> Boolean,
    onQueue: () -> Unit,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var minAmountText by remember { mutableStateOf("") }
    var maxAmountText by remember { mutableStateOf("") }
    var directSaleIdText by remember { mutableStateOf("") }
    var lookupMessage by remember { mutableStateOf<String?>(null) }

    val criteria = SalesHistoryCriteria(
        query = query,
        minAmount = minAmountText.toLongOrNull(),
        maxAmount = maxAmountText.toLongOrNull(),
    )
    val visibleSales = SalesHistoryLookupPolicy.filter(sales, criteria)
    val directSaleId = SalesHistoryLookupPolicy.parseDirectSaleId(directSaleIdText)
    val amountRangeInvalid = criteria.minAmount != null && criteria.maxAmount != null && criteria.minAmount > criteria.maxAmount
    val historyResponsive = rememberRegisterResponsiveMetrics()
    val historyFilterScroll = rememberScrollState()
    val filterModifier = Modifier
        .fillMaxWidth()
        .then(
  if (historyResponsive.isCompact) {
      Modifier.heightIn(max = 190.dp).verticalScroll(historyFilterScroll)
  } else {
      Modifier
  },
        )
        .padding(
  horizontal = historyResponsive.screenPaddingDp.dp,
  vertical = if (historyResponsive.isCompact) 5.dp else 8.dp,
        )

    Column(Modifier.fillMaxSize()) {
        Header("SCR-400", "売上一覧・検索")
        Column(filterModifier) {
  if (historyResponsive.isCompact) {
      Row(
          Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
          OutlinedTextField(
              value = query,
              onValueChange = { query = it.take(80) },
              label = { Text("売上No.・担当・支払") },
              singleLine = true,
              modifier = Modifier.weight(1f),
          )
          OutlinedButton(
              onClick = {
                  query = ""
                  minAmountText = ""
                  maxAmountText = ""
              },
              modifier = Modifier.width(96.dp).height(48.dp),
          ) { Text("クリア", fontSize = 12.sp, maxLines = 1) }
          OutlinedButton(
              onClick = onQueue,
              modifier = Modifier.width(132.dp).height(48.dp),
          ) { Text("印刷キュー", fontSize = 12.sp, maxLines = 1) }
      }
      Spacer(Modifier.height(5.dp))
      Row(
          Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
          OutlinedTextField(
              value = minAmountText,
              onValueChange = { minAmountText = it.filter(Char::isDigit).take(12) },
              label = { Text("金額以上") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              singleLine = true,
              modifier = Modifier.weight(1f),
          )
          OutlinedTextField(
              value = maxAmountText,
              onValueChange = { maxAmountText = it.filter(Char::isDigit).take(12) },
              label = { Text("金額以下") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              singleLine = true,
              modifier = Modifier.weight(1f),
          )
      }
  } else {
      Row(
          Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
          OutlinedTextField(
              value = query,
              onValueChange = { query = it.take(80) },
              label = { Text("売上No.・担当・支払") },
              singleLine = true,
              modifier = Modifier.weight(1f),
          )
          OutlinedTextField(
              value = minAmountText,
              onValueChange = { minAmountText = it.filter(Char::isDigit).take(12) },
              label = { Text("金額以上") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              singleLine = true,
              modifier = Modifier.width(150.dp),
          )
          OutlinedTextField(
              value = maxAmountText,
              onValueChange = { maxAmountText = it.filter(Char::isDigit).take(12) },
              label = { Text("金額以下") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              singleLine = true,
              modifier = Modifier.width(150.dp),
          )
          OutlinedButton(
              onClick = {
                  query = ""
                  minAmountText = ""
                  maxAmountText = ""
              },
          ) { Text("条件クリア") }
          OutlinedButton(onClick = onQueue) { Text("統合印刷キュー") }
      }
  }
  Row(
      Modifier.fillMaxWidth().padding(top = if (historyResponsive.isCompact) 5.dp else 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(if (historyResponsive.isCompact) 6.dp else 8.dp),
  ) {
      Text(
          "表示 ${visibleSales.size}件 / 読込 ${sales.size}件（最大${SalesHistoryLookupPolicy.RECENT_LOAD_LIMIT}件）",
          fontWeight = FontWeight.Bold,
          color = Navy,
          fontSize = if (historyResponsive.isCompact) 12.sp else 14.sp,
          maxLines = 1,
          modifier = Modifier.weight(1f),
      )
      OutlinedTextField(
          value = directSaleIdText,
          onValueChange = {
              directSaleIdText = it.filter { ch -> ch.isDigit() || ch == '#' }.take(20)
              lookupMessage = null
          },
          label = { Text("売上No.直接表示") },
          singleLine = true,
          modifier = if (historyResponsive.isCompact) Modifier.width(175.dp) else Modifier.width(210.dp),
      )
      Button(
          onClick = {
              val saleId = directSaleId ?: return@Button
              if (!onDirectLookup(saleId)) {
                  lookupMessage = "売上No.$saleId は見つかりません"
              }
          },
          enabled = directSaleId != null,
          modifier = if (historyResponsive.isCompact) Modifier.height(48.dp) else Modifier,
          colors = ButtonDefaults.buttonColors(containerColor = Blue),
      ) { Text("表示", fontSize = if (historyResponsive.isCompact) 12.sp else 14.sp, maxLines = 1) }
  }
  if (amountRangeInvalid) {
      Text(
          "金額範囲は『以上 ≤ 以下』になるよう入力してください",
          color = Danger,
          fontWeight = FontWeight.Bold,
          fontSize = if (historyResponsive.isCompact) 12.sp else 14.sp,
      )
  } else if (!lookupMessage.isNullOrBlank()) {
      Text(
          lookupMessage.orEmpty(),
          color = Danger,
          fontWeight = FontWeight.Bold,
          fontSize = if (historyResponsive.isCompact) 12.sp else 14.sp,
      )
  }
        }
        CardPanel(
  Modifier
      .weight(1f)
      .fillMaxWidth()
      .padding(horizontal = historyResponsive.screenPaddingDp.dp),
        ) {
  when {
      sales.isEmpty() -> {
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Text("売上はまだありません", color = Color.Gray, fontSize = if (historyResponsive.isCompact) 19.sp else 22.sp)
          }
      }
      visibleSales.isEmpty() -> {
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Text("条件に一致する売上はありません", color = Color.Gray, fontSize = if (historyResponsive.isCompact) 19.sp else 22.sp)
          }
      }
      else -> {
          LazyColumn {
              itemsIndexed(visibleSales) { _, sale ->
                  if (historyResponsive.isCompact) {
                      Column(
                          Modifier
                              .fillMaxWidth()
                              .clickable { onOpen(sale) }
                              .padding(vertical = 7.dp),
                      ) {
                          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                              Text("#${sale.id}", fontWeight = FontWeight.Bold, color = Navy, maxLines = 1)
                              Spacer(Modifier.width(8.dp))
                              Text(formatDate(sale.createdAt), modifier = Modifier.weight(1f), fontSize = 12.sp, maxLines = 1)
                              Text(yen(sale.totalAmount), fontWeight = FontWeight.Bold, maxLines = 1)
                          }
                          Spacer(Modifier.height(2.dp))
                          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                              Text(sale.operatorName, modifier = Modifier.weight(0.8f), fontSize = 11.sp, maxLines = 1)
                              Text(sale.paymentLabel, modifier = Modifier.weight(1f), fontSize = 11.sp, maxLines = 1)
                              Text("印字 ${sale.printCount}回", color = Color.Gray, fontSize = 11.sp, maxLines = 1)
                          }
                      }
                  } else {
                      Row(
                          Modifier.fillMaxWidth().clickable { onOpen(sale) }.padding(vertical = 11.dp),
                          verticalAlignment = Alignment.CenterVertically,
                      ) {
                          Text("#${sale.id}", Modifier.width(80.dp), fontWeight = FontWeight.Bold)
                          Text(formatDate(sale.createdAt), Modifier.width(165.dp))
                          Text(sale.operatorName, Modifier.width(100.dp))
                          Text(sale.paymentLabel, Modifier.weight(1f))
                          Text("印字 ${sale.printCount}回", Modifier.width(85.dp), color = Color.Gray)
                          Text(yen(sale.totalAmount), Modifier.width(130.dp), textAlign = TextAlign.End, fontWeight = FontWeight.Bold)
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
private fun SaleDetailScreen(
    detail: SaleDetailRecord,
    canReverse: Boolean,
    onReceipt: () -> Unit,
    onVoucher: () -> Unit,
    onReverse: () -> Unit,
    onBack: () -> Unit,
) {
    val detailResponsive = rememberRegisterResponsiveMetrics()
    val detailSummaryScroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        Header("SCR-410", "売上詳細")
        Row(
  Modifier.weight(1f).padding(detailResponsive.screenPaddingDp.dp),
  horizontalArrangement = Arrangement.spacedBy(detailResponsive.panelGapDp.dp),
        ) {
  CardPanel(
      Modifier
          .weight(if (detailResponsive.isCompact) 1.1f else 1f)
          .fillMaxHeight(),
  ) {
      Text(
          "売上 #${detail.summary.id}",
          fontSize = if (detailResponsive.isCompact) 20.sp else 24.sp,
          fontWeight = FontWeight.Bold,
          color = Navy,
          maxLines = 1,
      )
      Text(
          "${formatDate(detail.summary.createdAt)} / 担当 ${detail.summary.operatorName}",
          color = Color.Gray,
          fontSize = if (detailResponsive.isCompact) 12.sp else 14.sp,
          maxLines = 1,
      )
      Spacer(Modifier.height(if (detailResponsive.isCompact) 7.dp else 12.dp))
      LazyColumn(Modifier.weight(1f)) {
          itemsIndexed(detail.items) { _, item ->
              Column(Modifier.fillMaxWidth().padding(vertical = if (detailResponsive.isCompact) 4.dp else 6.dp)) {
                  AmountRow("${item.product.name} ${item.product.taxSymbol} × ${item.quantity}", yen(item.baseAmount))
                  if (item.discountAmount > 0) Text("値引 -${yen(item.discountAmount)}", color = Danger, fontSize = 12.sp)
                  if (item.note.isNotBlank()) Text("メモ ${item.note}", color = Color.Gray, fontSize = 12.sp)
              }
          }
      }
  }
  val detailSummaryModifier = if (detailResponsive.isCompact) {
      Modifier.weight(0.9f)
  } else {
      Modifier.width(380.dp)
  }
  CardPanel(detailSummaryModifier.fillMaxHeight()) {
      Column(Modifier.weight(1f).verticalScroll(detailSummaryScroll)) {
          AmountRow("税抜", yen(detail.taxSummary.netAmount))
          AmountRow("消費税", yen(detail.taxSummary.taxAmount))
          AmountRow("合計", yen(detail.taxSummary.grossAmount), emphasized = true)
          Spacer(Modifier.height(if (detailResponsive.isCompact) 6.dp else 10.dp))
          detail.taxSummary.buckets.forEach { bucket ->
              val label = if (bucket.taxable) "${bucket.ratePercent}%対象" else "非課税"
              AmountRow(label, "${yen(bucket.grossAmount)} / 税 ${yen(bucket.taxAmount)}")
          }
          Spacer(Modifier.height(if (detailResponsive.isCompact) 8.dp else 14.dp))
          Text("支払", fontWeight = FontWeight.Bold, fontSize = if (detailResponsive.isCompact) 14.sp else 16.sp)
          detail.payments.forEach { payment ->
              AmountRow(payment.method.displayName, yen(payment.receivedAmount))
          }
          AmountRow("お釣り", yen(detail.summary.changeAmount))
      }
      Spacer(Modifier.height(detailResponsive.panelGapDp.dp))
      BlueButton(
          "レシート／再印字",
          onReceipt,
          Modifier.fillMaxWidth().height(if (detailResponsive.isCompact) 46.dp else 52.dp),
      )
      Spacer(Modifier.height(if (detailResponsive.isCompact) 5.dp else 8.dp))
      OutlinedButton(
          onClick = onVoucher,
          modifier = Modifier.fillMaxWidth().height(if (detailResponsive.isCompact) 46.dp else 52.dp),
      ) {
          Text("この売上で領収書発行", fontSize = if (detailResponsive.isCompact) 12.sp else 14.sp, maxLines = 1)
      }
      if (canReverse) {
          Spacer(Modifier.height(if (detailResponsive.isCompact) 5.dp else 8.dp))
          OutlinedButton(
              onClick = onReverse,
              modifier = Modifier.fillMaxWidth().height(if (detailResponsive.isCompact) 46.dp else 52.dp),
          ) {
              Text(
                  "この売上を返品・取消",
                  color = Danger,
                  fontWeight = FontWeight.Bold,
                  fontSize = if (detailResponsive.isCompact) 12.sp else 14.sp,
                  maxLines = 1,
              )
          }
      }
  }
        }
        BottomActions(onBack, "一覧へ戻る", onBack)
    }
}

@Composable
private fun ReceiptPreviewScreen(
    detail: SaleDetailRecord,
    paper: ReceiptPaper,
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
                    Text(
                        "プリンタ設定 ${paper.widthMm}mm（印刷時は変更しません）",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                    )
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
                            Text("設定 ${job.paperWidthMm}mm", Modifier.width(95.dp))
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
    PrintJobStatus.DISCARDED -> Color.Gray
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
    buttonHeightDp: Int? = null,
    rowGapDp: Int? = null,
) {
    val resolvedButtonHeightDp = buttonHeightDp
        ?: if (compact) RegisterLayoutPolicy.COMPACT_KEY_HEIGHT_DP else 48
    val resolvedRowGapDp = rowGapDp
        ?: if (compact) RegisterLayoutPolicy.COMPACT_KEY_GAP_DP else 6
    val buttonHeight = resolvedButtonHeightDp.dp
    val rowGap = resolvedRowGapDp.dp
    val columnGap = if (compact) resolvedRowGapDp.dp else 8.dp
    val digitFontSize = when {
        resolvedButtonHeightDp >= 72 -> 24.sp
        resolvedButtonHeightDp >= 60 -> 21.sp
        resolvedButtonHeightDp >= 48 -> 18.sp
        else -> 16.sp
    }
    val content: @Composable () -> Unit = {
        for (rowStart in 1..9 step 3) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(columnGap)) {
                for (digit in rowStart until rowStart + 3) {
                    OutlinedButton(
                        onClick = { onDigit(digit.toString()) },
                        modifier = Modifier.weight(1f).height(buttonHeight),
                    ) {
                        Text(digit.toString(), fontSize = digitFontSize, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(rowGap))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(columnGap)) {
            OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f).height(buttonHeight)) {
                Text("C", color = Danger, fontSize = digitFontSize)
            }
            OutlinedButton(onClick = { onDigit("0") }, modifier = Modifier.weight(1f).height(buttonHeight)) {
                Text("0", fontSize = digitFontSize)
            }
            BlueButton(bottomActionLabel, onBottomAction, Modifier.weight(1f).height(buttonHeight))
        }
    }
    if (compact || buttonHeightDp != null) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) { content() }
    } else {
        content()
    }
}

@Composable
private fun ValueBox(
    value: String,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    heightDp: Int? = null,
) {
    val resolvedHeightDp = heightDp
        ?: if (compact) RegisterLayoutPolicy.COMPACT_VALUE_HEIGHT_DP else 54
    Box(
        modifier
            .fillMaxWidth()
            .height(resolvedHeightDp.dp)
            .background(PaleBlue, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(
            value,
            fontSize = if (resolvedHeightDp >= 60) 29.sp else 25.sp,
            fontWeight = FontWeight.Bold,
            color = Navy,
            maxLines = 1,
        )
    }
}

@Composable
private fun CardPanel(modifier: Modifier = Modifier, content: @Composable Column.() -> Unit) {
    val responsive = rememberRegisterResponsiveMetrics()
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Border),
    ) {
        Column(
            Modifier.fillMaxSize().padding(responsive.cardPaddingDp.dp),
            content = content,
        )
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
    val responsive = rememberRegisterResponsiveMetrics()
    Row(
        Modifier
            .fillMaxWidth()
            .height(responsive.bottomBarHeightDp.dp)
            .padding(horizontal = responsive.screenPaddingDp.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(responsive.panelGapDp.dp),
    ) {
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.weight(if (responsive.isCompact) 0.28f else 0.20f).fillMaxHeight(),
        ) { Text("戻る", maxLines = 1) }
        BlueButton(
            confirmLabel,
            onConfirm,
            Modifier.weight(1f).fillMaxHeight(),
            confirmEnabled,
        )
    }
}

private fun yen(amount: Long): String = NumberFormat.getCurrencyInstance(Locale.JAPAN).format(amount)

private fun formatDate(epochMillis: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(Date(epochMillis))
