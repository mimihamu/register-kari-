package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.os.StatFs
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val AUTO_BACKUP_PREFS = "auto_backup_status_v1"
private const val AUTO_BACKUP_METADATA_DIR = ".metadata"
private const val AUTO_BACKUP_SAFETY_MARGIN_BYTES = 128L * 1024L * 1024L
private const val AUTO_BACKUP_MIN_WORK_BYTES = 32L * 1024L * 1024L
private const val AUTO_BACKUP_STALE_TEMP_MILLIS = 60L * 60L * 1000L

const val DEFAULT_Z_BACKUP_BUSINESS_DAYS = 14
const val DEFAULT_MONTHLY_BACKUP_MONTHS = 12

enum class BackupCreationReason(val displayName: String) {
    MANUAL("手動バックアップ"),
    MANUAL_AUTO("自動バックアップを今すぐ実行"),
    Z_SETTLEMENT("Z精算後"),
    PERIODIC("定期バックアップ"),
}

enum class AutoBackupFileState {
    VERIFYING,
    READY,
    CORRUPT,
}

enum class AutoBackupResultState(val displayName: String) {
    NEVER("未実行"),
    REQUESTED("実行待ち"),
    CREATED("成功"),
    FAILED("失敗"),
    SKIPPED_LOW_STORAGE("容量不足で中止"),
    SKIPPED_DUPLICATE("重複のため省略"),
}

data class AutoBackupMetadata(
    val fileName: String,
    val reason: BackupCreationReason,
    val businessDate: String?,
    val businessSessionId: Long?,
    val settlementId: Long?,
    val createdAt: Long,
    val appVersion: String,
    val databaseSha256: String,
    val exportedExternally: Boolean,
    val lastVerifiedAt: Long?,
    val state: AutoBackupFileState,
)

data class AutoBackupRuntimeStatus(
    val enabled: Boolean = true,
    val lastRequestedAt: Long? = null,
    val lastCompletedAt: Long? = null,
    val lastResult: AutoBackupResultState = AutoBackupResultState.NEVER,
    val lastReason: BackupCreationReason? = null,
    val lastError: String? = null,
    val lastRetentionResult: String? = null,
    val nextCondition: String = "Z精算が正常に確定した後",
)

data class BackupRetentionEntry(
    val fileName: String,
    val createdAt: Long,
    val valid: Boolean,
    val reason: BackupCreationReason?,
    val businessDate: String?,
    val state: AutoBackupFileState,
    val pendingRestore: Boolean,
)

data class BackupRetentionResult(
    val planned: List<String>,
    val deleted: List<String>,
    val failed: Map<String, String>,
)

object AutoBackupTriggerPolicy {
    fun shouldEnqueue(type: SettlementReportType, settlementCommitted: Boolean): Boolean =
        settlementCommitted && type == SettlementReportType.Z_SETTLEMENT

    fun uniqueZWorkName(settlementId: Long): String = "tsuguregi-auto-backup-z-$settlementId"
}

object AutoBackupStoragePolicy {
    fun estimatedWorkingBytes(databaseBytes: Long): Long =
        maxOf(AUTO_BACKUP_MIN_WORK_BYTES, databaseBytes.coerceAtLeast(0L) * 2L + AUTO_BACKUP_MIN_WORK_BYTES)

    fun safetyMarginBytes(databaseBytes: Long): Long =
        maxOf(AUTO_BACKUP_SAFETY_MARGIN_BYTES, databaseBytes.coerceAtLeast(0L))

    fun hasCapacity(availableBytes: Long, databaseBytes: Long): Boolean =
        availableBytes >= estimatedWorkingBytes(databaseBytes) + safetyMarginBytes(databaseBytes)
}

