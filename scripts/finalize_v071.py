from pathlib import Path
import subprocess

ROOT = Path('.')
NORMAL_WORKFLOW_COMMIT = 'f683ddd5301d529631e57753dfedd7783cd812cd'


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

# Version.
path = 'app/build.gradle.kts'
text = read(path)
text = replace_once(text, 'versionCode = 100', 'versionCode = 101', 'versionCode')
text = replace_once(text, 'versionName = "0.70.0-dev.1"', 'versionName = "0.71.0-dev.1"', 'versionName')
write(path, text)

# Advance current-version assertions without removing older feature assertions.
for old, new in [
    ('0.70.0-dev.1', '0.71.0-dev.1'),
    ('versionCode = 100', 'versionCode = 101'),
    ('TSUGUREGI_v0.70.0_dev1_sale_receipt_reprint_database_paging_debug.apk', 'TSUGUREGI_v0.71.0_dev1_sale_receipt_reprint_period_index_debug.apk'),
    ('TSUGUREGI-v0.70.0-dev1-sale-receipt-reprint-database-paging-apks', 'TSUGUREGI-v0.71.0-dev1-sale-receipt-reprint-period-index-apks'),
]:
    replace_all_tests(old, new)

# Restore known-good normal workflow and advance to v0.71.
workflow = subprocess.check_output(
    ['git', 'show', f'{NORMAL_WORKFLOW_COMMIT}:.github/workflows/build-apk.yml'],
    text=True,
)
workflow = workflow.replace('Verify cumulative v0.14-v0.70 sources', 'Verify cumulative v0.14-v0.71 sources')
workflow = workflow.replace("grep -q 'versionCode = 100' app/build.gradle.kts", "grep -q 'versionCode = 101' app/build.gradle.kts")
workflow = workflow.replace("grep -q 'versionName = \"0.70.0-dev.1\"' app/build.gradle.kts", "grep -q 'versionName = \"0.71.0-dev.1\"' app/build.gradle.kts")
workflow = replace_once(
    workflow,
    '          test -s app/src/test/java/jp/co/tenposinfo/register/V070SaleReceiptReprintDatabasePagingTest.kt\n',
    '          test -s app/src/test/java/jp/co/tenposinfo/register/V070SaleReceiptReprintDatabasePagingTest.kt\n'
    '          test -s app/src/test/java/jp/co/tenposinfo/register/V071SaleReceiptReprintPeriodIndexTest.kt\n'
    "          grep -q 'idx_sale_receipt_reprint_requested_time' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintAudit.kt\n"
    "          grep -q 'enum class SaleReceiptReprintLedgerPeriod' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintOperations.kt\n"
    "          grep -Fq 'r.requested_at >= ?' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintOperations.kt\n"
    "          grep -q 'SaleReceiptReprintLedgerPeriod.entries' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt\n"
    "          grep -q '期間DB絞込' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt\n",
    'v071 source checks',
)
workflow = replace_once(
    workflow,
    '          test -s docs/V0.70_DATABASE_PAGING_FOUNDATION.md\n          test -s docs/V0.70_RELEASE_NOTES.md\n',
    '          test -s docs/V0.70_DATABASE_PAGING_FOUNDATION.md\n          test -s docs/V0.70_RELEASE_NOTES.md\n'
    '          test -s docs/V0.71_REPRINT_LEDGER_PERIOD_INDEX.md\n          test -s docs/V0.71_RELEASE_NOTES.md\n',
    'v071 docs',
)
workflow = workflow.replace(
    'TSUGUREGI_v0.70.0_dev1_sale_receipt_reprint_database_paging_debug.apk',
    'TSUGUREGI_v0.71.0_dev1_sale_receipt_reprint_period_index_debug.apk',
)
workflow = workflow.replace('REGISTER_VERSION_NAME=0.70.0-dev.1', 'REGISTER_VERSION_NAME=0.71.0-dev.1')
workflow = workflow.replace('REGISTER_VERSION_CODE=100', 'REGISTER_VERSION_CODE=101')
workflow = workflow.replace(
    'TSUGUREGI-v0.70.0-dev1-sale-receipt-reprint-database-paging-apks',
    'TSUGUREGI-v0.71.0-dev1-sale-receipt-reprint-period-index-apks',
)
workflow = replace_once(
    workflow,
    '          SALE_RECEIPT_REPRINT_DATABASE_RECENT_LIMIT=false\n',
    '          SALE_RECEIPT_REPRINT_DATABASE_RECENT_LIMIT=false\n'
    '          SALE_RECEIPT_REPRINT_PERIOD_FILTER=true\n'
    '          SALE_RECEIPT_REPRINT_PERIOD_BOUND_ARGS=true\n'
    '          SALE_RECEIPT_REPRINT_GLOBAL_TIME_INDEX=true\n'
    '          SALE_RECEIPT_REPRINT_PERIOD_LOCAL_TODAY=true\n',
    'v071 flags',
)
workflow = replace_once(
    workflow,
    '          REAL_DEVICE_SALE_RECEIPT_REPRINT_DATABASE_PAGING_VERIFICATION=required\n',
    '          REAL_DEVICE_SALE_RECEIPT_REPRINT_PERIOD_INDEX_VERIFICATION=required\n'
    '          REAL_DEVICE_SALE_RECEIPT_REPRINT_DATABASE_PAGING_VERIFICATION=required\n',
    'v071 real-device flag',
)
write('.github/workflows/build-apk.yml', workflow)

Path('scripts/finalize_v071.py').unlink()

checks = {
    'app/build.gradle.kts': ['versionCode = 101', 'versionName = "0.71.0-dev.1"'],
    'app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintAudit.kt': ['idx_sale_receipt_reprint_requested_time'],
    'app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintOperations.kt': ['SaleReceiptReprintLedgerPeriod', 'r.requested_at >= ?', 'earliestRequestedAt'],
    'app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt': ['SaleReceiptReprintLedgerPeriod.entries', '期間DB絞込'],
    '.github/workflows/build-apk.yml': ['v0.14-v0.71', 'V071SaleReceiptReprintPeriodIndexTest.kt', 'REGISTER_VERSION_CODE=101', 'SALE_RECEIPT_REPRINT_GLOBAL_TIME_INDEX=true'],
}
for file, needles in checks.items():
    source = read(file)
    for needle in needles:
        if needle not in source:
            raise RuntimeError(f'{file}: missing {needle!r}')

subprocess.run(['git', 'config', 'user.name', 'tsuguregi-ci'], check=True)
subprocess.run(['git', 'config', 'user.email', 'tsuguregi-ci@users.noreply.github.com'], check=True)
subprocess.run(['git', 'add', '-A'], check=True)
subprocess.run(['git', 'commit', '-m', 'finalize v0.71 receipt reprint period index'], check=True)
sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
print(f'FINALIZE_COMMIT={sha}')
subprocess.run(['git', 'status', '--short'], check=True)
subprocess.run(['git', 'push', 'origin', 'HEAD:develop/v0.71'], check=True)
