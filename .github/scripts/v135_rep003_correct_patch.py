from pathlib import Path

ROOT = Path('.')


def replace_once(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding='utf-8')
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{path}: expected 1 match, got {count}: {old[:140]!r}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')


def replace_between(path: str, start: str, end: str, replacement: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding='utf-8')
    if text.count(start) != 1 or text.count(end) != 1:
        raise SystemExit(f'{path}: markers not unique start={text.count(start)} end={text.count(end)}')
    a = text.index(start)
    b = text.index(end, a)
    p.write_text(text[:a] + replacement + text[b:], encoding='utf-8')

# ---------- OperationsStore: surface all REP-003 facts and re-check them at commit boundary ----------
replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/OperationsStore.kt',
    '''    val pendingPrints: Int,\n    val heldTickets: Int,\n    val settled: Boolean,\n)\n''',
    '''    val pendingPrints: Int,\n    val heldTickets: Int,\n    val settled: Boolean,\n    val openCartItems: Int = 0,\n    val incompletePayments: Int = 0,\n    val backupFailureMessage: String? = null,\n)\n''',
)

replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/OperationsStore.kt',
    '''        val heldTickets = longQuery("SELECT COUNT(*) FROM held_tickets").toInt()\n        val settled = longQuery(\n''',
    '''        val heldTickets = longQuery("SELECT COUNT(*) FROM held_tickets").toInt()\n        val openCartItems = longQuery("SELECT COUNT(*) FROM cart_items").toInt()\n        val incompletePayments = if (\n            openCartItems > 0 && SchemaMigration.tableExists(db, "payment_draft_meta")\n        ) {\n            longQuery("SELECT COUNT(*) FROM payment_draft_meta").coerceAtMost(1L).toInt()\n        } else {\n            0\n        }\n        val backupStatus = AutoBackupStatusStore(appContext).load()\n        val backupFailureMessage = when (backupStatus.lastResult) {\n            AutoBackupResultState.FAILED -> backupStatus.lastError ?: "直近バックアップが失敗しました"\n            AutoBackupResultState.SKIPPED_LOW_STORAGE -> backupStatus.lastError ?: "容量不足でバックアップを実行できませんでした"\n            else -> null\n        }\n        val settled = longQuery(\n''',
)

replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/OperationsStore.kt',
    '''            pendingPrints = pendingPrints,\n            heldTickets = heldTickets,\n            settled = settled,\n        )\n''',
    '''            pendingPrints = pendingPrints,\n            heldTickets = heldTickets,\n            settled = settled,\n            openCartItems = openCartItems,\n            incompletePayments = incompletePayments,\n            backupFailureMessage = backupFailureMessage,\n        )\n''',
)

replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/OperationsStore.kt',
    '''    fun recordSettlement(\n        type: SettlementReportType,\n        actualCash: Long?,\n        operatorName: String,\n        pendingPrintsAcknowledged: Boolean = false,\n    ): Long {\n''',
    '''    fun recordSettlement(\n        type: SettlementReportType,\n        actualCash: Long?,\n        operatorName: String,\n        pendingPrintsAcknowledged: Boolean = false,\n        backupFailureAcknowledged: Boolean = false,\n    ): Long {\n''',
)

replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/OperationsStore.kt',
    '''                val preflight = ZSettlementPreflightPolicy.evaluate(\n                    heldTickets = summary.heldTickets,\n                    pendingPrints = summary.pendingPrints,\n                    pendingPrintsAcknowledged = pendingPrintsAcknowledged,\n                )\n''',
    '''                val preflight = ZSettlementPreflightPolicy.evaluate(\n                    heldTickets = summary.heldTickets,\n                    pendingPrints = summary.pendingPrints,\n                    pendingPrintsAcknowledged = pendingPrintsAcknowledged,\n                    openCartItems = summary.openCartItems,\n                    incompletePayments = summary.incompletePayments,\n                    backupFailureMessage = summary.backupFailureMessage,\n                    actualCashEntered = actualCash != null,\n                    backupFailureAcknowledged = backupFailureAcknowledged,\n                )\n''',
)

replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/OperationsStore.kt',
    '''            if (type == SettlementReportType.Z_SETTLEMENT && summary.pendingPrints > 0) {\n                insertAudit(\n                    eventType = "Z_SETTLEMENT_PENDING_PRINTS_ACKNOWLEDGED",\n                    referenceId = id,\n                    detail = "未印刷データ ${summary.pendingPrints}件を確認し、責任者承認でZ精算を継続",\n                    operatorName = operatorName,\n                    createdAt = now,\n                )\n            }\n''',
    '''            if (type == SettlementReportType.Z_SETTLEMENT && summary.pendingPrints > 0) {\n                insertAudit(\n                    eventType = "Z_SETTLEMENT_PENDING_PRINTS_ACKNOWLEDGED",\n                    referenceId = id,\n                    detail = "未印刷データ ${summary.pendingPrints}件を確認し、責任者承認でZ精算を継続",\n                    operatorName = operatorName,\n                    createdAt = now,\n                )\n            }\n            if (type == SettlementReportType.Z_SETTLEMENT && summary.backupFailureMessage != null) {\n                insertAudit(\n                    eventType = "Z_SETTLEMENT_BACKUP_FAILURE_ACKNOWLEDGED",\n                    referenceId = id,\n                    detail = "直近バックアップ失敗を確認してZ精算を継続: ${summary.backupFailureMessage}",\n                    operatorName = operatorName,\n                    createdAt = now,\n                )\n            }\n''',
)

# ---------- Coordinator: preserve X report/print behavior; pass REP-003 backup acknowledgement only for Z ----------
replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/SecureOperationsCoordinator.kt',
    '''        managerPin: String,\n        pendingPrintsAcknowledged: Boolean = false,\n    ): Long {\n''',
    '''        managerPin: String,\n        pendingPrintsAcknowledged: Boolean = false,\n        backupFailureAcknowledged: Boolean = false,\n    ): Long {\n''',
)
replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/SecureOperationsCoordinator.kt',
    '''            store.recordSettlement(type, actualCash, actor, pendingPrintsAcknowledged)\n''',
    '''            store.recordSettlement(\n                type = type,\n                actualCash = actualCash,\n                operatorName = actor,\n                pendingPrintsAcknowledged = pendingPrintsAcknowledged,\n                backupFailureAcknowledged = backupFailureAcknowledged,\n            )\n''',
)

# ---------- Main operations route callback ----------
replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt',
    '''        pin: String,\n        pendingPrintsAcknowledged: Boolean,\n    ) {\n        val result = runCatching {\n            secureStore.recordSettlement(type, actualCash, pin, pendingPrintsAcknowledged)\n        }\n''',
    '''        pin: String,\n        pendingPrintsAcknowledged: Boolean,\n        backupFailureAcknowledged: Boolean,\n    ) {\n        val result = runCatching {\n            secureStore.recordSettlement(\n                type = type,\n                actualCash = actualCash,\n                managerPin = pin,\n                pendingPrintsAcknowledged = pendingPrintsAcknowledged,\n                backupFailureAcknowledged = backupFailureAcknowledged,\n            )\n        }\n''',
)

replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt',
    '''                onExecute = { actualCash, pin, pendingPrintsAcknowledged ->\n                    executeSettlement(\n                        SettlementReportType.X_INSPECTION,\n                        actualCash,\n                        pin,\n                        pendingPrintsAcknowledged,\n                    )\n                },\n''',
    '''                onExecute = { actualCash, pin, pendingPrintsAcknowledged, backupFailureAcknowledged ->\n                    executeSettlement(\n                        SettlementReportType.X_INSPECTION,\n                        actualCash,\n                        pin,\n                        pendingPrintsAcknowledged,\n                        backupFailureAcknowledged,\n                    )\n                },\n''',
)
replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt',
    '''                onExecute = { actualCash, pin, pendingPrintsAcknowledged ->\n                    executeSettlement(\n                        SettlementReportType.Z_SETTLEMENT,\n                        actualCash,\n                        pin,\n                        pendingPrintsAcknowledged,\n                    )\n                },\n''',
    '''                onExecute = { actualCash, pin, pendingPrintsAcknowledged, backupFailureAcknowledged ->\n                    executeSettlement(\n                        SettlementReportType.Z_SETTLEMENT,\n                        actualCash,\n                        pin,\n                        pendingPrintsAcknowledged,\n                        backupFailureAcknowledged,\n                    )\n                },\n''',
)

