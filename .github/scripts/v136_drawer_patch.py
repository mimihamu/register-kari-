from pathlib import Path


def rep(path: str, old: str, new: str, count: int = 1) -> None:
    p = Path(path)
    s = p.read_text()
    if old not in s:
        raise SystemExit(f"pattern not found in {path}: {old[:160]!r}")
    p.write_text(s.replace(old, new, count))


# AdminSettingsStore: formal business-context settings and safe test routing.
p = "app/src/main/java/jp/co/tenposinfo/register/AdminSettingsStore.kt"
rep(p,
'''    val drawerEnabled: Boolean = false,
    val drawerOpenOnCashSale: Boolean = true,
    val drawerPort: Int = 0,''',
'''    val drawerEnabled: Boolean = false,
    val drawerOpenOnCashSale: Boolean = true,
    val drawerOpenOnCashRefund: Boolean = true,
    val drawerOpenOnCashMovement: Boolean = true,
    val drawerOpenOnExchange: Boolean = true,
    val drawerStandaloneEnabled: Boolean = false,
    val drawerOpenReasonRequired: Boolean = true,
    val drawerPort: Int = 0,''')
rep(p,
'''class AdminSettingsStore(context: Context) : AutoCloseable {
    private val baseDatabase = RegisterDatabase(context.applicationContext)''',
'''class AdminSettingsStore(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val baseDatabase = RegisterDatabase(appContext)''')
rep(p,
'''            "timeout_millis", "enabled", "profile_key", "cut_mode", "drawer_enabled", "drawer_open_on_cash",
            "drawer_port", "drawer_on_millis", "drawer_off_millis", "receipt_auto_print",''',
'''            "timeout_millis", "enabled", "profile_key", "cut_mode", "drawer_enabled", "drawer_open_on_cash",
            "drawer_port", "drawer_on_millis", "drawer_off_millis", "receipt_auto_print",
            "drawer_open_on_cash_refund", "drawer_open_on_cash_movement", "drawer_open_on_exchange",
            "drawer_standalone_enabled", "drawer_open_reason_required",''')
rep(p,
'''            drawerOffMillis = cursor.getInt(14),
            receiptAutoPrintEnabled = cursor.getInt(15),''',
'''            drawerOffMillis = cursor.getInt(14),
            receiptAutoPrintEnabled = cursor.getInt(15),
            drawerOpenOnCashRefund = cursor.getInt(16) != 0,
            drawerOpenOnCashMovement = cursor.getInt(17) != 0,
            drawerOpenOnExchange = cursor.getInt(18) != 0,
            drawerStandaloneEnabled = cursor.getInt(19) != 0,
            drawerOpenReasonRequired = cursor.getInt(20) != 0,''')
rep(p,
'''        require(configuration.drawerOnMillis in 20..500) { "ドロアON時間は20～500msです" }''',
'''        require(configuration.drawerOnMillis in CashDrawerSafetyPolicyV136.MIN_OPEN_PULSE_MS..CashDrawerSafetyPolicyV136.MAX_OPEN_PULSE_MS) {
            "ドロアON時間は${CashDrawerSafetyPolicyV136.MIN_OPEN_PULSE_MS}～${CashDrawerSafetyPolicyV136.MAX_OPEN_PULSE_MS}msです"
        }''')
rep(p,
'''                    put("drawer_open_on_cash", if (configuration.drawerOpenOnCashSale) 1 else 0)
                    put("drawer_port", configuration.drawerPort)''',
'''                    put("drawer_open_on_cash", if (configuration.drawerOpenOnCashSale) 1 else 0)
                    put("drawer_open_on_cash_refund", if (configuration.drawerOpenOnCashRefund) 1 else 0)
                    put("drawer_open_on_cash_movement", if (configuration.drawerOpenOnCashMovement) 1 else 0)
                    put("drawer_open_on_exchange", if (configuration.drawerOpenOnExchange) 1 else 0)
                    put("drawer_standalone_enabled", if (configuration.drawerStandaloneEnabled) 1 else 0)
                    put("drawer_open_reason_required", if (configuration.drawerOpenReasonRequired) 1 else 0)
                    put("drawer_port", configuration.drawerPort)''')
