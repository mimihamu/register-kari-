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


# 1. Sale summary exposes the already-persisted business attribution without breaking old constructors.
replace(
    "app/src/main/java/jp/co/tenposinfo/register/Receipt.kt",
    '''data class SaleSummaryRecord(
    val id: Long,
    val operatorName: String,
    val paymentLabel: String,
    val totalAmount: Long,
    val taxAmount: Long,
    val changeAmount: Long,
    val createdAt: Long,
    val printCount: Int,
)''',
    '''data class SaleSummaryRecord(
    val id: Long,
    val operatorName: String,
    val paymentLabel: String,
    val totalAmount: Long,
    val taxAmount: Long,
    val changeAmount: Long,
    val createdAt: Long,
    val printCount: Int,
    val businessDate: String? = null,
    val businessSessionId: Long? = null,
)''',
)

# 2. RegisterDatabase reads business_date/session_id without calling BusinessSessionSchema.ensure(),
# because ensure() may backfill legacy rows. Search/read paths remain non-mutating.
replace(
    "app/src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt",
    '''        readableDatabase.query(
            "sales",
            arrayOf(
                "id",
                "operator_name",
                "payment_method",
                "total_amount",
                "tax_amount",
                "change_amount",
                "created_at",
                "print_count",
            ),''',
    '''        val db = readableDatabase
        db.query(
            "sales",
            saleSummaryColumns(db),''',
    1,
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt",
    '''        val summary = readableDatabase.query(
            "sales",
            arrayOf(
                "id",
                "operator_name",
                "payment_method",
                "total_amount",
                "tax_amount",
                "change_amount",
                "created_at",
                "print_count",
            ),''',
    '''        val summaryDb = readableDatabase
        val summary = summaryDb.query(
            "sales",
            saleSummaryColumns(summaryDb),''',
    1,
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt",
    '''        createdAt = getLong(6),
        printCount = getInt(7),
    )

    private fun Cursor.toPrintJob()''',
    '''        createdAt = getLong(6),
        printCount = getInt(7),
        businessDate = if (isNull(8)) null else getString(8),
        businessSessionId = if (isNull(9)) null else getLong(9),
    )

    private fun saleSummaryColumns(db: SQLiteDatabase): Array<String> {
        val businessDate = if (SchemaMigration.hasColumn(db, "sales", "business_date")) {
            "business_date"
        } else {
            "NULL AS business_date"
        }
        val businessSessionId = if (SchemaMigration.hasColumn(db, "sales", "business_session_id")) {
            "business_session_id"
        } else {
            "NULL AS business_session_id"
        }
        return arrayOf(
            "id",
            "operator_name",
            "payment_method",
            "total_amount",
            "tax_amount",
            "change_amount",
            "created_at",
            "print_count",
            businessDate,
            businessSessionId,
        )
    }

    private fun Cursor.toPrintJob()''',
)

