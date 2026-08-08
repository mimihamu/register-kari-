from pathlib import Path
import subprocess

ROOT = Path('.')


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding='utf-8')


def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text, encoding='utf-8')


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f'{label}: expected exactly 1 occurrence, found {count}')
    return text.replace(old, new, 1)


def replace_all_in_tests(old: str, new: str) -> int:
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

# 1) app version
path = 'app/build.gradle.kts'
text = read(path)
text = replace_once(text, 'versionCode = 98', 'versionCode = 99', 'app versionCode')
text = replace_once(text, 'versionName = "0.68.0-dev.1"', 'versionName = "0.69.0-dev.1"', 'app versionName')
write(path, text)

# 2) manifest activity
path = 'app/src/main/AndroidManifest.xml'
text = read(path)
if '.SaleReceiptReprintLedgerActivity' not in text:
    old = '''        <activity
            android:name=".SaleReceiptReprintActivity"
            android:exported="false"
            android:screenOrientation="landscape" />
'''
    new = old + '''
        <activity
            android:name=".SaleReceiptReprintLedgerActivity"
            android:exported="false"
            android:screenOrientation="landscape" />
'''
    text = replace_once(text, old, new, 'manifest ledger activity')
write(path, text)

# 3) SCR-647 -> ledger route
path = 'app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintActivity.kt'
text = read(path)
if 'onOpenLedger' not in text:
    text = replace_once(
        text,
        '''                        onOpenQueue = {
                            context.startActivity(Intent(context, UnifiedPrintQueueActivity::class.java))
                        },
                        onClose = onClose,
''',
        '''                        onOpenQueue = {
                            context.startActivity(Intent(context, UnifiedPrintQueueActivity::class.java))
                        },
                        onOpenLedger = {
                            context.startActivity(Intent(context, SaleReceiptReprintLedgerActivity::class.java))
                        },
                        onClose = onClose,
''',
        'reprint route ledger callback',
    )
    text = replace_once(
        text,
        '''    onConfirmReprint: () -> Unit,
    onOpenQueue: () -> Unit,
    onClose: () -> Unit,
''',
        '''    onConfirmReprint: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenLedger: () -> Unit,
    onClose: () -> Unit,
''',
        'reprint screen ledger parameter',
    )
    text = replace_once(
        text,
        '''                    OutlinedButton(onClick = onOpenQueue, modifier = Modifier.fillMaxWidth().height(46.dp)) {
                        Text("統合印刷キューを開く")
                    }
                    Spacer(Modifier.height(10.dp))
''',
        '''                    OutlinedButton(onClick = onOpenQueue, modifier = Modifier.fillMaxWidth().height(46.dp)) {
                        Text("統合印刷キューを開く")
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = onOpenLedger, modifier = Modifier.fillMaxWidth().height(46.dp)) {
                        Text("運用台帳を開く")
                    }
                    Spacer(Modifier.height(10.dp))
''',
        'reprint ledger button',
    )
write(path, text)

