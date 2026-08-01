from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]

EXPECTED = {
    "app/build.gradle.kts": "40be7aa1dc5eb2ed6b0cd8127f88180d3c1449b5",
    "app/src/main/AndroidManifest.xml": "9f5d6d8eb775eb71c24cabeb69bf0eee53165bd9",
    "app/src/debug/AndroidManifest.xml": "2cf73a9d0091e40fe2d865507cc8cbc55686a263",
    "app/src/main/java/jp/co/tenposinfo/register/PrinterStatusCapability.kt": "bf83b242e3eedb5250ffcdd731777faec808af1a",
    "app/src/main/java/jp/co/tenposinfo/register/PrinterSoakTestActivity.kt": "129f4a84fd73265c44d72ebbfeca8d8186b2de35",
    "app/src/main/java/jp/co/tenposinfo/register/PrinterSoakTestResultStore.kt": "4a482253ec6e91ccacc6aa10be05d0cd5a2de373",
    "app/src/main/java/jp/co/tenposinfo/register/PrinterToolsHubActivity.kt": "bcb2cffc66336f530df88cae91104c39b2ccc8b2",
}

def path(rel: str) -> Path:
    return ROOT / rel

def blob_sha(rel: str) -> str:
    return subprocess.check_output(["git", "hash-object", rel], cwd=ROOT, text=True).strip()

def verify() -> None:
    mismatches = []
    for rel, expected in EXPECTED.items():
        actual = blob_sha(rel)
        if actual != expected:
            mismatches.append(f"{rel}: expected {expected}, actual {actual}")
    if mismatches:
        raise SystemExit("Latest blob verification failed:\n" + "\n".join(mismatches))

def replace_once(rel: str, old: str, new: str) -> None:
    target = path(rel)
    text = target.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{rel}: replacement target count={count}, expected 1")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")

verify()

replace_once(
    "app/build.gradle.kts",
    '        versionCode = 35\n        versionName = "0.12.0-dev.21"',
    '        versionCode = 36\n        versionName = "0.12.0-dev.22"',
)

capability_path = path("app/src/main/java/jp/co/tenposinfo/register/PrinterStatusCapability.kt")
capability_text = capability_path.read_text(encoding="utf-8")
capability_text += '''

data class PrinterSoakTestCapabilityDecision(
    val allowed: Boolean,
    val reason: String,
)

object PrinterSoakTestCapabilityPolicy {
    fun evaluate(
        profile: PrinterProfile,
        statusProtocol: PrinterStatusProtocol,
    ): PrinterSoakTestCapabilityDecision {
        if (statusProtocol == PrinterStatusProtocol.NONE) {
            return PrinterSoakTestCapabilityDecision(
                allowed = false,
                reason = "状態取得非対応のプリンターでは連続印刷試験を開始できません",
            )
        }
        val capability = PrinterStatusCapabilityRegistry.forProfile(profile)
        val allowed =
            profile == PrinterProfile.EPSON_TM_JAPAN &&
                statusProtocol == PrinterStatusProtocol.EPSON_DLE_EOT &&
                capability.decision(PrinterStatusCheckPurpose.SOAK_TEST) ==
                PrinterStatusCheckDecision.ALLOWED
        return if (allowed) {
            PrinterSoakTestCapabilityDecision(
                allowed = true,
                reason = "EPSON仕様確認済みの状態取得方式で連続印刷試験を開始できます",
            )
        } else {
            PrinterSoakTestCapabilityDecision(
                allowed = false,
                reason = PrinterStatusCapabilityRegistry.denialMessage(
                    profile,
                    PrinterStatusCheckPurpose.SOAK_TEST,
                ),
            )
        }
    }
}
'''
capability_path.write_text(capability_text, encoding="utf-8")

replace_once(
    "app/src/main/java/jp/co/tenposinfo/register/PrinterSoakTestActivity.kt",
    '''            if (configuration.profile.statusProtocol == PrinterStatusProtocol.NONE) {
                running = false
                testJob = null
                statusMessage = "状態取得非対応のプリンターでは連続印刷試験を開始できません"
                statusColor = StRed
                addLog(statusMessage)
                return@launch
            }

            activeRunId = withContext(Dispatchers.IO) {
''',
    '''            val capabilityDecision = PrinterSoakTestCapabilityPolicy.evaluate(
                profile = configuration.profile,
                statusProtocol = configuration.profile.statusProtocol,
            )
            if (!capabilityDecision.allowed) {
                running = false
                testJob = null
                activeRunId = null
                statusMessage = capabilityDecision.reason
                statusColor = StRed
                addLog("開始拒否：${capabilityDecision.reason}")
                return@launch
            }

            activeRunId = withContext(Dispatchers.IO) {
''',
)
replace_once(
    "app/src/main/java/jp/co/tenposinfo/register/PrinterSoakTestActivity.kt",
    '''                    TcpPrinterStatusClient(configuration).query()
''',
    '''                    TcpPrinterStatusClient(configuration).query(
                        purpose = PrinterStatusCheckPurpose.SOAK_TEST,
                    )
''',
)