# 3. SCR-400 adds business-date range controls and surfaces attribution in list/detail.
replace(
    "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt",
    '''    var minAmountText by remember { mutableStateOf("") }
    var maxAmountText by remember { mutableStateOf("") }
    var directSaleIdText by remember { mutableStateOf("") }''',
    '''    var minAmountText by remember { mutableStateOf("") }
    var maxAmountText by remember { mutableStateOf("") }
    var businessDateFromText by remember { mutableStateOf("") }
    var businessDateToText by remember { mutableStateOf("") }
    var directSaleIdText by remember { mutableStateOf("") }''',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt",
    '''        query = query,
        minAmount = minAmountText.toLongOrNull(),
        maxAmount = maxAmountText.toLongOrNull(),
    )
    val visibleSales = SalesHistoryLookupPolicy.filter(sales, criteria)
    val directSaleId = SalesHistoryLookupPolicy.parseDirectSaleId(directSaleIdText)
    val amountRangeInvalid = criteria.minAmount != null && criteria.maxAmount != null && criteria.minAmount > criteria.maxAmount''',
    '''        query = query,
        minAmount = minAmountText.toLongOrNull(),
        maxAmount = maxAmountText.toLongOrNull(),
        businessDateFrom = businessDateFromText,
        businessDateTo = businessDateToText,
    )
    val criteriaValidation = SalesHistoryLookupPolicy.validate(criteria)
    val visibleSales = SalesHistoryLookupPolicy.filter(sales, criteria)
    val directSaleId = SalesHistoryLookupPolicy.parseDirectSaleId(directSaleIdText)''',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt",
    '''                        query = ""
                        minAmountText = ""
                        maxAmountText = ""
                    },''',
    '''                        query = ""
                        minAmountText = ""
                        maxAmountText = ""
                        businessDateFromText = ""
                        businessDateToText = ""
                        lookupMessage = null
                    },''',
)
# Add business date controls at start of second row, before result count.
replace(
    "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt",
    '''            ) {
                Text(
                    "表示 ${visibleSales.size}件 / 読込 ${sales.size}件（直近最大${SalesHistoryLookupPolicy.RECENT_LOAD_LIMIT}件）",
                    fontWeight = FontWeight.Bold,
                    color = Navy,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = directSaleIdText,''',
    '''            ) {
                OutlinedTextField(
                    value = businessDateFromText,
                    onValueChange = {
                        businessDateFromText = it.filter { ch -> ch.isDigit() || ch == '-' }.take(10)
                        lookupMessage = null
                    },
                    label = { Text("営業日From") },
                    placeholder = { Text("YYYY-MM-DD") },
                    singleLine = true,
                    modifier = Modifier.width(175.dp),
                )
                Text("～", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = businessDateToText,
                    onValueChange = {
                        businessDateToText = it.filter { ch -> ch.isDigit() || ch == '-' }.take(10)
                        lookupMessage = null
                    },
                    label = { Text("営業日To") },
                    placeholder = { Text("YYYY-MM-DD") },
                    singleLine = true,
                    modifier = Modifier.width(175.dp),
                )
                Text(
                    "表示 ${visibleSales.size}件 / 読込 ${sales.size}件",
                    fontWeight = FontWeight.Bold,
                    color = Navy,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = directSaleIdText,''',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt",
    '''            if (amountRangeInvalid) {
                Text("金額範囲は『以上 ≤ 以下』になるよう入力してください", color = Danger, fontWeight = FontWeight.Bold)
            } else if (!lookupMessage.isNullOrBlank()) {
                Text(lookupMessage.orEmpty(), color = Danger, fontWeight = FontWeight.Bold)
            }''',
    '''            if (!criteriaValidation.valid) {
                Text(criteriaValidation.message.orEmpty(), color = Danger, fontWeight = FontWeight.Bold)
            } else if (!lookupMessage.isNullOrBlank()) {
                Text(lookupMessage.orEmpty(), color = Danger, fontWeight = FontWeight.Bold)
            } else if (businessDateFromText.isNotBlank() || businessDateToText.isNotBlank()) {
                Text(
                    "営業日で検索中（深夜0時をまたぐ取引も営業開始時の営業日に属します）",
                    color = Color(0xFF2E7D32),
                    fontWeight = FontWeight.Bold,
                )
            }''',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt",
    '''                                Text("#${sale.id}", Modifier.width(80.dp), fontWeight = FontWeight.Bold)
                                Text(formatDate(sale.createdAt), Modifier.width(165.dp))
                                Text(sale.operatorName, Modifier.width(100.dp))''',
    '''                                Text("#${sale.id}", Modifier.width(75.dp), fontWeight = FontWeight.Bold)
                                Text(sale.businessDate ?: "営業日未記録", Modifier.width(115.dp), color = if (sale.businessDate == null) Danger else Navy)
                                Text(formatDate(sale.createdAt), Modifier.width(155.dp))
                                Text(sale.operatorName, Modifier.width(95.dp))''',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt",
    '''                Text("売上 #${detail.summary.id}", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Navy)
                Text("${formatDate(detail.summary.createdAt)} / 担当 ${detail.summary.operatorName}", color = Color.Gray)
                Spacer(Modifier.height(12.dp))''',
    '''                Text("売上 #${detail.summary.id}", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Navy)
                Text("${formatDate(detail.summary.createdAt)} / 担当 ${detail.summary.operatorName}", color = Color.Gray)
                Text(
                    "営業日 ${detail.summary.businessDate ?: "未記録"} / セッション ${detail.summary.businessSessionId?.let { "No.$it" } ?: "未記録"}",
                    color = if (detail.summary.businessDate == null) Danger else Navy,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))''',
)

