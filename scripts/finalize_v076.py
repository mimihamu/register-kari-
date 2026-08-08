from pathlib import Path
import subprocess

WORKFLOW = Path('.github/workflows/build-apk.yml')
SCRIPT = Path('scripts/finalize_v076.py')
LOOKUP = Path('app/src/main/java/jp/co/tenposinfo/register/BusinessDateSalesLookupActivity.kt')
OPERATIONS = Path('app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt')
BUILD = Path('app/build.gradle.kts')

OLD_VERSION = '0.75.0-dev.1'
NEW_VERSION = '0.76.0-dev.1'
OLD_APK = 'TSUGUREGI_v0.75.0_dev1_sale_receipt_reprint_csv_export_debug.apk'
NEW_APK = 'TSUGUREGI_v0.76.0_dev1_business_session_sales_drilldown_debug.apk'
OLD_ARTIFACT = 'TSUGUREGI-v0.75.0-dev1-sale-receipt-reprint-csv-export-apks'
NEW_ARTIFACT = 'TSUGUREGI-v0.76.0-dev1-business-session-sales-drilldown-apks'


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 occurrence, got {count}')
    return text.replace(old, new, 1)

# 1) BusinessDateSalesLookupActivity: navigation context + real legacy column check.
lookup = LOOKUP.read_text()
lookup = replace_once(
    lookup,
    '''        configureRegisterSystemBars(window)
        setContent {
            MaterialTheme {
                BusinessDateSalesLookupRoute(onClose = { finish() })
            }
        }
''',
    '''        configureRegisterSystemBars(window)
        val requestedContext = BusinessDateSalesLookupNavigation.requestedContext(intent)
        setContent {
            MaterialTheme {
                BusinessDateSalesLookupRoute(
                    requestedContext = requestedContext,
                    onClose = { finish() },
                )
            }
        }
''',
    'lookup onCreate context',
)
lookup = replace_once(
    lookup,
    '''        val businessDateAvailable = SchemaMigration.hasColumn(db, "sales", "business_date")
        val query = SalesHistoryLookupPolicy.buildDatabaseQuery(
            criteria = criteria,
            businessDateColumnAvailable = businessDateAvailable,
        ) ?: return BusinessDateSalesQueryPage(emptyList(), safeOffset, safePageSize, hasNext = false)
''',
    '''        val businessDateAvailable = SchemaMigration.hasColumn(db, "sales", "business_date")
        val businessSessionIdAvailable = SchemaMigration.hasColumn(db, "sales", "business_session_id")
        val query = SalesHistoryLookupPolicy.buildDatabaseQuery(
            criteria = criteria,
            businessDateColumnAvailable = businessDateAvailable,
            businessSessionIdColumnAvailable = businessSessionIdAvailable,
        ) ?: return BusinessDateSalesQueryPage(emptyList(), safeOffset, safePageSize, hasNext = false)
''',
    'lookup session column availability',
)
lookup = replace_once(
    lookup,
    '@Composable\nprivate fun BusinessDateSalesLookupRoute(onClose: () -> Unit) {\n',
    '@Composable\nprivate fun BusinessDateSalesLookupRoute(\n    requestedContext: BusinessDateSalesLookupContext?,\n    onClose: () -> Unit,\n) {\n',
    'lookup route signature',
)
lookup = replace_once(
    lookup,
    '''        BusinessDateSalesLookupScreen(
            store = store,
            refreshEpoch = refreshEpoch,
''',
    '''        BusinessDateSalesLookupScreen(
            store = store,
            requestedContext = requestedContext,
            refreshEpoch = refreshEpoch,
''',
    'lookup route pass context',
)
lookup = replace_once(
    lookup,
    '''private fun BusinessDateSalesLookupScreen(
    store: BusinessDateSalesReadStore,
    refreshEpoch: Int,
''',
    '''private fun BusinessDateSalesLookupScreen(
    store: BusinessDateSalesReadStore,
    requestedContext: BusinessDateSalesLookupContext?,
    refreshEpoch: Int,
''',
    'lookup screen signature',
)
old_states = '''    var query by remember { mutableStateOf("") }
    var minAmountText by remember { mutableStateOf("") }
    var maxAmountText by remember { mutableStateOf("") }
    var businessDateFrom by remember { mutableStateOf("") }
    var businessDateTo by remember { mutableStateOf("") }
    var directSaleIdText by remember { mutableStateOf("") }
    var appliedCriteria by remember { mutableStateOf(SalesHistoryCriteria()) }
    var pageOffset by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<BusinessDateSaleRecord?>(null) }
    var lookupMessage by remember { mutableStateOf<String?>(null) }

    val draftCriteria = SalesHistoryCriteria(
        query = query,
        minAmount = minAmountText.toLongOrNull(),
        maxAmount = maxAmountText.toLongOrNull(),
        businessDateFrom = businessDateFrom,
        businessDateTo = businessDateTo,
    )
'''
new_states = '''    var contextLocked by remember(requestedContext) { mutableStateOf(requestedContext != null) }
    var query by remember { mutableStateOf("") }
    var minAmountText by remember { mutableStateOf("") }
    var maxAmountText by remember { mutableStateOf("") }
    var businessDateFrom by remember(requestedContext) { mutableStateOf(requestedContext?.businessDate.orEmpty()) }
    var businessDateTo by remember(requestedContext) { mutableStateOf(requestedContext?.businessDate.orEmpty()) }
    var directSaleIdText by remember { mutableStateOf("") }
    var appliedCriteria by remember(requestedContext) {
        mutableStateOf(
            requestedContext?.let { requested ->
                SalesHistoryCriteria(
                    businessDateFrom = requested.businessDate,
                    businessDateTo = requested.businessDate,
                    businessSessionId = requested.businessSessionId,
                )
            } ?: SalesHistoryCriteria(),
        )
    }
    var pageOffset by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<BusinessDateSaleRecord?>(null) }
    var lookupMessage by remember { mutableStateOf<String?>(null) }

    val draftCriteria = SalesHistoryCriteria(
        query = query,
        minAmount = minAmountText.toLongOrNull(),
        maxAmount = maxAmountText.toLongOrNull(),
        businessDateFrom = businessDateFrom,
        businessDateTo = businessDateTo,
        businessSessionId = if (contextLocked) requestedContext?.businessSessionId else null,
    )
'''
lookup = replace_once(lookup, old_states, new_states, 'lookup locked states')

