package jp.co.tenposinfo.register

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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

internal data class GoogleDriveAndroidClientInfo(
    val applicationId: String,
    val sha1: String,
    val sha256: String,
)

internal object GoogleDriveAndroidClientInfoReader {
    fun read(context: Context): GoogleDriveAndroidClientInfo {
        val signature = signatures(context).firstOrNull()?.toByteArray() ?: byteArrayOf()
        return GoogleDriveAndroidClientInfo(
            applicationId = context.packageName,
            sha1 = digest("SHA-1", signature),
            sha256 = digest("SHA-256", signature),
        )
    }

    internal fun formatFingerprint(bytes: ByteArray): String =
        bytes.joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }

    private fun digest(algorithm: String, value: ByteArray): String =
        if (value.isEmpty()) "取得できません" else formatFingerprint(MessageDigest.getInstance(algorithm).digest(value))

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

internal object GoogleDriveSetupGuidePolicy {
    const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

    val steps = listOf(
        "Google Cloudでプロジェクトを作成または選択する",
        "APIライブラリでGoogle Drive APIを有効にする",
        "Google Auth Platformのブランディング、対象ユーザー、データアクセスを設定する",
        "データアクセスへdrive.fileスコープを追加する",
        "OAuthクライアントをAndroidで作成し、この画面のapplicationIdとSHA-1を登録する",
        "外部・テスト運用の場合は利用するGoogleアカウントをテストユーザーへ追加する",
        "つぐレジへ戻り、Googleアカウント登録、接続確認、今すぐアップロードの順に実行する",
    )

    fun advice(status: GoogleDriveAccountStatus): String = when (status) {
        GoogleDriveAccountStatus.CLOUD_CONFIGURATION_REQUIRED ->
            "Android OAuthクライアントのapplicationIdまたはSHA-1が、このAPKと一致しているか確認してください。"
        GoogleDriveAccountStatus.DRIVE_API_DISABLED ->
            "選択中のGoogle CloudプロジェクトでGoogle Drive APIを有効にしてください。"
        GoogleDriveAccountStatus.AUTHORIZATION_FAILED ->
            "テストユーザー、端末のGoogleアカウント、Google Play開発者サービスを確認してください。"
        GoogleDriveAccountStatus.VERIFICATION_FAILED ->
            "通信状態、Drive APIの有効化、認可の有効期限を確認してから接続確認を再実行してください。"
        GoogleDriveAccountStatus.CONNECTED ->
            "接続済みです。今すぐアップロードで売上JSONの送信を確認してください。"
        GoogleDriveAccountStatus.CONNECTING -> "接続処理の完了を待ってください。"
        GoogleDriveAccountStatus.NOT_CONNECTED -> "下の手順を完了してからGoogleアカウントを登録してください。"
    }
}

class GoogleDriveSetupGuideActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureRegisterSystemBars(window)
        val clientInfo = GoogleDriveAndroidClientInfoReader.read(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GoogleDriveSetupGuideScreen(
                        clientInfo = clientInfo,
                        accountState = GoogleDriveAccountStore(this).load(),
                        onCopy = ::copyValue,
                        onOpenCloudConsole = {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.cloud.google.com/")))
                        },
                        onOpenAccount = {
                            startActivity(Intent(this, GoogleDriveAccountActivity::class.java))
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
private fun GoogleDriveSetupGuideScreen(
    clientInfo: GoogleDriveAndroidClientInfo,
    accountState: GoogleDriveAccountState,
    onCopy: (String, String) -> Unit,
    onOpenCloudConsole: () -> Unit,
    onOpenAccount: () -> Unit,
    onClose: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Google Drive初期設定ガイド", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Google Cloudへ登録する値を、このAPKから自動取得しています")
                }
                OutlinedButton(onClick = onClose) { Text("戻る") }
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(modifier = Modifier.weight(1f)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("1. Android OAuthクライアントへ登録する値", fontWeight = FontWeight.Bold)
                        ClientValue("applicationId（パッケージ名）", clientInfo.applicationId, onCopy)
                        ClientValue("署名SHA-1", clientInfo.sha1, onCopy)
                        ClientValue("署名SHA-256（照合用）", clientInfo.sha256, onCopy)
                        Text("OAuthクライアント作成時に必要なのはapplicationIdとSHA-1です。SHA-256はAPK照合用です。")
                    }
                }

                Card(modifier = Modifier.weight(1f)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("2. Google Cloudで行う作業", fontWeight = FontWeight.Bold)
                        GoogleDriveSetupGuidePolicy.steps.forEachIndexed { index, step ->
                            Text("${index + 1}. $step")
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("現在の状態：${accountStatusLabelForGuide(accountState.status)}", fontWeight = FontWeight.Bold)
                    Text(GoogleDriveSetupGuidePolicy.advice(accountState.status))
                    Text("要求スコープ：${GoogleDriveSetupGuidePolicy.DRIVE_FILE_SCOPE}")
                    Text("つぐレジ＋は別のAndroid OAuthクライアントが必要です。つぐレジと同じGoogle Cloudプロジェクト内で、つぐレジ＋のapplicationIdとSHA-1を別登録します。")
                }
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onOpenCloudConsole, modifier = Modifier.weight(1f).height(52.dp)) {
                    Text("Google Cloud Consoleを開く")
                }
                Button(onClick = onOpenAccount, modifier = Modifier.weight(1f).height(52.dp)) {
                    Text("Googleアカウント登録へ進む")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Google Cloud設定はブラウザで行います。アプリ側へパスワード、アクセストークン、更新トークンは保存しません。")
        }
    }
}

@Composable
private fun ClientValue(
    label: String,
    value: String,
    onCopy: (String, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(value, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { onCopy(label, value) }) { Text("コピー") }
        }
    }
}

private fun accountStatusLabelForGuide(status: GoogleDriveAccountStatus): String = when (status) {
    GoogleDriveAccountStatus.NOT_CONNECTED -> "未接続"
    GoogleDriveAccountStatus.CONNECTING -> "接続処理中"
    GoogleDriveAccountStatus.CONNECTED -> "接続済み"
    GoogleDriveAccountStatus.CLOUD_CONFIGURATION_REQUIRED -> "Google Cloud設定が必要"
    GoogleDriveAccountStatus.DRIVE_API_DISABLED -> "Google Drive APIが無効"
    GoogleDriveAccountStatus.AUTHORIZATION_FAILED -> "Google認可失敗"
    GoogleDriveAccountStatus.VERIFICATION_FAILED -> "Drive接続確認失敗"
}
