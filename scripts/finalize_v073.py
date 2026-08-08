from pathlib import Path
import subprocess

ROOT = Path('.')
NORMAL_WORKFLOW_COMMIT = '599991e7317daf444e81678271f3b9c8bfe00369'


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding='utf-8')


def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text, encoding='utf-8')


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f'{label}: expected exactly 1 occurrence, found {count}')
    return text.replace(old, new, 1)


def replace_all_tests(old: str, new: str) -> None:
    for base in ['app/src/test', 'management-app/src/test', 'customer-display/src/test']:
        root = ROOT / base
        if not root.exists():
            continue
        for path in root.rglob('*.kt'):
            source = path.read_text(encoding='utf-8')
            updated = source.replace(old, new)
            if updated != source:
                path.write_text(updated, encoding='utf-8')

# Version.
path = 'app/build.gradle.kts'
text = read(path)
text = replace_once(text, 'versionCode = 102', 'versionCode = 103', 'versionCode')
text = replace_once(text, 'versionName = "0.72.0-dev.1"', 'versionName = "0.73.0-dev.1"', 'versionName')
write(path, text)

# Advance current-version assertions only; older feature assertions remain.
for old, new in [
    ('0.72.0-dev.1', '0.73.0-dev.1'),
    ('versionCode = 102', 'versionCode = 103'),
    ('TSUGUREGI_v0.72.0_dev1_sale_receipt_reprint_custom_range_debug.apk', 'TSUGUREGI_v0.73.0_dev1_sale_receipt_reprint_stable_paging_debug.apk'),
    ('TSUGUREGI-v0.72.0-dev1-sale-receipt-reprint-custom-range-apks', 'TSUGUREGI-v0.73.0-dev1-sale-receipt-reprint-stable-paging-apks'),
]:
    replace_all_tests(old, new)