main_settlement = r'''@Composable
private fun SettlementScreen(
    reportType: SettlementReportType,
    screenCode: String,
    title: String,
    session: BusinessSessionRecord?,
    summary: DailyOperationsSummary,
    history: List<SettlementRecord>,
    operatorName: String,
    revision: Int,
    onExecute: (Long?, String, Boolean, Boolean) -> Unit,
    message: String?,
    onBack: () -> Unit,
) {
    var actualCash by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var pendingPrintsAcknowledged by remember { mutableStateOf(false) }
    var backupFailureAcknowledged by remember { mutableStateOf(false) }
    var showZConfirmation by remember { mutableStateOf(false) }
    @Suppress("UNUSED_VARIABLE") val refresh = revision
    val isZSettlement = reportType == SettlementReportType.Z_SETTLEMENT
    val actual = actualCash.toLongOrNull()
    val previewActual = if (isZSettlement) actual else actual ?: summary.expectedCash
    val variance = previewActual?.let { OperationsMath.variance(it, summary.expectedCash) }
    val zPreflight = if (isZSettlement) {
        ZSettlementPreflightPolicy.evaluate(
            heldTickets = summary.heldTickets,
            pendingPrints = summary.pendingPrints,
            pendingPrintsAcknowledged = pendingPrintsAcknowledged,
            openCartItems = summary.openCartItems,
            incompletePayments = summary.incompletePayments,
            backupFailureMessage = summary.backupFailureMessage,
            actualCashEntered = actual != null,
            backupFailureAcknowledged = backupFailureAcknowledged,
        )
    } else {
        ZSettlementPreflightResult(true, summary.heldTickets, summary.pendingPrints, false, null)
    }

    if (showZConfirmation && previewActual != null && variance != null) {
        AlertDialog(
            onDismissRequest = { showZConfirmation = false },
            title = { Text("Z精算して営業を終了しますか？", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("営業日 ${summary.businessDate} / セッションNo.${summary.businessSessionId}")
                    Text("純売上 ${opYen(summary.netSales)}")
                    Text("現金実査 ${opYen(previewActual)} / 過不足 ${signedYen(variance)}")
                    zPreflight.items.forEach { item ->
                        Text("${item.category.displayName}: ${item.statusText}")
                    }
                    Text("完了後、この営業セッションでは販売できません。", color = OpDanger, fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showZConfirmation = false
                        onExecute(actual, pin, pendingPrintsAcknowledged, backupFailureAcknowledged)
                    },
                    enabled = pin.isNotBlank() && zPreflight.mayProceed,
                    colors = ButtonDefaults.buttonColors(containerColor = OpDanger),
                ) { Text("Z精算して営業終了") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showZConfirmation = false }) { Text("戻る") }
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        OpHeader(screenCode, title)
        Row(Modifier.weight(1f).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OpPanel(Modifier.width(430.dp).fillMaxHeight()) {
                Text(if (isZSettlement) "Z精算" else "X点検", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (isZSettlement) OpDanger else OpNavy)
                Spacer(Modifier.height(6.dp))
                Text(
                    session?.let { "対象: ${it.businessDate} / セッションNo.${it.id}" } ?: "営業中のセッションがありません",
                    color = if (session == null) OpDanger else OpGreen,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(10.dp))
                OpNumericField(
                    if (isZSettlement) "現金実査額（必須）" else "現金実査額（空欄は理論額）",
                    actualCash,
                    { actualCash = it },
                )
                if (isZSettlement && actual == null) {
                    Text(SettlementActualCashSafetyV105.Z_REQUIRED_MESSAGE, color = OpDanger)
                }
                Spacer(Modifier.height(6.dp))
                OpAuthenticatedOperator(operatorName)
                if (isZSettlement) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                        label = { Text("責任者PIN") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("精算前確認（REP-003）", fontWeight = FontWeight.Bold, color = OpNavy)
                    zPreflight.items.forEach { item ->
                        Text(
                            "${item.category.displayName}: ${item.statusText}（${item.continuation.displayName}）",
                            color = if (item.active) OpDanger else OpGreen,
                            fontWeight = if (item.active) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        if (item.active && item.category == SettlementPreflightCategoryV135.PENDING_PRINT) {
                            Row(
                                Modifier.fillMaxWidth().clickable { pendingPrintsAcknowledged = !pendingPrintsAcknowledged },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = pendingPrintsAcknowledged, onCheckedChange = { pendingPrintsAcknowledged = it })
                                Text("未印刷のまま精算することを責任者確認", color = OpDanger)
                            }
                        }
                        if (item.active && item.category == SettlementPreflightCategoryV135.BACKUP_FAILURE) {
                            Row(
                                Modifier.fillMaxWidth().clickable { backupFailureAcknowledged = !backupFailureAcknowledged },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = backupFailureAcknowledged, onCheckedChange = { backupFailureAcknowledged = it })
                                Text("バックアップ失敗を確認して精算を継続", color = OpDanger)
                            }
                        }
                    }
                    zPreflight.message?.let { Text(it, color = OpDanger, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (isZSettlement) {
                        "Z精算は精算前確認を通過した営業セッションに対して1回だけ実行し、完了と同時に営業終了します。"
                    } else {
                        "X点検は期間を締めず現在値を保存・印刷します。営業は終了せず販売を継続できます。"
                    },
                    color = Color.DarkGray,
                )
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        if (isZSettlement) showZConfirmation = true else onExecute(actual, "", false, false)
                    },
                    enabled = session != null && (!isZSettlement || (!summary.settled && zPreflight.mayProceed)),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (isZSettlement) OpDanger else OpBlue),
                ) {
                    Text(if (isZSettlement) "Z精算の確認へ" else "X点検を実行", fontWeight = FontWeight.Bold)
                }
                if (message != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        message,
                        color = if (message.contains("保存しました") || message.contains("終了しました")) OpGreen else OpDanger,
                    )
                }
            }

            OpPanel(Modifier.width(370.dp).fillMaxHeight()) {
                Text("プレビュー", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = OpNavy)
                Spacer(Modifier.height(10.dp))
                OpAmountRow("営業セッション", if (summary.businessSessionId > 0) "No.${summary.businessSessionId}" else "未開始")
                OpAmountRow("営業日", summary.businessDate)
                OpAmountRow("売上総額", opYen(summary.salesGross))
                OpAmountRow("返品・取消", "-${opYen(summary.reversalGross)}")
                OpAmountRow("純売上", opYen(summary.netSales), emphasized = true)
                OpAmountRow("現金理論", opYen(summary.expectedCash))
                OpAmountRow("現金実査", previewActual?.let(::opYen) ?: "未入力")
                OpAmountRow("過不足", variance?.let(::signedYen) ?: "未計算", emphasized = true)
                OpAmountRow("未会計保留伝票", "${summary.heldTickets}件")
                OpAmountRow("販売途中明細", "${summary.openCartItems}明細")
                OpAmountRow("未完了決済", "${summary.incompletePayments}件")
                OpAmountRow("未印刷", "${summary.pendingPrints}件")
                OpAmountRow("バックアップ", summary.backupFailureMessage?.let { "失敗" } ?: "問題なし")
                if (isZSettlement) {
                    Spacer(Modifier.height(8.dp))
                    zPreflight.items.forEach { item ->
                        Text("${item.category.displayName}: ${item.statusText}", color = if (item.active) OpDanger else OpGreen)
                    }
                }
            }

            OpPanel(Modifier.weight(1f).fillMaxHeight()) {
                Text("保存履歴", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = OpNavy)
                Spacer(Modifier.height(8.dp))
                if (history.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("履歴はありません", color = Color.Gray) }
                } else {
                    LazyColumn {
                        itemsIndexed(history) { _, record ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Row(Modifier.fillMaxWidth()) {
                                    Text(record.type.displayName, fontWeight = FontWeight.Bold, color = OpNavy)
                                    Spacer(Modifier.weight(1f))
                                    Text(opDateTime(record.createdAt), color = Color.Gray)
                                }
                                Text("${record.businessDate}  セッションNo.${record.businessSessionId}")
                                Text("純売上 ${opYen(record.netSales)}  差異 ${signedYen(record.variance)}")
                                Text("担当 ${record.operatorName}", color = Color.Gray)
                            }
                        }
                    }
                }
            }
        }
        OpBottomBar("レジ管理へ戻る", onBack)
    }
}
'''
replace_between(
    'app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt',
    '@Composable\nprivate fun SettlementScreen(',
    '\n@Composable\nprivate fun CashMovementScreen(',
    main_settlement,
)

