from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def path(relative: str) -> Path:
    return ROOT / relative


def read(relative: str) -> str:
    return path(relative).read_text(encoding="utf-8")


def write(relative: str, content: str) -> None:
    target = path(relative)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


def replace(relative: str, old: str, new: str, minimum: int = 1) -> None:
    content = read(relative)
    count = content.count(old)
    if count < minimum:
        raise RuntimeError(f"{relative}: replacement target not found: {old[:100]!r}")
    write(relative, content.replace(old, new))


def regex_replace(relative: str, pattern: str, replacement: str) -> None:
    content = read(relative)
    updated, count = re.subn(pattern, replacement, content)
    if count == 0:
        raise RuntimeError(f"{relative}: regex target not found: {pattern}")
    write(relative, updated)


RECOVERY_ACTIVITY = r'''package jp.co.tenposinfo.register.plus

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
'''

APP_TEST = r'''package jp.co.tenposinfo.register

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V051GoogleDriveRecoveryFallbackTest {
    @Test
    fun registerKeepsCompatibilityDeliveryForRecoveryMode() {
        val root = File("..")
        val build = File("build.gradle.kts").readText()
        val delivery = File("src/main/java/jp/co/tenposinfo/register/OutboxExternalDelivery.kt").readText()
        val syncSettings = File("src/main/java/jp/co/tenposinfo/register/SyncSettingsActivity.kt").readText()
        val plusRecovery = File(root, "management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveRecoveryActivity.kt").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        val docs = File(root, "docs/V0.51_GOOGLE_DRIVE_RECOVERY_FALLBACK.md").readText()

        assertTrue(build.contains("versionCode = 81"))
        assertTrue(build.contains("versionName = \"0.51.0-dev.1\""))
        assertTrue(delivery.contains("takePersistableUriPermission"))
        assertTrue(delivery.contains("drive-sync-staging"))
        assertTrue(syncSettings.contains("Google Drive・同期設定"))
        assertTrue(plusRecovery.contains("つぐレジ側でも互換用送信先として同じGoogle Driveフォルダを選択"))
        assertTrue(docs.contains("同じGoogle Driveフォルダ"))
        assertTrue(workflow.contains("TSUGUREGI_v0.51.0_dev1_drive_recovery_fallback_debug.apk"))
        assertTrue(workflow.contains("TSUGUREGI-v0.51.0-dev1-drive-recovery-fallback-apks"))
        assertFalse(File(root, "tools/v051_apply.py").exists())
        assertFalse(File(root, ".github/workflows/v051-apply-temp.yml").exists())
        assertFalse(File(root, "tools/build-apk-v051.generated.yml").exists())
    }
}
'''

