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
        raise RuntimeError(f"{path}: replacement source missing ({actual} < {count}): {old[:140]!r}")
    write(path, text.replace(old, new, count))


# 1. Reversal screen receives up to 1,000 recent sales and an exact-ID lookup callback.
replace(
    "app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt",
    '''            OperationsScreen.REVERSAL -> ReversalScreen(
                sales = registerDatabase.listSales(200),
                reversedSaleIds = store.reversedSaleIds(),
                reversals = store.recentReversals(),
                operatorName = operator.name,
                revision = revision,
                message = message,
                printerPaperWidthMm = PrinterPaperSettingPolicy.currentWidthMm(appContext),
                loadLines = store::loadReturnableLines,
                onExecute = { saleId, type, quantities, reason, pin, requestId ->''',
    '''            OperationsScreen.REVERSAL -> ReversalScreen(
                sales = registerDatabase.listSales(SalesHistoryLookupPolicy.RECENT_LOAD_LIMIT),
                reversedSaleIds = store.reversedSaleIds(),
                reversals = store.recentReversals(),
                operatorName = operator.name,
                revision = revision,
                message = message,
                printerPaperWidthMm = PrinterPaperSettingPolicy.currentWidthMm(appContext),
                loadLines = store::loadReturnableLines,
                lookupSale = { saleId -> registerDatabase.loadSaleDetail(saleId)?.summary },
                onExecute = { saleId, type, quantities, reason, pin, requestId ->''',
)

replace(
    "app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt",
    '''    printerPaperWidthMm: Int,
    loadLines: (Long) -> List<ReturnableSaleLine>,
    onExecute: (Long, ReversalType, Map<Long, Int>, String, String, String) -> PartialReversalResult?,''',
    '''    printerPaperWidthMm: Int,
    loadLines: (Long) -> List<ReturnableSaleLine>,
    lookupSale: (Long) -> SaleSummaryRecord?,
    onExecute: (Long, ReversalType, Map<Long, Int>, String, String, String) -> PartialReversalResult?,''',
)

replace(
    "app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt",
    '''    var savedResult by remember { mutableStateOf<PartialReversalResult?>(null) }
    var localMessage by remember { mutableStateOf<String?>(null) }
    @Suppress("UNUSED_VARIABLE") val refresh = revision
    val selected = sales.firstOrNull { it.id == selectedSaleId }
    val selectedItems = runCatching { PartialReturnPolicy.select(type, lines, quantities) }.getOrNull().orEmpty()''',
    '''    var savedResult by remember { mutableStateOf<PartialReversalResult?>(null) }
    var localMessage by remember { mutableStateOf<String?>(null) }
    var saleQuery by remember { mutableStateOf("") }
    var directSaleIdText by remember { mutableStateOf("") }
    var directSaleOverride by remember { mutableStateOf<SaleSummaryRecord?>(null) }
    @Suppress("UNUSED_VARIABLE") val refresh = revision
    val visibleSales = SalesHistoryLookupPolicy.filter(sales, SalesHistoryCriteria(query = saleQuery))
    val directSaleId = SalesHistoryLookupPolicy.parseDirectSaleId(directSaleIdText)
    val selected = directSaleOverride?.takeIf { it.id == selectedSaleId }
        ?: sales.firstOrNull { it.id == selectedSaleId }
    val selectedItems = runCatching { PartialReturnPolicy.select(type, lines, quantities) }.getOrNull().orEmpty()''',
)

