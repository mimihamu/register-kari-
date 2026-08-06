package jp.co.tenposinfo.register.plus

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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

enum class GoogleDriveRecoveryRecommendation {
    RUN_CONNECTION_TEST,
    DIRECT_API_READY,
    USE_COMPATIBILITY_FOLDER,
    COMPATIBILITY_FOLDER_READY,
    CHECK_CONFIGURATION,
}

object GoogleDriveRecoveryPolicy {
    fun recommend(
        testStatus: GoogleDriveConnectionTestStatus,
        folderStatus: DriveConnectionStatus,
    ): GoogleDriveRecoveryRecommendation {
        if (
            folderStatus == DriveConnectionStatus.READY &&
            testStatus != GoogleDriveConnectionTestStatus.SUCCEEDED
        ) {
            return GoogleDriveRecoveryRecommendation.COMPATIBILITY_FOLDER_READY
        }
        return when (testStatus) {
            GoogleDriveConnectionTestStatus.SUCCEEDED ->
                GoogleDriveRecoveryRecommendation.DIRECT_API_READY
            GoogleDriveConnectionTestStatus.NOT_FOUND ->
                GoogleDriveRecoveryRecommendation.USE_COMPATIBILITY_FOLDER
            GoogleDriveConnectionTestStatus.FAILED ->
                GoogleDriveRecoveryRecommendation.CHECK_CONFIGURATION
            GoogleDriveConnectionTestStatus.NOT_RUN,
            GoogleDriveConnectionTestStatus.RUNNING,
            -> GoogleDriveRecoveryRecommendation.RUN_CONNECTION_TEST
        }
    }

    fun title(recommendation: GoogleDriveRecoveryRecommendation): String = when (recommendation) {
        GoogleDriveRecoveryRecommendation.RUN_CONNECTION_TEST -> "最初に接続テストを実行してください"
        GoogleDriveRecoveryRecommendation.DIRECT_API_READY -> "Drive API方式を利用できます"
        GoogleDriveRecoveryRecommendation.USE_COMPATIBILITY_FOLDER -> "互換フォルダ方式へ切り替えてください"
        GoogleDriveRecoveryRecommendation.COMPATIBILITY_FOLDER_READY -> "互換フォルダ方式を利用できます"
        GoogleDriveRecoveryRecommendation.CHECK_CONFIGURATION -> "設定確認後、必要なら互換方式へ切り替えてください"
    }

    fun detail(recommendation: GoogleDriveRecoveryRecommendation): String = when (recommendation) {
        GoogleDriveRecoveryRecommendation.RUN_CONNECTION_TEST ->
            "つぐレジで接続テストJSONを作成し、つぐレジ＋で検索してください。"
        GoogleDriveRecoveryRecommendation.DIRECT_API_READY ->
            "別アプリ間で接続テストJSONを取得できています。通常のDrive API差分同期を利用できます。"
        GoogleDriveRecoveryRecommendation.USE_COMPATIBILITY_FOLDER ->
            "drive.fileの別OAuthクライアント間可視性により見つからない場合があります。両アプリで同じDriveフォルダを選択してください。"
        GoogleDriveRecoveryRecommendation.COMPATIBILITY_FOLDER_READY ->
            "登録フォルダの永続読取権限を確認できました。フォルダ差分取込を利用できます。"
        GoogleDriveRecoveryRecommendation.CHECK_CONFIGURATION ->
            "Googleアカウント、Drive API、OAuth設定、通信状態を診断してください。復旧を急ぐ場合は互換フォルダ方式を利用できます。"
    }
}

data class GoogleDriveRecoveryUiState(
    val loading: Boolean = true,
    val registering: Boolean = false,
    val account: GoogleDriveAccountState = GoogleDriveAccountState(),
    val connectionTest: GoogleDriveConnectionTestState = GoogleDriveConnectionTestState(),
    val folderRegistration: ImportFolderRegistration? = null,
    val folderConnection: DriveConnectionUiState = DriveConnectionUiState(),
    val directAutoSyncEnabled: Boolean = true,
    val folderAutoImportEnabled: Boolean = true,
    val selectedMode: GoogleDriveOperatingMode = GoogleDriveOperatingMode.AUTOMATIC,
    val resolvedMode: GoogleDriveResolvedMode = GoogleDriveResolvedMode.UNDECIDED,
    val checklist: GoogleDriveValidationChecklist = GoogleDriveValidationChecklist(),
    val message: String = "接続方式を確認しています",
)

