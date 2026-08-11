package jp.co.tenposinfo.register

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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class GoogleDriveApiFailureCategory(val retryable: Boolean) {
    AUTHORIZATION_REQUIRED(false),
    API_DISABLED(false),
    STORAGE_FULL(false),
    PERMISSION_DENIED(false),
    RATE_LIMITED(true),
    NETWORK(true),
    SERVER(true),
    INVALID_DATA(false),
    UNKNOWN(true),
}


enum class GoogleDriveCandidateFailureDisposition {
    RETRY_BATCH,
    PERMANENT_ITEM,
    BLOCK_QUEUE,
}

object GoogleDriveCandidateFailurePolicy {
    fun disposition(category: GoogleDriveApiFailureCategory): GoogleDriveCandidateFailureDisposition = when (category) {
        GoogleDriveApiFailureCategory.AUTHORIZATION_REQUIRED,
        GoogleDriveApiFailureCategory.API_DISABLED,
        GoogleDriveApiFailureCategory.STORAGE_FULL,
        GoogleDriveApiFailureCategory.PERMISSION_DENIED,
        -> GoogleDriveCandidateFailureDisposition.BLOCK_QUEUE

        GoogleDriveApiFailureCategory.RATE_LIMITED,
        GoogleDriveApiFailureCategory.NETWORK,
        GoogleDriveApiFailureCategory.SERVER,
        GoogleDriveApiFailureCategory.UNKNOWN,
        -> GoogleDriveCandidateFailureDisposition.RETRY_BATCH

        GoogleDriveApiFailureCategory.INVALID_DATA -> GoogleDriveCandidateFailureDisposition.PERMANENT_ITEM
    }
}

class GoogleDriveApiException(
    val responseCode: Int,
    val responseBody: String,
) : IOException("Google Drive API HTTP $responseCode")

class GoogleDriveAuthorizationRequiredException(message: String) : IllegalStateException(message)

object GoogleDriveApiErrorPolicy {
    fun classify(error: Throwable): GoogleDriveApiFailureCategory {
        if (error is GoogleDriveAuthorizationRequiredException) {
            return GoogleDriveApiFailureCategory.AUTHORIZATION_REQUIRED
        }
        if (error is IllegalArgumentException || error is JSONException) {
            return GoogleDriveApiFailureCategory.INVALID_DATA
        }
        if (error is GoogleDriveApiException) {
            val body = error.responseBody.lowercase(Locale.ROOT)
            return when {
                error.responseCode == 401 -> GoogleDriveApiFailureCategory.AUTHORIZATION_REQUIRED
                body.contains("service_disabled") || body.contains("accessnotconfigured") ->
                    GoogleDriveApiFailureCategory.API_DISABLED
                body.contains("storagequotaexceeded") || body.contains("storage quota") ->
                    GoogleDriveApiFailureCategory.STORAGE_FULL
                error.responseCode == 429 || body.contains("ratelimitexceeded") ||
                    body.contains("userratelimitexceeded") -> GoogleDriveApiFailureCategory.RATE_LIMITED
                error.responseCode == 403 -> GoogleDriveApiFailureCategory.PERMISSION_DENIED
                error.responseCode >= 500 -> GoogleDriveApiFailureCategory.SERVER
                error.responseCode in 400..499 -> GoogleDriveApiFailureCategory.INVALID_DATA
                else -> GoogleDriveApiFailureCategory.UNKNOWN
            }
        }
        return if (error is IOException) {
            GoogleDriveApiFailureCategory.NETWORK
        } else {
            GoogleDriveApiFailureCategory.UNKNOWN
        }
    }

    fun message(category: GoogleDriveApiFailureCategory): String = when (category) {
        GoogleDriveApiFailureCategory.AUTHORIZATION_REQUIRED -> "Googleアカウントの再認可が必要です"
        GoogleDriveApiFailureCategory.API_DISABLED -> "Google CloudでGoogle Drive APIを有効にしてください"
        GoogleDriveApiFailureCategory.STORAGE_FULL -> "Google Driveの空き容量が不足しています"
        GoogleDriveApiFailureCategory.PERMISSION_DENIED -> "Google Driveへのアクセス権限が不足しています"
        GoogleDriveApiFailureCategory.RATE_LIMITED -> "Google Drive APIの呼出上限に達したため再試行します"
        GoogleDriveApiFailureCategory.NETWORK -> "ネットワーク通信に失敗しました"
        GoogleDriveApiFailureCategory.SERVER -> "Google Drive側の一時エラーです"
        GoogleDriveApiFailureCategory.INVALID_DATA -> "Google Driveへ送信するデータまたは要求が不正です"
        GoogleDriveApiFailureCategory.UNKNOWN -> "Google Drive同期で不明なエラーが発生しました"
    }
}