rep(p,
'''    fun testDrawer(configuration: PrinterConfiguration): Result<Unit> {
        require(configuration.host.isNotBlank()) { "IPアドレスまたはホスト名を入力してください" }
        return printerGateway(configuration).send(PrinterCommandEncoder.drawerOnly(configuration))
    }''',
'''    fun testDrawer(configuration: PrinterConfiguration, actor: String): Result<Unit> {
        require(configuration.host.isNotBlank()) { "IPアドレスまたはホスト名を入力してください" }
        return CashDrawerRuntimeV136.dispatchDiagnostic(appContext, configuration, actor).map { Unit }
    }''')
rep(p,
'''                drawer_off_millis INTEGER NOT NULL DEFAULT 500,
                updated_at INTEGER NOT NULL''',
'''                drawer_off_millis INTEGER NOT NULL DEFAULT 500,
                drawer_open_on_cash_refund INTEGER NOT NULL DEFAULT 1,
                drawer_open_on_cash_movement INTEGER NOT NULL DEFAULT 1,
                drawer_open_on_exchange INTEGER NOT NULL DEFAULT 1,
                drawer_standalone_enabled INTEGER NOT NULL DEFAULT 0,
                drawer_open_reason_required INTEGER NOT NULL DEFAULT 1,
                updated_at INTEGER NOT NULL''')
rep(p,
'''        ensurePrinterColumn("drawer_off_millis", "INTEGER NOT NULL DEFAULT 500")
        ensurePrinterColumn("printable_dot_width", "INTEGER NOT NULL DEFAULT 0")''',
'''        ensurePrinterColumn("drawer_off_millis", "INTEGER NOT NULL DEFAULT 500")
        ensurePrinterColumn("drawer_open_on_cash_refund", "INTEGER NOT NULL DEFAULT 1")
        ensurePrinterColumn("drawer_open_on_cash_movement", "INTEGER NOT NULL DEFAULT 1")
        ensurePrinterColumn("drawer_open_on_exchange", "INTEGER NOT NULL DEFAULT 1")
        ensurePrinterColumn("drawer_standalone_enabled", "INTEGER NOT NULL DEFAULT 0")
        ensurePrinterColumn("drawer_open_reason_required", "INTEGER NOT NULL DEFAULT 1")
        ensurePrinterColumn("printable_dot_width", "INTEGER NOT NULL DEFAULT 0")''')

# Low-level pulse must match formal 50..500ms ON duration.
p = "app/src/main/java/jp/co/tenposinfo/register/PrinterProfile.kt"
rep(p,
'''        require(onMillis in 20..500) { "ドロアON時間は20～500msです" }''',
'''        require(onMillis in CashDrawerSafetyPolicyV136.MIN_OPEN_PULSE_MS..CashDrawerSafetyPolicyV136.MAX_OPEN_PULSE_MS) {
            "ドロアON時間は${CashDrawerSafetyPolicyV136.MIN_OPEN_PULSE_MS}～${CashDrawerSafetyPolicyV136.MAX_OPEN_PULSE_MS}msです"
        }''')

# Printing must never cause drawer opening; print retry/reprint therefore cannot reopen it.
p = "app/src/main/java/jp/co/tenposinfo/register/Receipt.kt"
rep(p,
'''        val openDrawer = configuration.drawerEnabled &&
            configuration.drawerOpenOnCashSale &&
            !data.reprint &&
            data.payments.any { it.method == PaymentMethod.CASH }
        val copies = DocumentPrintSettingsPolicyV136.normalizeCopies(data.documentCopies)''',
'''        // CSH-004: physical drawer opening is a committed business event, never a print side effect.
        val copies = DocumentPrintSettingsPolicyV136.normalizeCopies(data.documentCopies)''')
