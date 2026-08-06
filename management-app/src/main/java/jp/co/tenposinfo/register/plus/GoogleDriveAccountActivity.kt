package jp.co.tenposinfo.register.plus

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.Scopes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

enum class GoogleDriveAccountStatus {
    NOT_CONNECTED,
    CONNECTING,
    CONNECTED,
    CLOUD_CONFIGURATION_REQUIRED,
    DRIVE_API_DISABLED,
    AUTHORIZATION_FAILED,
    VERIFICATION_FAILED,
}

data class GoogleDriveAccountState(
    val status: GoogleDriveAccountStatus = GoogleDriveAccountStatus.NOT_CONNECTED,
    val email: String? = null,
    val displayName: String? = null,
    val permissionId: String? = null,
    val lastVerifiedAt: Long? = null,
    val message: String = "Googleアカウントを接続してください",
)

object GoogleDriveAccountPolicy {
    const val DRIVE_FILE_SCOPE = Scopes.DRIVE_FILE
    val requestedScopes: List<Scope> = listOf(Scope(DRIVE_FILE_SCOPE))

    fun statusForAuthorizationError(error: Throwable): GoogleDriveAccountStatus =
        if (error is ApiException && error.statusCode == CommonStatusCodes.DEVELOPER_ERROR) {
            GoogleDriveAccountStatus.CLOUD_CONFIGURATION_REQUIRED
        } else {
            GoogleDriveAccountStatus.AUTHORIZATION_FAILED
        }
}

class GoogleDriveAccountStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "tsuguregi_plus_google_drive_account",
        Context.MODE_PRIVATE,
    )

    fun load(): GoogleDriveAccountState {
        val email = preferences.getString("email", null)?.takeIf(String::isNotBlank)
            ?: return GoogleDriveAccountState()
        return GoogleDriveAccountState(
            status = GoogleDriveAccountStatus.CONNECTED,
            email = email,
            displayName = preferences.getString("display_name", null),
            permissionId = preferences.getString("permission_id", null),
            lastVerifiedAt = preferences.getLong("verified_at", 0L).takeIf { it > 0L },
            message = "前回接続したGoogleアカウントです。必要に応じて接続確認を実行してください",
        )
    }

    fun save(state: GoogleDriveAccountState) {
        require(!state.email.isNullOrBlank())
        preferences.edit()
            .putString("email", state.email)
            .putString("display_name", state.displayName)
            .putString("permission_id", state.permissionId)
            .putLong("verified_at", state.lastVerifiedAt ?: 0L)
            .apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }
}

data class GoogleDriveProfile(
    val email: String,
    val displayName: String?,
    val permissionId: String?,
)

class GoogleDriveProbeException(
    val responseCode: Int,
    val responseBody: String,
) : IllegalStateException("Google Drive API HTTP $responseCode")

object GoogleDriveAboutApi {
    private const val ABOUT_URL =
        "https://www.googleapis.com/drive/v3/about?fields=user(displayName,emailAddress,permissionId)"

    fun verify(accessToken: String): GoogleDriveProfile {
        val connection = URL(ABOUT_URL).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) throw GoogleDriveProbeException(code, body)
            val user = JSONObject(body).getJSONObject("user")
            GoogleDriveProfile(
                email = user.getString("emailAddress"),
                displayName = user.optString("displayName").takeIf(String::isNotBlank),
                permissionId = user.optString("permissionId").takeIf(String::isNotBlank),
            )
        } finally {
            connection.disconnect()
        }
    }
}

class GoogleDriveAccountActivity : ComponentActivity() {
    private val authorizationClient by lazy { Identity.getAuthorizationClient(this) }
    private val accountStore by lazy { GoogleDriveAccountStore(this) }
    private val state = mutableStateOf(GoogleDriveAccountState())
    private val syncStatus = mutableStateOf(GoogleDriveDirectSyncStatus())
    private val connectionTest = mutableStateOf(GoogleDriveConnectionTestState())

