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
