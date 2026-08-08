from pathlib import Path
import subprocess

ROOT = Path('.')
NORMAL_WORKFLOW_COMMIT = '5e5bd2f734761d5a08170219671efa0962f9c296'


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding='utf-8')


def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text, encoding='utf-8')


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f'{label}: expected exactly 1 occurrence, found {count}')
    return text.replace(old, new, 1)


def replace_all_tests(old: str, new: str) -> int:
    changed = 0
    for base in ['app/src/test', 'management-app/src/test', 'customer-display/src/test']:
        root = ROOT / base
        if not root.exists():
            continue
        for path in root.rglob('*.kt'):
            source = path.read_text(encoding='utf-8')
            updated = source.replace(old, new)
            if updated != source:
                path.write_text(updated, encoding='utf-8')
                changed += 1
    return changed

# App version.
path = 'app/build.gradle.kts'
text = read(path)
text = replace_once(text, 'versionCode = 99', 'versionCode = 100', 'versionCode')
text = replace_once(text, 'versionName = "0.69.0-dev.1"', 'versionName = "0.70.0-dev.1"', 'versionName')
write(path, text)

# Remove one unused import introduced while shaping the DB store.
path = 'app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintOperations.kt'
text = read(path)
text = text.replace('import android.database.sqlite.SQLiteDatabase\n', '')
write(path, text)

# Current-version assertions in cumulative tests.
for old, new in [
    ('0.69.0-dev.1', '0.70.0-dev.1'),
    ('versionCode = 99', 'versionCode = 100'),
    ('TSUGUREGI_v0.69.0_dev1_sale_receipt_reprint_operations_ledger_debug.apk', 'TSUGUREGI_v0.70.0_dev1_sale_receipt_reprint_database_paging_debug.apk'),
    ('TSUGUREGI-v0.69.0-dev1-sale-receipt-reprint-operations-ledger-apks', 'TSUGUREGI-v0.70.0-dev1-sale-receipt-reprint-database-paging-apks'),
]:
    replace_all_tests(old, new)

# Keep final docs focused; temporary planning notes are not release artifacts.
for temporary in [
    'docs/V0.70_IMPLEMENTATION_STATUS.md',
    'docs/V0.70_DATA_SAFETY.md',
    'docs/V0.70_UI_PLAN.md',
    'docs/V0.70_SEARCH_FIELDS.md',
]:
    p = ROOT / temporary
    if p.exists():
        p.unlink()

