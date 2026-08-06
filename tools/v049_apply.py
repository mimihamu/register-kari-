from pathlib import Path
import re

ROOT = Path('.')


def replace_once(path: str, old: str, new: str) -> None:
    target = ROOT / path
    text = target.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'missing replacement in {path}: {old[:120]!r}')
    target.write_text(text.replace(old, new, 1), encoding='utf-8')


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content.strip() + '\n', encoding='utf-8')


replace_once(
    'app/build.gradle.kts',
    'versionCode = 78\n        versionName = "0.48.0-dev.1"',
    'versionCode = 79\n        versionName = "0.49.0-dev.1"',
)
replace_once(
    'management-app/build.gradle.kts',
    'versionCode = 7\n        versionName = "0.7.0-dev.1"',
    'versionCode = 8\n        versionName = "0.8.0-dev.1"',
)

replace_once(
    'management-app/src/main/AndroidManifest.xml',
    '''        <activity
            android:name=".GoogleDriveAccountActivity"
            android:exported="false"
            android:screenOrientation="sensorPortrait" />''',
    '''        <activity
            android:name=".GoogleDriveSetupGuideActivity"
            android:exported="false"
            android:screenOrientation="sensorPortrait" />

        <activity
            android:name=".GoogleDriveDiagnosticsActivity"
            android:exported="false"
            android:screenOrientation="sensorPortrait" />

        <activity
            android:name=".GoogleDriveAccountActivity"
            android:exported="false"
            android:screenOrientation="sensorPortrait" />''',
)

replace_once(
    'management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveAccountActivity.kt',
    'import android.content.Context\n',
    'import android.content.Context\nimport android.content.Intent\n',
)
replace_once(
    'management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveAccountActivity.kt',
    '''                        onAutoSyncChanged = ::setAutoSync,
                        onDisconnect = ::disconnect,
                        onClose = ::finish,''',
    '''                        onAutoSyncChanged = ::setAutoSync,
                        onDisconnect = ::disconnect,
                        onOpenSetupGuide = {
                            startActivity(Intent(this, GoogleDriveSetupGuideActivity::class.java))
                        },
                        onOpenDiagnostics = {
                            startActivity(Intent(this, GoogleDriveDiagnosticsActivity::class.java))
                        },
                        onClose = ::finish,''',
)
replace_once(
    'management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveAccountActivity.kt',
    '''    onAutoSyncChanged: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
    onClose: () -> Unit,''',
    '''    onAutoSyncChanged: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
    onOpenSetupGuide: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onClose: () -> Unit,''',
)
replace_once(
    'management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveAccountActivity.kt',
    '''        Button(
            onClick = onConnect,''',
    '''        Row(
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
            onClick = onConnect,''',
)
replace_once(
    'management-app/src/main/java/jp/co/tenposinfo/register/plus/ManagementFolderSyncScreen.kt',
    'Text("Googleアカウント連携")',
    'Text("Google Drive設定・診断")',
)

setup_guide = r'''
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
'''
write(
    'management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveSetupGuideActivity.kt',
    setup_guide,
)