anchor = '''        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(
'''
banner = '''        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            if (contextLocked && requestedContext != null) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "SCR-510連携: 営業日 ${requestedContext.businessDate} / 営業セッション No.${requestedContext.businessSessionId} 固定",
                        color = BusinessLookupGreen,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = {
                            contextLocked = false
                            directSaleIdText = ""
                            appliedCriteria = SalesHistoryCriteria(
                                businessDateFrom = requestedContext.businessDate,
                                businessDateTo = requestedContext.businessDate,
                            )
                            pageOffset = 0
                            selected = null
                            lookupMessage = "営業セッション固定を解除し、同じ営業日の全売上を表示しました"
                        },
                    ) { Text("固定解除・同日全売上を表示") }
                }
            }
            Row(
'''
lookup = replace_once(lookup, anchor, banner, 'lookup fixed banner')

# Disable five criteria fields while locked. Each marker is unique in its field block.
for label in ['営業日From', '営業日To', '売上No.・担当・支払', '金額以上', '金額以下']:
    marker = f'label = {{ Text("{label}") }},\n'
    if lookup.count(marker) != 1:
        raise SystemExit(f'field label {label}: expected 1')
    lookup = lookup.replace(marker, marker + '                    enabled = !contextLocked,\n', 1)

lookup = replace_once(
    lookup,
    '                    enabled = validation.valid,\n                    colors = ButtonDefaults.buttonColors(containerColor = BusinessLookupBlue),\n                ) { Text("検索") }\n',
    '                    enabled = !contextLocked && validation.valid,\n                    colors = ButtonDefaults.buttonColors(containerColor = BusinessLookupBlue),\n                ) { Text("検索") }\n',
    'lookup search button lock',
)
# Direct sale field: add enabled after its label.
direct_label = '                    label = { Text("売上No.直接表示") },\n'
lookup = replace_once(lookup, direct_label, direct_label + '                    enabled = !contextLocked,\n', 'direct field lock')
lookup = replace_once(
    lookup,
    '                    enabled = directSaleId != null,\n                    colors = ButtonDefaults.buttonColors(containerColor = BusinessLookupBlue),\n                ) { Text("表示") }\n',
    '                    enabled = !contextLocked && directSaleId != null,\n                    colors = ButtonDefaults.buttonColors(containerColor = BusinessLookupBlue),\n                ) { Text("表示") }\n',
    'direct button lock',
)
# Condition clear button.
clear_marker = '''                OutlinedButton(
                    onClick = {
                        query = ""
'''
clear_replacement = '''                OutlinedButton(
                    enabled = !contextLocked,
                    onClick = {
                        query = ""
'''
lookup = replace_once(lookup, clear_marker, clear_replacement, 'condition clear lock')

