from pathlib import Path

SOURCE = Path('management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveDirectSync.kt')
TEST = Path('management-app/src/test/java/jp/co/tenposinfo/register/plus/V152Syn004DriveChangesRateLimitTest.kt')
DOC = Path('docs/V1.36_SYN_004_DRIVE_CHANGES_RATE_LIMIT.md')


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'missing SYN-004 anchor: {label}')
    return text.replace(old, new, 1)


text = SOURCE.read_text()
if 'SYN004_DRIVE_CHANGES_API' in text:
    print('SYN-004 already applied')
    raise SystemExit(0)

text = replace_once(
    text,
    '''class GoogleDriveSyncApiException(
    val responseCode: Int,
    val responseBody: String,
) : IOException("Google Drive API HTTP $responseCode")''',
    '''class GoogleDriveSyncApiException(
    val responseCode: Int,
    val responseBody: String,
    val retryAfterMillis: Long? = null,
) : IOException("Google Drive API HTTP $responseCode")

object GoogleDriveSyncRetryPolicyV152 {
    const val MAX_ATTEMPTS = 5
    private const val BASE_DELAY_MS = 1_000L
    private const val MAX_DELAY_MS = 60_000L

    fun shouldRetry(error: Throwable): Boolean = when (GoogleDriveSyncErrorPolicy.classify(error)) {
        GoogleDriveSyncFailureCategory.RATE_LIMITED,
        GoogleDriveSyncFailureCategory.NETWORK,
        GoogleDriveSyncFailureCategory.SERVER,
        -> true
        else -> false
    }

    fun delayMillis(attempt: Int, retryAfterMillis: Long?, jitterUnit: Double): Long {
        require(attempt >= 1)
        val exponent = (attempt - 1).coerceAtMost(6)
        val exponential = (BASE_DELAY_MS * (1L shl exponent)).coerceAtMost(MAX_DELAY_MS)
        val normalizedJitter = jitterUnit.coerceIn(0.0, 1.0)
        val jittered = (exponential * (0.5 + normalizedJitter)).toLong().coerceAtLeast(1L)
        return maxOf(retryAfterMillis ?: 0L, jittered).coerceAtMost(MAX_DELAY_MS)
    }
}''',
    'api exception + retry policy',
)

text = replace_once(
    text,
    '''data class GoogleDriveSyncRemotePage(
    val files: List<GoogleDriveSyncRemoteFile>,
    val nextPageToken: String?,
    val incompleteSearch: Boolean,
)''',
    '''data class GoogleDriveSyncRemotePage(
    val files: List<GoogleDriveSyncRemoteFile>,
    val nextPageToken: String?,
    val incompleteSearch: Boolean,
    val newStartPageToken: String? = null,
)''',
    'remote page token model',
)

list_anchor = '''    fun download(fileId: String): ByteArray = executeBytes(
        method = "GET",
        url = "$DRIVE_FILES_URL/${encodePath(fileId)}?alt=media&supportsAllDrives=false",
    )'''
