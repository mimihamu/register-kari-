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


# 1. SCR-400 loads a wider recent window and supports exact sale-ID lookup outside that window.
replace(
    "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt",
    '''            AppScreen.SALES_HISTORY -> SalesHistoryScreen(
                sales = database.listSales(),
                onOpen = {
                    selectedSaleId = it.id
                    screen = AppScreen.SALE_DETAIL
                },
                onQueue = { openUnifiedPrintQueue() },
                onBack = { screen = AppScreen.SALES },
            )''',
    '''            AppScreen.SALES_HISTORY -> SalesHistoryScreen(
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
            )''',
)

old_screen = '''@Composable
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
'''
new_screen = '''@Composable
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

    Column(Modifier.fillMaxSize()) {
        Header("SCR-400", "売上一覧・検索")
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
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
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "表示 ${visibleSales.size}件 / 読込 ${sales.size}件（直近最大${SalesHistoryLookupPolicy.RECENT_LOAD_LIMIT}件）",
                    fontWeight = FontWeight.Bold,
                    color = Navy,
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
                    modifier = Modifier.width(210.dp),
                )
                Button(
                    onClick = {
                        val saleId = directSaleId ?: return@Button
                        if (!onDirectLookup(saleId)) {
                            lookupMessage = "売上No.$saleId は見つかりません"
                        }
                    },
                    enabled = directSaleId != null,
                    colors = ButtonDefaults.buttonColors(containerColor = Blue),
                ) { Text("表示") }
            }
            if (amountRangeInvalid) {
                Text("金額範囲は『以上 ≤ 以下』になるよう入力してください", color = Danger, fontWeight = FontWeight.Bold)
            } else if (!lookupMessage.isNullOrBlank()) {
                Text(lookupMessage.orEmpty(), color = Danger, fontWeight = FontWeight.Bold)
            }
        }
        CardPanel(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp)) {
            when {
                sales.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("売上はまだありません", color = Color.Gray, fontSize = 22.sp)
                    }
                }
                visibleSales.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("条件に一致する売上はありません", color = Color.Gray, fontSize = 22.sp)
                    }
                }
                else -> {
                    LazyColumn {
                        itemsIndexed(visibleSales) { _, sale ->
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
        BottomActions(onBack, "販売へ戻る", onBack)
    }
}
'''
replace("app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt", old_screen, new_screen)

# 2. Receipt voucher deep-link resolves the exact requested sale, even if outside the recent list.
replace(
    "app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherActivity.kt",
    '''        val sales = remember(refreshEpoch) { database.listSales() }
        val saleContext = ReceiptVoucherNavigation.resolveSaleContext(
            requestedSaleId = requestedSaleId,
            availableSaleIds = sales.map { it.id },
        )''',
    '''        val recentSales = remember(refreshEpoch) {
            database.listSales(SalesHistoryLookupPolicy.RECENT_LOAD_LIMIT)
        }
        val requestedSale = remember(refreshEpoch, requestedSaleId) {
            requestedSaleId?.let { database.loadSaleDetail(it)?.summary }
        }
        val sales = remember(recentSales, requestedSale) {
            SalesHistoryLookupPolicy.includeRequestedSale(recentSales, requestedSale)
        }
        val saleContext = ReceiptVoucherNavigation.resolveSaleContext(
            requestedSaleId = requestedSaleId,
            availableSaleIds = sales.map { it.id },
        )''',
)

# 3. Version advance.
replace("app/build.gradle.kts", "versionCode = 91", "versionCode = 92")
replace("app/build.gradle.kts", 'versionName = "0.61.0-dev.1"', 'versionName = "0.62.0-dev.1"')

old_apk = "TSUGUREGI_v0.61.0_dev1_sale_context_receipt_voucher_navigation_debug.apk"
new_apk = "TSUGUREGI_v0.62.0_dev1_sales_history_lookup_debug.apk"
old_artifact = "TSUGUREGI-v0.61.0-dev1-sale-context-receipt-voucher-navigation-apks"
new_artifact = "TSUGUREGI-v0.62.0-dev1-sales-history-lookup-apks"
for root in [ROOT / "app/src/test", ROOT / "management-app/src/test"]:
    for path in root.rglob("*.kt"):
        text = path.read_text(encoding="utf-8")
        updated = (
            text.replace("versionCode = 91", "versionCode = 92")
            .replace("0.61.0-dev.1", "0.62.0-dev.1")
            .replace(old_apk, new_apk)
            .replace(old_artifact, new_artifact)
        )
        if updated != text:
            path.write_text(updated, encoding="utf-8")

