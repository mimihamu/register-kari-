from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly 1 occurrence, found {count}")
    return text.replace(old, new, 1)


# --- MainActivity: screen state + operations wiring ---
main_path = "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt"
main = read(main_path)
main = replace_once(
    main,
    "    TICKETS,\n    PAYMENT,",
    "    TICKETS,\n    TICKET_SPLIT,\n    PAYMENT,",
    "AppScreen.TICKET_SPLIT",
)
main = replace_once(
    main,
    "    var ticketMessage by remember { mutableStateOf<String?>(null) }\n    var paymentMessage by remember { mutableStateOf<String?>(null) }",
    "    var ticketMessage by remember { mutableStateOf<String?>(null) }\n    var selectedHeldTicketId by remember { mutableStateOf<Long?>(null) }\n    var paymentMessage by remember { mutableStateOf<String?>(null) }",
    "selectedHeldTicketId state",
)

start = main.index("            AppScreen.TICKETS -> TicketListScreen(")
end = main.index("            AppScreen.PAYMENT -> PaymentScreen(", start)
new_screen_block = '''            AppScreen.TICKETS -> TicketListScreen(
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

'''
main = main[:start] + new_screen_block + main[end:]

func_start = main.index("@Composable\nprivate fun TicketListScreen(")
func_end = main.index("@Composable\nprivate fun PaymentScreen(", func_start)
new_functions = r'''@Composable
private fun TicketListScreen(
    tickets: List<HeldTicket>,
    currentCartCount: Int,
    message: String?,
    onLoad: (HeldTicket) -> Unit,
    onRename: (HeldTicket, String) -> Unit,
    onDelete: (HeldTicket) -> Unit,
    onMerge: (HeldTicket, HeldTicket) -> Unit,
    onSplit: (HeldTicket) -> Unit,
    onBack: () -> Unit,
) {
    var editingTicketId by remember { mutableStateOf<Long?>(null) }
    var editingName by remember { mutableStateOf("") }
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }
    var mergeSourceId by remember { mutableStateOf<Long?>(null) }
    var pendingMergeTargetId by remember { mutableStateOf<Long?>(null) }
    val mergeSource = mergeSourceId?.let { sourceId -> tickets.firstOrNull { it.id == sourceId } }

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
        if (mergeSource != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = PaleBlue),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
            ) {
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
        if (!message.isNullOrBlank()) {
            Text(
                message,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp),
                color = if (message.contains("できません") || message.contains("見つかりません")) Danger else Color(0xFF2E7D32),
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
                                    when {
                                        pendingDeleteId == ticket.id -> Color(0xFFFFEBEE)
                                        mergeSourceId == ticket.id -> PaleBlue
                                        pendingMergeTargetId == ticket.id -> PaleYellow
                                        else -> Color.Transparent
                                    },
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
                                BlueButton(
                                    if (currentCartCount > 0) "退避して呼出" else "呼出",
                                    { onLoad(ticket) },
                                    Modifier.width(if (currentCartCount > 0) 145.dp else 105.dp),
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (mergeSource == null) {
                                    OutlinedButton(onClick = {
                                        mergeSourceId = ticket.id
                                        pendingMergeTargetId = null
                                        editingTicketId = null
                                        pendingDeleteId = null
                                    }) { Text("結合") }
                                    Spacer(Modifier.width(8.dp))
                                    OutlinedButton(onClick = { onSplit(ticket) }) { Text("分割") }
                                } else if (mergeSource.id == ticket.id) {
                                    OutlinedButton(onClick = {
                                        mergeSourceId = null
                                        pendingMergeTargetId = null
                                    }) { Text("結合元を取消") }
                                } else if (pendingMergeTargetId == ticket.id) {
                                    Button(
                                        onClick = {
                                            val source = mergeSource
                                            mergeSourceId = null
                                            pendingMergeTargetId = null
                                            onMerge(source, ticket)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Danger),
                                    ) { Text("結合確定") }
                                } else {
                                    OutlinedButton(onClick = {
                                        pendingMergeTargetId = ticket.id
                                        editingTicketId = null
                                        pendingDeleteId = null
                                    }) { Text("この伝票へ結合") }
                                }
                            }
                            if (pendingMergeTargetId == ticket.id && mergeSource != null) {
                                Text(
                                    "${mergeSource.name} の全明細を ${ticket.name} の末尾へ結合します。元伝票は結合成功時のみ削除されます。",
                                    color = Danger,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 6.dp),
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

    Column(Modifier.fillMaxSize()) {
        Header("SCR-201", "伝票分割")
        if (!externalMessage.isNullOrBlank()) {
            Text(
                externalMessage,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp),
                color = Danger,
                fontWeight = FontWeight.Bold,
            )
        }
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            CardPanel(Modifier.weight(1f).fillMaxHeight()) {
                Text("分割元: ${ticket.name}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Navy)
                Text("明細ごとに新しい伝票へ移す数量を入力します。元伝票を空にはできません。", color = Color.Gray)
                Spacer(Modifier.height(10.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    itemsIndexed(items) { index, item ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.product.name, fontWeight = FontWeight.Bold, color = Navy)
                                Text(
                                    "元数量 ${item.quantity}点 / 単価 ${yen(item.unitPrice)} / 税 ${item.product.taxSymbol}",
                                    color = Color.Gray,
                                )
                                if (item.discountAmount != 0L) {
                                    Text("行値引 ${yen(item.discountAmount)}", color = Color.Gray)
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
                                modifier = Modifier.width(130.dp),
                            )
                        }
                    }
                }
            }
            CardPanel(Modifier.width(350.dp).fillMaxHeight()) {
                Text("分割先", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Navy)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it.take(HeldTicketSafetyPolicy.MAX_NAME_LENGTH) },
                    label = { Text("新しい伝票名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                AmountRow("移動点数", "${validation.movedCount}点", emphasized = validation.canConfirm)
                AmountRow("元伝票残数", "${validation.remainingCount}点")
                Spacer(Modifier.height(14.dp))
                Text(
                    "数量の一部を分ける場合、行値引は数量比で按分し、税スナップショットは双方へ維持します。",
                    color = Color.Gray,
                )
                if (validation.message != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(validation.message, color = if (validation.canConfirm) Color(0xFF2E7D32) else Danger, fontWeight = FontWeight.Bold)
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

'''
main = main[:func_start] + new_functions + main[func_end:]
write(main_path, main)


