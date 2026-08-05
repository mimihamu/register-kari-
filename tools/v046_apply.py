from pathlib import Path


def replace(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    if new in text:
        return
    if old not in text:
        raise RuntimeError(f"replacement source not found: {path}: {old[:100]!r}")
    file.write_text(text.replace(old, new, 1))


replace(
    "app/src/main/AndroidManifest.xml",
    '''        <activity
            android:name=".GoogleDriveAccountActivity"
            android:exported="false"
            android:screenOrientation="landscape" />
''',
    '''        <activity
            android:name=".GoogleDriveAccountActivity"
            android:exported="false"
            android:screenOrientation="landscape" />

        <activity
            android:name=".GoogleDriveSetupGuideActivity"
            android:exported="false"
            android:screenOrientation="landscape" />
''',
)

replace(
    "app/src/main/java/jp/co/tenposinfo/register/SyncSettingsActivity.kt",
    "context.startActivity(Intent(context, GoogleDriveAccountActivity::class.java))",
    "context.startActivity(Intent(context, GoogleDriveSetupGuideActivity::class.java))",
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/SyncSettingsActivity.kt",
    'Text("Googleアカウント・直接送信")',
    'Text("Google Drive初期設定・アカウント")',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/GoogleDriveAccountActivity.kt",
    '"Google CloudにapplicationIdと署名SHA-1を登録してください"',
    '"Google Cloud設定が必要です。同期設定の初期設定ガイドを確認してください"',
)
replace(
    "app/src/main/java/jp/co/tenposinfo/register/GoogleDriveAccountActivity.kt",
    '"Google CloudでGoogle Drive APIを有効にしてください"',
    '"Google Drive APIが無効です。同期設定の初期設定ガイドを確認してください"',
)
replace("app/build.gradle.kts", "versionCode = 75", "versionCode = 76")
replace("app/build.gradle.kts", 'versionName = "0.45.0-dev.1"', 'versionName = "0.46.0-dev.1"')

for root in ("app/src/test", "management-app/src/test"):
    for file in Path(root).rglob("*.kt"):
        text = file.read_text()
        text = text.replace("versionCode = 75", "versionCode = 76")
        text = text.replace('versionName = \\"0.45.0-dev.1\\"', 'versionName = \\"0.46.0-dev.1\\"')
        text = text.replace(
            "TSUGUREGI_v0.45.0_dev1_drive_api_sync_debug.apk",
            "TSUGUREGI_v0.46.0_dev1_drive_setup_guide_debug.apk",
        )
        text = text.replace(
            "TSUGUREGI-v0.45.0-dev1-drive-api-sync-apks",
            "TSUGUREGI-v0.46.0-dev1-drive-setup-guide-apks",
        )
        file.write_text(text)

workflow = Path(".github/workflows/build-apk.yml")
text = workflow.read_text()
text = text.replace("Verify cumulative v0.14-v0.45 sources", "Verify cumulative v0.14-v0.46 sources")
text = text.replace("grep -q 'versionCode = 75' app/build.gradle.kts", "grep -q 'versionCode = 76' app/build.gradle.kts")
text = text.replace(
    "grep -q 'versionName = \\\"0.45.0-dev.1\\\"' app/build.gradle.kts",
    "grep -q 'versionName = \\\"0.46.0-dev.1\\\"' app/build.gradle.kts",
)
text = text.replace(
    "            app/src/main/java/jp/co/tenposinfo/register/GoogleDriveAccountActivity.kt \\\n",
    "            app/src/main/java/jp/co/tenposinfo/register/GoogleDriveAccountActivity.kt \\\n"
    "            app/src/main/java/jp/co/tenposinfo/register/GoogleDriveSetupGuideActivity.kt \\\n",
)
text = text.replace(
    "            app/src/test/java/jp/co/tenposinfo/register/V045GoogleDriveDirectUploadTest.kt \\\n",
    "            app/src/test/java/jp/co/tenposinfo/register/V045GoogleDriveDirectUploadTest.kt \\\n"
    "            app/src/test/java/jp/co/tenposinfo/register/V046GoogleDriveSetupGuideTest.kt \\\n",
)
text = text.replace(
    "            docs/V0.45_GOOGLE_DRIVE_DIRECT_SYNC.md \\\n",
    "            docs/V0.46_GOOGLE_DRIVE_SETUP_GUIDE.md \\\n"
    "            docs/V0.46_RELEASE_NOTES.md \\\n"
    "            docs/V0.45_GOOGLE_DRIVE_DIRECT_SYNC.md \\\n",
)
text = text.replace(
    "          pos_drive = (root / 'GoogleDriveDirectUpload.kt').read_text()",
    "          pos_drive = (root / 'GoogleDriveDirectUpload.kt').read_text()\n"
    "          setup_guide = (root / 'GoogleDriveSetupGuideActivity.kt').read_text()",
)
text = text.replace(
    "          assert 'GoogleDriveDirectUploadBootstrapProvider' in pos_manifest",
    "          for token in ('GET_SIGNING_CERTIFICATES', 'MessageDigest.getInstance', "
    "'applicationId（パッケージ名）', 'Google Cloud Consoleを開く', 'Googleアカウント登録へ進む'):\n"
    "              assert token in setup_guide, token\n\n"
    "          assert 'GoogleDriveSetupGuideActivity' in pos_manifest\n"
    "          assert 'GoogleDriveDirectUploadBootstrapProvider' in pos_manifest",
)
text = text.replace(
    "for version in ('v045', 'v044'",
    "for version in ('v046', 'v045', 'v044'",
)
text = text.replace(
    "          assert not Path('.github/workflows/v045-source-export-temp.yml').exists()",
    "          assert not Path('.github/workflows/v045-source-export-temp.yml').exists()\n"
    "          assert not Path('.github/workflows/v046-apply-temp.yml').exists()\n"
    "          assert not Path('.github/workflows/v046-trigger-temp.yml').exists()\n"
    "          assert not Path('tools/v046_apply.py').exists()",
)
text = text.replace(
    "TSUGUREGI_v0.45.0_dev1_drive_api_sync_debug.apk",
    "TSUGUREGI_v0.46.0_dev1_drive_setup_guide_debug.apk",
)
text = text.replace("REGISTER_VERSION_NAME=0.45.0-dev.1", "REGISTER_VERSION_NAME=0.46.0-dev.1")
text = text.replace("REGISTER_VERSION_CODE=75", "REGISTER_VERSION_CODE=76")
text = text.replace(
    "TSUGUREGI-v0.45.0-dev1-drive-api-sync-apks",
    "TSUGUREGI-v0.46.0-dev1-drive-setup-guide-apks",
)
text = text.replace(
    "          GOOGLE_DRIVE_DIRECT_UPLOAD=true",
    "          GOOGLE_DRIVE_SETUP_GUIDE=true\n          GOOGLE_DRIVE_DIRECT_UPLOAD=true",
)
workflow.write_text(text)
