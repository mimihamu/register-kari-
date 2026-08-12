package jp.co.tenposinfo.register

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

private const val OUTBOX_DELIVERY_SETTINGS_PREFS = "outbox_delivery_settings_v1"
private const val OUTBOX_DELIVERY_STATUS_PREFS = "outbox_delivery_status_v1"
private const val OUTBOX_DELIVERY_MIME = "application/json"
private const val OUTBOX_DELIVERY_MAX_BYTES = 20L * 1024L * 1024L

data class OutboxDeliverySettings(
    val enabled: Boolean = false,
    val treeUri: String? = null,
    val destinationLabel: String? = null,
    val unmeteredNetworkOnly: Boolean = false,
    val failureNotificationsEnabled: Boolean = true,
)

object OutboxDeliverySettingsPolicy {
    fun sanitized(settings: OutboxDeliverySettings): OutboxDeliverySettings {
        val uri = settings.treeUri?.trim()?.takeIf(String::isNotEmpty)
        return settings.copy(
            enabled = settings.enabled && uri != null,
            treeUri = uri,
            destinationLabel = settings.destinationLabel?.trim()?.takeIf(String::isNotEmpty),
        )
    }

    fun validated(settings: OutboxDeliverySettings): OutboxDeliverySettings {
        val sanitized = sanitized(settings)
        require(!settings.enabled || sanitized.treeUri != null) {
            "外部自動送信をONにするには保存先フォルダを選択してください"
        }
        sanitized.treeUri?.let {
            require(it.startsWith("content://") && "/tree/" in it) {
                "送信先はAndroidのフォルダ選択から指定してください"
            }
        }
        return sanitized
    }
}

class OutboxDeliverySettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        OUTBOX_DELIVERY_SETTINGS_PREFS,
        Context.MODE_PRIVATE,
    )

    fun load(): OutboxDeliverySettings = OutboxDeliverySettingsPolicy.sanitized(
        OutboxDeliverySettings(
            enabled = preferences.getBoolean("enabled", false),
            treeUri = preferences.getString("tree_uri", null),
            destinationLabel = preferences.getString("destination_label", null),
            unmeteredNetworkOnly = preferences.getBoolean("unmetered_only", false),
            failureNotificationsEnabled = preferences.getBoolean("failure_notifications", true),
        ),
    )

    fun save(settings: OutboxDeliverySettings): OutboxDeliverySettings {
        val validated = OutboxDeliverySettingsPolicy.validated(settings)
        preferences.edit()
            .putBoolean("enabled", validated.enabled)
            .apply {
                if (validated.treeUri == null) remove("tree_uri") else putString("tree_uri", validated.treeUri)
                if (validated.destinationLabel == null) remove("destination_label")
                else putString("destination_label", validated.destinationLabel)
            }
            .putBoolean("unmetered_only", validated.unmeteredNetworkOnly)
            .putBoolean("failure_notifications", validated.failureNotificationsEnabled)
            .apply()
        return validated
    }
}

enum class OutboxDeliveryResultState(val displayName: String) {
    NEVER("未実行"),
    IDLE("待機中"),
    RUNNING("送信中"),
    SUCCESS("成功"),
    FAILED("失敗"),
    DESTINATION_MISSING("送信先未設定"),
    PERMISSION_LOST("送信先権限なし"),
    WAITING_UNMETERED("Wi-Fi等を待機"),
}

data class OutboxDeliveryStatus(
    val result: OutboxDeliveryResultState = OutboxDeliveryResultState.NEVER,
    val lastStartedAt: Long? = null,
    val lastCompletedAt: Long? = null,
    val lastObjectKey: String? = null,
    val lastError: String? = null,
    val pendingCount: Int = 0,
    val failedCount: Int = 0,
)

class OutboxDeliveryStatusStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        OUTBOX_DELIVERY_STATUS_PREFS,
        Context.MODE_PRIVATE,
    )

    fun load(): OutboxDeliveryStatus = OutboxDeliveryStatus(
        result = runCatching {
            OutboxDeliveryResultState.valueOf(
                preferences.getString("result", OutboxDeliveryResultState.NEVER.name).orEmpty(),
            )
        }.getOrDefault(OutboxDeliveryResultState.NEVER),
        lastStartedAt = preferences.getLong("last_started_at", 0L).takeIf { it > 0L },
        lastCompletedAt = preferences.getLong("last_completed_at", 0L).takeIf { it > 0L },
        lastObjectKey = preferences.getString("last_object_key", null),
        lastError = preferences.getString("last_error", null),
        pendingCount = preferences.getInt("pending_count", 0).coerceAtLeast(0),
        failedCount = preferences.getInt("failed_count", 0).coerceAtLeast(0),
    )

    fun idle(pendingCount: Int, failedCount: Int) = write(
        result = OutboxDeliveryResultState.IDLE,
        pendingCount = pendingCount,
        failedCount = failedCount,
        completed = false,
    )

    fun running(pendingCount: Int, failedCount: Int) {
        preferences.edit()
            .putString("result", OutboxDeliveryResultState.RUNNING.name)
            .putLong("last_started_at", System.currentTimeMillis())
            .putInt("pending_count", pendingCount.coerceAtLeast(0))
            .putInt("failed_count", failedCount.coerceAtLeast(0))
            .remove("last_error")
            .apply()
    }

    fun waiting(pendingCount: Int, failedCount: Int) = write(
        result = OutboxDeliveryResultState.WAITING_UNMETERED,
        pendingCount = pendingCount,
        failedCount = failedCount,
        completed = false,
    )

    fun success(objectKey: String?, pendingCount: Int, failedCount: Int) = write(
        result = OutboxDeliveryResultState.SUCCESS,
        objectKey = objectKey,
        pendingCount = pendingCount,
        failedCount = failedCount,
        completed = true,
    )

    fun failed(
        result: OutboxDeliveryResultState,
        error: String,
        pendingCount: Int,
        failedCount: Int,
    ) = write(
        result = result,
        error = error,
        pendingCount = pendingCount,
        failedCount = failedCount,
        completed = true,
    )

    private fun write(
        result: OutboxDeliveryResultState,
        objectKey: String? = null,
        error: String? = null,
        pendingCount: Int,
        failedCount: Int,
        completed: Boolean,
    ) {
        preferences.edit()
            .putString("result", result.name)
            .putInt("pending_count", pendingCount.coerceAtLeast(0))
            .putInt("failed_count", failedCount.coerceAtLeast(0))
            .apply {
                if (completed) putLong("last_completed_at", System.currentTimeMillis())
                if (objectKey == null) remove("last_object_key") else putString("last_object_key", objectKey)
                if (error == null) remove("last_error") else putString("last_error", error)
            }
            .apply()
    }
}

object OutboxDeliveryPathPolicy {
    fun segments(objectKey: String): List<String> {
        val normalized = objectKey.trim()
        require(normalized.length in 1..320) { "保存キーの長さが不正です" }
        require('\\' !in normalized) { "保存キーに使用できない区切り文字があります" }
        val segments = normalized.split('/')
        require(segments.size in 2..8) { "保存キーの階層が不正です" }
        segments.forEach { segment ->
            require(segment.isNotBlank() && segment !in setOf(".", "..")) {
                "保存キーの階層名が不正です"
            }
            require(segment.length <= 100) { "保存キーの階層名が長すぎます" }
            require(OutboxObjectKey.sanitizeSegment(segment) == segment) {
                "保存キーに使用できない文字があります"
            }
        }
        require(segments.last().endsWith(".json")) { "同期ファイルはJSONである必要があります" }
        return segments
    }

    fun partialName(fileName: String): String {
        require('/' !in fileName && '\\' !in fileName && fileName.endsWith(".json")) {
            "同期ファイル名が不正です"
        }
        return "$fileName.partial"
    }
}

object OutboxDeliveryRetryPolicy {
    const val MAX_ATTEMPTS = 10

    fun delayMillis(attempt: Int): Long = when {
        attempt <= 1 -> 60_000L
        attempt == 2 -> 5 * 60_000L
        attempt == 3 -> 30 * 60_000L
        attempt <= 6 -> 2 * 60 * 60_000L
        else -> 6 * 60 * 60_000L
    }

    fun permanent(attempt: Int): Boolean = attempt >= MAX_ATTEMPTS
}

object OutboxDeliveryNetworkPolicy {
    fun mayDeliver(context: Context, unmeteredOnly: Boolean): Boolean {
        if (!unmeteredOnly) return true
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return !manager.isActiveNetworkMetered
    }
}

internal data class OutboxExternalDocument(
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
    val size: Long?,
)

internal object OutboxExternalDocumentProvider {
    fun rootDocumentUri(treeUri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )

    fun ensureDirectory(context: Context, treeUri: Uri, parentUri: Uri, displayName: String): Uri {
        val existing = findChild(context, treeUri, parentUri, displayName)
        if (existing != null) {
            require(existing.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                "同名のファイルがあるためフォルダを作成できません: $displayName"
            }
            return existing.uri
        }
        val created = DocumentsContract.createDocument(
            context.contentResolver,
            parentUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            displayName,
        ) ?: error("送信先へフォルダを作成できません: $displayName")
        return verifyExactDisplayName(context, created, displayName)
    }

    fun findChild(
        context: Context,
        treeUri: Uri,
        parentUri: Uri,
        displayName: String,
    ): OutboxExternalDocument? {
        val resolver = context.contentResolver
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getDocumentId(parentUri),
        )
        var matched: OutboxExternalDocument? = null
        var matchCount = 0
        val cursor = OutboxProviderQuerySafetyV109.requireAvailable(
            displayName,
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null,
                null,
                null,
            ),
        )
        cursor.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) != displayName) continue
                matchCount++
                OutboxDuplicateDisplayNameSafetyV108.requireUnique(displayName, matchCount)
                matched = OutboxExternalDocument(
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIndex)),
                    displayName = displayName,
                    mimeType = if (mimeIndex < 0 || cursor.isNull(mimeIndex)) null else cursor.getString(mimeIndex),
                    size = cursor.longOrNull(sizeIndex),
                )
            }
        }
        return matched
    }

    fun createFile(context: Context, parentUri: Uri, displayName: String): Uri {
        val created = DocumentsContract.createDocument(
            context.contentResolver,
            parentUri,
            OUTBOX_DELIVERY_MIME,
            displayName,
        ) ?: error("送信先へファイルを作成できません: $displayName")
        return verifyExactDisplayName(context, created, displayName)
    }

    fun rename(context: Context, documentUri: Uri, displayName: String): Uri? {
        val renamed = DocumentsContract.renameDocument(
            context.contentResolver,
            documentUri,
            displayName,
        ) ?: return null
        return verifyExactDisplayName(context, renamed, displayName)
    }

    fun delete(context: Context, documentUri: Uri): Boolean =
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, documentUri) }
            .getOrDefault(false)

    fun size(context: Context, documentUri: Uri): Long? {
        val cursor = OutboxProviderMetadataSafetyV110.requireAvailable(
            "size",
            context.contentResolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_SIZE),
                null,
                null,
                null,
            ),
        )
        return cursor.use { value ->
            if (!value.moveToFirst()) null
            else value.longOrNull(value.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE))
        }
    }

    private fun displayName(context: Context, documentUri: Uri): String? {
        val cursor = OutboxProviderMetadataSafetyV110.requireAvailable(
            "displayName",
            context.contentResolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            ),
        )
        return cursor.use { value ->
            val index = value.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (!value.moveToFirst() || index < 0 || value.isNull(index)) null else value.getString(index)
        }
    }

    private fun verifyExactDisplayName(
        context: Context,
        documentUri: Uri,
        requestedName: String,
    ): Uri {
        val actualName = try {
            displayName(context, documentUri)
        } catch (error: OutboxProviderMetadataUnavailableException) {
            delete(context, documentUri)
            throw error
        }
        if (!OutboxProviderNameSafetyV107.isExact(requestedName, actualName)) {
            delete(context, documentUri)
            throw OutboxProviderNameMismatchException(requestedName, actualName)
        }
        return documentUri
    }

    private fun Cursor.longOrNull(index: Int): Long? =
        if (index < 0 || isNull(index)) null else getLong(index)
}

private data class OutboxDeliveryRecord(
    val id: Long,
    val eventId: String,
    val objectKey: String,
    val attemptCount: Int,
    val workerToken: String,
)

data class OutboxDeliveryRunResult(
    val deliveredCount: Int,
    val lastObjectKey: String?,
    val pendingCount: Int,
    val failedCount: Int,
    val retryRecommended: Boolean,
)

class OutboxExternalDeliveryCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val settingsStore = OutboxDeliverySettingsStore(appContext)
    private val statusStore = OutboxDeliveryStatusStore(appContext)

    fun process(limit: Int = 100): OutboxDeliveryRunResult {
        val settings = settingsStore.load()
        val countsBefore = counts()
        if (!settings.enabled) {
            statusStore.idle(countsBefore.first, countsBefore.second)
            OutboxDeliveryNotificationCoordinator.clear(appContext)
            return OutboxDeliveryRunResult(0, null, countsBefore.first, countsBefore.second, false)
        }
        val treeUriText = settings.treeUri
        if (treeUriText == null) {
            statusStore.failed(
                OutboxDeliveryResultState.DESTINATION_MISSING,
                "外部送信先が未設定です",
                countsBefore.first,
                countsBefore.second,
            )
            return OutboxDeliveryRunResult(0, null, countsBefore.first, countsBefore.second, false)
        }
        val treeUri = Uri.parse(treeUriText)
        if (!ExternalBackupDestinationAccess.hasPersistedWritePermission(appContext, treeUri)) {
            val message = "外部送信先への書込権限がありません。保存先を選び直してください。"
            statusStore.failed(
                OutboxDeliveryResultState.PERMISSION_LOST,
                message,
                countsBefore.first,
                countsBefore.second,
            )
            OutboxDeliveryNotificationCoordinator.apply(
                appContext,
                OutboxDeliveryResultState.PERMISSION_LOST,
                message,
            )
            OutboxDeliveryAudit.record(
                appContext,
                "SYNC_OUTBOX_DESTINATION_PERMISSION_LOST",
                message,
            )
            return OutboxDeliveryRunResult(0, null, countsBefore.first, countsBefore.second, false)
        }
        if (!OutboxDeliveryNetworkPolicy.mayDeliver(appContext, settings.unmeteredNetworkOnly)) {
            statusStore.waiting(countsBefore.first, countsBefore.second)
            return OutboxDeliveryRunResult(0, null, countsBefore.first, countsBefore.second, false)
        }

        val records = claimStaged(limit)
        if (records.isEmpty()) {
            statusStore.idle(countsBefore.first, countsBefore.second)
            OutboxDeliveryNotificationCoordinator.clear(appContext)
            return OutboxDeliveryRunResult(0, null, countsBefore.first, countsBefore.second, false)
        }

        statusStore.running(countsBefore.first, countsBefore.second)
        var delivered = 0
        var lastObjectKey: String? = null
        var retryRecommended = false
        for (record in records) {
            val localFile = localFile(record.objectKey)
            if (!localFile.isFile) {
                moveBackToPending(record, "ローカルJSONが見つからないため再生成します")
                continue
            }
            try {
                val duplicate = deliverOne(treeUri, record.objectKey, localFile)
                markSent(record)
                delivered++
                lastObjectKey = record.objectKey
                OutboxDeliveryAudit.record(
                    appContext,
                    if (duplicate) "SYNC_OUTBOX_EXTERNAL_SKIPPED_DUPLICATE" else "SYNC_OUTBOX_EXTERNAL_SENT",
                    "${record.eventId} / ${record.objectKey} / ${localFile.length()} bytes / sha256=${sha256(FileInputStream(localFile))}",
                    record.id,
                )
            } catch (error: Throwable) {
                val permissionLost = error is SecurityException ||
                    !ExternalBackupDestinationAccess.hasPersistedWritePermission(appContext, treeUri)
                val collision = error is OutboxDestinationCollisionException
                val message = (error.message ?: error.javaClass.simpleName).take(500)
                val permanent = if (permissionLost) {
                    markDeliveryPaused(record, message)
                    false
                } else {
                    markDeliveryFailure(record, message, forcePermanent = collision)
                }
                val result = if (permissionLost) {
                    OutboxDeliveryResultState.PERMISSION_LOST
                } else {
                    OutboxDeliveryResultState.FAILED
                }
                val counts = counts()
                statusStore.failed(result, message, counts.first, counts.second)
                OutboxDeliveryNotificationCoordinator.apply(appContext, result, message)
                OutboxDeliveryAudit.record(
                    appContext,
                    if (collision) "SYNC_OUTBOX_EXTERNAL_COLLISION" else "SYNC_OUTBOX_EXTERNAL_FAILED",
                    "${record.eventId} / ${record.objectKey} / permanent=$permanent / $message",
                    record.id,
                )
                retryRecommended = !permissionLost && !collision && !permanent
                break
            }
        }

        val countsAfter = counts()
        if (!retryRecommended) {
            if (delivered > 0 || lastObjectKey != null) {
                statusStore.success(lastObjectKey, countsAfter.first, countsAfter.second)
            } else if (countsAfter.second == 0) {
                statusStore.idle(countsAfter.first, countsAfter.second)
            }
            if (countsAfter.second == 0) OutboxDeliveryNotificationCoordinator.clear(appContext)
        }
        return OutboxDeliveryRunResult(
            deliveredCount = delivered,
            lastObjectKey = lastObjectKey,
            pendingCount = countsAfter.first,
            failedCount = countsAfter.second,
            retryRecommended = retryRecommended,
        )
    }

    fun retryFailed(): Int {
        val dbHelper = RegisterDatabase(appContext)
        return try {
            val db = dbHelper.writableDatabase
            JournalOutboxSchema.ensureCore(db)
            val rows = db.rawQuery(
                "SELECT id, object_key FROM sync_outbox WHERE status='FAILED' ORDER BY created_at ASC",
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getString(1))
                }
            }
            val now = System.currentTimeMillis()
            var changed = 0
            db.beginTransaction()
            try {
                rows.forEach { (id, objectKey) ->
                    val targetStatus = if (runCatching { localFile(objectKey).isFile }.getOrDefault(false)) {
                        SyncOutboxStatus.STAGED.name
                    } else {
                        SyncOutboxStatus.PENDING.name
                    }
                    changed += db.update(
                        "sync_outbox",
                        ContentValues().apply {
                            put("status", targetStatus)
                            put("attempt_count", 0)
                            put("next_attempt_at", 0)
                            putNull("last_error")
                            putNull("processing_started_at")
                            putNull("lease_until")
                            putNull("worker_token")
                            put("updated_at", now)
                        },
                        "id=? AND status='FAILED'",
                        arrayOf(id.toString()),
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            if (changed > 0) {
                OutboxDeliveryAudit.record(
                    appContext,
                    "SYNC_OUTBOX_RETRY_REQUESTED",
                    "failed=$changed",
                )
            }
            changed
        } finally {
            dbHelper.close()
        }
    }

    fun currentCounts(): Pair<Int, Int> = counts()

    private fun claimStaged(limit: Int): List<OutboxDeliveryRecord> {
        val helper = RegisterDatabase(appContext)
        return try {
            val db = helper.writableDatabase
            JournalOutboxSchema.ensureCore(db)
            val now = System.currentTimeMillis()
            val workerToken = UUID.randomUUID().toString()
            db.beginTransaction()
            try {
                val selected = db.rawQuery(
                    """
                    SELECT id, event_id, object_key, attempt_count
                    FROM sync_outbox
                    WHERE status='STAGED'
                      AND next_attempt_at <= ?
                      AND (worker_token IS NULL OR lease_until IS NULL OR lease_until <= ?)
                    ORDER BY created_at ASC, id ASC
                    LIMIT ?
                    """.trimIndent(),
                    arrayOf(
                        now.toString(),
                        now.toString(),
                        limit.coerceIn(1, 500).toString(),
                    ),
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                OutboxDeliveryRecord(
                                    id = cursor.getLong(0),
                                    eventId = cursor.getString(1),
                                    objectKey = cursor.getString(2),
                                    attemptCount = cursor.getInt(3),
                                    workerToken = workerToken,
                                ),
                            )
                        }
                    }
                }
                val claimed = selected.mapNotNull { record ->
                    val changed = db.update(
                        "sync_outbox",
                        ContentValues().apply {
                            put("processing_started_at", now)
                            put("lease_until", now + OutboxExternalDeliveryLeaseV113.LEASE_MILLIS)
                            put("worker_token", workerToken)
                            put("updated_at", now)
                        },
                        "id=? AND status='STAGED' AND next_attempt_at <= ? AND (worker_token IS NULL OR lease_until IS NULL OR lease_until <= ?)",
                        arrayOf(record.id.toString(), now.toString(), now.toString()),
                    )
                    if (changed == 1) record else null
                }
                db.setTransactionSuccessful()
                claimed
            } finally {
                db.endTransaction()
            }
        } finally {
            helper.close()
        }
    }

    private fun counts(): Pair<Int, Int> {
        val helper = RegisterDatabase(appContext)
        return try {
            val db = helper.writableDatabase
            JournalOutboxSchema.ensureCore(db)
            val pending = db.rawQuery(
                "SELECT COUNT(*) FROM sync_outbox WHERE status IN ('PENDING','PROCESSING','RETRY','STAGED')",
                null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
            val failed = db.rawQuery(
                "SELECT COUNT(*) FROM sync_outbox WHERE status='FAILED'",
                null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
            pending to failed
        } finally {
            helper.close()
        }
    }

    private fun localFile(objectKey: String): File {
        OutboxDeliveryPathPolicy.segments(objectKey)
        val root = File(appContext.filesDir, "drive-sync-staging").canonicalFile
        val file = File(root, objectKey).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) {
            "ローカル同期ファイルのパスが不正です"
        }
        return file
    }

    private fun deliverOne(treeUri: Uri, objectKey: String, localFile: File): Boolean {
        require(localFile.length() in 1..OUTBOX_DELIVERY_MAX_BYTES) {
            "同期JSONのサイズが不正です"
        }
        val segments = OutboxDeliveryPathPolicy.segments(objectKey)
        var parent = OutboxExternalDocumentProvider.rootDocumentUri(treeUri)
        segments.dropLast(1).forEach { directory ->
            parent = OutboxExternalDocumentProvider.ensureDirectory(
                appContext,
                treeUri,
                parent,
                directory,
            )
        }
        val fileName = segments.last()
        val localSha256 = sha256(FileInputStream(localFile))
        val existing = OutboxExternalDocumentProvider.findChild(
            appContext,
            treeUri,
            parent,
            fileName,
        )
        if (existing != null) {
            val existingIsDirectory = existing.mimeType == DocumentsContract.Document.MIME_TYPE_DIR
            val sameSize = !existingIsDirectory && existing.size == localFile.length()
            val sameSha256 = if (sameSize) {
                sha256(
                    appContext.contentResolver.openInputStream(existing.uri)
                        ?: throw FileNotFoundException("送信済みJSONを検証できません"),
                ) == localSha256
            } else {
                false
            }
            when (
                OutboxDestinationCollisionSafetyV106.decide(
                    existingIsDirectory = existingIsDirectory,
                    sameSize = sameSize,
                    sameSha256 = sameSha256,
                )
            ) {
                OutboxExistingDestinationDecisionV106.ALREADY_SENT -> return true
                OutboxExistingDestinationDecisionV106.COLLISION ->
                    throw OutboxDestinationCollisionException(fileName)
            }
        }

        val partialName = OutboxDeliveryPathPolicy.partialName(fileName)
        OutboxExternalDocumentProvider.findChild(appContext, treeUri, parent, partialName)?.let {
            require(OutboxExternalDocumentProvider.delete(appContext, it.uri)) {
                "前回の一時ファイルを削除できません: $partialName"
            }
        }
        val partialUri = OutboxExternalDocumentProvider.createFile(appContext, parent, partialName)
        var committedUri: Uri? = null
        var externalCommitted = false
        try {
            val written = appContext.contentResolver.openOutputStream(partialUri, "w")?.use { output ->
                localFile.inputStream().buffered().use { input ->
                    copyWithLimit(input, output, OUTBOX_DELIVERY_MAX_BYTES)
                }
            } ?: throw FileNotFoundException("送信先の一時ファイルを開けません")
            require(written == localFile.length()) { "送信JSONの書込サイズが一致しません" }
            val partialSize = OutboxExternalDocumentProvider.size(appContext, partialUri)
            require(partialSize == null || partialSize == written) {
                "送信先の一時ファイルサイズが一致しません"
            }
            val partialSha = sha256(
                appContext.contentResolver.openInputStream(partialUri)
                    ?: throw FileNotFoundException("送信先の一時ファイルを検証できません"),
            )
            require(partialSha == localSha256) { "送信先の一時ファイルSHA-256が一致しません" }

            val preCommitExisting = OutboxExternalDocumentProvider.findChild(
                appContext,
                treeUri,
                parent,
                fileName,
            )
            if (preCommitExisting != null) {
                val existingIsDirectory =
                    preCommitExisting.mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                val sameSize = !existingIsDirectory && preCommitExisting.size == written
                val sameSha256 = if (sameSize) {
                    sha256(
                        appContext.contentResolver.openInputStream(preCommitExisting.uri)
                            ?: throw FileNotFoundException("確定直前の同名JSONを検証できません"),
                    ) == localSha256
                } else {
                    false
                }
                when (
                    OutboxDestinationCollisionSafetyV106.decide(
                        existingIsDirectory = existingIsDirectory,
                        sameSize = sameSize,
                        sameSha256 = sameSha256,
                    )
                ) {
                    OutboxExistingDestinationDecisionV106.ALREADY_SENT -> {
                        require(OutboxExternalDocumentProvider.delete(appContext, partialUri)) {
                            "確定直前の競合解決後に一時ファイルを削除できません: $partialName"
                        }
                        return true
                    }
                    OutboxExistingDestinationDecisionV106.COLLISION ->
                        throw OutboxDestinationCollisionException(fileName)
                }
            }

            // Legacy v1.07 frozen source-gate compatibility:
            // catch (error: OutboxProviderNameMismatchException) { throw error }
            // v1.11以降はrename例外をcatchせず、同じ安全性を自然伝播で保証する。
            val renamed = OutboxExternalDocumentProvider.rename(appContext, partialUri, fileName)
            val finalUri = renamed ?: copyPartialToFinal(
                treeUri = treeUri,
                parentUri = parent,
                partialUri = partialUri,
                finalName = fileName,
                expectedBytes = written,
            )
            committedUri = finalUri
            val finalSize = OutboxExternalDocumentProvider.size(appContext, finalUri)
            require(finalSize == null || finalSize == written) {
                "送信JSONの確定サイズが一致しません"
            }
            val finalSha = sha256(
                appContext.contentResolver.openInputStream(finalUri)
                    ?: throw FileNotFoundException("送信JSONを検証できません"),
            )
            require(finalSha == localSha256) { "送信JSONのSHA-256が一致しません" }

            val visibleFinal = OutboxExternalDocumentProvider.findChild(
                appContext,
                treeUri,
                parent,
                fileName,
            ) ?: throw OutboxFinalCommitVisibilityUnavailableException(fileName)
            OutboxFinalCommitRaceSafetyV112.requireSameDocument(
                fileName = fileName,
                committedDocumentId = DocumentsContract.getDocumentId(finalUri),
                visibleDocumentId = DocumentsContract.getDocumentId(visibleFinal.uri),
            )
            externalCommitted = true
            return false
        } catch (error: Throwable) {
            if (!externalCommitted) {
                committedUri?.let { OutboxExternalDocumentProvider.delete(appContext, it) }
                OutboxExternalDocumentProvider.delete(appContext, partialUri)
            }
            throw error
        }
    }

    private fun copyPartialToFinal(
        treeUri: Uri,
        parentUri: Uri,
        partialUri: Uri,
        finalName: String,
        expectedBytes: Long,
    ): Uri {
        val finalUri = OutboxExternalDocumentProvider.createFile(appContext, parentUri, finalName)
        try {
            val copied = appContext.contentResolver.openInputStream(partialUri)?.use { input ->
                appContext.contentResolver.openOutputStream(finalUri, "w")?.use { output ->
                    copyWithLimit(input, output, OUTBOX_DELIVERY_MAX_BYTES)
                }
            } ?: throw FileNotFoundException("送信先の一時ファイルを再読込できません")
            require(copied == expectedBytes) { "送信JSONの確定コピーサイズが一致しません" }
            OutboxExternalDocumentProvider.delete(appContext, partialUri)
            return finalUri
        } catch (error: Throwable) {
            OutboxExternalDocumentProvider.delete(appContext, finalUri)
            throw error
        }
    }

    private fun markSent(record: OutboxDeliveryRecord) {
        val helper = RegisterDatabase(appContext)
        try {
            val db = helper.writableDatabase
            val now = System.currentTimeMillis()
            val changed = db.update(
                "sync_outbox",
                ContentValues().apply {
                    put("status", SyncOutboxStatus.SENT.name)
                    put("next_attempt_at", 0)
                    putNull("last_error")
                    putNull("processing_started_at")
                    putNull("lease_until")
                    putNull("worker_token")
                    put("updated_at", now)
                },
                "id=? AND status='STAGED' AND worker_token=?",
                arrayOf(record.id.toString(), record.workerToken),
            )
            OutboxExternalDeliveryLeaseV113.requireOwnedTransition(record.id, changed)
        } finally {
            helper.close()
        }
    }

    private fun moveBackToPending(record: OutboxDeliveryRecord, message: String) {
        val helper = RegisterDatabase(appContext)
        try {
            val db = helper.writableDatabase
            val changed = db.update(
                "sync_outbox",
                ContentValues().apply {
                    put("status", SyncOutboxStatus.PENDING.name)
                    put("next_attempt_at", 0)
                    put("last_error", message)
                    putNull("processing_started_at")
                    putNull("lease_until")
                    putNull("worker_token")
                    put("updated_at", System.currentTimeMillis())
                },
                "id=? AND status='STAGED' AND worker_token=?",
                arrayOf(record.id.toString(), record.workerToken),
            )
            OutboxExternalDeliveryLeaseV113.requireOwnedTransition(record.id, changed)
        } finally {
            helper.close()
        }
    }

    private fun markDeliveryPaused(record: OutboxDeliveryRecord, message: String) {
        val helper = RegisterDatabase(appContext)
        try {
            val changed = helper.writableDatabase.update(
                "sync_outbox",
                ContentValues().apply {
                    put("status", SyncOutboxStatus.STAGED.name)
                    put("next_attempt_at", 0)
                    put("last_error", message)
                    putNull("processing_started_at")
                    putNull("lease_until")
                    putNull("worker_token")
                    put("updated_at", System.currentTimeMillis())
                },
                "id=? AND status='STAGED' AND worker_token=?",
                arrayOf(record.id.toString(), record.workerToken),
            )
            OutboxExternalDeliveryLeaseV113.requireOwnedTransition(record.id, changed)
        } finally {
            helper.close()
        }
    }

    private fun markDeliveryFailure(
        record: OutboxDeliveryRecord,
        message: String,
        forcePermanent: Boolean = false,
    ): Boolean {
        val helper = RegisterDatabase(appContext)
        return try {
            val db = helper.writableDatabase
            val attempts = record.attemptCount + 1
            val permanent = forcePermanent || OutboxDeliveryRetryPolicy.permanent(attempts)
            val changed = db.update(
                "sync_outbox",
                ContentValues().apply {
                    put("status", if (permanent) SyncOutboxStatus.FAILED.name else SyncOutboxStatus.STAGED.name)
                    put("attempt_count", attempts)
                    put(
                        "next_attempt_at",
                        if (permanent) Long.MAX_VALUE
                        else System.currentTimeMillis() + OutboxDeliveryRetryPolicy.delayMillis(attempts),
                    )
                    put("last_error", message)
                    putNull("processing_started_at")
                    putNull("lease_until")
                    putNull("worker_token")
                    put("updated_at", System.currentTimeMillis())
                },
                "id=? AND status='STAGED' AND worker_token=?",
                arrayOf(record.id.toString(), record.workerToken),
            )
            OutboxExternalDeliveryLeaseV113.requireOwnedTransition(record.id, changed)
            permanent
        } finally {
            helper.close()
        }
    }

    private fun copyWithLimit(input: InputStream, output: java.io.OutputStream, maxBytes: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            require(total <= maxBytes) { "同期JSONが上限サイズを超えています" }
            output.write(buffer, 0, read)
        }
        output.flush()
        return total
    }

    private fun sha256(input: InputStream): String = input.use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") {
            "%02x".format(Locale.ROOT, it.toInt() and 0xff)
        }
    }
}

