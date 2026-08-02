from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def replace_regex_once(text: str, pattern: str, replacement: str, label: str) -> str:
    result, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"{label}: expected one regex match, found {count}")
    return result


# Version
path = Path("app/build.gradle.kts")
text = path.read_text()
text = replace_once(text, 'versionCode = 55', 'versionCode = 56', 'versionCode')
text = replace_once(text, 'versionName = "0.25.0-dev.1"', 'versionName = "0.26.0-dev.1"', 'versionName')
path.write_text(text)

# Permission master and compatibility
path = Path("app/src/main/java/jp/co/tenposinfo/register/AdminSettingsStore.kt")
text = path.read_text()
text = replace_once(
    text,
    '    SETTLEMENT("点検・精算"),',
    '    X_INSPECTION("X点検"),\n    Z_SETTLEMENT("Z精算"),\n    SETTLEMENT("点検・精算（旧互換）"),',
    'permission enum',
)
text = replace_once(
    text,
    '        OperatorRole.MANAGER -> RegisterPermission.entries.toSet()',
    '        OperatorRole.MANAGER -> RegisterPermissionCompatibilityV026.selectablePermissions.toSet()',
    'manager defaults',
)
text = replace_once(
    text,
    '            val effectivePermissions = if (permissions.isEmpty()) OperatorPermissionPolicy.defaults(role) else permissions',
    '            val effectivePermissions = RegisterPermissionCompatibilityV026.normalizeForSave(\n                if (permissions.isEmpty()) OperatorPermissionPolicy.defaults(role) else permissions,\n            )',
    'permission save normalization',
)
old_load = '''    private fun loadPermissions(operatorId: Long): Set<RegisterPermission> {
        val permissions = linkedSetOf<RegisterPermission>()
        db.query(
            "operator_permissions",
            arrayOf("permission_key"),
            "operator_id = ?",
            arrayOf(operatorId.toString()),
            null,
            null,
            "permission_key ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                runCatching { RegisterPermission.valueOf(cursor.getString(0)) }.getOrNull()?.let(permissions::add)
            }
        }
        return permissions
    }
'''
new_load = old_load.replace('        return permissions\n', '        return RegisterPermissionCompatibilityV026.expand(permissions)\n')
text = replace_once(text, old_load, new_load, 'admin permission load')
path.write_text(text)

# Authentication permission compatibility
path = Path("app/src/main/java/jp/co/tenposinfo/register/OperatorSession.kt")
text = path.read_text()
old_auth_load = '''    private fun loadPermissions(operatorId: Long): Set<RegisterPermission> {
        val result = linkedSetOf<RegisterPermission>()
        db.query(
            "operator_permissions",
            arrayOf("permission_key"),
            "operator_id = ?",
            arrayOf(operatorId.toString()),
            null,
            null,
            "permission_key ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                runCatching { RegisterPermission.valueOf(cursor.getString(0)) }.getOrNull()?.let(result::add)
            }
        }
        return result
    }
'''
new_auth_load = old_auth_load.replace('        return result\n', '        return RegisterPermissionCompatibilityV026.expand(result)\n')
text = replace_once(text, old_auth_load, new_auth_load, 'auth permission load')
path.write_text(text)

# Permission UI hides legacy permission
path = Path("app/src/main/java/jp/co/tenposinfo/register/AdminSettingsActivity.kt")
text = path.read_text()
text = replace_once(
    text,
    '                    RegisterPermission.entries.forEach { permission ->',
    '                    RegisterPermissionCompatibilityV026.selectablePermissions.forEach { permission ->',
    'permission UI',
)
path.write_text(text)

