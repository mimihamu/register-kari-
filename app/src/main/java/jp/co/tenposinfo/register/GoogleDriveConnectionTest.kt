package jp.co.tenposinfo.register

import android.content.Context
import org.json.JSONObject
import java.util.UUID

enum class GoogleDriveConnectionTestStatus {
    NOT_RUN,
    RUNNING,
    SUCCEEDED,
    FAILED,
}

data class GoogleDriveConnectionTestState(
    val status: GoogleDriveConnectionTestStatus = GoogleDriveConnectionTestStatus.NOT_RUN,
    val testId: String? = null,
    val fileId: String? = null,
    val fileName: String? = null,
    val updatedAt: Long? = null,
    val message: String = "接続テストはまだ実行されていません",
)

object GoogleDriveConnectionTestContract {
    const val SCHEMA = "jp.co.tenposinfo.tsuguregi.drive-connection-test"
    const val APP = "tsuguregi"
    const val ROLE = "connection-test"
    const val SOURCE_APP = "tsuguregi-register"
    const val PURPOSE = "oauth-cross-app-visibility"
    const val SLOT = "default"
    const val FILE_NAME = "つぐレジ_接続テスト.json"
}

class GoogleDriveConnectionTestStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "tsuguregi_drive_connection_test",
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
            updatedAt = preferences.getLong("updated_at", 0L).takeIf { it > 0L },
            message = preferences.getString("message", null)
                ?: "接続テストはまだ実行されていません",
        )
    }

    fun running(): GoogleDriveConnectionTestState = save(
        load().copy(
            status = GoogleDriveConnectionTestStatus.RUNNING,
            message = "売上を含まない接続テストJSONをGoogle Driveへ作成しています",
        ),
    )

    fun succeeded(
        testId: String,
        remote: GoogleDriveRemoteFile,
        updatedAt: Long,
    ): GoogleDriveConnectionTestState = save(
        GoogleDriveConnectionTestState(
            status = GoogleDriveConnectionTestStatus.SUCCEEDED,
            testId = testId,
            fileId = remote.id,
            fileName = remote.name,
            updatedAt = updatedAt,
            message = "接続テストJSONを作成しました。つぐレジ＋で検索してください",
        ),
    )

    fun failed(message: String): GoogleDriveConnectionTestState = save(
        load().copy(
            status = GoogleDriveConnectionTestStatus.FAILED,
            updatedAt = System.currentTimeMillis(),
            message = message.take(500),
        ),
    )

    private fun save(state: GoogleDriveConnectionTestState): GoogleDriveConnectionTestState {
        preferences.edit()
            .putString("status", state.status.name)
            .putString("test_id", state.testId)
            .putString("file_id", state.fileId)
            .putString("file_name", state.fileName)
            .putLong("updated_at", state.updatedAt ?: 0L)
            .putString("message", state.message)
            .apply()
        return state
    }
}

class GoogleDriveConnectionTestCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val store = GoogleDriveConnectionTestStore(appContext)

    fun createOrUpdate(accessToken: String): GoogleDriveConnectionTestState {
        store.running()
        val testId = UUID.randomUUID().toString()
        val updatedAt = System.currentTimeMillis()
        val bytes = JSONObject()
            .put("schema", GoogleDriveConnectionTestContract.SCHEMA)
            .put("testId", testId)
            .put("sourceApp", GoogleDriveConnectionTestContract.SOURCE_APP)
            .put("purpose", GoogleDriveConnectionTestContract.PURPOSE)
            .put("createdAt", updatedAt)
            .put("containsSalesData", false)
            .toString(2)
            .toByteArray(Charsets.UTF_8)
        val properties = mapOf(
            "app" to GoogleDriveConnectionTestContract.APP,
            "role" to GoogleDriveConnectionTestContract.ROLE,
            "sourceApp" to GoogleDriveConnectionTestContract.SOURCE_APP,
            "purpose" to GoogleDriveConnectionTestContract.PURPOSE,
            "slot" to GoogleDriveConnectionTestContract.SLOT,
            "testId" to testId,
        )
        val client = GoogleDriveRestClient(accessToken)
        val query = buildString {
            append("'root' in parents and trashed=false and mimeType='application/json'")
            append(propertyQuery("app", GoogleDriveConnectionTestContract.APP))
            append(propertyQuery("role", GoogleDriveConnectionTestContract.ROLE))
            append(propertyQuery("sourceApp", GoogleDriveConnectionTestContract.SOURCE_APP))
            append(propertyQuery("slot", GoogleDriveConnectionTestContract.SLOT))
        }
        val existing = client.findOne(query)
        val remote = if (existing == null) {
            client.createJson(
                name = GoogleDriveConnectionTestContract.FILE_NAME,
                parentId = "root",
                bytes = bytes,
                appProperties = properties,
            )
        } else {
            client.updateJson(
                fileId = existing.id,
                bytes = bytes,
                appProperties = properties,
            )
        }
        return store.succeeded(testId, remote, updatedAt)
    }

    private fun propertyQuery(key: String, value: String): String =
        " and appProperties has { key='${GoogleDriveRestClient.quoted(key)}' and value='${GoogleDriveRestClient.quoted(value)}' }"
}