object AutoBackupRetentionPolicy {
    fun selectDeletionCandidates(
        entries: List<BackupRetentionEntry>,
        zBusinessDays: Int = DEFAULT_Z_BACKUP_BUSINESS_DAYS,
        monthlyMonths: Int = DEFAULT_MONTHLY_BACKUP_MONTHS,
    ): Set<String> {
        require(zBusinessDays >= 1) { "Z精算バックアップの保持営業日は1以上が必要です" }
        require(monthlyMonths >= 1) { "月次バックアップの保持月数は1以上が必要です" }
        if (entries.isEmpty()) return emptySet()

        val protected = mutableSetOf<String>()
        entries.filter { !it.valid || it.pendingRestore || it.state != AutoBackupFileState.READY }
            .mapTo(protected) { it.fileName }
        entries.filter { it.reason == null || it.reason == BackupCreationReason.MANUAL || it.reason == BackupCreationReason.MANUAL_AUTO }
            .mapTo(protected) { it.fileName }
        entries.filter { it.valid && it.state == AutoBackupFileState.READY }
            .maxByOrNull { it.createdAt }
            ?.let { protected += it.fileName }

        val deletion = mutableSetOf<String>()
        val zEntries = entries.filter { it.reason == BackupCreationReason.Z_SETTLEMENT }
        val retainedDates = zEntries.mapNotNull { it.businessDate }
            .distinct()
            .sortedDescending()
            .take(zBusinessDays)
            .toSet()
        zEntries.groupBy { it.businessDate }.forEach { (date, sameDate) ->
            if (date == null) return@forEach
            val newest = sameDate.maxByOrNull { it.createdAt }?.fileName
            sameDate.forEach { entry ->
                if (entry.fileName !in protected && (date !in retainedDates || entry.fileName != newest)) {
                    deletion += entry.fileName
                }
            }
        }

        val periodic = entries.filter { it.reason == BackupCreationReason.PERIODIC }
        val retainedMonths = periodic.map { monthKey(it.createdAt) }
            .distinct()
            .sortedDescending()
            .take(monthlyMonths)
            .toSet()
        periodic.groupBy { monthKey(it.createdAt) }.forEach { (month, sameMonth) ->
            val newest = sameMonth.maxByOrNull { it.createdAt }?.fileName
            sameMonth.forEach { entry ->
                if (entry.fileName !in protected && (month !in retainedMonths || entry.fileName != newest)) {
                    deletion += entry.fileName
                }
            }
        }
        return deletion
    }

    private fun monthKey(createdAt: Long): String {
        val date = Instant.ofEpochMilli(createdAt).atZone(ZoneOffset.UTC)
        return "%04d-%02d".format(Locale.ROOT, date.year, date.monthValue)
    }
}

class AutoBackupMetadataStore(context: Context) {
    private val metadataDir = File(context.applicationContext.filesDir, "data_backups/$AUTO_BACKUP_METADATA_DIR").apply { mkdirs() }

    fun readAll(): Map<String, AutoBackupMetadata> = metadataDir.listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(".meta") }
        .mapNotNull { file -> runCatching { decode(file.readText(Charsets.UTF_8)) }.getOrNull() }
        .associateBy { it.fileName }

    fun find(fileName: String): AutoBackupMetadata? = readAll()[fileName]

    fun hasSettlement(settlementId: Long): Boolean = readAll().values.any {
        it.reason == BackupCreationReason.Z_SETTLEMENT && it.settlementId == settlementId && it.state == AutoBackupFileState.READY
    }

    fun write(metadata: AutoBackupMetadata) {
        BackupFilePolicy.requireSafe(metadata.fileName)
        val target = File(metadataDir, "${metadata.fileName}.meta")
        val temporary = File(metadataDir, "${metadata.fileName}.${UUID.randomUUID()}.tmp")
        try {
            temporary.writeText(encode(metadata), Charsets.UTF_8)
            DataProtectionManager.atomicReplace(temporary, target)
        } finally {
            temporary.delete()
        }
    }

    fun markExported(fileName: String) {
        val current = find(fileName) ?: return
        write(current.copy(exportedExternally = true))
    }

    fun delete(fileName: String) {
        File(metadataDir, "${BackupFilePolicy.requireSafe(fileName)}.meta").delete()
    }

    private fun encode(value: AutoBackupMetadata): String = buildString {
        appendLine("file_name=${value.fileName}")
        appendLine("reason=${value.reason.name}")
        appendLine("business_date=${value.businessDate.orEmpty()}")
        appendLine("business_session_id=${value.businessSessionId?.toString().orEmpty()}")
        appendLine("settlement_id=${value.settlementId?.toString().orEmpty()}")
        appendLine("created_at=${value.createdAt}")
        appendLine("app_version=${value.appVersion}")
        appendLine("database_sha256=${value.databaseSha256.lowercase(Locale.ROOT)}")
        appendLine("exported_externally=${value.exportedExternally}")
        appendLine("last_verified_at=${value.lastVerifiedAt?.toString().orEmpty()}")
        appendLine("state=${value.state.name}")
    }

    private fun decode(text: String): AutoBackupMetadata {
        val values = DataProtectionManager.readSimpleProperties(text)
        val fileName = BackupFilePolicy.requireSafe(values.getValue("file_name"))
        val hash = values.getValue("database_sha256").lowercase(Locale.ROOT)
        require(hash.matches(Regex("[0-9a-f]{64}"))) { "自動バックアップメタデータのSHA-256が不正です" }
        return AutoBackupMetadata(
            fileName = fileName,
            reason = BackupCreationReason.valueOf(values.getValue("reason")),
            businessDate = values["business_date"]?.takeIf(String::isNotBlank),
            businessSessionId = values["business_session_id"]?.toLongOrNull(),
            settlementId = values["settlement_id"]?.toLongOrNull(),
            createdAt = values.getValue("created_at").toLong(),
            appVersion = values.getValue("app_version"),
            databaseSha256 = hash,
            exportedExternally = values["exported_externally"].toBoolean(),
            lastVerifiedAt = values["last_verified_at"]?.toLongOrNull(),
            state = AutoBackupFileState.valueOf(values.getValue("state")),
        )
    }
}

class AutoBackupStatusStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(AUTO_BACKUP_PREFS, Context.MODE_PRIVATE)

    fun load(): AutoBackupRuntimeStatus = AutoBackupRuntimeStatus(
        enabled = true,
        lastRequestedAt = preferences.getLong("last_requested_at", 0L).takeIf { it > 0L },
        lastCompletedAt = preferences.getLong("last_completed_at", 0L).takeIf { it > 0L },
        lastResult = runCatching {
            AutoBackupResultState.valueOf(preferences.getString("last_result", AutoBackupResultState.NEVER.name).orEmpty())
        }.getOrDefault(AutoBackupResultState.NEVER),
        lastReason = preferences.getString("last_reason", null)?.let { runCatching { BackupCreationReason.valueOf(it) }.getOrNull() },
        lastError = preferences.getString("last_error", null),
        lastRetentionResult = preferences.getString("last_retention_result", null),
    )

    fun requested(reason: BackupCreationReason, at: Long = System.currentTimeMillis()) {
        preferences.edit()
            .putLong("last_requested_at", at)
            .putString("last_result", AutoBackupResultState.REQUESTED.name)
            .putString("last_reason", reason.name)
            .remove("last_error")
            .apply()
    }

    fun completed(reason: BackupCreationReason, result: AutoBackupResultState, error: String? = null) {
        preferences.edit()
            .putLong("last_completed_at", System.currentTimeMillis())
            .putString("last_result", result.name)
            .putString("last_reason", reason.name)
            .apply {
                if (error.isNullOrBlank()) remove("last_error") else putString("last_error", error)
            }
            .apply()
    }

    fun retention(result: String) {
        preferences.edit().putString("last_retention_result", result).apply()
    }
}

object AutoBackupAudit {
    fun record(context: Context, eventType: String, detail: String, actorName: String, referenceId: Long = 0L) {
        OperationsStore(context.applicationContext).close()
        RegisterDatabase(context.applicationContext).use { helper ->
            helper.writableDatabase.insertOrThrow("operation_audit", null, ContentValues().apply {
                put("event_type", eventType)
                put("reference_id", referenceId)
                put("detail", detail)
                put("operator_name", actorName.ifBlank { "責任者" })
                put("created_at", System.currentTimeMillis())
            })
        }
    }
}

class AutoBackupRetentionManager(context: Context) {
    private val appContext = context.applicationContext
    private val backupDir = File(appContext.filesDir, "data_backups")
    private val manager = DataProtectionManager(appContext)
    private val metadataStore = AutoBackupMetadataStore(appContext)

    fun apply(actorName: String): BackupRetentionResult {
        val backups = manager.listBackups()
        val metadata = metadataStore.readAll()
        val pendingName = manager.pendingRestoreStatus().backupFileName
        val entries = backups.map { backup ->
            val meta = metadata[backup.fileName]
            BackupRetentionEntry(
                fileName = backup.fileName,
                createdAt = backup.createdAt,
                valid = backup.valid,
                reason = meta?.reason,
                businessDate = meta?.businessDate,
                state = meta?.state ?: AutoBackupFileState.READY,
                pendingRestore = backup.fileName == pendingName,
            )
        }
        val plan = AutoBackupRetentionPolicy.selectDeletionCandidates(entries).sorted()
        AutoBackupAudit.record(appContext, "DATA_BACKUP_RETENTION_STARTED", "削除候補 ${plan.size}件", actorName)
        val deleted = mutableListOf<String>()
        val failed = linkedMapOf<String, String>()
        plan.forEach { fileName ->
            val archive = File(backupDir, BackupFilePolicy.requireSafe(fileName))
            runCatching {
                require(archive.isFile) { "対象ファイルがありません" }
                require(archive.delete()) { "バックアップを削除できません" }
                metadataStore.delete(fileName)
                deleted += fileName
                AutoBackupAudit.record(appContext, "DATA_BACKUP_RETENTION_DELETED", fileName, actorName)
            }.onFailure { error ->
                failed[fileName] = error.message ?: error.javaClass.simpleName
                runCatching {
                    AutoBackupAudit.record(appContext, "DATA_BACKUP_RETENTION_FAILED", "$fileName / ${failed[fileName]}", actorName)
                }
            }
        }
        return BackupRetentionResult(plan, deleted, failed)
    }
}

