from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")


def replace(path: str, old: str, new: str, count: int = 1) -> None:
    text = read(path)
    actual = text.count(old)
    if actual < count:
        raise RuntimeError(f"{path}: replacement source missing ({actual} < {count}): {old[:160]!r}")
    write(path, text.replace(old, new, count))


# 1. OperationsActivity consumes an exact reversal sale context but re-checks current REVERSAL permission.
replace(
    "app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt",
    '''        configureRegisterSystemBars(window)
        setContent {
            MaterialTheme {
                OperationsApp(onClose = { finish() })
            }
        }''',
    '''        configureRegisterSystemBars(window)
        val requestedReversalSaleId = ReversalNavigation.requestedSaleId(intent)
        setContent {
            MaterialTheme {
                OperationsApp(
                    requestedReversalSaleId = requestedReversalSaleId,
                    onClose = { finish() },
                )
            }
        }''',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt",
    '''@Composable
private fun OperationsApp(onClose: () -> Unit) {''',
    '''@Composable
private fun OperationsApp(
    requestedReversalSaleId: Long?,
    onClose: () -> Unit,
) {''',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt",
    '''    var message by remember { mutableStateOf<String?>(null) }
    var activeOperator by remember { mutableStateOf(OperatorSessionRegistry.current(appContext)) }

    androidx.compose.runtime.LaunchedEffect(Unit) {''',
    '''    var message by remember { mutableStateOf<String?>(null) }
    var activeOperator by remember { mutableStateOf(OperatorSessionRegistry.current(appContext)) }
    var reversalContextSaleId by remember { mutableStateOf<Long?>(null) }
    var requestedReversalHandled by remember(requestedReversalSaleId) { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(requestedReversalSaleId) {
        val requested = requestedReversalSaleId
        if (requested != null && !requestedReversalHandled) {
            val current = OperatorSessionRegistry.current(appContext)
            activeOperator = current
            if (current?.allows(RegisterPermission.REVERSAL) == true) {
                reversalContextSaleId = requested
                message = null
                screen = OperationsScreen.REVERSAL
            } else {
                reversalContextSaleId = null
                message = "返品・取消の権限がありません"
                screen = OperationsScreen.MENU
            }
            requestedReversalHandled = true
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {''',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt",
    '''                onCashMovement = { openScreen(RegisterPermission.CASH_MOVEMENT, OperationsScreen.CASH_MOVEMENT) },
                onReversal = { openScreen(RegisterPermission.REVERSAL, OperationsScreen.REVERSAL) },
                onClose = onClose,''',
    '''                onCashMovement = { openScreen(RegisterPermission.CASH_MOVEMENT, OperationsScreen.CASH_MOVEMENT) },
                onReversal = {
                    reversalContextSaleId = null
                    openScreen(RegisterPermission.REVERSAL, OperationsScreen.REVERSAL)
                },
                onClose = onClose,''',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt",
    '''            OperationsScreen.REVERSAL -> ReversalScreen(
                sales = registerDatabase.listSales(SalesHistoryLookupPolicy.RECENT_LOAD_LIMIT),''',
    '''            OperationsScreen.REVERSAL -> ReversalScreen(
                initialSaleId = reversalContextSaleId,
                sales = registerDatabase.listSales(SalesHistoryLookupPolicy.RECENT_LOAD_LIMIT),''',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt",
    '''                },
                onBack = { screen = OperationsScreen.MENU },
            )
        }
    }
}

@Composable
private fun OperationsMenuScreen(''',
    '''                },
                onBack = {
                    reversalContextSaleId = null
                    screen = OperationsScreen.MENU
                },
            )
        }
    }
}

@Composable
private fun OperationsMenuScreen(''',
)

