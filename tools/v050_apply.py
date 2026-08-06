from pathlib import Path
import textwrap


def read(path: str) -> str:
    return Path(path).read_text(encoding="utf-8")


def write(path: str, content: str) -> None:
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    content = read(path)
    if old not in content:
        raise RuntimeError(f"expected text not found in {path}: {old[:120]!r}")
    write(path, content.replace(old, new, 1))


def replace_all(path: str, old: str, new: str) -> None:
    content = read(path)
    if old not in content:
        return
    write(path, content.replace(old, new))


register_connection_test = r'''package jp.co.tenposinfo.register

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
'''

plus_connection_test = r'''package jp.co.tenposinfo.register.plus

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
'''

app_test = r'''package jp.co.tenposinfo.register

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V050GoogleDriveCrossAppConnectionTest {
    @Test
    fun registerCreatesSalesFreeReusableConnectionTestJson() {
        val root = File("src/main/java/jp/co/tenposinfo/register")
        val source = File(root, "GoogleDriveConnectionTest.kt").readText()
        val account = File(root, "GoogleDriveAccountActivity.kt").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File("../.github/workflows/build-apk.yml").readText()
        val docs = File("../docs/V0.50_GOOGLE_DRIVE_CROSS_APP_CONNECTION_TEST.md").readText()

        for (token in listOf(
            "GoogleDriveConnectionTestCoordinator",
            "GoogleDriveConnectionTestContract",
            "oauth-cross-app-visibility",
            "connection-test",
            "containsSalesData",
            "client.updateJson",
            "slot",
        )) assertTrue(source.contains(token))
        assertTrue(account.contains("接続テストJSONを作成"))
        assertTrue(account.contains("createConnectionTest"))
        assertFalse(source.contains("SalesJournalJsonContract"))
        assertFalse(source.contains("putString(\"access_token\""))
        assertFalse(source.contains("putString(\"refresh_token\""))
        assertTrue(build.contains("versionCode = 80"))
        assertTrue(build.contains("versionName = \"0.50.0-dev.1\""))
        assertTrue(workflow.contains("V050GoogleDriveCrossAppConnectionTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_v0.50.0_dev1_drive_cross_app_connection_test_debug.apk"))
        assertTrue(docs.contains("実売上を使用しない"))
        assertFalse(File("../tools/v050_apply.py").exists())
        assertFalse(File("../.github/workflows/v050-apply-temp.yml").exists())
        assertFalse(File("../tools/build-apk-v050.generated.yml").exists())
    }
}
'''

plus_test = r'''package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V050GoogleDriveCrossAppConnectionTest {
    @Test
    fun plusSearchesDownloadsAndValidatesRegisterConnectionTestWithoutImportingIt() {
        val root = File("src/main/java/jp/co/tenposinfo/register/plus")
        val source = File(root, "GoogleDriveConnectionTest.kt").readText()
        val account = File(root, "GoogleDriveAccountActivity.kt").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File("../.github/workflows/build-apk.yml").readText()
        val docs = File("../docs/V0.50_GOOGLE_DRIVE_CROSS_APP_CONNECTION_TEST.md").readText()

        for (token in listOf(
            "GoogleDriveConnectionTestVerifier",
            "findLatest",
            "download",
            "ALLOWED_KEYS",
            "containsSalesData",
            "NOT_FOUND",
            "modifiedTime desc",
        )) assertTrue(source.contains(token))
        assertTrue(account.contains("つぐレジ接続テストを検索"))
        assertTrue(account.contains("verifyConnectionTest"))
        assertFalse(source.contains("SalesJournalImportRepository"))
        assertFalse(source.contains("drive_sync_files"))
        assertFalse(source.contains("putString(\"access_token\""))
        assertFalse(source.contains("putString(\"refresh_token\""))
        assertTrue(build.contains("versionCode = 9"))
        assertTrue(build.contains("versionName = \"0.9.0-dev.1\""))
        assertTrue(workflow.contains("V050GoogleDriveCrossAppConnectionTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_PLUS_v0.9.0_dev1_drive_cross_app_connection_test_debug.apk"))
        assertTrue(workflow.contains("TSUGUREGI-v0.50.0-dev1-drive-cross-app-connection-test-apks"))
        assertTrue(docs.contains("別Android OAuthクライアント"))
        assertFalse(File("../tools/v050_apply.py").exists())
        assertFalse(File("../.github/workflows/v050-apply-temp.yml").exists())
        assertFalse(File("../tools/build-apk-v050.generated.yml").exists())
    }
}
'''

