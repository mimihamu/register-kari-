from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


def replace_required(path: str, old: str, new: str, expected: int = 1) -> None:
    content = read(path)
    count = content.count(old)
    if count != expected:
        raise RuntimeError(f"{path}: expected {expected} occurrences, found {count}: {old[:80]!r}")
    write(path, content.replace(old, new))


# 1. Expose the already-implemented sync screen from the real administrator menu.
admin_path = "app/src/main/java/jp/co/tenposinfo/register/AdminSettingsActivity.kt"
replace_required(
    admin_path,
    "                    onDataProtection = { context.startActivity(Intent(context, DataProtectionActivity::class.java)) },\n                    onSecurity = { screen = AdminScreen.SECURITY },",
    "                    onDataProtection = { context.startActivity(Intent(context, DataProtectionActivity::class.java)) },\n                    onSync = { context.startActivity(Intent(context, SyncSettingsActivity::class.java)) },\n                    onSecurity = { screen = AdminScreen.SECURITY },",
)
replace_required(
    admin_path,
    "    onDataProtection: () -> Unit,\n    onSecurity: () -> Unit,",
    "    onDataProtection: () -> Unit,\n    onSync: () -> Unit,\n    onSecurity: () -> Unit,",
)
replace_required(
    admin_path,
    "                    AsMenuTile(\"責任者PIN\", \"責任者PINを安全に更新\", AsPaleYellow, Modifier.weight(1f), onSecurity)\n                    AsMenuTile(\"データ保全\", \"整合性診断、バックアップ、復元\", Color(0xFFE8F3EE), Modifier.weight(1f), onDataProtection)\n                    Spacer(Modifier.weight(1f))",
    "                    AsMenuTile(\"責任者PIN\", \"責任者PINを安全に更新\", AsPaleYellow, Modifier.weight(1f), onSecurity)\n                    AsMenuTile(\"データ保全\", \"整合性診断、バックアップ、復元\", Color(0xFFE8F3EE), Modifier.weight(1f), onDataProtection)\n                    AsMenuTile(\"Google Drive・同期\", \"初期設定、アカウント、送信状況、診断\", Color(0xFFE8F0FC), Modifier.weight(1f), onSync)",
)

sync_path = "app/src/main/java/jp/co/tenposinfo/register/SyncSettingsActivity.kt"
sync = read(sync_path)
sync = sync.replace("Text(\"Google Drive同期基盤\"", "Text(\"Google Drive・同期設定\"")
sync = sync.replace("Text(\"SCR-760  売上ジャーナル・外部同期基盤\"", "Text(\"SCR-760  Google Drive・同期\"")
write(sync_path, sync)

# 2. Version bump.
replace_required("app/build.gradle.kts", "versionCode = 77", "versionCode = 78")
replace_required("app/build.gradle.kts", 'versionName = "0.47.0-dev.1"', 'versionName = "0.48.0-dev.1"')

# 3. Cumulative contract tests follow the current register build version/artifact.
for base in (ROOT / "app/src/test").rglob("*.kt"):
    text = base.read_text(encoding="utf-8")
    text = text.replace("versionCode = 77", "versionCode = 78")
    text = text.replace('versionName = \\"0.47.0-dev.1\\"', 'versionName = \\"0.48.0-dev.1\\"')
    text = text.replace("TSUGUREGI-v0.47.0-dev1-drive-diagnostics-apks", "TSUGUREGI-v0.48.0-dev1-drive-settings-entry-apks")
    text = text.replace("TSUGUREGI_v0.47.0_dev1_drive_diagnostics_debug.apk", "TSUGUREGI_v0.48.0_dev1_drive_settings_entry_debug.apk")
    base.write_text(text, encoding="utf-8")
for base in (ROOT / "management-app/src/test").rglob("*.kt"):
    text = base.read_text(encoding="utf-8")
    text = text.replace("versionCode = 77", "versionCode = 78")
    text = text.replace('versionName = \\"0.47.0-dev.1\\"', 'versionName = \\"0.48.0-dev.1\\"')
    text = text.replace("TSUGUREGI-v0.47.0-dev1-drive-diagnostics-apks", "TSUGUREGI-v0.48.0-dev1-drive-settings-entry-apks")
    text = text.replace("TSUGUREGI_v0.47.0_dev1_drive_diagnostics_debug.apk", "TSUGUREGI_v0.48.0_dev1_drive_settings_entry_debug.apk")
    base.write_text(text, encoding="utf-8")

