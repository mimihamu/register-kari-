package jp.co.tenposinfo.register

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val EXTERNAL_BACKUP_SETTINGS_PREFS = "external_backup_settings_v1"
private const val EXTERNAL_BACKUP_STATUS_PREFS = "external_backup_status_v1"
private const val EXTERNAL_BACKUP_RECEIPTS_PREFS = "external_backup_receipts_v1"
private const val EXTERNAL_BACKUP_MIME = "application/octet-stream"
private const val EXTERNAL_BACKUP_MAX_BYTES = 600L * 1024L * 1024L

data class ExternalBackupSettings(
    val enabled: Boolean = false,
    val treeUri: String? = null,
    val destinationLabel: String? = null,
    val unmeteredNetworkOnly: Boolean = false,
    val failureNotificationsEnabled: Boolean = true,
)

object ExternalBackupSettingsPolicy {
    fun sanitized(settings: ExternalBackupSettings): ExternalBackupSettings {
        val uri = settings.treeUri?.trim()?.takeIf(String::isNotEmpty)
        return settings.copy(
            enabled = settings.enabled && uri != null,
            treeUri = uri,
            destinationLabel = settings.destinationLabel?.trim()?.takeIf(String::isNotEmpty),
        )
    }

    fun validated(settings: ExternalBackupSettings): ExternalBackupSettings {
        val sanitized = sanitized(settings)
        require(!settings.enabled || sanitized.treeUri != null) {
            "外部自動保存をONにするには保存先フォルダを選択してください"
        }
        sanitized.treeUri?.let {
            require(it.startsWith("${ContentResolver.SCHEME_CONTENT}://") && "/tree/" in it) {
                "外部保存先はAndroidのフォルダ選択から指定してください"
            }
        }
        return sanitized
    }
}

class ExternalBackupSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        EXTERNAL_BACKUP_SETTINGS_PREFS,
        Context.MODE_PRIVATE,
    )

    fun load(): ExternalBackupSettings = ExternalBackupSettingsPolicy.sanitized(
        ExternalBackupSettings(
            enabled = preferences.getBoolean("enabled", false),
            treeUri = preferences.getString("tree_uri", null),
            destinationLabel = preferences.getString("destination_label", null),
            unmeteredNetworkOnly = preferences.getBoolean("unmetered_only", false),
            failureNotificationsEnabled = preferences.getBoolean("failure_notifications", true),
        ),
    )

    fun save(settings: ExternalBackupSettings): ExternalBackupSettings {
        val validated = ExternalBackupSettingsPolicy.validated(settings)
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

enum class ExternalBackupMirrorResultState(val displayName: String) {
    NEVER("未実行"),
    IDLE("待機中"),
    RUNNING("外部保存中"),
    SUCCESS("成功"),
    FAILED("失敗"),
    DESTINATION_MISSING("保存先未設定"),
    PERMISSION_LOST("保存先権限なし"),
}

data class ExternalBackupMirrorStatus(
    val result: ExternalBackupMirrorResultState = ExternalBackupMirrorResultState.NEVER,
    val lastStartedAt: Long? = null,
    val lastCompletedAt: Long? = null,
    val lastFileName: String? = null,
    val lastError: String? = null,
    val pendingCount: Int = 0,
)

class ExternalBackupStatusStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        EXTERNAL_BACKUP_STATUS_PREFS,
        Context.MODE_PRIVATE,
    )

    fun load(): ExternalBackupMirrorStatus = ExternalBackupMirrorStatus(
        result = runCatching {
            ExternalBackupMirrorResultState.valueOf(
                preferences.getString("result", ExternalBackupMirrorResultState.NEVER.name).orEmpty(),
            )
        }.getOrDefault(ExternalBackupMirrorResultState.NEVER),
        lastStartedAt = preferences.getLong("last_started_at", 0L).takeIf { it > 0L },
        lastCompletedAt = preferences.getLong("last_completed_at", 0L).takeIf { it > 0L },
        lastFileName = preferences.getString("last_file_name", null),
        lastError = preferences.getString("last_error", null),
        pendingCount = preferences.getInt("pending_count", 0).coerceAtLeast(0),
    )

    fun idle(pendingCount: Int) {
        preferences.edit()
            .putString("result", ExternalBackupMirrorResultState.IDLE.name)
            .putInt("pending_count", pendingCount.coerceAtLeast(0))
            .remove("last_error")
            .apply()
    }

    fun running(pendingCount: Int) {
        preferences.edit()
            .putString("result", ExternalBackupMirrorResultState.RUNNING.name)
            .putLong("last_started_at", System.currentTimeMillis())
            .putInt("pending_count", pendingCount.coerceAtLeast(0))
            .remove("last_error")
            .apply()
    }

    fun success(fileName: String?, pendingCount: Int) = write(
        result = ExternalBackupMirrorResultState.SUCCESS,
        fileName = fileName,
        pendingCount = pendingCount,
        completed = true,
    )

    fun failed(
        result: ExternalBackupMirrorResultState,
        error: String,
        pendingCount: Int,
    ) = write(
        result = result,
        error = error,
        pendingCount = pendingCount,
        completed = true,
    )

    private fun write(
        result: ExternalBackupMirrorResultState,
        fileName: String? = null,
        error: String? = null,
        pendingCount: Int,
        completed: Boolean,
    ) {
        preferences.edit()
            .putString("result", result.name)
            .putInt("pending_count", pendingCount.coerceAtLeast(0))
            .apply {
                if (completed) putLong("last_completed_at", System.currentTimeMillis())
                if (fileName == null) remove("last_file_name") else putString("last_file_name", fileName)
                if (error == null) remove("last_error") else putString("last_error", error)
            }
            .apply()
    }
}