    private val authorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            state.value = state.value.copy(
                status = GoogleDriveAccountStatus.AUTHORIZATION_FAILED,
                message = "Googleアカウント接続がキャンセルされました",
            )
            return@registerForActivityResult
        }
        runCatching {
            authorizationClient.getAuthorizationResultFromIntent(result.data!!)
        }.onSuccess(::consumeAuthorizationResult)
            .onFailure(::handleAuthorizationFailure)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshState()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GoogleDriveAccountScreen(
                        state = state.value,
                        syncStatus = syncStatus.value,
                        connectionTest = connectionTest.value,
                        onConnect = { authorize(selectAccount = true) },
                        onVerify = { authorize(selectAccount = false) },
                        onSync = { synchronize(forceReimport = false) },
                        onForceSync = { synchronize(forceReimport = true) },
                        onConnectionTest = ::verifyConnectionTest,
                        onAutoSyncChanged = ::setAutoSync,
                        onDisconnect = ::disconnect,
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
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun refreshState() {
        state.value = accountStore.load()
        syncStatus.value = GoogleDriveDirectSyncStatusStore(this).load()
        connectionTest.value = GoogleDriveConnectionTestStore(this).load()
    }

    private fun authorize(selectAccount: Boolean) {
        state.value = state.value.copy(
            status = GoogleDriveAccountStatus.CONNECTING,
            message = if (selectAccount) {
                "Googleアカウントを選択しています"
            } else {
                "Google Drive接続を確認しています"
            },
        )
        val builder = AuthorizationRequest.builder()
            .setRequestedScopes(GoogleDriveAccountPolicy.requestedScopes)
        val storedEmail = accountStore.load().email
        if (selectAccount || storedEmail.isNullOrBlank()) {
            builder.setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
        } else {
            builder.setAccount(Account(storedEmail, "com.google"))
        }
        authorizationClient.authorize(builder.build())
            .addOnSuccessListener(::consumeAuthorizationResult)
            .addOnFailureListener(::handleAuthorizationFailure)
    }

    private fun consumeAuthorizationResult(result: AuthorizationResult) {
        if (result.hasResolution()) {
            val pendingIntent = result.pendingIntent
            if (pendingIntent == null) {
                handleAuthorizationFailure(IllegalStateException("認可画面を開始できません"))
                return
            }
            authorizationLauncher.launch(
                IntentSenderRequest.Builder(pendingIntent.intentSender).build(),
            )
            return
        }
        val accessToken = result.accessToken
        if (accessToken.isNullOrBlank()) {
            handleAuthorizationFailure(IllegalStateException("アクセストークンを取得できません"))
            return
        }
        lifecycleScope.launch {
            val verified = runCatching {
                withContext(Dispatchers.IO) {
                    GoogleDriveAboutApi.verify(accessToken)
                }
            }
            state.value = verified.fold(
                onSuccess = { profile ->
                    GoogleDriveAccountState(
                        status = GoogleDriveAccountStatus.CONNECTED,
                        email = profile.email,
                        displayName = profile.displayName,
                        permissionId = profile.permissionId,
                        lastVerifiedAt = System.currentTimeMillis(),
                        message = "Google Drive APIへ接続できました",
                    ).also(accountStore::save)
                },
                onFailure = { error ->
                    val apiDisabled = error is GoogleDriveProbeException &&
                        error.responseCode == 403 &&
                        (error.responseBody.contains("SERVICE_DISABLED") ||
                            error.responseBody.contains("accessNotConfigured"))
                    state.value.copy(
                        status = if (apiDisabled) {
                            GoogleDriveAccountStatus.DRIVE_API_DISABLED
                        } else {
                            GoogleDriveAccountStatus.VERIFICATION_FAILED
                        },
                        message = if (apiDisabled) {
                            "Google CloudでGoogle Drive APIを有効にしてください"
                        } else {
                            "Drive接続確認に失敗しました：${error.message ?: error.javaClass.simpleName}"
                        },
                    )
                },
            )
        }
    }

    private fun synchronize(forceReimport: Boolean) {
        if (state.value.email == null || syncStatus.value.running) return
        syncStatus.value = syncStatus.value.copy(
            running = true,
            lastMessage = if (forceReimport) "Drive上のJSONを全件再確認しています" else "Drive上の差分を確認しています",
        )
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val token = GoogleDriveSyncAccessTokenProvider.acquire(applicationContext)
                    GoogleDriveDirectSyncRepository(applicationContext).use { repository ->
                        repository.synchronize(token, forceReimport)
                    }
                }
            }
            syncStatus.value = GoogleDriveDirectSyncStatusStore(applicationContext).load()
            result.onFailure { error ->
                val category = GoogleDriveSyncErrorPolicy.classify(error)
                val message = "${GoogleDriveSyncErrorPolicy.message(category)}：${error.message ?: error.javaClass.simpleName}"
                GoogleDriveDirectSyncStatusStore(applicationContext).failed(message)
                syncStatus.value = GoogleDriveDirectSyncStatusStore(applicationContext).load()
                state.value = state.value.copy(message = message)
            }
        }
    }

    private fun verifyConnectionTest() {
        if (state.value.email == null || connectionTest.value.status == GoogleDriveConnectionTestStatus.RUNNING) return
        connectionTest.value = GoogleDriveConnectionTestStore(applicationContext).running()
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val token = GoogleDriveSyncAccessTokenProvider.acquire(applicationContext)
                    GoogleDriveConnectionTestVerifier(applicationContext).searchAndVerify(token)
                }
            }
            connectionTest.value = result.fold(
                onSuccess = { it },
                onFailure = { error ->
                    val category = GoogleDriveSyncErrorPolicy.classify(error)
                    GoogleDriveConnectionTestStore(applicationContext).failed(
                        "${GoogleDriveSyncErrorPolicy.message(category)}：${error.message ?: error.javaClass.simpleName}",
                    )
                },
            )
        }
    }

    private fun setAutoSync(enabled: Boolean) {
        GoogleDriveDirectSyncStatusStore(applicationContext).setAutoSyncOnLaunch(enabled)
        syncStatus.value = syncStatus.value.copy(autoSyncOnLaunch = enabled)
        if (enabled) {
            GoogleDriveDirectSyncScheduler.ensurePeriodic(applicationContext)
            GoogleDriveDirectSyncScheduler.enqueueStartup(applicationContext)
        }
    }

    private fun handleAuthorizationFailure(error: Throwable) {
        val status = GoogleDriveAccountPolicy.statusForAuthorizationError(error)
        state.value = state.value.copy(
            status = status,
            message = if (status == GoogleDriveAccountStatus.CLOUD_CONFIGURATION_REQUIRED) {
                "Google CloudにapplicationIdと署名SHA-1を登録してください"
            } else {
                "Googleアカウント認可に失敗しました：${error.message ?: error.javaClass.simpleName}"
            },
        )
    }

    private fun disconnect() {
        val email = state.value.email ?: accountStore.load().email
        if (email.isNullOrBlank()) {
            accountStore.clear()
            state.value = GoogleDriveAccountState()
            return
        }
        state.value = state.value.copy(
            status = GoogleDriveAccountStatus.CONNECTING,
            message = "Google Driveアクセスを解除しています",
        )
        val request = RevokeAccessRequest.builder()
            .setAccount(Account(email, "com.google"))
            .setScopes(GoogleDriveAccountPolicy.requestedScopes)
            .build()
        authorizationClient.revokeAccess(request)
            .addOnCompleteListener {
                accountStore.clear()
                state.value = GoogleDriveAccountState(
                    message = if (it.isSuccessful) {
                        "Googleアカウント連携を解除しました。取込済みローカル売上は削除していません"
                    } else {
                        "端末内の登録を解除しました。Google側の解除結果は確認できませんでした"
                    },
                )
            }
    }
}