PLUS_TEST = r'''package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V051GoogleDriveRecoveryFallbackTest {
    @Test
    fun recommendationUsesFolderWhenDirectTestCannotBeFound() {
        assertEquals(
            GoogleDriveRecoveryRecommendation.USE_COMPATIBILITY_FOLDER,
            GoogleDriveRecoveryPolicy.recommend(
                GoogleDriveConnectionTestStatus.NOT_FOUND,
                DriveConnectionStatus.NOT_REGISTERED,
            ),
        )
        assertEquals(
            GoogleDriveRecoveryRecommendation.COMPATIBILITY_FOLDER_READY,
            GoogleDriveRecoveryPolicy.recommend(
                GoogleDriveConnectionTestStatus.NOT_FOUND,
                DriveConnectionStatus.READY,
            ),
        )
        assertEquals(
            GoogleDriveRecoveryRecommendation.DIRECT_API_READY,
            GoogleDriveRecoveryPolicy.recommend(
                GoogleDriveConnectionTestStatus.SUCCEEDED,
                DriveConnectionStatus.NOT_REGISTERED,
            ),
        )
    }

    @Test
    fun recoveryFlowPersistsFolderAndStopsAutomaticDirectSync() {
        val root = File("..")
        val sourceRoot = File("src/main/java/jp/co/tenposinfo/register/plus")
        val recovery = File(sourceRoot, "GoogleDriveRecoveryActivity.kt").readText()
        val directSync = File(sourceRoot, "GoogleDriveDirectSync.kt").readText()
        val account = File(sourceRoot, "GoogleDriveAccountActivity.kt").readText()
        val folderScreen = File(sourceRoot, "ManagementFolderSyncScreen.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val plusBuild = File("build.gradle.kts").readText()
        val registerBuild = File(root, "app/build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        val docs = File(root, "docs/V0.51_GOOGLE_DRIVE_RECOVERY_FALLBACK.md").readText()

        for (token in listOf(
            "ActivityResultContracts.OpenDocumentTree",
            "persistFolderPermission",
            "ImportFolderPreferences",
            "DriveConnectionInspector",
            "DriveSyncPreferences(applicationContext).setAutoImportOnLaunch(true)",
            "GoogleDriveDirectSyncStatusStore(applicationContext).setAutoSyncOnLaunch(false)",
            "GoogleDriveDirectSyncScheduler.setAutomaticSyncEnabled",
            "同じGoogle Driveフォルダを選択",
            "Googleアカウント連携、取込済み売上",
        )) assertTrue(recovery.contains(token))

        assertTrue(directSync.contains("fun setAutomaticSyncEnabled"))
        assertTrue(directSync.contains("cancelUniqueWork(PERIODIC_NAME)"))
        assertTrue(directSync.contains("cancelUniqueWork(STARTUP_NAME)"))
        assertTrue(account.contains("GoogleDriveRecoveryActivity::class.java"))
        assertTrue(account.contains("互換フォルダ方式へ切替"))
        assertTrue(folderScreen.contains("Drive APIで取得できない場合の復旧経路"))
        assertTrue(manifest.contains("android:name=\".GoogleDriveRecoveryActivity\""))
        assertTrue(manifest.contains("android:screenOrientation=\"sensorPortrait\""))
        assertTrue(registerBuild.contains("versionCode = 81"))
        assertTrue(registerBuild.contains("versionName = \"0.51.0-dev.1\""))
        assertTrue(plusBuild.contains("versionCode = 10"))
        assertTrue(plusBuild.contains("versionName = \"0.10.0-dev.1\""))
        assertTrue(workflow.contains("V051GoogleDriveRecoveryFallbackTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_PLUS_v0.10.0_dev1_drive_recovery_fallback_debug.apk"))
        assertTrue(docs.contains("Drive API自動同期を停止"))
        assertFalse(recovery.contains("putString(\"access_token\""))
        assertFalse(recovery.contains("putString(\"refresh_token\""))
        assertFalse(recovery.contains("treeUri = Text"))
        assertFalse(File(root, "tools/v051_apply.py").exists())
        assertFalse(File(root, ".github/workflows/v051-apply-temp.yml").exists())
        assertFalse(File(root, "tools/build-apk-v051.generated.yml").exists())
    }
}
'''

DOCS = r'''# v0.51 Google Drive接続方式・復旧フォールバック

## 目的

`drive.file`スコープで、つぐレジとつぐレジ＋が別applicationId・別Android OAuthクライアントとして動作した場合、つぐレジ＋から接続テストJSONを検索できない可能性があります。

v0.51では、接続テストが見つからない場合でも売上管理を止めないため、Storage Access Frameworkを利用した互換フォルダ方式へ安全に切り替える復旧画面を追加します。

## 画面

つぐレジ＋のGoogle Driveアカウント画面から「接続方式・復旧設定」を開きます。

画面には次の状態を表示します。

- Drive API接続テスト結果
- Drive APIの起動時・定期差分同期が有効か
- 互換フォルダの登録名
- フォルダ提供元
- 永続読取権限
- 起動時フォルダ差分取込が有効か
- 現在の推奨方式

フォルダURIそのものは表示しません。

## 互換フォルダへの切替

1. つぐレジ側で、互換用送信先としてGoogle Drive内のフォルダを選択します。
2. つぐレジ＋の復旧画面で「同じGoogle Driveフォルダを選択」を押します。
3. Androidのフォルダ選択画面から、つぐレジ側と同じフォルダを選択します。
4. つぐレジ＋は永続読取権限を保存し、フォルダを読み取れるか診断します。
5. 登録成功時はDrive API自動同期を停止し、フォルダの起動時差分取込を有効化します。

切替後も手動のDrive API接続確認や診断は利用できます。

## Drive API方式への復帰

復旧画面の「Drive API自動同期を再有効化」で、起動時・定期差分同期を再開できます。

登録済み互換フォルダは削除しません。必要に応じて管理画面からフォルダ登録を解除します。

## 削除しないデータ

方式を切り替えても次のデータは削除しません。

- Googleアカウント連携情報
- 取込済み売上
- SQLiteデータ
- Drive上の売上JSON
- 互換フォルダのファイル
- 接続テストJSON

アクセストークンと更新トークンは従来どおり保存しません。

## 自動同期停止処理

互換方式への切替時は、つぐレジ＋の設定値だけでなくWorkManagerの次の処理も停止します。

- Drive API定期同期
- Drive API起動時同期

手動差分同期を再度実行することは可能です。

## 実機確認事項

- Google Driveアプリからフォルダを選択できること
- 永続読取権限が端末再起動後も維持されること
- つぐレジとつぐレジ＋で同じフォルダを選択できること
- Drive API自動同期が停止すること
- フォルダ起動時差分取込が動作すること
- Drive API方式を再有効化できること
- Workspace管理アカウントでの動作
- Google Driveアプリ未導入端末の表示
'''

