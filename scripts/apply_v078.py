from pathlib import Path
import subprocess

ROOT = Path('.')


def replace_once(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding='utf-8')
    if old not in text:
        raise RuntimeError(f'anchor not found in {path}: {old[:120]!r}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')


def replace_tests(old: str, new: str) -> None:
    for base in [ROOT / 'app/src/test', ROOT / 'management-app/src/test']:
        for p in base.rglob('*.kt'):
            text = p.read_text(encoding='utf-8')
            updated = text.replace(old, new)
            if updated != text:
                p.write_text(updated, encoding='utf-8')


# Pure reconciliation policy.
(ROOT / 'app/src/main/java/jp/co/tenposinfo/register/SettlementReconciliationV078.kt').write_text(r'''package jp.co.tenposinfo.register

enum class SettlementReconciliationSeverity {
    OK,
    INFO,
    ALERT,
}

data class SettlementReconciliationField(
    val label: String,
    val savedValue: String,
    val currentValue: String,
) {
    val matches: Boolean get() = savedValue == currentValue
}

data class SettlementReconciliationResult(
    val reportId: Long,
    val reportType: SettlementReportType,
    val businessSessionId: Long,
    val businessDate: String,
    val fullSnapshot: Boolean,
    val fields: List<SettlementReconciliationField>,
    val severity: SettlementReconciliationSeverity,
    val message: String,
) {
    val differences: List<SettlementReconciliationField> get() = fields.filterNot { it.matches }
    val exactMatch: Boolean get() = fullSnapshot && differences.isEmpty()
}

/**
 * v0.78 点検・精算の保存snapshotと現在DB集計を、業務データを変更せず比較する。
 * X点検後は営業継続により差が発生し得るためINFO、Z精算後の差異はALERTとする。
 */
object SettlementReconciliationPolicyV078 {
    fun compare(
        saved: SettlementRecord,
        current: DailyOperationsSummary,
    ): SettlementReconciliationResult {
        val fullSnapshot = saved.snapshotVersion >= SettlementSnapshotSchemaV027.SNAPSHOT_VERSION
        val fields = buildList {
            add(field("営業日", saved.businessDate, current.businessDate))
            add(field("営業セッション", saved.businessSessionId, current.businessSessionId))
            add(field("売上総額", saved.salesGross, current.salesGross))
            add(field("返品・取消", saved.reversalGross, current.reversalGross))
            add(field("純売上", saved.netSales, current.netSales))
            add(field("現金理論", saved.expectedCash, current.expectedCash))
            add(field("売上件数", saved.transactionCount.toLong(), current.transactionCount.toLong()))
            add(field("返品・取消件数", saved.reversalCount.toLong(), current.reversalCount.toLong()))
            if (fullSnapshot) {
                add(field("開始釣銭", saved.openingCash, current.openingCash))
                add(field("入金", saved.cashIn, current.cashIn))
                add(field("出金", saved.cashOut, current.cashOut))
            }
        }
        val differences = fields.filterNot { it.matches }
        val severity = when {
            differences.isEmpty() && fullSnapshot -> SettlementReconciliationSeverity.OK
            differences.isNotEmpty() && saved.type == SettlementReportType.Z_SETTLEMENT ->
                SettlementReconciliationSeverity.ALERT
            else -> SettlementReconciliationSeverity.INFO
        }
        val message = when {
            differences.isEmpty() && fullSnapshot ->
                "保存値と現在DB集計は一致しています。"
            differences.isEmpty() ->
                "比較可能な保存値は一致しています。旧形式snapshotのため完全照合はできません。"
            saved.type == SettlementReportType.X_INSPECTION ->
                "X点検後の取引・返品・入出金で現在値が変わるため、差異は参考情報です。"
            !fullSnapshot ->
                "Z精算の保存値と現在DB集計に差異があります。旧形式snapshotのため比較範囲は限定されています。監査してください。"
            else ->
                "Z精算の保存値と現在DB集計に差異があります。監査してください。"
        }
        return SettlementReconciliationResult(
            reportId = saved.id,
            reportType = saved.type,
            businessSessionId = saved.businessSessionId,
            businessDate = saved.businessDate,
            fullSnapshot = fullSnapshot,
            fields = fields,
            severity = severity,
            message = message,
        )
    }

    private fun field(label: String, saved: Long, current: Long) = SettlementReconciliationField(
        label = label,
        savedValue = saved.toString(),
        currentValue = current.toString(),
    )

    private fun field(label: String, saved: String, current: String) = SettlementReconciliationField(
        label = label,
        savedValue = saved,
        currentValue = current,
    )
}
''', encoding='utf-8')