result_store = path("app/src/main/java/jp/co/tenposinfo/register/PrinterSoakTestResultStore.kt")
result_text = result_store.read_text(encoding="utf-8")
start = result_text.index("    fun finish(\n")
end = result_text.index("\n    fun recordCsvExport", start)
new_finish = '''    fun finish(
        runId: Long,
        status: PrinterSoakTestRunStatus,
        completedCount: Int,
        summary: String,
        actor: String,
        finishedAt: Long = System.currentTimeMillis(),
    ): PrinterSoakTestStoredResult {
        require(status != PrinterSoakTestRunStatus.RUNNING)
        val before = requireNotNull(loadRun(runId)) { "連続印刷試験結果が見つかりません" }
        val resolution = PrinterSoakTestFinishPolicy.resolve(
            currentStatus = before.status,
            currentCompletedCount = before.completedCount,
            currentSummary = before.summary,
            requestedCompletedCount = completedCount,
            requestedSummary = summary,
        )
        if (!resolution.shouldFinalize) {
            val existingCsv = PrinterSoakTestCsv.render(before, listSteps(runId))
            return PrinterSoakTestStoredResult(runId, before.csvPath, existingCsv)
        }

        val claimed = transaction {
            update(
                "printer_soak_test_runs",
                ContentValues().apply {
                    put("finished_at", finishedAt)
                    put("completed_count", resolution.completedCount)
                    put("status", status.name)
                    put("summary", resolution.summary)
                },
                "id = ? AND status = ?",
                arrayOf(runId.toString(), PrinterSoakTestRunStatus.RUNNING.name),
            )
        }
        if (claimed <= 0) {
            val existing = requireNotNull(loadRun(runId)) { "連続印刷試験結果が見つかりません" }
            return PrinterSoakTestStoredResult(
                runId = runId,
                csvPath = existing.csvPath,
                csvText = PrinterSoakTestCsv.render(existing, listSteps(runId)),
            )
        }

        val run = requireNotNull(loadRun(runId)) { "連続印刷試験結果が見つかりません" }
        val csvText = PrinterSoakTestCsv.render(run, listSteps(runId))
        val csvPath = runCatching {
            exportDirectory.mkdirs()
            val file = File(exportDirectory, "TSUGUREGI_printer_soak_test_${runId}.csv")
            file.writeText("\uFEFF$csvText", Charsets.UTF_8)
            file.absolutePath
        }.getOrNull()

        transaction {
            update(
                "printer_soak_test_runs",
                ContentValues().apply {
                    if (csvPath == null) putNull("csv_path") else put("csv_path", csvPath)
                },
                "id = ? AND csv_path IS NULL",
                arrayOf(runId.toString()),
            )
            insertAudit(
                eventType = when (status) {
                    PrinterSoakTestRunStatus.COMPLETED -> "PRINTER_SOAK_TEST_COMPLETED"
                    PrinterSoakTestRunStatus.STOPPED -> "PRINTER_SOAK_TEST_STOPPED"
                    PrinterSoakTestRunStatus.FAILED -> "PRINTER_SOAK_TEST_FAILED"
                    PrinterSoakTestRunStatus.RUNNING -> error("RUNNINGは終了状態ではありません")
                },
                referenceId = runId,
                detail = "${resolution.completedCount}/${run.totalPlanned} / " +
                    "${resolution.summary.take(500)} / CSV=${csvPath ?: "保存失敗"}",
                actor = actor,
                createdAt = finishedAt,
            )
        }
        val stored = requireNotNull(loadRun(runId)) { "連続印刷試験結果が見つかりません" }
        return PrinterSoakTestStoredResult(runId, stored.csvPath, csvText)
    }
'''
result_store.write_text(result_text[:start] + new_finish + result_text[end:], encoding="utf-8")