object ExternalBackupDestinationAccess {
    fun hasPersistedWritePermission(context: Context, treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == treeUri && permission.isReadPermission && permission.isWritePermission
        }

    fun destinationKey(treeUri: Uri): String = MessageDigest.getInstance("SHA-256")
        .digest(treeUri.toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
        .take(24)
}

data class ExternalBackupMirrorEntry(
    val fileName: String,
    val createdAt: Long,
    val valid: Boolean,
    val state: AutoBackupFileState,
    val mirroredDestinationKey: String?,
)

object ExternalBackupMirrorPolicy {
    fun pending(
        entries: List<ExternalBackupMirrorEntry>,
        destinationKey: String,
    ): List<ExternalBackupMirrorEntry> = entries
        .filter {
            it.valid &&
                it.state == AutoBackupFileState.READY &&
                it.mirroredDestinationKey != destinationKey
        }
        .sortedWith(compareBy<ExternalBackupMirrorEntry> { it.createdAt }.thenBy { it.fileName })
}

object ExternalBackupFileNamePolicy {
    fun partialName(fileName: String): String =
        "${BackupFilePolicy.requireSafe(fileName)}.partial"
}

class ExternalBackupReceiptStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        EXTERNAL_BACKUP_RECEIPTS_PREFS,
        Context.MODE_PRIVATE,
    )

    fun destinationKeyFor(fileName: String): String? =
        preferences.getString(receiptKey(fileName), null)

    fun mark(fileName: String, destinationKey: String) {
        BackupFilePolicy.requireSafe(fileName)
        require(destinationKey.matches(Regex("[0-9a-f]{24}"))) { "外部保存先キーが不正です" }
        preferences.edit().putString(receiptKey(fileName), destinationKey).apply()
    }

    private fun receiptKey(fileName: String): String =
        "mirror_${BackupFilePolicy.requireSafe(fileName)}"
}

internal data class ExternalDocument(
    val uri: Uri,
    val displayName: String,
    val size: Long?,
)

internal object ExternalBackupDocumentProvider {
    fun displayName(context: Context, treeUri: Uri): String {
        val root = rootDocumentUri(treeUri)
        return queryDocument(context.contentResolver, root)?.displayName
            ?: runCatching { DocumentsContract.getTreeDocumentId(treeUri).substringAfterLast('/') }.getOrNull()
            ?: "選択済みフォルダ"
    }