RELEASE_NOTES = r'''# v0.51 リリースノート

## 追加

- つぐレジ＋に「Google Drive接続方式・復旧」画面を追加
- 接続テスト結果に応じた推奨方式表示
- Androidのフォルダ選択による互換フォルダ登録
- フォルダ提供元・永続読取権限・読取状態の診断
- 互換フォルダ方式への自動切替
- Drive API方式の再有効化

## 切替時の制御

互換フォルダ登録成功時に次を実行します。

- Drive API起動時・定期差分同期を停止
- WorkManagerの定期同期と起動時同期をキャンセル
- フォルダ起動時差分取込を有効化

Googleアカウント連携、取込済み売上、SQLite、Drive上のファイルは削除しません。

## バージョン

- つぐレジ: 0.51.0-dev.1 / versionCode 81
- つぐレジ＋: 0.10.0-dev.1 / versionCode 10
- つぐレジ CD: 0.14.0-dev.1 / versionCode 7

## 実機未確認

Google Driveフォルダ選択、永続権限、両アプリ間の同一フォルダ運用、自動同期切替は実機確認が必要です。
'''

# Add source, tests and documentation.
write(
    "management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveRecoveryActivity.kt",
    RECOVERY_ACTIVITY,
)
write(
    "app/src/test/java/jp/co/tenposinfo/register/V051GoogleDriveRecoveryFallbackTest.kt",
    APP_TEST,
)
write(
    "management-app/src/test/java/jp/co/tenposinfo/register/plus/V051GoogleDriveRecoveryFallbackTest.kt",
    PLUS_TEST,
)
write("docs/V0.51_GOOGLE_DRIVE_RECOVERY_FALLBACK.md", DOCS)
write("docs/V0.51_RELEASE_NOTES.md", RELEASE_NOTES)

# Version increments.
regex_replace("app/build.gradle.kts", r"versionCode = 80\b", "versionCode = 81")
replace("app/build.gradle.kts", 'versionName = "0.50.0-dev.1"', 'versionName = "0.51.0-dev.1"')
regex_replace("management-app/build.gradle.kts", r"versionCode = 9\b", "versionCode = 10")
replace(
    "management-app/build.gradle.kts",
    'versionName = "0.9.0-dev.1"',
    'versionName = "0.10.0-dev.1"',
)

# Existing cumulative tests follow the current build and artifact names.
for test_root in (
    path("app/src/test/java"),
    path("management-app/src/test/java"),
):
    for source in test_root.rglob("*.kt"):
        content = source.read_text(encoding="utf-8")
        content = re.sub(r"versionCode = 80\b", "versionCode = 81", content)
        content = content.replace('versionName = \\"0.50.0-dev.1\\"', 'versionName = \\"0.51.0-dev.1\\"')
        content = re.sub(r"versionCode = 9\b", "versionCode = 10", content)
        content = content.replace('versionName = \\"0.9.0-dev.1\\"', 'versionName = \\"0.10.0-dev.1\\"')
        content = content.replace(
            "TSUGUREGI_v0.50.0_dev1_drive_cross_app_connection_test_debug.apk",
            "TSUGUREGI_v0.51.0_dev1_drive_recovery_fallback_debug.apk",
        )
        content = content.replace(
            "TSUGUREGI_PLUS_v0.9.0_dev1_drive_cross_app_connection_test_debug.apk",
            "TSUGUREGI_PLUS_v0.10.0_dev1_drive_recovery_fallback_debug.apk",
        )
        content = content.replace(
            "TSUGUREGI-v0.50.0-dev1-drive-cross-app-connection-test-apks",
            "TSUGUREGI-v0.51.0-dev1-drive-recovery-fallback-apks",
        )
        source.write_text(content, encoding="utf-8")

