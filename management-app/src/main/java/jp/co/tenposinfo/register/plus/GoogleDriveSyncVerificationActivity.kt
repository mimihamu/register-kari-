package jp.co.tenposinfo.register.plus

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class GoogleDriveSyncHealth {
    SETUP_REQUIRED,
    NEVER_RUN,
    RUNNING,
    STALLED,
    HEALTHY,
    WARNING,
    ERROR,
}

data class GoogleDriveSyncVerificationSnapshot(
    val accountEmail: String?,
    val selectedMode: GoogleDriveOperatingMode,
    val resolvedMode: GoogleDriveResolvedMode,
    val folderStatus: DriveConnectionStatus,
    val directStatus: GoogleDriveDirectSyncStatus,
    val folderSummary: ImportFolderScanSummary?,
    val dashboard: ImportDashboard,
    val recentRejectionCount: Int,
)

object GoogleDriveSyncVerificationPolicy {
    const val STALE_RUNNING_MILLIS = 30L * 60L * 1_000L
    const val STALE_SUCCESS_MILLIS = 48L * 60L * 60L * 1_000L

    fun isStalled(status: GoogleDriveDirectSyncStatus, nowMillis: Long): Boolean =
        status.running && (
            status.lastStartedAt == null ||
                nowMillis - status.lastStartedAt > STALE_RUNNING_MILLIS
            )

    fun health(
        snapshot: GoogleDriveSyncVerificationSnapshot,
        nowMillis: Long,
    ): GoogleDriveSyncHealth {
        if (snapshot.resolvedMode == GoogleDriveResolvedMode.UNDECIDED) {
            return GoogleDriveSyncHealth.SETUP_REQUIRED
        }
        if (snapshot.directStatus.running) {
            return if (isStalled(snapshot.directStatus, nowMillis)) {
                GoogleDriveSyncHealth.STALLED
            } else {
                GoogleDriveSyncHealth.RUNNING
            }
        }
        val lastCompletedAt = when (snapshot.resolvedMode) {
            GoogleDriveResolvedMode.DRIVE_API -> snapshot.directStatus.lastCompletedAt
            GoogleDriveResolvedMode.COMPATIBILITY_FOLDER -> snapshot.folderSummary?.scannedAt
            GoogleDriveResolvedMode.UNDECIDED -> null
        } ?: return GoogleDriveSyncHealth.NEVER_RUN

        if (snapshot.resolvedMode == GoogleDriveResolvedMode.DRIVE_API) {
            snapshot.directStatus.lastFailureCategory?.let { category ->
                return if (category.retryable) {
                    GoogleDriveSyncHealth.WARNING
                } else {
                    GoogleDriveSyncHealth.ERROR
                }
            }
            if (
                snapshot.directStatus.errorCount > 0 ||
                snapshot.directStatus.rejectedCount > 0
            ) {
                return GoogleDriveSyncHealth.WARNING
            }
        }
        if (
            snapshot.resolvedMode == GoogleDriveResolvedMode.COMPATIBILITY_FOLDER &&
            (snapshot.folderSummary?.readErrorCount ?: 0) > 0
        ) {
            return GoogleDriveSyncHealth.WARNING
        }
        if (nowMillis - lastCompletedAt > STALE_SUCCESS_MILLIS) {
            return GoogleDriveSyncHealth.WARNING
        }
        return GoogleDriveSyncHealth.HEALTHY
    }

    fun nextAction(
        snapshot: GoogleDriveSyncVerificationSnapshot,
        nowMillis: Long,
    ): String = when (health(snapshot, nowMillis)) {
        GoogleDriveSyncHealth.SETUP_REQUIRED ->
            "先に運用セットアップでDrive APIまたは互換フォルダ方式を確定してください。"
        GoogleDriveSyncHealth.NEVER_RUN ->
            "現在の方式で差分同期を実行し、売上JSONの取得と重複防止を確認してください。"
        GoogleDriveSyncHealth.RUNNING ->
            "同期処理中です。完了後に状態を再読込してください。"
        GoogleDriveSyncHealth.STALLED ->
            "30分以上完了していません。自動同期設定を修復してから再実行してください。"
        GoogleDriveSyncHealth.HEALTHY ->
            "同期は正常です。直近履歴と取込件数を確認してください。"
        GoogleDriveSyncHealth.WARNING ->
            "隔離、読取失敗、古い同期結果のいずれかがあります。再実行後も残る場合は診断レポートを共有してください。"
        GoogleDriveSyncHealth.ERROR ->
            "再認可、API有効化、権限確認が必要です。設定を修復して再実行してください。"
    }
}