# 4. New navigation contract test.
write(
    "app/src/test/java/jp/co/tenposinfo/register/V048GoogleDriveSettingsEntryTest.kt",
    '''package jp.co.tenposinfo.register

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V048GoogleDriveSettingsEntryTest {
    @Test
    fun visibleAdministratorMenuOpensDriveAndSyncScreen() {
        val root = File("src/main/java/jp/co/tenposinfo/register")
        val main = File(root, "MainActivity.kt").readText()
        val admin = File(root, "AdminSettingsActivity.kt").readText()
        val sync = File(root, "SyncSettingsActivity.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File("../.github/workflows/build-apk.yml").readText()
        val docs = File("../docs/V0.48_GOOGLE_DRIVE_SETTINGS_ENTRY.md").readText()
        val notes = File("../docs/V0.48_RELEASE_NOTES.md").readText()

        assertTrue(main.contains("onOpenSettings = { context.startActivity(Intent(context, AdminSettingsActivity::class.java)) }"))
        assertTrue(admin.contains("onSync = { context.startActivity(Intent(context, SyncSettingsActivity::class.java)) }"))
        assertTrue(admin.contains("onSync: () -> Unit"))
        assertTrue(admin.contains("Google Drive・同期"))
        assertTrue(admin.contains("初期設定、アカウント、送信状況、診断"))
        assertTrue(sync.contains("Google Drive・同期設定"))
        assertTrue(sync.contains("Google Drive初期設定・アカウント"))
        assertTrue(sync.contains("GoogleDriveSetupGuideActivity::class.java"))
        assertTrue(manifest.contains("android:name=\".SyncSettingsActivity\""))
        assertTrue(manifest.contains("android:name=\".GoogleDriveSetupGuideActivity\""))
        assertTrue(build.contains("versionCode = 78"))
        assertTrue(build.contains("versionName = \"0.48.0-dev.1\""))
        assertTrue(workflow.contains("V048GoogleDriveSettingsEntryTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_v0.48.0_dev1_drive_settings_entry_debug.apk"))
        assertTrue(workflow.contains("TSUGUREGI-v0.48.0-dev1-drive-settings-entry-apks"))
        assertTrue(docs.contains("販売画面 → 各種設定 → 責任者認証 → Google Drive・同期"))
        assertTrue(notes.contains("0.48.0-dev.1"))
        assertFalse(File("../tools/v048_apply.py").exists())
        assertFalse(File("../.github/workflows/v048-apply-temp.yml").exists())
    }
}
''',
)

# 5. Documentation and corrected operating path.
write(
    "docs/V0.48_GOOGLE_DRIVE_SETTINGS_ENTRY.md",
    '''# v0.48 Google Drive・同期の設定入口

## 背景

v0.47までは `SyncSettingsActivity`、Google Drive初期設定、アカウント設定、診断画面が実装されていましたが、実際にユーザーが開く「各種設定」メニューから `SyncSettingsActivity` を呼び出すタイルがありませんでした。

そのため、機能は存在していても通常操作では開けない状態でした。

## 正式な操作経路

販売画面 → 各種設定 → 責任者認証 → Google Drive・同期

「Google Drive・同期」を開くと、次の操作ができます。

- Google Drive初期設定・アカウント
- Google Cloud設定値とAPK署名の確認
- Googleアカウント登録と接続確認
- Drive API直接送信
- Outbox送信状況の確認
- 再キュー
- 互換用フォルダ送信設定
- Google Drive診断・ログ

## 表示条件

販売画面の「各種設定」は、責任者かつ設定権限を持つ担当者だけが開けます。各種設定画面では登録済み責任者PINによる認証を行います。

## 維持事項

- `drive.file`限定スコープ
- アクセストークン、更新トークン非保存
- SQLiteローカル売上を原本とする設計
- SAFフォルダ方式は互換用
- つぐレジ＋は別のAndroid OAuthクライアントを使用
''',
)
write(
    "docs/V0.48_RELEASE_NOTES.md",
    '''# つぐレジ v0.48 リリースノート

## バージョン

- つぐレジ: 0.48.0-dev.1 / versionCode 78
- つぐレジ＋: 0.7.0-dev.1 / versionCode 7
- つぐレジ CD: 0.14.0-dev.1 / versionCode 7

## 変更内容

- 「各種設定」に「Google Drive・同期」タイルを追加
- 実装済みだった `SyncSettingsActivity` を通常操作から開けるように修正
- 同期画面の見出しを「Google Drive・同期」に統一
- Google Drive初期設定、アカウント、送信状況、診断への入口を一本化
- 操作経路を「販売画面 → 各種設定 → 責任者認証 → Google Drive・同期」に訂正

## 不具合の原因

同期画面とGoogle Drive関連画面はManifestへ登録済みでしたが、「各種設定」メニューから同期画面へ遷移するコールバックとタイルがありませんでした。

## 実機未確認

- 各種設定タイルの実機表示
- 画面サイズ別の3段目タイル配置
- 責任者PIN認証後の画面遷移
- Googleアカウント選択、OAuth同意、Drive API接続
- Driveへの実アップロードとつぐレジ＋からの取得
''',
)

# Correct misleading route wording in recent Drive docs where present.
for relative in (
    "docs/V0.46_GOOGLE_DRIVE_SETUP_GUIDE.md",
    "docs/V0.46_RELEASE_NOTES.md",
    "docs/V0.47_GOOGLE_DRIVE_DIAGNOSTICS.md",
    "docs/V0.47_RELEASE_NOTES.md",
):
    path = ROOT / relative
    text = path.read_text(encoding="utf-8")
    text = text.replace("設定 → 同期設定 →", "販売画面 → 各種設定 → Google Drive・同期 →")
    text = text.replace("設定 > 同期設定 >", "販売画面 > 各種設定 > Google Drive・同期 >")
    text = text.replace("設定 ＞ 同期設定 ＞", "販売画面 ＞ 各種設定 ＞ Google Drive・同期 ＞")
    path.write_text(text, encoding="utf-8")