    fun findChild(context: Context, treeUri: Uri, displayName: String): ExternalDocument? {
        val resolver = context.contentResolver
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) != displayName) continue
                val id = cursor.getString(idIndex)
                val size = cursor.longOrNull(sizeIndex)
                return ExternalDocument(
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                    displayName = displayName,
                    size = size,
                )
            }
        }
        return null
    }

    fun createChild(context: Context, treeUri: Uri, displayName: String): Uri =
        DocumentsContract.createDocument(
            context.contentResolver,
            rootDocumentUri(treeUri),
            EXTERNAL_BACKUP_MIME,
            displayName,
        ) ?: error("外部保存先へファイルを作成できません")

    fun rename(context: Context, documentUri: Uri, displayName: String): Uri? =
        DocumentsContract.renameDocument(context.contentResolver, documentUri, displayName)

    fun delete(context: Context, documentUri: Uri): Boolean =
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, documentUri) }
            .getOrDefault(false)

    fun size(context: Context, documentUri: Uri): Long? =
        queryDocument(context.contentResolver, documentUri)?.size

    private fun rootDocumentUri(treeUri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )

    private fun queryDocument(resolver: ContentResolver, documentUri: Uri): ExternalDocument? =
        resolver.query(
            documentUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            ExternalDocument(
                uri = documentUri,
                displayName = cursor.getString(nameIndex),
                size = cursor.longOrNull(sizeIndex),
            )
        }

    private fun Cursor.longOrNull(index: Int): Long? =
        if (index < 0 || isNull(index)) null else getLong(index)
}

private data class ExternalBackupMirrorRunResult(
    val exportedCount: Int,
    val lastFileName: String?,
    val pendingCount: Int,
)

private class ExternalBackupMirrorService(private val context: Context) {
    private val appContext = context.applicationContext
    private val manager = DataProtectionManager(appContext)
    private val metadataStore = AutoBackupMetadataStore(appContext)
    private val receiptStore = ExternalBackupReceiptStore(appContext)

    fun mirrorPending(settings: ExternalBackupSettings): ExternalBackupMirrorRunResult {
        val treeUri = Uri.parse(settings.treeUri ?: error("外部保存先が設定されていません"))
        require(ExternalBackupDestinationAccess.hasPersistedWritePermission(appContext, treeUri)) {
            "外部保存先への書込権限が失われています"
        }
        val destinationKey = ExternalBackupDestinationAccess.destinationKey(treeUri)
        val backups = manager.listBackups()
        val metadata = metadataStore.readAll()
        val entries = backups.map { backup ->
            ExternalBackupMirrorEntry(
                fileName = backup.fileName,
                createdAt = backup.createdAt,
                valid = backup.valid,
                state = metadata[backup.fileName]?.state ?: AutoBackupFileState.READY,
                mirroredDestinationKey = receiptStore.destinationKeyFor(backup.fileName),
            )
        }
        val pending = ExternalBackupMirrorPolicy.pending(entries, destinationKey)
        var exported = 0
        var lastFile: String? = null
        pending.forEach { entry ->
            mirrorOne(treeUri, destinationKey, entry.fileName)
            exported++
            lastFile = entry.fileName
        }
        val remaining = ExternalBackupMirrorPolicy.pending(
            entries = manager.listBackups().map { backup ->
                ExternalBackupMirrorEntry(
                    fileName = backup.fileName,
                    createdAt = backup.createdAt,
                    valid = backup.valid,
                    state = metadataStore.find(backup.fileName)?.state ?: AutoBackupFileState.READY,
                    mirroredDestinationKey = receiptStore.destinationKeyFor(backup.fileName),
                )
            },
            destinationKey = destinationKey,
        ).size
        return ExternalBackupMirrorRunResult(exported, lastFile, remaining)
    }

    fun pendingCount(settings: ExternalBackupSettings): Int {
        val treeUri = settings.treeUri?.let(Uri::parse) ?: return 0
        val destinationKey = ExternalBackupDestinationAccess.destinationKey(treeUri)
        val metadata = metadataStore.readAll()
        return ExternalBackupMirrorPolicy.pending(
            manager.listBackups().map { backup ->
                ExternalBackupMirrorEntry(
                    fileName = backup.fileName,
                    createdAt = backup.createdAt,
                    valid = backup.valid,
                    state = metadata[backup.fileName]?.state ?: AutoBackupFileState.READY,
                    mirroredDestinationKey = receiptStore.destinationKeyFor(backup.fileName),
                )
            },
            destinationKey,
        ).size
    }