docs = r'''# v0.50 Google Drive アプリ間接続テスト

## 目的

つぐレジがGoogle Driveへ作成したファイルを、別applicationId・別Android OAuthクライアントのつぐレジ＋から`drive.file`スコープで参照できるかを、実売上を使用しない専用JSONで実機確認する。

## 操作手順

1. つぐレジでGoogleアカウントを接続する。
2. `Google Driveアカウント連携`画面の`接続テストJSONを作成`を押す。
3. 同じGoogleアカウントをつぐレジ＋へ接続する。
4. つぐレジ＋の`Google Driveアカウント`画面で`つぐレジ接続テストを検索`を押す。
5. 検索・取得・内容検証が成功するか確認する。

## テストファイル

- ファイル名: `つぐレジ_接続テスト.json`
- 保存先: Google Driveのマイドライブ直下
- `appProperties.role`: `connection-test`
- `appProperties.sourceApp`: `tsuguregi-register`
- `appProperties.slot`: `default`
- schema: `jp.co.tenposinfo.tsuguregi.drive-connection-test`

同じ固定ファイルを更新するため、繰り返し実行してもファイルが増え続けない。

JSONに含めるのはテストID、作成元、目的、作成日時、`containsSalesData=false`だけである。取引、商品、金額、税、支払、顧客などの実売上データは含めない。

## つぐレジ＋の検証

つぐレジ＋は`role=connection-test`だけを検索し、次を検証する。

- ファイルを列挙できること
- JSON本文を取得できること
- schema、testId、sourceApp、purposeが一致すること
- appPropertiesのtestIdと本文のtestIdが一致すること
- 想定外のJSON項目がないこと
- `containsSalesData=false`であること

接続テストJSONは売上取込処理へ渡さず、`drive_sync_files`や売上集計DBへ登録しない。

## 失敗時の見方

- 見つからない: Googleアカウント相違、つぐレジ側未作成、または別Android OAuthクライアントから`drive.file`で列挙できない可能性
- 401: 再認可が必要
- 403: Drive API無効、OAuth設定不備、権限不足の可能性
- 429/5xx: Google Drive側の一時エラー
- JSON不正: ファイル内容またはappPropertiesが想定契約と不一致

`drive.file`のアプリ間可視性はコードだけでは確定できないため、成功したと断定せず実機結果を記録する。

## セキュリティ

- アクセストークン・更新トークンを保存しない
- Authorizationヘッダーを画面やログへ表示しない
- 実売上JSONを接続テストに使用しない
- テストファイルを売上取込しない
- 共有ドライブは対象外
'''

release_notes = r'''# v0.50 リリースノート

## バージョン

- つぐレジ: `0.50.0-dev.1` / versionCode 80
- つぐレジ＋: `0.9.0-dev.1` / versionCode 9
- つぐレジ CD: `0.14.0-dev.1` / versionCode 7

## 追加

- つぐレジの売上なしGoogle Drive接続テストJSON作成
- 固定テストファイルの更新によるファイル増加防止
- つぐレジ＋の接続テスト検索・取得・契約検証
- 別Android OAuthクライアント間の`drive.file`可視性確認支援
- テストID、fileId、実行結果の画面表示

## 既存機能への影響

- 売上ジャーナルの作成・アップロード処理は変更しない
- つぐレジ＋の売上差分同期・取込処理は変更しない
- 接続テストJSONを売上DBへ登録しない
- SQLiteスキーマは変更しない
- SAF互換フォルダ方式を維持
- アクセストークン・更新トークン非保存を維持

## 実機未確認

Google OAuth、Drive APIへのテストファイル作成、別Android OAuthクライアントのつぐレジ＋からの列挙・取得、`drive.file`可視性、本番署名OAuthは実機確認が必要。
'''

write("app/src/main/java/jp/co/tenposinfo/register/GoogleDriveConnectionTest.kt", register_connection_test)
write("management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveConnectionTest.kt", plus_connection_test)
write("app/src/test/java/jp/co/tenposinfo/register/V050GoogleDriveCrossAppConnectionTest.kt", app_test)
write("management-app/src/test/java/jp/co/tenposinfo/register/plus/V050GoogleDriveCrossAppConnectionTest.kt", plus_test)
write("docs/V0.50_GOOGLE_DRIVE_CROSS_APP_CONNECTION_TEST.md", docs)
write("docs/V0.50_RELEASE_NOTES.md", release_notes)