# 4. v0.62 tests.
write(
    "app/src/test/java/jp/co/tenposinfo/register/V062SalesHistoryLookupTest.kt",
    '''package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V062SalesHistoryLookupTest {
    private fun sale(
        id: Long,
        operator: String,
        payment: String,
        amount: Long,
    ) = SaleSummaryRecord(
        id = id,
        operatorName = operator,
        paymentLabel = payment,
        totalAmount = amount,
        taxAmount = 0L,
        changeAmount = 0L,
        createdAt = id,
        printCount = 0,
    )

    @Test
    fun filtersBySaleOperatorPaymentAndAmountRange() {
        val sales = listOf(
            sale(123, "山田", "現金", 4_000),
            sale(124, "佐藤", "カード", 8_000),
            sale(125, "山田", "カード", 12_000),
        )
        assertEquals(listOf(123L), SalesHistoryLookupPolicy.filter(sales, SalesHistoryCriteria(query = "#123")).map { it.id })
        assertEquals(listOf(123L, 125L), SalesHistoryLookupPolicy.filter(sales, SalesHistoryCriteria(query = "山田")).map { it.id })
        assertEquals(listOf(124L, 125L), SalesHistoryLookupPolicy.filter(sales, SalesHistoryCriteria(query = "カード")).map { it.id })
        assertEquals(listOf(124L), SalesHistoryLookupPolicy.filter(sales, SalesHistoryCriteria(minAmount = 5_000, maxAmount = 10_000)).map { it.id })
        assertTrue(SalesHistoryLookupPolicy.filter(sales, SalesHistoryCriteria(minAmount = 20_000, maxAmount = 10_000)).isEmpty())
    }

    @Test
    fun parsesDirectSaleNumberSafely() {
        assertEquals(123L, SalesHistoryLookupPolicy.parseDirectSaleId("#123"))
        assertEquals(456L, SalesHistoryLookupPolicy.parseDirectSaleId(" 456 "))
        assertNull(SalesHistoryLookupPolicy.parseDirectSaleId("0"))
        assertNull(SalesHistoryLookupPolicy.parseDirectSaleId("12A"))
        assertNull(SalesHistoryLookupPolicy.parseDirectSaleId(""))
    }

    @Test
    fun requestedOldSaleIsAddedOnceAheadOfRecentWindow() {
        val recent = listOf(sale(300, "A", "現金", 1_000), sale(299, "B", "現金", 1_000))
        val old = sale(10, "C", "カード", 2_000)
        assertEquals(listOf(10L, 300L, 299L), SalesHistoryLookupPolicy.includeRequestedSale(recent, old).map { it.id })
        assertEquals(listOf(300L, 299L), SalesHistoryLookupPolicy.includeRequestedSale(recent, recent.first()).map { it.id })
    }

    @Test
    fun sourceConnectsDirectLookupAndOldReceiptVoucherSaleResolution() {
        val root = File("..")
        val main = File("src/main/java/jp/co/tenposinfo/register/MainActivity.kt").readText()
        val voucher = File("src/main/java/jp/co/tenposinfo/register/ReceiptVoucherActivity.kt").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()

        assertTrue(main.contains("database.listSales(SalesHistoryLookupPolicy.RECENT_LOAD_LIMIT)"))
        assertTrue(main.contains("val detail = database.loadSaleDetail(saleId)"))
        assertTrue(main.contains("売上No.直接表示"))
        assertTrue(main.contains("金額以上"))
        assertTrue(main.contains("条件に一致する売上はありません"))
        assertTrue(voucher.contains("database.loadSaleDetail(it)?.summary"))
        assertTrue(voucher.contains("SalesHistoryLookupPolicy.includeRequestedSale"))
        assertTrue(workflow.contains("V062SalesHistoryLookupTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_v0.62.0_dev1_sales_history_lookup_debug.apk"))
    }
}
''',
)

write(
    "docs/V0.62_SALES_HISTORY_LOOKUP.md",
    '''# v0.62 売上検索・過去売上安全参照

## 目的

日常運用で売上一覧が増えた後も、レシートや領収書の問い合わせ対象を売上No.から安全に特定できるようにする。

## SCR-400 売上一覧・検索

- 直近一覧の読込上限を200件から1,000件へ拡張する。
- 売上No.、担当者、支払方法の文字検索を行う。
- 合計金額の下限／上限で絞り込む。
- 金額下限が上限を超える条件は結果0件として扱い、画面へ入力誤りを表示する。
- 一覧に含まれない古い売上も「売上No.直接表示」からSQLiteへ直接照会できる。
- 直接照会で存在しない売上No.は画面上で明示し、別売上へ自動移動しない。
- 売上データは検索・絞り込みで更新・削除しない。

## 領収書との連携

v0.61では売上No.を画面間で引き継いだが、領収書発行画面の候補一覧が直近件数に限定されると古い売上No.を固定できない可能性があった。

v0.62では指定売上No.をSQLiteへ直接照会し、直近1,000件に含まれない場合だけ候補一覧の先頭へ補完する。同一売上を重複追加しない。

これにより、運用台帳に残る古い領収書から「この売上で追加発行」を開いた場合も、存在する元売上を別売上へフォールバックさせない。

## 維持する安全条件

- 売上SQLiteを正本として維持する。
- 売上一覧検索で売上・印刷・領収書履歴を変更しない。
- 領収書の売上額超過拒否、発行済み額、UUID二重発行防止、二段階確認を維持する。
- 印刷復旧は統合印刷キューへ一本化する。
- `VIEW_SALES`権限制御を維持する。

## 実機確認が必要な項目

- 売上1,000件相当での一覧スクロール性能
- 売上No.・担当・支払・金額条件のタッチ操作
- 1,000件より古い売上No.の直接表示
- 古い売上詳細→領収書発行の対象固定
- ソフトウェアキーボード表示時の操作到達性
- v0.61→v0.62上書き更新
''',
)
write(
    "docs/V0.62_RELEASE_NOTES.md",
    '''# v0.62 Release Notes

- SCR-400を「売上一覧・検索」へ拡張。
- 直近売上の読込上限を1,000件へ拡張。
- 売上No.・担当者・支払方法の検索を追加。
- 合計金額の下限／上限絞り込みを追加。
- 一覧上限外でも売上No.をSQLiteへ直接照会して詳細を開ける機能を追加。
- 存在しない売上No.では別売上へ移動しない。
- v0.61の売上指定領収書で、直近一覧外の古い元売上もSQLiteから直接解決するよう強化。
- 売上・領収書・印刷・同期データを検索処理で削除・更新しない。
''',
)

