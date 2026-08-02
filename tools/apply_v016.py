from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
OPS = ROOT / "app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt"
SECURE = ROOT / "app/src/main/java/jp/co/tenposinfo/register/SecureOperationsCoordinator.kt"
TEST = ROOT / "app/src/test/java/jp/co/tenposinfo/register/V016OperationsAuthorizationTest.kt"
DOC = ROOT / "docs/V0.16_OPERATIONS_AUTHORIZATION.md"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, got {count}")
    return text.replace(old, new, 1)


text = OPS.read_text(encoding="utf-8")

start = text.index("@Composable\nprivate fun OperationsApp(onClose: () -> Unit) {")
end = text.index("\n@Composable\nprivate fun OperationsMenuScreen(", start)
new_app = r'''@Composable
private fun OperationsApp(onClose: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val store = remember { OperationsStore(appContext) }
    val secureStore = remember { SecureOperationsCoordinator(appContext, store) }
    val registerDatabase = remember { RegisterDatabase(appContext) }
    var screen by remember { mutableStateOf(OperationsScreen.MENU) }
    var revision by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var activeOperator by remember { mutableStateOf(OperatorSessionRegistry.current(appContext)) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(5_000L)
            activeOperator = OperatorSessionRegistry.current(appContext)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            store.close()
            registerDatabase.close()
        }
    }

    fun openScreen(permission: RegisterPermission, destination: OperationsScreen) {
        val current = OperatorSessionRegistry.current(appContext)
        activeOperator = current
        if (current?.allows(permission) == true) {
            message = null
            screen = destination
        } else {
            message = "${permission.displayName}の権限がありません"
        }
    }

    val operator = activeOperator
    Surface(Modifier.fillMaxSize(), color = OpBackground) {
        if (operator == null) {
            OperationsAccessDeniedScreen(onClose)
            return@Surface
        }

        when (screen) {
            OperationsScreen.MENU -> OperationsMenuScreen(
                summary = if (
                    operator.allows(RegisterPermission.VIEW_SALES) ||
                    operator.allows(RegisterPermission.SETTLEMENT)
                ) store.dailySummary() else null,
                operatorName = operator.name,
                permissions = operator.permissions,
                message = message,
                onDailySales = { openScreen(RegisterPermission.VIEW_SALES, OperationsScreen.DAILY_SALES) },
                onSettlement = { openScreen(RegisterPermission.SETTLEMENT, OperationsScreen.SETTLEMENT) },
                onCashMovement = { openScreen(RegisterPermission.CASH_MOVEMENT, OperationsScreen.CASH_MOVEMENT) },
                onReversal = { openScreen(RegisterPermission.REVERSAL, OperationsScreen.REVERSAL) },
                onClose = onClose,
            )

            OperationsScreen.DAILY_SALES -> DailySalesScreen(
                summary = store.dailySummary(),
                onBack = { screen = OperationsScreen.MENU },
            )

            OperationsScreen.SETTLEMENT -> SettlementScreen(
                summary = store.dailySummary(),
                history = store.recentSettlements(),
                operatorName = operator.name,
                revision = revision,
                onExecute = { type, actualCash, pin ->
                    val result = runCatching { secureStore.recordSettlement(type, actualCash, pin) }
                    message = result.fold(
                        onSuccess = { "${type.displayName}を保存しました（No.$it）" },
                        onFailure = { it.message ?: "保存に失敗しました" },
                    )
                    if (result.isSuccess) revision++
                    activeOperator = OperatorSessionRegistry.current(appContext)
                },
                message = message,
                onBack = { screen = OperationsScreen.MENU },
            )

            OperationsScreen.CASH_MOVEMENT -> CashMovementScreen(
                records = store.recentCashMovements(),
                operatorName = operator.name,
                revision = revision,
                message = message,
                onSave = { type, amount, reason ->
                    val result = runCatching { secureStore.recordCashMovement(type, amount, reason) }
                    message = result.fold(
                        onSuccess = { "${type.displayName}を保存しました（No.$it）" },
                        onFailure = { it.message ?: "保存に失敗しました" },
                    )
                    if (result.isSuccess) revision++
                    activeOperator = OperatorSessionRegistry.current(appContext)
                },
                onBack = { screen = OperationsScreen.MENU },
            )

            OperationsScreen.REVERSAL -> ReversalScreen(
                sales = registerDatabase.listSales(200),
                reversedSaleIds = store.reversedSaleIds(),
                reversals = store.recentReversals(),
                operatorName = operator.name,
                revision = revision,
                message = message,
                onExecute = { saleId, type, reason, pin ->
                    val result = runCatching { secureStore.createFullReversal(saleId, type, reason, pin) }
                    message = result.fold(
                        onSuccess = { "${type.displayName}を反対取引として保存しました（No.$it）" },
                        onFailure = { it.message ?: "処理に失敗しました" },
                    )
                    if (result.isSuccess) revision++
                    activeOperator = OperatorSessionRegistry.current(appContext)
                },
                onBack = { screen = OperationsScreen.MENU },
            )
        }
    }
}
'''
text = text[:start] + new_app + text[end:]

