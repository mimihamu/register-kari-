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
        raise RuntimeError(f"{path}: replacement source missing ({actual} < {count}): {old[:120]!r}")
    text = text.replace(old, new, count)
    write(path, text)


# 1. Navigation contract: keep Android Intent wiring centralized and add pure sale-context resolution.
write(
    "app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherNavigation.kt",
    '''package jp.co.tenposinfo.register

import android.content.Context
import android.content.Intent

internal data class ReceiptVoucherSaleContext(
    val requestedSaleId: Long?,
    val selectedSaleId: Long?,
    val selectionLocked: Boolean,
    val requestedSaleUnavailable: Boolean,
)

internal object ReceiptVoucherNavigation {
    const val EXTRA_SALE_ID = "jp.co.tenposinfo.register.extra.RECEIPT_VOUCHER_SALE_ID"

    fun issuanceIntent(context: Context, saleId: Long? = null): Intent =
        Intent(context, ReceiptVoucherActivity::class.java).apply {
            if (saleId != null && saleId > 0L) putExtra(EXTRA_SALE_ID, saleId)
        }

    fun ledgerIntent(context: Context, saleId: Long? = null): Intent =
        Intent(context, ReceiptVoucherLedgerActivity::class.java).apply {
            if (saleId != null && saleId > 0L) putExtra(EXTRA_SALE_ID, saleId)
        }

    fun requestedSaleId(intent: Intent?): Long? {
        val value = intent?.getLongExtra(EXTRA_SALE_ID, -1L) ?: return null
        return value.takeIf { it > 0L }
    }

    fun resolveSaleContext(
        requestedSaleId: Long?,
        availableSaleIds: Collection<Long>,
    ): ReceiptVoucherSaleContext {
        val selected = when {
            requestedSaleId != null && requestedSaleId in availableSaleIds -> requestedSaleId
            else -> availableSaleIds.firstOrNull()
        }
        return ReceiptVoucherSaleContext(
            requestedSaleId = requestedSaleId,
            selectedSaleId = selected,
            selectionLocked = requestedSaleId != null && selected == requestedSaleId,
            requestedSaleUnavailable = requestedSaleId != null && requestedSaleId !in availableSaleIds,
        )
    }

    fun resolveInitialSaleId(requestedSaleId: Long?, availableSaleIds: Collection<Long>): Long? =
        resolveSaleContext(requestedSaleId, availableSaleIds).selectedSaleId
}
''',
)