# SCR-520: add reconciliation loader and modal result.
screen_path = 'app/src/main/java/jp/co/tenposinfo/register/SettlementHistoryScreenV027.kt'
replace_once(
    screen_path,
    'import androidx.compose.material3.Button\n',
    'import androidx.compose.material3.AlertDialog\nimport androidx.compose.material3.Button\n',
)
replace_once(
    screen_path,
    '''    previewLoader: (Long) -> String,\n    onOpenSalesDetail: (SettlementRecord) -> Unit,\n    onReprint: (SettlementRecord, String) -> Unit,\n''',
    '''    previewLoader: (Long) -> String,\n    reconciliationLoader: (SettlementRecord) -> SettlementReconciliationResult,\n    onOpenSalesDetail: (SettlementRecord) -> Unit,\n    onReprint: (SettlementRecord, String) -> Unit,\n''',
)
replace_once(
    screen_path,
    '''    var managerPin by remember { mutableStateOf("") }\n    @Suppress("UNUSED_VARIABLE") val refresh = revision\n''',
    '''    var managerPin by remember { mutableStateOf("") }\n    var reconciliation by remember { mutableStateOf<SettlementReconciliationResult?>(null) }\n    var reconciliationError by remember { mutableStateOf<String?>(null) }\n    @Suppress("UNUSED_VARIABLE") val refresh = revision\n''',
)
replace_once(
    screen_path,
    '''            selectedReportId = filtered.firstOrNull()?.id\n            managerPin = ""\n        }\n    }\n''',
    '''            selectedReportId = filtered.firstOrNull()?.id\n            managerPin = ""\n        }\n        reconciliation = null\n        reconciliationError = null\n    }\n''',
)
old_block = '''                    val canViewSales = RegisterPermission.VIEW_SALES in permissions\n                    OutlinedButton(\n                        onClick = { onOpenSalesDetail(selected) },\n                        enabled = canViewSales && selected.businessSessionId > 0L,\n                        modifier = Modifier.fillMaxWidth().height(46.dp),\n                    ) {\n                        Text("この営業セッションの売上明細", fontWeight = FontWeight.Bold)\n                    }\n                    if (!canViewSales) {\n                        Text(\n                            "売上参照の権限がありません",\n                            color = HistoryDanger,\n                            fontSize = 12.sp,\n                        )\n                    }\n'''
new_block = '''                    val canViewSales = RegisterPermission.VIEW_SALES in permissions\n                    val reportPermission = SettlementHistoryPolicyV027.permissionFor(selected.type)\n                    val canReconcile = canViewSales && reportPermission in permissions\n                    OutlinedButton(\n                        onClick = { onOpenSalesDetail(selected) },\n                        enabled = canViewSales && selected.businessSessionId > 0L,\n                        modifier = Modifier.fillMaxWidth().height(46.dp),\n                    ) {\n                        Text("この営業セッションの売上明細", fontWeight = FontWeight.Bold)\n                    }\n                    Spacer(Modifier.height(6.dp))\n                    OutlinedButton(\n                        onClick = {\n                            runCatching { reconciliationLoader(selected) }\n                                .onSuccess { result ->\n                                    reconciliation = result\n                                    reconciliationError = null\n                                }\n                                .onFailure { error ->\n                                    reconciliation = null\n                                    reconciliationError = error.message ?: "整合確認に失敗しました"\n                                }\n                        },\n                        enabled = canReconcile && selected.businessSessionId > 0L,\n                        modifier = Modifier.fillMaxWidth().height(46.dp),\n                    ) {\n                        Text("保存値と現在DBを照合", fontWeight = FontWeight.Bold)\n                    }\n                    reconciliationError?.let { error ->\n                        Text(error, color = HistoryDanger, fontSize = 12.sp)\n                    }\n                    if (!canViewSales) {\n                        Text(\n                            "売上参照の権限がありません",\n                            color = HistoryDanger,\n                            fontSize = 12.sp,\n                        )\n                    } else if (reportPermission !in permissions) {\n                        Text(\n                            "${reportPermission.displayName}の権限がありません",\n                            color = HistoryDanger,\n                            fontSize = 12.sp,\n                        )\n                    }\n'''
replace_once(screen_path, old_block, new_block)
replace_once(
    screen_path,
    '''        }\n    }\n}\n\n@Composable\nprivate fun SettlementHistoryHeader() {\n''',
    '''        }\n    }\n\n    reconciliation?.let { result ->\n        AlertDialog(\n            onDismissRequest = { reconciliation = null },\n            title = { Text("保存値と現在DBの整合確認") },\n            text = {\n                Column(\n                    Modifier.fillMaxWidth().height(420.dp).verticalScroll(rememberScrollState()),\n                    verticalArrangement = Arrangement.spacedBy(6.dp),\n                ) {\n                    Text(\n                        "${result.reportType.displayName} No.${result.reportId} / ${result.businessDate} / セッションNo.${result.businessSessionId}",\n                        fontWeight = FontWeight.Bold,\n                        color = HistoryNavy,\n                    )\n                    Text(\n                        result.message,\n                        color = when (result.severity) {\n                            SettlementReconciliationSeverity.OK -> HistoryGreen\n                            SettlementReconciliationSeverity.INFO -> Color.DarkGray\n                            SettlementReconciliationSeverity.ALERT -> HistoryDanger\n                        },\n                        fontWeight = FontWeight.Bold,\n                    )\n                    Text(\n                        if (result.fullSnapshot) "保存snapshot: 完全保存" else "保存snapshot: 旧形式（比較範囲限定）",\n                        color = Color.Gray,\n                        fontSize = 12.sp,\n                    )\n                    Spacer(Modifier.height(4.dp))\n                    result.fields.forEach { field ->\n                        Row(Modifier.fillMaxWidth()) {\n                            Text(field.label, Modifier.width(120.dp), fontWeight = FontWeight.SemiBold)\n                            Text("保存 ${field.savedValue}", Modifier.weight(1f), fontSize = 13.sp)\n                            Text(\n                                "現在 ${field.currentValue}",\n                                Modifier.weight(1f),\n                                color = if (field.matches) HistoryGreen else HistoryDanger,\n                                fontWeight = if (field.matches) FontWeight.Normal else FontWeight.Bold,\n                                fontSize = 13.sp,\n                            )\n                        }\n                    }\n                    Spacer(Modifier.height(4.dp))\n                    Text(\n                        "未印刷・未会計伝票は営業セッション単位で現在値を再現できないため照合対象外です。",\n                        color = Color.Gray,\n                        fontSize = 12.sp,\n                    )\n                }\n            },\n            confirmButton = {\n                Button(onClick = { reconciliation = null }) { Text("閉じる") }\n            },\n        )\n    }\n}\n\n@Composable\nprivate fun SettlementHistoryHeader() {\n''',
)