# Replace only the left selection panel; calculation and execution panels remain untouched.
old_panel = '''            OpPanel(Modifier.width(315.dp).fillMaxHeight()) {
                Text("元売上を選択", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = OpNavy)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(sales) { _, sale ->
                        val completed = sale.id in reversedSaleIds
                        val selectedRow = sale.id == selectedSaleId
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(if (selectedRow) OpPaleBlue else Color.Transparent)
                                .clickable(enabled = !completed) {
                                    selectedSaleId = sale.id
                                    lines = runCatching { loadLines(sale.id) }.getOrElse {
                                        localMessage = it.message ?: "明細取得に失敗しました"
                                        emptyList()
                                    }
                                    quantities = emptyMap()
                                    savedResult = null
                                    requestId = UUID.randomUUID().toString()
                                }
                                .padding(horizontal = 7.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("No.${sale.id}  ${opDateTime(sale.createdAt)}", fontWeight = FontWeight.Bold)
                                Text("${sale.paymentLabel} / ${sale.operatorName}", color = Color.Gray, fontSize = 12.sp)
                            }
                            Text(opYen(sale.totalAmount), fontWeight = FontWeight.Bold)
                            if (completed) Text(" 完了", color = OpDanger, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("最近の処理", fontWeight = FontWeight.Bold, color = OpNavy)
                reversals.take(3).forEach { record ->
                    Text("${record.type.displayName} No.${record.originalSaleId}  -${opYen(record.grossAmount)}", fontSize = 12.sp)
                }
            }
'''
new_panel = '''            OpPanel(Modifier.width(355.dp).fillMaxHeight()) {
                Text("元売上を検索・選択", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = OpNavy)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = saleQuery,
                    onValueChange = { saleQuery = it.take(80) },
                    label = { Text("売上No.・担当・支払") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(5.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = directSaleIdText,
                        onValueChange = {
                            directSaleIdText = it.filter { ch -> ch.isDigit() || ch == '#' }.take(20)
                            localMessage = null
                        },
                        label = { Text("売上No.直接指定") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            val saleId = directSaleId ?: return@Button
                            val sale = lookupSale(saleId)
                            when {
                                sale == null -> {
                                    localMessage = "売上No.$saleId は見つかりません"
                                }
                                sale.id in reversedSaleIds -> {
                                    localMessage = "売上No.${sale.id} は全量返品・取消済みです"
                                }
                                else -> {
                                    selectedSaleId = sale.id
                                    directSaleOverride = sale.takeIf { candidate -> sales.none { it.id == candidate.id } }
                                    lines = runCatching { loadLines(sale.id) }.getOrElse {
                                        localMessage = it.message ?: "明細取得に失敗しました"
                                        emptyList()
                                    }
                                    quantities = emptyMap()
                                    savedResult = null
                                    requestId = UUID.randomUUID().toString()
                                    localMessage = if (lines.isEmpty()) "返品可能な明細がありません" else null
                                }
                            }
                        },
                        enabled = directSaleId != null,
                        colors = ButtonDefaults.buttonColors(containerColor = OpBlue),
                    ) { Text("表示") }
                }
                Text(
                    "表示 ${visibleSales.size}件 / 読込 ${sales.size}件（直近最大${SalesHistoryLookupPolicy.RECENT_LOAD_LIMIT}件）",
                    color = Color.Gray,
                    fontSize = 11.sp,
                )
                directSaleOverride?.takeIf { it.id == selectedSaleId }?.let { sale ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                        colors = CardDefaults.cardColors(containerColor = OpPaleBlue),
                        border = BorderStroke(2.dp, OpBlue),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(8.dp)) {
                            Text("直接指定 No.${sale.id}", fontWeight = FontWeight.Bold, color = OpNavy)
                            Text("${opDateTime(sale.createdAt)} / ${sale.operatorName} / ${sale.paymentLabel}", fontSize = 11.sp)
                            Text(opYen(sale.totalAmount), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(5.dp))
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(visibleSales) { _, sale ->
                        val completed = sale.id in reversedSaleIds
                        val selectedRow = sale.id == selectedSaleId
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(if (selectedRow) OpPaleBlue else Color.Transparent)
                                .clickable(enabled = !completed) {
                                    selectedSaleId = sale.id
                                    directSaleOverride = null
                                    lines = runCatching { loadLines(sale.id) }.getOrElse {
                                        localMessage = it.message ?: "明細取得に失敗しました"
                                        emptyList()
                                    }
                                    quantities = emptyMap()
                                    savedResult = null
                                    requestId = UUID.randomUUID().toString()
                                    localMessage = if (lines.isEmpty()) "返品可能な明細がありません" else null
                                }
                                .padding(horizontal = 7.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("No.${sale.id}  ${opDateTime(sale.createdAt)}", fontWeight = FontWeight.Bold)
                                Text("${sale.paymentLabel} / ${sale.operatorName}", color = Color.Gray, fontSize = 12.sp)
                            }
                            Text(opYen(sale.totalAmount), fontWeight = FontWeight.Bold)
                            if (completed) Text(" 完了", color = OpDanger, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("最近の処理", fontWeight = FontWeight.Bold, color = OpNavy)
                reversals.take(3).forEach { record ->
                    Text("${record.type.displayName} No.${record.originalSaleId}  -${opYen(record.grossAmount)}", fontSize = 12.sp)
                }
            }
'''
replace("app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt", old_panel, new_panel)