diagnostics = r'''
package jp.co.tenposinfo.register.plus

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object GoogleDrivePlusDiagnosticSanitizer {
    private val bearer = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+")
    private val googleToken = Regex("ya29\\.[A-Za-z0-9._-]+")
    private val contentUri = Regex("content://[^\\s]+")

    fun detail(value: String?): String = value.orEmpty()
        .replace(bearer, "Bearer [REDACTED]")
        .replace(googleToken, "[REDACTED_TOKEN]")
        .replace(contentUri, "content://[REDACTED]")
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()
        .take(800)

    fun maskedEmail(value: String?): String {
        val email = value?.trim().orEmpty()
        if (email.isEmpty()) return "未登録"
        val at = email.indexOf('@')
        if (at <= 0 || at == email.lastIndex) return "***"
        return "${email.substring(0, at).take(1)}***@${email.substring(at + 1)}"
    }
}

internal data class GoogleDrivePlusDiagnosticDatabaseState(
    val driveSyncFileCount: Int = 0,
    val importedJournalCount: Int = 0,
    val rejectionCount: Int = 0,
    val importRunCounts: Map<String, Int> = emptyMap(),
    val recentFailures: List<String> = emptyList(),
    val error: String? = null,
)

internal data class GoogleDrivePlusDiagnosticSnapshot(
    val generatedAt: Long,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val sha1: String,
    val sha256: String,
    val device: String,
    val androidVersion: String,
    val playServices: String,
    val network: String,
    val accountStatus: String,
    val maskedAccount: String,
    val accountMessage: String,
    val lastVerifiedAt: Long?,
    val syncStatus: GoogleDriveDirectSyncStatus,
    val compatibilityFolder: String,
    val database: GoogleDrivePlusDiagnosticDatabaseState,
)

internal object GoogleDrivePlusDiagnosticReport {
    fun format(snapshot: GoogleDrivePlusDiagnosticSnapshot): String = buildString {
        appendLine("つぐレジ＋ Google Drive診断ログ")
        appendLine("生成日時=${formatTime(snapshot.generatedAt)}")
        appendLine("アプリ=${snapshot.versionName} (${snapshot.versionCode})")
        appendLine("applicationId=${snapshot.packageName}")
        appendLine("署名SHA-1=${snapshot.sha1}")
        appendLine("署名SHA-256=${snapshot.sha256}")
        appendLine("端末=${snapshot.device}")
        appendLine("Android=${snapshot.androidVersion}")
        appendLine("Google Play開発者サービス=${snapshot.playServices}")
        appendLine("ネットワーク=${snapshot.network}")
        appendLine("アカウント状態=${snapshot.accountStatus}")
        appendLine("アカウント=${snapshot.maskedAccount}")
        appendLine("接続メッセージ=${GoogleDrivePlusDiagnosticSanitizer.detail(snapshot.accountMessage)}")
        appendLine("最終接続確認=${formatTime(snapshot.lastVerifiedAt)}")
        appendLine("差分同期実行中=${snapshot.syncStatus.running}")
        appendLine("自動差分同期=${snapshot.syncStatus.autoSyncOnLaunch}")
        appendLine("同期開始=${formatTime(snapshot.syncStatus.lastStartedAt)}")
        appendLine("同期完了=${formatTime(snapshot.syncStatus.lastCompletedAt)}")
        appendLine("同期結果=${GoogleDrivePlusDiagnosticSanitizer.detail(snapshot.syncStatus.lastMessage)}")
        appendLine("同期件数=確認:${snapshot.syncStatus.listedCount},取得:${snapshot.syncStatus.downloadedCount},未変更:${snapshot.syncStatus.unchangedCount},新規:${snapshot.syncStatus.importedCount},重複:${snapshot.syncStatus.duplicateCount},隔離:${snapshot.syncStatus.rejectedCount},エラー:${snapshot.syncStatus.errorCount}")
        appendLine("互換用フォルダ=${snapshot.compatibilityFolder}")
        appendLine("Drive同期管理ファイル=${snapshot.database.driveSyncFileCount}")
        appendLine("取込済み売上JSON=${snapshot.database.importedJournalCount}")
        appendLine("隔離JSON=${snapshot.database.rejectionCount}")
        appendLine("取込実行状態=${formatCounts(snapshot.database.importRunCounts)}")
        snapshot.database.error?.let {
            appendLine("DB診断エラー=${GoogleDrivePlusDiagnosticSanitizer.detail(it)}")
        }
        appendLine()
        appendLine("直近の隔離・失敗")
        if (snapshot.database.recentFailures.isEmpty()) appendLine("なし")
        snapshot.database.recentFailures.forEach {
            appendLine("- ${GoogleDrivePlusDiagnosticSanitizer.detail(it)}")
        }
        appendLine()
        appendLine("除外情報=アクセストークン、更新トークン、売上JSON本文、raw_preview、content URI全文")
    }

    fun formatTime(value: Long?): String {
        if (value == null || value <= 0L) return "未記録"
        return SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date(value))
    }

    private fun formatCounts(counts: Map<String, Int>): String =
        if (counts.isEmpty()) "なし" else counts.entries.joinToString(",") { "${it.key}:${it.value}" }
}

internal class GoogleDrivePlusDiagnosticRepository(private val context: Context) {
    private val appContext = context.applicationContext

    fun snapshot(): GoogleDrivePlusDiagnosticSnapshot {
        val account = GoogleDriveAccountStore(appContext).load()
        val sync = GoogleDriveDirectSyncStatusStore(appContext).load()
        val client = GoogleDrivePlusAndroidClientInfoReader.read(appContext)
        val folder = ImportFolderPreferences(appContext).registration()
        val playServicesCode = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(appContext)
        return GoogleDrivePlusDiagnosticSnapshot(
            generatedAt = System.currentTimeMillis(),
            packageName = appContext.packageName,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            sha1 = client.sha1,
            sha256 = client.sha256,
            device = listOf(Build.MANUFACTURER, Build.MODEL)
                .filter(String::isNotBlank)
                .joinToString(" "),
            androidVersion = "${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}",
            playServices = "${GoogleApiAvailability.getInstance().getErrorString(playServicesCode)} ($playServicesCode)",
            network = networkSummary(),
            accountStatus = account.status.name,
            maskedAccount = GoogleDrivePlusDiagnosticSanitizer.maskedEmail(account.email),
            accountMessage = account.message,
            lastVerifiedAt = account.lastVerifiedAt,
            syncStatus = sync,
            compatibilityFolder = folder?.displayName ?: "未登録",
            database = databaseState(),
        )
    }

    private fun networkSummary(): String {
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = manager.activeNetwork ?: return "未接続"
        val capabilities = manager.getNetworkCapabilities(active) ?: return "状態取得不可"
        val transports = buildList {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("モバイル")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("Ethernet")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
        }
        val internet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return "${transports.ifEmpty { listOf("その他") }.joinToString("+")} / internet=$internet / validated=$validated / metered=$metered"
    }

    private fun databaseState(): GoogleDrivePlusDiagnosticDatabaseState = runCatching {
        ManagementDatabase(appContext).use { helper ->
            val db = helper.readableDatabase
            GoogleDrivePlusDiagnosticDatabaseState(
                driveSyncFileCount = rowCount(db, "drive_sync_files"),
                importedJournalCount = rowCount(db, "imported_journal"),
                rejectionCount = rowCount(db, "import_rejections"),
                importRunCounts = statusCounts(db),
                recentFailures = recentFailures(db),
            )
        }
    }.getOrElse { error ->
        GoogleDrivePlusDiagnosticDatabaseState(
            error = error.message ?: error.javaClass.simpleName,
        )
    }

    private fun rowCount(db: SQLiteDatabase, table: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM $table", emptyArray()).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    private fun statusCounts(db: SQLiteDatabase): Map<String, Int> =
        db.rawQuery(
            "SELECT status, COUNT(*) FROM import_runs GROUP BY status ORDER BY status",
            emptyArray(),
        ).use { cursor ->
            linkedMapOf<String, Int>().apply {
                while (cursor.moveToNext()) put(cursor.getString(0), cursor.getInt(1))
            }
        }

    private fun recentFailures(db: SQLiteDatabase): List<String> =
        db.rawQuery(
            "SELECT source_name, rejection_code, message, created_at FROM import_rejections ORDER BY created_at DESC LIMIT 20",
            emptyArray(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        "${GoogleDrivePlusDiagnosticReport.formatTime(cursor.getLong(3))} / ${cursor.getString(0).orEmpty().take(120)} / ${cursor.getString(1).orEmpty()} / ${cursor.getString(2).orEmpty()}",
                    )
                }
            }
        }
}

class GoogleDriveDiagnosticsActivity : ComponentActivity() {
    private val snapshot = mutableStateOf<GoogleDrivePlusDiagnosticSnapshot?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refresh()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GoogleDrivePlusDiagnosticsScreen(
                        snapshot = snapshot.value,
                        onRefresh = ::refresh,
                        onCopy = ::copyReport,
                        onShare = ::shareReport,
                        onOpenSetupGuide = {
                            startActivity(Intent(this, GoogleDriveSetupGuideActivity::class.java))
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

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            snapshot.value = withContext(Dispatchers.IO) {
                GoogleDrivePlusDiagnosticRepository(applicationContext).snapshot()
            }
        }
    }

    private fun report(): String? = snapshot.value?.let(GoogleDrivePlusDiagnosticReport::format)

    private fun copyReport() {
        val report = report() ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("つぐレジ＋ Google Drive診断ログ", report))
    }

    private fun shareReport() {
        val report = report() ?: return
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "つぐレジ＋ Google Drive診断ログ")
                    putExtra(Intent.EXTRA_TEXT, report)
                },
                "診断ログを共有",
            ),
        )
    }
}

@Composable
private fun GoogleDrivePlusDiagnosticsScreen(
    snapshot: GoogleDrivePlusDiagnosticSnapshot?,
    onRefresh: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onOpenSetupGuide: () -> Unit,
    onOpenAccount: () -> Unit,
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
                        "つぐレジ＋ Google Drive診断・ログ",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("OAuth登録値、端末環境、同期結果、取込DBをまとめて確認します")
                }
                OutlinedButton(onClick = onClose) { Text("戻る") }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onRefresh, modifier = Modifier.weight(1f).height(48.dp)) { Text("更新") }
                OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f).height(48.dp)) { Text("コピー") }
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f).height(48.dp)) { Text("共有") }
            }
        }

        if (snapshot == null) {
            item { Text("診断情報を取得しています…") }
        } else {
            item {
                PlusDiagnosticCard("アプリ・署名") {
                    PlusDiagnosticLine("バージョン", "${snapshot.versionName} (${snapshot.versionCode})")
                    PlusDiagnosticLine("applicationId", snapshot.packageName)
                    PlusDiagnosticLine("署名SHA-1", snapshot.sha1)
                    PlusDiagnosticLine("署名SHA-256", snapshot.sha256)
                }
            }
            item {
                PlusDiagnosticCard("端末・通信") {
                    PlusDiagnosticLine("端末", snapshot.device)
                    PlusDiagnosticLine("Android", snapshot.androidVersion)
                    PlusDiagnosticLine("Google Play開発者サービス", snapshot.playServices)
                    PlusDiagnosticLine("ネットワーク", snapshot.network)
                }
            }
            item {
                PlusDiagnosticCard("Googleアカウント・差分同期") {
                    PlusDiagnosticLine("アカウント状態", snapshot.accountStatus)
                    PlusDiagnosticLine("アカウント", snapshot.maskedAccount)
                    PlusDiagnosticLine("最終接続確認", GoogleDrivePlusDiagnosticReport.formatTime(snapshot.lastVerifiedAt))
                    PlusDiagnosticLine("状態メッセージ", GoogleDrivePlusDiagnosticSanitizer.detail(snapshot.accountMessage))
                    PlusDiagnosticLine("同期結果", GoogleDrivePlusDiagnosticSanitizer.detail(snapshot.syncStatus.lastMessage))
                    PlusDiagnosticLine(
                        "件数",
                        "確認 ${snapshot.syncStatus.listedCount}／取得 ${snapshot.syncStatus.downloadedCount}／未変更 ${snapshot.syncStatus.unchangedCount}／新規 ${snapshot.syncStatus.importedCount}／重複 ${snapshot.syncStatus.duplicateCount}／隔離 ${snapshot.syncStatus.rejectedCount}／エラー ${snapshot.syncStatus.errorCount}",
                    )
                }
            }
            item {
                PlusDiagnosticCard("ローカル取込DB") {
                    PlusDiagnosticLine("Drive同期管理", "${snapshot.database.driveSyncFileCount}件")
                    PlusDiagnosticLine("取込済み売上JSON", "${snapshot.database.importedJournalCount}件")
                    PlusDiagnosticLine("隔離JSON", "${snapshot.database.rejectionCount}件")
                    PlusDiagnosticLine("互換用フォルダ", snapshot.compatibilityFolder)
                    snapshot.database.error?.let {
                        PlusDiagnosticLine("DB診断エラー", GoogleDrivePlusDiagnosticSanitizer.detail(it))
                    }
                }
            }
            item {
                PlusDiagnosticCard("直近の隔離・失敗") {
                    if (snapshot.database.recentFailures.isEmpty()) {
                        Text("なし")
                    } else {
                        snapshot.database.recentFailures.forEach {
                            Text("・${GoogleDrivePlusDiagnosticSanitizer.detail(it)}")
                        }
                    }
                }
            }
        }

        item {
            OutlinedButton(
                onClick = onOpenSetupGuide,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("初期設定ガイド") }
            Button(
                onClick = onOpenAccount,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Googleアカウント・同期設定") }
            Text("診断レポートにはアクセストークン、更新トークン、売上JSON本文、content URI全文を含めません。")
        }
    }
}

@Composable
private fun PlusDiagnosticCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(title, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun PlusDiagnosticLine(label: String, value: String) {
    Text("$label：$value")
}
'''
write(
    'management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveDiagnosticsActivity.kt',
    diagnostics,
)

