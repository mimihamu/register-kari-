package jp.co.tenposinfo.register.plus

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.security.MessageDigest

internal data class GoogleDrivePlusAndroidClientInfo(
    val applicationId: String,
    val sha1: String,
    val sha256: String,
)

internal object GoogleDrivePlusAndroidClientInfoReader {
    fun read(context: Context): GoogleDrivePlusAndroidClientInfo {
        val signature = signatures(context).firstOrNull()?.toByteArray() ?: byteArrayOf()
        return GoogleDrivePlusAndroidClientInfo(
            applicationId = context.packageName,
            sha1 = digest("SHA-1", signature),
            sha256 = digest("SHA-256", signature),
        )
    }

    internal fun formatFingerprint(bytes: ByteArray): String =
        bytes.joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }

    private fun digest(algorithm: String, value: ByteArray): String =
        if (value.isEmpty()) {
            "取得できません"
        } else {
            formatFingerprint(MessageDigest.getInstance(algorithm).digest(value))
        }

    @Suppress("DEPRECATION")
    private fun signatures(context: Context) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo
            ?.apkContentsSigners
            .orEmpty()
    } else {
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            .signatures
            .orEmpty()
    }
}

internal object GoogleDrivePlusSetupGuidePolicy {
    const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

    val steps = listOf(
        "つぐレジと同じGoogle Cloudプロジェクトを選択する",
        "APIライブラリでGoogle Drive APIを有効にする",
        "Google Auth Platformのブランディング、対象ユーザー、データアクセスを設定する",
        "データアクセスへdrive.fileスコープを追加する",
        "OAuthクライアントをAndroidで新規作成する",
        "この画面のapplicationIdと署名SHA-1を登録する",
        "外部・テスト運用では利用するGoogleアカウントをテストユーザーへ追加する",
        "つぐレジ＋へ戻り、Googleアカウント登録、接続確認、今すぐ差分同期の順に実行する",
    )

    fun advice(status: GoogleDriveAccountStatus): String = when (status) {
        GoogleDriveAccountStatus.CLOUD_CONFIGURATION_REQUIRED ->
            "つぐレジ＋用Android OAuthクライアントのapplicationIdとSHA-1が、このAPKと一致しているか確認してください。"
        GoogleDriveAccountStatus.DRIVE_API_DISABLED ->
            "選択中のGoogle CloudプロジェクトでGoogle Drive APIを有効にしてください。"
        GoogleDriveAccountStatus.AUTHORIZATION_FAILED ->
            "テストユーザー、端末のGoogleアカウント、Google Play開発者サービスを確認してください。"
        GoogleDriveAccountStatus.VERIFICATION_FAILED ->
            "通信状態、Drive APIの有効化、認可状態を確認してから接続確認を再実行してください。"
        GoogleDriveAccountStatus.CONNECTED ->
            "接続済みです。今すぐ差分同期で売上JSONの取得を確認してください。"
        GoogleDriveAccountStatus.CONNECTING -> "接続処理の完了を待ってください。"
        GoogleDriveAccountStatus.NOT_CONNECTED -> "下の手順を完了してからGoogleアカウントを登録してください。"
    }
}

class GoogleDriveSetupGuideActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val clientInfo = GoogleDrivePlusAndroidClientInfoReader.read(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GoogleDrivePlusSetupGuideScreen(
                        clientInfo = clientInfo,
                        accountState = GoogleDriveAccountStore(this).load(),
                        onCopy = ::copyValue,
                        onOpenCloudConsole = {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.cloud.google.com/")))
                        },
                        onOpenAccount = {
                            startActivity(Intent(this, GoogleDriveAccountActivity::class.java))
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

    private fun copyValue(label: String, value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "$label をコピーしました", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun GoogleDrivePlusSetupGuideScreen(
    clientInfo: GoogleDrivePlusAndroidClientInfo,
    accountState: GoogleDriveAccountState,
    onCopy: (String, String) -> Unit,
    onOpenCloudConsole: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onClose: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "つぐレジ＋ Google Drive初期設定",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("Google Cloudへ登録する値を、このAPKから自動取得しています")
                }
                OutlinedButton(onClick = onClose) { Text("戻る") }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("1. Android OAuthクライアントへ登録する値", fontWeight = FontWeight.Bold)
                    PlusClientValue("applicationId（パッケージ名）", clientInfo.applicationId, onCopy)
                    PlusClientValue("署名SHA-1", clientInfo.sha1, onCopy)
                    PlusClientValue("署名SHA-256（照合用）", clientInfo.sha256, onCopy)
                    Text("OAuth登録に使うのはapplicationIdとSHA-1です。開発版と本番版で署名が異なる場合はOAuthクライアントも分けます。")
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text("2. Google Cloudで行う作業", fontWeight = FontWeight.Bold)
                    GoogleDrivePlusSetupGuidePolicy.steps.forEachIndexed { index, step ->
                        Text("${index + 1}. $step")
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text("現在の状態：${plusAccountStatusLabel(accountState.status)}", fontWeight = FontWeight.Bold)
                    Text(GoogleDrivePlusSetupGuidePolicy.advice(accountState.status))
                    Text("要求スコープ：${GoogleDrivePlusSetupGuidePolicy.DRIVE_FILE_SCOPE}")
                    Text("つぐレジとつぐレジ＋はapplicationIdが異なるため、同じGoogle Cloudプロジェクト内でも別々のAndroid OAuthクライアントが必要です。")
                    Text("drive.fileでは、つぐレジが作成したファイルをつぐレジ＋が列挙できるか実機確認が必要です。確認前に連携済みとは断定しません。")
                }
            }
        }

        item {
            Button(
                onClick = onOpenCloudConsole,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Google Cloud Consoleを開く") }
            OutlinedButton(
                onClick = onOpenAccount,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Googleアカウント登録へ進む") }
            OutlinedButton(
                onClick = onOpenDiagnostics,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("診断・ログを開く") }
            Text("パスワード、アクセストークン、更新トークンは診断レポートへ出力しません。")
        }
    }
}

@Composable
private fun PlusClientValue(
    label: String,
    value: String,
    onCopy: (String, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold)
        Text(value)
        OutlinedButton(onClick = { onCopy(label, value) }) { Text("コピー") }
    }
}

private fun plusAccountStatusLabel(status: GoogleDriveAccountStatus): String = when (status) {
    GoogleDriveAccountStatus.NOT_CONNECTED -> "未接続"
    GoogleDriveAccountStatus.CONNECTING -> "接続処理中"
    GoogleDriveAccountStatus.CONNECTED -> "接続済み"
    GoogleDriveAccountStatus.CLOUD_CONFIGURATION_REQUIRED -> "Google Cloud設定が必要"
    GoogleDriveAccountStatus.DRIVE_API_DISABLED -> "Google Drive APIが無効"
    GoogleDriveAccountStatus.AUTHORIZATION_FAILED -> "Google認可失敗"
    GoogleDriveAccountStatus.VERIFICATION_FAILED -> "Drive接続確認失敗"
}
