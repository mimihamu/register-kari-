package jp.co.tenposinfo.register.plus

import android.accounts.Account
import android.app.Activity
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class GoogleDriveEasyConnectUiState(
    val account: GoogleDriveAccountState = GoogleDriveAccountState(),
    val sync: GoogleDriveDirectSyncStatus = GoogleDriveDirectSyncStatus(),
    val connectionTest: GoogleDriveConnectionTestState = GoogleDriveConnectionTestState(),
    val folderAutoImportEnabled: Boolean = false,
    val busy: Boolean = false,
    val message: String = "Googleアカウントを選ぶだけで、つぐレジとの同期を設定します",
)

object GoogleDriveEasyConnectPolicy {
    fun isReady(state: GoogleDriveEasyConnectUiState): Boolean =
        state.account.email != null &&
            state.connectionTest.status == GoogleDriveConnectionTestStatus.SUCCEEDED &&
            state.sync.autoSyncOnLaunch &&
            !state.folderAutoImportEnabled &&
            !state.busy

    fun statusLabel(state: GoogleDriveEasyConnectUiState): String = when {
        state.busy -> "設定中"
        state.account.email == null -> "未接続"
        isReady(state) -> "正常"
        state.connectionTest.status == GoogleDriveConnectionTestStatus.NOT_FOUND -> "つぐレジ側の準備待ち"
        state.connectionTest.status == GoogleDriveConnectionTestStatus.RUNNING -> "接続確認中"
        else -> "確認が必要"
    }
}

class GoogleDriveEasyConnectActivity : ComponentActivity() {
    private val authorizationClient by lazy { Identity.getAuthorizationClient(this) }
    private val accountStore by lazy { GoogleDriveAccountStore(this) }
    private val uiState = mutableStateOf(GoogleDriveEasyConnectUiState())