replace_once(
    "app/src/main/java/jp/co/tenposinfo/register/PrinterToolsHubActivity.kt",
    '''                    onOpenAnalysis = { startActivity(Intent(this, PrinterStatusAnalysisActivity::class.java)) },
                    onOpenNotification = { startActivity(Intent(this, PrinterNotificationSettingsActivity::class.java)) },
''',
    '''                    onOpenAnalysis = { startActivity(Intent(this, PrinterStatusAnalysisActivity::class.java)) },
                    onOpenValidation = { startActivity(Intent(this, PrinterStatusValidationActivity::class.java)) },
                    onOpenNotification = { startActivity(Intent(this, PrinterNotificationSettingsActivity::class.java)) },
''',
)
replace_once(
    "app/src/main/java/jp/co/tenposinfo/register/PrinterToolsHubActivity.kt",
    '''    onOpenAnalysis: () -> Unit,
    onOpenNotification: () -> Unit,
''',
    '''    onOpenAnalysis: () -> Unit,
    onOpenValidation: () -> Unit,
    onOpenNotification: () -> Unit,
''',
)
replace_once(
    "app/src/main/java/jp/co/tenposinfo/register/PrinterToolsHubActivity.kt",
    '''                        PrinterHubAction("応答分析", "型番別の採取進捗と正常時との差分ビット候補", PhIndigo, onOpenAnalysis, Modifier.weight(1f))
                        PrinterHubAction("管理者通知", "Android通知の許可・端末通知設定", PhBlue, onOpenNotification, Modifier.weight(1f))
''',
    '''                        PrinterHubAction("応答分析", "型番別の採取進捗と正常時との差分ビット候補", PhIndigo, onOpenAnalysis, Modifier.weight(1f))
                        PrinterHubAction("最終検証・承認", "再現性、外れ値、信頼度、候補審査。runtime未適用", PhPurple, onOpenValidation, Modifier.weight(1f))
                        PrinterHubAction("管理者通知", "Android通知の許可・端末通知設定", PhBlue, onOpenNotification, Modifier.weight(1f))
''',
)

replace_once(
    "app/src/main/AndroidManifest.xml",
    '''        <activity
            android:name=".PrinterStatusAnalysisActivity"
            android:exported="false"
            android:screenOrientation="landscape" />

        <activity
            android:name=".PrinterNotificationSettingsActivity"
''',
    '''        <activity
            android:name=".PrinterStatusAnalysisActivity"
            android:exported="false"
            android:screenOrientation="landscape" />

        <activity
            android:name=".PrinterStatusValidationActivity"
            android:exported="false"
            android:label="つぐレジ プリンター検証"
            android:screenOrientation="landscape" />

        <activity
            android:name=".PrinterNotificationSettingsActivity"
''',
)

replace_once(
    "app/src/debug/AndroidManifest.xml",
    '''        <activity
            android:name=".PrinterNotificationSettingsActivity"
''',
    '''        <activity
            android:name=".PrinterStatusValidationActivity"
            android:exported="true"
            android:label="つぐレジ プリンター検証"
            android:screenOrientation="landscape"
            tools:replace="android:exported">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <activity
            android:name=".PrinterNotificationSettingsActivity"
''',
)

path(".github/workflows/build-apk.yml").write_text('''name: Build つぐレジ APK

on:
  push:
    branches:
      - main
      - develop/**
  pull_request:
  workflow_dispatch:

permissions:
  contents: read

jobs:
  build-debug-apk:
    runs-on: ubuntu-latest
    timeout-minutes: 30

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"

      - name: Set up Android SDK
        uses: android-actions/setup-android@v3

      - name: Install Android API 36
        run: |
          yes | sdkmanager --licenses >/dev/null || true
          sdkmanager "platforms;android-36" "build-tools;36.0.0"

      - name: Set up Gradle 9.5
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: "9.5.0"

      - name: Verify dev.22 metadata
        run: |
          grep -q 'versionCode = 36' app/build.gradle.kts
          grep -q 'versionName = "0.12.0-dev.22"' app/build.gradle.kts

      - name: Run unit tests
        run: gradle --no-daemon :app:testDebugUnitTest

      - name: Build debug APK
        run: gradle --no-daemon :app:assembleDebug

      - name: Prepare named APK and SHA-256
        run: |
          mkdir -p artifacts
          cp app/build/outputs/apk/debug/app-debug.apk artifacts/TSUGUREGI_v0.12.0_dev22_debug.apk
          sha256sum artifacts/TSUGUREGI_v0.12.0_dev22_debug.apk | tee artifacts/TSUGUREGI_v0.12.0_dev22_debug.apk.sha256
          stat --printf='APK_SIZE_BYTES=%s\n' artifacts/TSUGUREGI_v0.12.0_dev22_debug.apk | tee artifacts/build-summary.txt
          echo "VERSION_NAME=0.12.0-dev.22" >> artifacts/build-summary.txt
          echo "VERSION_CODE=36" >> artifacts/build-summary.txt
          echo "SIGNING=development-not-production" >> artifacts/build-summary.txt
          echo "REAL_PRINTER_VERIFICATION=not-performed-by-ci" >> artifacts/build-summary.txt

      - name: Upload APK
        id: upload
        uses: actions/upload-artifact@v4
        with:
          name: TSUGUREGI-v0.12.0-dev22-debug-apk
          path: |
            artifacts/TSUGUREGI_v0.12.0_dev22_debug.apk
            artifacts/TSUGUREGI_v0.12.0_dev22_debug.apk.sha256
            artifacts/build-summary.txt
          if-no-files-found: error
          retention-days: 30

      - name: Report artifact ID
        run: echo "ARTIFACT_ID=${{ steps.upload.outputs.artifact-id }}"
''', encoding="utf-8")