rep(p, '''                openDrawer = openDrawer && copyIndex == 0,''', '''                openDrawer = false,''')

# Cash movements include exchange; EXCHANGE is deliberately excluded from IN/OUT expected-cash totals.
p = "app/src/main/java/jp/co/tenposinfo/register/OperationsStore.kt"
rep(p,
'''enum class CashMovementType(val displayName: String, val sign: Long) {
    IN("入金", 1),
    OUT("出金", -1),
}''',
'''enum class CashMovementType(val displayName: String, val sign: Long) {
    IN("入金", 1),
    OUT("出金", -1),
    EXCHANGE("両替", 0),
}''')
rep(p,
'''    fun loadReturnableLines(saleId: Long): List<ReturnableSaleLine> =''',
'''    fun reversalHasCashRefund(reversalId: Long): Boolean = longQuery(
        "SELECT COUNT(*) FROM reversal_payments WHERE reversal_id = ? AND payment_method = ?",
        arrayOf(reversalId.toString(), PaymentMethod.CASH.name),
    ) > 0

    fun loadReturnableLines(saleId: Long): List<ReturnableSaleLine> =''')

# Authenticated movement/reversal flows dispatch after DB commit only.
p = "app/src/main/java/jp/co/tenposinfo/register/SecureOperationsCoordinator.kt"
rep(p,
'''    fun recordCashMovement(type: CashMovementType, amount: Long, reason: String): Long {
        val operator = requireOperator(OperationsAction.CASH_MOVEMENT)
        return store.recordCashMovement(type, amount, reason, OperationsActorFormatter.direct(operator))
    }''',
'''    fun recordCashMovement(type: CashMovementType, amount: Long, reason: String): Long {
        val operator = requireOperator(OperationsAction.CASH_MOVEMENT)
        val actor = OperationsActorFormatter.direct(operator)
        val movementId = store.recordCashMovement(type, amount, reason, actor)
        val openContext = when (type) {
            CashMovementType.IN -> CashDrawerOpenContextV136.CASH_IN
            CashMovementType.OUT -> CashDrawerOpenContextV136.CASH_OUT
            CashMovementType.EXCHANGE -> CashDrawerOpenContextV136.EXCHANGE
        }
        CashDrawerRuntimeV136.dispatchAsync(
            context = appContext,
            openContext = openContext,
            referenceId = movementId,
            eventKey = "CASH_MOVEMENT:$movementId",
            reason = reason,
            actor = actor,
        )
        return movementId
    }''')
rep(p,
'''            ManualRefundFallbackRuntimeV135.complete(appContext, refundContext, result.refundAmount)
            result''',
'''            ManualRefundFallbackRuntimeV135.complete(appContext, refundContext, result.refundAmount)
            CashDrawerRuntimeV136.dispatchAsync(
                context = appContext,
                openContext = CashDrawerOpenContextV136.CASH_REFUND,
                referenceId = result.reversalId,
                eventKey = "REVERSAL:${result.reversalId}",
                reason = reason,
                actor = actor,
                hasCashPayment = store.reversalHasCashRefund(result.reversalId),
            )
            result''')

# Manual no-source return uses its committed id and cash refund method.
p = "app/src/main/java/jp/co/tenposinfo/register/ManualReturnV135.kt"
rep(p,
'''        runCatching { AutomaticPrintScheduler.enqueueNow(appContext) }
        return result''',
'''        if (request.refundMethod == ManualRefundMethodV135.CASH) {
            CashDrawerRuntimeV136.dispatchAsync(
                context = appContext,
                openContext = CashDrawerOpenContextV136.CASH_REFUND,
                referenceId = result.manualReturnId,
                eventKey = "MANUAL_RETURN:${result.manualReturnId}",
                reason = request.reason.trim().ifBlank { "元取引なし現金返品" },
                actor = operator.name,
                hasCashPayment = true,
            )
        }
        runCatching { AutomaticPrintScheduler.enqueueNow(appContext) }
        return result''')