# 4. Advance version and cumulative current-version assertions.
replace("app/build.gradle.kts", "versionCode = 94", "versionCode = 95")
replace("app/build.gradle.kts", 'versionName = "0.64.0-dev.1"', 'versionName = "0.65.0-dev.1"')

old_apk = "TSUGUREGI_v0.64.0_dev1_sale_detail_reversal_navigation_debug.apk"
new_apk = "TSUGUREGI_v0.65.0_dev1_business_date_sales_lookup_debug.apk"
old_artifact = "TSUGUREGI-v0.64.0-dev1-sale-detail-reversal-navigation-apks"
new_artifact = "TSUGUREGI-v0.65.0-dev1-business-date-sales-lookup-apks"
for root in [ROOT / "app/src/test", ROOT / "management-app/src/test"]:
    for path in root.rglob("*.kt"):
        text = path.read_text(encoding="utf-8")
        updated = (
            text.replace("versionCode = 94", "versionCode = 95")
            .replace("0.64.0-dev.1", "0.65.0-dev.1")
            .replace(old_apk, new_apk)
            .replace(old_artifact, new_artifact)
        )
        if updated != text:
            path.write_text(updated, encoding="utf-8")

# 5. v0.65 tests.
write(
    "app/src/test/java/jp/co/tenposinfo/register/V065BusinessDateSalesLookupTest.kt",
    '''package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V065BusinessDateSalesLookupTest {
    private fun sale(
        id: Long,
        createdAt: Long,
        businessDate: String?,
        sessionId: Long? = null,
    ) = SaleSummaryRecord(
        id = id,
        operatorName = "担当$id",
        paymentLabel = "現金",
        totalAmount = 4_000,
        taxAmount = 0,
        changeAmount = 0,
        createdAt = createdAt,
        printCount = 0,
        businessDate = businessDate,
        businessSessionId = sessionId,
    )

    @Test
    fun filtersByBusinessDateNotCalendarTimestamp() {
        val sales = listOf(
            sale(1, createdAt = 100, businessDate = "2026-08-07", sessionId = 11),
            sale(2, createdAt = 200, businessDate = "2026-08-08", sessionId = 12),
            sale(3, createdAt = 300, businessDate = null),
        )
        val result = SalesHistoryLookupPolicy.filter(
            sales,
            SalesHistoryCriteria(businessDateFrom = "2026-08-07", businessDateTo = "2026-08-07"),
        )
        assertEquals(listOf(1L), result.map { it.id })
        assertEquals(100L, result.single().createdAt)
    }

    @Test
    fun supportsOpenEndedBusinessDateRangesAndExcludesLegacyNullOnlyWhenDateFilterIsActive() {
        val sales = listOf(
            sale(1, 100, "2026-08-06"),
            sale(2, 200, "2026-08-07"),
            sale(3, 300, "2026-08-08"),
            sale(4, 400, null),
        )
        assertEquals(listOf(2L, 3L), SalesHistoryLookupPolicy.filter(sales, SalesHistoryCriteria(businessDateFrom = "2026-08-07")).map { it.id })
        assertEquals(listOf(1L, 2L), SalesHistoryLookupPolicy.filter(sales, SalesHistoryCriteria(businessDateTo = "2026-08-07")).map { it.id })
        assertEquals(4, SalesHistoryLookupPolicy.filter(sales, SalesHistoryCriteria()).size)
    }

    @Test
    fun invalidDatesAndReverseRangeFailClosed() {
        val invalidFormat = SalesHistoryLookupPolicy.validate(SalesHistoryCriteria(businessDateFrom = "2026-8-7"))
        assertFalse(invalidFormat.valid)
        assertTrue(invalidFormat.message.orEmpty().contains("YYYY-MM-DD"))

        val reversed = SalesHistoryLookupPolicy.validate(
            SalesHistoryCriteria(businessDateFrom = "2026-08-08", businessDateTo = "2026-08-07"),
        )
        assertFalse(reversed.valid)
        assertTrue(reversed.message.orEmpty().contains("From ≤ To"))
    }

    @Test
    fun summaryDefaultsPreserveLegacyConstructors() {
        val legacy = SaleSummaryRecord(1, "担当", "現金", 100, 0, 0, 1, 0)
        assertNull(legacy.businessDate)
        assertNull(legacy.businessSessionId)
    }

    @Test
    fun databaseReadPathDoesNotInvokeBusinessSessionBackfill() {
        val database = File("src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt").readText()
        val main = File("src/main/java/jp/co/tenposinfo/register/MainActivity.kt").readText()
        val workflow = File("../.github/workflows/build-apk.yml").readText()

        assertTrue(database.contains("saleSummaryColumns(db)"))
        assertTrue(database.contains("NULL AS business_date"))
        assertTrue(database.contains("NULL AS business_session_id"))
        val listSalesBody = database.substringAfter("fun listSales").substringBefore("fun loadSaleDetail")
        assertFalse(listSalesBody.contains("BusinessSessionSchema.ensure"))
        assertTrue(main.contains("営業日From"))
        assertTrue(main.contains("営業日To"))
        assertTrue(main.contains("営業日未記録"))
        assertTrue(main.contains("深夜0時をまたぐ取引"))
        assertTrue(workflow.contains("V065BusinessDateSalesLookupTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_v0.65.0_dev1_business_date_sales_lookup_debug.apk"))
    }
}
''',
)