# 2. ReversalScreen locks a valid sale opened from sale detail and requires an explicit unlock to search another sale.
replace(
    "app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt",
    '''@Composable
private fun ReversalScreen(
    sales: List<SaleSummaryRecord>,''',
    '''@Composable
private fun ReversalScreen(
    initialSaleId: Long?,
    sales: List<SaleSummaryRecord>,''',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt",
    '''    var selectedSaleId by remember { mutableStateOf<Long?>(null) }
    var lines by remember { mutableStateOf<List<ReturnableSaleLine>>(emptyList()) }''',
    '''    var selectedSaleId by remember(initialSaleId) { mutableStateOf<Long?>(initialSaleId) }
    var contextSaleLocked by remember(initialSaleId) { mutableStateOf(initialSaleId != null) }
    var lines by remember { mutableStateOf<List<ReturnableSaleLine>>(emptyList()) }''',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt",
    '''    val canExecute = selected != null && reason.isNotBlank() && pin.isNotBlank() && when (type) {
        ReversalType.CANCEL -> canCancel
        ReversalType.RETURN -> selectedItems.isNotEmpty()
    }

    Column(Modifier.fillMaxSize()) {''',
    '''    val canExecute = selected != null && reason.isNotBlank() && pin.isNotBlank() && when (type) {
        ReversalType.CANCEL -> canCancel
        ReversalType.RETURN -> selectedItems.isNotEmpty()
    }

    androidx.compose.runtime.LaunchedEffect(initialSaleId) {
        val saleId = initialSaleId ?: return@LaunchedEffect
        val sale = lookupSale(saleId)
        val context = ReversalNavigation.resolve(
            requestedSaleId = saleId,
            saleExists = sale != null,
            alreadyCompleted = saleId in reversedSaleIds,
        )
        when {
            !context.saleExists -> {
                selectedSaleId = null
                directSaleOverride = null
                contextSaleLocked = false
                lines = emptyList()
                quantities = emptyMap()
                savedResult = null
                requestId = UUID.randomUUID().toString()
                localMessage = "指定された売上No.$saleId は見つかりません。元売上は選択していません"
            }
            context.alreadyCompleted -> {
                selectedSaleId = null
                directSaleOverride = null
                contextSaleLocked = false
                lines = emptyList()
                quantities = emptyMap()
                savedResult = null
                requestId = UUID.randomUUID().toString()
                localMessage = "売上No.$saleId は全量返品・取消済みです。元売上は選択していません"
            }
            context.mayOpenLocked && sale != null -> {
                selectedSaleId = sale.id
                directSaleOverride = sale.takeIf { candidate -> sales.none { it.id == candidate.id } }
                lines = runCatching { loadLines(sale.id) }.getOrElse {
                    localMessage = it.message ?: "明細取得に失敗しました"
                    emptyList()
                }
                quantities = emptyMap()
                savedResult = null
                requestId = UUID.randomUUID().toString()
                if (lines.isEmpty()) {
                    selectedSaleId = null
                    directSaleOverride = null
                    contextSaleLocked = false
                    localMessage = "売上No.${sale.id} に返品可能な明細がありません。元売上は選択していません"
                } else {
                    contextSaleLocked = true
                    localMessage = "売上No.${sale.id} から開きました。元売上を固定しています"
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {''',
)

# Insert fixed-context UI immediately after left-panel heading.
replace(
    "app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt",
    '''                Text("元売上を検索・選択", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = OpNavy)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = saleQuery,
                    onValueChange = { saleQuery = it.take(80) },
                    label = { Text("売上No.・担当・支払") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )''',
    '''                Text("元売上を検索・選択", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = OpNavy)
                if (contextSaleLocked && selectedSaleId != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        colors = CardDefaults.cardColors(containerColor = OpPaleBlue),
                        border = BorderStroke(2.dp, OpBlue),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(8.dp)) {
                            Text(
                                "売上No.$selectedSaleId から開いています",
                                fontWeight = FontWeight.Bold,
                                color = OpNavy,
                            )
                            Text("対象売上は固定中です。変更する場合だけ下のボタンを押してください。", fontSize = 11.sp)
                            Spacer(Modifier.height(5.dp))
                            OutlinedButton(
                                onClick = {
                                    contextSaleLocked = false
                                    selectedSaleId = null
                                    directSaleOverride = null
                                    lines = emptyList()
                                    quantities = emptyMap()
                                    savedResult = null
                                    requestId = UUID.randomUUID().toString()
                                    localMessage = "元売上固定を解除しました。別売上を検索してください"
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("別売上を検索") }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = saleQuery,
                    onValueChange = { saleQuery = it.take(80) },
                    enabled = !contextSaleLocked,
                    label = { Text("売上No.・担当・支払") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )''',
)
# Direct ID field and button disabled during context lock.
replace(
    "app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt",
    '''                        label = { Text("売上No.直接指定") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),''',
    '''                        enabled = !contextSaleLocked,
                        label = { Text("売上No.直接指定") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),''',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt",
    '''                        enabled = directSaleId != null,
                        colors = ButtonDefaults.buttonColors(containerColor = OpBlue),''',
    '''                        enabled = directSaleId != null && !contextSaleLocked,
                        colors = ButtonDefaults.buttonColors(containerColor = OpBlue),''',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt",
    '''                                .clickable(enabled = !completed) {''',
    '''                                .clickable(enabled = !completed && !contextSaleLocked) {''',
)

