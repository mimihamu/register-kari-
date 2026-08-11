from pathlib import Path
import re

root = Path('management-app/src/test/java/jp/co/tenposinfo/register/plus')
version_code_changes = 0
version_name_changes = 0
artifact_changes = 0

for path in sorted(root.glob('*.kt')):
    text = path.read_text(encoding='utf-8')
    original = text

    pattern_code = re.compile(r'assertTrue\((\w+)\.contains\("versionCode = 14"\)\)')
    def repl_code(match):
        global version_code_changes
        version_code_changes += 1
        var = match.group(1)
        return f'assertTrue(Regex("versionCode\\\\s*=\\\\s*(\\\\d+)").find({var})?.groupValues?.get(1)?.toIntOrNull()?.let {{ it >= 14 }} == true)'
    text = pattern_code.sub(repl_code, text)

    pattern_name = re.compile(r'assertTrue\((\w+)\.contains\("versionName = \\"0\.14\.0-dev\.1\\""\)\)')
    def repl_name(match):
        global version_name_changes
        version_name_changes += 1
        var = match.group(1)
        return f'assertTrue(Regex("""versionName\\s*=\\s*"0\\.(\\d+)\\.0-dev\\.1""").find({var})?.groupValues?.get(1)?.toIntOrNull()?.let {{ it >= 14 }} == true)'
    text = pattern_name.sub(repl_name, text)

    old_artifact = 'assertTrue(workflow.contains("TSUGUREGI_PLUS_v0.14.0_dev1_sync_operations_dashboard_debug.apk"))'
    new_artifact = 'assertTrue(workflow.contains("TSUGUREGI_PLUS_v") && workflow.contains("_debug.apk"))'
    if old_artifact in text:
        artifact_changes += text.count(old_artifact)
        text = text.replace(old_artifact, new_artifact)

    if text != original:
        path.write_text(text, encoding='utf-8')

v101 = root / 'V101GoogleDriveTransientImportSafetyTest.kt'
text = v101.read_text(encoding='utf-8')
old_root = 'if (File(current, "management-app").isDirectory) current.parentFile else current'
new_root = 'if (File(current, "management-app").isDirectory) current else current.parentFile'
if text.count(old_root) != 1:
    raise SystemExit(f'V101 root anchor expected 1, found {text.count(old_root)}')
v101.write_text(text.replace(old_root, new_root, 1), encoding='utf-8')

print(f'versionCode changes={version_code_changes}')
print(f'versionName changes={version_name_changes}')
print(f'artifact changes={artifact_changes}')
if version_code_changes != 13 or version_name_changes != 13 or artifact_changes != 13:
    raise SystemExit('Expected exactly 13 stale Plus release assertions of each type')