object GoogleDriveAccessTokenProvider {
    fun acquire(context: Context): String {
        val accountState = GoogleDriveAccountStore(context).load()
        val email = accountState.email
            ?: throw GoogleDriveAuthorizationRequiredException("Googleアカウントが未登録です")
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
            throw GoogleDriveAuthorizationRequiredException(
                error.cause?.message ?: error.message ?: "Google認可情報を取得できません",
            )
        }
        if (result.hasResolution()) {
            throw GoogleDriveAuthorizationRequiredException("Googleアカウント画面で再接続してください")
        }
        return result.accessToken?.takeIf(String::isNotBlank)
            ?: throw GoogleDriveAuthorizationRequiredException("アクセストークンを取得できません")
    }
}

data class GoogleDriveRemoteFile(
    val id: String,
    val name: String,
    val modifiedTime: String?,
    val size: Long?,
    val appProperties: Map<String, String>,
)

class GoogleDriveRestClient(private val accessToken: String) {
    fun findOne(query: String): GoogleDriveRemoteFile? {
        val fields = "files(id,name,modifiedTime,size,appProperties)"
        val url = buildString {
            append(DRIVE_FILES_URL)
            append("?spaces=drive")
            append("&supportsAllDrives=false")
            append("&pageSize=10")
            append("&fields=").append(encode(fields))
            append("&q=").append(encode(query))
        }
        val body = execute("GET", url)
        val files = JSONObject(body).optJSONArray("files") ?: JSONArray()
        return if (files.length() == 0) null else files.getJSONObject(0).toRemoteFile()
    }

    fun createFolder(
        name: String,
        parentId: String,
        appProperties: Map<String, String>,
    ): GoogleDriveRemoteFile {
        val metadata = JSONObject()
            .put("name", name)
            .put("mimeType", FOLDER_MIME)
            .put("parents", JSONArray().put(parentId))
            .put("appProperties", appProperties.toJsonObject())
        val body = execute(
            method = "POST",
            url = "$DRIVE_FILES_URL?supportsAllDrives=false&fields=id,name,modifiedTime,size,appProperties",
            requestBody = metadata.toString().toByteArray(Charsets.UTF_8),
            contentType = "application/json; charset=UTF-8",
        )
        return JSONObject(body).toRemoteFile()
    }

    fun createJson(
        name: String,
        parentId: String,
        bytes: ByteArray,
        appProperties: Map<String, String>,
    ): GoogleDriveRemoteFile {
        val metadata = JSONObject()
            .put("name", name)
            .put("mimeType", JSON_MIME)
            .put("parents", JSONArray().put(parentId))
            .put("appProperties", appProperties.toJsonObject())
        return multipart(
            method = "POST",
            url = "$DRIVE_UPLOAD_URL?uploadType=multipart&supportsAllDrives=false&fields=id,name,modifiedTime,size,appProperties",
            metadata = metadata,
            bytes = bytes,
        )
    }

    fun updateJson(
        fileId: String,
        bytes: ByteArray,
        appProperties: Map<String, String>,
    ): GoogleDriveRemoteFile {
        val metadata = JSONObject()
            .put("mimeType", JSON_MIME)
            .put("appProperties", appProperties.toJsonObject())
        return multipart(
            method = "PATCH",
            url = "$DRIVE_UPLOAD_URL/${encodePath(fileId)}?uploadType=multipart&supportsAllDrives=false&fields=id,name,modifiedTime,size,appProperties",
            metadata = metadata,
            bytes = bytes,
        )
    }

