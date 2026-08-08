from pathlib import Path
import subprocess

WORKFLOW = Path('.github/workflows/build-apk.yml')
SCRIPT = Path('scripts/finalize_v075.py')
ACTIVITY = Path('app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt')
BUILD = Path('app/build.gradle.kts')

OLD_VERSION = '0.74.0-dev.1'
NEW_VERSION = '0.75.0-dev.1'
OLD_APK = 'TSUGUREGI_v0.74.0_dev1_sale_receipt_reprint_matching_new_items_debug.apk'
NEW_APK = 'TSUGUREGI_v0.75.0_dev1_sale_receipt_reprint_csv_export_debug.apk'
OLD_ARTIFACT = 'TSUGUREGI-v0.74.0-dev1-sale-receipt-reprint-matching-new-items-apks'
NEW_ARTIFACT = 'TSUGUREGI-v0.75.0-dev1-sale-receipt-reprint-csv-export-apks'


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 occurrence, got {count}')
    return text.replace(old, new, 1)

# 1) SCR-648: SAF CSV export of frozen applied criteria + snapshot.
activity = ACTIVITY.read_text()
activity = replace_once(
    activity,
    'import androidx.activity.compose.setContent\n',
    'import androidx.activity.compose.setContent\nimport androidx.activity.compose.rememberLauncherForActivityResult\nimport androidx.activity.result.contract.ActivityResultContracts\n',
    'activity result imports',
)
activity = replace_once(
    activity,
    'import androidx.compose.runtime.remember\n',
    'import androidx.compose.runtime.remember\nimport androidx.compose.runtime.rememberCoroutineScope\n',
    'coroutine scope import',
)
activity = replace_once(
    activity,
    'import java.util.Locale\n',
    'import java.util.Locale\nimport kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.launch\nimport kotlinx.coroutines.withContext\n',
    'coroutine imports',
)

old_state = '''    var cursorHistory by remember { mutableStateOf<List<SaleReceiptReprintLedgerCursor?>>(emptyList()) }
    var selectedId by remember { mutableStateOf<Long?>(null) }

    fun applyCriteria(criteria: SaleReceiptReprintLedgerCriteria) {
'''
new_state = '''    var cursorHistory by remember { mutableStateOf<List<SaleReceiptReprintLedgerCursor?>>(emptyList()) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    val context = LocalContext.current
    val exportScope = rememberCoroutineScope()
    var pendingExportCriteria by remember { mutableStateOf<SaleReceiptReprintLedgerCriteria?>(null) }
    var pendingExportSnapshot by remember { mutableStateOf<SaleReceiptReprintLedgerSnapshot?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    val csvExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        val criteriaToExport = pendingExportCriteria
        val snapshotToExport = pendingExportSnapshot
        pendingExportCriteria = null
        pendingExportSnapshot = null
        if (uri != null) {
            val currentOperator = OperatorSessionRegistry.current(context.applicationContext)
            if (criteriaToExport == null || snapshotToExport == null) {
                exportMessage = "CSV出力条件を取得できませんでした"
            } else if (currentOperator == null || !currentOperator.allows(RegisterPermission.VIEW_SALES)) {
                exportMessage = "売上参照権限が失効したためCSV出力を中止しました"
            } else {
                exportScope.launch {
                    val exportResult: Result<Int> = withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                                val exporter = SaleReceiptReprintCsvExporter(context.applicationContext)
                                try {
                                    exporter.exportSnapshot(criteriaToExport, snapshotToExport, output)
                                } finally {
                                    exporter.close()
                                }
                            } ?: error("CSV保存先を開けませんでした")
                        }
                    }
                    exportMessage = exportResult.fold(
                        onSuccess = { count -> "CSV出力完了: ${count}件" },
                        onFailure = { error -> "CSV出力失敗: ${error.message ?: "書き込みエラー"}" },
                    )
                }
            }
        }
        Unit
    }

    fun applyCriteria(criteria: SaleReceiptReprintLedgerCriteria) {
'''
activity = replace_once(activity, old_state, new_state, 'CSV launcher state')