replace_once(
    "app/build.gradle.kts",
    'versionCode = 79\n        versionName = "0.49.0-dev.1"',
    'versionCode = 80\n        versionName = "0.50.0-dev.1"',
)
replace_once(
    "management-app/build.gradle.kts",
    'versionCode = 8\n        versionName = "0.8.0-dev.1"',
    'versionCode = 9\n        versionName = "0.9.0-dev.1"',
)

register_account = "app/src/main/java/jp/co/tenposinfo/register/GoogleDriveAccountActivity.kt"
replace_once(
    register_account,
    "    private val uploadStatus = mutableStateOf(GoogleDriveDirectUploadStatus())\n",
    "    private val uploadStatus = mutableStateOf(GoogleDriveDirectUploadStatus())\n"
    "    private val connectionTest = mutableStateOf(GoogleDriveConnectionTestState())\n",
)
replace_once(
    register_account,
    "        uploadStatus.value = GoogleDriveDirectUploadStatusStore(this).load()\n        configureRegisterSystemBars(window)",
    "        uploadStatus.value = GoogleDriveDirectUploadStatusStore(this).load()\n"
    "        connectionTest.value = GoogleDriveConnectionTestStore(this).load()\n"
    "        configureRegisterSystemBars(window)",
)
replace_once(
    register_account,
    "                        uploadStatus = uploadStatus.value,\n",
    "                        uploadStatus = uploadStatus.value,\n"
    "                        connectionTest = connectionTest.value,\n",
)
replace_once(
    register_account,
    "                        onRetry = ::retryFailed,\n",
    "                        onRetry = ::retryFailed,\n"
    "                        onConnectionTest = ::createConnectionTest,\n",
)
replace_once(
    register_account,
    "        uploadStatus.value = GoogleDriveDirectUploadStatusStore(this).load()\n    }\n\n    private fun authorize",
    "        uploadStatus.value = GoogleDriveDirectUploadStatusStore(this).load()\n"
    "        connectionTest.value = GoogleDriveConnectionTestStore(this).load()\n"
    "    }\n\n    private fun authorize",
)
replace_once(
    register_account,
    "    private fun retryFailed() {\n",
    '''    private fun createConnectionTest() {
        if (state.value.email == null || connectionTest.value.status == GoogleDriveConnectionTestStatus.RUNNING) return
        connectionTest.value = GoogleDriveConnectionTestStore(applicationContext).running()
        diagnosticLog.append("CONNECTION_TEST", "STARTED", "売上なし接続テストJSON作成")
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val token = GoogleDriveAccessTokenProvider.acquire(applicationContext)
                    GoogleDriveConnectionTestCoordinator(applicationContext).createOrUpdate(token)
                }
            }
            connectionTest.value = result.fold(
                onSuccess = { completed ->
                    diagnosticLog.append(
                        "CONNECTION_TEST",
                        "SUCCESS",
                        "testId=${completed.testId} / fileId=${completed.fileId}",
                    )
                    completed
                },
                onFailure = { error ->
                    val category = GoogleDriveApiErrorPolicy.classify(error)
                    diagnosticLog.append(
                        "CONNECTION_TEST",
                        category.name,
                        error.message ?: error.javaClass.simpleName,
                    )
                    GoogleDriveConnectionTestStore(applicationContext).failed(
                        "${GoogleDriveApiErrorPolicy.message(category)}：${error.message ?: error.javaClass.simpleName}",
                    )
                },
            )
        }
    }

    private fun retryFailed() {
''',
)
replace_once(
    register_account,
    "    uploadStatus: GoogleDriveDirectUploadStatus,\n",
    "    uploadStatus: GoogleDriveDirectUploadStatus,\n"
    "    connectionTest: GoogleDriveConnectionTestState,\n",
)
replace_once(
    register_account,
    "    onRetry: () -> Unit,\n",
    "    onRetry: () -> Unit,\n"
    "    onConnectionTest: () -> Unit,\n",
)
replace_once(
    register_account,
    '''        OutlinedButton(
            onClick = onDiagnostics,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text("診断・ログ") }

        Card(modifier = Modifier.fillMaxWidth()) {''',
    '''        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onDiagnostics,
                modifier = Modifier.weight(1f).height(48.dp),
            ) { Text("診断・ログ") }
            Button(
                onClick = onConnectionTest,
                enabled = state.email != null && connectionTest.status != GoogleDriveConnectionTestStatus.RUNNING,
                modifier = Modifier.weight(1f).height(48.dp),
            ) { Text("接続テストJSONを作成") }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("つぐレジ→つぐレジ＋ 接続テスト", fontWeight = FontWeight.Bold)
                Text(connectionTest.message)
                connectionTest.testId?.let { Text("テストID：$it") }
                connectionTest.fileName?.let { Text("ファイル：$it") }
                connectionTest.fileId?.let { Text("fileId：$it") }
                Text("商品名・金額・税・支払などの売上情報は含めません。")
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {''',
)
replace_once(register_account, "Text(\"v0.47 診断対応済み直接同期\"", "Text(\"v0.50 アプリ間接続テスト対応\"")