# --- Pure UI validation policy ---
write(
    "app/src/main/java/jp/co/tenposinfo/register/HeldTicketOperationsUi.kt",
    '''package jp.co.tenposinfo.register

internal data class HeldTicketSplitUiValidation(
    val movedQuantities: Map<Int, Int>,
    val movedCount: Int,
    val remainingCount: Int,
    val canConfirm: Boolean,
    val message: String?,
)

internal object HeldTicketOperationsUiPolicy {
    fun validateSplit(
        items: List<CartItem>,
        rawQuantities: List<String>,
        rawName: String,
    ): HeldTicketSplitUiValidation {
        val totalCount = items.sumOf { it.quantity }
        if (items.isEmpty()) {
            return invalid(emptyMap(), 0, 0, "分割元の伝票に明細がありません")
        }
        if (rawName.trim().isEmpty()) {
            return invalid(emptyMap(), 0, totalCount, "新しい伝票名を入力してください")
        }

        val parsed = mutableListOf<Int>()
        items.forEachIndexed { index, item ->
            val raw = rawQuantities.getOrNull(index)?.trim().orEmpty()
            val quantity = if (raw.isEmpty()) 0 else raw.toIntOrNull()
            if (quantity == null || quantity < 0) {
                return invalid(emptyMap(), 0, totalCount, "移動数量は0以上の数字で入力してください")
            }
            if (quantity > item.quantity) {
                return invalid(emptyMap(), 0, totalCount, "${item.product.name}の移動数量が元数量を超えています")
            }
            parsed += quantity
        }

        val movedCountLong = parsed.sumOf { it.toLong() }
        if (movedCountLong == 0L) {
            return invalid(emptyMap(), 0, totalCount, "移動する商品数量を入力してください")
        }
        if (movedCountLong >= totalCount.toLong()) {
            return invalid(emptyMap(), movedCountLong.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), 0, "元伝票を空にはできません。1点以上残してください")
        }
        val movedCount = movedCountLong.toInt()
        return HeldTicketSplitUiValidation(
            movedQuantities = parsed.mapIndexedNotNull { index, quantity ->
                quantity.takeIf { it > 0 }?.let { index to it }
            }.toMap(),
            movedCount = movedCount,
            remainingCount = totalCount - movedCount,
            canConfirm = true,
            message = "${movedCount}点を新しい伝票へ分割します",
        )
    }

    private fun invalid(
        movedQuantities: Map<Int, Int>,
        movedCount: Int,
        remainingCount: Int,
        message: String,
    ) = HeldTicketSplitUiValidation(
        movedQuantities = movedQuantities,
        movedCount = movedCount,
        remainingCount = remainingCount,
        canConfirm = false,
        message = message,
    )
}
''',
)