# 3. Sale detail shows reversal entry only to operators with REVERSAL permission.
replace(
    "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt",
    '''                    SaleDetailScreen(
                        detail = detail,
                        onReceipt = { screen = AppScreen.RECEIPT_PREVIEW },
                        onVoucher = {
                            context.startActivity(ReceiptVoucherNavigation.issuanceIntent(context, detail.summary.id))
                        },
                        onBack = { screen = AppScreen.SALES_HISTORY },
                    )''',
    '''                    SaleDetailScreen(
                        detail = detail,
                        canReverse = currentOperator?.allows(RegisterPermission.REVERSAL) == true,
                        onReceipt = { screen = AppScreen.RECEIPT_PREVIEW },
                        onVoucher = {
                            context.startActivity(ReceiptVoucherNavigation.issuanceIntent(context, detail.summary.id))
                        },
                        onReverse = {
                            context.startActivity(ReversalNavigation.intent(context, detail.summary.id))
                        },
                        onBack = { screen = AppScreen.SALES_HISTORY },
                    )''',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt",
    '''private fun SaleDetailScreen(
    detail: SaleDetailRecord,
    onReceipt: () -> Unit,
    onVoucher: () -> Unit,
    onBack: () -> Unit,
) {''',
    '''private fun SaleDetailScreen(
    detail: SaleDetailRecord,
    canReverse: Boolean,
    onReceipt: () -> Unit,
    onVoucher: () -> Unit,
    onReverse: () -> Unit,
    onBack: () -> Unit,
) {''',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt",
    '''                OutlinedButton(onClick = onVoucher, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("この売上で領収書発行")
                }
            }''',
    '''                OutlinedButton(onClick = onVoucher, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("この売上で領収書発行")
                }
                if (canReverse) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onReverse, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text("この売上を返品・取消", color = Danger, fontWeight = FontWeight.Bold)
                    }
                }
            }''',
)

# 4. Version advance and current-version expectations in cumulative tests.
replace("app/build.gradle.kts", "versionCode = 93", "versionCode = 94")
replace("app/build.gradle.kts", 'versionName = "0.63.0-dev.1"', 'versionName = "0.64.0-dev.1"')

old_apk = "TSUGUREGI_v0.63.0_dev1_reversal_sale_lookup_debug.apk"
new_apk = "TSUGUREGI_v0.64.0_dev1_sale_detail_reversal_navigation_debug.apk"
old_artifact = "TSUGUREGI-v0.63.0-dev1-reversal-sale-lookup-apks"
new_artifact = "TSUGUREGI-v0.64.0-dev1-sale-detail-reversal-navigation-apks"
for root in [ROOT / "app/src/test", ROOT / "management-app/src/test"]:
    for path in root.rglob("*.kt"):
        text = path.read_text(encoding="utf-8")
        updated = (
            text.replace("versionCode = 93", "versionCode = 94")
            .replace("0.63.0-dev.1", "0.64.0-dev.1")
            .replace(old_apk, new_apk)
            .replace(old_artifact, new_artifact)
        )
        if updated != text:
            path.write_text(updated, encoding="utf-8")