write(
    "docs/V0.65_BUSINESS_DATE_SALES_LOOKUP.md",
    '''# v0.65 営業日別 売上検索

## 目的

飲食店では深夜0時をまたいで営業するため、売上発生時刻のカレンダー日ではなく、営業開始時に確定した「営業日」で売上を探せる必要がある。

v0.65では、既にsalesへ保存されている `business_date` と `business_session_id` を読み取り側へ露出し、SCR-400で営業日範囲検索を可能にする。

## データ保全

検索のために `BusinessSessionSchema.ensure()` は呼ばない。これは過去データの営業セッション帰属をバックフィルする可能性があるためである。

読み取り時は `PRAGMA table_info(sales)` で既存列の有無だけを確認する。

- `business_date` が存在する場合: その値を読む。
- 存在しない旧DB: `NULL AS business_date` として扱う。
- `business_session_id` も同様。

したがって、売上一覧・詳細を開くだけではsalesを更新しない。

## 検索条件

- 営業日From（YYYY-MM-DD）
- 営業日To（YYYY-MM-DD）
- Fromのみ／Toのみも可
- From > Toはエラー
- 存在しない日付や形式不正はエラー
- 営業日未記録の旧売上は、営業日条件を指定していない場合は表示する
- 営業日条件を指定した場合、未記録売上は範囲へ含めない
- 売上No.／担当者／支払方法／金額条件とはAND検索

## 表示

- SCR-400一覧へ営業日を表示する。
- 未記録は「営業日未記録」と明示する。
- SCR-410売上詳細へ営業日と営業セッションNo.を表示する。
- 売上発生日時は従来どおり併記し、営業日と混同しない。

## 実機確認が必要な項目

- 深夜0時をまたぐ営業で、翌日0時以降の売上が前営業日に検索されること
- 営業日From/To入力時のソフトウェアキーボード操作
- 直近1,000件での営業日絞り込み性能
- 営業日未記録の旧売上表示
- 古いDBで一覧表示だけではデータ更新が発生しないこと
- v0.64→v0.65上書き更新
''',
)
write(
    "docs/V0.65_RELEASE_NOTES.md",
    '''# v0.65 Release Notes

- 売上サマリーへ既存 `business_date` / `business_session_id` を読み取り専用で追加。
- SCR-400へ営業日From/To検索を追加。
- 日付形式不正、From > Toをfail-closedで拒否。
- 営業日未記録の旧売上は勝手に補完せず「営業日未記録」と表示。
- 営業日条件を指定した場合だけ未記録売上を検索対象外にする。
- SCR-410へ営業日／営業セッションNo.表示を追加。
- 検索読み取りではバックフィルを実行せず、salesを更新しない。
- v0.64までの売上No.直接参照、領収書、返品・取消安全導線を維持。
''',
)