# --- v0.57 tests ---
write(
    "app/src/test/java/jp/co/tenposinfo/register/V057HeldTicketOperationsUiTest.kt",
    '''package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V057HeldTicketOperationsUiTest {
    private fun item(id: String, quantity: Int): CartItem = CartItem(
        product = Product(
            id = id,
            name = "商品$id",
            unitPrice = 1_000L,
            taxCategory = TaxCategory.INCLUDED_10,
            displayOrder = 1,
        ),
        quantity = quantity,
    )

    @Test
    fun splitValidationRejectsEmptyOverQuantityAndFullMove() {
        val items = listOf(item("A", 2), item("B", 1))

        assertFalse(HeldTicketOperationsUiPolicy.validateSplit(items, listOf("0", "0"), "分割先").canConfirm)
        assertFalse(HeldTicketOperationsUiPolicy.validateSplit(items, listOf("3", "0"), "分割先").canConfirm)
        assertFalse(HeldTicketOperationsUiPolicy.validateSplit(items, listOf("2", "1"), "分割先").canConfirm)
        assertFalse(HeldTicketOperationsUiPolicy.validateSplit(items, listOf("1", "0"), "   ").canConfirm)
    }

    @Test
    fun splitValidationProducesIndexedMoveMapAndRemainingCount() {
        val validation = HeldTicketOperationsUiPolicy.validateSplit(
            items = listOf(item("A", 3), item("B", 2)),
            rawQuantities = listOf("1", "2"),
            rawName = "分割先",
        )

        assertTrue(validation.canConfirm)
        assertEquals(mapOf(0 to 1, 1 to 2), validation.movedQuantities)
        assertEquals(3, validation.movedCount)
        assertEquals(2, validation.remainingCount)
    }

    @Test
    fun ticketListAndSplitScreenWireAtomicV056Engine() {
        val root = File("..")
        val main = File("src/main/java/jp/co/tenposinfo/register/MainActivity.kt").readText()
        val uiPolicy = File("src/main/java/jp/co/tenposinfo/register/HeldTicketOperationsUi.kt").readText()
        val engine = File("src/main/java/jp/co/tenposinfo/register/HeldTicketSafety.kt").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        val docs = File(root, "docs/V0.57_HELD_TICKET_OPERATIONS_UI.md").readText()

        assertTrue(main.contains("TICKET_SPLIT"))
        assertTrue(main.contains("onMerge: (HeldTicket, HeldTicket) -> Unit"))
        assertTrue(main.contains("onSplit: (HeldTicket) -> Unit"))
        assertTrue(main.contains("結合元として選択中"))
        assertTrue(main.contains("結合確定"))
        assertTrue(main.contains("この伝票へ結合"))
        assertTrue(main.contains("Header(\\\"SCR-201\\\", \\\"伝票分割\\\")"))
        assertTrue(main.contains("移動数量"))
        assertTrue(main.contains("分割実行"))
        assertTrue(main.contains("heldTicketCoordinator.merge(source, target)"))
        assertTrue(main.contains("heldTicketCoordinator.split("))
        assertTrue(uiPolicy.contains("元伝票を空にはできません"))
        assertTrue(engine.contains("db.beginTransaction()"))
        assertTrue(build.contains("versionCode = 87"))
        assertTrue(build.contains("versionName = \\\"0.57.0-dev.1\\\""))
        assertTrue(workflow.contains("V057HeldTicketOperationsUiTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_v0.57.0_dev1_held_ticket_operations_ui_debug.apk"))
        assertTrue(docs.contains("結合元 → 結合先 → 結合確定"))
    }
}
''',
)


