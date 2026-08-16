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
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

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

class GoogleDriveSyncBatchException(
    val category: GoogleDriveSyncFailureCategory,
    message: String,
    cause: Throwable,
) : IOException(message, cause)

class GoogleDriveSyncIncompleteListingException(message: String) : IOException(message)

object GoogleDriveSyncErrorPolicy {
    fun classify(error: Throwable): GoogleDriveSyncFailureCategory {
        if (error is GoogleDriveDirectSyncStatusPersistenceException) {
            return GoogleDriveSyncFailureCategory.UNKNOWN
        }
        if (error is GoogleDriveSyncBatchException) {
            return error.category
        }
        if (error is GoogleDriveSyncIncompleteListingException) {
            return GoogleDriveSyncFailureCategory.SERVER
        }
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

enum class GoogleDriveRemoteFileFailureDisposition {
    RETRY_BATCH,
    BLOCK_BATCH,
    REJECT_FILE,
}

data class GoogleDriveRemoteFileFailureDecision(
    val category: GoogleDriveSyncFailureCategory,
    val disposition: GoogleDriveRemoteFileFailureDisposition,
)

object GoogleDriveRemoteFileFailurePolicy {
    fun decide(error: Throwable): GoogleDriveRemoteFileFailureDecision {
        if (error is IllegalArgumentException || error is JSONException) {
            return GoogleDriveRemoteFileFailureDecision(
                category = GoogleDriveSyncFailureCategory.INVALID_DATA,
                disposition = GoogleDriveRemoteFileFailureDisposition.REJECT_FILE,
            )
        }
        val category = GoogleDriveSyncErrorPolicy.classify(error)
        val disposition = when (category) {
            GoogleDriveSyncFailureCategory.AUTHORIZATION_REQUIRED,
            GoogleDriveSyncFailureCategory.API_DISABLED,
            GoogleDriveSyncFailureCategory.PERMISSION_DENIED,
            -> GoogleDriveRemoteFileFailureDisposition.BLOCK_BATCH

            GoogleDriveSyncFailureCategory.RATE_LIMITED,
            GoogleDriveSyncFailureCategory.NETWORK,
            GoogleDriveSyncFailureCategory.SERVER,
            GoogleDriveSyncFailureCategory.UNKNOWN,
            -> GoogleDriveRemoteFileFailureDisposition.RETRY_BATCH

            GoogleDriveSyncFailureCategory.INVALID_DATA -> GoogleDriveRemoteFileFailureDisposition.REJECT_FILE
        }
        return GoogleDriveRemoteFileFailureDecision(category, disposition)
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

object GoogleDriveRemoteVersionPolicyV119 {
    fun canSkipDownload(
        knownRemoteVersion: String?,
        currentRemoteVersion: String?,
        forceReimport: Boolean,
    ): Boolean = !forceReimport &&
        !knownRemoteVersion.isNullOrBlank() &&
        knownRemoteVersion == currentRemoteVersion
}

data class GoogleDriveSyncRemoteFile(
    val id: String,
    val name: String,
    val modifiedTime: String,
    val version: String?,
    val size: Long?,
    val appProperties: Map<String, String>,
)

data class GoogleDriveSyncRemotePage(
    val files: List<GoogleDriveSyncRemoteFile>,
    val nextPageToken: String?,
    val incompleteSearch: Boolean,
)

class GoogleDriveSyncRestClient(private val accessToken: String) {
    fun listJournalPage(pageToken: String?): GoogleDriveSyncRemotePage {
        val query = "mimeType='application/json' and trashed=false" +
            propertyQuery("app", APP) + propertyQuery("role", ROLE)
        val fields = "nextPageToken,incompleteSearch,files(id,name,modifiedTime,version,size,appProperties)"
        val url = buildString {
            append(DRIVE_FILES_URL)
            append("?spaces=drive")
            append("&supportsAllDrives=false")
            append("&pageSize=$PAGE_SIZE")
            append("&orderBy=modifiedTime")
            append("&fields=").append(encode(fields))
            append("&q=").append(encode(query))
            pageToken?.let { append("&pageToken=").append(encode(it)) }
        }
        val root = JSONObject(execute("GET", url))
        val files = root.optJSONArray("files") ?: JSONArray()
        val result = ArrayList<GoogleDriveSyncRemoteFile>(files.length())
        for (index in 0 until files.length()) {
            val item = files.getJSONObject(index)
            val properties = linkedMapOf<String, String>()
            item.optJSONObject("appProperties")?.let { source ->
                source.keys().forEach { key -> properties[key] = source.optString(key) }
            }
            result += GoogleDriveSyncRemoteFile(
                id = item.getString("id"),
                name = item.optString("name").ifBlank { "${item.getString("id")}.json" },
                modifiedTime = item.optString("modifiedTime"),
                version = item.optString("version").takeIf(String::isNotBlank),
                size = item.optString("size").toLongOrNull(),
                appProperties = properties,
            )
        }
        return GoogleDriveSyncRemotePage(
            files = result,
            nextPageToken = root.optString("nextPageToken").takeIf(String::isNotBlank),
            incompleteSearch = root.optBoolean("incompleteSearch", false),
        )
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
        const val PAGE_SIZE = 1_000

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
    val lastFailureCategory: GoogleDriveSyncFailureCategory? = null,
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
        lastFailureCategory = preferences.getString("failure_category", null)?.let { value ->
            runCatching { GoogleDriveSyncFailureCategory.valueOf(value) }.getOrNull()
        },
        lastMessage = preferences.getString("message", null)
            ?: "Drive API同期はまだ実行されていません",
    )

    fun setAutoSyncOnLaunch(enabled: Boolean) {
        preferences.edit().putBoolean("auto_sync", enabled).apply()
    }

    fun running(): String {
        val runToken = UUID.randomUUID().toString()
        preferences.edit()
            .putBoolean("running", true)
            .putString("run_token", runToken)
            .putLong("started_at", System.currentTimeMillis())
            .putInt("listed", 0)
            .putInt("downloaded", 0)
            .putInt("unchanged", 0)
            .putInt("imported", 0)
            .putInt("duplicates", 0)
            .putInt("rejected", 0)
            .putInt("errors", 0)
            .remove("failure_category")
            .remove(KEY_OWNED_RUN_FAILURE_PENDING)
            .putString("message", "Google Driveの売上JSONを確認しています")
            .apply()
        return runToken
    }

    fun progress(runToken: String, result: GoogleDriveDirectSyncResult) {
        if (preferences.getString("run_token", null) != runToken) return
        preferences.edit()
            .putInt("listed", result.listedCount)
            .putInt("downloaded", result.downloadedCount)
            .putInt("unchanged", result.unchangedCount)
            .putInt("imported", result.importedCount)
            .putInt("duplicates", result.duplicateCount)
            .putInt("rejected", result.rejectedCount)
            .putInt("errors", result.errorCount)
            .putString(
                "message",
                "同期中／確認${result.listedCount}件／取得${result.downloadedCount}件／新規${result.importedCount}件／重複${result.duplicateCount}件／隔離${result.rejectedCount}件",
            )
            .apply()
    }

    fun complete(runToken: String, result: GoogleDriveDirectSyncResult) {
        if (preferences.getString("run_token", null) != runToken) return
        val completedAt = System.currentTimeMillis()
        preferences.edit()
            .putBoolean("running", false)
            .remove("run_token")
            .putLong("completed_at", completedAt)
            .putInt("listed", result.listedCount)
            .putInt("downloaded", result.downloadedCount)
            .putInt("unchanged", result.unchangedCount)
            .putInt("imported", result.importedCount)
            .putInt("duplicates", result.duplicateCount)
            .putInt("rejected", result.rejectedCount)
            .putInt("errors", result.errorCount)
            .remove("failure_category")
            .remove(KEY_OWNED_RUN_FAILURE_PENDING)
            .putString(
                "message",
                "最終同期 ${formatSyncTime(completedAt)}／確認${result.listedCount}件／取得${result.downloadedCount}件／新規${result.importedCount}件／重複${result.duplicateCount}件／隔離${result.rejectedCount}件",
            )
            .apply()
    }

    fun failed(message: String) {
        failed(GoogleDriveSyncFailureCategory.UNKNOWN, message)
    }

    fun failed(category: GoogleDriveSyncFailureCategory, message: String) {
        if (preferences.getBoolean("running", false)) return
        val ownedRunFailure = preferences.getBoolean(KEY_OWNED_RUN_FAILURE_PENDING, false)
        writeFailure(
            category = category,
            message = message,
            resetProgress = !ownedRunFailure,
            markOwnedRunFailurePending = false,
        )
    }

    fun failedForRun(runToken: String, category: GoogleDriveSyncFailureCategory, message: String) {
        if (preferences.getString("run_token", null) != runToken) return
        writeFailure(
            category = category,
            message = message,
            resetProgress = false,
            markOwnedRunFailurePending = true,
        )
    }

    fun recoverStaleRun(message: String) {
        val completedAt = System.currentTimeMillis()
        preferences.edit()
            .putBoolean("running", false)
            .remove("run_token")
            .remove(KEY_OWNED_RUN_FAILURE_PENDING)
            .putLong("completed_at", completedAt)
            .putString("failure_category", GoogleDriveSyncFailureCategory.UNKNOWN.name)
            .putString(
                "message",
                "最終同期 ${formatSyncTime(completedAt)}（停止状態を修復）／${message.take(360)}",
            )
            .apply()
    }

    private fun writeFailure(
        category: GoogleDriveSyncFailureCategory,
        message: String,
        resetProgress: Boolean,
        markOwnedRunFailurePending: Boolean,
    ) {
        val completedAt = System.currentTimeMillis()
        val editor = preferences.edit()
            .putBoolean("running", false)
            .remove("run_token")
            .putLong("completed_at", completedAt)
            .putString("failure_category", category.name)
            .putString(
                "message",
                "最終同期 ${formatSyncTime(completedAt)}（失敗）／${message.take(420)}",
            )
        if (resetProgress) {
            resetProgressCounters(editor)
        }
        if (markOwnedRunFailurePending) {
            editor.putBoolean(KEY_OWNED_RUN_FAILURE_PENDING, true)
        } else {
            editor.remove(KEY_OWNED_RUN_FAILURE_PENDING)
        }
        editor.apply()
    }

    private fun formatSyncTime(value: Long): String =
        SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date(value))

    private fun resetProgressCounters(editor: android.content.SharedPreferences.Editor) {
        editor
            .putInt("listed", 0)
            .putInt("downloaded", 0)
            .putInt("unchanged", 0)
            .putInt("imported", 0)
            .putInt("duplicates", 0)
            .putInt("rejected", 0)
            .putInt("errors", 0)
    }

    companion object {
        private const val KEY_OWNED_RUN_FAILURE_PENDING = "owned_run_failure_pending_v125"
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

object GoogleDriveSyncSingleFlightV121 {
    private val lock = ReentrantLock(true)

    fun <T> run(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}

class GoogleDriveDirectSyncRepository(
    private val context: Context,
    private val database: ManagementDatabase = ManagementDatabase(context.applicationContext),
) : AutoCloseable {
    private val appContext = context.applicationContext

    fun synchronize(accessToken: String, forceReimport: Boolean = false): GoogleDriveDirectSyncResult =
        GoogleDriveSyncSingleFlightV121.run {
            GoogleDriveStartupRecoveryBarrierV132.requireDriveSyncAllowed()
            // v1.32 cumulative source-test compatibility marker: val runToken = statusStore.running()
            val runToken = GoogleDriveDirectSyncStatusDurabilityV133.start(appContext)
            try {
                val initialDb = database.writableDatabase
                ensureSchema(initialDb)
                GoogleDrivePageCommitCheckpointStoreV134.ensureSchema(initialDb)
                GoogleDrivePageCommitCheckpointStoreV134.clear(initialDb)
                val client = GoogleDriveSyncRestClient(accessToken)
                val visitedPageTokens = mutableSetOf<String>()
                var pageToken: String? = null
                var listed = 0
                var downloaded = 0
                var unchanged = 0
                var imported = 0
                var duplicates = 0
                var rejected = 0
                var errors = 0

                do {
                    if (pageToken != null && !visitedPageTokens.add(pageToken)) {
                        throw GoogleDriveSyncIncompleteListingException("Google Driveのpage tokenが循環しました")
                    }
                    val page = client.listJournalPage(pageToken)
                    if (page.incompleteSearch) {
                        throw GoogleDriveSyncIncompleteListingException("Google Driveの一覧検索が不完全なため再試行します")
                    }
                    listed += page.files.size
                    val documents = mutableListOf<SalesJournalImportDocument>()
                    val processed = mutableListOf<ProcessedDriveFile>()
                    val unchangedFingerprints = mutableListOf<ProcessedDriveFile>()

                    page.files.forEach { remote ->
                        val known = known(remote.id)
                        if (
                            GoogleDriveRemoteVersionPolicyV119.canSkipDownload(
                                knownRemoteVersion = known?.remoteVersion,
                                currentRemoteVersion = remote.version,
                                forceReimport = forceReimport,
                            )
                        ) {
                            unchanged += 1
                            return@forEach
                        }
                        try {
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
                                unchangedFingerprints += ProcessedDriveFile(remote, sha256)
                                unchanged += 1
                            } else {
                                documents += SalesJournalImportDocument(
                                    sourceName = remote.name,
                                    sourceUri = "gdrive://${remote.id}",
                                    rawJson = rawJson,
                                )
                                processed += ProcessedDriveFile(remote, sha256)
                            }
                        } catch (error: Throwable) {
                            val decision = GoogleDriveRemoteFileFailurePolicy.decide(error)
                            when (decision.disposition) {
                                GoogleDriveRemoteFileFailureDisposition.REJECT_FILE -> {
                                    errors += 1
                                    documents += SalesJournalImportDocument(
                                        sourceName = remote.name,
                                        sourceUri = "gdrive://${remote.id}",
                                        rawJson = null,
                                        loadErrorCode = ImportRejectionCode.READ_ERROR,
                                        loadErrorMessage = error.message ?: "Google Driveから読み込めませんでした",
                                    )
                                }
                                GoogleDriveRemoteFileFailureDisposition.RETRY_BATCH,
                                GoogleDriveRemoteFileFailureDisposition.BLOCK_BATCH,
                                -> throw GoogleDriveSyncBatchException(
                                    category = decision.category,
                                    message = "${GoogleDriveSyncErrorPolicy.message(decision.category)}：${error.message ?: error.javaClass.simpleName}",
                                    cause = error,
                                )
                            }
                        }
                    }

                    val pageDb = database.writableDatabase
                    var committedPageResult: GoogleDriveDirectSyncResult? = null
                    pageDb.beginTransaction()
                    try {
                        val batch = if (documents.isEmpty()) {
                            null
                        } else {
                            // Android SQLiteDatabase supports nested transactions; the existing importer
                            // transaction therefore commits only when this outer page transaction succeeds.
                            SalesJournalImportRepository(database).importDocumentsWithCommitHook(documents) { db ->
                                processed.forEach { recordFingerprint(db, it.remote, it.sha256) }
                            }
                        }
                        unchangedFingerprints.forEach {
                            recordFingerprint(pageDb, it.remote, it.sha256)
                        }
                        val result = GoogleDriveDirectSyncResult(
                            listedCount = listed,
                            downloadedCount = downloaded + documents.size,
                            unchangedCount = unchanged,
                            importedCount = imported + (batch?.importedCount ?: 0),
                            duplicateCount = duplicates + (batch?.duplicateCount ?: 0),
                            rejectedCount = rejected + (batch?.rejectedCount ?: 0),
                            errorCount = errors,
                        )
                        GoogleDrivePageCommitCheckpointStoreV134.persist(
                            db = pageDb,
                            runToken = runToken,
                            result = result,
                        )
                        pageDb.setTransactionSuccessful()
                        committedPageResult = result
                    } finally {
                        pageDb.endTransaction()
                    }

                    val pageResult = checkNotNull(committedPageResult)
                    downloaded = pageResult.downloadedCount
                    imported = pageResult.importedCount
                    duplicates = pageResult.duplicateCount
                    rejected = pageResult.rejectedCount
                    // v1.23 cumulative source-test compatibility marker: statusStore.progress(
                    GoogleDriveDirectSyncStatusDurabilityV133.progress(
                        context = appContext,
                        runToken = runToken,
                        result = pageResult,
                    )
                    pageToken = page.nextPageToken
                } while (pageToken != null)

                val result = GoogleDriveDirectSyncResult(
                    listedCount = listed,
                    downloadedCount = downloaded,
                    unchangedCount = unchanged,
                    importedCount = imported,
                    duplicateCount = duplicates,
                    rejectedCount = rejected,
                    errorCount = errors,
                )
                GoogleDriveDirectSyncStatusDurabilityV133.complete(
                    context = appContext,
                    runToken = runToken,
                    result = result,
                )
                result
            } catch (error: Throwable) {
                if (error is GoogleDriveDirectSyncStatusPersistenceException) throw error
                val category = GoogleDriveSyncErrorPolicy.classify(error)
                GoogleDriveDirectSyncStatusDurabilityV133.failedForRun(
                    context = appContext,
                    runToken = runToken,
                    category = category,
                    message = "${GoogleDriveSyncErrorPolicy.message(category)}：${error.message ?: error.javaClass.simpleName}",
                )
                throw error
            }
        }

    override fun close() {
        database.close()
    }

    private fun known(fileId: String): KnownDriveFile? = database.readableDatabase.rawQuery(
        "SELECT remote_version, content_sha256 FROM $TABLE WHERE file_id=?",
        arrayOf(fileId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) {
            null
        } else {
            KnownDriveFile(
                remoteVersion = if (cursor.isNull(0)) null else cursor.getString(0),
                contentSha256 = cursor.getString(1),
            )
        }
    }

    private fun recordFingerprint(
        db: SQLiteDatabase,
        remote: GoogleDriveSyncRemoteFile,
        sha256: String,
    ) {
        db.insertWithOnConflict(
            TABLE,
            null,
            ContentValues().apply {
                put("file_id", remote.id)
                put("file_name", remote.name)
                put("modified_time", remote.modifiedTime)
                if (remote.version == null) putNull("remote_version") else put("remote_version", remote.version)
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
                remote_version TEXT,
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

    private data class KnownDriveFile(val remoteVersion: String?, val contentSha256: String)
    private data class ProcessedDriveFile(val remote: GoogleDriveSyncRemoteFile, val sha256: String)

    companion object {
        const val TABLE = "drive_sync_files"
    }
}

class GoogleDriveDirectSyncWorker(context: Context, parameters: WorkerParameters) :
    Worker(context, parameters) {
    override fun doWork(): Result {
        if (GoogleDriveStartupRecoveryBarrierV132.isBlocked()) return Result.success()
        val account = GoogleDriveAccountStore(applicationContext).load()
        if (account.email == null) return Result.success()
        val statusStore = GoogleDriveDirectSyncStatusStore(applicationContext)
        val historyStore = GoogleDriveSyncVerificationHistoryStore(applicationContext)
        return runCatching {
            val token = GoogleDriveSyncAccessTokenProvider.acquire(applicationContext)
            GoogleDriveDirectSyncRepository(applicationContext).use { repository ->
                repository.synchronize(token, forceReimport = false)
            }
        }.fold(
            onSuccess = {
                val finalizedStatus = statusStore.load()
                historyStore.append(
                    GoogleDriveWorkerVerificationRecordV127.success(finalizedStatus),
                )
                Result.success()
            },
            onFailure = { error ->
                if (
                    error is GoogleDriveStartupRecoveryBlockedException ||
                    error is GoogleDriveDirectSyncStatusPersistenceException
                ) {
                    Result.success()
                } else {
                    val category = GoogleDriveSyncErrorPolicy.classify(error)
                    // v1.32 cumulative source-test compatibility marker: statusStore.failed(
                    GoogleDriveDirectSyncStatusDurabilityV133.failed(
                        context = applicationContext,
                        category = category,
                        message = "${GoogleDriveSyncErrorPolicy.message(category)}：${error.message ?: error.javaClass.simpleName}",
                    )
                    val finalizedStatus = statusStore.load()
                    historyStore.append(
                        GoogleDriveWorkerVerificationRecordV127.failure(
                            status = finalizedStatus,
                            error = error,
                        ),
                    )
                    if (category.retryable) Result.retry() else Result.success()
                }
            },
        )
    }
}

object GoogleDriveDirectSyncScheduler {
    private const val PERIODIC_NAME = "tsuguregi-plus-drive-api-sync-periodic"
    private const val IMMEDIATE_NAME = "tsuguregi-plus-drive-api-sync-immediate"

    fun setAutomaticSyncEnabled(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        if (enabled) {
            if (GoogleDriveStartupRecoveryBarrierV132.isBlocked()) return
            ensurePeriodic(appContext)
            enqueueStartup(appContext)
        } else {
            WorkManager.getInstance(appContext).cancelUniqueWork(PERIODIC_NAME)
            WorkManager.getInstance(appContext).cancelUniqueWork(IMMEDIATE_NAME)
        }
    }

    fun ensurePeriodic(context: Context) {
        if (GoogleDriveStartupRecoveryBarrierV132.isBlocked()) return
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
        if (GoogleDriveStartupRecoveryBarrierV132.isBlocked()) return
        if (!GoogleDriveDirectSyncStatusStore(context).load().autoSyncOnLaunch) return
        enqueueImmediate(context, ExistingWorkPolicy.KEEP)
    }

    fun enqueueNow(context: Context) {
        if (GoogleDriveStartupRecoveryBarrierV132.isBlocked()) return
        enqueueImmediate(context, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    private fun enqueueImmediate(context: Context, policy: ExistingWorkPolicy) {
        if (GoogleDriveStartupRecoveryBarrierV132.isBlocked()) return
        val request = OneTimeWorkRequestBuilder<GoogleDriveDirectSyncWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(IMMEDIATE_NAME, policy, request)
    }
}

class GoogleDriveDirectSyncBootstrapProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return false
        if (GoogleDriveStartupRecoveryBarrierV132.isBlocked()) return true
        runCatching {
            val enabled = GoogleDriveDirectSyncStatusStore(appContext).load().autoSyncOnLaunch
            GoogleDriveDirectSyncScheduler.setAutomaticSyncEnabled(appContext, enabled)
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