    private val authorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            uiState.value = uiState.value.copy(
                busy = false,
                message = "Googleアカウントの選択がキャンセルされました",
            )
            return@registerForActivityResult
        }
        runCatching {
            authorizationClient.getAuthorizationResultFromIntent(result.data!!)
        }.onSuccess(::consumeAuthorizationResult)
            .onFailure(::handleFailure)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refresh()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GoogleDriveEasyConnectScreen(
                        state = uiState.value,
                        onConnect = ::connect,
                        onSync = ::synchronizeNow,
                        onDisconnect = ::disconnect,
                        onOpenAdvanced = {
                            startActivity(Intent(this, GoogleDriveRecoveryActivity::class.java))
                        },
                        onClose = ::finish,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh(message: String? = null) {
        uiState.value = GoogleDriveEasyConnectUiState(
            account = accountStore.load(),
            sync = GoogleDriveDirectSyncStatusStore(applicationContext).load(),
            connectionTest = GoogleDriveConnectionTestStore(applicationContext).load(),
            folderAutoImportEnabled = DriveSyncPreferences(applicationContext).autoImportOnLaunch(),
            busy = uiState.value.busy,
            message = message ?: uiState.value.message,
        )
    }

    private fun connect() {
        if (uiState.value.busy) return
        uiState.value = uiState.value.copy(
            busy = true,
            message = "Googleアカウントを選択しています",
        )
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(GoogleDriveAccountPolicy.requestedScopes)
            .setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
            .build()
        authorizationClient.authorize(request)
            .addOnSuccessListener(::consumeAuthorizationResult)
            .addOnFailureListener(::handleFailure)
    }

    private fun consumeAuthorizationResult(result: AuthorizationResult) {
        if (result.hasResolution()) {
            val pendingIntent = result.pendingIntent
            if (pendingIntent == null) {
                handleFailure(IllegalStateException("Google認可画面を開始できません"))
                return
            }
            authorizationLauncher.launch(
                IntentSenderRequest.Builder(pendingIntent.intentSender).build(),
            )
            return
        }
        val accessToken = result.accessToken
        if (accessToken.isNullOrBlank()) {
            handleFailure(IllegalStateException("Google認可情報を取得できません"))
            return
        }
        uiState.value = uiState.value.copy(
            busy = true,
            message = "接続を確認し、自動同期を準備しています",
        )
        lifecycleScope.launch {
            val profileResult = runCatching {
                withContext(Dispatchers.IO) { GoogleDriveAboutApi.verify(accessToken) }
            }
            profileResult.fold(
                onSuccess = { profile -> finishEasySetup(accessToken, profile) },
                onFailure = ::handleFailure,
            )
        }
    }

    private fun finishEasySetup(accessToken: String, profile: GoogleDriveProfile) {
        val account = GoogleDriveAccountState(
            status = GoogleDriveAccountStatus.CONNECTED,
            email = profile.email,
            displayName = profile.displayName,
            permissionId = profile.permissionId,
            lastVerifiedAt = System.currentTimeMillis(),
            message = "Google Driveへ接続済みです",
        )
        accountStore.save(account)
        uiState.value = uiState.value.copy(account = account)
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    applyDirectSyncDefaults()
                    val connection = GoogleDriveConnectionTestVerifier(applicationContext)
                        .searchAndVerify(accessToken)
                    val syncResult = if (connection.status == GoogleDriveConnectionTestStatus.SUCCEEDED) {
                        GoogleDriveDirectSyncRepository(applicationContext).use { repository ->
                            repository.synchronize(accessToken, forceReimport = false)
                        }
                    } else {
                        null
                    }
                    connection to syncResult
                }
            }
            result.fold(
                onSuccess = { (connection, syncResult) ->
                    uiState.value = uiState.value.copy(busy = false)
                    refresh(
                        when (connection.status) {
                            GoogleDriveConnectionTestStatus.SUCCEEDED ->
                                "Google連携が完了しました。新規${syncResult?.importedCount ?: 0}件、重複${syncResult?.duplicateCount ?: 0}件を確認しました"
                            GoogleDriveConnectionTestStatus.NOT_FOUND ->
                                "Google連携は完了しました。つぐレジ側でも同じGoogleアカウントを選ぶと自動で接続されます"
                            else -> connection.message
                        },
                    )
                },
                onFailure = { error ->
                    uiState.value = uiState.value.copy(busy = false)
                    refresh(
                        "アカウント接続は完了しましたが、自動確認に失敗しました：${error.message ?: error.javaClass.simpleName}",
                    )
                },
            )
        }
    }

    private fun applyDirectSyncDefaults() {
        GoogleDriveOperatingModeStore(applicationContext).save(GoogleDriveOperatingMode.DRIVE_API)
        DriveSyncPreferences(applicationContext).setAutoImportOnLaunch(false)
        GoogleDriveDirectSyncStatusStore(applicationContext).setAutoSyncOnLaunch(true)
        GoogleDriveDirectSyncScheduler.setAutomaticSyncEnabled(applicationContext, enabled = true)
    }

    private fun synchronizeNow() {
        if (uiState.value.account.email == null || uiState.value.busy) return
        uiState.value = uiState.value.copy(
            busy = true,
            message = "つぐレジとの接続確認と売上同期を実行しています",
        )
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    applyDirectSyncDefaults()
                    val token = GoogleDriveSyncAccessTokenProvider.acquire(applicationContext)
                    val connection = GoogleDriveConnectionTestVerifier(applicationContext)
                        .searchAndVerify(token)
                    val syncResult = if (connection.status == GoogleDriveConnectionTestStatus.SUCCEEDED) {
                        GoogleDriveDirectSyncRepository(applicationContext).use { repository ->
                            repository.synchronize(token, forceReimport = false)
                        }
                    } else {
                        null
                    }
                    connection to syncResult
                }
            }
            uiState.value = uiState.value.copy(busy = false)
            result.fold(
                onSuccess = { (connection, syncResult) ->
                    refresh(
                        if (connection.status == GoogleDriveConnectionTestStatus.SUCCEEDED) {
                            "同期完了：新規${syncResult?.importedCount ?: 0}件／重複${syncResult?.duplicateCount ?: 0}件／隔離${syncResult?.rejectedCount ?: 0}件"
                        } else {
                            "つぐレジ側の接続確認ファイルがまだ見つかりません"
                        },
                    )
                },
                onFailure = { error ->
                    val category = GoogleDriveSyncErrorPolicy.classify(error)
                    refresh(
                        "${GoogleDriveSyncErrorPolicy.message(category)}：${error.message ?: error.javaClass.simpleName}",
                    )
                },
            )
        }
    }

    private fun disconnect() {
        val email = uiState.value.account.email ?: accountStore.load().email
        if (email.isNullOrBlank()) {
            accountStore.clear()
            refresh("Google連携は解除されています")
            return
        }
        uiState.value = uiState.value.copy(
            busy = true,
            message = "Google連携を解除しています",
        )
        val request = RevokeAccessRequest.builder()
            .setAccount(Account(email, "com.google"))
            .setScopes(GoogleDriveAccountPolicy.requestedScopes)
            .build()
        authorizationClient.revokeAccess(request).addOnCompleteListener { task ->
            accountStore.clear()
            GoogleDriveDirectSyncStatusStore(applicationContext).setAutoSyncOnLaunch(false)
            GoogleDriveDirectSyncScheduler.setAutomaticSyncEnabled(applicationContext, enabled = false)
            uiState.value = GoogleDriveEasyConnectUiState(
                message = if (task.isSuccessful) {
                    "Google連携を解除しました。取込済み売上と同期履歴は削除していません"
                } else {
                    "端末内の連携を解除しました。Google側の解除結果は確認できませんでした"
                },
            )
        }
    }

    private fun handleFailure(error: Throwable) {
        val status = GoogleDriveAccountPolicy.statusForAuthorizationError(error)
        uiState.value = uiState.value.copy(
            busy = false,
            account = uiState.value.account.copy(status = status),
            message = if (status == GoogleDriveAccountStatus.CLOUD_CONFIGURATION_REQUIRED) {
                "Google Cloudのアプリ登録を確認してください"
            } else {
                "Google連携に失敗しました：${error.message ?: error.javaClass.simpleName}"
            },
        )
    }
}

