from pathlib import Path
import re

path = Path("app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, found {count}")
    text = text.replace(old, new, 1)


replace_once(
    '''    var queueMessage by remember { mutableStateOf<String?>(null) }
''',
    '''    var queueMessage by remember { mutableStateOf<String?>(null) }
    var ticketMessage by remember { mutableStateOf<String?>(null) }
    var paymentMessage by remember { mutableStateOf<String?>(null) }
    var saleCommitInProgress by remember { mutableStateOf(false) }
    val heldTicketCoordinator = remember { HeldTicketSafetyCoordinator(database) }
    val saleCommitGuard = remember { SaleCommitGuard() }
''',
    "state variables",
)

replace_once(
    '''                onHold = {
                    if (currentOperator?.allows(RegisterPermission.HOLD_TICKET) != true) {
                        accessMessage = "保留伝票の権限がありません"
                    } else if (cart.isNotEmpty()) {
                        accessMessage = null
                        val sequence = database.listHeldTickets().size + 1
                        database.holdCart("伝票$sequence", operatorName, cart.toList())
                        replaceCart(emptyList())
                    }
                },
''',
    '''                onHold = {
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
''',
    "hold action",
)

replace_once(
    '''                onPayment = {
                    paymentState = PaymentState()
                    CustomerDisplayRuntime.publish(
''',
    '''                onPayment = {
                    paymentState = PaymentState()
                    paymentMessage = null
                    saleCommitInProgress = false
                    saleCommitGuard.resetForNewPayment()
                    CustomerDisplayRuntime.publish(
''',
    "payment reset",
)

old_tickets = '''            AppScreen.TICKETS -> TicketListScreen(
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
'''
new_tickets = '''            AppScreen.TICKETS -> TicketListScreen(
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
'''
replace_once(old_tickets, new_tickets, "ticket routing")

replace_once(
    '''            AppScreen.PAYMENT -> PaymentScreen(
                items = cart,
                state = paymentState,
''',
    '''            AppScreen.PAYMENT -> PaymentScreen(
                items = cart,
                state = paymentState,
                completing = saleCommitInProgress,
                externalMessage = paymentMessage,
''',
    "payment parameters",
)

replace_once(
    '''                onBack = {
                    CustomerDisplayRuntime.publish(
                        CustomerDisplaySnapshotFactory.sales(
                            cart.toList(),
                            CustomerDisplaySettingsStore(context.applicationContext).load().storeName,
                        ),
                    )
                    screen = AppScreen.SALES
                },
                onComplete = {
                    val saleId = database.saveSale(operatorName, cart.toList(), paymentState, receiptPaper.widthMm)
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
                    screen = AppScreen.COMPLETE
                },
''',
    '''                onBack = {
                    saleCommitGuard.resetForNewPayment()
                    saleCommitInProgress = false
                    paymentMessage = null
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
                        database.saveSale(operatorName, cart.toList(), paymentState, receiptPaper.widthMm)
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
''',
    "payment completion",
)

pattern = re.compile(
    r'''@Composable\nprivate fun TicketListScreen\(.*?\n}\n\n@Composable\nprivate fun PaymentScreen\(''',
    re.DOTALL,
)
replacement = '''@Composable
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
private fun PaymentScreen('''
text, count = pattern.subn(replacement, text, count=1)
if count != 1:
    raise SystemExit(f"ticket screen replacement: expected 1 match, found {count}")

replace_once(
    '''private fun PaymentScreen(
    items: List<CartItem>,
    state: PaymentState,
    onStateChange: (PaymentState) -> Unit,
''',
    '''private fun PaymentScreen(
    items: List<CartItem>,
    state: PaymentState,
    completing: Boolean,
    externalMessage: String?,
    onStateChange: (PaymentState) -> Unit,
''',
    "payment signature",
)

replace_once(
    '''    var input by remember { mutableStateOf("") }
    var acknowledgedMixedTax by remember { mutableStateOf(false) }
''',
    '''    var input by remember { mutableStateOf("") }
    var operationMessage by remember { mutableStateOf<String?>(null) }
    var acknowledgedMixedTax by remember { mutableStateOf(false) }
''',
    "payment local message",
)

replace_once(
    '''        runCatching { PaymentEngine.addPayment(state, summary.grossAmount, method, amount) }
            .onSuccess {
                onStateChange(it)
                input = ""
            }
''',
    '''        if (completing) return
        runCatching { PaymentEngine.addPayment(state, summary.grossAmount, method, amount) }
            .onSuccess {
                onStateChange(it)
                input = ""
                operationMessage = null
            }
            .onFailure { error ->
                operationMessage = error.message ?: "支払を追加できませんでした"
            }
''',
    "payment add errors",
)

replace_once(
    '''                ValueBox(if (input.isBlank()) "残額全額" else input, compact = true)
''',
    '''                val visibleMessage = externalMessage ?: operationMessage
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
''',
    "payment visible message",
)

text = text.replace(
    '''                            enabled = remaining > 0,
''',
    '''                            enabled = remaining > 0 && !completing,
''',
    3,
)

replace_once(
    '''            confirmLabel = "会計確定",
            onConfirm = onComplete,
            confirmEnabled = remaining == 0L && !mixed.hasMixedTax,
''',
    '''            confirmLabel = if (completing) "会計確定中…" else "会計確定",
            onConfirm = onComplete,
            confirmEnabled = remaining == 0L && !mixed.hasMixedTax && !completing,
''',
    "payment confirm lock",
)

path.write_text(text, encoding="utf-8")
print("v0.14 UI safety changes applied")