test_source = r'''
package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V049GoogleDrivePlusSetupDiagnosticsTest {
    @Test
    fun plusExposesSetupGuideAndDiagnosticsFromDriveAccountScreen() {
        val root = File("src/main/java/jp/co/tenposinfo/register/plus")
        val account = File(root, "GoogleDriveAccountActivity.kt").readText()
        val setup = File(root, "GoogleDriveSetupGuideActivity.kt").readText()
        val diagnostics = File(root, "GoogleDriveDiagnosticsActivity.kt").readText()
        val folderScreen = File(root, "ManagementFolderSyncScreen.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File("../.github/workflows/build-apk.yml").readText()
        val docs = File("../docs/V0.49_TSUGUREGI_PLUS_DRIVE_SETUP_DIAGNOSTICS.md").readText()

        assertTrue(account.contains("GoogleDriveSetupGuideActivity::class.java"))
        assertTrue(account.contains("GoogleDriveDiagnosticsActivity::class.java"))
        assertTrue(account.contains("初期設定ガイド"))
        assertTrue(account.contains("診断・ログ"))
        assertTrue(folderScreen.contains("Google Drive設定・診断"))

        assertTrue(setup.contains("GET_SIGNING_CERTIFICATES"))
        assertTrue(setup.contains("applicationId（パッケージ名）"))
        assertTrue(setup.contains("署名SHA-1"))
        assertTrue(setup.contains("別々のAndroid OAuthクライアント"))
        assertTrue(setup.contains("Google Cloud Consoleを開く"))

        assertTrue(diagnostics.contains("GoogleApiAvailability"))
        assertTrue(diagnostics.contains("ConnectivityManager"))
        assertTrue(diagnostics.contains("ManagementDatabase"))
        assertTrue(diagnostics.contains("drive_sync_files"))
        assertTrue(diagnostics.contains("import_rejections"))
        assertTrue(diagnostics.contains("Intent.ACTION_SEND"))
        assertTrue(diagnostics.contains("REDACTED_TOKEN"))
        assertFalse(diagnostics.contains("putString(\"access_token\""))
        assertFalse(diagnostics.contains("putString(\"refresh_token\""))

        assertTrue(manifest.contains("android:name=\".GoogleDriveSetupGuideActivity\""))
        assertTrue(manifest.contains("android:name=\".GoogleDriveDiagnosticsActivity\""))
        assertTrue(manifest.contains("android:screenOrientation=\"sensorPortrait\""))
        assertTrue(build.contains("versionCode = 8"))
        assertTrue(build.contains("versionName = \"0.8.0-dev.1\""))
        assertTrue(workflow.contains("V049GoogleDrivePlusSetupDiagnosticsTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_PLUS_v0.8.0_dev1_drive_setup_diagnostics_debug.apk"))
        assertTrue(workflow.contains("TSUGUREGI-v0.49.0-dev1-plus-drive-setup-diagnostics-apks"))
        assertTrue(docs.contains("つぐレジ＋用のAndroid OAuthクライアント"))
        assertFalse(File("../tools/v049_apply.py").exists())
        assertFalse(File("../.github/workflows/v049-apply-temp.yml").exists())
        assertFalse(File("../tools/build-apk-v049.generated.yml").exists())
    }
}
'''
write(
    'management-app/src/test/java/jp/co/tenposinfo/register/plus/V049GoogleDrivePlusSetupDiagnosticsTest.kt',
    test_source,
)

