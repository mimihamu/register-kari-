from pathlib import Path
import subprocess

ROOT = Path('.')


def replace_once(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding='utf-8')
    if old not in text:
        raise RuntimeError(f'anchor not found in {path}: {old[:100]!r}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')


def replace_all_in_tests(old: str, new: str) -> None:
    for base in [ROOT / 'app/src/test', ROOT / 'management-app/src/test']:
        for p in base.rglob('*.kt'):
            text = p.read_text(encoding='utf-8')
            updated = text.replace(old, new)
            if updated != text:
                p.write_text(updated, encoding='utf-8')


# App version.
replace_once('app/build.gradle.kts', 'versionCode = 106', 'versionCode = 107')
replace_once('app/build.gradle.kts', 'versionName = "0.76.0-dev.1"', 'versionName = "0.77.0-dev.1"')

# Settlement history UI: expose an exact-session sales drilldown only to VIEW_SALES operators.
replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/SettlementHistoryScreenV027.kt',
    '''    previewLoader: (Long) -> String,\n    onReprint: (SettlementRecord, String) -> Unit,\n''',
    '''    previewLoader: (Long) -> String,\n    onOpenSalesDetail: (SettlementRecord) -> Unit,\n    onReprint: (SettlementRecord, String) -> Unit,\n''',
)
replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/SettlementHistoryScreenV027.kt',
    '''                    Text(\n                        "担当 ${selected.operatorName}\\n発行 ${historyDateTime(selected.createdAt)}",\n                        color = Color.DarkGray,\n                        fontSize = 13.sp,\n                    )\n''',
    '''                    Text(\n                        "担当 ${selected.operatorName}\\n発行 ${historyDateTime(selected.createdAt)}",\n                        color = Color.DarkGray,\n                        fontSize = 13.sp,\n                    )\n                    Spacer(Modifier.height(8.dp))\n                    val canViewSales = RegisterPermission.VIEW_SALES in permissions\n                    OutlinedButton(\n                        onClick = { onOpenSalesDetail(selected) },\n                        enabled = canViewSales && selected.businessSessionId > 0L,\n                        modifier = Modifier.fillMaxWidth().height(46.dp),\n                    ) {\n                        Text("この営業セッションの売上明細", fontWeight = FontWeight.Bold)\n                    }\n                    if (!canViewSales) {\n                        Text(\n                            "売上参照の権限がありません",\n                            color = HistoryDanger,\n                            fontSize = 12.sp,\n                        )\n                    }\n''',
)

# Runtime recheck immediately before navigation; never trust stale screen permissions.
replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt',
    '''                previewLoader = store::previewSettlement,\n                onReprint = { record, managerPin ->\n''',
    '''                previewLoader = store::previewSettlement,\n                onOpenSalesDetail = { record ->\n                    val current = OperatorSessionRegistry.current(appContext)\n                    activeOperator = current\n                    if (current?.allows(RegisterPermission.VIEW_SALES) == true) {\n                        message = null\n                        context.startActivity(\n                            BusinessDateSalesLookupNavigation.intent(\n                                context,\n                                record.businessDate,\n                                record.businessSessionId,\n                            ),\n                        )\n                    } else {\n                        message = "売上参照の権限がありません"\n                    }\n                },\n                onReprint = { record, managerPin ->\n''',
)