# 6. Advance cumulative workflow.
workflow_path = ".github/workflows/build-apk.yml"
text = read(workflow_path)
replacements = [
    ("Verify cumulative v0.14-v0.64 sources", "Verify cumulative v0.14-v0.65 sources"),
    ("grep -q 'versionCode = 94' app/build.gradle.kts", "grep -q 'versionCode = 95' app/build.gradle.kts"),
    ("grep -q 'versionName = \"0.64.0-dev.1\"' app/build.gradle.kts", "grep -q 'versionName = \"0.65.0-dev.1\"' app/build.gradle.kts"),
    (old_apk, new_apk),
    (old_artifact, new_artifact),
    ("REGISTER_VERSION_NAME=0.64.0-dev.1", "REGISTER_VERSION_NAME=0.65.0-dev.1"),
    ("REGISTER_VERSION_CODE=94", "REGISTER_VERSION_CODE=95"),
]
for old, new in replacements:
    if old not in text:
        raise RuntimeError(f"workflow replacement missing: {old}")
    text = text.replace(old, new)
text = text.replace(
    "          test -s app/src/test/java/jp/co/tenposinfo/register/V064SaleDetailReversalNavigationTest.kt\n",
    "          test -s app/src/test/java/jp/co/tenposinfo/register/V064SaleDetailReversalNavigationTest.kt\n"
    "          test -s app/src/test/java/jp/co/tenposinfo/register/V065BusinessDateSalesLookupTest.kt\n",
)
text = text.replace(
    "          test -s docs/V0.64_RELEASE_NOTES.md\n",
    "          test -s docs/V0.64_RELEASE_NOTES.md\n"
    "          test -s docs/V0.65_BUSINESS_DATE_SALES_LOOKUP.md\n"
    "          test -s docs/V0.65_RELEASE_NOTES.md\n",
)
anchor = "          grep -q '別売上を検索' app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt\n"
if anchor not in text:
    raise RuntimeError("workflow v0.64 source anchor missing")
text = text.replace(
    anchor,
    anchor
    + "          grep -q 'businessDate: String? = null' app/src/main/java/jp/co/tenposinfo/register/Receipt.kt\n"
    + "          grep -q 'NULL AS business_date' app/src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt\n"
    + "          grep -q 'NULL AS business_session_id' app/src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt\n"
    + "          grep -q '営業日From' app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt\n"
    + "          grep -q '営業日未記録' app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt\n",
)
summary_anchor = "          REVERSAL_CONTEXT_EXPLICIT_UNLOCK=true\n"
if summary_anchor not in text:
    raise RuntimeError("workflow v0.64 summary anchor missing")
text = text.replace(
    summary_anchor,
    summary_anchor
    + "          SALES_HISTORY_BUSINESS_DATE_FILTER=true\n"
    + "          SALES_HISTORY_BUSINESS_SESSION_ATTRIBUTION=true\n"
    + "          SALES_HISTORY_READ_ONLY_LEGACY_COLUMNS=true\n"
    + "          SALES_HISTORY_LEGACY_DATE_INFERENCE=false\n",
)
real_anchor = "          REAL_DEVICE_SALE_DETAIL_REVERSAL_NAVIGATION_VERIFICATION=required\n"
if real_anchor not in text:
    raise RuntimeError("workflow v0.64 real-device anchor missing")
text = text.replace(
    real_anchor,
    "          REAL_DEVICE_BUSINESS_DATE_SALES_LOOKUP_VERIFICATION=required\n" + real_anchor,
)
write(workflow_path, text)

# Remove generation-only files from final generated commit.
for relative in ["tools/v065_apply.py", ".github/workflows/v065-apply.yml"]:
    p = ROOT / relative
    if p.exists():
        p.unlink()

print("v0.65 patch applied")