# 4) Operations hub route + tile
path = 'app/src/main/java/jp/co/tenposinfo/register/OperationsHubActivityV030.kt'
text = read(path)
if 'openReceiptReprintLedger' not in text:
    text = replace_once(
        text,
        '''                    openReceiptVoucher = { startActivity(Intent(this, ReceiptVoucherActivity::class.java)) },
                    openSalesLookup = { startActivity(Intent(this, BusinessDateSalesLookupActivity::class.java)) },
                    openLegacyManagement = { startActivity(Intent(this, OperationsActivity::class.java)) },
''',
        '''                    openReceiptVoucher = { startActivity(Intent(this, ReceiptVoucherActivity::class.java)) },
                    openSalesLookup = { startActivity(Intent(this, BusinessDateSalesLookupActivity::class.java)) },
                    openReceiptReprintLedger = { startActivity(Intent(this, SaleReceiptReprintLedgerActivity::class.java)) },
                    openLegacyManagement = { startActivity(Intent(this, OperationsActivity::class.java)) },
''',
        'hub activity callback',
    )
    text = replace_once(
        text,
        '''    openReceiptVoucher: () -> Unit,
    openSalesLookup: () -> Unit,
    openLegacyManagement: () -> Unit,
) {
''',
        '''    openReceiptVoucher: () -> Unit,
    openSalesLookup: () -> Unit,
    openReceiptReprintLedger: () -> Unit,
    openLegacyManagement: () -> Unit,
) {
''',
        'hub route signature',
    )
    text = replace_once(
        text,
        '''            openReceiptVoucher = openReceiptVoucher,
            openSalesLookup = openSalesLookup,
            openLegacyManagement = openLegacyManagement,
''',
        '''            openReceiptVoucher = openReceiptVoucher,
            openSalesLookup = openSalesLookup,
            openReceiptReprintLedger = openReceiptReprintLedger,
            openLegacyManagement = openLegacyManagement,
''',
        'hub route to screen',
    )
    text = replace_once(
        text,
        '''    openReceiptVoucher: () -> Unit,
    openSalesLookup: () -> Unit,
    openLegacyManagement: () -> Unit,
) {
    val metrics = rememberRegisterResponsiveMetrics()
''',
        '''    openReceiptVoucher: () -> Unit,
    openSalesLookup: () -> Unit,
    openReceiptReprintLedger: () -> Unit,
    openLegacyManagement: () -> Unit,
) {
    val metrics = rememberRegisterResponsiveMetrics()
''',
        'hub screen signature',
    )
    text = replace_once(
        text,
        '''                    HubTileV030(
                        "営業日別 売上検索",
                        "営業日・売上No.・担当・支払・金額で検索",
                        HubPaleBlueV030,
                        RegisterPermission.VIEW_SALES in permissions,
                        openSalesLookup,
                        Modifier.fillMaxWidth().heightIn(min = 112.dp),
                    )
                    HubLegacyPanelV030(Modifier.fillMaxWidth(), permissions, openLegacyManagement)
''',
        '''                    HubTileV030(
                        "営業日別 売上検索",
                        "営業日・売上No.・担当・支払・金額で検索",
                        HubPaleBlueV030,
                        RegisterPermission.VIEW_SALES in permissions,
                        openSalesLookup,
                        Modifier.fillMaxWidth().heightIn(min = 112.dp),
                    )
                    HubTileV030(
                        "レシート再印字台帳",
                        "再印字要求・印刷状態・エラーを全売上横断で確認",
                        Color(0xFFFFF3E0),
                        RegisterPermission.VIEW_SALES in permissions,
                        openReceiptReprintLedger,
                        Modifier.fillMaxWidth().heightIn(min = 112.dp),
                    )
                    HubLegacyPanelV030(Modifier.fillMaxWidth(), permissions, openLegacyManagement)
''',
        'hub compact ledger tile',
    )
    text = replace_once(
        text,
        '''                            HubTileV030(
                                "営業日別 売上検索",
                                "営業日・売上No.・担当・支払・金額で検索",
                                HubPaleBlueV030,
                                RegisterPermission.VIEW_SALES in permissions,
                                openSalesLookup,
                                Modifier.weight(1f).fillMaxHeight(),
                            )
                            HubLegacyPanelV030(
                                Modifier.weight(1f).fillMaxHeight(),
                                permissions,
                                openLegacyManagement,
                            )
''',
        '''                            HubTileV030(
                                "営業日別 売上検索",
                                "営業日・売上No.・担当・支払・金額で検索",
                                HubPaleBlueV030,
                                RegisterPermission.VIEW_SALES in permissions,
                                openSalesLookup,
                                Modifier.weight(1f).fillMaxHeight(),
                            )
                            HubTileV030(
                                "レシート再印字台帳",
                                "再印字要求・印刷状態・エラーを横断確認",
                                Color(0xFFFFF3E0),
                                RegisterPermission.VIEW_SALES in permissions,
                                openReceiptReprintLedger,
                                Modifier.weight(1f).fillMaxHeight(),
                            )
                            HubLegacyPanelV030(
                                Modifier.weight(1f).fillMaxHeight(),
                                permissions,
                                openLegacyManagement,
                            )
''',
        'hub wide ledger tile',
    )