# Secure operation routing and backend acknowledgement
secure = '''package jp.co.tenposinfo.register

import android.content.Context
import java.time.LocalDate

enum class OperationsAction(
    val permission: RegisterPermission,
    val managerApprovalRequired: Boolean,
) {
    DAILY_SALES(RegisterPermission.VIEW_SALES, false),
    X_INSPECTION(RegisterPermission.X_INSPECTION, false),
    Z_SETTLEMENT(RegisterPermission.Z_SETTLEMENT, false),
    SETTLEMENT(RegisterPermission.SETTLEMENT, false),
    CASH_MOVEMENT(RegisterPermission.CASH_MOVEMENT, false),
    REVERSAL(RegisterPermission.REVERSAL, true),
}

object OperationsAuthorizationPolicy {
    fun canAccess(operator: AuthenticatedOperator?, action: OperationsAction): Boolean =
        operator?.allows(action.permission) == true

    fun requiresManagerApproval(action: OperationsAction, settlementType: SettlementReportType? = null): Boolean =
        action.managerApprovalRequired || settlementType == SettlementReportType.Z_SETTLEMENT
}

object OperationsActorFormatter {
    fun direct(operator: AuthenticatedOperator): String = operator.name

    fun approved(operator: AuthenticatedOperator, managerName: String): String =
        "${operator.name}（承認:${managerName}）"
}

/**
 * 管理操作の書込直前に、現在のログインセッション・個別権限・責任者PINを再検証する。
 * UI表示だけの権限制御に依存せず、停止済み担当者や失効セッションからの書込を拒否する。
 * 同一プロセス内の連打はOperationExecutionGuardで拒否し、永続的な重複はDB操作キーで拒否する。
 */
class SecureOperationsCoordinator(
    context: Context,
    private val store: OperationsStore,
) {
    private val appContext = context.applicationContext
    private val executionGuard = OperationExecutionGuard()

    fun startBusinessDay(businessDate: LocalDate, openingCash: Long): Long =
        executionGuard.runExclusive("BUSINESS_OPEN:$businessDate", "営業開始を処理中です") {
            val operator = requireOperator(OperationsAction.Z_SETTLEMENT)
            store.startBusinessDay(businessDate, openingCash, OperationsActorFormatter.direct(operator))
        }

    fun recordCashMovement(type: CashMovementType, amount: Long, reason: String): Long {
        val operator = requireOperator(OperationsAction.CASH_MOVEMENT)
        return store.recordCashMovement(type, amount, reason, OperationsActorFormatter.direct(operator))
    }

    fun recordSettlement(
        type: SettlementReportType,
        actualCash: Long?,
        managerPin: String,
        pendingPrintsAcknowledged: Boolean = false,
    ): Long {
        val session = store.activeBusinessSession()
            ?: throw IllegalStateException("営業中の営業日がありません")
        val businessDate = session.businessDate
        val persistentKey = OperationsIdempotencyPolicy.settlementKey(type, session.id)
        val executionKey = persistentKey ?: "X_INSPECTION:SESSION:${session.id}"
        val action = when (type) {
            SettlementReportType.X_INSPECTION -> OperationsAction.X_INSPECTION
            SettlementReportType.Z_SETTLEMENT -> OperationsAction.Z_SETTLEMENT
        }
        var backupActor = "責任者"
        val settlementId = executionGuard.runExclusive(executionKey, "点検・精算を処理中です") {
            val operator = requireOperator(action)
            val actor = if (OperationsAuthorizationPolicy.requiresManagerApproval(action, type)) {
                OperationsActorFormatter.approved(operator, requireManagerName(managerPin))
            } else {
                OperationsActorFormatter.direct(operator)
            }
            backupActor = actor
            store.recordSettlement(type, actualCash, actor, pendingPrintsAcknowledged)
        }

        if (AutoBackupTriggerPolicy.shouldEnqueue(type, settlementCommitted = true)) {
            runCatching {
                AutoBackupScheduler.enqueueZSettlement(
                    context = appContext,
                    businessDate = businessDate,
                    businessSessionId = session.id,
                    settlementId = settlementId,
                    actorName = backupActor,
                )
            }.onFailure { error ->
                runCatching {
                    AutoBackupStatusStore(appContext).completed(
                        BackupCreationReason.Z_SETTLEMENT,
                        AutoBackupResultState.FAILED,
                        error.message ?: error.javaClass.simpleName,
                    )
                    AutoBackupAudit.record(
                        appContext,
                        "DATA_BACKUP_AUTO_FAILED",
                        "Z精算確定後の要求登録に失敗: ${error.message}",
                        backupActor,
                        settlementId,
                    )
                }
            }
        }
        return settlementId
    }

    fun createReversal(
        originalSaleId: Long,
        type: ReversalType,
        requestedQuantities: Map<Long, Int>,
        reason: String,
        managerPin: String,
        paperWidthMm: Int,
        requestId: String,
    ): PartialReversalResult {
        val executionKey = OperationsIdempotencyPolicy.reversalKey(originalSaleId)
        return executionGuard.runExclusive(executionKey, "返品・取消を処理中です") {
            val operator = requireOperator(OperationsAction.REVERSAL)
            val managerName = requireManagerName(managerPin)
            store.createReversal(
                originalSaleId = originalSaleId,
                type = type,
                requestedQuantities = requestedQuantities,
                reason = reason,
                operatorName = OperationsActorFormatter.approved(operator, managerName),
                paperWidthMm = paperWidthMm,
                requestId = requestId,
            )
        }
    }

    fun createFullReversal(
        originalSaleId: Long,
        type: ReversalType,
        reason: String,
        managerPin: String,
    ): Long = createReversal(
        originalSaleId = originalSaleId,
        type = type,
        requestedQuantities = if (type == ReversalType.RETURN) {
            store.loadReturnableLines(originalSaleId).associate { it.saleItemId to it.remainingQuantity }
        } else emptyMap(),
        reason = reason,
        managerPin = managerPin,
        paperWidthMm = 80,
        requestId = "FULL-${type.name}",
    ).reversalId

    private fun requireOperator(action: OperationsAction): AuthenticatedOperator {
        val operator = OperatorSessionRegistry.current(appContext)
            ?: throw SecurityException("ログインセッションが失効しています。販売画面から再ログインしてください")
        if (!OperationsAuthorizationPolicy.canAccess(operator, action)) {
            throw SecurityException("${action.permission.displayName}の権限がありません")
        }
        return operator
    }

    private fun requireManagerName(pin: String): String {
        require(pin.isNotBlank()) { "責任者PINを入力してください" }
        return AdminSettingsStore(appContext).use { it.managerNameForPin(pin) }
            ?: throw SecurityException("責任者PINが違います")
    }
}
'''
Path("app/src/main/java/jp/co/tenposinfo/register/SecureOperationsCoordinator.kt").write_text(secure)