class GoogleDriveRecoveryActivity : ComponentActivity() {
    private val folderPreferences by lazy { ImportFolderPreferences(this) }
    private val documentSource by lazy { SalesJournalDocumentSource(contentResolver) }
    private val inspector by lazy { DriveConnectionInspector(this, contentResolver) }
    private val operatingModeStore by lazy { GoogleDriveOperatingModeStore(this) }
    private val checklistStore by lazy { GoogleDriveValidationChecklistStore(this) }
    private val uiState = mutableStateOf(GoogleDriveRecoveryUiState())

    private val folderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) registerCompatibilityFolder(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GoogleDriveRecoveryScreen(
                        state = uiState.value,
                        onChooseFolder = { folderLauncher.launch(null) },
                        onModeChanged = ::changeOperatingMode,
                        onApplyRecommended = ::applyRecommendedMode,
                        onChecklistChanged = ::updateChecklist,
                        onResetChecklist = ::resetChecklist,
                        onOpenConnectionTest = {
                            startActivity(Intent(this, GoogleDriveAccountActivity::class.java))
                        },
                        onOpenMain = {
                            startActivity(
                                Intent(this, MainActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                },
                            )
                        },
                        onOpenSetupGuide = {
                            startActivity(Intent(this, GoogleDriveSetupGuideActivity::class.java))
                        },
                        onOpenDiagnostics = {
                            startActivity(Intent(this, GoogleDriveDiagnosticsActivity::class.java))
                        },
                        onOpenSyncVerification = {
                            startActivity(Intent(this, GoogleDriveSyncVerificationActivity::class.java))
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
        val registration = folderPreferences.registration()
        lifecycleScope.launch {
            val connection = withContext(Dispatchers.IO) {
                inspector.inspect(registration)
            }
            val account = GoogleDriveAccountStore(applicationContext).load()
            val test = GoogleDriveConnectionTestStore(applicationContext).load()
            val directEnabled = GoogleDriveDirectSyncStatusStore(applicationContext)
                .load().autoSyncOnLaunch
            val folderEnabled = DriveSyncPreferences(applicationContext).autoImportOnLaunch()
            val mode = operatingModeStore.load()
            val snapshot = GoogleDriveOperationsSnapshot(
                accountConnected = account.email != null,
                connectionTestStatus = test.status,
                folderStatus = connection.status,
                selectedMode = mode,
                directAutoSyncEnabled = directEnabled,
                folderAutoImportEnabled = folderEnabled,
            )
            uiState.value = GoogleDriveRecoveryUiState(
                loading = false,
                registering = false,
                account = account,
                connectionTest = test,
                folderRegistration = registration,
                folderConnection = connection,
                directAutoSyncEnabled = directEnabled,
                folderAutoImportEnabled = folderEnabled,
                selectedMode = mode,
                resolvedMode = GoogleDriveOperationsPolicy.resolve(snapshot),
                checklist = checklistStore.load(),
                message = message ?: "現在の接続方式を確認しました",
            )
        }
    }

    private fun registerCompatibilityFolder(uri: Uri) {
        if (uiState.value.registering) return
        uiState.value = uiState.value.copy(
            registering = true,
            message = "互換用フォルダを登録しています",
        )
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    documentSource.persistFolderPermission(uri)
                    val registration = ImportFolderRegistration(
                        treeUri = uri.toString(),
                        displayName = documentSource.folderDisplayName(uri),
                    )
                    folderPreferences.saveRegistration(registration)
                    val connection = inspector.inspect(registration)
                    val selectedMode = operatingModeStore.load()
                    if (
                        connection.status == DriveConnectionStatus.READY &&
                        selectedMode != GoogleDriveOperatingMode.DRIVE_API
                    ) {
                        DriveSyncPreferences(applicationContext).setAutoImportOnLaunch(true)
                        GoogleDriveDirectSyncStatusStore(applicationContext).setAutoSyncOnLaunch(false)
                        GoogleDriveDirectSyncScheduler.setAutomaticSyncEnabled(
                            applicationContext,
                            enabled = false,
                        )
                    }
                    registration to connection
                }
            }
            result.fold(
                onSuccess = { (_, connection) ->
                    refresh(
                        if (connection.status == DriveConnectionStatus.READY) {
                            if (operatingModeStore.load() == GoogleDriveOperatingMode.DRIVE_API) {
                                "フォルダを登録しました。Drive API固定のため自動切替はしていません"
                            } else {
                                "互換フォルダを登録し、重複同期を避ける設定へ切り替えました"
                            }
                        } else {
                            "フォルダを登録しましたが、接続診断が必要です"
                        },
                    )
                },
                onFailure = { error ->
                    uiState.value = uiState.value.copy(
                        loading = false,
                        registering = false,
                        message = "互換フォルダを登録できませんでした：${error.message ?: error.javaClass.simpleName}",
                    )
                },
            )
        }
    }

    private fun changeOperatingMode(mode: GoogleDriveOperatingMode) {
        operatingModeStore.save(mode)
        refresh("運用モードを「${operatingModeLabel(mode)}」に変更しました。設定を一括適用してください")
    }

    private fun applyRecommendedMode() {
        val current = uiState.value
        val snapshot = GoogleDriveOperationsSnapshot(
            accountConnected = current.account.email != null,
            connectionTestStatus = current.connectionTest.status,
            folderStatus = current.folderConnection.status,
            selectedMode = current.selectedMode,
            directAutoSyncEnabled = current.directAutoSyncEnabled,
            folderAutoImportEnabled = current.folderAutoImportEnabled,
        )
        when (GoogleDriveOperationsPolicy.resolve(snapshot)) {
            GoogleDriveResolvedMode.DRIVE_API -> {
                DriveSyncPreferences(applicationContext).setAutoImportOnLaunch(false)
                GoogleDriveDirectSyncStatusStore(applicationContext).setAutoSyncOnLaunch(true)
                GoogleDriveDirectSyncScheduler.setAutomaticSyncEnabled(applicationContext, enabled = true)
                refresh("Drive API方式を適用しました。互換フォルダの自動取込は停止しています")
            }

            GoogleDriveResolvedMode.COMPATIBILITY_FOLDER -> {
                DriveSyncPreferences(applicationContext).setAutoImportOnLaunch(true)
                GoogleDriveDirectSyncStatusStore(applicationContext).setAutoSyncOnLaunch(false)
                GoogleDriveDirectSyncScheduler.setAutomaticSyncEnabled(applicationContext, enabled = false)
                refresh("互換フォルダ方式を適用しました。Drive API自動同期は停止しています")
            }

            GoogleDriveResolvedMode.UNDECIDED -> {
                uiState.value = current.copy(
                    message = GoogleDriveOperationsPolicy.nextAction(snapshot),
                )
            }
        }
    }

    private fun updateChecklist(key: String, checked: Boolean) {
        uiState.value = uiState.value.copy(
            checklist = checklistStore.update(key, checked),
            message = "実機確認チェックを更新しました",
        )
    }

    private fun resetChecklist() {
        uiState.value = uiState.value.copy(
            checklist = checklistStore.reset(),
            message = "実機確認チェックをリセットしました",
        )
    }
}