object OutboxDeliveryAudit {
    fun record(
        context: Context,
        eventType: String,
        detail: String,
        referenceId: Long = 0L,
        actorName: String = "同期Worker",
    ) {
        val appContext = context.applicationContext
        OperationsStore(appContext).close()
        RegisterDatabase(appContext).use { helper ->
            helper.writableDatabase.insertOrThrow(
                "operation_audit",
                null,
                ContentValues().apply {
                    put("event_type", eventType)
                    put("reference_id", referenceId)
                    put("detail", detail.take(1000))
                    put("operator_name", actorName.ifBlank { "同期Worker" })
                    put("created_at", System.currentTimeMillis())
                },
            )
        }
    }
}

object OutboxDeliveryNotificationCoordinator {
    private const val CHANNEL_ID = "outbox_delivery_failures"
    private const val NOTIFICATION_ID = 12_035

    fun clear(context: Context) {
        NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
    }

    fun apply(
        context: Context,
        result: OutboxDeliveryResultState,
        detail: String,
    ) {
        val appContext = context.applicationContext
        val settings = OutboxDeliverySettingsStore(appContext).load()
        if (!settings.failureNotificationsEnabled) return
        if (result !in setOf(
                OutboxDeliveryResultState.FAILED,
                OutboxDeliveryResultState.PERMISSION_LOST,
            )
        ) return
        if (!canPostNotification(appContext)) return
        createChannel(appContext)
        val intent = Intent(appContext, OutboxDeliverySettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            35,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val permissionLost = result == OutboxDeliveryResultState.PERMISSION_LOST
        val title = if (permissionLost) {
            "つぐレジ：売上同期先を選び直してください"
        } else {
            "つぐレジ：売上ジャーナル送信に失敗"
        }
        val instruction = if (permissionLost) {
            "同期基盤の外部自動送信設定を開き、保存先フォルダを再選択してください。"
        } else {
            "売上はSQLiteへ確定済みです。送信先、空き容量、通信状態を確認してください。"
        }
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$detail\n$instruction"))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
    }

    private fun canPostNotification(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "売上ジャーナル同期異常",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Google Drive・USB・端末フォルダへの売上ジャーナル送信失敗を通知"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