    private fun multipart(
        method: String,
        url: String,
        metadata: JSONObject,
        bytes: ByteArray,
    ): GoogleDriveRemoteFile {
        val boundary = "tsuguregi-${System.nanoTime()}"
        val output = ByteArrayOutputStream()
        output.write("--$boundary\r\n".toByteArray())
        output.write("Content-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray())
        output.write(metadata.toString().toByteArray(Charsets.UTF_8))
        output.write("\r\n--$boundary\r\n".toByteArray())
        output.write("Content-Type: application/json\r\n\r\n".toByteArray())
        output.write(bytes)
        output.write("\r\n--$boundary--\r\n".toByteArray())
        val response = execute(
            method = method,
            url = url,
            requestBody = output.toByteArray(),
            contentType = "multipart/related; boundary=$boundary",
        )
        return JSONObject(response).toRemoteFile()
    }

    private fun execute(
        method: String,
        url: String,
        requestBody: ByteArray? = null,
        contentType: String? = null,
    ): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 20_000
            connection.readTimeout = 45_000
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")
            if (requestBody != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType ?: "application/json")
                connection.setFixedLengthStreamingMode(requestBody.size)
                connection.outputStream.use { it.write(requestBody) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw GoogleDriveApiException(code, body)
            body
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.toRemoteFile(): GoogleDriveRemoteFile {
        val properties = linkedMapOf<String, String>()
        optJSONObject("appProperties")?.let { source ->
            source.keys().forEach { key -> properties[key] = source.optString(key) }
        }
        return GoogleDriveRemoteFile(
            id = getString("id"),
            name = optString("name"),
            modifiedTime = optString("modifiedTime").takeIf(String::isNotBlank),
            size = optString("size").toLongOrNull(),
            appProperties = properties,
        )
    }

    private fun Map<String, String>.toJsonObject(): JSONObject = JSONObject().also { target ->
        forEach { (key, value) -> target.put(key, value) }
    }

    companion object {
        const val DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files"
        const val DRIVE_UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val JSON_MIME = "application/json"

        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
        fun encodePath(value: String): String = value.replace("/", "%2F")
        fun quoted(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")
    }
}

data class GoogleDriveUploadEnvelope(
    val duplicateKey: String,
    val storeId: String,
    val terminalId: String,
    val businessDate: String,
)

object GoogleDriveUploadEnvelopeParser {
    fun parse(bytes: ByteArray): GoogleDriveUploadEnvelope {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.optString("schema") == SalesJournalJsonContract.SCHEMA) {
            "売上ジャーナルschemaが不正です"
        }
        val duplicateKey = root.getString("duplicateImportKey")
        require(Regex("sj1-[0-9a-f]{64}").matches(duplicateKey)) {
            "duplicateImportKeyが不正です"
        }
        return GoogleDriveUploadEnvelope(
            duplicateKey = duplicateKey,
            storeId = root.getString("storeId"),
            terminalId = root.getString("terminalId"),
            businessDate = root.getString("businessDate"),
        )
    }
}

data class GoogleDriveDirectUploadStatus(
    val running: Boolean = false,
    val lastCompletedAt: Long? = null,
    val uploadedCount: Int = 0,
    val duplicateCount: Int = 0,
    val retryCount: Int = 0,
    val permanentFailureCount: Int = 0,
    val blockedCategory: GoogleDriveApiFailureCategory? = null,
    val lastMessage: String = "Google Drive直接送信はまだ実行されていません",
)

class GoogleDriveDirectUploadStatusStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "tsuguregi_drive_api_upload_status",
        Context.MODE_PRIVATE,
    )

    fun load(): GoogleDriveDirectUploadStatus = GoogleDriveDirectUploadStatus(
        running = preferences.getBoolean("running", false),
        lastCompletedAt = preferences.getLong("completed_at", 0L).takeIf { it > 0L },
        uploadedCount = preferences.getInt("uploaded", 0),
        duplicateCount = preferences.getInt("duplicates", 0),
        retryCount = preferences.getInt("retries", 0),
        permanentFailureCount = preferences.getInt("permanent_failures", 0),
        blockedCategory = preferences.getString("blocked_category", null)?.let { value ->
            runCatching { GoogleDriveApiFailureCategory.valueOf(value) }.getOrNull()
        },
        lastMessage = preferences.getString("message", null)
            ?: "Google Drive直接送信はまだ実行されていません",
    )

    fun running() {
        preferences.edit().putBoolean("running", true).putString("message", "Google Driveへ送信しています").apply()
    }