menu_start = text.index("@Composable\nprivate fun OperationsMenuScreen(")
menu_end = text.index("\n@Composable\nprivate fun MenuTile(", menu_start)
new_menu = r'''@Composable
private fun OperationsMenuScreen(
    summary: DailyOperationsSummary?,
    operatorName: String,
    permissions: Set<RegisterPermission>,
    message: String?,
    onDailySales: () -> Unit,
    onSettlement: () -> Unit,
    onCashMovement: () -> Unit,
    onReversal: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        OpHeader("SCR-700", "レジ管理メニュー")
        Row(
            Modifier.weight(1f).padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            OpPanel(Modifier.width(390.dp).fillMaxHeight()) {
                Text("本日の状態", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = OpNavy)
                Spacer(Modifier.height(8.dp))
                OpAuthenticatedOperator(operatorName)
                Spacer(Modifier.height(8.dp))
                if (summary == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("売上集計の表示権限がありません", color = Color.Gray)
                    }
                } else {
                    OpAmountRow("営業日", summary.businessDate)
                    OpAmountRow("純売上", opYen(summary.netSales), emphasized = true)
                    OpAmountRow("取引件数", "${summary.transactionCount}件")
                    OpAmountRow("返品・取消", "${summary.reversalCount}件 / -${opYen(summary.reversalGross)}")
                    OpAmountRow("現金理論残高", opYen(summary.expectedCash))
                    OpAmountRow("未印刷", "${summary.pendingPrints}件")
                    OpAmountRow("未会計伝票", "${summary.heldTickets}件")
                    OpAmountRow("精算状態", if (summary.settled) "Z精算済み" else "未精算")
                }
                if (message != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        message,
                        color = if (message.contains("失敗") || message.contains("違い") || message.contains("権限")) OpDanger else OpGreen,
                    )
                }
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    MenuTile(
                        "当日売上",
                        "SCR-510\n売上・支払・現金を確認",
                        OpPaleBlue,
                        Modifier.weight(1f),
                        RegisterPermission.VIEW_SALES in permissions,
                        onDailySales,
                    )
                    MenuTile(
                        "点検・精算",
                        "SCR-500\nX点検／Z精算／現金実査",
                        OpPaleGreen,
                        Modifier.weight(1f),
                        RegisterPermission.SETTLEMENT in permissions,
                        onSettlement,
                    )
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    MenuTile(
                        "入出金",
                        "入金・出金を理由付きで記録",
                        OpPaleYellow,
                        Modifier.weight(1f),
                        RegisterPermission.CASH_MOVEMENT in permissions,
                        onCashMovement,
                    )
                    MenuTile(
                        "返品・取消",
                        "元売上を残して反対取引を作成",
                        Color(0xFFFCE8E6),
                        Modifier.weight(1f),
                        RegisterPermission.REVERSAL in permissions,
                        onReversal,
                    )
                }
            }
        }
        OpBottomBar("販売へ戻る", onClose)
    }
}
'''
text = text[:menu_start] + new_menu + text[menu_end:]