@Composable
private fun GoogleDriveRecoveryScreen(
    state: GoogleDriveRecoveryUiState,
    onChooseFolder: () -> Unit,
    onModeChanged: (GoogleDriveOperatingMode) -> Unit,
    onApplyRecommended: () -> Unit,
    onChecklistChanged: (String, Boolean) -> Unit,
    onResetChecklist: () -> Unit,
    onOpenConnectionTest: () -> Unit,
    onOpenMain: () -> Unit,
    onOpenSetupGuide: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSyncVerification: () -> Unit,
    onClose: () -> Unit,
) {
    val snapshot = GoogleDriveOperationsSnapshot(
        accountConnected = state.account.email != null,
        connectionTestStatus = state.connectionTest.status,
        folderStatus = state.folderConnection.status,
        selectedMode = state.selectedMode,
        directAutoSyncEnabled = state.directAutoSyncEnabled,
        folderAutoImportEnabled = state.folderAutoImportEnabled,
    )
    val recommendation = GoogleDriveRecoveryPolicy.recommend(
        testStatus = state.connectionTest.status,
        folderStatus = state.folderConnection.status,
    )
    val healthy = GoogleDriveOperationsPolicy.currentConfigurationHealthy(snapshot)

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
                    "Google Drive運用セットアップ・復旧",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text("接続確認、方式選択、自動同期、実機確認を一画面で完了します")
            }
            OutlinedButton(onClick = onClose) { Text("戻る") }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (healthy) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
            ),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(if (healthy) "運用設定：正常" else "運用設定：確認が必要", fontWeight = FontWeight.Bold)
                Text("選択：${operatingModeLabel(state.selectedMode)}")
                Text("適用候補：${resolvedModeLabel(state.resolvedMode)}", fontWeight = FontWeight.SemiBold)
                Text(GoogleDriveOperationsPolicy.nextAction(snapshot))
                Text(state.message)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("初回セットアップ状況", fontWeight = FontWeight.Bold)
                SetupStatusRow("1. Googleアカウント", state.account.email != null, state.account.email ?: "未登録")
                SetupStatusRow(
                    "2. アプリ間接続テスト",
                    state.connectionTest.status == GoogleDriveConnectionTestStatus.SUCCEEDED,
                    connectionTestStatusLabel(state.connectionTest.status),
                )
                SetupStatusRow(
                    "3. 互換フォルダ",
                    state.folderConnection.status == DriveConnectionStatus.READY,
                    state.folderRegistration?.displayName ?: "未登録",
                )
                SetupStatusRow(
                    "4. 自動同期の競合防止",
                    healthy,
                    if (healthy) "同時実行なし" else "一括適用が必要",
                )
                Text(GoogleDriveRecoveryPolicy.title(recommendation))
                Text(GoogleDriveRecoveryPolicy.detail(recommendation))
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("運用モード", fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ModeButton(
                        label = "自動選択",
                        selected = state.selectedMode == GoogleDriveOperatingMode.AUTOMATIC,
                        enabled = !state.registering,
                        modifier = Modifier.weight(1f),
                    ) { onModeChanged(GoogleDriveOperatingMode.AUTOMATIC) }
                    ModeButton(
                        label = "Drive API",
                        selected = state.selectedMode == GoogleDriveOperatingMode.DRIVE_API,
                        enabled = !state.registering,
                        modifier = Modifier.weight(1f),
                    ) { onModeChanged(GoogleDriveOperatingMode.DRIVE_API) }
                    ModeButton(
                        label = "互換フォルダ",
                        selected = state.selectedMode == GoogleDriveOperatingMode.COMPATIBILITY_FOLDER,
                        enabled = !state.registering,
                        modifier = Modifier.weight(1f),
                    ) { onModeChanged(GoogleDriveOperatingMode.COMPATIBILITY_FOLDER) }
                }
                Text("自動選択は接続テスト成功時にDrive APIを優先し、見つからない場合は利用可能な互換フォルダへ切り替えます。")
                Button(
                    onClick = onApplyRecommended,
                    enabled = !state.loading && !state.registering &&
                        state.resolvedMode != GoogleDriveResolvedMode.UNDECIDED,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("推奨設定を一括適用") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Drive API方式", fontWeight = FontWeight.Bold)
                Text("接続テスト：${connectionTestStatusLabel(state.connectionTest.status)}")
                Text(state.connectionTest.message)
                Text("起動時・定期差分同期：${if (state.directAutoSyncEnabled) "有効" else "停止"}")
                state.connectionTest.testId?.let { Text("テストID：$it") }
                OutlinedButton(
                    onClick = onOpenConnectionTest,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.registering,
                ) { Text("アカウント・接続テスト画面を開く") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("互換フォルダ方式", fontWeight = FontWeight.Bold)
                Text("登録：${state.folderRegistration?.displayName ?: "未登録"}")
                Text("接続：${folderConnectionStatusLabel(state.folderConnection.status)}")
                Text(state.folderConnection.detail)
                state.folderConnection.providerName?.let { Text("提供元：$it") }
                Text("永続読取権限：${if (state.folderConnection.persistedReadPermission) "有効" else "未確認・無効"}")
                Text("起動時差分取込：${if (state.folderAutoImportEnabled) "有効" else "停止"}")
                Button(
                    onClick = onChooseFolder,
                    enabled = !state.loading && !state.registering,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(if (state.folderRegistration == null) "同じGoogle Driveフォルダを選択" else "互換フォルダを変更")
                }
                if (state.folderConnection.status == DriveConnectionStatus.READY) {
                    OutlinedButton(
                        onClick = onOpenMain,
                        enabled = !state.registering,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text("登録フォルダの差分取込画面を開く") }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    "実機確認チェック ${state.checklist.completedCount}/6",
                    fontWeight = FontWeight.Bold,
                )
                ChecklistRow(
                    "両アプリで同じGoogleアカウントを使用",
                    state.checklist.sameGoogleAccountConfirmed,
                ) { onChecklistChanged(GoogleDriveChecklistKey.SAME_ACCOUNT, it) }
                ChecklistRow(
                    "つぐレジから互換フォルダへ書込成功",
                    state.checklist.registerFolderWriteConfirmed,
                ) { onChecklistChanged(GoogleDriveChecklistKey.REGISTER_FOLDER_WRITE, it) }
                ChecklistRow(
                    "つぐレジ＋から同じフォルダを読取成功",
                    state.checklist.plusFolderReadConfirmed,
                ) { onChecklistChanged(GoogleDriveChecklistKey.PLUS_FOLDER_READ, it) }
                ChecklistRow(
                    "テスト売上JSONをDriveへ送信",
                    state.checklist.sampleSaleUploaded,
                ) { onChecklistChanged(GoogleDriveChecklistKey.SAMPLE_SALE_UPLOADED, it) }
                ChecklistRow(
                    "テスト売上をつぐレジ＋へ重複なしで取込",
                    state.checklist.sampleSaleImported,
                ) { onChecklistChanged(GoogleDriveChecklistKey.SAMPLE_SALE_IMPORTED, it) }
                ChecklistRow(
                    "端末再起動後も同期方式と権限を維持",
                    state.checklist.appRestartVerified,
                ) { onChecklistChanged(GoogleDriveChecklistKey.APP_RESTART_VERIFIED, it) }
                OutlinedButton(
                    onClick = onResetChecklist,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("確認チェックをリセット") }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onOpenSetupGuide,
                modifier = Modifier.weight(1f),
                enabled = !state.registering,
            ) { Text("初期設定ガイド") }
            OutlinedButton(
                onClick = onOpenDiagnostics,
                modifier = Modifier.weight(1f),
                enabled = !state.registering,
            ) { Text("診断・ログ") }
        }

        Button(
            onClick = onOpenSyncVerification,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.registering,
        ) { Text("売上同期検証・復旧") }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("一括適用時の安全動作", fontWeight = FontWeight.Bold)
                Text("Drive API方式では互換フォルダの起動時取込を停止します。")
                Text("互換フォルダ方式ではDrive APIの起動時・定期同期を停止します。")
                Text("Googleアカウント連携、取込済み売上、Drive上のファイル、SQLiteデータは削除しません。")
                Text("フォルダURI、アクセストークン、更新トークンは画面や診断メッセージへ表示しません。")
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SetupStatusRow(label: String, complete: Boolean, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (complete) "✓" else "・", fontWeight = FontWeight.Bold)
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ModeButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
    }
}

