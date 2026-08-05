package jp.co.tenposinfo.register

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
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class GoogleDriveDiagnosticEvent(
    val timestamp: Long,
    val stage: String,
    val result: String,
    val detail: String,
)

internal object GoogleDriveDiagnosticSanitizer {
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
        val local = email.substring(0, at)
        val domain = email.substring(at + 1)
        return "${local.take(1)}***@$domain"
    }
}

internal class GoogleDriveDiagnosticLogStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "tsuguregi_google_drive_diagnostic_log",
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun append(stage: String, result: String, detail: String?) {
        val events = loadInternal().toMutableList()
        events += GoogleDriveDiagnosticEvent(
            timestamp = System.currentTimeMillis(),
            stage = GoogleDriveDiagnosticSanitizer.detail(stage).take(80),
            result = GoogleDriveDiagnosticSanitizer.detail(result).take(80),
            detail = GoogleDriveDiagnosticSanitizer.detail(detail),
        )
        val payload = JSONArray()
        events.takeLast(MAX_EVENTS).forEach { event ->
            payload.put(
                JSONObject()
                    .put("timestamp", event.timestamp)
                    .put("stage", event.stage)
                    .put("result", event.result)
                    .put("detail", event.detail),
            )
        }
        preferences.edit().putString("events", payload.toString()).apply()
    }

    @Synchronized
    fun load(): List<GoogleDriveDiagnosticEvent> = loadInternal()

    @Synchronized
    fun clear() {
        preferences.edit().remove("events").apply()
    }

    private fun loadInternal(): List<GoogleDriveDiagnosticEvent> {
        val raw = preferences.getString("events", null) ?: return emptyList()
        return runCatching {
            val source = JSONArray(raw)
            buildList {
                for (index in 0 until source.length()) {
                    val item = source.optJSONObject(index) ?: continue
                    add(
                        GoogleDriveDiagnosticEvent(
                            timestamp = item.optLong("timestamp"),
                            stage = GoogleDriveDiagnosticSanitizer.detail(item.optString("stage")),
                            result = GoogleDriveDiagnosticSanitizer.detail(item.optString("result")),
                            detail = GoogleDriveDiagnosticSanitizer.detail(item.optString("detail")),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        const val MAX_EVENTS = 100
    }
}

internal data class GoogleDriveDiagnosticDatabaseState(
    val outboxCounts: Map<String, Int> = emptyMap(),
    val directUploadCounts: Map<String, Int> = emptyMap(),
    val recentFailures: List<String> = emptyList(),
    val error: String? = null,
)

internal data class GoogleDriveDiagnosticSnapshot(
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
    val uploadStatus: GoogleDriveDirectUploadStatus,
    val compatibilityFolder: String,
    val database: GoogleDriveDiagnosticDatabaseState,
    val events: List<GoogleDriveDiagnosticEvent>,
)

internal object GoogleDriveDiagnosticReport {
    fun format(snapshot: GoogleDriveDiagnosticSnapshot): String = buildString {
        appendLine("つぐレジ Google Drive診断ログ")
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
        appendLine("接続メッセージ=${GoogleDriveDiagnosticSanitizer.detail(snapshot.accountMessage)}")
        appendLine("最終接続確認=${formatTime(snapshot.lastVerifiedAt)}")
        appendLine("直接送信実行中=${snapshot.uploadStatus.running}")
        appendLine("直接送信結果=${GoogleDriveDiagnosticSanitizer.detail(snapshot.uploadStatus.lastMessage)}")
        appendLine("直接送信件数=送信:${snapshot.uploadStatus.uploadedCount},既存:${snapshot.uploadStatus.duplicateCount},再試行:${snapshot.uploadStatus.retryCount},永久失敗:${snapshot.uploadStatus.permanentFailureCount}")
        appendLine("互換用フォルダ=${snapshot.compatibilityFolder}")
        appendLine("Outbox=${formatCounts(snapshot.database.outboxCounts)}")
        appendLine("Drive API送信管理=${formatCounts(snapshot.database.directUploadCounts)}")
        snapshot.database.error?.let { appendLine("DB診断エラー=${GoogleDriveDiagnosticSanitizer.detail(it)}") }
        appendLine()
        appendLine("直近失敗")
        if (snapshot.database.recentFailures.isEmpty()) appendLine("なし")
        snapshot.database.recentFailures.forEach { appendLine("- ${GoogleDriveDiagnosticSanitizer.detail(it)}") }
        appendLine()
        appendLine("診断イベント（新しい順）")
        if (snapshot.events.isEmpty()) appendLine("なし")
        snapshot.events.sortedByDescending { it.timestamp }.forEach { event ->
            appendLine("- ${formatTime(event.timestamp)} [${event.stage}] ${event.result}: ${event.detail}")
        }
        appendLine()
        appendLine("除外情報=アクセストークン、更新トークン、売上JSON本文、保存先content URI")
    }

    fun formatTime(value: Long?): String {
        if (value == null || value <= 0L) return "未記録"
        return SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date(value))
    }

    private fun formatCounts(counts: Map<String, Int>): String =
        if (counts.isEmpty()) "なし" else counts.entries.joinToString(",") { "${it.key}:${it.value}" }
}

internal class GoogleDriveDiagnosticRepository(private val context: Context) {
    private val appContext = context.applicationContext

    fun snapshot(): GoogleDriveDiagnosticSnapshot {
        val account = GoogleDriveAccountStore(appContext).load()
        val upload = GoogleDriveDirectUploadStatusStore(appContext).load()
        val client = GoogleDriveAndroidClientInfoReader.read(appContext)
        val compatibility = OutboxDeliverySettingsStore(appContext).load()
        val playServicesCode = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(appContext)
        return GoogleDriveDiagnosticSnapshot(
            generatedAt = System.currentTimeMillis(),
            packageName = appContext.packageName,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            sha1 = client.sha1,
            sha256 = client.sha256,
            device = listOf(Build.MANUFACTURER, Build.MODEL).filter(String::isNotBlank).joinToString(" "),
            androidVersion = "${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}",
            playServices = "${ConnectionResult.getStatusString(playServicesCode)} ($playServicesCode)",
            network = networkSummary(),
            accountStatus = account.status.name,
            maskedAccount = GoogleDriveDiagnosticSanitizer.maskedEmail(account.email),
            accountMessage = account.message,
            lastVerifiedAt = account.lastVerifiedAt,
            uploadStatus = upload,
            compatibilityFolder = if (compatibility.enabled) {
                "有効 / ${compatibility.destinationLabel ?: "名称未設定"}"
            } else {
                "無効"
            },
            database = databaseState(),
            events = GoogleDriveDiagnosticLogStore(appContext).load(),
        )
    }

    private fun networkSummary(): String {
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return "未接続"
        val capabilities = manager.getNetworkCapabilities(network) ?: return "状態取得不可"
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

    private fun databaseState(): GoogleDriveDiagnosticDatabaseState = runCatching {
        RegisterDatabase(appContext).use { helper ->
            val db = helper.readableDatabase
            val outbox = if (tableExists(db, "sync_outbox")) statusCounts(db, "sync_outbox") else emptyMap()
            val direct = if (tableExists(db, GoogleDriveDirectUploadCoordinator.TABLE)) {
                statusCounts(db, GoogleDriveDirectUploadCoordinator.TABLE)
            } else {
                emptyMap()
            }
            val failures = if (tableExists(db, GoogleDriveDirectUploadCoordinator.TABLE)) {
                db.rawQuery(
                    """
                    SELECT event_id, status, attempt_count, last_error, updated_at
                    FROM ${GoogleDriveDirectUploadCoordinator.TABLE}
                    WHERE last_error IS NOT NULL AND last_error <> ''
                    ORDER BY updated_at DESC
                    LIMIT 20
                    """.trimIndent(),
                    emptyArray(),
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                "event=${cursor.getString(0).take(80)} / status=${cursor.getString(1)} / attempts=${cursor.getInt(2)} / ${cursor.getString(3).orEmpty()} / ${GoogleDriveDiagnosticReport.formatTime(cursor.getLong(4))}",
                            )
                        }
                    }
                }
            } else {
                emptyList()
            }
            GoogleDriveDiagnosticDatabaseState(
                outboxCounts = outbox,
                directUploadCounts = direct,
                recentFailures = failures,
            )
        }
    }.getOrElse { error ->
        GoogleDriveDiagnosticDatabaseState(error = error.message ?: error.javaClass.simpleName)
    }

    private fun statusCounts(db: SQLiteDatabase, table: String): Map<String, Int> =
        db.rawQuery("SELECT status, COUNT(*) FROM $table GROUP BY status ORDER BY status", emptyArray())
            .use { cursor ->
                linkedMapOf<String, Int>().apply {
                    while (cursor.moveToNext()) put(cursor.getString(0), cursor.getInt(1))
                }
            }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(table),
        ).use { it.moveToFirst() }
}