menu_tile_start = text.index("@Composable\nprivate fun MenuTile(")
menu_tile_end = text.index("\n@Composable\nprivate fun DailySalesScreen", menu_tile_start)
new_tile = r'''@Composable
private fun MenuTile(
    title: String,
    description: String,
    background: Color,
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxHeight().clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (enabled) background else Color(0xFFE8ECEF)),
        border = BorderStroke(1.dp, OpBorder),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, fontSize = 27.sp, fontWeight = FontWeight.Bold, color = if (enabled) OpNavy else Color.Gray)
            Spacer(Modifier.height(12.dp))
            Text(
                if (enabled) description else "$description\n権限なし",
                textAlign = TextAlign.Center,
                color = if (enabled) Color.DarkGray else Color.Gray,
                lineHeight = 22.sp,
            )
        }
    }
}
'''
text = text[:menu_tile_start] + new_tile + text[menu_tile_end:]

text = replace_once(
    text,
    """private fun SettlementScreen(\n    summary: DailyOperationsSummary,\n    history: List<SettlementRecord>,\n    revision: Int,\n    onExecute: (SettlementReportType, Long?, String, String) -> Unit,""",
    """private fun SettlementScreen(\n    summary: DailyOperationsSummary,\n    history: List<SettlementRecord>,\n    operatorName: String,\n    revision: Int,\n    onExecute: (SettlementReportType, Long?, String) -> Unit,""",
    "settlement signature",
)
text = replace_once(text, '    var operator by remember { mutableStateOf("責任者") }\n', '', "settlement operator state")
text = replace_once(
    text,
    """                OutlinedTextField(\n                    value = operator,\n                    onValueChange = { operator = it.take(30) },\n                    label = { Text(\"担当者\") },\n                    singleLine = true,\n                    modifier = Modifier.fillMaxWidth(),\n                )""",
    """                OpAuthenticatedOperator(operatorName)""",
    "settlement operator field",
)
text = replace_once(text, 'label = { Text("責任者PIN（テスト：0000）") }', 'label = { Text("責任者PIN") }', "settlement pin label")
text = replace_once(
    text,
    "onClick = { onExecute(reportType, actual, operator, pin) },",
    "onClick = { onExecute(reportType, actual, pin) },",
    "settlement execute",
)

text = replace_once(
    text,
    """private fun CashMovementScreen(\n    records: List<CashMovementRecord>,\n    revision: Int,\n    message: String?,\n    onSave: (CashMovementType, Long, String, String) -> Unit,""",
    """private fun CashMovementScreen(\n    records: List<CashMovementRecord>,\n    operatorName: String,\n    revision: Int,\n    message: String?,\n    onSave: (CashMovementType, Long, String) -> Unit,""",
    "cash signature",
)
text = replace_once(text, '    var operator by remember { mutableStateOf("責任者") }\n', '', "cash operator state")
text = replace_once(
    text,
    """                OutlinedTextField(\n                    value = operator,\n                    onValueChange = { operator = it.take(30) },\n                    label = { Text(\"担当者\") },\n                    singleLine = true,\n                    modifier = Modifier.fillMaxWidth(),\n                )""",
    """                OpAuthenticatedOperator(operatorName)""",
    "cash operator field",
)
text = replace_once(
    text,
    "onSave(type, amount.toLongOrNull() ?: 0, reason, operator)",
    "onSave(type, amount.toLongOrNull() ?: 0, reason)",
    "cash save",
)