# Runtime permission recheck and fresh-record comparison in OperationsActivity.
replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt',
    '''                previewLoader = store::previewSettlement,\n                onOpenSalesDetail = { record ->\n''',
    '''                previewLoader = store::previewSettlement,\n                reconciliationLoader = { record ->\n                    val current = OperatorSessionRegistry.current(appContext)\n                    activeOperator = current\n                    val reportPermission = SettlementHistoryPolicyV027.permissionFor(record.type)\n                    check(current?.allows(RegisterPermission.VIEW_SALES) == true) { "売上参照の権限がありません" }\n                    check(current.allows(reportPermission)) { "${reportPermission.displayName}の権限がありません" }\n                    val latestRecord = store.settlementById(record.id)\n                        ?: error("点検・精算履歴No.${record.id}が見つかりません")\n                    check(latestRecord.businessSessionId == record.businessSessionId) { "営業セッションが一致しません" }\n                    SettlementReconciliationPolicyV078.compare(\n                        latestRecord,\n                        store.summaryForSession(latestRecord.businessSessionId),\n                    )\n                },\n                onOpenSalesDetail = { record ->\n''',
)

# App version.
replace_once('app/build.gradle.kts', 'versionCode = 107', 'versionCode = 108')
replace_once('app/build.gradle.kts', 'versionName = "0.77.0-dev.1"', 'versionName = "0.78.0-dev.1"')