object AutoBackupScheduler {
    private const val KEY_REASON = "reason"
    private const val KEY_BUSINESS_DATE = "business_date"
    private const val KEY_BUSINESS_SESSION_ID = "business_session_id"
    private const val KEY_SETTLEMENT_ID = "settlement_id"
    private const val KEY_ACTOR_NAME = "actor_name"

    fun enqueueZSettlement(
        context: Context,
        businessDate: String,
        businessSessionId: Long,
        settlementId: Long,
        actorName: String,
    ) {
        enqueue(
            context = context,
            uniqueName = AutoBackupTriggerPolicy.uniqueZWorkName(settlementId),
            reason = BackupCreationReason.Z_SETTLEMENT,
            businessDate = businessDate,
            businessSessionId = businessSessionId,
            settlementId = settlementId,
            actorName = actorName,
        )
    }

    fun enqueueManualNow(context: Context, actorName: String) {
        enqueue(
            context = context,
            uniqueName = "tsuguregi-auto-backup-manual-${System.currentTimeMillis()}",
            reason = BackupCreationReason.MANUAL_AUTO,
            businessDate = null,
            businessSessionId = null,
            settlementId = null,
            actorName = actorName,
        )
    }

    private fun enqueue(
        context: Context,
        uniqueName: String,
        reason: BackupCreationReason,
        businessDate: String?,
        businessSessionId: Long?,
        settlementId: Long?,
        actorName: String,
    ) {
        val appContext = context.applicationContext
        val input = Data.Builder()
            .putString(KEY_REASON, reason.name)
            .putString(KEY_BUSINESS_DATE, businessDate)
            .putLong(KEY_BUSINESS_SESSION_ID, businessSessionId ?: 0L)
            .putLong(KEY_SETTLEMENT_ID, settlementId ?: 0L)
            .putString(KEY_ACTOR_NAME, actorName)
            .build()
        val request = OneTimeWorkRequestBuilder<AutoBackupWorker>()
            .setInputData(input)
            .addTag("tsuguregi-auto-backup")
            .build()
        AutoBackupStatusStore(appContext).requested(reason)
        AutoBackupAudit.record(
            appContext,
            "DATA_BACKUP_AUTO_REQUESTED",
            "${reason.name} / ${businessDate.orEmpty()} / settlement=${settlementId ?: 0L}",
            actorName,
            settlementId ?: 0L,
        )
        WorkManager.getInstance(appContext).enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request)
    }

    internal fun reason(data: Data): BackupCreationReason =
        BackupCreationReason.valueOf(data.getString(KEY_REASON) ?: error("バックアップ作成理由がありません"))

    internal fun businessDate(data: Data): String? = data.getString(KEY_BUSINESS_DATE)?.takeIf(String::isNotBlank)
    internal fun businessSessionId(data: Data): Long? = data.getLong(KEY_BUSINESS_SESSION_ID, 0L).takeIf { it > 0L }
    internal fun settlementId(data: Data): Long? = data.getLong(KEY_SETTLEMENT_ID, 0L).takeIf { it > 0L }
    internal fun actorName(data: Data): String = data.getString(KEY_ACTOR_NAME).orEmpty().ifBlank { "責任者" }
}

private object AutoBackupExecutionLock {
    val monitor = Any()
}