# 2. Version advance.
replace("app/build.gradle.kts", "versionCode = 92", "versionCode = 93")
replace("app/build.gradle.kts", 'versionName = "0.62.0-dev.1"', 'versionName = "0.63.0-dev.1"')

old_apk = "TSUGUREGI_v0.62.0_dev1_sales_history_lookup_debug.apk"
new_apk = "TSUGUREGI_v0.63.0_dev1_reversal_sale_lookup_debug.apk"
old_artifact = "TSUGUREGI-v0.62.0-dev1-sales-history-lookup-apks"
new_artifact = "TSUGUREGI-v0.63.0-dev1-reversal-sale-lookup-apks"
for root in [ROOT / "app/src/test", ROOT / "management-app/src/test"]:
    for path in root.rglob("*.kt"):
        text = path.read_text(encoding="utf-8")
        updated = (
            text.replace("versionCode = 92", "versionCode = 93")
            .replace("0.62.0-dev.1", "0.63.0-dev.1")
            .replace(old_apk, new_apk)
            .replace(old_artifact, new_artifact)
        )
        if updated != text:
            path.write_text(updated, encoding="utf-8")

# 3. Regression/integration test keeps the safety engine and validates the new selection path.
write(
    "app/src/test/java/jp/co/tenposinfo/register/V063ReversalSaleLookupTest.kt",
    '''package jp.co.tenposinfo.register

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V063ReversalSaleLookupTest {
    @Test
    fun reversalUiUsesExpandedReadOnlyLookupWithoutReplacingSafetyEngine() {
        val root = File("..")
        val activity = File("src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt").readText()
        val store = File("src/main/java/jp/co/tenposinfo/register/OperationsStore.kt").readText()
        val secure = File("src/main/java/jp/co/tenposinfo/register/SecureOperationsCoordinator.kt").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()

        assertTrue(activity.contains("registerDatabase.listSales(SalesHistoryLookupPolicy.RECENT_LOAD_LIMIT)"))
        assertTrue(activity.contains("lookupSale = { saleId -> registerDatabase.loadSaleDetail(saleId)?.summary }"))
        assertTrue(activity.contains("SalesHistoryLookupPolicy.filter(sales, SalesHistoryCriteria(query = saleQuery))"))
        assertTrue(activity.contains("売上No.直接指定"))
        assertTrue(activity.contains("全量返品・取消済みです"))
        assertTrue(activity.contains("directSaleOverride"))
        assertTrue(activity.contains("secureStore.createReversal"))
        assertTrue(store.contains("PartialReturnPolicy.select"))
        assertTrue(store.contains("line_tax_snapshots"))
        assertTrue(store.contains("reversal_items"))
        assertTrue(store.contains("claimOperationKey"))
        assertTrue(secure.contains("RegisterPermission.REVERSAL"))
        assertTrue(workflow.contains("V063ReversalSaleLookupTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_v0.63.0_dev1_reversal_sale_lookup_debug.apk"))
    }
}
''',
)

