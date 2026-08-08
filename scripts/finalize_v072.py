from pathlib import Path
import subprocess

ROOT = Path('.')
NORMAL_WORKFLOW_COMMIT = 'fdffb4016a89ded2e80aa49dc92782126c22f094'


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
text = replace_once(text, 'versionCode = 101', 'versionCode = 102', 'versionCode')
text = replace_once(text, 'versionName = "0.71.0-dev.1"', 'versionName = "0.72.0-dev.1"', 'versionName')
write(path, text)

# SCR-648 custom range UI, applied only after validation succeeds.
path = 'app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt'
text = read(path)
text = replace_once(
    text,
    '''    var filter by remember { mutableStateOf(SaleReceiptReprintLedgerFilter.ALL) }
    var period by remember { mutableStateOf(SaleReceiptReprintLedgerPeriod.ALL) }
    var query by remember { mutableStateOf("") }
    var appliedCriteria by remember { mutableStateOf(SaleReceiptReprintLedgerCriteria()) }
''',
    '''    var filter by remember { mutableStateOf(SaleReceiptReprintLedgerFilter.ALL) }
    var period by remember { mutableStateOf(SaleReceiptReprintLedgerPeriod.ALL) }
    var query by remember { mutableStateOf("") }
    var customStartDate by remember { mutableStateOf("") }
    var customEndDate by remember { mutableStateOf("") }
    var customRange by remember { mutableStateOf<SaleReceiptReprintCustomRange?>(null) }
    var dateError by remember { mutableStateOf<String?>(null) }
    var appliedCriteria by remember { mutableStateOf(SaleReceiptReprintLedgerCriteria()) }
''',
    'custom states',
)
text = replace_once(
    text,
    '''            Button(
                onClick = {
                    appliedCriteria = SaleReceiptReprintLedgerCriteria(filter = filter, period = period, query = query)
                    pageOffset = 0
                    selectedId = null
                },
                colors = ButtonDefaults.buttonColors(containerColor = ReprintLedgerBlue),
            ) { Text("検索") }
''',
    '''            Button(
                onClick = {
                    if (period == SaleReceiptReprintLedgerPeriod.CUSTOM) {
                        runCatching {
                            SaleReceiptReprintLedgerPolicy.parseCustomRange(customStartDate, customEndDate)
                        }.onSuccess { range ->
                            customRange = range
                            appliedCriteria = SaleReceiptReprintLedgerCriteria(
                                filter = filter,
                                period = SaleReceiptReprintLedgerPeriod.CUSTOM,
                                customStartInclusive = range.startInclusive,
                                customEndExclusive = range.endExclusive,
                                query = query,
                            )
                            dateError = null
                            pageOffset = 0
                            selectedId = null
                        }.onFailure { error ->
                            dateError = error.message ?: "任意期間を確認してください"
                        }
                    } else {
                        appliedCriteria = SaleReceiptReprintLedgerCriteria(filter = filter, period = period, query = query)
                        dateError = null
                        pageOffset = 0
                        selectedId = null
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = ReprintLedgerBlue),
            ) { Text("検索") }
''',
    'search custom validation',
)
text = replace_once(
    text,
    '''                    query = ""
                    filter = SaleReceiptReprintLedgerFilter.ALL
                    period = SaleReceiptReprintLedgerPeriod.ALL
                    appliedCriteria = SaleReceiptReprintLedgerCriteria()
''',
    '''                    query = ""
                    filter = SaleReceiptReprintLedgerFilter.ALL
                    period = SaleReceiptReprintLedgerPeriod.ALL
                    customStartDate = ""
                    customEndDate = ""
                    customRange = null
                    dateError = null
                    appliedCriteria = SaleReceiptReprintLedgerCriteria()
''',
    'clear custom state',
)
text = replace_once(
    text,
    '''                    filter = item
                    appliedCriteria = SaleReceiptReprintLedgerCriteria(filter = item, period = period, query = query)
                    pageOffset = 0
''',
    '''                    filter = item
                    appliedCriteria = SaleReceiptReprintLedgerCriteria(
                        filter = item,
                        period = period,
                        customStartInclusive = if (period == SaleReceiptReprintLedgerPeriod.CUSTOM) customRange?.startInclusive else null,
                        customEndExclusive = if (period == SaleReceiptReprintLedgerPeriod.CUSTOM) customRange?.endExclusive else null,
                        query = query,
                    )
                    pageOffset = 0
''',
    'status preserves custom range',
)
text = replace_once(
    text,
    '            SaleReceiptReprintLedgerPeriod.entries.forEach { item ->\n',
    '            SaleReceiptReprintLedgerPeriod.entries.filter { it != SaleReceiptReprintLedgerPeriod.CUSTOM }.forEach { item ->\n',
    'fixed period buttons exclude custom',
)
text = replace_once(
    text,
    '''                    period = item
                    appliedCriteria = SaleReceiptReprintLedgerCriteria(filter = filter, period = item, query = query)
                    pageOffset = 0
''',
    '''                    period = item
                    customRange = null
                    dateError = null
                    appliedCriteria = SaleReceiptReprintLedgerCriteria(filter = filter, period = item, query = query)
                    pageOffset = 0
''',
    'fixed period clears active custom range',
)
text = replace_once(
    text,
    '''            Text("期間変更時は先頭ページへ戻ります / 5秒更新は条件・ページを維持", color = Color.Gray, fontSize = 12.sp)
        }

        Row(
            Modifier.weight(1f).fillMaxWidth().padding(14.dp),
''',
    '''            Text("期間変更時は先頭ページへ戻ります / 5秒更新は条件・ページを維持", color = Color.Gray, fontSize = 12.sp)
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("任意期間", color = Color.Gray, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = customStartDate,
                onValueChange = { customStartDate = it.take(10); dateError = null },
                label = { Text("開始日 yyyy/MM/dd") },
                singleLine = true,
                modifier = Modifier.width(170.dp),
            )
            Text("～", color = Color.Gray)
            OutlinedTextField(
                value = customEndDate,
                onValueChange = { customEndDate = it.take(10); dateError = null },
                label = { Text("終了日 yyyy/MM/dd") },
                singleLine = true,
                modifier = Modifier.width(170.dp),
            )
            val applyCustomRange = {
                runCatching {
                    SaleReceiptReprintLedgerPolicy.parseCustomRange(customStartDate, customEndDate)
                }.onSuccess { range ->
                    customRange = range
                    period = SaleReceiptReprintLedgerPeriod.CUSTOM
                    appliedCriteria = SaleReceiptReprintLedgerCriteria(
                        filter = filter,
                        period = SaleReceiptReprintLedgerPeriod.CUSTOM,
                        customStartInclusive = range.startInclusive,
                        customEndExclusive = range.endExclusive,
                        query = query,
                    )
                    dateError = null
                    pageOffset = 0
                    selectedId = null
                }.onFailure { error ->
                    dateError = error.message ?: "任意期間を確認してください"
                }
            }
            if (period == SaleReceiptReprintLedgerPeriod.CUSTOM) {
                Button(
                    onClick = applyCustomRange,
                    colors = ButtonDefaults.buttonColors(containerColor = ReprintLedgerBlue),
                ) { Text("任意期間を適用") }
            } else {
                OutlinedButton(onClick = applyCustomRange) { Text("任意期間を適用") }
            }
            dateError?.let { error ->
                Text(error, color = ReprintLedgerDanger, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            Text("開始/終了どちらか片方だけでも指定可", color = Color.Gray, fontSize = 12.sp)
        }

        Row(
            Modifier.weight(1f).fillMaxWidth().padding(14.dp),
''',
    'custom range input row',
)
write(path, text)