# 2. Receipt voucher issuance UI accepts a sale context and locks the sale when opened from a sale-specific route.
replace(
    "app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherActivity.kt",
    '''        setContent {
            MaterialTheme {
                ReceiptVoucherRoute(onClose = { finish() })
            }
        }''',
    '''        val requestedSaleId = ReceiptVoucherNavigation.requestedSaleId(intent)
        setContent {
            MaterialTheme {
                ReceiptVoucherRoute(
                    requestedSaleId = requestedSaleId,
                    onClose = { finish() },
                )
            }
        }''',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherActivity.kt",
    '''@Composable
private fun ReceiptVoucherRoute(onClose: () -> Unit) {''',
    '''@Composable
private fun ReceiptVoucherRoute(
    requestedSaleId: Long?,
    onClose: () -> Unit,
) {''',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherActivity.kt",
    '''        val sales = remember(refreshEpoch) { database.listSales() }
        ReceiptVoucherOperationsScreen(
            sales = sales,
            operatorName = operator.name,
            voucherStore = voucherStore,
            onRefresh = { refreshEpoch++ },
            onClose = onClose,
        )''',
    '''        val sales = remember(refreshEpoch) { database.listSales() }
        val saleContext = ReceiptVoucherNavigation.resolveSaleContext(
            requestedSaleId = requestedSaleId,
            availableSaleIds = sales.map { it.id },
        )
        ReceiptVoucherOperationsScreen(
            sales = sales,
            operatorName = operator.name,
            voucherStore = voucherStore,
            initialSaleId = saleContext.selectedSaleId,
            lockedSaleId = saleContext.selectedSaleId.takeIf { saleContext.selectionLocked },
            requestedSaleUnavailable = saleContext.requestedSaleUnavailable,
            onRefresh = { refreshEpoch++ },
            onClose = onClose,
        )''',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherActivity.kt",
    '''private fun ReceiptVoucherOperationsScreen(
    sales: List<SaleSummaryRecord>,
    operatorName: String,
    voucherStore: ReceiptVoucherStore,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var selectedSaleId by remember { mutableStateOf<Long?>(sales.firstOrNull()?.id) }''',
    '''private fun ReceiptVoucherOperationsScreen(
    sales: List<SaleSummaryRecord>,
    operatorName: String,
    voucherStore: ReceiptVoucherStore,
    initialSaleId: Long?,
    lockedSaleId: Long?,
    requestedSaleUnavailable: Boolean,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var selectedSaleId by remember(initialSaleId) { mutableStateOf<Long?>(initialSaleId) }''',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherActivity.kt",
    '''                    Text("売上を選択", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = VoucherNavy)
                    Spacer(Modifier.height(8.dp))
                    if (sales.isEmpty()) {''',
    '''                    Text("売上を選択", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = VoucherNavy)
                    Spacer(Modifier.height(6.dp))
                    if (lockedSaleId != null) {
                        Text(
                            "売上No.$lockedSaleId から開いています。対象売上は固定されています。",
                            color = VoucherBlue,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(6.dp))
                    } else if (requestedSaleUnavailable) {
                        Text(
                            "指定された売上が見つからないため、売上一覧から選択してください。",
                            color = VoucherDanger,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    if (sales.isEmpty()) {''',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherActivity.kt",
    '''                                Card(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        selectedSaleId = sale.id
                                        unitAmountText = ""
                                        copiesText = "1"
                                        requestId = UUID.randomUUID().toString()
                                        issueConfirmation = false
                                        reprintConfirmationId = null
                                        message = null
                                    },''',
    '''                                val saleSelectionEnabled = lockedSaleId == null || lockedSaleId == sale.id
                                Card(
                                    modifier = Modifier.fillMaxWidth().clickable(
                                        enabled = saleSelectionEnabled,
                                        onClick = {
                                            selectedSaleId = sale.id
                                            unitAmountText = ""
                                            copiesText = "1"
                                            requestId = UUID.randomUUID().toString()
                                            issueConfirmation = false
                                            reprintConfirmationId = null
                                            message = null
                                        },
                                    ),''',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherActivity.kt",
    '''                        Text("領収書発行", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = VoucherNavy)
                        Spacer(Modifier.height(8.dp))
                        VoucherAmountRow("売上合計", selectedSale?.let { voucherYen(it.totalAmount) } ?: "-")''',
    '''                        Text("領収書発行", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = VoucherNavy)
                        selectedSale?.let {
                            Text(
                                "対象 売上No.${it.id}${if (lockedSaleId == it.id) "（固定）" else ""}",
                                color = if (lockedSaleId == it.id) VoucherBlue else Color.DarkGray,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        VoucherAmountRow("売上合計", selectedSale?.let { voucherYen(it.totalAmount) } ?: "-")''',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherActivity.kt",
    '''            OutlinedButton(
                onClick = { context.startActivity(Intent(context, ReceiptVoucherLedgerActivity::class.java)) },
                modifier = Modifier.heightIn(min = 46.dp),
            ) { Text("運用台帳・印刷状態") }''',
    '''            OutlinedButton(
                onClick = { context.startActivity(ReceiptVoucherNavigation.ledgerIntent(context, selectedSaleId)) },
                modifier = Modifier.heightIn(min = 46.dp),
            ) { Text("運用台帳・印刷状態") }''',
)

# Preserve the v0.60 integration check while validating the new navigation abstraction.
replace(
    "app/src/test/java/jp/co/tenposinfo/register/V060ReceiptVoucherOperationsLedgerTest.kt",
    'assertTrue(issuance.contains("ReceiptVoucherLedgerActivity::class.java"))',
    'assertTrue(issuance.contains("ReceiptVoucherNavigation.ledgerIntent"))',
)

