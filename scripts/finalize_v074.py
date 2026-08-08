from pathlib import Path
import subprocess

ROOT = Path('.')
WORKFLOW = Path('.github/workflows/build-apk.yml')
SCRIPT = Path('scripts/finalize_v074.py')
ACTIVITY = Path('app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt')
BUILD = Path('app/build.gradle.kts')

OLD_VERSION = '0.73.0-dev.1'
NEW_VERSION = '0.74.0-dev.1'
OLD_CODE = '103'
NEW_CODE = '104'
OLD_APK = 'TSUGUREGI_v0.73.0_dev1_sale_receipt_reprint_stable_paging_debug.apk'
NEW_APK = 'TSUGUREGI_v0.74.0_dev1_sale_receipt_reprint_matching_new_items_debug.apk'
OLD_ARTIFACT = 'TSUGUREGI-v0.73.0-dev1-sale-receipt-reprint-stable-paging-apks'
NEW_ARTIFACT = 'TSUGUREGI-v0.74.0-dev1-sale-receipt-reprint-matching-new-items-apks'


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 occurrence, got {count}')
    return text.replace(old, new, 1)

# 1) SCR-648: condition-matching new-item indicator + refresh same applied criteria.
activity = ACTIVITY.read_text()
old_block = '''            if (page.newerAuditCount > 0) {
                Text(
                    "新しい再印字要求 ${page.newerAuditCount}件（検索再実行で反映）",
                    color = ReprintLedgerDanger,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
            }
'''
new_block = '''            if (page.newerAuditCount > 0) {
                Text(
                    "条件一致の新着 ${page.newerAuditCount}件",
                    color = ReprintLedgerDanger,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
                OutlinedButton(
                    onClick = { applyCriteria(appliedCriteria) },
                ) { Text("新着を反映") }
            }
'''
activity = replace_once(activity, old_block, new_block, 'activity newer block')
ACTIVITY.write_text(activity)

# 2) App version.
build = BUILD.read_text()
build = replace_once(build, 'versionCode = 103', 'versionCode = 104', 'app versionCode')
build = replace_once(build, 'versionName = "0.73.0-dev.1"', 'versionName = "0.74.0-dev.1"', 'app versionName')
BUILD.write_text(build)

# 3) Current-version references in cumulative tests.
for base in [Path('app/src/test'), Path('management-app/src/test'), Path('customer-display/src/test')]:
    if not base.exists():
        continue
    for path in base.rglob('*.kt'):
        text = path.read_text()
        updated = text.replace(OLD_VERSION, NEW_VERSION)
        updated = updated.replace('versionCode = 103', 'versionCode = 104')
        updated = updated.replace(OLD_APK, NEW_APK)
        updated = updated.replace(OLD_ARTIFACT, NEW_ARTIFACT)
        if path.name == 'V073SaleReceiptReprintStablePagingTest.kt':
            updated = updated.replace(
                'assertTrue(activity.contains("新しい再印字要求"))',
                'assertTrue(activity.contains("page.newerAuditCount > 0"))',
            )
        if updated != text:
            path.write_text(updated)

# 4) Restore the normal v0.73 workflow from the parent commit, then advance it cumulatively to v0.74.
workflow = subprocess.check_output(
    ['git', 'show', 'HEAD^:.github/workflows/build-apk.yml'],
    text=True,
)
workflow = replace_once(workflow, 'Verify cumulative v0.14-v0.73 sources', 'Verify cumulative v0.14-v0.74 sources', 'workflow title')
workflow = replace_once(workflow, "grep -q 'versionCode = 103' app/build.gradle.kts", "grep -q 'versionCode = 104' app/build.gradle.kts", 'workflow versionCode')
workflow = replace_once(workflow, "grep -q 'versionName = \"0.73.0-dev.1\"' app/build.gradle.kts", "grep -q 'versionName = \"0.74.0-dev.1\"' app/build.gradle.kts", 'workflow versionName')
workflow = workflow.replace(OLD_APK, NEW_APK)
workflow = workflow.replace(OLD_ARTIFACT, NEW_ARTIFACT)
workflow = replace_once(workflow, 'REGISTER_VERSION_NAME=0.73.0-dev.1', 'REGISTER_VERSION_NAME=0.74.0-dev.1', 'summary versionName')
workflow = replace_once(workflow, 'REGISTER_VERSION_CODE=103', 'REGISTER_VERSION_CODE=104', 'summary versionCode')