@Composable
private fun ChecklistRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, modifier = Modifier.weight(1f))
    }
}

private fun operatingModeLabel(mode: GoogleDriveOperatingMode): String = when (mode) {
    GoogleDriveOperatingMode.AUTOMATIC -> "自動選択"
    GoogleDriveOperatingMode.DRIVE_API -> "Drive API固定"
    GoogleDriveOperatingMode.COMPATIBILITY_FOLDER -> "互換フォルダ固定"
}

private fun resolvedModeLabel(mode: GoogleDriveResolvedMode): String = when (mode) {
    GoogleDriveResolvedMode.DRIVE_API -> "Drive API方式"
    GoogleDriveResolvedMode.COMPATIBILITY_FOLDER -> "互換フォルダ方式"
    GoogleDriveResolvedMode.UNDECIDED -> "未決定"
}

private fun connectionTestStatusLabel(status: GoogleDriveConnectionTestStatus): String = when (status) {
    GoogleDriveConnectionTestStatus.NOT_RUN -> "未実行"
    GoogleDriveConnectionTestStatus.RUNNING -> "確認中"
    GoogleDriveConnectionTestStatus.SUCCEEDED -> "成功"
    GoogleDriveConnectionTestStatus.NOT_FOUND -> "見つからない"
    GoogleDriveConnectionTestStatus.FAILED -> "失敗"
}

private fun folderConnectionStatusLabel(status: DriveConnectionStatus): String = when (status) {
    DriveConnectionStatus.NOT_REGISTERED -> "未登録"
    DriveConnectionStatus.CHECKING -> "確認中"
    DriveConnectionStatus.READY -> "利用可能"
    DriveConnectionStatus.PERMISSION_MISSING -> "権限失効"
    DriveConnectionStatus.PROVIDER_UNAVAILABLE -> "提供元を利用不可"
    DriveConnectionStatus.READ_FAILED -> "読取失敗"
}