# 5. v0.64 integration tests.
write(
    "app/src/test/java/jp/co/tenposinfo/register/V064SaleDetailReversalNavigationTest.kt",
    '''package jp.co.tenposinfo.register

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V064SaleDetailReversalNavigationTest {
    @Test
    fun reversalSaleContextOpensLockedOnlyForExistingIncompleteSale() {
        assertTrue(ReversalNavigation.resolve(10L, saleExists = true, alreadyCompleted = false).mayOpenLocked)
        assertFalse(ReversalNavigation.resolve(10L, saleExists = false, alreadyCompleted = false).mayOpenLocked)
        assertFalse(ReversalNavigation.resolve(10L, saleExists = true, alreadyCompleted = true).mayOpenLocked)
        assertFalse(ReversalNavigation.resolve(null, saleExists = true, alreadyCompleted = false).mayOpenLocked)
    }

    @Test
    fun sourceRechecksPermissionAndLocksExactSaleUntilExplicitUnlock() {
        val root = File("..")
        val main = File("src/main/java/jp/co/tenposinfo/register/MainActivity.kt").readText()
        val operations = File("src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt").readText()
        val secure = File("src/main/java/jp/co/tenposinfo/register/SecureOperationsCoordinator.kt").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()

        assertTrue(main.contains("canReverse = currentOperator?.allows(RegisterPermission.REVERSAL) == true"))
        assertTrue(main.contains("ReversalNavigation.intent(context, detail.summary.id)"))
        assertTrue(main.contains("この売上を返品・取消"))
        assertTrue(operations.contains("ReversalNavigation.requestedSaleId(intent)"))
        assertTrue(operations.contains("current?.allows(RegisterPermission.REVERSAL) == true"))
        assertTrue(operations.contains("initialSaleId = reversalContextSaleId"))
        assertTrue(operations.contains("contextSaleLocked"))
        assertTrue(operations.contains("元売上を固定しています"))
        assertTrue(operations.contains("別売上を検索"))
        assertTrue(operations.contains("enabled = !contextSaleLocked"))
        assertTrue(operations.contains("!completed && !contextSaleLocked"))
        assertTrue(operations.contains("secureStore.createReversal"))
        assertTrue(secure.contains("RegisterPermission.REVERSAL"))
        assertTrue(workflow.contains("V064SaleDetailReversalNavigationTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_v0.64.0_dev1_sale_detail_reversal_navigation_debug.apk"))
    }
}
''',
)

write(
    "docs/V0.64_SALE_DETAIL_REVERSAL_NAVIGATION.md",
    '''# v0.64 売上詳細→返品・取消 安全導線

## 目的

売上一覧・検索で対象売上を確認した後、返品・取消画面でもう一度同じ売上を検索・指定する二重操作をなくし、元売上の取り違えを防ぐ。

## 導線

1. SCR-400 売上一覧・検索で売上を開く。
2. SCR-410 売上詳細で明細、税、支払を確認する。
3. `REVERSAL`権限を持つ担当者にだけ「この売上を返品・取消」を表示する。
4. 選択中の売上No.を `ReversalNavigation` で `OperationsActivity` へ引き継ぐ。
5. `OperationsActivity` は起動時に現在の認証済みセッションと `REVERSAL` 権限を再取得・再検証する。
6. 権限が有効な場合だけ返品・取消画面を直接開く。
7. 指定売上が存在し、全量返品・取消済みでなければ元売上を固定して明細を表示する。

## 取り違え防止

- 売上詳細から開いた直後は検索欄、売上No.直接指定、他売上行の選択を無効化する。
- 画面に「売上No.Xから開いています」「対象売上は固定中」を表示する。
- 別売上へ切り替える場合は「別売上を検索」を明示的に押す。
- 固定解除時は現在の元売上、返品数量、プレビュー、要求IDをクリアする。
- 指定売上が不存在、全量返品・取消済み、返品可能明細なしの場合はfail-closedで元売上を選択しない。

## 維持する安全条件

- 元売上と元売上明細は変更・削除しない。
- 部分返品は `loadReturnableLines()` と `PartialReturnPolicy` を継続利用する。
- 販売時税スナップショットを使用する。
- 商品値引と混合支払の返金按分を維持する。
- `REVERSAL`権限をDB書込直前にも再検証する。
- 責任者PINをDB書込直前に再検証する。
- 要求ID・永続操作キーによる二重処理防止を維持する。
- SQLiteトランザクションによる原子確定を維持する。

## 実機確認が必要な項目

- 売上詳細→返品・取消の画面遷移
- 権限あり／なし担当者で導線表示が変わること
- 起動後に権限を剥奪した場合に返品画面へ入れないこと
- 指定売上が固定され、他行をタップしても切り替わらないこと
- 「別売上を検索」で固定解除されること
- 古い売上の直接検索→詳細→返品・取消の一連操作
- v0.63→v0.64上書き更新
''',
)
write(
    "docs/V0.64_RELEASE_NOTES.md",
    '''# v0.64 Release Notes

- 売上詳細へ「この売上を返品・取消」導線を追加。
- 導線は `REVERSAL` 権限を持つ担当者だけに表示。
- `OperationsActivity` 側でも起動時に現在権限を再検証。
- 売上詳細から開いた返品画面では指定売上を初期固定し、別売上の誤選択を防止。
- 固定中は検索、売上No.直接指定、他売上行の選択を無効化。
- 「別売上を検索」を明示操作すると、選択・数量・プレビュー・要求IDをクリアして固定解除。
- 指定売上が不存在、全量返品取消済み、返品可能明細なしの場合は元売上を選択しないfail-closed動作。
- v0.63までの部分返品・税スナップショット・値引按分・混合支払返金・責任者PIN・冪等性・原子処理を維持。
''',
)