v073_test = '          test -s app/src/test/java/jp/co/tenposinfo/register/V073SaleReceiptReprintStablePagingTest.kt\n'
if v073_test not in workflow:
    raise SystemExit('V073 test guard missing')
workflow = workflow.replace(
    v073_test,
    v073_test + '          test -s app/src/test/java/jp/co/tenposinfo/register/V074SaleReceiptReprintMatchingNewItemsTest.kt\n',
    1,
)

old_indicator_guard = "          grep -q '新しい再印字要求' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt\n"
if old_indicator_guard not in workflow:
    raise SystemExit('old v0.73 indicator guard missing')
workflow = workflow.replace(
    old_indicator_guard,
    "          grep -q '条件一致の新着' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt\n"
    "          grep -q '新着を反映' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt\n"
    "          grep -q 'applyCriteria(appliedCriteria)' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt\n"
    "          ! grep -q '検索再実行で反映' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt\n"
    "          grep -q 'appendNewerThanSnapshot' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintStablePaging.kt\n"
    "          grep -q 'countMatchingNewerThan' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintStablePaging.kt\n"
    "          grep -q 'newerAuditCount = countMatchingNewerThan(criteria, snapshot)' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintStablePaging.kt\n",
    1,
)

v073_docs = '          test -s docs/V0.73_RELEASE_NOTES.md\n'
if v073_docs not in workflow:
    raise SystemExit('V073 docs guard missing')
workflow = workflow.replace(
    v073_docs,
    v073_docs
    + '          test -s docs/V0.74_REPRINT_LEDGER_MATCHING_NEW_ITEMS.md\n'
    + '          test -s docs/V0.74_RELEASE_NOTES.md\n',
    1,
)

summary_marker = '          SALE_RECEIPT_REPRINT_LEGACY_OFFSET_UI=false\n'
if summary_marker not in workflow:
    raise SystemExit('v0.73 summary marker missing')
workflow = workflow.replace(
    summary_marker,
    summary_marker
    + '          SALE_RECEIPT_REPRINT_MATCHING_NEWER_COUNT=true\n'
    + '          SALE_RECEIPT_REPRINT_MATCHING_NEWER_BOUND_ARGS=true\n'
    + '          SALE_RECEIPT_REPRINT_APPLIED_CRITERIA_REFRESH=true\n'
    + '          SALE_RECEIPT_REPRINT_UNAPPLIED_INPUT_ISOLATION=true\n',
    1,
)

real_device_marker = '          REAL_DEVICE_SALE_RECEIPT_REPRINT_STABLE_PAGING_VERIFICATION=required\n'
if real_device_marker not in workflow:
    raise SystemExit('real-device v0.73 marker missing')
workflow = workflow.replace(
    real_device_marker,
    '          REAL_DEVICE_SALE_RECEIPT_REPRINT_MATCHING_NEW_ITEMS_VERIFICATION=required\n' + real_device_marker,
    1,
)
WORKFLOW.write_text(workflow)

# 5) Guard expected final state before committing.
checks = {
    'activity matching label': '条件一致の新着' in ACTIVITY.read_text(),
    'activity refresh applied criteria': 'applyCriteria(appliedCriteria)' in ACTIVITY.read_text(),
    'old indicator removed': '検索再実行で反映' not in ACTIVITY.read_text(),
    'build version': NEW_VERSION in BUILD.read_text() and 'versionCode = 104' in BUILD.read_text(),
    'workflow V074': 'V074SaleReceiptReprintMatchingNewItemsTest.kt' in WORKFLOW.read_text(),
    'workflow artifact': NEW_ARTIFACT in WORKFLOW.read_text(),
    'workflow POS APK': NEW_APK in WORKFLOW.read_text(),
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit('final guards failed: ' + ', '.join(failed))

SCRIPT.unlink()
subprocess.run(['git', 'config', 'user.name', 'github-actions[bot]'], check=True)
subprocess.run(['git', 'config', 'user.email', '41898282+github-actions[bot]@users.noreply.github.com'], check=True)
subprocess.run(['git', 'add', '-A'], check=True)
subprocess.run(['git', 'commit', '-m', 'finalize v0.74 matching newer reprint requests'], check=True)
sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
print(f'FINALIZE_COMMIT={sha}')
subprocess.run(['git', 'push', 'origin', 'HEAD:develop/v0.74'], check=True)