@Composable
private fun GoogleDriveAccountScreen(
    state: GoogleDriveAccountState,
    syncStatus: GoogleDriveDirectSyncStatus,
    connectionTest: GoogleDriveConnectionTestState,
    onConnect: () -> Unit,
    onVerify: () -> Unit,
    onSync: () -> Unit,
    onForceSync: () -> Unit,
    onConnectionTest: () -> Unit,
    onAutoSyncChanged: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
    onOpenSetupGuide: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onClose: () -> Unit,
) {
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
                Text("Google Driveアカウント", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("つぐレジ＋がDrive APIから売上JSONを差分取得します")
            }
            OutlinedButton(onClick = onClose) { Text("戻る") }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (state.status == GoogleDriveAccountStatus.CONNECTED) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(accountStatusLabel(state.status), fontWeight = FontWeight.Bold)
                state.email?.let { Text("アカウント：$it") }
                state.displayName?.let { Text("表示名：$it") }
                Text("権限：drive.file（このGoogle Cloudプロジェクトが作成・許可されたファイルのみ）")
                Text(state.message)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(if (syncStatus.running) "Drive API同期中" else "Drive API同期状態", fontWeight = FontWeight.Bold)
                Text(syncStatus.lastMessage)
                Text("確認 ${syncStatus.listedCount}／取得 ${syncStatus.downloadedCount}／未変更 ${syncStatus.unchangedCount}")
                Text("新規 ${syncStatus.importedCount}／重複 ${syncStatus.duplicateCount}／隔離 ${syncStatus.rejectedCount}／読込エラー ${syncStatus.errorCount}")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("起動時に差分同期", fontWeight = FontWeight.SemiBold)
                        Text("接続済みの場合、起動時と1時間ごとに差分を確認します")
                    }
                    Switch(
                        checked = syncStatus.autoSyncOnLaunch,
                        onCheckedChange = onAutoSyncChanged,
                        enabled = !syncStatus.running,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onOpenSetupGuide,
                enabled = !syncStatus.running,
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("初期設定ガイド") }
            OutlinedButton(
                onClick = onOpenDiagnostics,
                enabled = !syncStatus.running,
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("診断・ログ") }
        }

        Button(
            onClick = onConnectionTest,
            enabled = state.email != null && !syncStatus.running && connectionTest.status != GoogleDriveConnectionTestStatus.RUNNING,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("つぐレジ接続テストを検索") }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("つぐレジ→つぐレジ＋ 接続テスト", fontWeight = FontWeight.Bold)
                Text(connectionTest.message)
                connectionTest.testId?.let { Text("テストID：$it") }
                connectionTest.fileName?.let { Text("ファイル：$it") }
                connectionTest.fileId?.let { Text("fileId：$it") }
                Text("接続テストJSONは売上取込・集計の対象にしません。")
            }
        }

        Button(
            onClick = onConnect,
            enabled = state.status != GoogleDriveAccountStatus.CONNECTING && !syncStatus.running,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(if (state.email == null) "Googleアカウントを登録" else "別アカウントへ変更")
        }
        OutlinedButton(
            onClick = onVerify,
            enabled = state.email != null && state.status != GoogleDriveAccountStatus.CONNECTING && !syncStatus.running,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("接続確認") }
        Button(
            onClick = onSync,
            enabled = state.email != null && !syncStatus.running,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("今すぐ差分同期") }
        OutlinedButton(
            onClick = onForceSync,
            enabled = state.email != null && !syncStatus.running,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("全件再取込") }
        OutlinedButton(
            onClick = onDisconnect,
            enabled = state.email != null && state.status != GoogleDriveAccountStatus.CONNECTING && !syncStatus.running,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("連携解除") }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("v0.50 アプリ間接続テスト対応", fontWeight = FontWeight.Bold)
                Text("Drive APIのfileId・modifiedTime・SHA-256をSQLiteへ保存し、変更されたJSONだけを取得します。")
                Text("取得したJSONは既存のSalesJournalImportRepositoryへ渡し、duplicateImportKeyで二重計上を防止します。")
                Text("不正JSONは隔離します。Drive上の削除とローカル売上削除は自動連動しません。")
                Text("フォルダ方式はUSB・端末フォルダ・Driveアプリ経由の互換用として残します。")
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

private fun accountStatusLabel(status: GoogleDriveAccountStatus): String = when (status) {
    GoogleDriveAccountStatus.NOT_CONNECTED -> "未接続"
    GoogleDriveAccountStatus.CONNECTING -> "接続処理中"
    GoogleDriveAccountStatus.CONNECTED -> "接続済み"
    GoogleDriveAccountStatus.CLOUD_CONFIGURATION_REQUIRED -> "Google Cloud設定が必要"
    GoogleDriveAccountStatus.DRIVE_API_DISABLED -> "Google Drive APIが無効"
    GoogleDriveAccountStatus.AUTHORIZATION_FAILED -> "アカウント認可失敗"
    GoogleDriveAccountStatus.VERIFICATION_FAILED -> "Drive接続確認失敗"
}