# Dedicated policy/source test.
(ROOT / 'app/src/test/java/jp/co/tenposinfo/register/V078SettlementReconciliationTest.kt').write_text(r'''package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V078SettlementReconciliationTest {
    private fun saved(
        type: SettlementReportType,
        snapshotVersion: Int = SettlementSnapshotSchemaV027.SNAPSHOT_VERSION,
        netSales: Long = 9_000,
    ) = SettlementRecord(
        id = 10,
        businessSessionId = 7,
        businessDate = "2026-08-09",
        type = type,
        salesGross = 10_000,
        reversalGross = 1_000,
        netSales = netSales,
        expectedCash = 8_000,
        actualCash = 8_000,
        variance = 0,
        transactionCount = 5,
        reversalCount = 1,
        pendingPrints = 0,
        heldTickets = 0,
        operatorName = "担当",
        createdAt = 1_000,
        openingCash = 2_000,
        cashIn = 500,
        cashOut = 200,
        snapshotVersion = snapshotVersion,
    )

    private fun current(netSales: Long = 9_000) = DailyOperationsSummary(
        businessSessionId = 7,
        businessDate = "2026-08-09",
        salesGross = 10_000,
        reversalGross = 1_000,
        netSales = netSales,
        transactionCount = 5,
        reversalCount = 1,
        paymentTotals = emptyList(),
        openingCash = 2_000,
        cashIn = 500,
        cashOut = 200,
        expectedCash = 8_000,
        pendingPrints = 99,
        heldTickets = 99,
        settled = true,
    )

    @Test
    fun completeZSnapshotExactMatchIsOk() {
        val result = SettlementReconciliationPolicyV078.compare(
            saved(SettlementReportType.Z_SETTLEMENT),
            current(),
        )
        assertTrue(result.exactMatch)
        assertTrue(result.differences.isEmpty())
        assertEquals(SettlementReconciliationSeverity.OK, result.severity)
    }

    @Test
    fun zMismatchIsAlertButXMismatchIsInformational() {
        val z = SettlementReconciliationPolicyV078.compare(
            saved(SettlementReportType.Z_SETTLEMENT),
            current(netSales = 9_500),
        )
        assertFalse(z.exactMatch)
        assertEquals(SettlementReconciliationSeverity.ALERT, z.severity)
        assertTrue(z.differences.any { it.label == "純売上" })
        assertTrue(z.message.contains("監査"))

        val x = SettlementReconciliationPolicyV078.compare(
            saved(SettlementReportType.X_INSPECTION),
            current(netSales = 9_500),
        )
        assertEquals(SettlementReconciliationSeverity.INFO, x.severity)
        assertTrue(x.message.contains("後の取引"))
    }

    @Test
    fun legacySnapshotDoesNotPretendToBeExact() {
        val result = SettlementReconciliationPolicyV078.compare(
            saved(SettlementReportType.Z_SETTLEMENT, snapshotVersion = 0),
            current(),
        )
        assertFalse(result.exactMatch)
        assertFalse(result.fullSnapshot)
        assertEquals(SettlementReconciliationSeverity.INFO, result.severity)
        assertFalse(result.fields.any { it.label == "開始釣銭" })
    }

    @Test
    fun sourceIsPermissionCheckedAndBusinessDataReadOnly() {
        val root = File("..")
        val policy = File("src/main/java/jp/co/tenposinfo/register/SettlementReconciliationV078.kt").readText()
        val screen = File("src/main/java/jp/co/tenposinfo/register/SettlementHistoryScreenV027.kt").readText()
        val operations = File("src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()

        assertTrue(screen.contains("保存値と現在DBを照合"))
        assertTrue(screen.contains("reconciliationLoader"))
        assertTrue(screen.contains("AlertDialog"))
        assertTrue(operations.contains("SettlementReconciliationPolicyV078.compare"))
        assertTrue(operations.contains("store.settlementById(record.id)"))
        assertTrue(operations.contains("store.summaryForSession(latestRecord.businessSessionId)"))
        assertTrue(operations.contains("current?.allows(RegisterPermission.VIEW_SALES) == true"))
        assertTrue(operations.contains("current.allows(reportPermission)"))
        assertFalse(policy.contains("UPDATE "))
        assertFalse(policy.contains("DELETE FROM"))
        assertFalse(screen.contains("UPDATE settlement_reports"))
        assertFalse(screen.contains("DELETE FROM settlement_reports"))
        assertTrue(build.contains("versionCode = 108"))
        assertTrue(build.contains("versionName = \"0.78.0-dev.1\""))
        assertTrue(workflow.contains("V078SettlementReconciliationTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_v0.78.0_dev1_settlement_reconciliation_debug.apk"))
        assertTrue(workflow.contains("TSUGUREGI-v0.78.0-dev1-settlement-reconciliation-apks"))
        assertTrue(File(root, "docs/V0.78_SETTLEMENT_RECONCILIATION.md").isFile)
        assertTrue(File(root, "docs/V0.78_RELEASE_NOTES.md").isFile)
    }
}
''', encoding='utf-8')

