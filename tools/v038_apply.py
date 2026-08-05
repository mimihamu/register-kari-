from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'marker not found in {path}: {old[:120]!r}')
    path.write_text(text.replace(old, new, 1), encoding='utf-8')


root = Path('app/src/main/java/jp/co/tenposinfo/register')

# Fix nullable temporary document handling before compilation.
operations = root / 'OutboxDeliveryOperations.kt'
replace_once(
    operations,
    '''            documentUri = OutboxExternalDocumentProvider.createFile(appContext, parent, fileName)\n            val written = appContext.contentResolver.openOutputStream(documentUri, "w")?.use { output ->\n''',
    '''            val createdUri = OutboxExternalDocumentProvider.createFile(appContext, parent, fileName)\n            documentUri = createdUri\n            val written = appContext.contentResolver.openOutputStream(createdUri, "w")?.use { output ->\n''',
)
text = operations.read_text(encoding='utf-8')
text = text.replace('openInputStream(documentUri)?.use { it.readBytes() }', 'openInputStream(createdUri)?.use { it.readBytes() }', 1)
text = text.replace('removed = OutboxExternalDocumentProvider.delete(appContext, documentUri)', 'removed = OutboxExternalDocumentProvider.delete(appContext, createdUri)', 1)
operations.write_text(text, encoding='utf-8')

# Connect the new dashboard to the existing external-delivery settings screen.
settings_ui = root / 'OutboxDeliverySettingsActivity.kt'
marker = '''                    Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {\n                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {\n                            Text("失敗通知", color = OdsNavy, fontSize = 22.sp, fontWeight = FontWeight.Bold)\n'''
insert = '''                    OutboxDeliveryOperationsPanel(\n                        treeUriText = treeUriText,\n                        destinationPermission = destinationPermission,\n                        onChanged = { text -> refreshState(text) },\n                    )\n\n''' + marker
replace_once(settings_ui, marker, insert)

# Cumulative application version.
build = Path('app/build.gradle.kts')
text = build.read_text(encoding='utf-8')
text = text.replace('versionCode = 67', 'versionCode = 68', 1)
text = text.replace('versionName = "0.37.0-dev.1"', 'versionName = "0.38.0-dev.1"', 1)
build.write_text(text, encoding='utf-8')

# Older cumulative source-hook tests track the current build identity.
for test_path in (
    Path('app/src/test/java/jp/co/tenposinfo/register/V035OutboxExternalDeliveryTest.kt'),
    Path('app/src/test/java/jp/co/tenposinfo/register/V037CustomerDisplayPresentationTest.kt'),
):
    text = test_path.read_text(encoding='utf-8')
    text = text.replace('versionCode = 67', 'versionCode = 68')
    text = text.replace('versionName = \\"0.37.0-dev.1\\"', 'versionName = \\"0.38.0-dev.1\\"')
    test_path.write_text(text, encoding='utf-8')

# Build a final workflow template. It is published separately because Actions tokens
# cannot update workflow files.
workflow = Path('.github/workflows/build-apk.yml').read_text(encoding='utf-8')
replacements = {
    'Verify cumulative v0.14-v0.37 sources': 'Verify cumulative v0.14-v0.38 sources',
    "versionCode = 67": "versionCode = 68",
    'versionName = "0.37.0-dev.1"': 'versionName = "0.38.0-dev.1"',
    'TSUGUREGI_v0.37.0_dev1_customer_display_presentation_debug.apk': 'TSUGUREGI_v0.38.0_dev1_outbox_operations_debug.apk',
    'TSUGUREGI-v0.37.0-dev1-customer-display-presentation-apks': 'TSUGUREGI-v0.38.0-dev1-outbox-operations-apks',
}
for old, new in replacements.items():
    if old not in workflow:
        raise SystemExit(f'workflow marker not found: {old}')
    workflow = workflow.replace(old, new)

file_marker = '''          for file in \\\n'''
file_insert = '''          for file in \\\n            app/src/main/java/jp/co/tenposinfo/register/OutboxDeliveryOperations.kt \\\n            app/src/main/java/jp/co/tenposinfo/register/OutboxDeliveryOperationsPanel.kt \\\n            app/src/test/java/jp/co/tenposinfo/register/V038OutboxDeliveryOperationsTest.kt \\\n            docs/V0.38_OUTBOX_DELIVERY_OPERATIONS.md \\\n            docs/V0.38_RELEASE_NOTES.md \\\n'''
if file_marker not in workflow:
    raise SystemExit('workflow file-list marker not found')
workflow = workflow.replace(file_marker, file_insert, 1)

python_marker = '''          # v0.37 customer-display presentation, synchronization and state restoration.\n'''
python_insert = '''          # v0.38 outbox operations dashboard and recovery controls.\n          operations = (root / 'OutboxDeliveryOperations.kt').read_text()\n          operations_panel = (root / 'OutboxDeliveryOperationsPanel.kt').read_text()\n          for token in (\n              'OutboxDeliveryDashboardCounts',\n              'fun retryItem',\n              'fun preview',\n              'fun recentAudit',\n              'fun testDestination',\n              'SYNC_OUTBOX_ITEM_RETRY_REQUESTED',\n              'SYNC_OUTBOX_DESTINATION_TEST_SUCCEEDED',\n          ):\n              assert token in operations, token\n          assert '送信運用ダッシュボード' in operations_panel\n          assert 'この1件を再試行' in operations_panel\n          assert '端末内JSONプレビュー' in operations_panel\n          assert 'OutboxDeliveryOperationsPanel' in delivery_ui\n\n''' + python_marker
if python_marker not in workflow:
    raise SystemExit('workflow python marker not found')
workflow = workflow.replace(python_marker, python_insert, 1)

cleanup_marker = '''          # Temporary transfer files/workflows must not survive the final branch.\n'''
cleanup_insert = '''          # Temporary transfer files/workflows must not survive the final branch.\n          assert not list(Path('tools').glob('v038*'))\n          assert not Path('.github/workflows/v038-apply.yml').exists()\n'''
if cleanup_marker not in workflow:
    raise SystemExit('workflow cleanup marker not found')
workflow = workflow.replace(cleanup_marker, cleanup_insert, 1)

Path('tools/v038_build_apk.yml').write_text(workflow, encoding='utf-8')
print('v0.38 source, tests, version and CI template applied')
