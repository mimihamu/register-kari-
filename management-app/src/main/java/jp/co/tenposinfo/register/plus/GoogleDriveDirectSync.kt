package jp.co.tenposinfo.register.plus

import android.accounts.Account
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.tasks.Tasks
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class GoogleDriveSyncFailureCategory(val retryable: Boolean) {
    AUTHORIZATION_REQUIRED(false),
    API_DISABLED(false),
    PERMISSION_DENIED(false),
    RATE_LIMITED(true),
    NETWORK(true),
    SERVER(true),
    INVALID_DATA(false),
    UNKNOWN(true),
}

class GoogleDriveSyncApiException(
    val responseCode: Int,
    val responseBody: String,
) : IOException("Google Drive API HTTP $responseCode")

class GoogleDriveSyncAuthorizationRequiredException(message: String) : IllegalStateException(message)

object GoogleDriveSyncErrorPolicy {
    fun classify(error: Throwable): GoogleDriveSyncFailureCategory {
        if (error is GoogleDriveSyncAuthorizationRequiredException) {
            return GoogleDriveSyncFailureCategory.AUTHORIZATION_REQUIRED
        }
        if (error is GoogleDriveSyncApiException) {
            val body = error.responseBody.lowercase(Locale.ROOT)
            return when {
                error.responseCode == 401 -> GoogleDriveSyncFailureCategory.AUTHORIZATION_REQUIRED
                body.contains("service_disabled") || body.contains("accessnotconfigured") ->
                    GoogleDriveSyncFailureCategory.API_DISABLED
                error.responseCode == 429 || body.contains("ratelimitexceeded") ||
                    body.contains("userratelimitexceeded") -> GoogleDriveSyncFailureCategory.RATE_LIMITED
                error.responseCode == 403 -> GoogleDriveSyncFailureCategory.PERMISSION_DENIED
                error.responseCode >= 500 -> GoogleDriveSyncFailureCategory.SERVER
                error.responseCode in 400..499 -> GoogleDriveSyncFailureCategory.INVALID_DATA
                else -> GoogleDriveSyncFailureCategory.UNKNOWN
            }
        }
        return if (error is IOException) {
            GoogleDriveSyncFailureCategory.NETWORK
        } else {
            GoogleDriveSyncFailureCategory.UNKNOWN
        }
    }

    fun message(category: GoogleDriveSyncFailureCategory): String = when (category) {
        GoogleDriveSyncFailureCategory.AUTHORIZATION_REQUIRED -> "Googleアカウントの再認可が必要です"
        GoogleDriveSyncFailureCategory.API_DISABLED -> "Google CloudでGoogle Drive APIを有効にしてください"
        GoogleDriveSyncFailureCategory.PERMISSION_DENIED -> "Google Driveの読取権限が不足しています"
        GoogleDriveSyncFailureCategory.RATE_LIMITED -> "Google Drive APIの呼出上限に達したため再試行します"
        GoogleDriveSyncFailureCategory.NETWORK -> "ネットワーク通信に失敗しました"
        GoogleDriveSyncFailureCategory.SERVER -> "Google Drive側の一時エラーです"
        GoogleDriveSyncFailureCategory.INVALID_DATA -> "Google Driveの応答またはJSONが不正です"
        GoogleDriveSyncFailureCategory.UNKNOWN -> "Google Drive同期で不明なエラーが発生しました"
    }
}

object GoogleDriveSyncAccessTokenProvider {
    fun acquire(context: Context): String {
        val email = GoogleDriveAccountStore(context).load().email
            ?: throw GoogleDriveSyncAuthorizationRequiredException("Googleアカウントが未登録です")
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(GoogleDriveAccountPolicy.requestedScopes)
            .setAccount(Account(email, "com.google"))
            .build()
        val result = try {
            Tasks.await(
                Identity.getAuthorizationClient(context).authorize(request),
                30,
                TimeUnit.SECONDS,
            )
        } catch (error: Throwable) {
            throw GoogleDriveSyncAuthorizationRequiredException(
                error.cause?.message ?: error.message ?: "Google認可情報を取得できません",
            )
        }
        if (result.hasResolution()) {
            throw GoogleDriveSyncAuthorizationRequiredException("Googleアカウント画面で再接続してください")
        }
        return result.accessToken?.takeIf(String::isNotBlank)
            ?: throw GoogleDriveSyncAuthorizationRequiredException("アクセストークンを取得できません")
    }
}