data class GoogleDriveSyncVerificationRecord(
    val recordedAt: Long,
    val mode: GoogleDriveResolvedMode,
    val success: Boolean,
    val listedCount: Int,
    val importedCount: Int,
    val duplicateCount: Int,
    val rejectedCount: Int,
    val errorCount: Int,
    val message: String,
)

class GoogleDriveSyncVerificationHistoryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "tsuguregi_plus_drive_sync_verification_history",
        Context.MODE_PRIVATE,
    )

    fun load(): List<GoogleDriveSyncVerificationRecord> {
        val raw = preferences.getString("history", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        GoogleDriveSyncVerificationRecord(
                            recordedAt = item.optLong("recordedAt"),
                            mode = runCatching {
                                GoogleDriveResolvedMode.valueOf(item.optString("mode"))
                            }.getOrDefault(GoogleDriveResolvedMode.UNDECIDED),
                            success = item.optBoolean("success"),
                            listedCount = item.optInt("listedCount"),
                            importedCount = item.optInt("importedCount"),
                            duplicateCount = item.optInt("duplicateCount"),
                            rejectedCount = item.optInt("rejectedCount"),
                            errorCount = item.optInt("errorCount"),
                            message = item.optString("message").take(240),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun append(record: GoogleDriveSyncVerificationRecord): List<GoogleDriveSyncVerificationRecord> {
        val records = (listOf(record) + load()).take(MAX_HISTORY)
        val array = JSONArray()
        records.forEach { item ->
            array.put(
                JSONObject()
                    .put("recordedAt", item.recordedAt)
                    .put("mode", item.mode.name)
                    .put("success", item.success)
                    .put("listedCount", item.listedCount)
                    .put("importedCount", item.importedCount)
                    .put("duplicateCount", item.duplicateCount)
                    .put("rejectedCount", item.rejectedCount)
                    .put("errorCount", item.errorCount)
                    .put("message", item.message.take(240)),
            )
        }
        preferences.edit().putString("history", array.toString()).apply()
        return records
    }

    fun clear(): List<GoogleDriveSyncVerificationRecord> {
        preferences.edit().remove("history").apply()
        return emptyList()
    }

    companion object {
        const val MAX_HISTORY = 20
    }
}

data class CompatibilityFolderSyncResult(
    val discoveredCount: Int,
    val changedCount: Int,
    val importedCount: Int,
    val duplicateCount: Int,
    val rejectedCount: Int,
    val readErrorCount: Int,
)

class CompatibilityFolderSyncRunner(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val database = ManagementDatabase(appContext)
    private val folderRepository = FolderImportRepository(database)
    private val importRepository = SalesJournalImportRepository(database)
    private val folderPreferences = ImportFolderPreferences(appContext)
    private val documentSource = SalesJournalDocumentSource(appContext.contentResolver)

    fun synchronize(forceRescan: Boolean = false): CompatibilityFolderSyncResult {
        val registration = folderPreferences.registration()
            ?: error("互換フォルダが登録されていません")
        val known = folderRepository.knownFingerprints(registration.treeUri)
        val scan = documentSource.scanFolder(
            treeUri = android.net.Uri.parse(registration.treeUri),
            knownFingerprints = known,
            forceRescan = forceRescan,
        )
        val batch = if (scan.documents.isEmpty()) {
            null
        } else {
            importRepository.importDocuments(scan.documents)
        }
        folderRepository.recordProcessedFiles(
            treeUri = registration.treeUri,
            files = scan.processedFiles,
        )
        folderPreferences.saveLastSummary(scan.summary)
        return CompatibilityFolderSyncResult(
            discoveredCount = scan.summary.discoveredJsonCount,
            changedCount = scan.summary.changedJsonCount,
            importedCount = batch?.importedCount ?: 0,
            duplicateCount = batch?.duplicateCount ?: 0,
            rejectedCount = batch?.rejectedCount ?: 0,
            readErrorCount = scan.summary.readErrorCount,
        )
    }

    override fun close() {
        database.close()
    }
}

object GoogleDriveSyncVerificationReport {
    fun maskAccount(email: String?): String = email
        ?.takeIf(String::isNotBlank)
        ?.let { value ->
            val at = value.indexOf('@')
            if (at <= 0 || at == value.lastIndex) {
                "[MASKED_ACCOUNT]"
            } else {
                "${value.first()}***@${value.substring(at + 1)}"
            }
        }
        ?: "未登録"

    fun build(
        snapshot: GoogleDriveSyncVerificationSnapshot,
        history: List<GoogleDriveSyncVerificationRecord>,
        applicationId: String,
        versionName: String,
        generatedAt: Long,
    ): String = buildString {
        appendLine("つぐレジ＋ Google Drive売上同期診断")
        appendLine("generatedAt=${formatVerificationTime(generatedAt)}")
        appendLine("applicationId=$applicationId")
        appendLine("versionName=$versionName")
        appendLine("account=${maskAccount(snapshot.accountEmail)}")
        appendLine("selectedMode=${snapshot.selectedMode.name}")
        appendLine("resolvedMode=${snapshot.resolvedMode.name}")
        appendLine("folderStatus=${snapshot.folderStatus.name}")
        appendLine("direct.running=${snapshot.directStatus.running}")
        appendLine("direct.auto=${snapshot.directStatus.autoSyncOnLaunch}")
        appendLine("direct.lastStartedAt=${snapshot.directStatus.lastStartedAt ?: 0L}")
        appendLine("direct.lastCompletedAt=${snapshot.directStatus.lastCompletedAt ?: 0L}")
        appendLine("direct.listed=${snapshot.directStatus.listedCount}")
        appendLine("direct.downloaded=${snapshot.directStatus.downloadedCount}")
        appendLine("direct.unchanged=${snapshot.directStatus.unchangedCount}")
        appendLine("direct.imported=${snapshot.directStatus.importedCount}")
        appendLine("direct.duplicates=${snapshot.directStatus.duplicateCount}")
        appendLine("direct.rejected=${snapshot.directStatus.rejectedCount}")
        appendLine("direct.errors=${snapshot.directStatus.errorCount}")
        appendLine("direct.failureCategory=${snapshot.directStatus.lastFailureCategory?.name ?: "NONE"}")
        appendLine("folder.lastScannedAt=${snapshot.folderSummary?.scannedAt ?: 0L}")
        appendLine("folder.discovered=${snapshot.folderSummary?.discoveredJsonCount ?: 0}")
        appendLine("folder.changed=${snapshot.folderSummary?.changedJsonCount ?: 0}")
        appendLine("folder.unchanged=${snapshot.folderSummary?.unchangedJsonCount ?: 0}")
        appendLine("folder.readErrors=${snapshot.folderSummary?.readErrorCount ?: 0}")
        appendLine("database.totalImported=${snapshot.dashboard.totalImported}")
        appendLine("database.totalRejected=${snapshot.dashboard.totalRejected}")
        appendLine("database.recentRejections=${snapshot.recentRejectionCount}")
        appendLine("history.count=${history.size}")
        history.take(10).forEachIndexed { index, item ->
            appendLine(
                "history[$index]=${item.recordedAt},${item.mode.name},${item.success}," +
                    "${item.listedCount},${item.importedCount},${item.duplicateCount}," +
                    "${item.rejectedCount},${item.errorCount},${item.message.take(120)}",
            )
        }
        appendLine("redaction=OAuth credentials, authorization headers, content URIs, tree URIs, and JSON bodies are not included")
    }
}

data class GoogleDriveSyncVerificationUiState(
    val loading: Boolean = true,
    val running: Boolean = false,
    val snapshot: GoogleDriveSyncVerificationSnapshot? = null,
    val history: List<GoogleDriveSyncVerificationRecord> = emptyList(),
    val message: String = "売上同期状態を確認しています",
)

class GoogleDriveSyncVerificationActivity : ComponentActivity() {
    private val uiState = mutableStateOf(GoogleDriveSyncVerificationUiState())
    private val inspector by lazy { DriveConnectionInspector(this, contentResolver) }
    private val historyStore by lazy { GoogleDriveSyncVerificationHistoryStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GoogleDriveSyncVerificationScreen(
                        state = uiState.value,
                        onRun = ::runCurrentMode,
                        onRepair = ::repairAutomaticSettings,
                        onRefresh = { refresh() },
                        onShare = ::shareReport,
                        onClearHistory = ::clearHistory,
                        onOpenSetup = {
                            startActivity(Intent(this, GoogleDriveRecoveryActivity::class.java))
                        },
                        onClose = ::finish,
                    )
                }
            }
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh(message: String? = null) {
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) { buildSnapshot() }
            uiState.value = uiState.value.copy(
                loading = false,
                running = false,
                snapshot = snapshot,
                history = historyStore.load(),
                message = message ?: GoogleDriveSyncVerificationPolicy.nextAction(
                    snapshot,
                    System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun buildSnapshot(): GoogleDriveSyncVerificationSnapshot {
        val account = GoogleDriveAccountStore(applicationContext).load()
        val test = GoogleDriveConnectionTestStore(applicationContext).load()
        val registration = ImportFolderPreferences(applicationContext).registration()
        val folderConnection = inspector.inspect(registration)
        val directStatus = GoogleDriveDirectSyncStatusStore(applicationContext).load()
        val folderPreferences = ImportFolderPreferences(applicationContext)
        val selectedMode = GoogleDriveOperatingModeStore(applicationContext).load()
        val operations = GoogleDriveOperationsSnapshot(
            accountConnected = account.email != null,
            connectionTestStatus = test.status,
            folderStatus = folderConnection.status,
            selectedMode = selectedMode,
            directAutoSyncEnabled = directStatus.autoSyncOnLaunch,
            folderAutoImportEnabled = DriveSyncPreferences(applicationContext).autoImportOnLaunch(),
        )
        val database = ManagementDatabase(applicationContext)
        return try {
            val repository = SalesJournalImportRepository(database)
            GoogleDriveSyncVerificationSnapshot(
                accountEmail = account.email,
                selectedMode = selectedMode,
                resolvedMode = GoogleDriveOperationsPolicy.resolve(operations),
                folderStatus = folderConnection.status,
                directStatus = directStatus,
                folderSummary = folderPreferences.lastSummary(),
                dashboard = repository.dashboard(),
                recentRejectionCount = repository.recentRejections().size,
            )
        } finally {
            database.close()
        }
    }

    private fun runCurrentMode() {
        val snapshot = uiState.value.snapshot ?: return
        if (uiState.value.running || snapshot.directStatus.running) return
        if (snapshot.resolvedMode == GoogleDriveResolvedMode.UNDECIDED) {
            uiState.value = uiState.value.copy(
                message = "運用方式が未確定です。先に運用セットアップを完了してください",
            )
            return
        }
        uiState.value = uiState.value.copy(
            running = true,
            message = "${verificationResolvedModeLabel(snapshot.resolvedMode)}で差分同期を実行しています",
        )
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when (snapshot.resolvedMode) {
                        GoogleDriveResolvedMode.DRIVE_API -> runDriveApiSync()
                        GoogleDriveResolvedMode.COMPATIBILITY_FOLDER -> runCompatibilityFolderSync()
                        GoogleDriveResolvedMode.UNDECIDED -> error("運用方式が未確定です")
                    }
                }
            }
            val record = result.fold(
                onSuccess = { it },
                onFailure = { error ->
                    if (snapshot.resolvedMode == GoogleDriveResolvedMode.DRIVE_API) {
                        val category = GoogleDriveSyncErrorPolicy.classify(error)
                        GoogleDriveDirectSyncStatusStore(applicationContext).failed(
                            category,
                            "${GoogleDriveSyncErrorPolicy.message(category)}：${error.message ?: error.javaClass.simpleName}",
                        )
                    }
                    GoogleDriveSyncVerificationRecord(
                        recordedAt = System.currentTimeMillis(),
                        mode = snapshot.resolvedMode,
                        success = false,
                        listedCount = 0,
                        importedCount = 0,
                        duplicateCount = 0,
                        rejectedCount = 0,
                        errorCount = 1,
                        message = error.message ?: error.javaClass.simpleName,
                    )
                },
            )
            historyStore.append(record)
            refresh(
                if (record.success) {
                    "同期完了：確認${record.listedCount}件／新規${record.importedCount}件／重複${record.duplicateCount}件／隔離${record.rejectedCount}件"
                } else {
                    "同期失敗：${record.message}"
                },
            )
        }
    }

    private fun runDriveApiSync(): GoogleDriveSyncVerificationRecord {
        val token = GoogleDriveSyncAccessTokenProvider.acquire(applicationContext)
        val result = GoogleDriveDirectSyncRepository(applicationContext).use { repository ->
            repository.synchronize(token, forceReimport = false)
        }
        return GoogleDriveSyncVerificationRecord(
            recordedAt = System.currentTimeMillis(),
            mode = GoogleDriveResolvedMode.DRIVE_API,
            success = result.errorCount == 0 && result.rejectedCount == 0,
            listedCount = result.listedCount,
            importedCount = result.importedCount,
            duplicateCount = result.duplicateCount,
            rejectedCount = result.rejectedCount,
            errorCount = result.errorCount,
            message = "Drive API差分同期",
        )
    }

    private fun runCompatibilityFolderSync(): GoogleDriveSyncVerificationRecord {
        val result = CompatibilityFolderSyncRunner(applicationContext).use { runner ->
            runner.synchronize(forceRescan = false)
        }
        return GoogleDriveSyncVerificationRecord(
            recordedAt = System.currentTimeMillis(),
            mode = GoogleDriveResolvedMode.COMPATIBILITY_FOLDER,
            success = result.readErrorCount == 0 && result.rejectedCount == 0,
            listedCount = result.discoveredCount,
            importedCount = result.importedCount,
            duplicateCount = result.duplicateCount,
            rejectedCount = result.rejectedCount,
            errorCount = result.readErrorCount,
            message = "互換フォルダ差分同期（変更${result.changedCount}件）",
        )
    }

    private fun repairAutomaticSettings() {
        val snapshot = uiState.value.snapshot ?: return
        val directStore = GoogleDriveDirectSyncStatusStore(applicationContext)
        if (
            GoogleDriveSyncVerificationPolicy.isStalled(
                snapshot.directStatus,
                System.currentTimeMillis(),
            )
        ) {
            directStore.recoverStaleRun("30分を超えた実行中状態を解除しました")
        }
        when (snapshot.resolvedMode) {
            GoogleDriveResolvedMode.DRIVE_API -> {
                DriveSyncPreferences(applicationContext).setAutoImportOnLaunch(false)
                directStore.setAutoSyncOnLaunch(true)
                GoogleDriveDirectSyncScheduler.setAutomaticSyncEnabled(applicationContext, enabled = true)
                refresh("Drive API方式の自動同期設定を修復しました")
            }
            GoogleDriveResolvedMode.COMPATIBILITY_FOLDER -> {
                DriveSyncPreferences(applicationContext).setAutoImportOnLaunch(true)
                directStore.setAutoSyncOnLaunch(false)
                GoogleDriveDirectSyncScheduler.setAutomaticSyncEnabled(applicationContext, enabled = false)
                refresh("互換フォルダ方式の自動取込設定を修復しました")
            }
            GoogleDriveResolvedMode.UNDECIDED -> {
                uiState.value = uiState.value.copy(
                    message = "運用方式が未確定のため設定は変更していません",
                )
            }
        }
    }

    private fun shareReport() {
        val snapshot = uiState.value.snapshot ?: return
        val report = GoogleDriveSyncVerificationReport.build(
            snapshot = snapshot,
            history = uiState.value.history,
            applicationId = BuildConfig.APPLICATION_ID,
            versionName = BuildConfig.VERSION_NAME,
            generatedAt = System.currentTimeMillis(),
        )
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "つぐレジ＋ Google Drive売上同期診断")
                    putExtra(Intent.EXTRA_TEXT, report)
                },
                "診断レポートを共有",
            ),
        )
    }

    private fun clearHistory() {
        uiState.value = uiState.value.copy(
            history = historyStore.clear(),
            message = "同期検証履歴を消去しました。売上データと同期指紋は削除していません",
        )
    }
}