    fun complete(result: GoogleDriveDirectUploadRunResult) {
        val editor = preferences.edit()
            .putBoolean("running", false)
            .putLong("completed_at", System.currentTimeMillis())
            .putInt("uploaded", result.uploadedCount)
            .putInt("duplicates", result.duplicateCount)
            .putInt("retries", result.retryCount)
            .putInt("permanent_failures", result.permanentFailureCount)
        val blocked = result.blockedCategory
        if (blocked == null) {
            editor.remove("blocked_category")
        } else {
            editor.putString("blocked_category", blocked.name)
        }
        editor.putString(
            "message",
            result.blockedMessage?.take(500)
                ?: "送信${result.uploadedCount}件／既存${result.duplicateCount}件／再試行${result.retryCount}件／永久失敗${result.permanentFailureCount}件",
        ).apply()
    }

    fun failed(message: String) {
        failed(GoogleDriveApiFailureCategory.UNKNOWN, message)
    }

    fun failed(category: GoogleDriveApiFailureCategory, message: String) {
        val editor = preferences.edit()
            .putBoolean("running", false)
            .putLong("completed_at", System.currentTimeMillis())
            .putString("message", message.take(500))
        if (GoogleDriveCandidateFailurePolicy.disposition(category) == GoogleDriveCandidateFailureDisposition.BLOCK_QUEUE) {
            editor.putString("blocked_category", category.name)
        }
        editor.apply()
    }

    fun clearBlocker() {
        preferences.edit().remove("blocked_category").apply()
    }
}

private data class GoogleDriveUploadCandidate(
    val outboxId: Long,
    val eventId: String,
    val objectKey: String,
    val outboxStatus: String,
    val directAttemptCount: Int,
)

data class GoogleDriveDirectUploadRunResult(
    val uploadedCount: Int,
    val duplicateCount: Int,
    val retryCount: Int,
    val permanentFailureCount: Int,
    val retryRecommended: Boolean,
    val blockedCategory: GoogleDriveApiFailureCategory? = null,
    val blockedMessage: String? = null,
)

class GoogleDriveDirectUploadCoordinator(context: Context) {
    private val appContext = context.applicationContext