list_replacement = '''    // SYN004_DRIVE_CHANGES_API: normal incremental synchronization is driven by
    // the Drive Changes API. The initial full listing is retained only as a baseline bootstrap.
    fun getStartPageToken(): String {
        val root = JSONObject(
            execute(
                "GET",
                "$DRIVE_CHANGES_START_TOKEN_URL?supportsAllDrives=false&fields=startPageToken",
            ),
        )
        return root.getString("startPageToken").also { require(it.isNotBlank()) }
    }

    fun listJournalChangesPage(pageToken: String): GoogleDriveSyncRemotePage {
        val fields = "nextPageToken,newStartPageToken,changes(removed,fileId,file(id,name,mimeType,trashed,modifiedTime,version,size,appProperties))"
        val url = buildString {
            append(DRIVE_CHANGES_URL)
            append("?pageToken=").append(encode(pageToken))
            append("&spaces=drive&includeRemoved=true&restrictToMyDrive=true")
            append("&pageSize=$PAGE_SIZE")
            append("&fields=").append(encode(fields))
        }
        val root = JSONObject(execute("GET", url))
        val changes = root.optJSONArray("changes") ?: JSONArray()
        val result = ArrayList<GoogleDriveSyncRemoteFile>(changes.length())
        for (index in 0 until changes.length()) {
            val change = changes.getJSONObject(index)
            if (change.optBoolean("removed", false)) continue
            val item = change.optJSONObject("file") ?: continue
            if (item.optBoolean("trashed", false) || item.optString("mimeType") != "application/json") continue
            val properties = linkedMapOf<String, String>()
            item.optJSONObject("appProperties")?.let { source ->
                source.keys().forEach { key -> properties[key] = source.optString(key) }
            }
            if (properties["app"] != APP || properties["role"] != ROLE) continue
            val id = item.optString("id").ifBlank { change.optString("fileId") }
            if (id.isBlank()) continue
            result += GoogleDriveSyncRemoteFile(
                id = id,
                name = item.optString("name").ifBlank { "$id.json" },
                modifiedTime = item.optString("modifiedTime"),
                version = item.optString("version").takeIf(String::isNotBlank),
                size = item.optString("size").toLongOrNull(),
                appProperties = properties,
            )
        }
        return GoogleDriveSyncRemotePage(
            files = result,
            nextPageToken = root.optString("nextPageToken").takeIf(String::isNotBlank),
            incompleteSearch = false,
            newStartPageToken = root.optString("newStartPageToken").takeIf(String::isNotBlank),
        )
    }

    fun download(fileId: String): ByteArray = executeBytes(
        method = "GET",
        url = "$DRIVE_FILES_URL/${encodePath(fileId)}?alt=media&supportsAllDrives=false",
    )'''
text = replace_once(text, list_anchor, list_replacement, 'changes API methods')

execute_anchor = '''    private fun executeBytes(
        method: String,
        url: String,
        requestBody: ByteArray? = null,
        contentType: String? = null,
    ): ByteArray {
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
    }'''
execute_replacement = '''    private fun executeBytes(
        method: String,
        url: String,
        requestBody: ByteArray? = null,
        contentType: String? = null,
    ): ByteArray {
        var attempt = 1
        while (true) {
            try {
                return executeBytesOnce(method, url, requestBody, contentType)
            } catch (error: Throwable) {
                if (attempt >= GoogleDriveSyncRetryPolicyV152.MAX_ATTEMPTS || !GoogleDriveSyncRetryPolicyV152.shouldRetry(error)) {
                    throw error
                }
                val retryAfter = (error as? GoogleDriveSyncApiException)?.retryAfterMillis
                val delay = GoogleDriveSyncRetryPolicyV152.delayMillis(
                    attempt = attempt,
                    retryAfterMillis = retryAfter,
                    jitterUnit = java.util.concurrent.ThreadLocalRandom.current().nextDouble(),
                )
                Thread.sleep(delay)
                attempt += 1
            }
        }
    }

    private fun executeBytesOnce(
        method: String,
        url: String,
        requestBody: ByteArray?,
        contentType: String?,
    ): ByteArray {
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
            val bytes = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes() }
                ?: ByteArray(0)
            if (code !in 200..299) {
                throw GoogleDriveSyncApiException(
                    responseCode = code,
                    responseBody = bytes.toString(Charsets.UTF_8),
                    retryAfterMillis = retryAfterMillis(connection.getHeaderField("Retry-After")),
                )
            }
            bytes
        } finally {
            connection.disconnect()
        }
    }

    private fun retryAfterMillis(value: String?): Long? {
        val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        text.toLongOrNull()?.let { seconds -> return (seconds.coerceAtLeast(0L) * 1_000L).coerceAtMost(60_000L) }
        return runCatching {
            val retryAt = java.time.ZonedDateTime.parse(text, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            (retryAt - System.currentTimeMillis()).coerceAtLeast(0L).coerceAtMost(60_000L)
        }.getOrNull()
    }'''
text = replace_once(text, execute_anchor, execute_replacement, 'HTTP retry layer')

text = replace_once(
    text,
    '''        const val DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files"
        const val DRIVE_UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"''',
    '''        const val DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files"
        const val DRIVE_CHANGES_URL = "https://www.googleapis.com/drive/v3/changes"
        const val DRIVE_CHANGES_START_TOKEN_URL = "https://www.googleapis.com/drive/v3/changes/startPageToken"
        const val DRIVE_UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"''',
    'Drive Changes constants',
)