# 6. Extend cumulative CI and artifact naming.
workflow_path = ".github/workflows/build-apk.yml"
workflow = read(workflow_path)
workflow = workflow.replace("Verify cumulative v0.14-v0.47 sources", "Verify cumulative v0.14-v0.48 sources")
workflow = workflow.replace("versionCode = 77", "versionCode = 78")
workflow = workflow.replace('versionName = \\\"0.47.0-dev.1\\\"', 'versionName = \\\"0.48.0-dev.1\\\"')
workflow = workflow.replace("TSUGUREGI_v0.47.0_dev1_drive_diagnostics_debug.apk", "TSUGUREGI_v0.48.0_dev1_drive_settings_entry_debug.apk")
workflow = workflow.replace("TSUGUREGI-v0.47.0-dev1-drive-diagnostics-apks", "TSUGUREGI-v0.48.0-dev1-drive-settings-entry-apks")
workflow = workflow.replace(
    "            app/src/main/AndroidManifest.xml \\\n            app/src/main/java/jp/co/tenposinfo/register/GoogleDriveAccountActivity.kt \\",
    "            app/src/main/AndroidManifest.xml \\\n            app/src/main/java/jp/co/tenposinfo/register/AdminSettingsActivity.kt \\\n            app/src/main/java/jp/co/tenposinfo/register/GoogleDriveAccountActivity.kt \\",
)
workflow = workflow.replace(
    "            app/src/test/java/jp/co/tenposinfo/register/V047GoogleDriveDiagnosticsTest.kt \\\n            management-app/src/main/AndroidManifest.xml \\",
    "            app/src/test/java/jp/co/tenposinfo/register/V047GoogleDriveDiagnosticsTest.kt \\\n            app/src/test/java/jp/co/tenposinfo/register/V048GoogleDriveSettingsEntryTest.kt \\\n            management-app/src/main/AndroidManifest.xml \\",
)
workflow = workflow.replace(
    "            docs/V0.47_GOOGLE_DRIVE_DIAGNOSTICS.md \\\n            docs/V0.47_RELEASE_NOTES.md \\",
    "            docs/V0.48_GOOGLE_DRIVE_SETTINGS_ENTRY.md \\\n            docs/V0.48_RELEASE_NOTES.md \\\n            docs/V0.47_GOOGLE_DRIVE_DIAGNOSTICS.md \\\n            docs/V0.47_RELEASE_NOTES.md \\",
)
workflow = workflow.replace(
    "          diagnostics = (root / 'GoogleDriveDiagnosticsActivity.kt').read_text()",
    "          diagnostics = (root / 'GoogleDriveDiagnosticsActivity.kt').read_text()\n          admin_settings = (root / 'AdminSettingsActivity.kt').read_text()\n          sync_settings = (root / 'SyncSettingsActivity.kt').read_text()",
)
workflow = workflow.replace(
    "          assert 'putString(\"refresh_token\"' not in diagnostics\n\n          assert 'GoogleDriveSetupGuideActivity' in pos_manifest",
    "          assert 'putString(\"refresh_token\"' not in diagnostics\n\n          for token in ('Google Drive・同期', '初期設定、アカウント、送信状況、診断', 'SyncSettingsActivity::class.java', 'onSync: () -> Unit'):\n              assert token in admin_settings, token\n          for token in ('Google Drive・同期設定', 'Google Drive初期設定・アカウント', 'GoogleDriveSetupGuideActivity::class.java'):\n              assert token in sync_settings, token\n\n          assert 'GoogleDriveSetupGuideActivity' in pos_manifest",
)
workflow = workflow.replace(
    "          for version in ('v047', 'v046'",
    "          for version in ('v048', 'v047', 'v046'",
)
workflow = workflow.replace(
    "          assert not Path('tools/build-apk-v047.generated.yml').exists()",
    "          assert not Path('tools/build-apk-v047.generated.yml').exists()\n          assert not Path('.github/workflows/v048-apply-temp.yml').exists()\n          assert not Path('tools/v048_apply.py').exists()",
)
write(workflow_path, workflow)

# Sanity checks before committing.
admin = read(admin_path)
workflow = read(workflow_path)
for token in (
    "Google Drive・同期",
    "初期設定、アカウント、送信状況、診断",
    "SyncSettingsActivity::class.java",
    "onSync: () -> Unit",
):
    if token not in admin:
        raise RuntimeError(f"missing admin token: {token}")
for token in (
    "V048GoogleDriveSettingsEntryTest.kt",
    "V0.48_GOOGLE_DRIVE_SETTINGS_ENTRY.md",
    "TSUGUREGI_v0.48.0_dev1_drive_settings_entry_debug.apk",
    "TSUGUREGI-v0.48.0-dev1-drive-settings-entry-apks",
):
    if token not in workflow:
        raise RuntimeError(f"missing workflow token: {token}")

print("v0.48 patch applied")