# 3. Ledger accepts sale context and can return to issuance for the selected sale.
replace(
    "app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherLedgerActivity.kt",
    '''        setContent {
            MaterialTheme {
                ReceiptVoucherLedgerRoute(
                    onOpenPrintQueue = { startActivity(Intent(this, UnifiedPrintQueueActivity::class.java)) },
                    onOpenIssuance = { startActivity(Intent(this, ReceiptVoucherActivity::class.java)) },
                    onClose = { finish() },
                )
            }
        }''',
    '''        val requestedSaleId = ReceiptVoucherNavigation.requestedSaleId(intent)
        setContent {
            MaterialTheme {
                ReceiptVoucherLedgerRoute(
                    requestedSaleId = requestedSaleId,
                    onOpenPrintQueue = { startActivity(Intent(this, UnifiedPrintQueueActivity::class.java)) },
                    onOpenIssuance = { saleId -> startActivity(ReceiptVoucherNavigation.issuanceIntent(this, saleId)) },
                    onClose = { finish() },
                )
            }
        }''',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherLedgerActivity.kt",
    '''private fun ReceiptVoucherLedgerRoute(
    onOpenPrintQueue: () -> Unit,
    onOpenIssuance: () -> Unit,
    onClose: () -> Unit,
) {''',
    '''private fun ReceiptVoucherLedgerRoute(
    requestedSaleId: Long?,
    onOpenPrintQueue: () -> Unit,
    onOpenIssuance: (Long?) -> Unit,
    onClose: () -> Unit,
) {''',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherLedgerActivity.kt",
    '''        val allEntries = remember(revision) { store.listLedger() }
        val entries = remember(allEntries, criteria) { ReceiptVoucherLedgerPolicy.filter(allEntries, criteria) }
        val summary = remember(allEntries) { ReceiptVoucherLedgerSummary.from(allEntries) }
        val selected = allEntries.firstOrNull { it.receipt.id == selectedId }

        Column(Modifier.fillMaxSize()) {''',
    '''        val allEntries = remember(revision) { store.listLedger() }
        val entries = remember(allEntries, criteria) { ReceiptVoucherLedgerPolicy.filter(allEntries, criteria) }
        val summary = remember(allEntries) { ReceiptVoucherLedgerSummary.from(allEntries) }
        val contextSelectedId = requestedSaleId?.let { saleId ->
            allEntries.firstOrNull { it.receipt.saleId == saleId }?.receipt?.id
        }
        val activeSelectedId = selectedId ?: contextSelectedId
        val selected = allEntries.firstOrNull { it.receipt.id == activeSelectedId }

        Column(Modifier.fillMaxSize()) {''',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherLedgerActivity.kt",
    '''            ReceiptVoucherLedgerFilters(
                criteria = criteria,
                onCriteriaChange = {
                    criteria = it
                    if (selectedId != null && allEntries.none { entry -> entry.receipt.id == selectedId }) selectedId = null
                },
                onRefresh = { revision++ },
            )''',
    '''            ReceiptVoucherLedgerFilters(
                criteria = criteria,
                onCriteriaChange = {
                    criteria = it
                    if (selectedId != null && allEntries.none { entry -> entry.receipt.id == selectedId }) selectedId = null
                },
                onRefresh = { revision++ },
            )
            if (requestedSaleId != null) {
                Text(
                    "売上No.$requestedSaleId の領収書コンテキストで開いています。台帳の他売上も確認できます。",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    color = LedgerBlue,
                    fontWeight = FontWeight.Bold,
                )
            }''',
)
# two list call sites use selectedId = selectedId
text = read("app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherLedgerActivity.kt")
if text.count("selectedId = selectedId,") < 2:
    raise RuntimeError("ReceiptVoucherLedgerActivity.kt: expected two selectedId list arguments")
text = text.replace("selectedId = selectedId,", "selectedId = activeSelectedId,", 2)
write("app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherLedgerActivity.kt", text)
# two detail call sites add issuance callback
text = read("app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherLedgerActivity.kt")
needle = '''                            entry = selected,
                            onOpenPrintQueue = onOpenPrintQueue,
                        )'''
