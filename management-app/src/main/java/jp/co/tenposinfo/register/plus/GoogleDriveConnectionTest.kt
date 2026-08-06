package jp.co.tenposinfo.register.plus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

enum class GoogleDriveConnectionTestStatus {
    NOT_RUN,
    RUNNING,
    SUCCEEDED,
    NOT_FOUND,
    FAILED,
}

data class GoogleDriveConnectionTestState(
    val status: GoogleDriveConnectionTestStatus = GoogleDriveConnectionTestStatus.NOT_RUN,
    val testId: String? = null,
    val fileId: String? = null,
    val fileName: String? = null,
    val sourceCreatedAt: Long? = null,
    val checkedAt: Long? = null,
    val message: String = "つぐレジ接続テストはまだ実行されていません",
)

object GoogleDriveConnectionTestContract {
    const val SCHEMA = "jp.co.tenposinfo.tsuguregi.drive-connection-test"
    const val APP = "tsuguregi"
    const val ROLE = "connection-test"
    const val SOURCE_APP = "tsuguregi-register"
    const val PURPOSE = "oauth-cross-app-visibility"
    const val SLOT = "default"
    const val MAX_BYTES = 64 * 1024
    val ALLOWED_KEYS = setOf(
        "schema",
        "testId",
        "sourceApp",
        "purpose",
        "createdAt",
        "containsSalesData",
    )
}

class GoogleDriveConnectionTestStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "tsuguregi_plus_drive_connection_test",
        Context.MODE_PRIVATE,
    )

    fun load(): GoogleDriveConnectionTestState {
        val status = runCatching {
            GoogleDriveConnectionTestStatus.valueOf(
                preferences.getString("status", GoogleDriveConnectionTestStatus.NOT_RUN.name)!!,
            )
        }.getOrDefault(GoogleDriveConnectionTestStatus.NOT_RUN)
        return GoogleDriveConnectionTestState(
            status = status,
            testId = preferences.getString("test_id", null),
            fileId = preferences.getString("file_id", null),
            fileName = preferences.getString("file_name", null),
            sourceCreatedAt = preferences.getLong("source_created_at", 0L).takeIf { it > 0L },
            checkedAt = preferences.getLong("checked_at", 0L).takeIf { it > 0L },
            message = preferences.getString("message", null)
                ?: "つぐレジ接続テストはまだ実行されていません",
        )
    }

    fun running(): GoogleDriveConnectionTestState = save(
        load().copy(
            status = GoogleDriveConnectionTestStatus.RUNNING,
            message = "つぐレジが作成した接続テストJSONを検索しています",
        ),
    )

    fun succeeded(
        remote: GoogleDriveConnectionTestRemoteFile,
        testId: String,
        sourceCreatedAt: Long,
    ): GoogleDriveConnectionTestState = save(
        GoogleDriveConnectionTestState(
            status = GoogleDriveConnectionTestStatus.SUCCEEDED,
            testId = testId,
            fileId = remote.id,
            fileName = remote.name,
            sourceCreatedAt = sourceCreatedAt,
            checkedAt = System.currentTimeMillis(),
            message = "つぐレジの接続テストJSONを検索・取得・検証できました",
        ),
    )

    fun notFound(): GoogleDriveConnectionTestState = save(
        load().copy(
            status = GoogleDriveConnectionTestStatus.NOT_FOUND,
            checkedAt = System.currentTimeMillis(),
            message = "接続テストJSONが見つかりません。同じGoogleアカウントか、drive.fileで別OAuthクライアントから参照できるかを確認してください",
        ),
    )

    fun failed(message: String): GoogleDriveConnectionTestState = save(
        load().copy(
            status = GoogleDriveConnectionTestStatus.FAILED,
            checkedAt = System.currentTimeMillis(),
            message = message.take(500),
        ),
    )

    private fun save(state: GoogleDriveConnectionTestState): GoogleDriveConnectionTestState {
        preferences.edit()
            .putString("status", state.status.name)
            .putString("test_id", state.testId)
            .putString("file_id", state.fileId)
            .putString("file_name", state.fileName)
            .putLong("source_created_at", state.sourceCreatedAt ?: 0L)
            .putLong("checked_at", state.checkedAt ?: 0L)
            .putString("message", state.message)
            .apply()
        return state
    }
}