(ROOT / 'docs/V0.78_SETTLEMENT_RECONCILIATION.md').write_text(r'''# v0.78 点検・精算 保存値整合確認

## 目的

SCR-520のX点検・Z精算履歴には発行時点の集計snapshotが保存されています。問い合わせ・監査時に、その保存値と現在SQLiteから同じ営業セッションを再集計した値を比較できるようにします。

## 比較対象

営業日、営業セッションNo.、売上総額、返品・取消、純売上、現金理論、売上件数、返品・取消件数を比較します。v0.27以降の完全snapshotでは、開始釣銭、入金、出金も比較します。

未印刷件数・未会計伝票件数は既存の現在集計が営業セッション単位に閉じていないため、誤判定を避けて照合対象外とします。

## 判定

- 完全snapshotですべて一致: OK
- X点検で差異: INFO。点検後も営業が続くため、後続売上・返品・入出金による差異を正常な可能性として扱います。
- Z精算で差異: ALERT。営業終了後の保存snapshotと現在DB集計が異なるため監査対象とします。
- 旧形式snapshot: 比較可能項目だけを照合し、完全一致とは断定しません。

## 権限・安全性

- `VIEW_SALES`に加え、対象レポート種別の`X_INSPECTION`または`Z_SETTLEMENT`権限を要求します。
- 「照合」押下時に`OperatorSessionRegistry.current()`で権限を再取得します。
- 選択中レコードを信用せず`settlementById()`で最新保存レコードを再取得し、営業セッションNo.一致を確認します。
- 比較処理自体は計算のみで、売上・返品・支払・点検・精算履歴を書き換えません。

## 実機確認が必要

- X点検後に追加売上を作成した場合、INFO差異になること
- Z精算直後は一致すること
- Z精算snapshotとDB集計に意図的差異を作れる検証環境でALERT表示になること
- 旧snapshotが完全一致扱いにならないこと
- `VIEW_SALES`または対象レポート権限がない場合に照合できないこと
- 画面表示後に権限が失効した場合、照合操作が拒否されること
''', encoding='utf-8')

(ROOT / 'docs/V0.78_RELEASE_NOTES.md').write_text(r'''# v0.78.0-dev.1 リリースノート

- SCR-520「点検・精算履歴／再印字」に「保存値と現在DBを照合」を追加しました。
- 保存済みX/Z snapshotと、同一営業セッションを現在SQLiteから再集計した値を比較します。
- X点検後の差異は後続取引があり得るためINFO、Z精算後の差異は監査対象としてALERT表示します。
- 旧形式snapshotは比較可能項目のみ確認し、完全一致とは判定しません。
- `VIEW_SALES`と対象X/Z権限を操作時に再確認します。
- 売上・税・支払・点検・精算履歴への書き込みは追加していません。
- 実機でのX後続取引、Z一致/差異、権限失効確認は未実施です。
''', encoding='utf-8')

# Current-version assertions in cumulative tests.
replace_tests('0.77.0-dev.1', '0.78.0-dev.1')
replace_tests('versionCode = 107', 'versionCode = 108')
replace_tests(
    'TSUGUREGI_v0.77.0_dev1_settlement_history_sales_drilldown_debug.apk',
    'TSUGUREGI_v0.78.0_dev1_settlement_reconciliation_debug.apk',
)
replace_tests(
    'TSUGUREGI-v0.77.0-dev1-settlement-history-sales-drilldown-apks',
    'TSUGUREGI-v0.78.0-dev1-settlement-reconciliation-apks',
)