# Operations UI exposes exchange and keeps it visually/accountingly neutral.
p = "app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt"
rep(p,
'''                    OpChoiceButton("入金", type == CashMovementType.IN, Modifier.weight(1f)) { type = CashMovementType.IN }
                    OpChoiceButton("出金", type == CashMovementType.OUT, Modifier.weight(1f)) { type = CashMovementType.OUT }''',
'''                    OpChoiceButton("入金", type == CashMovementType.IN, Modifier.weight(1f)) { type = CashMovementType.IN }
                    OpChoiceButton("出金", type == CashMovementType.OUT, Modifier.weight(1f)) { type = CashMovementType.OUT }
                    OpChoiceButton("両替", type == CashMovementType.EXCHANGE, Modifier.weight(1f)) { type = CashMovementType.EXCHANGE }''')
rep(p,
'''                    colors = ButtonDefaults.buttonColors(containerColor = if (type == CashMovementType.IN) OpBlue else OpDanger),''',
'''                    colors = ButtonDefaults.buttonColors(
                        containerColor = when (type) {
                            CashMovementType.IN -> OpBlue
                            CashMovementType.OUT -> OpDanger
                            CashMovementType.EXCHANGE -> OpNavy
                        },
                    ),''')
rep(p,
'''                                Text(record.type.displayName, color = if (record.type == CashMovementType.IN) OpBlue else OpDanger, fontWeight = FontWeight.Bold)''',
'''                                Text(
                                    record.type.displayName,
                                    color = when (record.type) {
                                        CashMovementType.IN -> OpBlue
                                        CashMovementType.OUT -> OpDanger
                                        CashMovementType.EXCHANGE -> OpNavy
                                    },
                                    fontWeight = FontWeight.Bold,
                                )''')
rep(p,
'''                                    if (record.type == CashMovementType.IN) "+${opYen(record.amount)}" else "-${opYen(record.amount)}",''',
'''                                    when (record.type) {
                                        CashMovementType.IN -> "+${opYen(record.amount)}"
                                        CashMovementType.OUT -> "-${opYen(record.amount)}"
                                        CashMovementType.EXCHANGE -> "±${opYen(record.amount)}"
                                    },''')

# Printer settings expose all formal contexts; standalone stays hard-disabled in initial release.
p = "app/src/main/java/jp/co/tenposinfo/register/AdminSettingsActivity.kt"
rep(p,
'''    var drawerEnabled by remember { mutableStateOf(initial.drawerEnabled) }
    var drawerOpenOnCash by remember { mutableStateOf(initial.drawerOpenOnCashSale) }
    var drawerPort by remember { mutableStateOf(initial.drawerPort) }''',
'''    var drawerEnabled by remember { mutableStateOf(initial.drawerEnabled) }
    var drawerOpenOnCash by remember { mutableStateOf(initial.drawerOpenOnCashSale) }
    var drawerOpenOnCashRefund by remember { mutableStateOf(initial.drawerOpenOnCashRefund) }
    var drawerOpenOnCashMovement by remember { mutableStateOf(initial.drawerOpenOnCashMovement) }
    var drawerOpenOnExchange by remember { mutableStateOf(initial.drawerOpenOnExchange) }
    var drawerPort by remember { mutableStateOf(initial.drawerPort) }''')
rep(p,
'''        drawerEnabled = drawerEnabled,
        drawerOpenOnCashSale = drawerOpenOnCash,
        drawerPort = drawerPort,''',
'''        drawerEnabled = drawerEnabled,
        drawerOpenOnCashSale = drawerOpenOnCash,
        drawerOpenOnCashRefund = drawerOpenOnCashRefund,
        drawerOpenOnCashMovement = drawerOpenOnCashMovement,
        drawerOpenOnExchange = drawerOpenOnExchange,
        drawerStandaloneEnabled = false,
        drawerOpenReasonRequired = true,
        drawerPort = drawerPort,''')