replacement = '''                            entry = selected,
                            onOpenPrintQueue = onOpenPrintQueue,
                            onOpenIssuance = { saleId -> onOpenIssuance(saleId) },
                        )'''
if text.count(needle) != 2:
    raise RuntimeError(f"ReceiptVoucherLedgerActivity.kt: expected two detail calls, found {text.count(needle)}")
text = text.replace(needle, replacement, 2)
write("app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherLedgerActivity.kt", text)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherLedgerActivity.kt",
    '''            ReceiptVoucherLedgerFooter(
                onOpenIssuance = onOpenIssuance,
                onOpenPrintQueue = onOpenPrintQueue,
                onClose = onClose,
            )''',
    '''            ReceiptVoucherLedgerFooter(
                onOpenIssuance = { onOpenIssuance(requestedSaleId) },
                onOpenPrintQueue = onOpenPrintQueue,
                onClose = onClose,
            )''',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherLedgerActivity.kt",
    '''private fun ReceiptVoucherLedgerDetail(
    modifier: Modifier,
    entry: ReceiptVoucherLedgerEntry?,
    onOpenPrintQueue: () -> Unit,
) {''',
    '''private fun ReceiptVoucherLedgerDetail(
    modifier: Modifier,
    entry: ReceiptVoucherLedgerEntry?,
    onOpenPrintQueue: () -> Unit,
    onOpenIssuance: (Long) -> Unit,
) {''',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherLedgerActivity.kt",
    '''            Button(
                onClick = onOpenPrintQueue,
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = LedgerBlue),
            ) {
                Text("統合印刷キューで確認・対応", fontWeight = FontWeight.Bold)
            }''',
    '''            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onOpenIssuance(entry.receipt.saleId) },
                    modifier = Modifier.weight(1f).heightIn(min = 50.dp),
                ) {
                    Text("この売上で追加発行", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onOpenPrintQueue,
                    modifier = Modifier.weight(1f).heightIn(min = 50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LedgerBlue),
                ) {
                    Text("統合印刷キューで確認・対応", fontWeight = FontWeight.Bold)
                }
            }''',
)

# 4. POS sale detail and completion routes open the voucher screen with the exact sale ID.
replace(
    "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt",
    '''            AppScreen.COMPLETE -> CompleteScreen(
                detail = lastSaleId?.let { database.loadSaleDetail(it) },
                onReceipt = {
                    selectedSaleId = lastSaleId
                    screen = AppScreen.RECEIPT_PREVIEW
                },
                onHistory = { screen = AppScreen.SALES_HISTORY },
                onQueue = { openUnifiedPrintQueue() },
                onNext = { screen = AppScreen.SALES },
            )''',
    '''            AppScreen.COMPLETE -> CompleteScreen(
                detail = lastSaleId?.let { database.loadSaleDetail(it) },
                onReceipt = {
                    selectedSaleId = lastSaleId
                    screen = AppScreen.RECEIPT_PREVIEW
                },
                onVoucher = {
                    context.startActivity(ReceiptVoucherNavigation.issuanceIntent(context, lastSaleId))
                },
                onHistory = { screen = AppScreen.SALES_HISTORY },
                onQueue = { openUnifiedPrintQueue() },
                onNext = { screen = AppScreen.SALES },
            )''',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt",
    '''                    SaleDetailScreen(
                        detail = detail,
                        onReceipt = { screen = AppScreen.RECEIPT_PREVIEW },
                        onBack = { screen = AppScreen.SALES_HISTORY },
                    )''',
    '''                    SaleDetailScreen(
                        detail = detail,
                        onReceipt = { screen = AppScreen.RECEIPT_PREVIEW },
                        onVoucher = {
                            context.startActivity(ReceiptVoucherNavigation.issuanceIntent(context, detail.summary.id))
                        },
                        onBack = { screen = AppScreen.SALES_HISTORY },
                    )''',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt",
    '''private fun CompleteScreen(
    detail: SaleDetailRecord?,
    onReceipt: () -> Unit,
    onHistory: () -> Unit,
    onQueue: () -> Unit,
    onNext: () -> Unit,
) {''',
    '''private fun CompleteScreen(
    detail: SaleDetailRecord?,
    onReceipt: () -> Unit,
    onVoucher: () -> Unit,
    onHistory: () -> Unit,
    onQueue: () -> Unit,
    onNext: () -> Unit,
) {''',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt",
    '''                OutlinedButton(onClick = onReceipt, enabled = detail != null) { Text("レシート確認") }
                OutlinedButton(onClick = onQueue) { Text("統合印刷キュー") }''',
    '''                OutlinedButton(onClick = onReceipt, enabled = detail != null) { Text("レシート確認") }
                OutlinedButton(onClick = onVoucher, enabled = detail != null) { Text("領収書発行") }
                OutlinedButton(onClick = onQueue) { Text("統合印刷キュー") }''',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt",
    '''private fun SaleDetailScreen(
    detail: SaleDetailRecord,
    onReceipt: () -> Unit,
    onBack: () -> Unit,
) {''',
    '''private fun SaleDetailScreen(
    detail: SaleDetailRecord,
    onReceipt: () -> Unit,
    onVoucher: () -> Unit,
    onBack: () -> Unit,
) {''',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt",
    '''                BlueButton("レシート／再印字", onReceipt, Modifier.fillMaxWidth().height(52.dp))''',
    '''                BlueButton("レシート／再印字", onReceipt, Modifier.fillMaxWidth().height(52.dp))
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onVoucher, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("この売上で領収書発行")
                }''',
)