    private fun mirrorOne(treeUri: Uri, destinationKey: String, fileName: String) {
        val safeName = BackupFilePolicy.requireSafe(fileName)
        val record = manager.listBackups().firstOrNull { it.fileName == safeName }
            ?: error("内部バックアップが見つかりません: $safeName")
        val verification = manager.verifyBackup(safeName)
        val internalArchive = File(appContext.filesDir, "data_backups/$safeName")
        require(internalArchive.isFile && internalArchive.length() == record.sizeBytes) {
            "内部バックアップの実ファイルが一致しません: $safeName"
        }
        val internalArchiveSha256 = sha256(internalArchive.inputStream())
        val existing = ExternalBackupDocumentProvider.findChild(appContext, treeUri, safeName)
        if (
            existing != null &&
            existing.size == record.sizeBytes &&
            record.sizeBytes > 0L &&
            sha256(appContext.contentResolver.openInputStream(existing.uri) ?: throw FileNotFoundException("外部保存済みファイルを検証できません")) == internalArchiveSha256
        ) {
            metadataStore.registerExport(
                BackupExportResult(safeName, record.sizeBytes, verification.manifest),
            )
            receiptStore.mark(safeName, destinationKey)
            AutoBackupAudit.record(
                appContext,
                "DATA_BACKUP_EXTERNAL_AUTO_SKIPPED_DUPLICATE",
                "$safeName / archiveSha256=$internalArchiveSha256 / destination=$destinationKey",
                "外部自動保存",
            )
            return
        }
        if (existing != null) {
            require(ExternalBackupDocumentProvider.delete(appContext, existing.uri)) {
                "外部保存先の同名ファイルを置き換えられません: $safeName"
            }
        }

        val partialName = ExternalBackupFileNamePolicy.partialName(safeName)
        ExternalBackupDocumentProvider.findChild(appContext, treeUri, partialName)?.let {
            require(ExternalBackupDocumentProvider.delete(appContext, it.uri)) {
                "外部保存先の前回一時ファイルを削除できません: $partialName"
            }
        }
        val partialUri = ExternalBackupDocumentProvider.createChild(appContext, treeUri, partialName)
        var committedUri: Uri? = null
        var externalCommitted = false
        try {
            val exported = appContext.contentResolver.openOutputStream(partialUri, "w")?.use { output ->
                manager.copyVerifiedBackup(safeName, output)
            } ?: throw FileNotFoundException("外部保存先の一時ファイルを開けません")
            val partialSize = ExternalBackupDocumentProvider.size(appContext, partialUri)
            require(partialSize == null || partialSize == exported.bytesWritten) {
                "外部保存先への書込サイズが一致しません"
            }
            val partialSha256 = sha256(
                appContext.contentResolver.openInputStream(partialUri)
                    ?: throw FileNotFoundException("外部保存先の一時ファイルを検証できません"),
            )
            require(partialSha256 == internalArchiveSha256) {
                "外部保存先の一時ファイルSHA-256が一致しません"
            }
            val renamed = runCatching {
                ExternalBackupDocumentProvider.rename(appContext, partialUri, safeName)
            }.getOrNull()
            val finalUri = renamed ?: copyPartialToFinal(treeUri, partialUri, safeName, exported.bytesWritten)
            committedUri = finalUri
            val finalSize = ExternalBackupDocumentProvider.size(appContext, finalUri)
            require(finalSize == null || finalSize == exported.bytesWritten) {
                "外部保存ファイルの確定サイズが一致しません"
            }
            val finalSha256 = sha256(
                appContext.contentResolver.openInputStream(finalUri)
                    ?: throw FileNotFoundException("外部保存ファイルを検証できません"),
            )
            require(finalSha256 == internalArchiveSha256) {
                "外部保存ファイルのSHA-256が一致しません"
            }
            externalCommitted = true
            metadataStore.registerExport(exported)
            receiptStore.mark(safeName, destinationKey)
            AutoBackupAudit.record(
                appContext,
                "DATA_BACKUP_EXTERNAL_AUTO_EXPORTED",
                "$safeName / ${exported.bytesWritten} bytes / archiveSha256=$internalArchiveSha256 / destination=$destinationKey",
                "外部自動保存",
            )
        } catch (error: Throwable) {
            if (!externalCommitted) {
                committedUri?.let { ExternalBackupDocumentProvider.delete(appContext, it) }
                ExternalBackupDocumentProvider.delete(appContext, partialUri)
            }
            throw error
        }
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
        digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }

    private fun copyPartialToFinal(
        treeUri: Uri,
        partialUri: Uri,
        finalName: String,
        expectedBytes: Long,
    ): Uri {
        val finalUri = ExternalBackupDocumentProvider.createChild(appContext, treeUri, finalName)
        try {
            val copied = appContext.contentResolver.openInputStream(partialUri)?.use { input ->
                appContext.contentResolver.openOutputStream(finalUri, "w")?.use { output ->
                    BackupTransferPolicy.copyWithLimit(input, output, EXTERNAL_BACKUP_MAX_BYTES)
                }
            } ?: throw FileNotFoundException("外部保存先の一時ファイルを再読込できません")
            require(copied == expectedBytes) { "外部保存ファイルの確定コピーサイズが一致しません" }
            ExternalBackupDocumentProvider.delete(appContext, partialUri)
            return finalUri
        } catch (error: Throwable) {
            ExternalBackupDocumentProvider.delete(appContext, finalUri)
            throw error
        }
    }
}

object ExternalBackupScheduler {
    private const val PERIODIC_WORK_NAME = "tsuguregi-external-backup-mirror"
    private const val IMMEDIATE_WORK_NAME = "tsuguregi-external-backup-mirror-now"
    private const val KEY_TRIGGER = "trigger"

    fun apply(
        context: Context,
        replaceExisting: Boolean = false,
    ) {
        val appContext = context.applicationContext
        val settings = ExternalBackupSettingsStore(appContext).load()
        val manager = WorkManager.getInstance(appContext)
        if (!settings.enabled || settings.treeUri == null) {
            manager.cancelUniqueWork(PERIODIC_WORK_NAME)
            manager.cancelUniqueWork(IMMEDIATE_WORK_NAME)
            ExternalBackupStatusStore(appContext).idle(0)
            return
        }
        val request = PeriodicWorkRequestBuilder<ExternalBackupMirrorWorker>(
            15,
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints(settings))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        manager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            if (replaceExisting) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
        enqueueNow(appContext, "scheduler-apply")
    }