doc = r'''
# v0.49 つぐレジ＋ Google Drive初期設定・診断

## 目的

つぐレジ側にはGoogle Drive初期設定ガイドと診断画面がある一方、つぐレジ＋側ではアカウント連携画面しかなく、Google Cloudへ登録するapplicationId・署名SHA-1や同期失敗の切り分け情報を画面から確認できなかった。

v0.49では、つぐレジ＋にも初期設定ガイドと診断・ログ画面を追加する。

## 画面経路

`つぐレジ＋トップ → Google Drive設定・診断 → 初期設定ガイド／診断・ログ`

Google Driveアカウント画面からも両画面を開ける。

## 初期設定ガイド

APKから次を取得して表示・コピーできる。

- applicationId
- 署名SHA-1
- 署名SHA-256
- 要求スコープ `https://www.googleapis.com/auth/drive.file`

つぐレジとつぐレジ＋はapplicationIdが異なるため、同じGoogle Cloudプロジェクト内でも、つぐレジ＋用のAndroid OAuthクライアントを別に作成する。

## 診断・ログ

次を表示し、テキストとしてコピー・共有できる。

- アプリバージョン、applicationId、署名SHA-1／SHA-256
- 端末、Android、Google Play開発者サービス
- ネットワーク種別、INTERNET、VALIDATED、metered
- Googleアカウント状態とマスク済みメールアドレス
- 差分同期の最終状態と件数
- `drive_sync_files`、`imported_journal`、`import_rejections`の件数
- 直近20件の隔離・失敗
- SAF互換フォルダの表示名

## 診断レポートへ含めない情報

- アクセストークン
- 更新トークン
- Authorizationヘッダー
- 売上JSON本文
- `raw_preview`
- content URI全文
- Googleアカウントの完全なメールアドレス

## 実機確認が必要な事項

- 開発版／本番版のOAuthクライアント登録値
- Googleアカウント選択、OAuth同意、Drive API `about.get`
- つぐレジが作成したファイルを、別Android OAuthクライアントのつぐレジ＋が`drive.file`で列挙できるか
- 差分同期、全件再取込、定期同期
- 実機診断レポートに機密情報が含まれないこと
'''
write('docs/V0.49_TSUGUREGI_PLUS_DRIVE_SETUP_DIAGNOSTICS.md', doc)