# 5. Version and cumulative current-version assertions.
replace("app/build.gradle.kts", "versionCode = 90", "versionCode = 91")
replace("app/build.gradle.kts", 'versionName = "0.60.0-dev.1"', 'versionName = "0.61.0-dev.1"')

old_apk = "TSUGUREGI_v0.60.0_dev1_receipt_voucher_operations_ledger_debug.apk"
new_apk = "TSUGUREGI_v0.61.0_dev1_sale_context_receipt_voucher_navigation_debug.apk"
old_artifact = "TSUGUREGI-v0.60.0-dev1-receipt-voucher-operations-ledger-apks"
new_artifact = "TSUGUREGI-v0.61.0-dev1-sale-context-receipt-voucher-navigation-apks"
for root in [ROOT / "app/src/test", ROOT / "management-app/src/test"]:
    for path in root.rglob("*.kt"):
        text = path.read_text(encoding="utf-8")
        updated = (
            text.replace("versionCode = 90", "versionCode = 91")
            .replace("0.60.0-dev.1", "0.61.0-dev.1")
            .replace(old_apk, new_apk)
            .replace(old_artifact, new_artifact)
        )
        if updated != text:
            path.write_text(updated, encoding="utf-8")

# 6. New v0.61 tests and documentation.
write(
    "app/src/test/java/jp/co/tenposinfo/register/V061ReceiptVoucherSaleContextNavigationTest.kt",
    '''package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V061ReceiptVoucherSaleContextNavigationTest {
    @Test
    fun requestedExistingSaleIsSelectedAndLocked() {
        val context = ReceiptVoucherNavigation.resolveSaleContext(20L, listOf(30L, 20L, 10L))
        assertEquals(20L, context.selectedSaleId)
        assertTrue(context.selectionLocked)
        assertFalse(context.requestedSaleUnavailable)
    }

    @Test
    fun unavailableRequestedSaleFallsBackWithoutLock() {
        val context = ReceiptVoucherNavigation.resolveSaleContext(99L, listOf(30L, 20L))
        assertEquals(30L, context.selectedSaleId)
        assertFalse(context.selectionLocked)
        assertTrue(context.requestedSaleUnavailable)
    }

    @Test
    fun emptySalesReturnsNoSelection() {
        val context = ReceiptVoucherNavigation.resolveSaleContext(null, emptyList())
        assertNull(context.selectedSaleId)
        assertFalse(context.selectionLocked)
        assertFalse(context.requestedSaleUnavailable)
    }

    @Test
    fun sourceConnectsCompletionSaleDetailIssuanceAndLedgerWithoutChangingSafetyRules() {
        val root = File("..")
        val main = File("src/main/java/jp/co/tenposinfo/register/MainActivity.kt").readText()
        val issuance = File("src/main/java/jp/co/tenposinfo/register/ReceiptVoucherActivity.kt").readText()
        val ledger = File("src/main/java/jp/co/tenposinfo/register/ReceiptVoucherLedgerActivity.kt").readText()
        val navigation = File("src/main/java/jp/co/tenposinfo/register/ReceiptVoucherNavigation.kt").readText()
        val voucher = File("src/main/java/jp/co/tenposinfo/register/ReceiptVoucher.kt").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()

        assertTrue(main.contains("ReceiptVoucherNavigation.issuanceIntent(context, lastSaleId)"))
        assertTrue(main.contains("ReceiptVoucherNavigation.issuanceIntent(context, detail.summary.id)"))
        assertTrue(main.contains("この売上で領収書発行"))
        assertTrue(issuance.contains("対象売上は固定されています"))
        assertTrue(issuance.contains("lockedSaleId"))
        assertTrue(issuance.contains("ReceiptVoucherNavigation.ledgerIntent(context, selectedSaleId)"))
        assertTrue(ledger.contains("この売上で追加発行"))
        assertTrue(ledger.contains("ReceiptVoucherNavigation.issuanceIntent"))
        assertTrue(navigation.contains("requestedSaleUnavailable"))
        assertTrue(voucher.contains("requestId"))
        assertTrue(voucher.contains("remainingAmount"))
        assertTrue(workflow.contains("V061ReceiptVoucherSaleContextNavigationTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_v0.61.0_dev1_sale_context_receipt_voucher_navigation_debug.apk"))
    }
}
''',
)
write(
    "docs/V0.61_RECEIPT_VOUCHER_SALE_CONTEXT_NAVIGATION.md",
    '''# v0.61 売上指定領収書導線

## 目的

v0.58〜v0.60で整備した領収書発行・再発行・運用台帳を、元売上の確認画面から安全に直接利用できるようにする。

## 実装

- 会計完了画面から、確定した売上No.を指定して領収書発行画面を開く。
- 売上詳細画面から、表示中の売上No.を指定して領収書発行画面を開く。
- 売上No.指定で開いた領収書画面では対象売上を固定し、別売上へのクリック切替を無効化する。
- 指定売上が削除等で見つからない場合は固定せず、既存の売上一覧へ安全にフォールバックして警告する。
- 領収書画面から運用台帳を開く際も、現在の対象売上No.を引き継ぐ。
- 台帳から「この売上で追加発行」を選ぶと、その売上No.を固定して領収書発行画面へ戻る。
- Intent extraのキーと解決ルールは `ReceiptVoucherNavigation` に集約する。

## 既存安全要件

- 一部領収の発行済み額／残額、売上額超過拒否、UUID要求IDによる二重実行防止はv0.58の既存実装を継続利用する。
- 発行・再発行の二段階確認を維持する。
- 領収書履歴・再発行履歴を削除しない。
- 印刷障害対応は統合印刷キューへ一本化したままとする。
- `VIEW_SALES`権限チェックは変更しない。

## 実機確認が必要な項目

- 横画面タブレットでの会計完了→領収書発行導線
- 売上詳細→対象売上固定表示
- 領収書→台帳→同売上追加発行の往復操作
- 58mm / 80mm実印刷
- v0.60→v0.61上書き更新
''',
)
write(
    "docs/V0.61_RELEASE_NOTES.md",
    '''# v0.61 Release Notes

- 売上詳細から対象売上を固定して領収書発行へ進める導線を追加。
- 会計完了直後から確定売上No.を引き継いで領収書発行へ進める導線を追加。
- 領収書画面の対象売上固定表示と誤選択防止を追加。
- 領収書画面から運用台帳へ売上No.を引き継ぐよう変更。
- 運用台帳から選択領収書と同じ元売上へ追加発行できる導線を追加。
- 指定売上が存在しない場合は売上一覧へ安全にフォールバック。
- v0.58〜v0.60の残額制御、二重実行防止、二段階確認、追記専用履歴、統合印刷キュー運用を維持。
''',
)