data class GoogleDriveSyncRemoteFile(
    val id: String,
    val name: String,
    val modifiedTime: String,
    val size: Long?,
    val appProperties: Map<String, String>,
)

class GoogleDriveSyncRestClient(private val accessToken: String) {
    fun listJournalFiles(maxFiles: Int = MAX_FILES): List<GoogleDriveSyncRemoteFile> {
        val result = mutableListOf<GoogleDriveSyncRemoteFile>()
        var pageToken: String? = null
        do {
            val query = "mimeType='application/json' and trashed=false" +
                propertyQuery("app", APP) + propertyQuery("role", ROLE)
            val fields = "nextPageToken,files(id,name,modifiedTime,size,appProperties)"
            val url = buildString {
                append(DRIVE_FILES_URL)
                append("?spaces=drive")
                append("&supportsAllDrives=false")
                append("&pageSize=1000")
                append("&orderBy=modifiedTime")
                append("&fields=").append(encode(fields))
                append("&q=").append(encode(query))
                pageToken?.let { append("&pageToken=").append(encode(it)) }
            }
            val root = JSONObject(execute("GET", url))
            val files = root.optJSONArray("files") ?: JSONArray()
            for (index in 0 until files.length()) {
                if (result.size >= maxFiles) break
                val item = files.getJSONObject(index)
                val properties = linkedMapOf<String, String>()
                item.optJSONObject("appProperties")?.let { source ->
                    source.keys().forEach { key -> properties[key] = source.optString(key) }
                }
                result += GoogleDriveSyncRemoteFile(
                    id = item.getString("id"),
                    name = item.optString("name").ifBlank { "${item.getString("id")}.json" },
                    modifiedTime = item.optString("modifiedTime"),
                    size = item.optString("size").toLongOrNull(),
                    appProperties = properties,
                )
            }
            pageToken = root.optString("nextPageToken").takeIf(String::isNotBlank)
        } while (pageToken != null && result.size < maxFiles)
        return result
    }

    fun download(fileId: String): ByteArray = executeBytes(
        method = "GET",
        url = "$DRIVE_FILES_URL/${encodePath(fileId)}?alt=media&supportsAllDrives=false",
    )

    private fun execute(method: String, url: String): String =
        executeBytes(method, url).toString(Charsets.UTF_8)

    private fun executeBytes(method: String, url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 20_000
            connection.readTimeout = 45_000
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            val bytes = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes() }
                ?: ByteArray(0)
            if (code !in 200..299) {
                throw GoogleDriveSyncApiException(code, bytes.toString(Charsets.UTF_8))
            }
            bytes
        } finally {
            connection.disconnect()
        }
    }

    private fun propertyQuery(key: String, value: String): String =
        " and appProperties has { key='${quoted(key)}' and value='${quoted(value)}' }"

    companion object {
        const val DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files"
        const val APP = "tsuguregi"
        const val ROLE = "sales-journal"
        const val MAX_FILES = 5_000

        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
        fun encodePath(value: String): String = value.replace("/", "%2F")
        fun quoted(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")
    }
}

data class GoogleDriveDirectSyncStatus(
    val running: Boolean = false,
    val autoSyncOnLaunch: Boolean = true,
    val lastStartedAt: Long? = null,
    val lastCompletedAt: Long? = null,
    val listedCount: Int = 0,
    val downloadedCount: Int = 0,
    val unchangedCount: Int = 0,
    val importedCount: Int = 0,
    val duplicateCount: Int = 0,
    val rejectedCount: Int = 0,
    val errorCount: Int = 0,
    val lastMessage: String = "Drive API同期はまだ実行されていません",
)

class GoogleDriveDirectSyncStatusStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "tsuguregi_plus_drive_api_sync_status",
        Context.MODE_PRIVATE,
    )

    fun load(): GoogleDriveDirectSyncStatus = GoogleDriveDirectSyncStatus(
        running = preferences.getBoolean("running", false),
        autoSyncOnLaunch = preferences.getBoolean("auto_sync", true),
        lastStartedAt = preferences.getLong("started_at", 0L).takeIf { it > 0L },
        lastCompletedAt = preferences.getLong("completed_at", 0L).takeIf { it > 0L },
        listedCount = preferences.getInt("listed", 0),
        downloadedCount = preferences.getInt("downloaded", 0),
        unchangedCount = preferences.getInt("unchanged", 0),
        importedCount = preferences.getInt("imported", 0),
        duplicateCount = preferences.getInt("duplicates", 0),
        rejectedCount = preferences.getInt("rejected", 0),
        errorCount = preferences.getInt("errors", 0),
        lastMessage = preferences.getString("message", null)
            ?: "Drive API同期はまだ実行されていません",
    )

    fun setAutoSyncOnLaunch(enabled: Boolean) {
        preferences.edit().putBoolean("auto_sync", enabled).apply()
    }

    fun running() {
        preferences.edit()
            .putBoolean("running", true)
            .putLong("started_at", System.currentTimeMillis())
            .putString("message", "Google Driveの売上JSONを確認しています")
            .apply()
    }

    fun complete(result: GoogleDriveDirectSyncResult) {
        preferences.edit()
            .putBoolean("running", false)
            .putLong("completed_at", System.currentTimeMillis())
            .putInt("listed", result.listedCount)
            .putInt("downloaded", result.downloadedCount)
            .putInt("unchanged", result.unchangedCount)
            .putInt("imported", result.importedCount)
            .putInt("duplicates", result.duplicateCount)
            .putInt("rejected", result.rejectedCount)
            .putInt("errors", result.errorCount)
            .putString(
                "message",
                "確認${result.listedCount}件／取得${result.downloadedCount}件／新規${result.importedCount}件／重複${result.duplicateCount}件／隔離${result.rejectedCount}件",
            )
            .apply()
    }

    fun failed(message: String) {
        preferences.edit()
            .putBoolean("running", false)
            .putLong("completed_at", System.currentTimeMillis())
            .putString("message", message.take(500))
            .apply()
    }
}

data class GoogleDriveDirectSyncResult(
    val listedCount: Int,
    val downloadedCount: Int,
    val unchangedCount: Int,
    val importedCount: Int,
    val duplicateCount: Int,
    val rejectedCount: Int,
    val errorCount: Int,
)