# Restore the known-good normal v0.69 workflow, then advance it to v0.70.
workflow = subprocess.check_output(
    ['git', 'show', f'{NORMAL_WORKFLOW_COMMIT}:.github/workflows/build-apk.yml'],
    text=True,
)
workflow = workflow.replace('Verify cumulative v0.14-v0.69 sources', 'Verify cumulative v0.14-v0.70 sources')
workflow = workflow.replace("grep -q 'versionCode = 99' app/build.gradle.kts", "grep -q 'versionCode = 100' app/build.gradle.kts")
workflow = workflow.replace("grep -q 'versionName = \"0.69.0-dev.1\"' app/build.gradle.kts", "grep -q 'versionName = \"0.70.0-dev.1\"' app/build.gradle.kts")
workflow = replace_once(
    workflow,
    '          test -s app/src/test/java/jp/co/tenposinfo/register/V069SaleReceiptReprintOperationsLedgerTest.kt\n',
    '          test -s app/src/test/java/jp/co/tenposinfo/register/V069SaleReceiptReprintOperationsLedgerTest.kt\n'
    '          test -s app/src/test/java/jp/co/tenposinfo/register/V070SaleReceiptReprintDatabasePagingTest.kt\n'
    '          test -s app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintOperations.kt\n'
    '          test -s app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt\n'
    "          grep -q 'DATABASE_PAGE_SIZE = 200' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintOperations.kt\n"
    "          grep -Fq 'LIMIT ? OFFSET ?' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintOperations.kt\n"
    "          grep -q 'safePageSize + 1' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintOperations.kt\n"
    "          grep -q 'store.search(appliedCriteria, pageOffset)' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt\n"
    "          grep -q 'SQLite直接検索' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt\n"
    "          grep -q '前へ' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt\n"
    "          grep -q '次へ' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt\n"
    "          ! grep -q 'store.list()' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt\n",
    'v070 source checks',
)
workflow = replace_once(
    workflow,
    '          test -s docs/V0.69_SALE_RECEIPT_REPRINT_OPERATIONS_LEDGER.md\n          test -s docs/V0.69_RELEASE_NOTES.md\n',
    '          test -s docs/V0.69_SALE_RECEIPT_REPRINT_OPERATIONS_LEDGER.md\n          test -s docs/V0.69_RELEASE_NOTES.md\n'
    '          test -s docs/V0.70_DATABASE_PAGING_FOUNDATION.md\n          test -s docs/V0.70_RELEASE_NOTES.md\n',
    'v070 docs',
)
workflow = workflow.replace(
    'TSUGUREGI_v0.69.0_dev1_sale_receipt_reprint_operations_ledger_debug.apk',
    'TSUGUREGI_v0.70.0_dev1_sale_receipt_reprint_database_paging_debug.apk',
)
workflow = workflow.replace('REGISTER_VERSION_NAME=0.69.0-dev.1', 'REGISTER_VERSION_NAME=0.70.0-dev.1')
workflow = workflow.replace('REGISTER_VERSION_CODE=99', 'REGISTER_VERSION_CODE=100')
workflow = workflow.replace(
    'TSUGUREGI-v0.69.0-dev1-sale-receipt-reprint-operations-ledger-apks',
    'TSUGUREGI-v0.70.0-dev1-sale-receipt-reprint-database-paging-apks',
)
workflow = replace_once(
    workflow,
    '          SALE_RECEIPT_REPRINT_LEDGER_VIEW_SALES_RECHECK=true\n',
    '          SALE_RECEIPT_REPRINT_LEDGER_VIEW_SALES_RECHECK=true\n'
    '          SALE_RECEIPT_REPRINT_DATABASE_SEARCH=true\n'
    '          SALE_RECEIPT_REPRINT_DATABASE_PAGE_SIZE=200\n'
    '          SALE_RECEIPT_REPRINT_DATABASE_BOUND_ARGS=true\n'
    '          SALE_RECEIPT_REPRINT_DATABASE_LIKE_ESCAPE=true\n'
    '          SALE_RECEIPT_REPRINT_DATABASE_RECENT_LIMIT=false\n',
    'v070 build flags',
)
workflow = replace_once(
    workflow,
    '          REAL_DEVICE_SALE_RECEIPT_REPRINT_LEDGER_VERIFICATION=required\n',
    '          REAL_DEVICE_SALE_RECEIPT_REPRINT_DATABASE_PAGING_VERIFICATION=required\n'
    '          REAL_DEVICE_SALE_RECEIPT_REPRINT_LEDGER_VERIFICATION=required\n',
    'v070 real-device flag',
)
write('.github/workflows/build-apk.yml', workflow)

# Finalizer itself must not remain in final branch.
Path('scripts/finalize_v070.py').unlink()

# Guard final state before commit.
checks = {
    'app/build.gradle.kts': ['versionCode = 100', 'versionName = "0.70.0-dev.1"'],
    'app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintOperations.kt': [
        'DATABASE_PAGE_SIZE = 200', 'LIMIT ? OFFSET ?', 'safePageSize + 1', 'SELECT COUNT(*)', 'escapeLike'
    ],
    'app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt': [
        'store.search(appliedCriteria, pageOffset)', 'SQLite直接検索', '前へ', '次へ'
    ],
    '.github/workflows/build-apk.yml': [
        'v0.14-v0.70', 'V070SaleReceiptReprintDatabasePagingTest.kt',
        'REGISTER_VERSION_CODE=100', 'SALE_RECEIPT_REPRINT_DATABASE_SEARCH=true'
    ],
}
for file, needles in checks.items():
    source = read(file)
    for needle in needles:
        if needle not in source:
            raise RuntimeError(f'{file}: missing {needle!r}')
if 'LOAD_LIMIT' in read('app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintOperations.kt'):
    raise RuntimeError('LOAD_LIMIT remained in v0.70 store')
if 'store.list()' in read('app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt'):
    raise RuntimeError('SCR-648 still uses fixed list loading')

subprocess.run(['git', 'config', 'user.name', 'tsuguregi-ci'], check=True)
subprocess.run(['git', 'config', 'user.email', 'tsuguregi-ci@users.noreply.github.com'], check=True)
subprocess.run(['git', 'add', '-A'], check=True)
subprocess.run(['git', 'commit', '-m', 'finalize v0.70 receipt reprint database paging'], check=True)
sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
print(f'FINALIZE_COMMIT={sha}')
subprocess.run(['git', 'status', '--short'], check=True)
subprocess.run(['git', 'push', 'origin', 'HEAD:develop/v0.70'], check=True)