# ---------- Responsive v0.30 route callback ----------
replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/SettlementActivityV030.kt',
    '''            onExecute = { actualCash, managerPin, pendingAcknowledged ->\n                val result = runCatching {\n                    secureStore.recordSettlement(\n                        type = reportType,\n                        actualCash = actualCash,\n                        managerPin = managerPin,\n                        pendingPrintsAcknowledged = pendingAcknowledged,\n                    )\n''',
    '''            onExecute = { actualCash, managerPin, pendingAcknowledged, backupFailureAcknowledged ->\n                val result = runCatching {\n                    secureStore.recordSettlement(\n                        type = reportType,\n                        actualCash = actualCash,\n                        managerPin = managerPin,\n                        pendingPrintsAcknowledged = pendingAcknowledged,\n                        backupFailureAcknowledged = backupFailureAcknowledged,\n                    )\n''',
)

responsive_block = r'''@Composable
private fun SettlementScreenV030(
    reportType: SettlementReportType,
    session: BusinessSessionRecord?,
    summary: DailyOperationsSummary,
    history: List<SettlementRecord>,
    operatorName: String,
    message: String?,
    onExecute: (Long?, String, Boolean, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val metrics = rememberRegisterResponsiveMetrics()
    val isZ = reportType == SettlementReportType.Z_SETTLEMENT
    var actualCash by remember { mutableStateOf("") }
    var managerPin by remember { mutableStateOf("") }
    var pendingAcknowledged by remember { mutableStateOf(false) }
    var backupFailureAcknowledged by remember { mutableStateOf(false) }
    var showConfirmation by remember { mutableStateOf(false) }
    val actual = actualCash.toLongOrNull()
    val previewActual = if (isZ) actual else actual ?: summary.expectedCash
    val variance = previewActual?.let { OperationsMath.variance(it, summary.expectedCash) }
    val preflight = if (isZ) {
        ZSettlementPreflightPolicy.evaluate(
            heldTickets = summary.heldTickets,
            pendingPrints = summary.pendingPrints,
            pendingPrintsAcknowledged = pendingAcknowledged,
            openCartItems = summary.openCartItems,
            incompletePayments = summary.incompletePayments,
            backupFailureMessage = summary.backupFailureMessage,
            actualCashEntered = actual != null,
            backupFailureAcknowledged = backupFailureAcknowledged,
        )
    } else {
        ZSettlementPreflightResult(true, summary.heldTickets, summary.pendingPrints, false, null)
    }

    if (showConfirmation && previewActual != null && variance != null) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = { Text("Z精算して営業を終了しますか？", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("営業日 ${summary.businessDate} / セッションNo.${summary.businessSessionId}")
                    Text("純売上 ${settlementYenV030(summary.netSales)}")
                    Text("現金実査 ${settlementYenV030(previewActual)} / 過不足 ${settlementSignedYenV030(variance)}")
                    preflight.items.forEach { Text("${it.category.displayName}: ${it.statusText}") }
                    Text("完了後、この営業セッションでは販売できません。", color = SettlementDangerV030, fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmation = false
                        onExecute(actual, managerPin, pendingAcknowledged, backupFailureAcknowledged)
                    },
                    enabled = managerPin.isNotBlank() && preflight.mayProceed,
                    colors = ButtonDefaults.buttonColors(containerColor = SettlementDangerV030),
                ) { Text("Z精算して営業終了") }
            },
            dismissButton = { OutlinedButton(onClick = { showConfirmation = false }) { Text("戻る") } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        SettlementHeaderV030(metrics, reportType)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val compact = metrics.isCompact || maxWidth < 1_080.dp || maxHeight < 560.dp
            if (compact) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(metrics.screenPaddingDp.dp),
                    verticalArrangement = Arrangement.spacedBy(metrics.panelGapDp.dp),
                ) {
                    SettlementInputPanelV030(
                        Modifier.fillMaxWidth(), reportType, session, summary, operatorName,
                        actualCash, managerPin, pendingAcknowledged, backupFailureAcknowledged,
                        preflight, message,
                        { actualCash = it.filter(Char::isDigit).take(12) },
                        { managerPin = it.filter(Char::isDigit).take(8) },
                        { pendingAcknowledged = it },
                        { backupFailureAcknowledged = it },
                        {
                            if (isZ) {
                                if (preflight.mayProceed) showConfirmation = true
                            } else onExecute(actual, "", false, false)
                        },
                    )
                    SettlementPreviewPanelV030(Modifier.fillMaxWidth(), summary, previewActual, variance, preflight, isZ)
                    SettlementHistoryPanelV030(Modifier.fillMaxWidth().heightIn(min = 180.dp), history)
                }
            } else {
                Row(
                    Modifier.fillMaxSize().padding(metrics.screenPaddingDp.dp),
                    horizontalArrangement = Arrangement.spacedBy(metrics.panelGapDp.dp),
                ) {
                    SettlementInputPanelV030(
                        Modifier.weight(1.15f).fillMaxHeight(), reportType, session, summary, operatorName,
                        actualCash, managerPin, pendingAcknowledged, backupFailureAcknowledged,
                        preflight, message,
                        { actualCash = it.filter(Char::isDigit).take(12) },
                        { managerPin = it.filter(Char::isDigit).take(8) },
                        { pendingAcknowledged = it },
                        { backupFailureAcknowledged = it },
                        {
                            if (isZ) {
                                if (preflight.mayProceed) showConfirmation = true
                            } else onExecute(actual, "", false, false)
                        },
                    )
                    SettlementPreviewPanelV030(Modifier.weight(0.9f).fillMaxHeight(), summary, previewActual, variance, preflight, isZ)
                    SettlementHistoryPanelV030(Modifier.weight(1.05f).fillMaxHeight(), history)
                }
            }
        }
        SettlementBottomV030(metrics, onClose)
    }
}

@Composable
private fun SettlementInputPanelV030(
    modifier: Modifier,
    reportType: SettlementReportType,
    session: BusinessSessionRecord?,
    summary: DailyOperationsSummary,
    operatorName: String,
    actualCash: String,
    managerPin: String,
    pendingAcknowledged: Boolean,
    backupFailureAcknowledged: Boolean,
    preflight: ZSettlementPreflightResult,
    message: String?,
    onActualCashChanged: (String) -> Unit,
    onManagerPinChanged: (String) -> Unit,
    onPendingAcknowledgedChanged: (Boolean) -> Unit,
    onBackupFailureAcknowledgedChanged: (Boolean) -> Unit,
    onExecute: () -> Unit,
) {
    val isZ = reportType == SettlementReportType.Z_SETTLEMENT
    SettlementPanelV030(modifier) {
        Text(if (isZ) "Z精算・営業終了" else "X点検", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (isZ) SettlementDangerV030 else SettlementNavyV030)
        Spacer(Modifier.height(6.dp))
        Text(session?.let { "対象: ${it.businessDate} / セッションNo.${it.id}" } ?: "営業中のセッションがありません", color = if (session == null) SettlementDangerV030 else SettlementGreenV030, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = actualCash,
            onValueChange = onActualCashChanged,
            label = { Text(if (isZ) "現金実査額（必須）" else "現金実査額（空欄は理論額）") },
            supportingText = { Text(if (isZ) SettlementActualCashSafetyV105.Z_REQUIRED_MESSAGE else "X点検では未入力の場合、理論現金を実在高として使用します。") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().background(SettlementPaleBlueV030, RoundedCornerShape(8.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("操作担当", color = Color.DarkGray)
            Spacer(Modifier.weight(1f))
            Text(operatorName, fontWeight = FontWeight.Bold, color = SettlementNavyV030)
        }
        if (isZ) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = managerPin,
                onValueChange = onManagerPinChanged,
                label = { Text("責任者PIN") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text("精算前確認（REP-003）", fontWeight = FontWeight.Bold, color = SettlementNavyV030)
            preflight.items.forEach { item ->
                Text("${item.category.displayName}: ${item.statusText}（${item.continuation.displayName}）", color = if (item.active) SettlementDangerV030 else SettlementGreenV030)
                if (item.active && item.category == SettlementPreflightCategoryV135.PENDING_PRINT) {
                    Row(Modifier.fillMaxWidth().clickable { onPendingAcknowledgedChanged(!pendingAcknowledged) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = pendingAcknowledged, onCheckedChange = onPendingAcknowledgedChanged)
                        Text("未印刷のまま精算することを責任者確認", color = SettlementDangerV030)
                    }
                }
                if (item.active && item.category == SettlementPreflightCategoryV135.BACKUP_FAILURE) {
                    Row(Modifier.fillMaxWidth().clickable { onBackupFailureAcknowledgedChanged(!backupFailureAcknowledged) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = backupFailureAcknowledged, onCheckedChange = onBackupFailureAcknowledgedChanged)
                        Text("バックアップ失敗を確認して精算を継続", color = SettlementDangerV030)
                    }
                }
            }
            preflight.message?.let { Text(it, color = SettlementDangerV030, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onExecute,
            enabled = session != null && (!isZ || (!summary.settled && preflight.mayProceed)),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (isZ) SettlementDangerV030 else SettlementBlueV030),
        ) { Text(if (isZ) "Z精算の確認へ" else "X点検を実行", fontWeight = FontWeight.Bold) }
        if (message != null) {
            Spacer(Modifier.height(8.dp))
            Text(message, color = if (message.contains("保存しました") || message.contains("終了しました")) SettlementGreenV030 else SettlementDangerV030, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SettlementPreviewPanelV030(
    modifier: Modifier,
    summary: DailyOperationsSummary,
    previewActual: Long?,
    variance: Long?,
    preflight: ZSettlementPreflightResult,
    isZ: Boolean,
) {
    SettlementPanelV030(modifier) {
        Text("プレビュー", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SettlementNavyV030)
        Spacer(Modifier.height(10.dp))
        SettlementAmountV030("営業セッション", if (summary.businessSessionId > 0) "No.${summary.businessSessionId}" else "未開始")
        SettlementAmountV030("営業日", summary.businessDate)
        SettlementAmountV030("売上総額", settlementYenV030(summary.salesGross))
        SettlementAmountV030("返品・取消", "-${settlementYenV030(summary.reversalGross)}")
        SettlementAmountV030("純売上", settlementYenV030(summary.netSales), true)
        SettlementAmountV030("現金理論", settlementYenV030(summary.expectedCash))
        SettlementAmountV030("現金実査", previewActual?.let(::settlementYenV030) ?: "未入力")
        SettlementAmountV030("過不足", variance?.let(::settlementSignedYenV030) ?: "未計算", true)
        SettlementAmountV030("未会計保留伝票", "${summary.heldTickets}件")
        SettlementAmountV030("販売途中明細", "${summary.openCartItems}明細")
        SettlementAmountV030("未完了決済", "${summary.incompletePayments}件")
        SettlementAmountV030("未印刷", "${summary.pendingPrints}件")
        SettlementAmountV030("バックアップ", if (summary.backupFailureMessage == null) "問題なし" else "失敗")
        if (isZ) {
            Spacer(Modifier.height(8.dp))
            preflight.items.forEach { Text("${it.category.displayName}: ${it.statusText}", color = if (it.active) SettlementDangerV030 else SettlementGreenV030) }
        }
    }
}
'''
replace_between(
    'app/src/main/java/jp/co/tenposinfo/register/SettlementActivityV030.kt',
    '@Composable\nprivate fun SettlementScreenV030(',
    '\n@Composable\nprivate fun SettlementHistoryPanelV030(',
    responsive_block,
)