old_buttons = '''            OutlinedButton(
                enabled = cursorHistory.isNotEmpty(),
                onClick = {
'''
new_buttons = '''            OutlinedButton(
                enabled = snapshot != null,
                onClick = {
                    val snapshotToExport = snapshot
                    if (snapshotToExport != null) {
                        pendingExportCriteria = appliedCriteria
                        pendingExportSnapshot = snapshotToExport
                        exportMessage = null
                        csvExportLauncher.launch(SaleReceiptReprintCsvPolicy.fileName())
                    }
                },
            ) { Text("CSV出力") }
            exportMessage?.let { message ->
                Text(message, color = Color.Gray, fontSize = 12.sp, maxLines = 1)
            }
            OutlinedButton(
                enabled = cursorHistory.isNotEmpty(),
                onClick = {
'''
activity = replace_once(activity, old_buttons, new_buttons, 'CSV button')
ACTIVITY.write_text(activity)

# 2) App version.
build = BUILD.read_text()
build = replace_once(build, 'versionCode = 104', 'versionCode = 105', 'app versionCode')
build = replace_once(build, 'versionName = "0.74.0-dev.1"', 'versionName = "0.75.0-dev.1"', 'app versionName')
BUILD.write_text(build)

# 3) Current-version references in cumulative tests.
for base in [Path('app/src/test'), Path('management-app/src/test'), Path('customer-display/src/test')]:
    if not base.exists():
        continue
    for path in base.rglob('*.kt'):
        text = path.read_text()
        updated = text.replace(OLD_VERSION, NEW_VERSION)
        updated = updated.replace('versionCode = 104', 'versionCode = 105')
        updated = updated.replace(OLD_APK, NEW_APK)
        updated = updated.replace(OLD_ARTIFACT, NEW_ARTIFACT)
        if updated != text:
            path.write_text(updated)

# 4) Restore normal v0.74 workflow from parent commit and advance cumulatively.
workflow = subprocess.check_output(['git', 'show', 'HEAD^:.github/workflows/build-apk.yml'], text=True)
workflow = replace_once(workflow, 'Verify cumulative v0.14-v0.74 sources', 'Verify cumulative v0.14-v0.75 sources', 'workflow title')
workflow = replace_once(workflow, "grep -q 'versionCode = 104' app/build.gradle.kts", "grep -q 'versionCode = 105' app/build.gradle.kts", 'workflow versionCode')
workflow = replace_once(workflow, "grep -q 'versionName = \"0.74.0-dev.1\"' app/build.gradle.kts", "grep -q 'versionName = \"0.75.0-dev.1\"' app/build.gradle.kts", 'workflow versionName')
workflow = workflow.replace(OLD_APK, NEW_APK)
workflow = workflow.replace(OLD_ARTIFACT, NEW_ARTIFACT)
workflow = replace_once(workflow, 'REGISTER_VERSION_NAME=0.74.0-dev.1', 'REGISTER_VERSION_NAME=0.75.0-dev.1', 'summary versionName')
workflow = replace_once(workflow, 'REGISTER_VERSION_CODE=104', 'REGISTER_VERSION_CODE=105', 'summary versionCode')

v074_test = '          test -s app/src/test/java/jp/co/tenposinfo/register/V074SaleReceiptReprintMatchingNewItemsTest.kt\n'
if v074_test not in workflow:
    raise SystemExit('V074 test guard missing')
workflow = workflow.replace(
    v074_test,
    v074_test + '          test -s app/src/test/java/jp/co/tenposinfo/register/V075SaleReceiptReprintCsvExportTest.kt\n',
    1,
)

v074_guard = "          grep -q 'newerAuditCount = countMatchingNewerThan(criteria, snapshot)' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintStablePaging.kt\n"
if v074_guard not in workflow:
    raise SystemExit('V074 static guard missing')