text = replace_once(
    text,
    """private fun ReversalScreen(\n    sales: List<SaleSummaryRecord>,\n    reversedSaleIds: Set<Long>,\n    reversals: List<ReversalRecord>,\n    revision: Int,\n    message: String?,\n    onExecute: (Long, ReversalType, String, String, String) -> Unit,""",
    """private fun ReversalScreen(\n    sales: List<SaleSummaryRecord>,\n    reversedSaleIds: Set<Long>,\n    reversals: List<ReversalRecord>,\n    operatorName: String,\n    revision: Int,\n    message: String?,\n    onExecute: (Long, ReversalType, String, String) -> Unit,""",
    "reversal signature",
)
text = replace_once(text, '    var operator by remember { mutableStateOf("責任者") }\n', '', "reversal operator state")
text = replace_once(
    text,
    """                OutlinedTextField(\n                    value = operator,\n                    onValueChange = { operator = it.take(30) },\n                    label = { Text(\"担当者\") },\n                    singleLine = true,\n                    modifier = Modifier.fillMaxWidth(),\n                )""",
    """                OpAuthenticatedOperator(operatorName)""",
    "reversal operator field",
)
text = replace_once(text, 'label = { Text("責任者PIN（テスト：0000）") }', 'label = { Text("責任者PIN") }', "reversal pin label")
text = replace_once(
    text,
    "selectedSaleId?.let { onExecute(it, type, reason, operator, pin) }",
    "selectedSaleId?.let { onExecute(it, type, reason, pin) }",
    "reversal execute",
)

helper_marker = "@Composable\nprivate fun OpHeader(screenId: String, title: String) {"
helper = r'''@Composable
private fun OperationsAccessDeniedScreen(onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("管理画面を利用できません", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = OpDanger)
        Spacer(Modifier.height(12.dp))
        Text("ログインセッションが失効したか、担当者が停止・権限変更されています。")
        Spacer(Modifier.height(24.dp))
        Button(onClick = onClose) { Text("販売画面へ戻る") }
    }
}

@Composable
private fun OpAuthenticatedOperator(operatorName: String) {
    Row(
        Modifier.fillMaxWidth().background(OpPaleBlue, RoundedCornerShape(8.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("操作担当", color = Color.DarkGray)
        Spacer(Modifier.weight(1f))
        Text(operatorName, fontWeight = FontWeight.Bold, color = OpNavy)
    }
}

'''
text = replace_once(text, helper_marker, helper + helper_marker, "security helper insertion")
text = text.replace('Text("REGISTER", color = Color.White', 'Text("つぐレジ", color = Color.White')

if 'テストPIN' in text or 'pin != "0000"' in text:
    raise RuntimeError("fixed test PIN remains")
if 'onValueChange = { operator =' in text:
    raise RuntimeError("editable operator field remains")

OPS.write_text(text, encoding="utf-8")

SECURE.write_text(r'''package jp.co.tenposinfo.register

import android.content.Context

enum class OperationsAction(
    val permission: RegisterPermission,
    val managerApprovalRequired: Boolean,
) {
    DAILY_SALES(RegisterPermission.VIEW_SALES, false),
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
 */
class SecureOperationsCoordinator(
    context: Context,
    private val store: OperationsStore,
) {
    private val appContext = context.applicationContext

    fun recordCashMovement(type: CashMovementType, amount: Long, reason: String): Long {
        val operator = requireOperator(OperationsAction.CASH_MOVEMENT)
        return store.recordCashMovement(type, amount, reason, OperationsActorFormatter.direct(operator))
    }

    fun recordSettlement(
        type: SettlementReportType,
        actualCash: Long?,
        managerPin: String,
    ): Long {
        val operator = requireOperator(OperationsAction.SETTLEMENT)
        val actor = if (OperationsAuthorizationPolicy.requiresManagerApproval(OperationsAction.SETTLEMENT, type)) {
            OperationsActorFormatter.approved(operator, requireManagerName(managerPin))
        } else {
            OperationsActorFormatter.direct(operator)
        }
        return store.recordSettlement(type, actualCash, actor)
    }

    fun createFullReversal(
        originalSaleId: Long,
        type: ReversalType,
        reason: String,
        managerPin: String,
    ): Long {
        val operator = requireOperator(OperationsAction.REVERSAL)
        val managerName = requireManagerName(managerPin)
        return store.createFullReversal(
            originalSaleId,
            type,
            reason,
            OperationsActorFormatter.approved(operator, managerName),
        )
    }

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
''', encoding="utf-8")