class GoogleDriveDiagnosticsActivity : ComponentActivity() {
    private val snapshot = mutableStateOf<GoogleDriveDiagnosticSnapshot?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureRegisterSystemBars(window)
        refresh()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GoogleDriveDiagnosticsScreen(
                        snapshot = snapshot.value,
                        onRefresh = ::refresh,
                        onCopy = ::copyReport,
                        onShare = ::shareReport,
                        onClear = ::clearEvents,
                        onOpenAccount = {
                            startActivity(Intent(this, GoogleDriveAccountActivity::class.java))
                        },
                        onUpload = {
                            GoogleDriveDiagnosticLogStore(this).append("MANUAL_UPLOAD", "REQUESTED", "診断画面から今すぐアップロード")
                            JournalOutboxStore(applicationContext).use { it.stagePending(500) }
                            GoogleDriveDirectUploadScheduler.enqueueNow(applicationContext)
                            refresh()
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
                GoogleDriveDiagnosticRepository(applicationContext).snapshot()
            }
        }
    }

    private fun report(): String? = snapshot.value?.let(GoogleDriveDiagnosticReport::format)

    private fun copyReport() {
        val report = report() ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("つぐレジ Google Drive診断ログ", report))
    }

    private fun shareReport() {
        val report = report() ?: return
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "つぐレジ Google Drive診断ログ")
                    putExtra(Intent.EXTRA_TEXT, report)
                },
                "診断ログを共有",
            ),
        )
    }

    private fun clearEvents() {
        GoogleDriveDiagnosticLogStore(this).clear()
        refresh()
    }
}