class GoogleDriveDirectSyncRepository(
    private val context: Context,
    private val database: ManagementDatabase = ManagementDatabase(context.applicationContext),
) : AutoCloseable {
    private val appContext = context.applicationContext

    fun synchronize(accessToken: String, forceReimport: Boolean = false): GoogleDriveDirectSyncResult {
        val statusStore = GoogleDriveDirectSyncStatusStore(appContext)
        statusStore.running()
        ensureSchema(database.writableDatabase)
        val client = GoogleDriveSyncRestClient(accessToken)
        val remoteFiles = client.listJournalFiles()
        val documents = mutableListOf<SalesJournalImportDocument>()
        val processed = mutableListOf<ProcessedDriveFile>()
        var unchanged = 0
        var errors = 0
        remoteFiles.forEach { remote ->
            val known = known(remote.id)
            if (!forceReimport && known != null && known.modifiedTime == remote.modifiedTime) {
                unchanged += 1
                return@forEach
            }
            runCatching {
                require(remote.size == null || remote.size in 1..SalesJournalImportContract.MAX_DOCUMENT_BYTES) {
                    "Google Drive上のJSONが20MiBを超えています"
                }
                val bytes = client.download(remote.id)
                require(bytes.isNotEmpty() && bytes.size <= SalesJournalImportContract.MAX_DOCUMENT_BYTES) {
                    "Google Drive上のJSONサイズが不正です"
                }
                val rawJson = bytes.toString(Charsets.UTF_8)
                val sha256 = sha256(bytes)
                if (!forceReimport && known != null && known.contentSha256 == sha256) {
                    recordFingerprint(remote, sha256)
                    unchanged += 1
                } else {
                    documents += SalesJournalImportDocument(
                        sourceName = remote.name,
                        sourceUri = "gdrive://${remote.id}",
                        rawJson = rawJson,
                    )
                    processed += ProcessedDriveFile(remote, sha256)
                }
            }.onFailure { error ->
                errors += 1
                documents += SalesJournalImportDocument(
                    sourceName = remote.name,
                    sourceUri = "gdrive://${remote.id}",
                    rawJson = null,
                    loadErrorCode = ImportRejectionCode.READ_ERROR,
                    loadErrorMessage = error.message ?: "Google Driveから読み込めませんでした",
                )
            }
        }
        val batch = if (documents.isEmpty()) null else SalesJournalImportRepository(database).importDocuments(documents)
        processed.forEach { recordFingerprint(it.remote, it.sha256) }
        val result = GoogleDriveDirectSyncResult(
            listedCount = remoteFiles.size,
            downloadedCount = documents.size,
            unchangedCount = unchanged,
            importedCount = batch?.importedCount ?: 0,
            duplicateCount = batch?.duplicateCount ?: 0,
            rejectedCount = batch?.rejectedCount ?: 0,
            errorCount = errors,
        )
        statusStore.complete(result)
        return result
    }

    override fun close() {
        database.close()
    }

    private fun known(fileId: String): KnownDriveFile? = database.readableDatabase.rawQuery(
        "SELECT modified_time, content_sha256 FROM $TABLE WHERE file_id=?",
        arrayOf(fileId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else KnownDriveFile(cursor.getString(0), cursor.getString(1))
    }

    private fun recordFingerprint(remote: GoogleDriveSyncRemoteFile, sha256: String) {
        database.writableDatabase.insertWithOnConflict(
            TABLE,
            null,
            ContentValues().apply {
                put("file_id", remote.id)
                put("file_name", remote.name)
                put("modified_time", remote.modifiedTime)
                put("content_sha256", sha256)
                put("store_id", remote.appProperties["storeId"])
                put("terminal_id", remote.appProperties["terminalId"])
                put("business_date", remote.appProperties["businessDate"])
                put("last_processed_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun ensureSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE (
                file_id TEXT PRIMARY KEY NOT NULL,
                file_name TEXT NOT NULL,
                modified_time TEXT NOT NULL,
                content_sha256 TEXT NOT NULL,
                store_id TEXT,
                terminal_id TEXT,
                business_date TEXT,
                last_processed_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_drive_sync_files_modified ON $TABLE(modified_time, last_processed_at DESC)",
        )
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

    private data class KnownDriveFile(val modifiedTime: String, val contentSha256: String)
    private data class ProcessedDriveFile(val remote: GoogleDriveSyncRemoteFile, val sha256: String)

    companion object {
        const val TABLE = "drive_sync_files"
    }
}

class GoogleDriveDirectSyncWorker(context: Context, parameters: WorkerParameters) :
    Worker(context, parameters) {
    override fun doWork(): Result {
        val account = GoogleDriveAccountStore(applicationContext).load()
        if (account.email == null) return Result.success()
        return runCatching {
            val token = GoogleDriveSyncAccessTokenProvider.acquire(applicationContext)
            GoogleDriveDirectSyncRepository(applicationContext).use { repository ->
                repository.synchronize(token, forceReimport = false)
            }
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { error ->
                val category = GoogleDriveSyncErrorPolicy.classify(error)
                GoogleDriveDirectSyncStatusStore(applicationContext).failed(
                    "${GoogleDriveSyncErrorPolicy.message(category)}：${error.message ?: error.javaClass.simpleName}",
                )
                if (category.retryable) Result.retry() else Result.success()
            },
        )
    }
}

object GoogleDriveDirectSyncScheduler {
    private const val PERIODIC_NAME = "tsuguregi-plus-drive-api-sync-periodic"
    private const val STARTUP_NAME = "tsuguregi-plus-drive-api-sync-startup"
    private const val MANUAL_NAME = "tsuguregi-plus-drive-api-sync-manual"

    fun ensurePeriodic(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<GoogleDriveDirectSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun enqueueStartup(context: Context) {
        if (!GoogleDriveDirectSyncStatusStore(context).load().autoSyncOnLaunch) return
        enqueue(context, STARTUP_NAME, ExistingWorkPolicy.KEEP)
    }

    fun enqueueNow(context: Context) {
        enqueue(context, MANUAL_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    private fun enqueue(context: Context, name: String, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<GoogleDriveDirectSyncWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(name, policy, request)
    }
}

class GoogleDriveDirectSyncBootstrapProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return false
        runCatching {
            GoogleDriveDirectSyncScheduler.ensurePeriodic(appContext)
            GoogleDriveDirectSyncScheduler.enqueueStartup(appContext)
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