# Advance cumulative current-version assertions.
for old, new in [
    ('0.71.0-dev.1', '0.72.0-dev.1'),
    ('versionCode = 101', 'versionCode = 102'),
    ('TSUGUREGI_v0.71.0_dev1_sale_receipt_reprint_period_index_debug.apk', 'TSUGUREGI_v0.72.0_dev1_sale_receipt_reprint_custom_range_debug.apk'),
    ('TSUGUREGI-v0.71.0-dev1-sale-receipt-reprint-period-index-apks', 'TSUGUREGI-v0.72.0-dev1-sale-receipt-reprint-custom-range-apks'),
]:
    replace_all_tests(old, new)

# Normal v0.72 workflow.
workflow = subprocess.check_output(
    ['git', 'show', f'{NORMAL_WORKFLOW_COMMIT}:.github/workflows/build-apk.yml'],
    text=True,
)
workflow = workflow.replace('Verify cumulative v0.14-v0.71 sources', 'Verify cumulative v0.14-v0.72 sources')
workflow = workflow.replace("grep -q 'versionCode = 101' app/build.gradle.kts", "grep -q 'versionCode = 102' app/build.gradle.kts")
workflow = workflow.replace("grep -q 'versionName = \"0.71.0-dev.1\"' app/build.gradle.kts", "grep -q 'versionName = \"0.72.0-dev.1\"' app/build.gradle.kts")
workflow = replace_once(
    workflow,
    '          test -s app/src/test/java/jp/co/tenposinfo/register/V071SaleReceiptReprintPeriodIndexTest.kt\n',
    '          test -s app/src/test/java/jp/co/tenposinfo/register/V071SaleReceiptReprintPeriodIndexTest.kt\n'
    '          test -s app/src/test/java/jp/co/tenposinfo/register/V072SaleReceiptReprintCustomRangeTest.kt\n'
    "          grep -q 'CUSTOM(\"任意期間\")' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintOperations.kt\n"
    "          grep -q 'parseCustomRange' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintOperations.kt\n"
    "          grep -Fq 'r.requested_at < ?' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintOperations.kt\n"
    "          grep -q '開始日 yyyy/MM/dd' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt\n"
    "          grep -q '終了日 yyyy/MM/dd' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt\n"
    "          grep -q '任意期間を適用' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt\n"
    "          grep -q 'dateError' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt\n",
    'v072 source checks',
)
workflow = replace_once(
    workflow,
    '          test -s docs/V0.71_REPRINT_LEDGER_PERIOD_INDEX.md\n          test -s docs/V0.71_RELEASE_NOTES.md\n',
    '          test -s docs/V0.71_REPRINT_LEDGER_PERIOD_INDEX.md\n          test -s docs/V0.71_RELEASE_NOTES.md\n'
    '          test -s docs/V0.72_REPRINT_LEDGER_CUSTOM_RANGE.md\n          test -s docs/V0.72_RELEASE_NOTES.md\n',
    'v072 docs',
)
workflow = workflow.replace(
    'TSUGUREGI_v0.71.0_dev1_sale_receipt_reprint_period_index_debug.apk',
    'TSUGUREGI_v0.72.0_dev1_sale_receipt_reprint_custom_range_debug.apk',
)
workflow = workflow.replace('REGISTER_VERSION_NAME=0.71.0-dev.1', 'REGISTER_VERSION_NAME=0.72.0-dev.1')
workflow = workflow.replace('REGISTER_VERSION_CODE=101', 'REGISTER_VERSION_CODE=102')
workflow = workflow.replace(
    'TSUGUREGI-v0.71.0-dev1-sale-receipt-reprint-period-index-apks',
    'TSUGUREGI-v0.72.0-dev1-sale-receipt-reprint-custom-range-apks',
)
workflow = replace_once(
    workflow,
    '          SALE_RECEIPT_REPRINT_PERIOD_LOCAL_TODAY=true\n',
    '          SALE_RECEIPT_REPRINT_PERIOD_LOCAL_TODAY=true\n'
    '          SALE_RECEIPT_REPRINT_CUSTOM_RANGE=true\n'
    '          SALE_RECEIPT_REPRINT_CUSTOM_RANGE_BOUND_ARGS=true\n'
    '          SALE_RECEIPT_REPRINT_CUSTOM_RANGE_EXCLUSIVE_END=true\n'
    '          SALE_RECEIPT_REPRINT_CUSTOM_RANGE_ONE_SIDED=true\n'
    '          SALE_RECEIPT_REPRINT_CUSTOM_RANGE_FAIL_CLOSED=true\n',
    'v072 flags',
)
workflow = replace_once(
    workflow,
    '          REAL_DEVICE_SALE_RECEIPT_REPRINT_PERIOD_INDEX_VERIFICATION=required\n',
    '          REAL_DEVICE_SALE_RECEIPT_REPRINT_CUSTOM_RANGE_VERIFICATION=required\n'
    '          REAL_DEVICE_SALE_RECEIPT_REPRINT_PERIOD_INDEX_VERIFICATION=required\n',
    'v072 real device flag',
)
write('.github/workflows/build-apk.yml', workflow)