TEST.write_text(r'''package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V016OperationsAuthorizationTest {
    private fun operator(vararg permissions: RegisterPermission) = AuthenticatedOperator(
        id = 1,
        code = "OP01",
        name = "担当者A",
        role = OperatorRole.CASHIER,
        permissions = permissions.toSet(),
    )

    @Test
    fun eachManagementActionUsesItsOwnPermission() {
        val cashier = operator(RegisterPermission.CASH_MOVEMENT)

        assertTrue(OperationsAuthorizationPolicy.canAccess(cashier, OperationsAction.CASH_MOVEMENT))
        assertFalse(OperationsAuthorizationPolicy.canAccess(cashier, OperationsAction.DAILY_SALES))
        assertFalse(OperationsAuthorizationPolicy.canAccess(cashier, OperationsAction.SETTLEMENT))
        assertFalse(OperationsAuthorizationPolicy.canAccess(cashier, OperationsAction.REVERSAL))
    }

    @Test
    fun nullOrExpiredSessionCannotAccessManagementActions() {
        OperationsAction.entries.forEach { action ->
            assertFalse(OperationsAuthorizationPolicy.canAccess(null, action))
        }
    }

    @Test
    fun zSettlementAndReversalRequireManagerApproval() {
        assertFalse(
            OperationsAuthorizationPolicy.requiresManagerApproval(
                OperationsAction.SETTLEMENT,
                SettlementReportType.X_INSPECTION,
            ),
        )
        assertTrue(
            OperationsAuthorizationPolicy.requiresManagerApproval(
                OperationsAction.SETTLEMENT,
                SettlementReportType.Z_SETTLEMENT,
            ),
        )
        assertTrue(OperationsAuthorizationPolicy.requiresManagerApproval(OperationsAction.REVERSAL))
    }

    @Test
    fun auditActorContainsAuthenticatedOperatorAndApprovingManager() {
        val actor = operator(RegisterPermission.REVERSAL)

        assertEquals("担当者A", OperationsActorFormatter.direct(actor))
        assertEquals("担当者A（承認:責任者B）", OperationsActorFormatter.approved(actor, "責任者B"))
    }
}
''', encoding="utf-8")

DOC.write_text(r'''# v0.16 管理操作権限・責任者認証・監査整合

## 修正対象

`OperationsActivity` に残っていた固定テストPIN `0000` と担当者名の自由入力を廃止する。

## 認証・権限

- 当日売上：`VIEW_SALES`
- 点検・精算：`SETTLEMENT`
- 入出金：`CASH_MOVEMENT`
- 返品・取消：`REVERSAL`
- 管理画面を開いた後も、各画面への遷移時と書込直前に現在セッションを再取得する。
- セッション失効、担当者停止、権限変更後の書込を拒否する。

## 責任者承認

- X点検：責任者PIN不要
- Z精算：有効な責任者マスターのPINが必要
- 返品・取消：有効な責任者マスターのPINが必要
- 固定PINとの文字列比較は行わない。

## 監査

- 操作担当者はログイン中の認証済み担当者を使用する。
- Z精算および返品・取消は、操作担当者と承認責任者の双方を履歴へ残す。
- 担当者名の手入力によるなりすましを許可しない。

## 実機確認

- 権限の異なる担当者で管理メニューの有効・無効表示を確認する。
- 管理画面表示中に担当者停止・権限変更・30分失効した場合、書込が拒否されることを確認する。
- 責任者PIN変更後、旧PINが拒否され新PINが即時有効になることを確認する。
''', encoding="utf-8")

print("v0.16 source patch applied")