old_when = '''            when {
                !validation.valid -> Text(validation.message.orEmpty(), color = BusinessLookupDanger, fontWeight = FontWeight.Bold)
'''
new_when = '''            when {
                contextLocked && requestedContext != null -> Text(
                    "営業日 ${requestedContext.businessDate} / セッションNo.${requestedContext.businessSessionId} の保存済み属性で固定DB検索中です。",
                    color = BusinessLookupGreen,
                    fontWeight = FontWeight.Bold,
                )
                !validation.valid -> Text(validation.message.orEmpty(), color = BusinessLookupDanger, fontWeight = FontWeight.Bold)
'''
lookup = replace_once(lookup, old_when, new_when, 'locked status message')
LOOKUP.write_text(lookup)

# 2) OperationsActivity: SCR-510 -> exact business session sales detail.
operations = OPERATIONS.read_text()
operations = replace_once(
    operations,
    '''            OperationsScreen.DAILY_SALES -> DailySalesScreen(
                summary = store.dailySummary(),
                onBack = { screen = OperationsScreen.MENU },
            )
''',
    '''            OperationsScreen.DAILY_SALES -> DailySalesScreen(
                summary = store.dailySummary(),
                onOpenSalesDetail = { businessDate, businessSessionId ->
                    context.startActivity(
                        BusinessDateSalesLookupNavigation.intent(context, businessDate, businessSessionId),
                    )
                },
                onBack = { screen = OperationsScreen.MENU },
            )
''',
    'operations daily route',
)
operations = replace_once(
    operations,
    '@Composable\nprivate fun DailySalesScreen(summary: DailyOperationsSummary, onBack: () -> Unit) {\n',
    '@Composable\nprivate fun DailySalesScreen(\n    summary: DailyOperationsSummary,\n    onOpenSalesDetail: (String, Long) -> Unit,\n    onBack: () -> Unit,\n) {\n',
    'daily screen signature',
)
old_settled = '''                Text(
                    if (summary.settled) "この営業セッションはZ精算済みです" else "この営業セッションは未精算です",
                    color = if (summary.settled) OpGreen else OpDanger,
                    fontWeight = FontWeight.Bold,
                )
'''
new_settled = old_settled + '''                Spacer(Modifier.height(12.dp))
                Button(
                    enabled = summary.businessSessionId > 0L,
                    onClick = { onOpenSalesDetail(summary.businessDate, summary.businessSessionId) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = OpBlue),
                ) { Text("この営業セッションの売上明細", fontWeight = FontWeight.Bold) }
'''
operations = replace_once(operations, old_settled, new_settled, 'daily drilldown button')
OPERATIONS.write_text(operations)

# 3) Version.
build = BUILD.read_text()
build = replace_once(build, 'versionCode = 105', 'versionCode = 106', 'app versionCode')
build = replace_once(build, 'versionName = "0.75.0-dev.1"', 'versionName = "0.76.0-dev.1"', 'app versionName')
BUILD.write_text(build)

# 4) Current-version references in cumulative tests.
for base in [Path('app/src/test'), Path('management-app/src/test'), Path('customer-display/src/test')]:
    if not base.exists():
        continue
    for path in base.rglob('*.kt'):
        text = path.read_text()
        updated = text.replace(OLD_VERSION, NEW_VERSION)
        updated = updated.replace('versionCode = 105', 'versionCode = 106')
        updated = updated.replace(OLD_APK, NEW_APK)
        updated = updated.replace(OLD_ARTIFACT, NEW_ARTIFACT)
        if updated != text:
            path.write_text(updated)

# 5) Normal workflow v0.75 -> v0.76.
workflow = subprocess.check_output(['git', 'show', 'HEAD^:.github/workflows/build-apk.yml'], text=True)
workflow = replace_once(workflow, 'Verify cumulative v0.14-v0.75 sources', 'Verify cumulative v0.14-v0.76 sources', 'workflow title')
workflow = replace_once(workflow, "grep -q 'versionCode = 105' app/build.gradle.kts", "grep -q 'versionCode = 106' app/build.gradle.kts", 'workflow versionCode')
workflow = replace_once(workflow, "grep -q 'versionName = \"0.75.0-dev.1\"' app/build.gradle.kts", "grep -q 'versionName = \"0.76.0-dev.1\"' app/build.gradle.kts", 'workflow versionName')
workflow = workflow.replace(OLD_APK, NEW_APK)
workflow = workflow.replace(OLD_ARTIFACT, NEW_ARTIFACT)
workflow = replace_once(workflow, 'REGISTER_VERSION_NAME=0.75.0-dev.1', 'REGISTER_VERSION_NAME=0.76.0-dev.1', 'summary versionName')
workflow = replace_once(workflow, 'REGISTER_VERSION_CODE=105', 'REGISTER_VERSION_CODE=106', 'summary versionCode')

v075_test = '          test -s app/src/test/java/jp/co/tenposinfo/register/V075SaleReceiptReprintCsvExportTest.kt\n'
if v075_test not in workflow:
    raise SystemExit('V075 test guard missing')