text = replace_once(
    text,
    '''                PlusAckOutboxV150.deliverPending(initialDb, client)
                val visitedPageTokens = mutableSetOf<String>()
                var pageToken: String? = null
                var listed = 0''',
    '''                PlusAckOutboxV150.deliverPending(initialDb, client)
                val persistedChangesToken = if (forceReimport) null else loadChangesPageToken(initialDb)
                var changesMode = persistedChangesToken != null
                // Capture before the initial full scan so writes racing with the baseline cannot be lost.
                val baselineChangesToken = if (changesMode) null else client.getStartPageToken()
                val visitedPageTokens = mutableSetOf<String>()
                var pageToken: String? = persistedChangesToken
                var listed = 0''',
    'initial changes token state',
)

text = replace_once(
    text,
    '''                do {
                    if (pageToken != null && !visitedPageTokens.add(pageToken)) {
                        throw GoogleDriveSyncIncompleteListingException("Google Driveのpage tokenが循環しました")
                    }
                    val page = client.listJournalPage(pageToken)
                    if (page.incompleteSearch) {''',
    '''                do {
                    val visitKey = "${if (changesMode) "changes" else "files"}:${pageToken ?: "first"}"
                    if (!visitedPageTokens.add(visitKey)) {
                        throw GoogleDriveSyncIncompleteListingException("Google Driveのpage tokenが循環しました")
                    }
                    val page = if (changesMode) {
                        client.listJournalChangesPage(checkNotNull(pageToken))
                    } else {
                        client.listJournalPage(pageToken)
                    }
                    if (page.incompleteSearch) {''',
    'select Changes API page',
)

text = replace_once(
    text,
    '''                        GoogleDrivePageCommitCheckpointStoreV134.persist(
                            db = pageDb,
                            runToken = runToken,
                            result = result,
                        )
                        pageDb.setTransactionSuccessful()''',
    '''                        GoogleDrivePageCommitCheckpointStoreV134.persist(
                            db = pageDb,
                            runToken = runToken,
                            result = result,
                        )
                        if (changesMode && !page.newStartPageToken.isNullOrBlank()) {
                            persistChangesPageToken(pageDb, page.newStartPageToken)
                        }
                        pageDb.setTransactionSuccessful()''',
    'atomic cursor persistence',
)

text = replace_once(
    text,
    '''                    pageToken = page.nextPageToken
                } while (pageToken != null)''',
    '''                    pageToken = page.nextPageToken
                    if (!changesMode && pageToken == null) {
                        changesMode = true
                        pageToken = baselineChangesToken
                    }
                } while (pageToken != null)''',
    'baseline-to-changes transition',
)

text = replace_once(
    text,
    '''    private fun known(fileId: String): KnownDriveFile? = database.readableDatabase.rawQuery(''',
    '''    private fun loadChangesPageToken(db: SQLiteDatabase): String? = db.rawQuery(
        "SELECT page_token FROM $CURSOR_TABLE WHERE singleton_id=1",
        null,
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).takeIf(String::isNotBlank) else null }

    private fun persistChangesPageToken(db: SQLiteDatabase, pageToken: String) {
        require(pageToken.isNotBlank())
        db.insertWithOnConflict(
            CURSOR_TABLE,
            null,
            ContentValues().apply {
                put("singleton_id", 1)
                put("page_token", pageToken)
                put("updated_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun known(fileId: String): KnownDriveFile? = database.readableDatabase.rawQuery(''',
    'cursor accessors',
)

text = replace_once(
    text,
    '''        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_drive_sync_files_modified ON $TABLE(modified_time, last_processed_at DESC)",
        )
    }''',
    '''        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_drive_sync_files_modified ON $TABLE(modified_time, last_processed_at DESC)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $CURSOR_TABLE (
                singleton_id INTEGER PRIMARY KEY CHECK(singleton_id=1),
                page_token TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }''',
    'cursor schema',
)

text = replace_once(
    text,
    '''    companion object {
        const val TABLE = "drive_sync_files"
    }''',
    '''    companion object {
        const val TABLE = "drive_sync_files"
        const val CURSOR_TABLE = "drive_changes_cursor"
    }''',
    'cursor table constant',
)

SOURCE.write_text(text)