# Add recovery navigation to the Plus account screen.
replace(
    "management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveAccountActivity.kt",
    '''                        onOpenDiagnostics = {
                            startActivity(Intent(this, GoogleDriveDiagnosticsActivity::class.java))
                        },
                        onClose = ::finish,''',
    '''                        onOpenDiagnostics = {
                            startActivity(Intent(this, GoogleDriveDiagnosticsActivity::class.java))
                        },
                        onOpenRecovery = {
                            startActivity(Intent(this, GoogleDriveRecoveryActivity::class.java))
                        },
                        onClose = ::finish,''',
)
replace(
    "management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveAccountActivity.kt",
    '''    onOpenSetupGuide: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onClose: () -> Unit,''',
    '''    onOpenSetupGuide: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenRecovery: () -> Unit,
    onClose: () -> Unit,''',
)
replace(
    "management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveAccountActivity.kt",
    '''        Button(
            onClick = onConnect,''',
    '''        OutlinedButton(
            onClick = onOpenRecovery,
            enabled = !syncStatus.running && connectionTest.status != GoogleDriveConnectionTestStatus.RUNNING,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(
                if (
                    connectionTest.status == GoogleDriveConnectionTestStatus.NOT_FOUND ||
                    connectionTest.status == GoogleDriveConnectionTestStatus.FAILED
                ) {
                    "互換フォルダ方式へ切替"
                } else {
                    "接続方式・復旧設定"
                },
            )
        }

        Button(
            onClick = onConnect,''',
)
replace(
    "management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveAccountActivity.kt",
    '''        if (enabled) {
            GoogleDriveDirectSyncScheduler.ensurePeriodic(applicationContext)
            GoogleDriveDirectSyncScheduler.enqueueStartup(applicationContext)
        }''',
    '''        GoogleDriveDirectSyncScheduler.setAutomaticSyncEnabled(applicationContext, enabled)''',
)
replace(
    "management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveAccountActivity.kt",
    'Text("v0.50 アプリ間接続テスト対応", fontWeight = FontWeight.Bold)',
    'Text("v0.51 接続方式・復旧対応", fontWeight = FontWeight.Bold)',
)

# Cancel automatic direct API jobs when the fallback is selected.
replace(
    "management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveDirectSync.kt",
    '''    fun ensurePeriodic(context: Context) {''',
    '''    fun setAutomaticSyncEnabled(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        if (enabled) {
            ensurePeriodic(appContext)
            enqueueStartup(appContext)
        } else {
            WorkManager.getInstance(appContext).cancelUniqueWork(PERIODIC_NAME)
            WorkManager.getInstance(appContext).cancelUniqueWork(STARTUP_NAME)
        }
    }

    fun ensurePeriodic(context: Context) {''',
)
replace(
    "management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveDirectSync.kt",
    '''        runCatching {
            GoogleDriveDirectSyncScheduler.ensurePeriodic(appContext)
            GoogleDriveDirectSyncScheduler.enqueueStartup(appContext)
        }''',
    '''        runCatching {
            val enabled = GoogleDriveDirectSyncStatusStore(appContext).load().autoSyncOnLaunch
            GoogleDriveDirectSyncScheduler.setAutomaticSyncEnabled(appContext, enabled)
        }''',
)

# Add the recovery activity to the manifest.
replace(
    "management-app/src/main/AndroidManifest.xml",
    '''        <activity
            android:name=".GoogleDriveAccountActivity"''',
    '''        <activity
            android:name=".GoogleDriveRecoveryActivity"
            android:exported="false"
            android:screenOrientation="sensorPortrait" />

        <activity
            android:name=".GoogleDriveAccountActivity"''',
)

replace(
    "management-app/src/main/java/jp/co/tenposinfo/register/plus/ManagementFolderSyncScreen.kt",
    'フォルダ方式は互換用です。本格連携は上の『Googleアカウント連携』から登録します。',
    'フォルダ方式は互換用です。Drive APIで取得できない場合の復旧経路として利用できます。',
)