# New cumulative acceptance test.
test_path = ROOT / 'app/src/test/java/jp/co/tenposinfo/register/V077SettlementHistorySalesDrilldownTest.kt'
test_path.write_text('''package jp.co.tenposinfo.register\n\nimport org.junit.Assert.assertFalse\nimport org.junit.Assert.assertTrue\nimport org.junit.Test\nimport java.io.File\n\nclass V077SettlementHistorySalesDrilldownTest {\n    @Test\n    fun settlementHistoryDrilldownUsesExactSavedSessionAndRechecksPermission() {\n        val root = File("..")\n        val screen = File("src/main/java/jp/co/tenposinfo/register/SettlementHistoryScreenV027.kt").readText()\n        val operations = File("src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt").readText()\n        val navigation = File("src/main/java/jp/co/tenposinfo/register/BusinessDateSalesLookupNavigation.kt").readText()\n        val salesPolicy = File("src/main/java/jp/co/tenposinfo/register/SalesHistoryLookup.kt").readText()\n        val build = File("build.gradle.kts").readText()\n        val workflow = File(root, ".github/workflows/build-apk.yml").readText()\n        val docs = File(root, "docs/V0.77_SETTLEMENT_HISTORY_SALES_DRILLDOWN.md")\n        val notes = File(root, "docs/V0.77_RELEASE_NOTES.md")\n\n        assertTrue(screen.contains("onOpenSalesDetail: (SettlementRecord) -> Unit"))\n        assertTrue(screen.contains("この営業セッションの売上明細"))\n        assertTrue(screen.contains("RegisterPermission.VIEW_SALES in permissions"))\n        assertTrue(screen.contains("selected.businessSessionId > 0L"))\n        assertTrue(operations.contains("onOpenSalesDetail = { record ->"))\n        assertTrue(operations.contains("OperatorSessionRegistry.current(appContext)"))\n        assertTrue(operations.contains("current?.allows(RegisterPermission.VIEW_SALES) == true"))\n        assertTrue(operations.contains("record.businessDate"))\n        assertTrue(operations.contains("record.businessSessionId"))\n        assertTrue(operations.contains("BusinessDateSalesLookupNavigation.intent"))\n        assertTrue(navigation.contains("LocalDate.parse"))\n        assertTrue(navigation.contains("if (sessionId <= 0L) return null"))\n        assertTrue(salesPolicy.contains("business_session_id = ?"))\n        assertFalse(screen.contains("UPDATE sales"))\n        assertFalse(screen.contains("DELETE FROM sales"))\n\n        assertTrue(build.contains("versionCode = 107"))\n        assertTrue(build.contains("versionName = \\"0.77.0-dev.1\\""))\n        assertTrue(workflow.contains("V077SettlementHistorySalesDrilldownTest.kt"))\n        assertTrue(workflow.contains("TSUGUREGI_v0.77.0_dev1_settlement_history_sales_drilldown_debug.apk"))\n        assertTrue(docs.isFile)\n        assertTrue(notes.isFile)\n    }\n}\n''', encoding='utf-8')

# Documentation.
(ROOT / 'docs/V0.77_SETTLEMENT_HISTORY_SALES_DRILLDOWN.md').write_text('''# v0.77 点検・精算履歴 → 営業セッション売上明細\n\n## 目的\n\nSCR-520のX点検・Z精算履歴には営業日と営業セッションNo.が保存されています。問い合わせや精算確認時に、その保存済み集計の根拠となる売上明細へ直接進めるようにします。\n\n## 仕様\n\n- 選択中のSettlementRecordが持つ`businessDate`と`businessSessionId`を使用します。\n- SCR-520の保存内容欄に「この営業セッションの売上明細」を表示します。\n- `VIEW_SALES`がない場合はボタンを無効化し、権限不足を明示します。\n- ボタン押下時には`OperatorSessionRegistry.current()`で現在の担当権限を再取得し、`VIEW_SALES`を再確認します。\n- 権限確認後、v0.76の`BusinessDateSalesLookupNavigation.intent()`へ営業日＋session IDを渡します。\n- 遷移先SCR-620ではv0.76の固定contextをそのまま利用し、同じ営業日に別セッションが存在しても混在させません。\n- session IDが0以下、または日付がISO形式として不正な場合は既存navigationがfail-closedします。\n- 履歴表示・ドリルダウンは読み取り専用で、売上・税・支払・点検・精算履歴を更新しません。\n\n## 権限\n\n- SCR-520自体は従来の点検・精算履歴表示権限を維持します。\n- 売上明細への遷移だけ追加で`VIEW_SALES`を要求します。\n- 画面表示時のpermissionsだけを信用せず、遷移操作時にも現在権限を再確認します。\n\n## 実機確認が必要\n\n- X点検履歴から同一sessionの売上だけが表示されること\n- Z精算履歴から同一sessionの売上だけが表示されること\n- 同じ営業日に複数sessionがある実データで混在しないこと\n- `VIEW_SALES`なしでボタンが無効になること\n- 画面表示後に`VIEW_SALES`が失効した場合に遷移しないこと\n- 売上0件sessionの表示\n''', encoding='utf-8')

(ROOT / 'docs/V0.77_RELEASE_NOTES.md').write_text('''# v0.77.0-dev.1 リリースノート\n\n- 点検・精算履歴SCR-520から、選択した履歴の営業セッション売上明細へ直接進める導線を追加しました。\n- 保存済み`businessDate + businessSessionId`をv0.76の固定売上検索へ渡すため、同日複数営業でも別sessionの売上を混在させません。\n- 売上明細導線は`VIEW_SALES`で制御し、操作時にも現在権限を再確認します。\n- 点検・精算履歴、売上、税、支払データへの書き込みは追加していません。\n- 実機での画面遷移・権限失効・同日複数sessionデータ確認は未実施です。\n''', encoding='utf-8')