@Composable
private fun GoogleDriveEasyConnectScreen(
    state: GoogleDriveEasyConnectUiState,
    onConnect: () -> Unit,
    onSync: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onClose: () -> Unit,
) {
    val ready = GoogleDriveEasyConnectPolicy.isReady(state)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Googleかんたん接続", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Googleアカウントを選ぶだけで、つぐレジとの接続確認と自動同期を設定します")
            }
            OutlinedButton(onClick = onClose) { Text("戻る") }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (ready) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("状態：${GoogleDriveEasyConnectPolicy.statusLabel(state)}", fontWeight = FontWeight.Bold)
                state.account.email?.let { Text("Googleアカウント：$it") }
                Text(state.message)
                Text("最終同期：${formatEasyConnectTime(state.sync.lastCompletedAt)}")
                Text("新規 ${state.sync.importedCount}／重複 ${state.sync.duplicateCount}／隔離 ${state.sync.rejectedCount}／エラー ${state.sync.errorCount}")
            }
        }

        Button(
            onClick = onConnect,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text(if (state.account.email == null) "Googleと連携" else "Googleアカウントを変更")
        }
        Button(
            onClick = onSync,
            enabled = state.account.email != null && !state.busy,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("今すぐ同期") }
        OutlinedButton(
            onClick = onDisconnect,
            enabled = state.account.email != null && !state.busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("連携を解除") }
        OutlinedButton(
            onClick = onOpenAdvanced,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("保守・復旧") }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("通常はこの画面だけで設定できます", fontWeight = FontWeight.Bold)
                Text("Drive APIを通常経路として選択し、1時間ごとの自動同期を有効にします。")
                Text("互換フォルダの自動取込は停止し、二重取込を防ぎます。")
                Text("互換フォルダ、方式切替、全件再確認、詳細診断は「保守・復旧」にまとめています。")
                Text("アクセストークンと更新トークンは保存しません。")
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun formatEasyConnectTime(value: Long?): String = value?.let {
    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(Date(it))
} ?: "未実行"
