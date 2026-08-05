from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    if old not in text:
        raise SystemExit(f"replacement target not found: {path}: {old[:120]!r}")
    file.write_text(text.replace(old, new, 1))


def replace_all(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    if old not in text:
        raise SystemExit(f"replacement target not found: {path}: {old!r}")
    file.write_text(text.replace(old, new))


ACCOUNT = "app/src/main/java/jp/co/tenposinfo/register/GoogleDriveAccountActivity.kt"
UPLOAD = "app/src/main/java/jp/co/tenposinfo/register/GoogleDriveDirectUpload.kt"
MANIFEST = "app/src/main/AndroidManifest.xml"
BUILD = "app/build.gradle.kts"
WORKFLOW = ".github/workflows/build-apk.yml"

replace_once(ACCOUNT, "import android.content.Context\n", "import android.content.Context\nimport android.content.Intent\n")
replace_once(
    ACCOUNT,
    "    private val accountStore by lazy { GoogleDriveAccountStore(this) }\n",
    "    private val accountStore by lazy { GoogleDriveAccountStore(this) }\n    private val diagnosticLog by lazy { GoogleDriveDiagnosticLogStore(this) }\n",
)
replace_once(
    ACCOUNT,
    "            state.value = state.value.copy(\n                status = GoogleDriveAccountStatus.AUTHORIZATION_FAILED,\n                message = \"Googleアカウント接続がキャンセルされました\",\n            )\n",
    "            diagnosticLog.append(\"AUTHORIZATION\", \"CANCELLED\", \"Googleアカウント接続がキャンセルされました\")\n            state.value = state.value.copy(\n                status = GoogleDriveAccountStatus.AUTHORIZATION_FAILED,\n                message = \"Googleアカウント接続がキャンセルされました\",\n            )\n",
)
replace_once(
    ACCOUNT,
    "                        onRetry = ::retryFailed,\n                        onDisconnect = ::disconnect,\n",
    "                        onRetry = ::retryFailed,\n                        onDiagnostics = {\n                            startActivity(Intent(this, GoogleDriveDiagnosticsActivity::class.java))\n                        },\n                        onDisconnect = ::disconnect,\n",
)
replace_once(
    ACCOUNT,
    "    private fun authorize(selectAccount: Boolean) {\n        state.value = state.value.copy(\n",
    "    private fun authorize(selectAccount: Boolean) {\n        diagnosticLog.append(\n            \"AUTHORIZATION\",\n            \"STARTED\",\n            if (selectAccount) \"アカウント選択\" else \"接続確認\",\n        )\n        state.value = state.value.copy(\n",
)
replace_once(
    ACCOUNT,
    "        if (result.hasResolution()) {\n            val pendingIntent = result.pendingIntent\n",
    "        if (result.hasResolution()) {\n            diagnosticLog.append(\"AUTHORIZATION\", \"RESOLUTION_REQUIRED\", \"Google同意画面を表示します\")\n            val pendingIntent = result.pendingIntent\n",
)
replace_once(
    ACCOUNT,
    "                onSuccess = { profile ->\n                    GoogleDriveAccountState(\n",
    "                onSuccess = { profile ->\n                    diagnosticLog.append(\"ABOUT_GET\", \"SUCCESS\", \"Drive API接続確認成功\")\n                    GoogleDriveAccountState(\n",
)
replace_once(
    ACCOUNT,
    "                onFailure = { error ->\n                    val apiDisabled = error is GoogleDriveProbeException &&\n",
    "                onFailure = { error ->\n                    val apiDisabled = error is GoogleDriveProbeException &&\n",
)
replace_once(
    ACCOUNT,
    "                    state.value.copy(\n                        status = if (apiDisabled) {\n",
    "                    diagnosticLog.append(\n                        \"ABOUT_GET\",\n                        if (apiDisabled) \"API_DISABLED\" else \"FAILED\",\n                        error.message ?: error.javaClass.simpleName,\n                    )\n                    state.value.copy(\n                        status = if (apiDisabled) {\n",
)
replace_once(
    ACCOUNT,
    "    private fun uploadNow() {\n        JournalOutboxStore(applicationContext).use { it.stagePending(500) }\n",
    "    private fun uploadNow() {\n        diagnosticLog.append(\"MANUAL_UPLOAD\", \"REQUESTED\", \"アカウント画面から今すぐアップロード\")\n        JournalOutboxStore(applicationContext).use { it.stagePending(500) }\n",
)
replace_once(
    ACCOUNT,
    "            GoogleDriveDirectUploadScheduler.enqueueNow(applicationContext)\n            uploadStatus.value = uploadStatus.value.copy(\n",
    "            GoogleDriveDirectUploadScheduler.enqueueNow(applicationContext)\n            diagnosticLog.append(\"RETRY_FAILED\", \"REQUESTED\", \"$count 件を再試行へ戻しました\")\n            uploadStatus.value = uploadStatus.value.copy(\n",
)
replace_once(
    ACCOUNT,
    "        val status = GoogleDriveAccountPolicy.statusForAuthorizationError(error)\n        state.value = state.value.copy(\n",
    "        val status = GoogleDriveAccountPolicy.statusForAuthorizationError(error)\n        diagnosticLog.append(\n            \"AUTHORIZATION\",\n            status.name,\n            error.message ?: error.javaClass.simpleName,\n        )\n        state.value = state.value.copy(\n",
)
replace_once(
    ACCOUNT,
    "        if (email.isNullOrBlank()) {\n            accountStore.clear()\n",
    "        if (email.isNullOrBlank()) {\n            diagnosticLog.append(\"DISCONNECT\", \"LOCAL_CLEAR\", \"登録アカウントなし\")\n            accountStore.clear()\n",
)
replace_once(
    ACCOUNT,
    "            .addOnCompleteListener {\n                accountStore.clear()\n",
    "            .addOnCompleteListener {\n                diagnosticLog.append(\n                    \"DISCONNECT\",\n                    if (it.isSuccessful) \"SUCCESS\" else \"GOOGLE_RESULT_UNKNOWN\",\n                    \"ローカル売上データは削除していません\",\n                )\n                accountStore.clear()\n",
)
replace_once(
    ACCOUNT,
    "    onRetry: () -> Unit,\n    onDisconnect: () -> Unit,\n",
    "    onRetry: () -> Unit,\n    onDiagnostics: () -> Unit,\n    onDisconnect: () -> Unit,\n",
)
replace_once(
    ACCOUNT,
    "        Card(modifier = Modifier.fillMaxWidth()) {\n            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {\n                Text(\"v0.45 直接同期仕様\", fontWeight = FontWeight.Bold)\n",
    "        OutlinedButton(\n            onClick = onDiagnostics,\n            modifier = Modifier.fillMaxWidth().height(48.dp),\n        ) { Text(\"診断・ログ\") }\n\n        Card(modifier = Modifier.fillMaxWidth()) {\n            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {\n                Text(\"v0.47 診断対応済み直接同期\", fontWeight = FontWeight.Bold)\n",
)

replace_once(
    UPLOAD,
    "    override fun doWork(): Result {\n        if (GoogleDriveAccountStore(applicationContext).load().email == null) return Result.success()\n        return runCatching {\n",
    "    override fun doWork(): Result {\n        val diagnosticLog = GoogleDriveDiagnosticLogStore(applicationContext)\n        if (GoogleDriveAccountStore(applicationContext).load().email == null) {\n            diagnosticLog.append(\"UPLOAD_WORKER\", \"SKIPPED\", \"Googleアカウント未登録\")\n            return Result.success()\n        }\n        diagnosticLog.append(\"UPLOAD_WORKER\", \"STARTED\", \"Drive API直接送信開始\")\n        return runCatching {\n",
)
replace_once(
    UPLOAD,
    "            onSuccess = { if (it.retryRecommended) Result.retry() else Result.success() },\n            onFailure = { error ->\n                val category = GoogleDriveApiErrorPolicy.classify(error)\n",
    "            onSuccess = { runResult ->\n                diagnosticLog.append(\n                    \"UPLOAD_WORKER\",\n                    if (runResult.retryRecommended) \"RETRY\" else \"SUCCESS\",\n                    \"送信=${runResult.uploadedCount},既存=${runResult.duplicateCount},再試行=${runResult.retryCount},永久失敗=${runResult.permanentFailureCount}\",\n                )\n                if (runResult.retryRecommended) Result.retry() else Result.success()\n            },\n            onFailure = { error ->\n                val category = GoogleDriveApiErrorPolicy.classify(error)\n                diagnosticLog.append(\n                    \"UPLOAD_WORKER\",\n                    category.name,\n                    error.message ?: error.javaClass.simpleName,\n                )\n",
)

replace_once(
    MANIFEST,
    "        <activity\n            android:name=\".GoogleDriveSetupGuideActivity\"\n            android:exported=\"false\"\n            android:screenOrientation=\"landscape\" />\n",
    "        <activity\n            android:name=\".GoogleDriveSetupGuideActivity\"\n            android:exported=\"false\"\n            android:screenOrientation=\"landscape\" />\n\n        <activity\n            android:name=\".GoogleDriveDiagnosticsActivity\"\n            android:exported=\"false\"\n            android:screenOrientation=\"landscape\" />\n",
)
replace_all(BUILD, "versionCode = 76", "versionCode = 77")
replace_all(BUILD, 'versionName = "0.46.0-dev.1"', 'versionName = "0.47.0-dev.1"')

for file in Path("app/src/test").rglob("*.kt"):
    text = file.read_text()
    text = text.replace("versionCode = 76", "versionCode = 77")
    text = text.replace("0.46.0-dev.1", "0.47.0-dev.1")
    text = text.replace(
        "TSUGUREGI_v0.46.0_dev1_drive_setup_guide_debug.apk",
        "TSUGUREGI_v0.47.0_dev1_drive_diagnostics_debug.apk",
    )
    file.write_text(text)

workflow = Path(WORKFLOW).read_text()
workflow = workflow.replace("v0.14-v0.46", "v0.14-v0.47")
workflow = workflow.replace("versionCode = 76", "versionCode = 77")
workflow = workflow.replace("0.46.0-dev.1", "0.47.0-dev.1")
workflow = workflow.replace(
    "TSUGUREGI_v0.46.0_dev1_drive_setup_guide_debug.apk",
    "TSUGUREGI_v0.47.0_dev1_drive_diagnostics_debug.apk",
)
workflow = workflow.replace(
    "TSUGUREGI-v0.46.0-dev1-drive-setup-guide-apks",
    "TSUGUREGI-v0.47.0-dev1-drive-diagnostics-apks",
)
workflow = workflow.replace(
    "            app/src/main/java/jp/co/tenposinfo/register/GoogleDriveSetupGuideActivity.kt \\\n",
    "            app/src/main/java/jp/co/tenposinfo/register/GoogleDriveSetupGuideActivity.kt \\\n            app/src/main/java/jp/co/tenposinfo/register/GoogleDriveDiagnosticsActivity.kt \\\n",
)
workflow = workflow.replace(
    "            app/src/test/java/jp/co/tenposinfo/register/V046GoogleDriveSetupGuideTest.kt \\\n",
    "            app/src/test/java/jp/co/tenposinfo/register/V046GoogleDriveSetupGuideTest.kt \\\n            app/src/test/java/jp/co/tenposinfo/register/V047GoogleDriveDiagnosticsTest.kt \\\n",
)
workflow = workflow.replace(
    "            docs/V0.46_GOOGLE_DRIVE_SETUP_GUIDE.md \\\n",
    "            docs/V0.47_GOOGLE_DRIVE_DIAGNOSTICS.md \\\n            docs/V0.47_RELEASE_NOTES.md \\\n            docs/V0.46_GOOGLE_DRIVE_SETUP_GUIDE.md \\\n",
)
workflow = workflow.replace(
    "          setup_guide = (root / 'GoogleDriveSetupGuideActivity.kt').read_text()\n",
    "          setup_guide = (root / 'GoogleDriveSetupGuideActivity.kt').read_text()\n          diagnostics = (root / 'GoogleDriveDiagnosticsActivity.kt').read_text()\n",
)
workflow = workflow.replace(
    "          assert 'GoogleDriveSetupGuideActivity' in pos_manifest\n",
    "          for token in ('GoogleDriveDiagnosticLogStore', 'GoogleDriveDiagnosticRepository', 'GoogleDriveDiagnosticReport', 'Intent.ACTION_SEND', 'REDACTED_TOKEN', 'content://[REDACTED]'):\n              assert token in diagnostics, token\n          assert 'putString(\"access_token\"' not in diagnostics\n          assert 'putString(\"refresh_token\"' not in diagnostics\n\n          assert 'GoogleDriveSetupGuideActivity' in pos_manifest\n          assert 'GoogleDriveDiagnosticsActivity' in pos_manifest\n          assert 'GoogleDriveDiagnosticsActivity::class.java' in pos_account\n          assert 'GoogleDriveDiagnosticLogStore' in pos_drive\n",
)
workflow = workflow.replace(
    "          for version in ('v046', 'v045'",
    "          for version in ('v047', 'v046', 'v045'",
)
workflow = workflow.replace(
    "          assert not Path('tools/v046_apply.py').exists()\n",
    "          assert not Path('tools/v046_apply.py').exists()\n          assert not Path('.github/workflows/v047-apply-temp.yml').exists()\n          assert not Path('tools/v047_apply.py').exists()\n",
)
workflow = workflow.replace(
    "          GOOGLE_DRIVE_SETUP_GUIDE=true\n",
    "          GOOGLE_DRIVE_SETUP_GUIDE=true\n          GOOGLE_DRIVE_DIAGNOSTICS=true\n          GOOGLE_DRIVE_DIAGNOSTIC_EVENT_LIMIT=100\n          GOOGLE_DRIVE_DIAGNOSTIC_TOKENS_INCLUDED=false\n          GOOGLE_DRIVE_DIAGNOSTIC_SALES_JSON_INCLUDED=false\n",
)
Path(WORKFLOW).write_text(workflow)

print("v0.47 source updates applied")