# 5. Advance cumulative workflow.
workflow_path = ".github/workflows/build-apk.yml"
text = read(workflow_path)
replacements = [
    ("Verify cumulative v0.14-v0.61 sources", "Verify cumulative v0.14-v0.62 sources"),
    ("grep -q 'versionCode = 91' app/build.gradle.kts", "grep -q 'versionCode = 92' app/build.gradle.kts"),
    ("grep -q 'versionName = \"0.61.0-dev.1\"' app/build.gradle.kts", "grep -q 'versionName = \"0.62.0-dev.1\"' app/build.gradle.kts"),
    (old_apk, new_apk),
    (old_artifact, new_artifact),
    ("REGISTER_VERSION_NAME=0.61.0-dev.1", "REGISTER_VERSION_NAME=0.62.0-dev.1"),
    ("REGISTER_VERSION_CODE=91", "REGISTER_VERSION_CODE=92"),
]
for old, new in replacements:
    if old not in text:
        raise RuntimeError(f"workflow replacement missing: {old}")
    text = text.replace(old, new)
text = text.replace(
    "          test -s app/src/test/java/jp/co/tenposinfo/register/V061ReceiptVoucherSaleContextNavigationTest.kt\n",
    "          test -s app/src/test/java/jp/co/tenposinfo/register/V061ReceiptVoucherSaleContextNavigationTest.kt\n"
    "          test -s app/src/test/java/jp/co/tenposinfo/register/V062SalesHistoryLookupTest.kt\n",
)
text = text.replace(
    "          test -s docs/V0.61_RELEASE_NOTES.md\n",
    "          test -s docs/V0.61_RELEASE_NOTES.md\n"
    "          test -s docs/V0.62_SALES_HISTORY_LOOKUP.md\n"
    "          test -s docs/V0.62_RELEASE_NOTES.md\n",
)
anchor = "          grep -q 'ReceiptVoucherNavigation.issuanceIntent(context, detail.summary.id)' app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt\n"
if anchor not in text:
    raise RuntimeError("workflow v0.61 source-check anchor missing")
text = text.replace(
    anchor,
    anchor
    + "          test -s app/src/main/java/jp/co/tenposinfo/register/SalesHistoryLookup.kt\n"
    + "          grep -q 'SalesHistoryLookupPolicy.RECENT_LOAD_LIMIT' app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt\n"
    + "          grep -q '売上No.直接表示' app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt\n"
    + "          grep -q 'SalesHistoryLookupPolicy.includeRequestedSale' app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherActivity.kt\n",
)
summary_anchor = "          RECEIPT_VOUCHER_COMPLETE_SCREEN_ENTRY=true\n"
if summary_anchor not in text:
    raise RuntimeError("workflow summary anchor missing")
text = text.replace(
    summary_anchor,
    summary_anchor
    + "          SALES_HISTORY_SEARCH=true\n"
    + "          SALES_HISTORY_RECENT_LOAD_LIMIT=1000\n"
    + "          SALES_HISTORY_DIRECT_ID_LOOKUP=true\n"
    + "          RECEIPT_VOUCHER_OLD_SALE_CONTEXT_LOOKUP=true\n",
)
real_anchor = "          REAL_DEVICE_RECEIPT_VOUCHER_SALE_CONTEXT_VERIFICATION=required\n"
if real_anchor not in text:
    raise RuntimeError("workflow real-device anchor missing")
text = text.replace(
    real_anchor,
    "          REAL_DEVICE_SALES_HISTORY_LOOKUP_VERIFICATION=required\n" + real_anchor,
)
write(workflow_path, text)

# 6. Remove generation-only files from the final generated commit.
for relative in ["tools/v062_apply.py", ".github/workflows/v062-apply.yml"]:
    p = ROOT / relative
    if p.exists():
        p.unlink()

print("v0.62 patch applied")