rep(p,
'''                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = drawerEnabled, onCheckedChange = { drawerEnabled = it })
                        Text("プリンター接続ドロアを使用")
                        Spacer(Modifier.width(18.dp))
                        Checkbox(
                            checked = drawerOpenOnCash,
                            onCheckedChange = { drawerOpenOnCash = it },
                            enabled = drawerEnabled,
                        )
                        Text("現金会計時に自動オープン")
                    }
''',
'''                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = drawerEnabled, onCheckedChange = { drawerEnabled = it })
                        Text("プリンター接続ドロアを使用")
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = drawerOpenOnCash, onCheckedChange = { drawerOpenOnCash = it }, enabled = drawerEnabled)
                        Text("現金会計")
                        Spacer(Modifier.width(12.dp))
                        Checkbox(checked = drawerOpenOnCashRefund, onCheckedChange = { drawerOpenOnCashRefund = it }, enabled = drawerEnabled)
                        Text("現金返金")
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = drawerOpenOnCashMovement, onCheckedChange = { drawerOpenOnCashMovement = it }, enabled = drawerEnabled)
                        Text("入金・出金")
                        Spacer(Modifier.width(12.dp))
                        Checkbox(checked = drawerOpenOnExchange, onCheckedChange = { drawerOpenOnExchange = it }, enabled = drawerEnabled)
                        Text("両替")
                    }
                    Text("単独開放は無効。開放は業務確定後に理由・担当者付きで監査記録します。", color = Color.Gray, fontSize = 12.sp)
''')
rep(p, 'onClick = { executeTest("ドロアテスト") { store.testDrawer(it) } },', 'onClick = { executeTest("ドロアテスト") { store.testDrawer(it, actorName) } },')
rep(p,
'''                AsValueRow("自動オープン", if (drawerEnabled && drawerOpenOnCash) "現金会計時" else "なし")''',
'''                AsValueRow(
                    "自動オープン",
                    if (!drawerEnabled) "なし" else listOfNotNull(
                        "現金会計".takeIf { drawerOpenOnCash },
                        "現金返金".takeIf { drawerOpenOnCashRefund },
                        "入出金".takeIf { drawerOpenOnCashMovement },
                        "両替".takeIf { drawerOpenOnExchange },
                    ).joinToString("・").ifBlank { "なし" },
                )''')
rep(p,
'''                    "レシート自動発行ONでは初回レシートにドロアキックを付加します。OFFでは会計確定後にドロアだけを独立送信します。後レシート／再発行、返品票、X点検票、Z精算票では自動でドロアを開きません。",''',
'''                    "ドロア開放は印刷から分離し、現金会計・現金返金・入出金・両替の業務確定後だけ実行します。同じ業務イベントは再送・再印字されても再開放しません。単独開放は無効です。",''')

# Async worker should not die on a validation or transport exception.
p = "app/src/main/java/jp/co/tenposinfo/register/CashDrawerSafetyV136.kt"
rep(p,
'''        executor.execute {
            dispatch(
                context = appContext,
                openContext = openContext,
                referenceId = referenceId,
                eventKey = eventKey,
                reason = reason,
                actor = actor,
                hasCashPayment = hasCashPayment,
            )
        }''',
'''        executor.execute {
            runCatching {
                dispatch(
                    context = appContext,
                    openContext = openContext,
                    referenceId = referenceId,
                    eventKey = eventKey,
                    reason = reason,
                    actor = actor,
                    hasCashPayment = hasCashPayment,
                ).getOrThrow()
            }
        }''')

# Existing print-setting source contract must now assert separation, not first-copy drawer embedding.
p = "app/src/test/java/jp/co/tenposinfo/register/V136DocumentPrintSettingsTest.kt"
rep(p,
'''        assertTrue(source.contains("openDrawer = openDrawer && copyIndex == 0"))''',
'''        assertTrue(source.contains("openDrawer = false"))
        assertFalse(source.contains("openDrawer = openDrawer && copyIndex == 0"))''')