# 6. Advance cumulative workflow.
workflow_path = ".github/workflows/build-apk.yml"
text = read(workflow_path)
replacements = [
    ("Verify cumulative v0.14-v0.63 sources", "Verify cumulative v0.14-v0.64 sources"),
    ("grep -q 'versionCode = 93' app/build.gradle.kts", "grep -q 'versionCode = 94' app/build.gradle.kts"),
    ("grep -q 'versionName = \"0.63.0-dev.1\"' app/build.gradle.kts", "grep -q 'versionName = \"0.64.0-dev.1\"' app/build.gradle.kts"),
    (old_apk, new_apk),
    (old_artifact, new_artifact),
    ("REGISTER_VERSION_NAME=0.63.0-dev.1", "REGISTER_VERSION_NAME=0.64.0-dev.1"),
    ("REGISTER_VERSION_CODE=93", "REGISTER_VERSION_CODE=94"),
]
for old, new in replacements:
    if old not in text:
        raise RuntimeError(f"workflow replacement missing: {old}")
    text = text.replace(old, new)
text = text.replace(
    "          test -s app/src/test/java/jp/co/tenposinfo/register/V063ReversalSaleLookupTest.kt\n",
    "          test -s app/src/test/java/jp/co/tenposinfo/register/V063ReversalSaleLookupTest.kt\n"
    "          test -s app/src/test/java/jp/co/tenposinfo/register/V064SaleDetailReversalNavigationTest.kt\n",
)
text = text.replace(
    "          test -s docs/V0.63_RELEASE_NOTES.md\n",
    "          test -s docs/V0.63_RELEASE_NOTES.md\n"
    "          test -s docs/V0.64_SALE_DETAIL_REVERSAL_NAVIGATION.md\n"
    "          test -s docs/V0.64_RELEASE_NOTES.md\n",
)
anchor = "          grep -q '元売上の選択を解除しました' app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt\n"
if anchor not in text:
    raise RuntimeError("workflow v0.63 source anchor missing")
text = text.replace(
    anchor,
    anchor
    + "          test -s app/src/main/java/jp/co/tenposinfo/register/ReversalNavigation.kt\n"
    + "          grep -q 'ReversalNavigation.intent(context, detail.summary.id)' app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt\n"
    + "          grep -q 'current?.allows(RegisterPermission.REVERSAL) == true' app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt\n"
    + "          grep -q '別売上を検索' app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt\n",
)
summary_anchor = "          REVERSAL_SAFETY_ENGINE_UNCHANGED=true\n"
if summary_anchor not in text:
    raise RuntimeError("workflow v0.63 summary anchor missing")
text = text.replace(
    summary_anchor,
    summary_anchor
    + "          SALE_DETAIL_REVERSAL_NAVIGATION=true\n"
    + "          REVERSAL_CONTEXT_PERMISSION_RECHECK=true\n"
    + "          REVERSAL_CONTEXT_SALE_LOCK=true\n"
    + "          REVERSAL_CONTEXT_EXPLICIT_UNLOCK=true\n",
)
real_anchor = "          REAL_DEVICE_REVERSAL_SALE_LOOKUP_VERIFICATION=required\n"
if real_anchor not in text:
    raise RuntimeError("workflow v0.63 real-device anchor missing")
text = text.replace(
    real_anchor,
    "          REAL_DEVICE_SALE_DETAIL_REVERSAL_NAVIGATION_VERIFICATION=required\n" + real_anchor,
)
write(workflow_path, text)

# Remove generation-only files from final generated commit.
for relative in ["tools/v064_apply.py", ".github/workflows/v064-apply.yml"]:
    p = ROOT / relative
    if p.exists():
        p.unlink()

print("v0.64 patch applied")