workflow = workflow.replace(v075_test, v075_test + '          test -s app/src/test/java/jp/co/tenposinfo/register/V076BusinessSessionSalesDrilldownTest.kt\n', 1)

v075_guard = "          grep -q 'CSV出力' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt\n"
if v075_guard not in workflow:
    raise SystemExit('V075 static guard missing')
workflow = workflow.replace(
    v075_guard,
    v075_guard
    + "          test -s app/src/main/java/jp/co/tenposinfo/register/BusinessDateSalesLookupNavigation.kt\n"
    + "          grep -q 'business_session_id = ?' app/src/main/java/jp/co/tenposinfo/register/SalesHistoryLookup.kt\n"
    + "          grep -q 'businessSessionIdColumnAvailable' app/src/main/java/jp/co/tenposinfo/register/BusinessDateSalesLookupActivity.kt\n"
    + "          grep -q 'requestedContext' app/src/main/java/jp/co/tenposinfo/register/BusinessDateSalesLookupActivity.kt\n"
    + "          grep -q 'contextLocked' app/src/main/java/jp/co/tenposinfo/register/BusinessDateSalesLookupActivity.kt\n"
    + "          grep -q '固定解除・同日全売上を表示' app/src/main/java/jp/co/tenposinfo/register/BusinessDateSalesLookupActivity.kt\n"
    + "          grep -q 'この営業セッションの売上明細' app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt\n"
    + "          grep -q 'BusinessDateSalesLookupNavigation.intent' app/src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt\n",
    1,
)

v075_docs = '          test -s docs/V0.75_RELEASE_NOTES.md\n'
if v075_docs not in workflow:
    raise SystemExit('V075 docs guard missing')
workflow = workflow.replace(
    v075_docs,
    v075_docs
    + '          test -s docs/V0.76_BUSINESS_SESSION_SALES_DRILLDOWN.md\n'
    + '          test -s docs/V0.76_RELEASE_NOTES.md\n',
    1,
)

summary_marker = '          SALE_RECEIPT_REPRINT_CSV_VIEW_SALES_RECHECK=true\n'
if summary_marker not in workflow:
    raise SystemExit('v0.75 summary marker missing')
workflow = workflow.replace(
    summary_marker,
    summary_marker
    + '          BUSINESS_SESSION_SALES_DRILLDOWN=true\n'
    + '          BUSINESS_SESSION_SALES_BOUND_ARG=true\n'
    + '          BUSINESS_SESSION_SALES_CONTEXT_LOCK=true\n'
    + '          BUSINESS_SESSION_SALES_EXPLICIT_UNLOCK=true\n'
    + '          BUSINESS_SESSION_SALES_LEGACY_FAIL_CLOSED=true\n',
    1,
)
real_device_marker = '          REAL_DEVICE_SALE_RECEIPT_REPRINT_CSV_EXPORT_VERIFICATION=required\n'
if real_device_marker not in workflow:
    raise SystemExit('real-device v0.75 marker missing')
workflow = workflow.replace(
    real_device_marker,
    '          REAL_DEVICE_BUSINESS_SESSION_SALES_DRILLDOWN_VERIFICATION=required\n' + real_device_marker,
    1,
)
WORKFLOW.write_text(workflow)

# 6) Guard final state.
checks = {
    'session DB bind': 'business_session_id = ?' in Path('app/src/main/java/jp/co/tenposinfo/register/SalesHistoryLookup.kt').read_text(),
    'legacy column check': 'businessSessionIdColumnAvailable' in LOOKUP.read_text(),
    'context lock': 'contextLocked' in LOOKUP.read_text(),
    'explicit unlock': '固定解除・同日全売上を表示' in LOOKUP.read_text(),
    'daily button': 'この営業セッションの売上明細' in OPERATIONS.read_text(),
    'navigation': 'BusinessDateSalesLookupNavigation.intent' in OPERATIONS.read_text(),
    'version': NEW_VERSION in BUILD.read_text() and 'versionCode = 106' in BUILD.read_text(),
    'workflow V076': 'V076BusinessSessionSalesDrilldownTest.kt' in WORKFLOW.read_text(),
    'artifact': NEW_ARTIFACT in WORKFLOW.read_text(),
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit('final guards failed: ' + ', '.join(failed))

SCRIPT.unlink()
subprocess.run(['git', 'config', 'user.name', 'github-actions[bot]'], check=True)
subprocess.run(['git', 'config', 'user.email', '41898282+github-actions[bot]@users.noreply.github.com'], check=True)
subprocess.run(['git', 'add', '-A'], check=True)
subprocess.run(['git', 'commit', '-m', 'finalize v0.76 business session sales drilldown'], check=True)
sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
print(f'FINALIZE_COMMIT={sha}')
subprocess.run(['git', 'push', 'origin', 'HEAD:develop/v0.76'], check=True)