# 7. Workflow advances cumulatively to v0.61.
workflow_path = ".github/workflows/build-apk.yml"
text = read(workflow_path)
replacements = [
    ("Verify cumulative v0.14-v0.60 sources", "Verify cumulative v0.14-v0.61 sources"),
    ("grep -q 'versionCode = 90' app/build.gradle.kts", "grep -q 'versionCode = 91' app/build.gradle.kts"),
    ("grep -q 'versionName = \"0.60.0-dev.1\"' app/build.gradle.kts", "grep -q 'versionName = \"0.61.0-dev.1\"' app/build.gradle.kts"),
    (old_apk, new_apk),
    (old_artifact, new_artifact),
    ("REGISTER_VERSION_NAME=0.60.0-dev.1", "REGISTER_VERSION_NAME=0.61.0-dev.1"),
    ("REGISTER_VERSION_CODE=90", "REGISTER_VERSION_CODE=91"),
    ("grep -q 'ReceiptVoucherLedgerActivity::class.java' app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherActivity.kt", "grep -q 'ReceiptVoucherNavigation.ledgerIntent' app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherActivity.kt"),
]
for old, new in replacements:
    if old not in text:
        raise RuntimeError(f"workflow replacement missing: {old}")
    text = text.replace(old, new)
text = text.replace(
    "          test -s app/src/test/java/jp/co/tenposinfo/register/V060ReceiptVoucherOperationsLedgerTest.kt\n",
    "          test -s app/src/test/java/jp/co/tenposinfo/register/V060ReceiptVoucherOperationsLedgerTest.kt\n"
    "          test -s app/src/test/java/jp/co/tenposinfo/register/V061ReceiptVoucherSaleContextNavigationTest.kt\n",
)
text = text.replace(
    "          test -s docs/V0.60_RELEASE_NOTES.md\n",
    "          test -s docs/V0.60_RELEASE_NOTES.md\n"
    "          test -s docs/V0.61_RECEIPT_VOUCHER_SALE_CONTEXT_NAVIGATION.md\n"
    "          test -s docs/V0.61_RELEASE_NOTES.md\n",
)
text = text.replace(
    "          grep -q '統合印刷キューで確認・対応' app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherLedgerActivity.kt\n",
    "          grep -q '統合印刷キューで確認・対応' app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherLedgerActivity.kt\n"
    "          test -s app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherNavigation.kt\n"
    "          grep -q '対象売上は固定されています' app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherActivity.kt\n"
    "          grep -q 'この売上で追加発行' app/src/main/java/jp/co/tenposinfo/register/ReceiptVoucherLedgerActivity.kt\n"
    "          grep -q 'ReceiptVoucherNavigation.issuanceIntent(context, detail.summary.id)' app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt\n",
)
text = text.replace(
    "          RECEIPT_VOUCHER_RECOVERY_USES_UNIFIED_QUEUE=true\n",
    "          RECEIPT_VOUCHER_RECOVERY_USES_UNIFIED_QUEUE=true\n"
    "          RECEIPT_VOUCHER_SALE_CONTEXT_NAVIGATION=true\n"
    "          RECEIPT_VOUCHER_SALE_CONTEXT_LOCK=true\n"
    "          RECEIPT_VOUCHER_LEDGER_ADDITIONAL_ISSUE=true\n"
    "          RECEIPT_VOUCHER_COMPLETE_SCREEN_ENTRY=true\n",
)
text = text.replace(
    "          REAL_DEVICE_RECEIPT_VOUCHER_LEDGER_VERIFICATION=required\n",
    "          REAL_DEVICE_RECEIPT_VOUCHER_SALE_CONTEXT_VERIFICATION=required\n"
    "          REAL_DEVICE_RECEIPT_VOUCHER_LEDGER_VERIFICATION=required\n",
)
write(workflow_path, text)

# 8. Remove generator files from the generated commit so the branch contains only product changes.
for relative in ["tools/v061_apply.py", ".github/workflows/v061-apply.yml"]:
    p = ROOT / relative
    if p.exists():
        p.unlink()

print("v0.61 patch applied")