Path('scripts/finalize_v072.py').unlink()

checks = {
    'app/build.gradle.kts': ['versionCode = 102', 'versionName = "0.72.0-dev.1"'],
    'app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintOperations.kt': ['CUSTOM("任意期間")', 'parseCustomRange', 'r.requested_at < ?', 'endDate?.plusDays(1)'],
    'app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt': ['開始日 yyyy/MM/dd', '終了日 yyyy/MM/dd', '任意期間を適用', 'dateError', 'parseCustomRange'],
    '.github/workflows/build-apk.yml': ['v0.14-v0.72', 'V072SaleReceiptReprintCustomRangeTest.kt', 'REGISTER_VERSION_CODE=102', 'SALE_RECEIPT_REPRINT_CUSTOM_RANGE=true'],
}
for file, needles in checks.items():
    source = read(file)
    for needle in needles:
        if needle not in source:
            raise RuntimeError(f'{file}: missing {needle!r}')

subprocess.run(['git', 'config', 'user.name', 'tsuguregi-ci'], check=True)
subprocess.run(['git', 'config', 'user.email', 'tsuguregi-ci@users.noreply.github.com'], check=True)
subprocess.run(['git', 'add', '-A'], check=True)
subprocess.run(['git', 'commit', '-m', 'finalize v0.72 receipt reprint custom range'], check=True)
sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
print(f'FINALIZE_COMMIT={sha}')
subprocess.run(['git', 'status', '--short'], check=True)
subprocess.run(['git', 'push', 'origin', 'HEAD:develop/v0.72'], check=True)