write(path, text)

# 5) current-version assertions in cumulative tests
replacements = [
    ('0.68.0-dev.1', '0.69.0-dev.1'),
    ('versionCode = 98', 'versionCode = 99'),
    ('TSUGUREGI_v0.68.0_dev1_sale_receipt_reprint_audit_debug.apk', 'TSUGUREGI_v0.69.0_dev1_sale_receipt_reprint_operations_ledger_debug.apk'),
    ('TSUGUREGI-v0.68.0-dev1-sale-receipt-reprint-audit-apks', 'TSUGUREGI-v0.69.0-dev1-sale-receipt-reprint-operations-ledger-apks'),
]
for old, new in replacements:
    replace_all_in_tests(old, new)

# 6) restore normal CI from parent, then advance to v0.69
workflow = subprocess.check_output(
    ['git', 'show', 'HEAD^:.github/workflows/build-apk.yml'],
    text=True,
)
workflow = workflow.replace('Verify cumulative v0.14-v0.68 sources', 'Verify cumulative v0.14-v0.69 sources')
workflow = workflow.replace("grep -q 'versionCode = 98' app/build.gradle.kts", "grep -q 'versionCode = 99' app/build.gradle.kts")
workflow = workflow.replace("grep -q 'versionName = \"0.68.0-dev.1\"' app/build.gradle.kts", "grep -q 'versionName = \"0.69.0-dev.1\"' app/build.gradle.kts")
workflow = replace_once(
    workflow,
    "          test -s app/src/test/java/jp/co/tenposinfo/register/V068SaleReceiptReprintAuditTest.kt\n",
    "          test -s app/src/test/java/jp/co/tenposinfo/register/V068SaleReceiptReprintAuditTest.kt\n          test -s app/src/test/java/jp/co/tenposinfo/register/V069SaleReceiptReprintOperationsLedgerTest.kt\n",
    'workflow v069 test',
)
workflow = replace_once(
    workflow,
    "          test -s docs/V0.68_SALE_RECEIPT_REPRINT_AUDIT.md\n          test -s docs/V0.68_RELEASE_NOTES.md\n",
    "          test -s docs/V0.68_SALE_RECEIPT_REPRINT_AUDIT.md\n          test -s docs/V0.68_RELEASE_NOTES.md\n          test -s docs/V0.69_SALE_RECEIPT_REPRINT_OPERATIONS_LEDGER.md\n          test -s docs/V0.69_RELEASE_NOTES.md\n",
    'workflow v069 docs',
)
anchor = "          grep -q 'SaleReceiptReprintAuditStore' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintActivity.kt\n"
if anchor in workflow:
    workflow = replace_once(
        workflow,
        anchor,
        anchor
        + "          test -s app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintOperations.kt\n"
        + "          test -s app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt\n"
        + "          grep -q 'SaleReceiptReprintLedgerActivity' app/src/main/AndroidManifest.xml\n"
        + "          grep -q '運用台帳を開く' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintActivity.kt\n"
        + "          grep -q 'レシート再印字台帳' app/src/main/java/jp/co/tenposinfo/register/OperationsHubActivityV030.kt\n",
        'workflow ledger acceptance checks',
    )
else:
    # Keep the finalizer strict even if the exact v0.68 acceptance line was renamed.
    marker = "          test -s app/src/test/java/jp/co/tenposinfo/register/V069SaleReceiptReprintOperationsLedgerTest.kt\n"
    workflow = replace_once(
        workflow,
        marker,
        marker
        + "          test -s app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintOperations.kt\n"
        + "          test -s app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt\n"
        + "          grep -q 'SaleReceiptReprintLedgerActivity' app/src/main/AndroidManifest.xml\n"
        + "          grep -q '運用台帳を開く' app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintActivity.kt\n"
        + "          grep -q 'レシート再印字台帳' app/src/main/java/jp/co/tenposinfo/register/OperationsHubActivityV030.kt\n",
        'workflow ledger acceptance checks fallback',
    )