# Store-level preflight enforcement
path = Path("app/src/main/java/jp/co/tenposinfo/register/OperationsStore.kt")
text = path.read_text()
text = replace_once(
    text,
    '''    fun recordSettlement(
        type: SettlementReportType,
        actualCash: Long?,
        operatorName: String,
    ): Long {''',
    '''    fun recordSettlement(
        type: SettlementReportType,
        actualCash: Long?,
        operatorName: String,
        pendingPrintsAcknowledged: Boolean = false,
    ): Long {''',
    'store settlement signature',
)
text = replace_once(
    text,
    '''            if (type == SettlementReportType.Z_SETTLEMENT && summary.settled) {
                throw IllegalStateException("この営業セッションは既にZ精算済みです")
            }
            val actual = actualCash ?: summary.expectedCash''',
    '''            if (type == SettlementReportType.Z_SETTLEMENT && summary.settled) {
                throw IllegalStateException("この営業セッションは既にZ精算済みです")
            }
            if (type == SettlementReportType.Z_SETTLEMENT) {
                val preflight = ZSettlementPreflightPolicy.evaluate(
                    heldTickets = summary.heldTickets,
                    pendingPrints = summary.pendingPrints,
                    pendingPrintsAcknowledged = pendingPrintsAcknowledged,
                )
                check(preflight.mayProceed) { preflight.message ?: "Z精算前の確認に失敗しました" }
            }
            val actual = actualCash ?: summary.expectedCash''',
    'store preflight',
)
text = replace_once(
    text,
    '''            if (type == SettlementReportType.Z_SETTLEMENT) {
                insertAudit(
                    eventType = "BUSINESS_CLOSE",''',
    '''            if (type == SettlementReportType.Z_SETTLEMENT && summary.pendingPrints > 0) {
                insertAudit(
                    eventType = "Z_SETTLEMENT_PENDING_PRINTS_ACKNOWLEDGED",
                    referenceId = id,
                    detail = "未印刷データ ${summary.pendingPrints}件を確認し、責任者承認でZ精算を継続",
                    operatorName = operatorName,
                    createdAt = now,
                )
            }
            if (type == SettlementReportType.Z_SETTLEMENT) {
                insertAudit(
                    eventType = "BUSINESS_CLOSE",''',
    'pending print audit',
)
path.write_text(text)