TEST.write_text('''package jp.co.tenposinfo.register.plus

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V152Syn004DriveChangesRateLimitTest {
    private val module = File(System.getProperty("user.dir")).let { if (File(it, "management-app").isDirectory) File(it, "management-app") else it }
    private fun source(name: String) = File(module, "src/main/java/jp/co/tenposinfo/register/plus/$name").readText()

    @Test fun normalSyncUsesPersistedDriveChangesCursor() {
        val drive = source("GoogleDriveDirectSync.kt")
        listOf(
            "SYN004_DRIVE_CHANGES_API",
            "DRIVE_CHANGES_URL",
            "DRIVE_CHANGES_START_TOKEN_URL",
            "listJournalChangesPage",
            "drive_changes_cursor",
            "loadChangesPageToken",
            "persistChangesPageToken",
            "newStartPageToken",
        ).forEach { assertTrue("missing $it", drive.contains(it)) }
        assertTrue(drive.contains("if (changesMode)"))
        assertTrue(drive.contains("client.listJournalChangesPage(checkNotNull(pageToken))"))
    }

    @Test fun bootstrapCapturesStartTokenBeforeFullScanAndThenConsumesChanges() {
        val drive = source("GoogleDriveDirectSync.kt")
        val capture = drive.indexOf("baselineChangesToken = if (changesMode) null else client.getStartPageToken()")
        val fullList = drive.indexOf("client.listJournalPage(pageToken)", capture)
        val transition = drive.indexOf("pageToken = baselineChangesToken", fullList)
        assertTrue(capture > 0 && fullList > capture && transition > fullList)
    }

    @Test fun cursorAdvancesInsideSamePageCommitTransaction() {
        val drive = source("GoogleDriveDirectSync.kt")
        val begin = drive.indexOf("pageDb.beginTransaction()")
        val persist = drive.indexOf("persistChangesPageToken(pageDb, page.newStartPageToken)", begin)
        val successful = drive.indexOf("pageDb.setTransactionSuccessful()", persist)
        val end = drive.indexOf("pageDb.endTransaction()", successful)
        assertTrue(begin > 0 && persist > begin && successful > persist && end > successful)
    }

    @Test fun rateLimitRetryHonorsRetryAfterAndExponentialJitter() {
        val drive = source("GoogleDriveDirectSync.kt")
        listOf(
            "Retry-After",
            "GoogleDriveSyncRetryPolicyV152",
            "MAX_ATTEMPTS = 5",
            "1L shl exponent",
            "0.5 + normalizedJitter",
            "Thread.sleep(delay)",
        ).forEach { assertTrue("missing $it", drive.contains(it)) }
        assertEquals(3_000L, GoogleDriveSyncRetryPolicyV152.delayMillis(1, 3_000L, 0.0))
        assertEquals(1_000L, GoogleDriveSyncRetryPolicyV152.delayMillis(1, null, 0.5))
        assertEquals(2_000L, GoogleDriveSyncRetryPolicyV152.delayMillis(2, null, 0.5))
    }

    @Test fun rateLimit403ClassificationRemainsRetryable() {
        val body = "{\\"error\\":{\\"errors\\":[{\\"reason\\":\\"userRateLimitExceeded\\"}]}}"
        val error = GoogleDriveSyncApiException(403, body, 1_000L)
        assertEquals(GoogleDriveSyncFailureCategory.RATE_LIMITED, GoogleDriveSyncErrorPolicy.classify(error))
        assertTrue(GoogleDriveSyncRetryPolicyV152.shouldRetry(error))
    }
}
''')

DOC.write_text('''# V1.36 SYN-004 Drive Changes API / rate-limit control

Formal v2.5 PLT-002 gap closure.

- First connection captures a Drive `startPageToken` before the baseline `files.list`, then consumes Changes from that captured token so changes racing with bootstrap are not lost.
- Normal synchronization reads the persisted `drive_changes_cursor.page_token` and uses `changes.list` rather than repeating a global files scan.
- `newStartPageToken` is persisted in the same SQLite transaction as the imported page/checkpoint. A crash before commit therefore cannot advance the cursor past uncommitted imports.
- 429 and rate-limit 403 remain retryable. HTTP `Retry-After` is honored; otherwise exponential backoff with jitter is used, bounded to five attempts / 60 seconds.
- Existing event-level immutable ACK/outbox behavior is unchanged.
- Real Google Drive throttling and offline/recovery behavior still require real-device acceptance testing.
''')

print('SYN-004 applied')