data class GoogleDriveConnectionTestRemoteFile(
    val id: String,
    val name: String,
    val modifiedTime: String?,
    val size: Long?,
    val appProperties: Map<String, String>,
)

class GoogleDriveConnectionTestRestClient(private val accessToken: String) {
    fun findLatest(): GoogleDriveConnectionTestRemoteFile? {
        val query = "mimeType='application/json' and trashed=false" +
            propertyQuery("app", GoogleDriveConnectionTestContract.APP) +
            propertyQuery("role", GoogleDriveConnectionTestContract.ROLE) +
            propertyQuery("sourceApp", GoogleDriveConnectionTestContract.SOURCE_APP) +
            propertyQuery("slot", GoogleDriveConnectionTestContract.SLOT)
        val fields = "files(id,name,modifiedTime,size,appProperties)"
        val url = buildString {
            append(DRIVE_FILES_URL)
            append("?spaces=drive")
            append("&supportsAllDrives=false")
            append("&pageSize=10")
            append("&orderBy=").append(encode("modifiedTime desc"))
            append("&fields=").append(encode(fields))
            append("&q=").append(encode(query))
        }
        val files = JSONObject(executeBytes("GET", url).toString(Charsets.UTF_8))
            .optJSONArray("files") ?: JSONArray()
        if (files.length() == 0) return null
        val item = files.getJSONObject(0)
        val properties = linkedMapOf<String, String>()
        item.optJSONObject("appProperties")?.let { source ->
            source.keys().forEach { key -> properties[key] = source.optString(key) }
        }
        return GoogleDriveConnectionTestRemoteFile(
            id = item.getString("id"),
            name = item.optString("name").ifBlank { "接続テスト.json" },
            modifiedTime = item.optString("modifiedTime").takeIf(String::isNotBlank),
            size = item.optString("size").toLongOrNull(),
            appProperties = properties,
        )
    }

    fun download(fileId: String): ByteArray = executeBytes(
        method = "GET",
        url = "$DRIVE_FILES_URL/${encodePath(fileId)}?alt=media&supportsAllDrives=false",
    )

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
        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
        fun encodePath(value: String): String = value.replace("/", "%2F")
        fun quoted(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")
    }
}

class GoogleDriveConnectionTestVerifier(context: Context) {
    private val appContext = context.applicationContext
    private val store = GoogleDriveConnectionTestStore(appContext)

    fun searchAndVerify(accessToken: String): GoogleDriveConnectionTestState {
        store.running()
        val client = GoogleDriveConnectionTestRestClient(accessToken)
        val remote = client.findLatest() ?: return store.notFound()
        require(remote.size == null || remote.size in 1..GoogleDriveConnectionTestContract.MAX_BYTES) {
            "接続テストJSONのサイズが不正です"
        }
        val bytes = client.download(remote.id)
        require(bytes.isNotEmpty() && bytes.size <= GoogleDriveConnectionTestContract.MAX_BYTES) {
            "接続テストJSONを取得できないか、サイズが不正です"
        }
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        val keys = mutableSetOf<String>()
        val iterator = root.keys()
        while (iterator.hasNext()) keys += iterator.next()
        require(keys == GoogleDriveConnectionTestContract.ALLOWED_KEYS) {
            "接続テストJSONに想定外の項目があります"
        }
        require(root.getString("schema") == GoogleDriveConnectionTestContract.SCHEMA) {
            "接続テストschemaが不正です"
        }
        val testId = root.getString("testId")
        require(testId.isNotBlank() && testId == remote.appProperties["testId"]) {
            "接続テストIDが一致しません"
        }
        require(root.getString("sourceApp") == GoogleDriveConnectionTestContract.SOURCE_APP) {
            "接続テストの作成元が不正です"
        }
        require(root.getString("purpose") == GoogleDriveConnectionTestContract.PURPOSE) {
            "接続テストの目的が不正です"
        }
        require(!root.getBoolean("containsSalesData")) {
            "接続テストJSONに売上データありの指定があります"
        }
        val createdAt = root.getLong("createdAt")
        require(createdAt > 0L) { "接続テスト作成日時が不正です" }
        return store.succeeded(remote, testId, createdAt)
    }
}