# Restore normal v0.72 workflow and advance cumulatively.
workflow = subprocess.check_output(
    ['git', 'show', f'{NORMAL_WORKFLOW_COMMIT}:.github/workflows/build-apk.yml'],
    text=True,
)
workflow = workflow.replace('Verify cumulative v0.14-v0.72 sources', 'Verify cumulative v0.14-v0.73 sources')
workflow = workflow.replace("grep -q 'versionCode = 102' app/build.gradle.kts", "grep -q 'versionCode = 103' app/build.gradle.kts")
workflow = workflow.replace("grep -q 'versionName = \"0.72.0-dev.1\"' app/build.gradle.kts", "grep -q 'versionName = \"0.73.0-dev.1\"' app/build.gradle.kts")
workflow = replace_once(
    workflow,
    '          test -s app/src/test/java/jp/co/tenposinfo/register/V072SaleReceiptReprintCustomRangeTest.kt\n',
    '          test -s app/src/test/java/jp/co/tenposinfo/register/V072SaleReceiptReprintCustomRangeTest.kt\n'
    '          test -s app/src/test/java/jp/co/tenposinfo/register/V073SaleReceiptReprintStablePagingTest.kt\n'
    '          test -s app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintStablePaging.kt\n'
    "          grep -q 'captureSnapshot' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintStablePaging.kt\n"
    "          grep -q 'searchStable' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintStablePaging.kt\n"
    "          grep -Fq 'r.id <= ?' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintStablePaging.kt\n"
    "          grep -Fq 'r.id < ?' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintStablePaging.kt\n"
    "          ! grep -Fq 'OFFSET ?' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintStablePaging.kt\n"
    "          grep -q 'SaleReceiptReprintStablePagingStore' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt\n"
    "          grep -q '検索時点固定' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt\n"
    "          grep -q '新しい再印字要求' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt\n"
    "          ! grep -q 'pageOffset' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt\n",
    'v073 source checks',
)
workflow = replace_once(
    workflow,
    '          test -s docs/V0.72_REPRINT_LEDGER_CUSTOM_RANGE.md\n          test -s docs/V0.72_RELEASE_NOTES.md\n',
    '          test -s docs/V0.72_REPRINT_LEDGER_CUSTOM_RANGE.md\n          test -s docs/V0.72_RELEASE_NOTES.md\n'
    '          test -s docs/V0.73_REPRINT_LEDGER_STABLE_PAGING.md\n          test -s docs/V0.73_RELEASE_NOTES.md\n',
    'v073 docs',
)
workflow = workflow.replace(
    'TSUGUREGI_v0.72.0_dev1_sale_receipt_reprint_custom_range_debug.apk',
    'TSUGUREGI_v0.73.0_dev1_sale_receipt_reprint_stable_paging_debug.apk',
)
workflow = workflow.replace('REGISTER_VERSION_NAME=0.72.0-dev.1', 'REGISTER_VERSION_NAME=0.73.0-dev.1')
workflow = workflow.replace('REGISTER_VERSION_CODE=102', 'REGISTER_VERSION_CODE=103')
workflow = workflow.replace(
    'TSUGUREGI-v0.72.0-dev1-sale-receipt-reprint-custom-range-apks',
    'TSUGUREGI-v0.73.0-dev1-sale-receipt-reprint-stable-paging-apks',
)
workflow = replace_once(
    workflow,
    '          SALE_RECEIPT_REPRINT_CUSTOM_RANGE_FAIL_CLOSED=true\n',
    '          SALE_RECEIPT_REPRINT_CUSTOM_RANGE_FAIL_CLOSED=true\n'
    '          SALE_RECEIPT_REPRINT_STABLE_SNAPSHOT=true\n'
    '          SALE_RECEIPT_REPRINT_KEYSET_PAGING=true\n'
    '          SALE_RECEIPT_REPRINT_SAME_MILLIS_ID_TIEBREAK=true\n'
    '          SALE_RECEIPT_REPRINT_PAGE_NEW_REQUEST_ISOLATION=true\n'
    '          SALE_RECEIPT_REPRINT_NEWER_REQUEST_INDICATOR=true\n'
    '          SALE_RECEIPT_REPRINT_LEGACY_OFFSET_UI=false\n',
    'v073 flags',
)
workflow = replace_once(
    workflow,
    '          REAL_DEVICE_SALE_RECEIPT_REPRINT_CUSTOM_RANGE_VERIFICATION=required\n',
    '          REAL_DEVICE_SALE_RECEIPT_REPRINT_STABLE_PAGING_VERIFICATION=required\n'
    '          REAL_DEVICE_SALE_RECEIPT_REPRINT_CUSTOM_RANGE_VERIFICATION=required\n',
    'v073 real-device flag',
)
write('.github/workflows/build-apk.yml', workflow)

Path('scripts/finalize_v073.py').unlink()

checks = {
    'app/build.gradle.kts': ['versionCode = 103', 'versionName = "0.73.0-dev.1"'],
    'app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintStablePaging.kt': ['captureSnapshot', 'searchStable', 'r.id <= ?', 'r.id < ?', 'countNewerThan'],
    'app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt': ['SaleReceiptReprintStablePagingStore', '検索時点固定', '新しい再印字要求', 'cursorHistory'],
    '.github/workflows/build-apk.yml': ['v0.14-v0.73', 'V073SaleReceiptReprintStablePagingTest.kt', 'REGISTER_VERSION_CODE=103', 'SALE_RECEIPT_REPRINT_KEYSET_PAGING=true'],
}
for file, needles in checks.items():
    source = read(file)
    for needle in needles:
        if needle not in source:
            raise RuntimeError(f'{file}: missing {needle!r}')
if 'pageOffset' in read('app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt'):
    raise RuntimeError('SCR-648 still contains OFFSET paging state')
if 'OFFSET ?' in read('app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintStablePaging.kt'):
    raise RuntimeError('stable paging store contains OFFSET')

subprocess.run(['git', 'config', 'user.name', 'tsuguregi-ci'], check=True)
subprocess.run(['git', 'config', 'user.email', 'tsuguregi-ci@users.noreply.github.com'], check=True)
subprocess.run(['git', 'add', '-A'], check=True)
subprocess.run(['git', 'commit', '-m', 'finalize v0.73 stable receipt reprint paging'], check=True)
sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
print(f'FINALIZE_COMMIT={sha}')
subprocess.run(['git', 'status', '--short'], check=True)
subprocess.run(['git', 'push', 'origin', 'HEAD:develop/v0.73'], check=True)