# Generate the next official CI workflow without modifying the workflow from Actions.
workflow = read(".github/workflows/build-apk.yml")
workflow = workflow.replace("Verify cumulative v0.14-v0.50 sources", "Verify cumulative v0.14-v0.51 sources")
workflow = re.sub(r"versionCode = 80\b", "versionCode = 81", workflow)
workflow = workflow.replace('versionName = "0.50.0-dev.1"', 'versionName = "0.51.0-dev.1"')
workflow = re.sub(r"versionCode = 9\b", "versionCode = 10", workflow)
workflow = workflow.replace('versionName = "0.9.0-dev.1"', 'versionName = "0.10.0-dev.1"')
workflow = workflow.replace(
    "TSUGUREGI_v0.50.0_dev1_drive_cross_app_connection_test_debug.apk",
    "TSUGUREGI_v0.51.0_dev1_drive_recovery_fallback_debug.apk",
)
workflow = workflow.replace(
    "TSUGUREGI_PLUS_v0.9.0_dev1_drive_cross_app_connection_test_debug.apk",
    "TSUGUREGI_PLUS_v0.10.0_dev1_drive_recovery_fallback_debug.apk",
)
workflow = workflow.replace(
    "TSUGUREGI-v0.50.0-dev1-drive-cross-app-connection-test-apks",
    "TSUGUREGI-v0.51.0-dev1-drive-recovery-fallback-apks",
)
workflow = workflow.replace(
    "            app/src/test/java/jp/co/tenposinfo/register/V050GoogleDriveCrossAppConnectionTest.kt \\\n",
    "            app/src/test/java/jp/co/tenposinfo/register/V050GoogleDriveCrossAppConnectionTest.kt \\\n            app/src/test/java/jp/co/tenposinfo/register/V051GoogleDriveRecoveryFallbackTest.kt \\\n",
)
workflow = workflow.replace(
    "            management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveConnectionTest.kt \\\n",
    "            management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveConnectionTest.kt \\\n            management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveRecoveryActivity.kt \\\n",
)
workflow = workflow.replace(
    "            management-app/src/test/java/jp/co/tenposinfo/register/plus/V050GoogleDriveCrossAppConnectionTest.kt \\\n",
    "            management-app/src/test/java/jp/co/tenposinfo/register/plus/V050GoogleDriveCrossAppConnectionTest.kt \\\n            management-app/src/test/java/jp/co/tenposinfo/register/plus/V051GoogleDriveRecoveryFallbackTest.kt \\\n",
)
workflow = workflow.replace(
    "            docs/V0.50_GOOGLE_DRIVE_CROSS_APP_CONNECTION_TEST.md \\\n",
    "            docs/V0.51_GOOGLE_DRIVE_RECOVERY_FALLBACK.md \\\n            docs/V0.51_RELEASE_NOTES.md \\\n            docs/V0.50_GOOGLE_DRIVE_CROSS_APP_CONNECTION_TEST.md \\\n",
)
workflow = workflow.replace(
    "          plus_connection_test = (plus / 'GoogleDriveConnectionTest.kt').read_text()\n",
    "          plus_connection_test = (plus / 'GoogleDriveConnectionTest.kt').read_text()\n          plus_recovery = (plus / 'GoogleDriveRecoveryActivity.kt').read_text()\n",
)
workflow = workflow.replace(
    "          assert 'つぐレジ接続テストを検索' in plus_account\n",
    "          assert 'つぐレジ接続テストを検索' in plus_account\n          assert 'GoogleDriveRecoveryActivity::class.java' in plus_account\n          assert '互換フォルダ方式へ切替' in plus_account\n          for token in ('GoogleDriveRecoveryPolicy', 'ActivityResultContracts.OpenDocumentTree', 'persistFolderPermission', 'ImportFolderPreferences', 'DriveConnectionInspector', 'setAutomaticSyncEnabled'):\n              assert token in plus_recovery, token\n          assert 'putString(\"access_token\"' not in plus_recovery\n          assert 'putString(\"refresh_token\"' not in plus_recovery\n          assert 'cancelUniqueWork(PERIODIC_NAME)' in plus_drive\n          assert 'cancelUniqueWork(STARTUP_NAME)' in plus_drive\n",
)
workflow = workflow.replace(
    "          assert 'GoogleDriveDirectSyncBootstrapProvider' in plus_manifest\n",
    "          assert 'GoogleDriveDirectSyncBootstrapProvider' in plus_manifest\n          assert 'GoogleDriveRecoveryActivity' in plus_manifest\n",
)
workflow = workflow.replace(
    "          for version in ('v050',",
    "          for version in ('v051', 'v050',",
)
workflow = workflow.replace(
    "          assert not Path('tools/build-apk-v050.generated.yml').exists()\n",
    "          assert not Path('tools/build-apk-v050.generated.yml').exists()\n          assert not Path('.github/workflows/v051-apply-temp.yml').exists()\n          assert not Path('tools/v051_apply.py').exists()\n          assert not Path('tools/build-apk-v051.generated.yml').exists()\n",
)
workflow = workflow.replace(
    "          GOOGLE_DRIVE_CROSS_APP_CONNECTION_TEST=true\n",
    "          GOOGLE_DRIVE_CROSS_APP_CONNECTION_TEST=true\n          GOOGLE_DRIVE_RECOVERY_FALLBACK=true\n          DIRECT_AUTOMATIC_SYNC_CANCELLED_ON_FALLBACK=true\n          FOLDER_AUTO_IMPORT_ENABLED_ON_FALLBACK=true\n          RECOVERY_SCREEN_DISPLAYS_FOLDER_URI=false\n",
)
write("tools/build-apk-v051.generated.yml", workflow)

print("v0.51 patch applied")