plus_account = "management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveAccountActivity.kt"
replace_once(
    plus_account,
    "    private val syncStatus = mutableStateOf(GoogleDriveDirectSyncStatus())\n",
    "    private val syncStatus = mutableStateOf(GoogleDriveDirectSyncStatus())\n"
    "    private val connectionTest = mutableStateOf(GoogleDriveConnectionTestState())\n",
)
replace_once(
    plus_account,
    "                        syncStatus = syncStatus.value,\n",
    "                        syncStatus = syncStatus.value,\n"
    "                        connectionTest = connectionTest.value,\n",
)
replace_once(
    plus_account,
    "                        onForceSync = { synchronize(forceReimport = true) },\n",
    "                        onForceSync = { synchronize(forceReimport = true) },\n"
    "                        onConnectionTest = ::verifyConnectionTest,\n",
)
replace_once(
    plus_account,
    "        syncStatus.value = GoogleDriveDirectSyncStatusStore(this).load()\n    }\n",
    "        syncStatus.value = GoogleDriveDirectSyncStatusStore(this).load()\n"
    "        connectionTest.value = GoogleDriveConnectionTestStore(this).load()\n"
    "    }\n",
)
replace_once(
    plus_account,
    "    private fun setAutoSync(enabled: Boolean) {\n",
    '''    private fun verifyConnectionTest() {
        if (state.value.email == null || connectionTest.value.status == GoogleDriveConnectionTestStatus.RUNNING) return
        connectionTest.value = GoogleDriveConnectionTestStore(applicationContext).running()
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val token = GoogleDriveSyncAccessTokenProvider.acquire(applicationContext)
                    GoogleDriveConnectionTestVerifier(applicationContext).searchAndVerify(token)
                }
            }
            connectionTest.value = result.fold(
                onSuccess = { it },
                onFailure = { error ->
                    val category = GoogleDriveSyncErrorPolicy.classify(error)
                    GoogleDriveConnectionTestStore(applicationContext).failed(
                        "${GoogleDriveSyncErrorPolicy.message(category)}：${error.message ?: error.javaClass.simpleName}",
                    )
                },
            )
        }
    }

    private fun setAutoSync(enabled: Boolean) {
''',
)
replace_once(
    plus_account,
    "    syncStatus: GoogleDriveDirectSyncStatus,\n",
    "    syncStatus: GoogleDriveDirectSyncStatus,\n"
    "    connectionTest: GoogleDriveConnectionTestState,\n",
)
replace_once(
    plus_account,
    "    onForceSync: () -> Unit,\n",
    "    onForceSync: () -> Unit,\n"
    "    onConnectionTest: () -> Unit,\n",
)
replace_once(
    plus_account,
    '''        Button(
            onClick = onConnect,''',
    '''        Button(
            onClick = onConnectionTest,
            enabled = state.email != null && !syncStatus.running && connectionTest.status != GoogleDriveConnectionTestStatus.RUNNING,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("つぐレジ接続テストを検索") }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("つぐレジ→つぐレジ＋ 接続テスト", fontWeight = FontWeight.Bold)
                Text(connectionTest.message)
                connectionTest.testId?.let { Text("テストID：$it") }
                connectionTest.fileName?.let { Text("ファイル：$it") }
                connectionTest.fileId?.let { Text("fileId：$it") }
                Text("接続テストJSONは売上取込・集計の対象にしません。")
            }
        }

        Button(
            onClick = onConnect,''',
)
replace_once(plus_account, "Text(\"v0.45 差分同期仕様\"", "Text(\"v0.50 アプリ間接続テスト対応\"")

