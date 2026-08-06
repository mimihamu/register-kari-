package jp.co.tenposinfo.register

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
        "tsuguregi_google_drive_account",
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
    private val diagnosticLog by lazy { GoogleDriveDiagnosticLogStore(this) }
    private val state = mutableStateOf(GoogleDriveAccountState())
    private val uploadStatus = mutableStateOf(GoogleDriveDirectUploadStatus())
    private val connectionTest = mutableStateOf(GoogleDriveConnectionTestState())

    private val authorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            diagnosticLog.append("AUTHORIZATION", "CANCELLED", "Googleアカウント接続がキャンセルされました")
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
        state.value = accountStore.load()
        uploadStatus.value = GoogleDriveDirectUploadStatusStore(this).load()
        connectionTest.value = GoogleDriveConnectionTestStore(this).load()
        configureRegisterSystemBars(window)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GoogleDriveAccountScreen(
                        state = state.value,
                        uploadStatus = uploadStatus.value,
                        connectionTest = connectionTest.value,
                        onConnect = { authorize(selectAccount = true) },
                        onVerify = { authorize(selectAccount = false) },
                        onUpload = ::uploadNow,
                        onRetry = ::retryFailed,
                        onConnectionTest = ::createConnectionTest,
                        onDiagnostics = {
                            startActivity(Intent(this, GoogleDriveDiagnosticsActivity::class.java))
                        },
                        onDisconnect = ::disconnect,
                        onClose = ::finish,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        state.value = accountStore.load()
        uploadStatus.value = GoogleDriveDirectUploadStatusStore(this).load()
        connectionTest.value = GoogleDriveConnectionTestStore(this).load()
    }

    private fun authorize(selectAccount: Boolean) {
        diagnosticLog.append(
            "AUTHORIZATION",
            "STARTED",
            if (selectAccount) "アカウント選択" else "接続確認",
        )
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
            diagnosticLog.append("AUTHORIZATION", "RESOLUTION_REQUIRED", "Google同意画面を表示します")
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
                    diagnosticLog.append("ABOUT_GET", "SUCCESS", "Drive API接続確認成功")
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
                    diagnosticLog.append(
                        "ABOUT_GET",
                        if (apiDisabled) "API_DISABLED" else "FAILED",
                        error.message ?: error.javaClass.simpleName,
                    )
                    state.value.copy(
                        status = if (apiDisabled) {
                            GoogleDriveAccountStatus.DRIVE_API_DISABLED
                        } else {
                            GoogleDriveAccountStatus.VERIFICATION_FAILED
                        },
                        message = if (apiDisabled) {
                            "Google Drive APIが無効です。同期設定の初期設定ガイドを確認してください"
                        } else {
                            "Drive接続確認に失敗しました：${error.message ?: error.javaClass.simpleName}"
                        },
                    )
                },
            )
        }
    }

    private fun uploadNow() {
        diagnosticLog.append("MANUAL_UPLOAD", "REQUESTED", "アカウント画面から今すぐアップロード")
        JournalOutboxStore(applicationContext).use { it.stagePending(500) }
        GoogleDriveDirectUploadScheduler.enqueueNow(applicationContext)
        uploadStatus.value = uploadStatus.value.copy(
            running = true,
            lastMessage = "Google Driveへの直接アップロードを要求しました",
        )
    }

    private fun createConnectionTest() {
        if (state.value.email == null || connectionTest.value.status == GoogleDriveConnectionTestStatus.RUNNING) return
        connectionTest.value = GoogleDriveConnectionTestStore(applicationContext).running()
        diagnosticLog.append("CONNECTION_TEST", "STARTED", "売上なし接続テストJSON作成")
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val token = GoogleDriveAccessTokenProvider.acquire(applicationContext)
                    GoogleDriveConnectionTestCoordinator(applicationContext).createOrUpdate(token)
                }
            }
            connectionTest.value = result.fold(
                onSuccess = { completed ->
                    diagnosticLog.append(
                        "CONNECTION_TEST",
                        "SUCCESS",
                        "testId=${completed.testId} / fileId=${completed.fileId}",
                    )
                    completed
                },
                onFailure = { error ->
                    val category = GoogleDriveApiErrorPolicy.classify(error)
                    diagnosticLog.append(
                        "CONNECTION_TEST",
                        category.name,
                        error.message ?: error.javaClass.simpleName,
                    )
                    GoogleDriveConnectionTestStore(applicationContext).failed(
                        "${GoogleDriveApiErrorPolicy.message(category)}：${error.message ?: error.javaClass.simpleName}",
                    )
                },
            )
        }
    }

    private fun retryFailed() {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                GoogleDriveDirectUploadCoordinator(applicationContext).retryPermanentFailures()
            }
            GoogleDriveDirectUploadScheduler.enqueueNow(applicationContext)
            diagnosticLog.append("RETRY_FAILED", "REQUESTED", "$count 件を再試行へ戻しました")
            uploadStatus.value = uploadStatus.value.copy(
                running = count > 0,
                lastMessage = "$count 件を再試行へ戻しました",
            )
        }
    }

    private fun handleAuthorizationFailure(error: Throwable) {
        val status = GoogleDriveAccountPolicy.statusForAuthorizationError(error)
        diagnosticLog.append(
            "AUTHORIZATION",
            status.name,
            error.message ?: error.javaClass.simpleName,
        )
        state.value = state.value.copy(
            status = status,
            message = if (status == GoogleDriveAccountStatus.CLOUD_CONFIGURATION_REQUIRED) {
                "Google Cloud設定が必要です。同期設定の初期設定ガイドを確認してください"
            } else {
                "Googleアカウント認可に失敗しました：${error.message ?: error.javaClass.simpleName}"
            },
        )
    }

    private fun disconnect() {
        val email = state.value.email ?: accountStore.load().email
        if (email.isNullOrBlank()) {
            diagnosticLog.append("DISCONNECT", "LOCAL_CLEAR", "登録アカウントなし")
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
                diagnosticLog.append(
                    "DISCONNECT",
                    if (it.isSuccessful) "SUCCESS" else "GOOGLE_RESULT_UNKNOWN",
                    "ローカル売上データは削除していません",
                )
                accountStore.clear()
                state.value = GoogleDriveAccountState(
                    message = if (it.isSuccessful) {
                        "Googleアカウント連携を解除しました。ローカル売上データは削除していません"
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
    uploadStatus: GoogleDriveDirectUploadStatus,
    connectionTest: GoogleDriveConnectionTestState,
    onConnect: () -> Unit,
    onVerify: () -> Unit,
    onUpload: () -> Unit,
    onRetry: () -> Unit,
    onConnectionTest: () -> Unit,
    onDiagnostics: () -> Unit,
    onDisconnect: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Google Driveアカウント連携",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text("つぐレジが売上ジャーナルJSONをDrive APIで直接送信します")
            }
            OutlinedButton(onClick = onClose) { Text("戻る") }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AccountCard(state, Modifier.weight(1f))
            UploadStatusCard(uploadStatus, Modifier.weight(1f))
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onConnect,
                enabled = state.status != GoogleDriveAccountStatus.CONNECTING,
                modifier = Modifier.weight(1f).height(52.dp),
            ) {
                Text(if (state.email == null) "Googleアカウントを登録" else "別アカウントへ変更")
            }
            OutlinedButton(
                onClick = onVerify,
                enabled = state.email != null && state.status != GoogleDriveAccountStatus.CONNECTING,
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("接続確認") }
            Button(
                onClick = onUpload,
                enabled = state.email != null && !uploadStatus.running,
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("今すぐアップロード") }
            OutlinedButton(
                onClick = onRetry,
                enabled = state.email != null && !uploadStatus.running,
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("失敗を再試行") }
            OutlinedButton(
                onClick = onDisconnect,
                enabled = state.email != null && state.status != GoogleDriveAccountStatus.CONNECTING,
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("連携解除") }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onDiagnostics,
                modifier = Modifier.weight(1f).height(48.dp),
            ) { Text("診断・ログ") }
            Button(
                onClick = onConnectionTest,
                enabled = state.email != null && connectionTest.status != GoogleDriveConnectionTestStatus.RUNNING,
                modifier = Modifier.weight(1f).height(48.dp),
            ) { Text("接続テストJSONを作成") }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("つぐレジ→つぐレジ＋ 接続テスト", fontWeight = FontWeight.Bold)
                Text(connectionTest.message)
                connectionTest.testId?.let { Text("テストID：$it") }
                connectionTest.fileName?.let { Text("ファイル：$it") }
                connectionTest.fileId?.let { Text("fileId：$it") }
                Text("商品名・金額・税・支払などの売上情報は含めません。")
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("v0.50 アプリ間接続テスト対応", fontWeight = FontWeight.Bold)
                Text("Drive上には つぐレジ/stores/{storeId}/terminals/{terminalId}/journal/{businessDate}/{duplicateKey}.json を作成します。")
                Text("フォルダとJSONはappProperties、親fileId、重複キー、SHA-256で識別します。")
                Text("アクセストークンと更新トークンは保存しません。Driveは同期・バックアップ経路であり、SQLiteのローカル売上を原本として維持します。")
                Text("互換用フォルダ送信は削除せず、USB・端末フォルダ・Driveアプリ経由の運用として併存します。")
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun AccountCard(state: GoogleDriveAccountState, modifier: Modifier) {
    Card(
        modifier = modifier,
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
            Text("権限：drive.file（このプロジェクトが作成・許可されたファイルのみ）")
            Text(state.message)
        }
    }
}

@Composable
private fun UploadStatusCard(status: GoogleDriveDirectUploadStatus, modifier: Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(if (status.running) "直接アップロード処理中" else "直接アップロード状態", fontWeight = FontWeight.Bold)
            Text(status.lastMessage)
            Text("送信 ${status.uploadedCount}／既存 ${status.duplicateCount}／再試行 ${status.retryCount}／永久失敗 ${status.permanentFailureCount}")
        }
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