@Composable
private fun GoogleDriveSyncVerificationScreen(
    state: GoogleDriveSyncVerificationUiState,
    onRun: () -> Unit,
    onRepair: () -> Unit,
    onRefresh: () -> Unit,
    onShare: () -> Unit,
    onClearHistory: () -> Unit,
    onOpenSetup: () -> Unit,
    onClose: () -> Unit,
) {
    val snapshot = state.snapshot
    val health = snapshot?.let {
        GoogleDriveSyncVerificationPolicy.health(it, System.currentTimeMillis())
    } ?: GoogleDriveSyncHealth.SETUP_REQUIRED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Google Drive売上同期検証・復旧",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text("実同期、停止検出、設定修復、履歴、診断共有をまとめて確認します")
            }
            OutlinedButton(onClick = onClose) { Text("戻る") }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("状態：${verificationHealthLabel(health)}", fontWeight = FontWeight.Bold)
                Text("方式：${verificationResolvedModeLabel(snapshot?.resolvedMode ?: GoogleDriveResolvedMode.UNDECIDED)}")
                Text(state.message)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("同期・取込状況", fontWeight = FontWeight.Bold)
                Text("Googleアカウント：${GoogleDriveSyncVerificationReport.maskAccount(snapshot?.accountEmail)}")
                Text("Drive API：${snapshot?.directStatus?.lastMessage ?: "未確認"}")
                Text("互換フォルダ最終確認：${formatVerificationTime(snapshot?.folderSummary?.scannedAt)}")
                Text("取込済み：${snapshot?.dashboard?.totalImported ?: 0}件")
                Text("隔離累計：${snapshot?.dashboard?.totalRejected ?: 0}件")
                Text("直近隔離表示：${snapshot?.recentRejectionCount ?: 0}件")
            }
        }

        Button(
            onClick = onRun,
            modifier = Modifier.fillMaxWidth(),
            enabled = snapshot != null &&
                snapshot.resolvedMode != GoogleDriveResolvedMode.UNDECIDED &&
                !state.running &&
                snapshot.directStatus.running.not(),
        ) { Text(if (state.running) "同期実行中" else "現在の方式で差分同期を実行") }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onRepair,
                modifier = Modifier.weight(1f),
                enabled = snapshot != null && !state.running,
            ) { Text("自動設定を修復") }
            OutlinedButton(
                onClick = onRefresh,
                modifier = Modifier.weight(1f),
                enabled = !state.running,
            ) { Text("状態を再読込") }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onShare,
                modifier = Modifier.weight(1f),
                enabled = snapshot != null,
            ) { Text("診断レポート共有") }
            OutlinedButton(
                onClick = onOpenSetup,
                modifier = Modifier.weight(1f),
            ) { Text("運用セットアップ") }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("直近の同期検証履歴", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = onClearHistory, enabled = state.history.isNotEmpty()) {
                        Text("履歴消去")
                    }
                }
                if (state.history.isEmpty()) {
                    Text("履歴はありません")
                } else {
                    state.history.take(8).forEach { record ->
                        Text(
                            "${formatVerificationTime(record.recordedAt)} " +
                                "${verificationResolvedModeLabel(record.mode)} " +
                                "${if (record.success) "成功" else "要確認"}／" +
                                "新規${record.importedCount} 重複${record.duplicateCount} " +
                                "隔離${record.rejectedCount} エラー${record.errorCount}",
                        )
                    }
                }
            }
        }

        Text(
            "履歴消去や設定修復では、取込済み売上、SQLite、Drive上のJSON、同期指紋を削除しません。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun verificationHealthLabel(value: GoogleDriveSyncHealth): String = when (value) {
    GoogleDriveSyncHealth.SETUP_REQUIRED -> "設定が必要"
    GoogleDriveSyncHealth.NEVER_RUN -> "未実行"
    GoogleDriveSyncHealth.RUNNING -> "実行中"
    GoogleDriveSyncHealth.STALLED -> "停止疑い"
    GoogleDriveSyncHealth.HEALTHY -> "正常"
    GoogleDriveSyncHealth.WARNING -> "注意"
    GoogleDriveSyncHealth.ERROR -> "エラー"
}

private fun verificationResolvedModeLabel(value: GoogleDriveResolvedMode): String = when (value) {
    GoogleDriveResolvedMode.DRIVE_API -> "Drive API"
    GoogleDriveResolvedMode.COMPATIBILITY_FOLDER -> "互換フォルダ"
    GoogleDriveResolvedMode.UNDECIDED -> "未確定"
}

private fun formatVerificationTime(value: Long?): String = value
    ?.takeIf { it > 0L }
    ?.let { SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date(it)) }
    ?: "未実行"