for path in Path("app/src/test").rglob("*.kt"):
    content = path.read_text(encoding="utf-8")
    content = content.replace('versionCode = 79', 'versionCode = 80')
    content = content.replace('versionName = \\"0.49.0-dev.1\\"', 'versionName = \\"0.50.0-dev.1\\"')
    content = content.replace('TSUGUREGI_v0.49.0_dev1_plus_drive_setup_diagnostics_debug.apk', 'TSUGUREGI_v0.50.0_dev1_drive_cross_app_connection_test_debug.apk')
    content = content.replace('TSUGUREGI-v0.49.0-dev1-plus-drive-setup-diagnostics-apks', 'TSUGUREGI-v0.50.0-dev1-drive-cross-app-connection-test-apks')
    path.write_text(content, encoding="utf-8")

for path in Path("management-app/src/test").rglob("*.kt"):
    content = path.read_text(encoding="utf-8")
    content = content.replace('versionCode = 79', 'versionCode = 80')
    content = content.replace('versionName = \\"0.49.0-dev.1\\"', 'versionName = \\"0.50.0-dev.1\\"')
    content = content.replace('versionCode = 8', 'versionCode = 9')
    content = content.replace('versionName = \\"0.8.0-dev.1\\"', 'versionName = \\"0.9.0-dev.1\\"')
    content = content.replace('TSUGUREGI_v0.49.0_dev1_plus_drive_setup_diagnostics_debug.apk', 'TSUGUREGI_v0.50.0_dev1_drive_cross_app_connection_test_debug.apk')
    content = content.replace('TSUGUREGI_PLUS_v0.8.0_dev1_drive_setup_diagnostics_debug.apk', 'TSUGUREGI_PLUS_v0.9.0_dev1_drive_cross_app_connection_test_debug.apk')
    content = content.replace('TSUGUREGI_PLUS_v0.7.0_dev1', 'TSUGUREGI_PLUS_v0.9.0_dev1')
    content = content.replace('TSUGUREGI-v0.49.0-dev1-plus-drive-setup-diagnostics-apks', 'TSUGUREGI-v0.50.0-dev1-drive-cross-app-connection-test-apks')
    path.write_text(content, encoding="utf-8")