workflow = workflow.replace(
    v074_guard,
    v074_guard
    + "          test -s app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintCsvExport.kt\n"
    + "          grep -q 'ActivityResultContracts.CreateDocument(\"text/csv\")' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt\n"
    + "          grep -q 'pendingExportCriteria = appliedCriteria' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt\n"
    + "          grep -q 'pendingExportSnapshot = snapshotToExport' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt\n"
    + "          grep -q 'Dispatchers.IO' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt\n"
    + "          grep -q 'CSV出力' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt\n"
    + "          grep -q 'SaleReceiptReprintLedgerPolicy.buildDatabaseQuery(criteria)' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintCsvExport.kt\n"
    + "          grep -q 'appendSnapshotBound(base, snapshot)' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintCsvExport.kt\n"
    + "          grep -q 'safeText' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintCsvExport.kt\n"
    + "          ! grep -q 'UPDATE sale_receipt_reprint_requests' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintCsvExport.kt\n"
    + "          ! grep -q 'DELETE FROM sale_receipt_reprint_requests' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintCsvExport.kt\n",
    1,
)

v074_docs = '          test -s docs/V0.74_RELEASE_NOTES.md\n'
if v074_docs not in workflow:
    raise SystemExit('V074 docs guard missing')
workflow = workflow.replace(
    v074_docs,
    v074_docs
    + '          test -s docs/V0.75_REPRINT_LEDGER_CSV_EXPORT.md\n'
    + '          test -s docs/V0.75_RELEASE_NOTES.md\n',
    1,
)

summary_marker = '          SALE_RECEIPT_REPRINT_UNAPPLIED_INPUT_ISOLATION=true\n'
if summary_marker not in workflow:
    raise SystemExit('v0.74 summary marker missing')
workflow = workflow.replace(
    summary_marker,
    summary_marker
    + '          SALE_RECEIPT_REPRINT_CSV_EXPORT=true\n'
    + '          SALE_RECEIPT_REPRINT_CSV_SNAPSHOT_BOUND=true\n'
    + '          SALE_RECEIPT_REPRINT_CSV_STREAMING=true\n'
    + '          SALE_RECEIPT_REPRINT_CSV_FORMULA_INJECTION_GUARD=true\n'
    + '          SALE_RECEIPT_REPRINT_CSV_SAF=true\n'
    + '          SALE_RECEIPT_REPRINT_CSV_VIEW_SALES_RECHECK=true\n',
    1,
)
real_device_marker = '          REAL_DEVICE_SALE_RECEIPT_REPRINT_MATCHING_NEW_ITEMS_VERIFICATION=required\n'
if real_device_marker not in workflow:
    raise SystemExit('real-device v0.74 marker missing')
workflow = workflow.replace(
    real_device_marker,
    '          REAL_DEVICE_SALE_RECEIPT_REPRINT_CSV_EXPORT_VERIFICATION=required\n' + real_device_marker,
    1,
)
WORKFLOW.write_text(workflow)

# 5) Guard final state.
checks = {
    'SAF launcher': 'ActivityResultContracts.CreateDocument("text/csv")' in ACTIVITY.read_text(),
    'criteria frozen': 'pendingExportCriteria = appliedCriteria' in ACTIVITY.read_text(),
    'snapshot frozen': 'pendingExportSnapshot = snapshotToExport' in ACTIVITY.read_text(),
    'permission recheck': 'currentOperator.allows(RegisterPermission.VIEW_SALES)' in ACTIVITY.read_text(),
    'IO dispatcher': 'Dispatchers.IO' in ACTIVITY.read_text(),
    'version': NEW_VERSION in BUILD.read_text() and 'versionCode = 105' in BUILD.read_text(),
    'V075 workflow': 'V075SaleReceiptReprintCsvExportTest.kt' in WORKFLOW.read_text(),
    'artifact': NEW_ARTIFACT in WORKFLOW.read_text(),
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit('final guards failed: ' + ', '.join(failed))

SCRIPT.unlink()
subprocess.run(['git', 'config', 'user.name', 'github-actions[bot]'], check=True)
subprocess.run(['git', 'config', 'user.email', '41898282+github-actions[bot]@users.noreply.github.com'], check=True)
subprocess.run(['git', 'add', '-A'], check=True)
subprocess.run(['git', 'commit', '-m', 'finalize v0.75 reprint ledger CSV export'], check=True)
sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
print(f'FINALIZE_COMMIT={sha}')
subprocess.run(['git', 'push', 'origin', 'HEAD:develop/v0.75'], check=True)