workflow = workflow.replace(
    'TSUGUREGI_v0.68.0_dev1_sale_receipt_reprint_audit_debug.apk',
    'TSUGUREGI_v0.69.0_dev1_sale_receipt_reprint_operations_ledger_debug.apk',
)
workflow = workflow.replace('REGISTER_VERSION_NAME=0.68.0-dev.1', 'REGISTER_VERSION_NAME=0.69.0-dev.1')
workflow = workflow.replace('REGISTER_VERSION_CODE=98', 'REGISTER_VERSION_CODE=99')
workflow = workflow.replace(
    'TSUGUREGI-v0.68.0-dev1-sale-receipt-reprint-audit-apks',
    'TSUGUREGI-v0.69.0-dev1-sale-receipt-reprint-operations-ledger-apks',
)
workflow = replace_once(
    workflow,
    '          SALE_RECEIPT_REPRINT_ALL_UI_ROUTES_AUDITED=true\n',
    '          SALE_RECEIPT_REPRINT_ALL_UI_ROUTES_AUDITED=true\n'
    '          SALE_RECEIPT_REPRINT_OPERATIONS_LEDGER=true\n'
    '          SALE_RECEIPT_REPRINT_LEDGER_READ_ONLY=true\n'
    '          SALE_RECEIPT_REPRINT_LEDGER_RECOVERY_USES_UNIFIED_QUEUE=true\n'
    '          SALE_RECEIPT_REPRINT_LEDGER_VIEW_SALES_RECHECK=true\n',
    'workflow ledger flags',
)
workflow = replace_once(
    workflow,
    '          REAL_DEVICE_SALE_RECEIPT_REPRINT_AUDIT_VERIFICATION=required\n',
    '          REAL_DEVICE_SALE_RECEIPT_REPRINT_LEDGER_VERIFICATION=required\n'
    '          REAL_DEVICE_SALE_RECEIPT_REPRINT_AUDIT_VERIFICATION=required\n',
    'workflow real-device ledger flag',
)
write('.github/workflows/build-apk.yml', workflow)

# Finalizer must not remain in the final branch.
Path('scripts/finalize_v069.py').unlink()

# Guard final state before committing.
checks = {
    'app/build.gradle.kts': ['versionCode = 99', 'versionName = "0.69.0-dev.1"'],
    'app/src/main/AndroidManifest.xml': ['.SaleReceiptReprintLedgerActivity'],
    'app/src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintActivity.kt': ['運用台帳を開く', 'SaleReceiptReprintLedgerActivity::class.java'],
    'app/src/main/java/jp/co/tenposinfo/register/OperationsHubActivityV030.kt': ['レシート再印字台帳', 'SaleReceiptReprintLedgerActivity::class.java'],
    '.github/workflows/build-apk.yml': ['v0.14-v0.69', 'V069SaleReceiptReprintOperationsLedgerTest.kt', 'REGISTER_VERSION_CODE=99', 'SALE_RECEIPT_REPRINT_OPERATIONS_LEDGER=true'],
}
for file, needles in checks.items():
    source = read(file)
    for needle in needles:
        if needle not in source:
            raise RuntimeError(f'{file}: missing final needle {needle!r}')

subprocess.run(['git', 'config', 'user.name', 'tsuguregi-ci'], check=True)
subprocess.run(['git', 'config', 'user.email', 'tsuguregi-ci@users.noreply.github.com'], check=True)
subprocess.run(['git', 'add', '-A'], check=True)
subprocess.run(['git', 'commit', '-m', 'finalize v0.69 receipt reprint operations ledger'], check=True)
sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
print(f'FINALIZE_COMMIT={sha}')
subprocess.run(['git', 'status', '--short'], check=True)
# The GitHub token may reject a workflow-file ref update. The commit object is still useful;
# the caller will fast-forward the branch via the connected GitHub API after verifying it.
subprocess.run(['git', 'push', 'origin', 'HEAD:develop/v0.69'], check=True)