# Cumulative workflow v0.78 + correct summary metadata.
workflow_path = ROOT / '.github/workflows/build-apk.yml'
w = workflow_path.read_text(encoding='utf-8')
w = w.replace('Verify cumulative v0.14-v0.77 sources', 'Verify cumulative v0.14-v0.78 sources')
w = w.replace("versionCode = 107", "versionCode = 108")
w = w.replace('0.77.0-dev.1', '0.78.0-dev.1')
w = w.replace(
    'TSUGUREGI_v0.77.0_dev1_settlement_history_sales_drilldown_debug.apk',
    'TSUGUREGI_v0.78.0_dev1_settlement_reconciliation_debug.apk',
)
w = w.replace(
    'TSUGUREGI-v0.77.0-dev1-settlement-history-sales-drilldown-apks',
    'TSUGUREGI-v0.78.0-dev1-settlement-reconciliation-apks',
)
w = w.replace('REGISTER_VERSION_CODE=107', 'REGISTER_VERSION_CODE=108')
anchor = '          test -s app/src/test/java/jp/co/tenposinfo/register/V077SettlementHistorySalesDrilldownTest.kt\n'
if anchor not in w:
    raise RuntimeError('V077 workflow test anchor missing')
w = w.replace(anchor, anchor + '''          test -s app/src/test/java/jp/co/tenposinfo/register/V078SettlementReconciliationTest.kt\n          test -s app/src/main/java/jp/co/tenposinfo/register/SettlementReconciliationV078.kt\n          grep -q '保存値と現在DBを照合' app/src/main/java/jp/co/tenposinfo/register/SettlementHistoryScreenV027.kt\n          grep -q 'SettlementReconciliationPolicyV078.compare' app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt\n          grep -q 'store.settlementById(record.id)' app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt\n          grep -q 'store.summaryForSession(latestRecord.businessSessionId)' app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt\n''', 1)
anchor = '          test -s docs/V0.77_RELEASE_NOTES.md\n'
if anchor not in w:
    raise RuntimeError('V077 docs workflow anchor missing')
w = w.replace(anchor, anchor + '''          test -s docs/V0.78_SETTLEMENT_RECONCILIATION.md\n          test -s docs/V0.78_RELEASE_NOTES.md\n''', 1)
anchor = '          SETTLEMENT_HISTORY_SALES_READ_ONLY=true\n'
if anchor not in w:
    raise RuntimeError('v0.77 flags anchor missing')
w = w.replace(anchor, anchor + '''          SETTLEMENT_RECONCILIATION=true\n          SETTLEMENT_RECONCILIATION_X_DIFFERENCE_INFORMATIONAL=true\n          SETTLEMENT_RECONCILIATION_Z_DIFFERENCE_ALERT=true\n          SETTLEMENT_RECONCILIATION_SNAPSHOT_VERSION_AWARE=true\n          SETTLEMENT_RECONCILIATION_RUNTIME_PERMISSION_RECHECK=true\n          SETTLEMENT_RECONCILIATION_BUSINESS_DATA_READ_ONLY=true\n''', 1)
anchor = '          REAL_DEVICE_SETTLEMENT_HISTORY_SALES_DRILLDOWN_VERIFICATION=required\n'
if anchor not in w:
    raise RuntimeError('v0.77 real-device anchor missing')
w = w.replace(anchor, '''          REAL_DEVICE_SETTLEMENT_RECONCILIATION_VERIFICATION=required\n''' + anchor, 1)
workflow_path.write_text(w, encoding='utf-8')

# Temporary apply files must not survive final generated commit.
Path('scripts/apply_v078.py').unlink(missing_ok=True)
Path('.github/workflows/apply-v078.yml').unlink(missing_ok=True)

subprocess.run(['git', 'config', 'user.name', 'github-actions[bot]'], check=True)
subprocess.run(['git', 'config', 'user.email', '41898282+github-actions[bot]@users.noreply.github.com'], check=True)
subprocess.run(['git', 'add', '-A'], check=True)
subprocess.run(['git', 'commit', '-m', 'feat(v0.78): add settlement reconciliation'], check=True)
sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
print(f'V078_COMMIT={sha}', flush=True)
result = subprocess.run(['git', 'push', 'origin', 'HEAD:develop/v0.78'])
raise SystemExit(result.returncode)