# Operations UI: separated permissions and Z preflight acknowledgement
path = Path("app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt")
text = path.read_text()
text = replace_once(text, 'import androidx.compose.material3.CardDefaults\n', 'import androidx.compose.material3.CardDefaults\nimport androidx.compose.material3.Checkbox\n', 'checkbox import')
text = replace_once(
    text,
    '''    fun executeSettlement(type: SettlementReportType, actualCash: Long?, pin: String) {
        val result = runCatching { secureStore.recordSettlement(type, actualCash, pin) }''',
    '''    fun executeSettlement(
        type: SettlementReportType,
        actualCash: Long?,
        pin: String,
        pendingPrintsAcknowledged: Boolean,
    ) {
        val result = runCatching {
            secureStore.recordSettlement(type, actualCash, pin, pendingPrintsAcknowledged)
        }''',
    'ui settlement execution',
)
text = replace_once(
    text,
    '''                    operator.allows(RegisterPermission.VIEW_SALES) ||
                    operator.allows(RegisterPermission.SETTLEMENT)''',
    '''                    operator.allows(RegisterPermission.VIEW_SALES) ||
                    operator.allows(RegisterPermission.X_INSPECTION) ||
                    operator.allows(RegisterPermission.Z_SETTLEMENT)''',
    'summary permissions',
)
text = text.replace('onBusiness = { openScreen(RegisterPermission.SETTLEMENT, OperationsScreen.BUSINESS) }', 'onBusiness = { openScreen(RegisterPermission.Z_SETTLEMENT, OperationsScreen.BUSINESS) }')
text = text.replace('onXInspection = { openScreen(RegisterPermission.SETTLEMENT, OperationsScreen.X_INSPECTION) }', 'onXInspection = { openScreen(RegisterPermission.X_INSPECTION, OperationsScreen.X_INSPECTION) }')
text = text.replace('onZSettlement = { openScreen(RegisterPermission.SETTLEMENT, OperationsScreen.Z_SETTLEMENT) }', 'onZSettlement = { openScreen(RegisterPermission.Z_SETTLEMENT, OperationsScreen.Z_SETTLEMENT) }')
text = replace_once(
    text,
    '''                onExecute = { actualCash, pin ->
                    executeSettlement(SettlementReportType.X_INSPECTION, actualCash, pin)
                },''',
    '''                onExecute = { actualCash, pin, pendingPrintsAcknowledged ->
                    executeSettlement(
                        SettlementReportType.X_INSPECTION,
                        actualCash,
                        pin,
                        pendingPrintsAcknowledged,
                    )
                },''',
    'x execute route',
)
text = replace_once(
    text,
    '''                onExecute = { actualCash, pin ->
                    executeSettlement(SettlementReportType.Z_SETTLEMENT, actualCash, pin)
                },''',
    '''                onExecute = { actualCash, pin, pendingPrintsAcknowledged ->
                    executeSettlement(
                        SettlementReportType.Z_SETTLEMENT,
                        actualCash,
                        pin,
                        pendingPrintsAcknowledged,
                    )
                },''',
    'z execute route',
)
text = replace_once(text, '                    RegisterPermission.SETTLEMENT in permissions,\n                    onBusiness,', '                    RegisterPermission.Z_SETTLEMENT in permissions,\n                    onBusiness,', 'business tile permission')
text = replace_once(text, '                        RegisterPermission.SETTLEMENT in permissions,\n                        onXInspection,', '                        RegisterPermission.X_INSPECTION in permissions,\n                        onXInspection,', 'x tile permission')
text = replace_once(text, '                        RegisterPermission.SETTLEMENT in permissions,\n                        onZSettlement,', '                        RegisterPermission.Z_SETTLEMENT in permissions,\n                        onZSettlement,', 'z tile permission')