release_notes = r'''
# v0.49 リリースノート

## バージョン

- つぐレジ: `0.49.0-dev.1` / versionCode 79
- つぐレジ＋: `0.8.0-dev.1` / versionCode 8
- つぐレジ CD: `0.14.0-dev.1` / versionCode 7

## 追加

- つぐレジ＋ Google Drive初期設定ガイド
- つぐレジ＋ applicationId・署名SHA-1／SHA-256表示とコピー
- つぐレジ＋ Google Drive診断・ログ
- 診断レポートのコピー・共有
- 同期DB件数、直近隔離・失敗の表示
- トップ画面とGoogle Driveアカウント画面からの正式導線

## 維持

- Drive API差分同期
- `drive.file`限定スコープ
- アクセストークン／更新トークン非保存
- SQLiteローカル売上を原本とする設計
- SAFフォルダ方式の互換運用
- 共有ドライブ非対応

## 実機未確認

Google OAuth、Drive API同期、別Android OAuthクライアント間の`drive.file`可視性、実機診断ログの内容は実機確認が必要。
'''
write('docs/V0.49_RELEASE_NOTES.md', release_notes)

# Update cumulative tests that intentionally follow the latest artifact/version.
for path in Path('app/src/test').rglob('*.kt'):
    text = path.read_text(encoding='utf-8')
    text = re.sub(
        r'TSUGUREGI_v0\.\d+\.0_dev1_[A-Za-z0-9_]+_debug\.apk',
        'TSUGUREGI_v0.49.0_dev1_plus_drive_setup_diagnostics_debug.apk',
        text,
    )
    text = re.sub(
        r'TSUGUREGI-v0\.\d+\.0-dev1-[a-z0-9-]+-apks',
        'TSUGUREGI-v0.49.0-dev1-plus-drive-setup-diagnostics-apks',
        text,
    )
    text = text.replace('build.contains("versionCode = 78")', 'build.contains("versionCode = 79")')
    text = text.replace(
        'build.contains("versionName = \\\"0.48.0-dev.1\\\"")',
        'build.contains("versionName = \\\"0.49.0-dev.1\\\"")',
    )
    text = text.replace('managementBuild.contains("versionCode = 7")', 'managementBuild.contains("versionCode = 8")')
    text = text.replace(
        'managementBuild.contains("versionName = \\\"0.7.0-dev.1\\\"")',
        'managementBuild.contains("versionName = \\\"0.8.0-dev.1\\\"")',
    )
    path.write_text(text, encoding='utf-8')