# --- Version bump ---
build_path = "app/build.gradle.kts"
build = read(build_path)
build = replace_once(build, "versionCode = 86", "versionCode = 87", "app versionCode")
build = replace_once(build, 'versionName = "0.56.0-dev.1"', 'versionName = "0.57.0-dev.1"', "app versionName")
write(build_path, build)


# --- Historical cumulative tests: keep assertions but point current-version literals at v0.57 ---
replacements = {
    "versionCode = 86": "versionCode = 87",
    'versionName = \\\"0.56.0-dev.1\\\"': 'versionName = \\\"0.57.0-dev.1\\\"',
    "TSUGUREGI_v0.56.0_dev1_held_ticket_merge_split_debug.apk": "TSUGUREGI_v0.57.0_dev1_held_ticket_operations_ui_debug.apk",
    "TSUGUREGI-v0.56.0-dev1-held-ticket-merge-split-apks": "TSUGUREGI-v0.57.0-dev1-held-ticket-operations-ui-apks",
}
for base in [ROOT / "app/src/test", ROOT / "management-app/src/test"]:
    for path in base.rglob("*.kt"):
        if path.name == "V057HeldTicketOperationsUiTest.kt":
            continue
        text = path.read_text(encoding="utf-8")
        updated = text
        for old, new in replacements.items():
            updated = updated.replace(old, new)
        if updated != text:
            path.write_text(updated, encoding="utf-8")


# --- CI ---
workflow_path = ".github/workflows/build-apk.yml"
workflow = read(workflow_path)
workflow = workflow.replace("Verify cumulative v0.14-v0.56 sources", "Verify cumulative v0.14-v0.57 sources")
workflow = workflow.replace("versionCode = 86", "versionCode = 87")
workflow = workflow.replace('versionName = "0.56.0-dev.1"', 'versionName = "0.57.0-dev.1"')
workflow = workflow.replace(
    "          test -s app/src/test/java/jp/co/tenposinfo/register/V056HeldTicketMergeSplitTest.kt\n",
    "          test -s app/src/test/java/jp/co/tenposinfo/register/V056HeldTicketMergeSplitTest.kt\n"
    "          test -s app/src/test/java/jp/co/tenposinfo/register/V057HeldTicketOperationsUiTest.kt\n",
)
workflow = workflow.replace(
    "          test -s docs/V0.56_RELEASE_NOTES.md\n",
    "          test -s docs/V0.56_RELEASE_NOTES.md\n"
    "          test -s docs/V0.57_HELD_TICKET_OPERATIONS_UI.md\n"
    "          test -s docs/V0.57_RELEASE_NOTES.md\n",
)
workflow = workflow.replace(
    "          grep -q 'HeldTicketMergeSplitPolicy' app/src/main/java/jp/co/tenposinfo/register/HeldTicketSafety.kt\n",
    "          grep -q 'HeldTicketMergeSplitPolicy' app/src/main/java/jp/co/tenposinfo/register/HeldTicketSafety.kt\n"
    "          grep -q 'TICKET_SPLIT' app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt\n"
    "          grep -q '結合確定' app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt\n"
    "          grep -q '分割実行' app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt\n"
    "          test -s app/src/main/java/jp/co/tenposinfo/register/HeldTicketOperationsUi.kt\n",
)
workflow = workflow.replace(
    "TSUGUREGI_v0.56.0_dev1_held_ticket_merge_split_debug.apk",
    "TSUGUREGI_v0.57.0_dev1_held_ticket_operations_ui_debug.apk",
)
workflow = workflow.replace(
    "TSUGUREGI-v0.56.0-dev1-held-ticket-merge-split-apks",
    "TSUGUREGI-v0.57.0-dev1-held-ticket-operations-ui-apks",
)
workflow = workflow.replace("REGISTER_VERSION_NAME=0.56.0-dev.1", "REGISTER_VERSION_NAME=0.57.0-dev.1")
workflow = workflow.replace("REGISTER_VERSION_CODE=86", "REGISTER_VERSION_CODE=87")
workflow = workflow.replace("HELD_TICKET_MERGE_SPLIT_UI=false", "HELD_TICKET_MERGE_SPLIT_UI=true")
workflow = workflow.replace(
    "          HELD_TICKET_MERGE_SPLIT_UI=true\n",
    "          HELD_TICKET_MERGE_SPLIT_UI=true\n"
    "          HELD_TICKET_MERGE_CONFIRMATION=true\n"
    "          HELD_TICKET_SPLIT_QUANTITY_UI=true\n",
)
write(workflow_path, workflow)