    fun process(accessToken: String, limit: Int = 100): GoogleDriveDirectUploadRunResult {
        GoogleDriveDirectUploadStatusStore(appContext).running()
        JournalOutboxStore(appContext).use { it.stagePending(limit) }
        ensureSchema()
        val client = GoogleDriveRestClient(accessToken)
        var uploaded = 0
        var duplicates = 0
        var retries = 0
        var permanentFailures = 0
        var retryRecommended = false
        var blockedCategory: GoogleDriveApiFailureCategory? = null
        var blockedMessage: String? = null
        for (candidate in loadCandidates(limit)) {
            val localFile = localFile(candidate.objectKey)
            if (!localFile.isFile) {
                markFailure(candidate, GoogleDriveApiFailureCategory.INVALID_DATA, "ローカルJSONが見つかりません")
                permanentFailures += 1
                continue
            }
            try {
                val bytes = localFile.readBytes()
                require(bytes.isNotEmpty() && bytes.size <= MAX_JSON_BYTES) { "JSONサイズが不正です" }
                val envelope = GoogleDriveUploadEnvelopeParser.parse(bytes)
                val sha256 = sha256(bytes)
                val rootId = ensureFolder(
                    client,
                    name = "つぐレジ",
                    parentId = "root",
                    properties = mapOf("app" to APP, "role" to "sales-journal-root"),
                )
                val storesId = ensureFolder(
                    client,
                    name = "stores",
                    parentId = rootId,
                    properties = mapOf("app" to APP, "role" to "stores"),
                )
                val storeId = ensureFolder(
                    client,
                    name = envelope.storeId,
                    parentId = storesId,
                    properties = mapOf(
                        "app" to APP,
                        "role" to "store",
                        "storeId" to envelope.storeId,
                    ),
                )
                val terminalsId = ensureFolder(
                    client,
                    name = "terminals",
                    parentId = storeId,
                    properties = mapOf("app" to APP, "role" to "terminals", "storeId" to envelope.storeId),
                )
                val terminalId = ensureFolder(
                    client,
                    name = envelope.terminalId,
                    parentId = terminalsId,
                    properties = mapOf(
                        "app" to APP,
                        "role" to "terminal",
                        "storeId" to envelope.storeId,
                        "terminalId" to envelope.terminalId,
                    ),
                )
                val journalId = ensureFolder(
                    client,
                    name = "journal",
                    parentId = terminalId,
                    properties = mapOf(
                        "app" to APP,
                        "role" to "journal",
                        "storeId" to envelope.storeId,
                        "terminalId" to envelope.terminalId,
                    ),
                )
                val dateId = ensureFolder(
                    client,
                    name = envelope.businessDate,
                    parentId = journalId,
                    properties = mapOf(
                        "app" to APP,
                        "role" to "business-date",
                        "storeId" to envelope.storeId,
                        "terminalId" to envelope.terminalId,
                        "businessDate" to envelope.businessDate,
                    ),
                )
                val properties = mapOf(
                    "app" to APP,
                    "role" to "sales-journal",
                    "storeId" to envelope.storeId,
                    "terminalId" to envelope.terminalId,
                    "businessDate" to envelope.businessDate,
                    "duplicateKey" to envelope.duplicateKey,
                    "contentSha256" to sha256,
                )
                val existing = client.findOne(
                    parentQuery(dateId) + " and mimeType='application/json'" +
                        propertyQuery("app", APP) + propertyQuery("role", "sales-journal") +
                        propertyQuery("duplicateKey", envelope.duplicateKey),
                )
                val duplicate = existing?.appProperties?.get("contentSha256") == sha256
                val remote = when {
                    duplicate -> existing
                    existing == null -> client.createJson(
                        name = "${envelope.duplicateKey}.json",
                        parentId = dateId,
                        bytes = bytes,
                        appProperties = properties,
                    )
                    else -> client.updateJson(existing.id, bytes, properties)
                }
                require(remote != null)
                markSuccess(candidate, remote, sha256)
                if (duplicate) duplicates += 1 else uploaded += 1
            } catch (error: Throwable) {
                val category = GoogleDriveApiErrorPolicy.classify(error)
                val detail = "${GoogleDriveApiErrorPolicy.message(category)}：${error.message ?: error.javaClass.simpleName}"
                when (GoogleDriveCandidateFailurePolicy.disposition(category)) {
                    GoogleDriveCandidateFailureDisposition.BLOCK_QUEUE -> {
                        blockedCategory = category
                        blockedMessage = "$detail。売上JSONは永久失敗にせず、再開可能な状態で保持しています"
                        break
                    }
                    GoogleDriveCandidateFailureDisposition.RETRY_BATCH -> {
                        markFailure(candidate, category, detail)
                        retries += 1
                        retryRecommended = true
                        break
                    }
                    GoogleDriveCandidateFailureDisposition.PERMANENT_ITEM -> {
                        markFailure(candidate, category, detail)
                        permanentFailures += 1
                    }
                }
            }
        }
        val result = GoogleDriveDirectUploadRunResult(
            uploadedCount = uploaded,
            duplicateCount = duplicates,
            retryCount = retries,
            permanentFailureCount = permanentFailures,
            retryRecommended = retryRecommended,
            blockedCategory = blockedCategory,
            blockedMessage = blockedMessage,
        )
        GoogleDriveDirectUploadStatusStore(appContext).complete(result)
        return result
    }

    fun retryPermanentFailures(): Int {
        ensureSchema()
        RegisterDatabase(appContext).use { helper ->
            return helper.writableDatabase.update(
                TABLE,
                ContentValues().apply {
                    put("status", STATUS_RETRY)
                    put("attempt_count", 0)
                    put("next_attempt_at", 0)
                    putNull("last_error")
                    put("updated_at", System.currentTimeMillis())
                },
                "status=?",
                arrayOf(STATUS_PERMANENT_FAILED),
            )
        }
    }

    private fun ensureFolder(
        client: GoogleDriveRestClient,
        name: String,
        parentId: String,
        properties: Map<String, String>,
    ): String {
        val query = buildString {
            append(parentQuery(parentId))
            append(" and mimeType='${GoogleDriveRestClient.FOLDER_MIME}'")
            properties.forEach { (key, value) -> append(propertyQuery(key, value)) }
        }
        return client.findOne(query)?.id
            ?: client.createFolder(name, parentId, properties).id
    }

