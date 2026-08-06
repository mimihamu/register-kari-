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
            "別アプリ間で接続テストJSONを取得できています。通常のDrive API差分同期を継続できます。"
        GoogleDriveRecoveryRecommendation.USE_COMPATIBILITY_FOLDER ->
            "同じGoogleアカウントでもdrive.fileの別OAuthクライアント間可視性により見つからない場合があります。両アプリで同じDriveフォルダを選択してください。"
        GoogleDriveRecoveryRecommendation.COMPATIBILITY_FOLDER_READY ->
            "登録フォルダの永続読取権限を確認できました。起動時のフォルダ差分取込を利用できます。"
        GoogleDriveRecoveryRecommendation.CHECK_CONFIGURATION ->
            "Googleアカウント、Drive API、OAuth設定、通信状態を診断してください。復旧を急ぐ場合は互換フォルダ方式を利用できます。"
    }
}

data class GoogleDriveRecoveryUiState(
    val loading: Boolean = true,
    val registering: Boolean = false,
    val connectionTest: GoogleDriveConnectionTestState = GoogleDriveConnectionTestState(),
    val folderRegistration: ImportFolderRegistration? = null,
    val folderConnection: DriveConnectionUiState = DriveConnectionUiState(),
    val directAutoSyncEnabled: Boolean = true,
    val folderAutoImportEnabled: Boolean = true,
    val message: String = "接続方式を確認しています",
)

class GoogleDriveRecoveryActivity : ComponentActivity() {
    private val folderPreferences by lazy { ImportFolderPreferences(this) }
    private val documentSource by lazy { SalesJournalDocumentSource(contentResolver) }
    private val inspector by lazy { DriveConnectionInspector(this, contentResolver) }
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
                        onEnableDirectApi = ::enableDirectApiAutomaticSync,
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
            uiState.value = GoogleDriveRecoveryUiState(
                loading = false,
                registering = false,
                connectionTest = GoogleDriveConnectionTestStore(applicationContext).load(),
                folderRegistration = registration,
                folderConnection = connection,
                directAutoSyncEnabled = GoogleDriveDirectSyncStatusStore(applicationContext)
                    .load().autoSyncOnLaunch,
                folderAutoImportEnabled = DriveSyncPreferences(applicationContext)
                    .autoImportOnLaunch(),
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
                    DriveSyncPreferences(applicationContext).setAutoImportOnLaunch(true)
                    GoogleDriveDirectSyncStatusStore(applicationContext).setAutoSyncOnLaunch(false)
                    GoogleDriveDirectSyncScheduler.setAutomaticSyncEnabled(
                        applicationContext,
                        enabled = false,
                    )
                    registration to inspector.inspect(registration)
                }
            }
            result.fold(
                onSuccess = { (registration, connection) ->
                    uiState.value = uiState.value.copy(
                        loading = false,
                        registering = false,
                        folderRegistration = registration,
                        folderConnection = connection,
                        directAutoSyncEnabled = false,
                        folderAutoImportEnabled = true,
                        message = if (connection.status == DriveConnectionStatus.READY) {
                            "互換フォルダ方式へ切り替えました。Drive API自動同期は停止しています"
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

    private fun enableDirectApiAutomaticSync() {
        GoogleDriveDirectSyncStatusStore(applicationContext).setAutoSyncOnLaunch(true)
        GoogleDriveDirectSyncScheduler.setAutomaticSyncEnabled(applicationContext, enabled = true)
        refresh("Drive APIの起動時・定期差分同期を再有効化しました。登録済み互換フォルダは削除していません")
    }
}

@Composable
private fun GoogleDriveRecoveryScreen(
    state: GoogleDriveRecoveryUiState,
    onChooseFolder: () -> Unit,
    onEnableDirectApi: () -> Unit,
    onOpenConnectionTest: () -> Unit,
    onOpenMain: () -> Unit,
    onOpenSetupGuide: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onClose: () -> Unit,
) {
    val recommendation = GoogleDriveRecoveryPolicy.recommend(
        testStatus = state.connectionTest.status,
        folderStatus = state.folderConnection.status,
    )
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
                    "Google Drive接続方式・復旧",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text("Drive APIで取得できない場合も、同じDriveフォルダを介して運用を継続できます")
            }
            OutlinedButton(onClick = onClose) { Text("戻る") }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (
                    recommendation == GoogleDriveRecoveryRecommendation.DIRECT_API_READY ||
                    recommendation == GoogleDriveRecoveryRecommendation.COMPATIBILITY_FOLDER_READY
                ) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
            ),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("推奨", fontWeight = FontWeight.Bold)
                Text(GoogleDriveRecoveryPolicy.title(recommendation), fontWeight = FontWeight.SemiBold)
                Text(GoogleDriveRecoveryPolicy.detail(recommendation))
                Text(state.message)
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
                ) { Text("接続テスト画面を開く") }
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
            }
        }

        Button(
            onClick = onChooseFolder,
            enabled = !state.loading && !state.registering,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(if (state.folderRegistration == null) "同じGoogle Driveフォルダを選択" else "互換フォルダを変更")
        }

        if (state.folderConnection.status == DriveConnectionStatus.READY) {
            Button(
                onClick = onOpenMain,
                enabled = !state.registering,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("登録フォルダの差分取込画面を開く") }
        }

        OutlinedButton(
            onClick = onEnableDirectApi,
            enabled = !state.registering && !state.directAutoSyncEnabled,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text("Drive API自動同期を再有効化") }

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

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("切替時の動作", fontWeight = FontWeight.Bold)
                Text("互換フォルダ登録時はDrive APIの起動時・定期同期を停止し、フォルダの起動時差分取込を有効化します。")
                Text("Googleアカウント連携、取込済み売上、Drive上のファイル、SQLiteデータは削除しません。")
                Text("つぐレジ側でも互換用送信先として同じGoogle Driveフォルダを選択してください。")
                Text("フォルダURI、アクセストークン、更新トークンは画面や診断メッセージへ表示しません。")
            }
        }
        Spacer(Modifier.height(12.dp))
    }
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