# Advance legacy tests that intentionally track the current cumulative app build.
replace_all_in_tests('0.76.0-dev.1', '0.77.0-dev.1')
replace_all_in_tests('versionCode = 106', 'versionCode = 107')
replace_all_in_tests(
    'TSUGUREGI_v0.76.0_dev1_business_session_sales_drilldown_debug.apk',
    'TSUGUREGI_v0.77.0_dev1_settlement_history_sales_drilldown_debug.apk',
)

# Main cumulative workflow.
workflow_path = ROOT / '.github/workflows/build-apk.yml'
w = workflow_path.read_text(encoding='utf-8')
w = w.replace('Verify cumulative v0.14-v0.76 sources', 'Verify cumulative v0.14-v0.77 sources')
w = w.replace("versionCode = 106", "versionCode = 107")
w = w.replace('0.76.0-dev.1', '0.77.0-dev.1')
w = w.replace(
    'TSUGUREGI_v0.76.0_dev1_business_session_sales_drilldown_debug.apk',
    'TSUGUREGI_v0.77.0_dev1_settlement_history_sales_drilldown_debug.apk',
)
w = w.replace(
    'TSUGUREGI-v0.76.0-dev1-business-session-sales-drilldown-apks',
    'TSUGUREGI-v0.77.0-dev1-settlement-history-sales-drilldown-apks',
)
anchor = '          test -s app/src/test/java/jp/co/tenposinfo/register/V076BusinessSessionSalesDrilldownTest.kt\n'
if anchor not in w:
    raise RuntimeError('V076 workflow anchor missing')
w = w.replace(anchor, anchor + '''          test -s app/src/test/java/jp/co/tenposinfo/register/V077SettlementHistorySalesDrilldownTest.kt\n          grep -q 'onOpenSalesDetail: (SettlementRecord) -> Unit' app/src/main/java/jp/co/tenposinfo/register/SettlementHistoryScreenV027.kt\n          grep -q 'この営業セッションの売上明細' app/src/main/java/jp/co/tenposinfo/register/SettlementHistoryScreenV027.kt\n          grep -q 'RegisterPermission.VIEW_SALES in permissions' app/src/main/java/jp/co/tenposinfo/register/SettlementHistoryScreenV027.kt\n          grep -q 'onOpenSalesDetail = { record ->' app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt\n          grep -q 'record.businessDate' app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt\n          grep -q 'record.businessSessionId' app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt\n''', 1)
anchor = '          test -s docs/V0.76_RELEASE_NOTES.md\n'
if anchor not in w:
    raise RuntimeError('v0.76 docs workflow anchor missing')
w = w.replace(anchor, anchor + '''          test -s docs/V0.77_SETTLEMENT_HISTORY_SALES_DRILLDOWN.md\n          test -s docs/V0.77_RELEASE_NOTES.md\n''', 1)
anchor = '          BUSINESS_SESSION_SALES_LEGACY_FAIL_CLOSED=true\n'
if anchor not in w:
    raise RuntimeError('business session flags anchor missing')
w = w.replace(anchor, anchor + '''          SETTLEMENT_HISTORY_SALES_DRILLDOWN=true\n          SETTLEMENT_HISTORY_SALES_EXACT_SESSION=true\n          SETTLEMENT_HISTORY_SALES_VIEW_SALES_GATE=true\n          SETTLEMENT_HISTORY_SALES_RUNTIME_PERMISSION_RECHECK=true\n          SETTLEMENT_HISTORY_SALES_READ_ONLY=true\n''', 1)
anchor = '          REAL_DEVICE_BUSINESS_SESSION_SALES_DRILLDOWN_VERIFICATION=required\n'
if anchor not in w:
    raise RuntimeError('real device anchor missing')
w = w.replace(anchor, '''          REAL_DEVICE_SETTLEMENT_HISTORY_SALES_DRILLDOWN_VERIFICATION=required\n''' + anchor, 1)
workflow_path.write_text(w, encoding='utf-8')

# Temporary apply machinery must not survive the generated commit.
Path('scripts/apply_v077.py').unlink(missing_ok=True)
Path('.github/workflows/apply-v077.yml').unlink(missing_ok=True)

subprocess.run(['git', 'config', 'user.name', 'github-actions[bot]'], check=True)
subprocess.run(['git', 'config', 'user.email', '41898282+github-actions[bot]@users.noreply.github.com'], check=True)
subprocess.run(['git', 'add', '-A'], check=True)
subprocess.run(['git', 'commit', '-m', 'feat(v0.77): add settlement history sales drilldown'], check=True)
sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
print(f'V077_COMMIT={sha}', flush=True)
result = subprocess.run(['git', 'push', 'origin', 'HEAD:develop/v0.77'])
raise SystemExit(result.returncode)