    private fun loadCandidates(limit: Int): List<GoogleDriveUploadCandidate> {
        RegisterDatabase(appContext).use { helper ->
            val db = helper.writableDatabase
            ensureSchema(db)
            return db.rawQuery(
                """
                SELECT o.id, o.event_id, o.object_key, o.status, COALESCE(d.attempt_count, 0)
                FROM sync_outbox o
                LEFT JOIN $TABLE d ON d.outbox_id=o.id
                WHERE o.status IN ('STAGED','SENT')
                  AND (d.status IS NULL OR (d.status='$STATUS_RETRY' AND d.next_attempt_at <= ?))
                ORDER BY o.created_at ASC, o.id ASC
                LIMIT ?
                """.trimIndent(),
                arrayOf(System.currentTimeMillis().toString(), limit.coerceIn(1, 500).toString()),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            GoogleDriveUploadCandidate(
                                outboxId = cursor.getLong(0),
                                eventId = cursor.getString(1),
                                objectKey = cursor.getString(2),
                                outboxStatus = cursor.getString(3),
                                directAttemptCount = cursor.getInt(4),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun markSuccess(
        candidate: GoogleDriveUploadCandidate,
        remote: GoogleDriveRemoteFile,
        sha256: String,
    ) {
        RegisterDatabase(appContext).use { helper ->
            val db = helper.writableDatabase
            ensureSchema(db)
            db.insertWithOnConflict(
                TABLE,
                null,
                ContentValues().apply {
                    put("outbox_id", candidate.outboxId)
                    put("event_id", candidate.eventId)
                    put("file_id", remote.id)
                    put("content_sha256", sha256)
                    put("modified_time", remote.modifiedTime)
                    put("status", STATUS_SUCCEEDED)
                    put("attempt_count", candidate.directAttemptCount)
                    put("next_attempt_at", 0)
                    putNull("last_error")
                    put("updated_at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            if (!OutboxDeliverySettingsStore(appContext).load().enabled && candidate.outboxStatus != SyncOutboxStatus.SENT.name) {
                db.update(
                    "sync_outbox",
                    ContentValues().apply {
                        put("status", SyncOutboxStatus.SENT.name)
                        put("next_attempt_at", 0)
                        putNull("last_error")
                        put("updated_at", System.currentTimeMillis())
                    },
                    "id=? AND status='STAGED'",
                    arrayOf(candidate.outboxId.toString()),
                )
            }
        }
        OutboxDeliveryAudit.record(
            appContext,
            "SYNC_OUTBOX_DRIVE_API_SENT",
            "${candidate.eventId} / fileId=${remote.id} / sha256=$sha256",
            candidate.outboxId,
        )
    }

    private fun markFailure(
        candidate: GoogleDriveUploadCandidate,
        category: GoogleDriveApiFailureCategory,
        detail: String,
    ) {
        val attempts = candidate.directAttemptCount + 1
        val permanent = !category.retryable || attempts >= MAX_ATTEMPTS
        RegisterDatabase(appContext).use { helper ->
            val db = helper.writableDatabase
            ensureSchema(db)
            db.insertWithOnConflict(
                TABLE,
                null,
                ContentValues().apply {
                    put("outbox_id", candidate.outboxId)
                    put("event_id", candidate.eventId)
                    put("status", if (permanent) STATUS_PERMANENT_FAILED else STATUS_RETRY)
                    put("attempt_count", attempts)
                    put(
                        "next_attempt_at",
                        if (permanent) Long.MAX_VALUE else System.currentTimeMillis() + retryDelay(attempts),
                    )
                    put("last_error", detail.take(500))
                    put("updated_at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            if (permanent && !OutboxDeliverySettingsStore(appContext).load().enabled) {
                db.update(
                    "sync_outbox",
                    ContentValues().apply {
                        put("status", SyncOutboxStatus.FAILED.name)
                        put("last_error", detail.take(500))
                        put("updated_at", System.currentTimeMillis())
                    },
                    "id=? AND status='STAGED'",
                    arrayOf(candidate.outboxId.toString()),
                )
            }
        }
        OutboxDeliveryAudit.record(
            appContext,
            "SYNC_OUTBOX_DRIVE_API_FAILED",
            "${candidate.eventId} / category=${category.name} / permanent=$permanent / $detail",
            candidate.outboxId,
        )
    }

    private fun ensureSchema() {
        RegisterDatabase(appContext).use { ensureSchema(it.writableDatabase) }
    }

    private fun ensureSchema(db: SQLiteDatabase) {
        JournalOutboxSchema.ensureCore(db)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE (
                outbox_id INTEGER PRIMARY KEY NOT NULL,
                event_id TEXT NOT NULL UNIQUE,
                file_id TEXT,
                content_sha256 TEXT,
                modified_time TEXT,
                status TEXT NOT NULL,
                attempt_count INTEGER NOT NULL DEFAULT 0,
                next_attempt_at INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(outbox_id) REFERENCES sync_outbox(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_drive_api_upload_status ON $TABLE(status, next_attempt_at, updated_at)")
    }

    private fun localFile(objectKey: String): File {
        OutboxDeliveryPathPolicy.segments(objectKey)
        val root = File(appContext.filesDir, "drive-sync-staging").canonicalFile
        val file = File(root, objectKey).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) { "ローカル同期パスが不正です" }
        return file
    }

    private fun parentQuery(parentId: String): String =
        "'${GoogleDriveRestClient.quoted(parentId)}' in parents and trashed=false"

    private fun propertyQuery(key: String, value: String): String =
        " and appProperties has { key='${GoogleDriveRestClient.quoted(key)}' and value='${GoogleDriveRestClient.quoted(value)}' }"

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

    private fun retryDelay(attempt: Int): Long = when {
        attempt <= 1 -> 60_000L
        attempt == 2 -> 5 * 60_000L
        attempt == 3 -> 30 * 60_000L
        attempt <= 6 -> 2 * 60 * 60_000L
        else -> 6 * 60 * 60_000L
    }

    companion object {
        const val APP = "tsuguregi"
        const val TABLE = "drive_api_uploads"
        const val STATUS_RETRY = "RETRY"
        const val STATUS_SUCCEEDED = "SUCCEEDED"
        const val STATUS_PERMANENT_FAILED = "PERMANENT_FAILED"
        const val MAX_ATTEMPTS = 10
        const val MAX_JSON_BYTES = 20 * 1024 * 1024
    }
}

class GoogleDriveDirectUploadWorker(context: Context, parameters: WorkerParameters) :
    Worker(context, parameters) {
    override fun doWork(): Result {
        val diagnosticLog = GoogleDriveDiagnosticLogStore(applicationContext)
        if (GoogleDriveAccountStore(applicationContext).load().email == null) {
            diagnosticLog.append("UPLOAD_WORKER", "SKIPPED", "Googleアカウント未登録")
            return Result.success()
        }
        diagnosticLog.append("UPLOAD_WORKER", "STARTED", "Drive API直接送信開始")
        return runCatching {
            val token = GoogleDriveAccessTokenProvider.acquire(applicationContext)
            GoogleDriveDirectUploadCoordinator(applicationContext).process(token, 100)
        }.fold(
            onSuccess = { runResult ->
                diagnosticLog.append(
                    "UPLOAD_WORKER",
                    when {
                        runResult.blockedCategory != null -> "BLOCKED"
                        runResult.retryRecommended -> "RETRY"
                        else -> "SUCCESS"
                    },
                    "送信=${runResult.uploadedCount},既存=${runResult.duplicateCount},再試行=${runResult.retryCount},永久失敗=${runResult.permanentFailureCount},停止=${runResult.blockedCategory?.name ?: "なし"}",
                )
                if (runResult.retryRecommended) Result.retry() else Result.success()
            },
            onFailure = { error ->
                val category = GoogleDriveApiErrorPolicy.classify(error)
                diagnosticLog.append(
                    "UPLOAD_WORKER",
                    category.name,
                    error.message ?: error.javaClass.simpleName,
                )
                GoogleDriveDirectUploadStatusStore(applicationContext).failed(
                    category,
                    "${GoogleDriveApiErrorPolicy.message(category)}：${error.message ?: error.javaClass.simpleName}",
                )
                if (category.retryable) Result.retry() else Result.success()
            },
        )
    }
}

object GoogleDriveDirectUploadScheduler {
    private const val PERIODIC_NAME = "tsuguregi-drive-api-upload-periodic"
    private const val IMMEDIATE_NAME = "tsuguregi-drive-api-upload-immediate"

    fun ensurePeriodic(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<GoogleDriveDirectUploadWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun enqueueNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<GoogleDriveDirectUploadWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }
}

class GoogleDriveDirectUploadBootstrapProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return false
        runCatching {
            GoogleDriveDirectUploadCoordinator(appContext)
            GoogleDriveDirectUploadScheduler.ensurePeriodic(appContext)
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