# --- Documentation ---
write(
    "docs/V0.57_HELD_TICKET_OPERATIONS_UI.md",
    '''# v0.57 保留伝票 結合・分割 操作UI

## 目的

v0.56で確定した原子的な保留伝票結合・分割エンジンを、つぐレジの「伝票一覧」から日常操作できるようにする。

## 結合操作

誤結合を避けるため、1回のタップでは実行しない。

1. 結合元伝票で「結合」を押す
2. 結合先伝票で「この伝票へ結合」を押す
3. 対象内容を確認して「結合確定」を押す

つまり **結合元 → 結合先 → 結合確定** の3段階操作とする。

結合成功時だけv0.56のSQLiteトランザクション内で結合元を削除する。途中失敗時はロールバックし、中途半端な伝票を残さない。

## 分割操作

伝票一覧の「分割」から `SCR-201 伝票分割` を開く。

- 明細ごとに「移動数量」を入力
- 新しい伝票名を入力・変更可能
- 0点移動は禁止
- 元数量を超える入力は禁止
- 全商品移動は禁止し、元伝票へ1点以上残す
- 確定可能な場合だけ「分割実行」を有効化

数量の一部だけを分割する場合の値引按分、税スナップショット維持、DBトランザクションはv0.56エンジンをそのまま利用する。

## UI安全策

- 結合モード中は結合元を明示表示
- 結合先候補を選んだ後に対象伝票を警告表示
- 結合元自身を結合先にはできない
- 分割前に移動点数と元伝票残数を表示
- 分割入力は純粋関数 `HeldTicketOperationsUiPolicy` で検証し、CIテスト可能にする

## データ保護

売上SQLite、Sales Journal、Drive JSON、同期fingerprint、取込済み売上、SENT済みOutbox、隔離履歴、同期履歴は変更・削除しない。

## 実機確認

CIでは画面サイズ、タッチ操作感、ソフトキーボード、実SQLiteでの大量伝票操作までは確認できないため、実機確認済みとは扱わない。
''',
)

write(
    "docs/V0.57_RELEASE_NOTES.md",
    '''# つぐレジ v0.57 リリースノート

## バージョン

- つぐレジ: `0.57.0-dev.1` / versionCode `87`
- つぐレジ＋: `0.14.0-dev.1` / versionCode `14`（機能変更なし）
- つぐレジ CD: `0.14.0-dev.1` / versionCode `7`（機能変更なし）

## 主な変更

### 保留伝票の結合UI

- 伝票一覧から結合元を選択
- 結合先を選択
- 「結合確定」で最終確認して実行
- 結合対象を画面上で明示し、誤操作を抑制
- v0.56の原子的結合処理を使用

### 保留伝票の分割UI

- `SCR-201 伝票分割` を追加
- 商品明細ごとに移動数量を指定
- 新しい伝票名を編集可能
- 移動点数・元伝票残数を表示
- 0点、数量超過、全移動、空名称をUI段階で拒否
- v0.56の値引按分・税スナップショット維持・SQLiteトランザクションを使用

### テスト

`V057HeldTicketOperationsUiTest` を追加し、入力検証、画面導線、v0.56エンジン接続、バージョン・CI接続を確認する。

## 実機確認

CI成功は実機確認を意味しない。画面レイアウト、タッチ操作、ソフトキーボード、実SQLiteでの結合・分割、v0.56→v0.57上書き更新は実機未確認として扱う。
''',
)

print("v0.57 patch applied")