new_settlement_screen = r'''@Composable
private fun SettlementScreen(
    reportType: SettlementReportType,
    screenCode: String,
    title: String,
    session: BusinessSessionRecord?,
    summary: DailyOperationsSummary,
    history: List<SettlementRecord>,
    operatorName: String,
    revision: Int,
    onExecute: (Long?, String, Boolean) -> Unit,
    message: String?,
    onBack: () -> Unit,
) {
    var actualCash by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var pendingPrintsAcknowledged by remember { mutableStateOf(false) }
    var showZConfirmation by remember { mutableStateOf(false) }
    @Suppress("UNUSED_VARIABLE") val refresh = revision
    val isZSettlement = reportType == SettlementReportType.Z_SETTLEMENT
    val actual = actualCash.toLongOrNull()
    val previewActual = actual ?: summary.expectedCash
    val variance = OperationsMath.variance(previewActual, summary.expectedCash)
    val zPreflight = if (isZSettlement) {
        ZSettlementPreflightPolicy.evaluate(
            heldTickets = summary.heldTickets,
            pendingPrints = summary.pendingPrints,
            pendingPrintsAcknowledged = pendingPrintsAcknowledged,
        )
    } else {
        ZSettlementPreflightResult(true, summary.heldTickets, summary.pendingPrints, false, null)
    }

    if (showZConfirmation) {
        AlertDialog(
            onDismissRequest = { showZConfirmation = false },
            title = { Text("Z精算して営業を終了しますか？", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("営業日 ${summary.businessDate} / セッションNo.${summary.businessSessionId}")
                    Text("純売上 ${opYen(summary.netSales)}")
                    Text("現金実査 ${opYen(previewActual)} / 過不足 ${signedYen(variance)}")
                    Text("未会計伝票 ${summary.heldTickets}件 / 未印刷 ${summary.pendingPrints}件")
                    if (summary.pendingPrints > 0) {
                        Text("未印刷データを残したまま精算する責任者確認済み", color = OpDanger, fontWeight = FontWeight.Bold)
                    }
                    Text("完了後、この営業セッションでは販売できません。", color = OpDanger, fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showZConfirmation = false
                        onExecute(actual, pin, pendingPrintsAcknowledged)
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
            OpPanel(Modifier.width(410.dp).fillMaxHeight()) {
                Text(if (isZSettlement) "Z精算" else "X点検", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (isZSettlement) OpDanger else OpNavy)
                Spacer(Modifier.height(6.dp))
                Text(
                    session?.let { "対象: ${it.businessDate} / セッションNo.${it.id}" } ?: "営業中のセッションがありません",
                    color = if (session == null) OpDanger else OpGreen,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(10.dp))
                OpNumericField("現金実査額（空欄は理論額）", actualCash, { actualCash = it })
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
                    if (summary.heldTickets > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "未会計伝票が${summary.heldTickets}件あります。会計または伝票取消を完了するまでZ精算できません。",
                            color = OpDanger,
                            fontWeight = FontWeight.Bold,
                        )
                    } else if (summary.pendingPrints > 0) {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                pendingPrintsAcknowledged = !pendingPrintsAcknowledged
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = pendingPrintsAcknowledged,
                                onCheckedChange = { pendingPrintsAcknowledged = it },
                            )
                            Text(
                                "未印刷データ${summary.pendingPrints}件を確認し、未印刷のまま精算する",
                                color = OpDanger,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (isZSettlement) {
                        "Z精算は現在の営業セッションに対して1回だけ実行し、完了と同時に営業終了します。同じ営業日で再開する場合は新しい営業セッションになります。"
                    } else {
                        "X点検は現在の営業セッションの値を保存します。営業は終了せず、そのまま販売を継続できます。"
                    },
                    color = Color.DarkGray,
                )
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        if (isZSettlement) showZConfirmation = true else onExecute(actual, "", false)
                    },
                    enabled = session != null && (!isZSettlement || (!summary.settled && summary.heldTickets == 0)),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (isZSettlement) OpDanger else OpBlue),
                ) {
                    Text(if (isZSettlement) "Z精算の確認へ" else "X点検を実行", fontWeight = FontWeight.Bold)
                }
                if (message != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        message,
                        color = if (
                            message.contains("違い") || message.contains("既に") ||
                            message.contains("失敗") || message.contains("未会計") || message.contains("未印刷")
                        ) OpDanger else OpGreen,
                    )
                }
            }

            OpPanel(Modifier.width(350.dp).fillMaxHeight()) {
                Text("プレビュー", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = OpNavy)
                Spacer(Modifier.height(10.dp))
                OpAmountRow("営業セッション", if (summary.businessSessionId > 0) "No.${summary.businessSessionId}" else "未開始")
                OpAmountRow("営業日", summary.businessDate)
                OpAmountRow("売上総額", opYen(summary.salesGross))
                OpAmountRow("返品・取消", "-${opYen(summary.reversalGross)}")
                OpAmountRow("純売上", opYen(summary.netSales), emphasized = true)
                OpAmountRow("現金理論", opYen(summary.expectedCash))
                OpAmountRow("現金実査", opYen(previewActual))
                OpAmountRow("過不足", signedYen(variance), emphasized = true)
                OpAmountRow("未印刷", "${summary.pendingPrints}件")
                OpAmountRow("未会計伝票", "${summary.heldTickets}件")
                if (summary.heldTickets > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text("未会計伝票があるためZ精算は禁止されています。", color = OpDanger, fontWeight = FontWeight.Bold)
                } else if (summary.pendingPrints > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text("未印刷データがあります。責任者確認後に限りZ精算できます。", color = OpDanger, fontWeight = FontWeight.Bold)
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

@Composable
private fun CashMovementScreen'''
text = replace_regex_once(
    text,
    r'@Composable\nprivate fun SettlementScreen\([\s\S]*?@Composable\nprivate fun CashMovementScreen',
    new_settlement_screen,
    'settlement screen',
)
path.write_text(text)

print("v0.26 sources applied")