for path in Path('management-app/src/test').rglob('*.kt'):
    text = path.read_text(encoding='utf-8')
    text = text.replace('build.contains("versionCode = 7")', 'build.contains("versionCode = 8")')
    text = text.replace(
        'build.contains("versionName = \\\"0.7.0-dev.1\\\"")',
        'build.contains("versionName = \\\"0.8.0-dev.1\\\"")',
    )
    text = re.sub(
        r'TSUGUREGI_PLUS_v0\.\d+\.0_dev1_[A-Za-z0-9_]+_debug\.apk',
        'TSUGUREGI_PLUS_v0.8.0_dev1_drive_setup_diagnostics_debug.apk',
        text,
    )
    text = re.sub(
        r'TSUGUREGI-v0\.\d+\.0-dev1-[a-z0-9-]+-apks',
        'TSUGUREGI-v0.49.0-dev1-plus-drive-setup-diagnostics-apks',
        text,
    )
    path.write_text(text, encoding='utf-8')

workflow_path = Path('.github/workflows/build-apk.yml')
workflow = workflow_path.read_text(encoding='utf-8')
workflow = workflow.replace('Verify cumulative v0.14-v0.48 sources', 'Verify cumulative v0.14-v0.49 sources')
workflow = workflow.replace("grep -q 'versionCode = 78' app/build.gradle.kts", "grep -q 'versionCode = 79' app/build.gradle.kts")
workflow = workflow.replace("grep -q 'versionName = \"0.48.0-dev.1\"' app/build.gradle.kts", "grep -q 'versionName = \"0.49.0-dev.1\"' app/build.gradle.kts")
workflow = workflow.replace(
    "grep -q 'versionCode = 7' management-app/build.gradle.kts\n          grep -q 'versionName = \"0.7.0-dev.1\"' management-app/build.gradle.kts",
    "grep -q 'versionCode = 8' management-app/build.gradle.kts\n          grep -q 'versionName = \"0.8.0-dev.1\"' management-app/build.gradle.kts",
)
workflow = workflow.replace(
    '            management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveAccountActivity.kt \\\n',
    '            management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveAccountActivity.kt \\\n            management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveSetupGuideActivity.kt \\\n            management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveDiagnosticsActivity.kt \\\n',
)
workflow = workflow.replace(
    '            management-app/src/test/java/jp/co/tenposinfo/register/plus/V045GoogleDriveDirectSyncTest.kt \\\n',
    '            management-app/src/test/java/jp/co/tenposinfo/register/plus/V045GoogleDriveDirectSyncTest.kt \\\n            management-app/src/test/java/jp/co/tenposinfo/register/plus/V049GoogleDrivePlusSetupDiagnosticsTest.kt \\\n',
)
workflow = workflow.replace(
    '            docs/V0.48_GOOGLE_DRIVE_SETTINGS_ENTRY.md \\\n',
    '            docs/V0.49_TSUGUREGI_PLUS_DRIVE_SETUP_DIAGNOSTICS.md \\\n            docs/V0.49_RELEASE_NOTES.md \\\n            docs/V0.48_GOOGLE_DRIVE_SETTINGS_ENTRY.md \\\n',
)
workflow = workflow.replace(
    "          plus_drive = (plus / 'GoogleDriveDirectSync.kt').read_text()\n",
    "          plus_drive = (plus / 'GoogleDriveDirectSync.kt').read_text()\n          plus_setup = (plus / 'GoogleDriveSetupGuideActivity.kt').read_text()\n          plus_diagnostics = (plus / 'GoogleDriveDiagnosticsActivity.kt').read_text()\n",
)
workflow = workflow.replace(
    "          assert 'fun importDocuments' in plus_import\n",
    "          assert 'fun importDocuments' in plus_import\n\n          for token in ('GET_SIGNING_CERTIFICATES', 'applicationId（パッケージ名）', '署名SHA-1', '別々のAndroid OAuthクライアント'):\n              assert token in plus_setup, token\n          for token in ('GoogleApiAvailability', 'ConnectivityManager', 'ManagementDatabase', 'drive_sync_files', 'import_rejections', 'Intent.ACTION_SEND', 'REDACTED_TOKEN'):\n              assert token in plus_diagnostics, token\n          assert 'putString(\"access_token\"' not in plus_diagnostics\n          assert 'putString(\"refresh_token\"' not in plus_diagnostics\n",
)
workflow = workflow.replace(
    "          for version in ('v048',",
    "          for version in ('v049', 'v048',",
)
workflow = workflow.replace(
    "          assert not Path('tools/build-apk-v048.generated.yml').exists()\n",
    "          assert not Path('tools/build-apk-v048.generated.yml').exists()\n          assert not Path('.github/workflows/v049-apply-temp.yml').exists()\n          assert not Path('tools/v049_apply.py').exists()\n          assert not Path('tools/build-apk-v049.generated.yml').exists()\n",
)
workflow = workflow.replace(
    'TSUGUREGI_v0.48.0_dev1_drive_settings_entry_debug.apk',
    'TSUGUREGI_v0.49.0_dev1_plus_drive_setup_diagnostics_debug.apk',
)
workflow = workflow.replace(
    'TSUGUREGI_PLUS_v0.7.0_dev1_drive_api_sync_debug.apk',
    'TSUGUREGI_PLUS_v0.8.0_dev1_drive_setup_diagnostics_debug.apk',
)
workflow = workflow.replace(
    'TSUGUREGI-v0.48.0-dev1-drive-settings-entry-apks',
    'TSUGUREGI-v0.49.0-dev1-plus-drive-setup-diagnostics-apks',
)
workflow = workflow.replace('REGISTER_VERSION_NAME=0.48.0-dev.1', 'REGISTER_VERSION_NAME=0.49.0-dev.1')
workflow = workflow.replace('REGISTER_VERSION_CODE=78', 'REGISTER_VERSION_CODE=79')
workflow = workflow.replace('MANAGEMENT_APP_VERSION_NAME=0.7.0-dev.1', 'MANAGEMENT_APP_VERSION_NAME=0.8.0-dev.1')
workflow = workflow.replace('MANAGEMENT_APP_VERSION_CODE=7', 'MANAGEMENT_APP_VERSION_CODE=8')
workflow = workflow.replace(
    '          GOOGLE_DRIVE_SETTINGS_ENTRY=true\n',
    '          GOOGLE_DRIVE_SETTINGS_ENTRY=true\n          PLUS_GOOGLE_DRIVE_SETUP_GUIDE=true\n          PLUS_GOOGLE_DRIVE_DIAGNOSTICS=true\n          PLUS_GOOGLE_DRIVE_DIAGNOSTIC_TOKENS_INCLUDED=false\n          PLUS_GOOGLE_DRIVE_DIAGNOSTIC_SALES_JSON_INCLUDED=false\n',
)
write('tools/build-apk-v049.generated.yml', workflow)

print('v0.49 patch applied')