    fun enqueueNow(context: Context, trigger: String) {
        val appContext = context.applicationContext
        val settings = ExternalBackupSettingsStore(appContext).load()
        if (!settings.enabled || settings.treeUri == null) return
        val request = OneTimeWorkRequestBuilder<ExternalBackupMirrorWorker>()
            .setInputData(Data.Builder().putString(KEY_TRIGGER, trigger).build())
            .setConstraints(constraints(settings))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    private fun constraints(settings: ExternalBackupSettings): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(
                if (settings.unmeteredNetworkOnly) NetworkType.UNMETERED else NetworkType.NOT_REQUIRED,
            )
            .setRequiresStorageNotLow(true)
            .build()
}

class ExternalBackupMirrorWorker(
    context: Context,
    parameters: WorkerParameters,
) : Worker(context, parameters) {
    override fun doWork(): Result {
        val appContext = applicationContext
        val settings = ExternalBackupSettingsStore(appContext).load()
        val statusStore = ExternalBackupStatusStore(appContext)
        if (!settings.enabled) {
            statusStore.idle(0)
            ExternalBackupFailureNotificationCoordinator.clear(appContext)
            return Result.success()
        }
        val treeUriText = settings.treeUri
        if (treeUriText == null) {
            statusStore.failed(
                ExternalBackupMirrorResultState.DESTINATION_MISSING,
                "外部保存先が未設定です",
                0,
            )
            return Result.success()
        }
        val treeUri = Uri.parse(treeUriText)
        if (!ExternalBackupDestinationAccess.hasPersistedWritePermission(appContext, treeUri)) {
            val message = "外部保存先への書込権限がありません。保存先を選び直してください。"
            statusStore.failed(ExternalBackupMirrorResultState.PERMISSION_LOST, message, 0)
            ExternalBackupFailureNotificationCoordinator.apply(
                appContext,
                ExternalBackupMirrorResultState.PERMISSION_LOST,
                message,
            )
            runCatching {
                AutoBackupAudit.record(
                    appContext,
                    "DATA_BACKUP_EXTERNAL_DESTINATION_PERMISSION_LOST",
                    message,
                    "外部自動保存",
                )
            }
            return Result.failure()
        }

        val service = ExternalBackupMirrorService(appContext)
        val pendingBefore = runCatching { service.pendingCount(settings) }.getOrDefault(0)
        if (pendingBefore == 0) {
            statusStore.idle(0)
            ExternalBackupFailureNotificationCoordinator.clear(appContext)
            return Result.success()
        }
        statusStore.running(pendingBefore)
        runCatching {
            AutoBackupAudit.record(
                appContext,
                "DATA_BACKUP_EXTERNAL_AUTO_REQUESTED",
                "pending=$pendingBefore / trigger=${inputData.getString("trigger").orEmpty()}",
                "外部自動保存",
            )
        }

        return try {
            val result = service.mirrorPending(settings)
            statusStore.success(result.lastFileName, result.pendingCount)
            ExternalBackupFailureNotificationCoordinator.clear(appContext)
            Result.success(
                Data.Builder()
                    .putInt("exported_count", result.exportedCount)
                    .putInt("pending_count", result.pendingCount)
                    .putString("last_file", result.lastFileName)
                    .build(),
            )
        } catch (error: Throwable) {
            val message = error.message ?: error.javaClass.simpleName
            val permissionLost = error is SecurityException ||
                !ExternalBackupDestinationAccess.hasPersistedWritePermission(appContext, treeUri)
            val state = if (permissionLost) {
                ExternalBackupMirrorResultState.PERMISSION_LOST
            } else {
                ExternalBackupMirrorResultState.FAILED
            }
            val pending = runCatching { service.pendingCount(settings) }.getOrDefault(pendingBefore)
            statusStore.failed(state, message, pending)
            ExternalBackupFailureNotificationCoordinator.apply(appContext, state, message)
            runCatching {
                AutoBackupAudit.record(
                    appContext,
                    "DATA_BACKUP_EXTERNAL_AUTO_FAILED",
                    "$state / $message / attempt=$runAttemptCount",
                    "外部自動保存",
                )
            }
            if (!permissionLost && runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }
}

object ExternalBackupFailureNotificationCoordinator {
    private const val CHANNEL_ID = "external_backup_failures"
    private const val NOTIFICATION_ID = 12_034

    fun clear(context: Context) {
        NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
    }

    fun apply(
        context: Context,
        result: ExternalBackupMirrorResultState,
        detail: String,
    ) {
        val appContext = context.applicationContext
        val settings = ExternalBackupSettingsStore(appContext).load()
        if (!settings.failureNotificationsEnabled) return
        if (result !in setOf(
                ExternalBackupMirrorResultState.FAILED,
                ExternalBackupMirrorResultState.PERMISSION_LOST,
            )
        ) return
        if (!canPostNotification(appContext)) return
        createChannel(appContext)
        val intent = Intent(appContext, ExternalBackupSettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            34,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (result == ExternalBackupMirrorResultState.PERMISSION_LOST) {
            "つぐレジ：外部保存先を選び直してください"
        } else {
            "つぐレジ：外部バックアップ保存に失敗"
        }
        val instruction = if (result == ExternalBackupMirrorResultState.PERMISSION_LOST) {
            "外部自動保存設定を開き、Google Drive・USB・端末フォルダを再選択してください。"
        } else {
            "内部バックアップは保持されています。外部自動保存設定で保存先と空き容量を確認してください。"
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
            "外部バックアップ異常",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Google Drive・USB・端末フォルダへの自動保存失敗を管理者へ通知"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