workflow = read(".github/workflows/build-apk.yml")
workflow = workflow.replace("Verify cumulative v0.14-v0.49 sources", "Verify cumulative v0.14-v0.50 sources")
workflow = workflow.replace("versionCode = 79", "versionCode = 80")
workflow = workflow.replace('versionName = "0.49.0-dev.1"', 'versionName = "0.50.0-dev.1"')
workflow = workflow.replace("versionCode = 8", "versionCode = 9")
workflow = workflow.replace('versionName = "0.8.0-dev.1"', 'versionName = "0.9.0-dev.1"')
workflow = workflow.replace(
    "            app/src/main/java/jp/co/tenposinfo/register/GoogleDriveDiagnosticsActivity.kt \\\n",
    "            app/src/main/java/jp/co/tenposinfo/register/GoogleDriveDiagnosticsActivity.kt \\\n"
    "            app/src/main/java/jp/co/tenposinfo/register/GoogleDriveConnectionTest.kt \\\n",
)
workflow = workflow.replace(
    "            app/src/test/java/jp/co/tenposinfo/register/V048GoogleDriveSettingsEntryTest.kt \\\n",
    "            app/src/test/java/jp/co/tenposinfo/register/V048GoogleDriveSettingsEntryTest.kt \\\n"
    "            app/src/test/java/jp/co/tenposinfo/register/V050GoogleDriveCrossAppConnectionTest.kt \\\n",
)
workflow = workflow.replace(
    "            management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveDiagnosticsActivity.kt \\\n",
    "            management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveDiagnosticsActivity.kt \\\n"
    "            management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveConnectionTest.kt \\\n",
)
workflow = workflow.replace(
    "            management-app/src/test/java/jp/co/tenposinfo/register/plus/V049GoogleDrivePlusSetupDiagnosticsTest.kt \\\n",
    "            management-app/src/test/java/jp/co/tenposinfo/register/plus/V049GoogleDrivePlusSetupDiagnosticsTest.kt \\\n"
    "            management-app/src/test/java/jp/co/tenposinfo/register/plus/V050GoogleDriveCrossAppConnectionTest.kt \\\n",
)
workflow = workflow.replace(
    "            docs/V0.49_TSUGUREGI_PLUS_DRIVE_SETUP_DIAGNOSTICS.md \\\n",
    "            docs/V0.50_GOOGLE_DRIVE_CROSS_APP_CONNECTION_TEST.md \\\n"
    "            docs/V0.50_RELEASE_NOTES.md \\\n"
    "            docs/V0.49_TSUGUREGI_PLUS_DRIVE_SETUP_DIAGNOSTICS.md \\\n",
)
workflow = workflow.replace(
    "          plus_diagnostics = (plus / 'GoogleDriveDiagnosticsActivity.kt').read_text()\n",
    "          plus_diagnostics = (plus / 'GoogleDriveDiagnosticsActivity.kt').read_text()\n"
    "          register_connection_test = (root / 'GoogleDriveConnectionTest.kt').read_text()\n"
    "          plus_connection_test = (plus / 'GoogleDriveConnectionTest.kt').read_text()\n",
)
workflow = workflow.replace(
    "          assert 'putString(\"refresh_token\"' not in plus_diagnostics\n\n",
    '''          assert 'putString("refresh_token"' not in plus_diagnostics

          for token in ('GoogleDriveConnectionTestCoordinator', 'connection-test', 'containsSalesData', 'client.updateJson', 'oauth-cross-app-visibility'):
              assert token in register_connection_test, token
          for token in ('GoogleDriveConnectionTestVerifier', 'findLatest', 'download', 'ALLOWED_KEYS', 'NOT_FOUND', 'modifiedTime desc'):
              assert token in plus_connection_test, token
          assert 'SalesJournalJsonContract' not in register_connection_test
          assert 'SalesJournalImportRepository' not in plus_connection_test
          assert 'putString("access_token"' not in register_connection_test
          assert 'putString("refresh_token"' not in register_connection_test
          assert 'putString("access_token"' not in plus_connection_test
          assert 'putString("refresh_token"' not in plus_connection_test
          assert '接続テストJSONを作成' in pos_account
          assert 'つぐレジ接続テストを検索' in plus_account

''',
)
workflow = workflow.replace(
    "          for version in ('v049',",
    "          for version in ('v050', 'v049',",
)
workflow = workflow.replace(
    "          assert not Path('tools/build-apk-v049.generated.yml').exists()\n",
    "          assert not Path('tools/build-apk-v049.generated.yml').exists()\n"
    "          assert not Path('.github/workflows/v050-apply-temp.yml').exists()\n"
    "          assert not Path('tools/v050_apply.py').exists()\n"
    "          assert not Path('tools/build-apk-v050.generated.yml').exists()\n",
)
workflow = workflow.replace(
    "TSUGUREGI_v0.49.0_dev1_plus_drive_setup_diagnostics_debug.apk",
    "TSUGUREGI_v0.50.0_dev1_drive_cross_app_connection_test_debug.apk",
)
workflow = workflow.replace(
    "TSUGUREGI_PLUS_v0.8.0_dev1_drive_setup_diagnostics_debug.apk",
    "TSUGUREGI_PLUS_v0.9.0_dev1_drive_cross_app_connection_test_debug.apk",
)
workflow = workflow.replace(
    "TSUGUREGI-v0.49.0-dev1-plus-drive-setup-diagnostics-apks",
    "TSUGUREGI-v0.50.0-dev1-drive-cross-app-connection-test-apks",
)
workflow = workflow.replace("REGISTER_VERSION_NAME=0.49.0-dev.1", "REGISTER_VERSION_NAME=0.50.0-dev.1")
workflow = workflow.replace("REGISTER_VERSION_CODE=79", "REGISTER_VERSION_CODE=80")
workflow = workflow.replace("MANAGEMENT_APP_VERSION_NAME=0.8.0-dev.1", "MANAGEMENT_APP_VERSION_NAME=0.9.0-dev.1")
workflow = workflow.replace("MANAGEMENT_APP_VERSION_CODE=8", "MANAGEMENT_APP_VERSION_CODE=9")
workflow = workflow.replace(
    "          PLUS_GOOGLE_DRIVE_DIAGNOSTIC_SALES_JSON_INCLUDED=false\n",
    "          PLUS_GOOGLE_DRIVE_DIAGNOSTIC_SALES_JSON_INCLUDED=false\n"
    "          GOOGLE_DRIVE_CROSS_APP_CONNECTION_TEST=true\n"
    "          CONNECTION_TEST_CONTAINS_SALES_DATA=false\n"
    "          CONNECTION_TEST_IMPORTED_TO_SALES_DATABASE=false\n",
)
write("tools/build-apk-v050.generated.yml", workflow)