@Composable
private fun GoogleDriveDiagnosticsScreen(
    snapshot: GoogleDriveDiagnosticSnapshot?,
    onRefresh: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
    onOpenAccount: () -> Unit,
    onUpload: () -> Unit,
    onClose: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Google Drive診断・ログ", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("認証、通信、Outbox、Drive API送信状態をまとめて確認します")
                }
                OutlinedButton(onClick = onClose) { Text("戻る") }
            }
        }

        if (snapshot == null) {
            item { Card(Modifier.fillMaxWidth()) { Text("診断情報を取得しています", Modifier.padding(16.dp)) } }
        } else {
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DiagnosticCard(
                        title = "アプリ・端末",
                        lines = listOf(
                            "${snapshot.versionName} (${snapshot.versionCode})",
                            snapshot.packageName,
                            "SHA-1 ${snapshot.sha1}",
                            "${snapshot.device} / Android ${snapshot.androidVersion}",
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    DiagnosticCard(
                        title = "Google・通信",
                        lines = listOf(
                            "Play開発者サービス ${snapshot.playServices}",
                            snapshot.network,
                            "状態 ${snapshot.accountStatus}",
                            "アカウント ${snapshot.maskedAccount}",
                            "最終確認 ${GoogleDriveDiagnosticReport.formatTime(snapshot.lastVerifiedAt)}",
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DiagnosticCard(
                        title = "直接アップロード",
                        lines = listOf(
                            snapshot.uploadStatus.lastMessage,
                            "送信 ${snapshot.uploadStatus.uploadedCount} / 既存 ${snapshot.uploadStatus.duplicateCount}",
                            "再試行 ${snapshot.uploadStatus.retryCount} / 永久失敗 ${snapshot.uploadStatus.permanentFailureCount}",
                            "Outbox ${snapshot.database.outboxCounts}",
                            "Drive管理 ${snapshot.database.directUploadCounts}",
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    DiagnosticCard(
                        title = "安全なログ出力",
                        lines = listOf(
                            "診断イベント ${snapshot.events.size}件",
                            "直近失敗 ${snapshot.database.recentFailures.size}件",
                            "互換用フォルダ ${snapshot.compatibilityFolder}",
                            "トークン、売上本文、保存先URIは出力しません",
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onRefresh, modifier = Modifier.weight(1f).height(50.dp)) { Text("再取得") }
                    OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f).height(50.dp)) { Text("コピー") }
                    OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f).height(50.dp)) { Text("共有") }
                    OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f).height(50.dp)) { Text("イベント消去") }
                    OutlinedButton(onClick = onOpenAccount, modifier = Modifier.weight(1f).height(50.dp)) { Text("アカウント設定") }
                    Button(onClick = onUpload, modifier = Modifier.weight(1f).height(50.dp)) { Text("送信テスト") }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("直近の失敗", fontWeight = FontWeight.Bold)
                        if (snapshot.database.recentFailures.isEmpty()) Text("記録なし")
                        snapshot.database.recentFailures.take(10).forEach { Text("・${GoogleDriveDiagnosticSanitizer.detail(it)}") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticCard(title: String, lines: List<String>, modifier: Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            lines.forEach { Text(it) }
        }
    }
}