write(
    "docs/V0.63_RELEASE_NOTES.md",
    '''# v0.63 Release Notes

- 返品・取消の元売上候補を直近200件から最大1,000件へ拡張。
- 売上No.・担当者・支払方法による候補検索を追加。
- 1,000件より古い売上も「売上No.直接指定」でSQLiteから参照可能にした。
- 存在しない売上No.はエラー表示し、別売上を自動選択しない。
- 全量返品・取消済み売上の再処理禁止を直接指定にも適用。
- 直接指定した古い売上も既存の部分返品、税スナップショット、値引按分、返金支払按分、責任者PIN、冪等要求ID、SQLite原子処理を使用。
- 元売上・売上明細は変更・削除せず、反対取引追記方式を維持。
''',
)

# 4. Advance cumulative CI.
workflow_path = ".github/workflows/build-apk.yml"
text = read(workflow_path)
replacements = [
    ("Verify cumulative v0.14-v0.62 sources", "Verify cumulative v0.14-v0.63 sources"),
    ("grep -q 'versionCode = 92' app/build.gradle.kts", "grep -q 'versionCode = 93' app/build.gradle.kts"),
    ("grep -q 'versionName = \"0.62.0-dev.1\"' app/build.gradle.kts", "grep -q 'versionName = \"0.63.0-dev.1\"' app/build.gradle.kts"),
    (old_apk, new_apk),
    (old_artifact, new_artifact),
    ("REGISTER_VERSION_NAME=0.62.0-dev.1", "REGISTER_VERSION_NAME=0.63.0-dev.1"),
    ("REGISTER_VERSION_CODE=92", "REGISTER_VERSION_CODE=93"),
]
for old, new in replacements:
    if old not in text:
        raise RuntimeError(f"workflow replacement missing: {old}")
    text = text.replace(old, new)
text = text.replace(
    "          test -s app/src/test/java/jp/co/tenposinfo/register/V062SalesHistoryLookupTest.kt\n",
    "          test -s app/src/test/java/jp/co/tenposinfo/register/V062SalesHistoryLookupTest.kt\n"
    "          test -s app/src/test/java/jp/co/tenposinfo/register/V063ReversalSaleLookupTest.kt\n",
)
text = text.replace(
    "          test -s docs/V0.62_RELEASE_NOTES.md\n",
    "          test -s docs/V0.62_RELEASE_NOTES.md\n"
    "          test -s docs/V0.63_REVERSAL_SALE_LOOKUP.md\n"
    "          test -s docs/V0.63_RELEASE_NOTES.md\n",
)
anchor = "          grep -q 'SalesHistoryLookupPolicy.includeRequestedSale' app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherActivity.kt\n"
if anchor not in text:
    raise RuntimeError("workflow v0.62 source-check anchor missing")
text = text.replace(
    anchor,
    anchor
    + "          grep -q 'registerDatabase.listSales(SalesHistoryLookupPolicy.RECENT_LOAD_LIMIT)' app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt\n"
    + "          grep -q '売上No.直接指定' app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt\n"
    + "          grep -q '全量返品・取消済みです' app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt\n",
)
summary_anchor = "          RECEIPT_VOUCHER_OLD_SALE_CONTEXT_LOOKUP=true\n"
if summary_anchor not in text:
    raise RuntimeError("workflow v0.62 summary anchor missing")
text = text.replace(
    summary_anchor,
    summary_anchor
    + "          REVERSAL_SALE_SEARCH=true\n"
    + "          REVERSAL_SALE_RECENT_LOAD_LIMIT=1000\n"
    + "          REVERSAL_SALE_DIRECT_ID_LOOKUP=true\n"
    + "          REVERSAL_SAFETY_ENGINE_UNCHANGED=true\n",
)
real_anchor = "          REAL_DEVICE_SALES_HISTORY_LOOKUP_VERIFICATION=required\n"
if real_anchor not in text:
    raise RuntimeError("workflow v0.62 real-device anchor missing")
text = text.replace(
    real_anchor,
    "          REAL_DEVICE_REVERSAL_SALE_LOOKUP_VERIFICATION=required\n" + real_anchor,
)
write(workflow_path, text)

# Remove generation-only files from final commit.
for relative in ["tools/v063_apply.py", ".github/workflows/v063-apply.yml"]:
    p = ROOT / relative
    if p.exists():
        p.unlink()

print("v0.63 patch applied")