class AutoBackupWorker(
    context: Context,
    parameters: WorkerParameters,
) : Worker(context, parameters) {
    override fun doWork(): Result = synchronized(AutoBackupExecutionLock.monitor) {
        executeLocked()
    }

    private fun executeLocked(): Result {
        val appContext = applicationContext
        val reason = runCatching { AutoBackupScheduler.reason(inputData) }.getOrElse { return Result.failure() }
        val businessDate = AutoBackupScheduler.businessDate(inputData)
        val businessSessionId = AutoBackupScheduler.businessSessionId(inputData)
        val settlementId = AutoBackupScheduler.settlementId(inputData)
        val actorName = AutoBackupScheduler.actorName(inputData)
        val statusStore = AutoBackupStatusStore(appContext)
        val metadataStore = AutoBackupMetadataStore(appContext)

        try {
            cleanupStaleTemporaryFiles(appContext)
            if (settlementId != null && metadataStore.hasSettlement(settlementId)) {
                statusStore.completed(reason, AutoBackupResultState.SKIPPED_DUPLICATE)
                AutoBackupAudit.record(appContext, "DATA_BACKUP_SKIPPED_DUPLICATE", "settlement=$settlementId", actorName, settlementId)
                return Result.success()
            }

            val databaseBytes = databaseWorkingBytes(appContext)
            if (!AutoBackupStoragePolicy.hasCapacity(availableBytes(appContext), databaseBytes)) {
                val retention = AutoBackupRetentionManager(appContext).apply(actorName)
                statusStore.retention(retention.summary())
            }
            if (!AutoBackupStoragePolicy.hasCapacity(availableBytes(appContext), databaseBytes)) {
                val message = "空き容量不足: DB=$databaseBytes bytes / available=${availableBytes(appContext)} bytes"
                statusStore.completed(reason, AutoBackupResultState.SKIPPED_LOW_STORAGE, message)
                AutoBackupAudit.record(appContext, "DATA_BACKUP_SKIPPED_LOW_STORAGE", message, actorName, settlementId ?: 0L)
                return Result.success()
            }

            val manager = DataProtectionManager(appContext)
            val backup = manager.createBackup(actorName)
            val verification = manager.verifyBackup(backup.fileName)
            val verifiedAt = System.currentTimeMillis()
            metadataStore.write(
                AutoBackupMetadata(
                    fileName = backup.fileName,
                    reason = reason,
                    businessDate = businessDate,
                    businessSessionId = businessSessionId,
                    settlementId = settlementId,
                    createdAt = verification.manifest.createdAt,
                    appVersion = verification.manifest.appVersion,
                    databaseSha256 = verification.manifest.databaseSha256,
                    exportedExternally = false,
                    lastVerifiedAt = verifiedAt,
                    state = AutoBackupFileState.READY,
                ),
            )
            AutoBackupAudit.record(
                appContext,
                "DATA_BACKUP_AUTO_CREATED",
                "${backup.fileName} / ${reason.name} / ${verification.manifest.databaseSha256}",
                actorName,
                settlementId ?: 0L,
            )
            statusStore.completed(reason, AutoBackupResultState.CREATED)
            val retention = AutoBackupRetentionManager(appContext).apply(actorName)
            statusStore.retention(retention.summary())
            cleanupStaleTemporaryFiles(appContext)
            return Result.success(Data.Builder().putString("backup_file", backup.fileName).build())
        } catch (error: Throwable) {
            val detail = error.message ?: error.javaClass.simpleName
            statusStore.completed(reason, AutoBackupResultState.FAILED, detail)
            runCatching {
                AutoBackupAudit.record(appContext, "DATA_BACKUP_AUTO_FAILED", "${reason.name} / $detail", actorName, settlementId ?: 0L)
            }
            cleanupStaleTemporaryFiles(appContext)
            return Result.failure(Data.Builder().putString("error", detail).build())
        }
    }

    private fun BackupRetentionResult.summary(): String =
        "候補${planned.size}件 / 削除${deleted.size}件 / 失敗${failed.size}件"

    private fun databaseWorkingBytes(context: Context): Long {
        val database = context.getDatabasePath("register.db")
        return database.length() + File(database.absolutePath + "-wal").length() + File(database.absolutePath + "-shm").length()
    }

    private fun availableBytes(context: Context): Long = StatFs(context.filesDir.absolutePath).availableBytes

    private fun cleanupStaleTemporaryFiles(context: Context) {
        val threshold = System.currentTimeMillis() - AUTO_BACKUP_STALE_TEMP_MILLIS
        File(context.filesDir, "data_backups").listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".tmp") && it.lastModified() < threshold }
            .forEach(File::delete)
        context.cacheDir.listFiles().orEmpty()
            .filter { it.name.startsWith("backup-") && it.lastModified() < threshold }
            .forEach(File::deleteRecursively)
    }
}
