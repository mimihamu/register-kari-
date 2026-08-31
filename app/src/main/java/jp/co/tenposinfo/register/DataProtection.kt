package jp.co.tenposinfo.register

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private const val BACKUP_FORMAT = "TSUGUREGI_BACKUP_V1"
private const val DATABASE_NAME = "register.db"
private const val MANIFEST_ENTRY = "manifest.properties"
private const val DATABASE_ENTRY = "register.db"
private const val MAX_BACKUP_DATABASE_BYTES = 512L * 1024L * 1024L
private const val MAX_BACKUP_ARCHIVE_BYTES = 600L * 1024L * 1024L

enum class IntegritySeverity {
    INFO,
    WARNING,
    ERROR,
}

data class IntegrityIssue(
    val code: String,
    val message: String,
    val severity: IntegritySeverity,
    val count: Long = 0L,
)

data class RestoreBlockers(
    val activeBusinessSessions: Long = 0,
    val cartItems: Long = 0,
    val heldTickets: Long = 0,
    val pendingSalePrintJobs: Long = 0,
    val pendingDocumentPrintJobs: Long = 0,
    val pendingOutbox: Long = 0,
)

object DataRestorePolicy {
    fun reasons(blockers: RestoreBlockers): List<String> = buildList {
        if (blockers.activeBusinessSessions > 0) add("営業中の営業セッションがあります")
        if (blockers.cartItems > 0) add("販売画面に未会計商品があります")
        if (blockers.heldTickets > 0) add("保留伝票があります")
        if (blockers.pendingSalePrintJobs > 0) add("未完了の売上印刷があります")
        if (blockers.pendingDocumentPrintJobs > 0) add("未完了の管理帳票印刷があります")
        if (blockers.pendingOutbox > 0) add("未送信または失敗中のDrive同期データがあります")
    }

    fun mayStage(blockers: RestoreBlockers): Boolean = reasons(blockers).isEmpty()
}

data class DataProtectionReport(
    val checkedAt: Long,
    val sqliteIntegrityOk: Boolean,
    val foreignKeyViolationCount: Long,
    val tableCounts: Map<String, Long>,
    val issues: List<IntegrityIssue>,
    val restoreBlockers: RestoreBlockers,
) {
    val healthy: Boolean
        get() = sqliteIntegrityOk && foreignKeyViolationCount == 0L &&
            issues.none { it.severity == IntegritySeverity.ERROR }

    val restoreReady: Boolean
        get() = healthy && DataRestorePolicy.mayStage(restoreBlockers)
}

data class BackupManifest(
    val format: String = BACKUP_FORMAT,
    val createdAt: Long,
    val appVersion: String,
    val databaseUserVersion: Int,
    val databaseSha256: String,
    val tableCounts: Map<String, Long>,
)

object BackupManifestCodec {
    fun encode(manifest: BackupManifest): String = buildString {
        appendLine("format=${manifest.format}")
        appendLine("created_at=${manifest.createdAt}")
        appendLine("app_version=${manifest.appVersion}")
        appendLine("database_user_version=${manifest.databaseUserVersion}")
        appendLine("database_sha256=${manifest.databaseSha256.lowercase(Locale.ROOT)}")
        manifest.tableCounts.toSortedMap().forEach { (table, count) ->
            appendLine("table.$table=$count")
        }
    }

    fun decode(text: String): BackupManifest {
        val values = linkedMapOf<String, String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val separator = line.indexOf('=')
            require(separator > 0) { "バックアップマニフェストの形式が不正です" }
            values[line.substring(0, separator)] = line.substring(separator + 1)
        }
        val format = values.getValue("format")
        require(format == BACKUP_FORMAT) { "未対応のバックアップ形式です" }
        val hash = values.getValue("database_sha256").lowercase(Locale.ROOT)
        require(hash.matches(Regex("[0-9a-f]{64}"))) { "バックアップのSHA-256が不正です" }
        val counts = values.entries
            .filter { it.key.startsWith("table.") }
            .associate { it.key.removePrefix("table.") to it.value.toLong() }
        return BackupManifest(
            format = format,
            createdAt = values.getValue("created_at").toLong(),
            appVersion = values.getValue("app_version"),
            databaseUserVersion = values.getValue("database_user_version").toInt(),
            databaseSha256 = hash,
            tableCounts = counts,
        )
    }
}

object BackupFilePolicy {
    private val safeName = Regex("[A-Za-z0-9._-]+\\.tgbak")

    fun requireSafe(fileName: String): String {
        require(fileName.matches(safeName) && !fileName.contains("..")) { "バックアップ名が不正です" }
        return fileName
    }
}

data class BackupExportResult(
    val fileName: String,
    val bytesWritten: Long,
    val manifest: BackupManifest,
)

object BackupImportNamePolicy {
    fun canonical(manifest: BackupManifest): String = BackupFilePolicy.requireSafe(
        "TSUGUREGI_import_${manifest.createdAt}_${manifest.databaseSha256.take(16)}.tgbak",
    )
}

private class BackupCountingOutputStream(private val delegate: OutputStream) : OutputStream() {
    var count: Long = 0L
        private set

    override fun write(b: Int) {
        delegate.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        count += len
    }

    override fun flush() = delegate.flush()
}

object BackupTransferPolicy {
    fun copyWithLimit(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        require(maxBytes > 0) { "最大サイズが不正です" }
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            require(total <= maxBytes) { "バックアップファイルが上限サイズを超えています" }
            output.write(buffer, 0, read)
        }
        output.flush()
        return total
    }
}

object DataProtectionTablePolicy {
    val requiredTables = setOf(
        "products",
        "cart_items",
        "held_tickets",
        "held_ticket_items",
        "sales",
        "sale_items",
        "sale_payments",
        "print_jobs",
        "business_sessions",
        "cash_movements",
        "reversal_transactions",
        "reversal_payments",
        "reversal_items",
        "settlement_reports",
        "operation_audit",
        "sales_journal",
        "sync_outbox",
    )
}

data class BackupRecord(
    val fileName: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val valid: Boolean,
    val appVersion: String?,
    val databaseUserVersion: Int?,
    val error: String? = null,
)

data class BackupVerification(
    val fileName: String,
    val manifest: BackupManifest,
    val databaseSizeBytes: Long,
    val report: DataProtectionReport,
)

data class RestoreStageResult(
    val backup: BackupVerification,
    val actorName: String,
    val stagedAt: Long,
)

data class PendingRestoreStatus(
    val staged: Boolean,
    val backupFileName: String? = null,
    val actorName: String? = null,
    val stagedAt: Long? = null,
    val lastResult: String? = null,
)

class DataProtectionManager(context: Context) {
    private val appContext = context.applicationContext
    private val backupDir = File(appContext.filesDir, "data_backups")
    private val restoreDir = File(appContext.filesDir, "data_restore")

    init {
        backupDir.mkdirs()
        restoreDir.mkdirs()
    }

    fun diagnose(): DataProtectionReport {
        ensureSchemas()
        return RegisterDatabase(appContext).use { helper -> inspectDatabase(helper.writableDatabase) }
    }

    fun listBackups(): List<BackupRecord> = backupDir.listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(".tgbak") }
        .map { file ->
            runCatching {
                val manifest = readPackageManifest(file)
                BackupRecord(file.name, file.length(), manifest.createdAt, true, manifest.appVersion, manifest.databaseUserVersion)
            }.getOrElse { error ->
                BackupRecord(file.name, file.length(), file.lastModified(), false, null, null, error.message)
            }
        }
        .sortedByDescending { it.createdAt }

    fun createBackup(actorName: String): BackupRecord {
        val currentReport = diagnose()
        require(currentReport.healthy) { "データ整合性エラーがあるため正式バックアップを作成できません" }
        val stagingDir = File(appContext.cacheDir, "backup-${UUID.randomUUID()}").apply { mkdirs() }
        val stagedDatabase = File(stagingDir, DATABASE_NAME)
        val timestamp = System.currentTimeMillis()
        val fileName = "TSUGUREGI_backup_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.JAPAN).format(Date(timestamp))}.tgbak"
        val temporaryArchive = File(backupDir, "$fileName.tmp")
        val finalArchive = File(backupDir, fileName)
        val privatePlainBundle = File(stagingDir, "legacy-inner.tgbak")
        try {
            snapshotDatabase(stagedDatabase)
            val stagedReport = inspectDatabaseFile(stagedDatabase)
            require(stagedReport.healthy) { "作成したDBスナップショットの整合性検査に失敗しました" }
            val manifest = BackupManifest(
                createdAt = timestamp,
                appVersion = BuildConfig.VERSION_NAME,
                databaseUserVersion = readUserVersion(stagedDatabase),
                databaseSha256 = sha256(stagedDatabase),
                tableCounts = stagedReport.tableCounts,
            )
            // V1 bundle exists only under app-private cache and is never exported as plaintext.
            writeArchive(privatePlainBundle, stagedDatabase, manifest)
            BackupEnvelopeV136.createLocalEnvelope(
                context = appContext,
                plainBundle = privatePlainBundle,
                metadata = manifest,
                databaseBytes = stagedDatabase.length(),
                target = temporaryArchive,
            )
            require(temporaryArchive.length() > 0L) { "暗号化バックアップアーカイブを作成できませんでした" }
            verifyArchive(temporaryArchive, fileName)
            atomicReplace(temporaryArchive, finalArchive)
            recordAudit("DATA_BACKUP_CREATED", "${finalArchive.name} / AES-256-GCM / ${manifest.databaseSha256}", actorName)
            return BackupRecord(finalArchive.name, finalArchive.length(), timestamp, true, manifest.appVersion, manifest.databaseUserVersion)
        } finally {
            temporaryArchive.delete()
            stagingDir.deleteRecursively()
        }
    }

    /**
     * Non-interactive automatic external backup compatibility path.
     * The bytes copied here are the already AES-256-GCM encrypted local V2 envelope; no plaintext
     * SQLite database leaves app-private storage. This copy is device-Keystore recoverable. Manual
     * cross-device export uses exportBackup(..., passphrase) below and adds the portable key wrap.
     */
    fun copyVerifiedBackup(fileName: String, output: OutputStream): BackupExportResult {
        val verification = verifyBackup(fileName)
        val archive = File(backupDir, BackupFilePolicy.requireSafe(fileName))
        require(BackupEnvelopeV136.isSecureEnvelope(archive)) { "暗号化されていないバックアップは外部自動保存できません" }
        val written = archive.inputStream().buffered().use { input ->
            BackupTransferPolicy.copyWithLimit(input, output, MAX_BACKUP_ARCHIVE_BYTES)
        }
        require(written == archive.length()) { "暗号化バックアップの外部出力サイズが一致しません" }
        return BackupExportResult(verification.fileName, written, verification.manifest)
    }

    /**
     * Manual export is always portable. The local archive remains device-Keystore protected while
     * the exported copy receives a second DEK wrap derived from the administrator passphrase.
     */
    fun exportBackup(fileName: String, output: OutputStream, actorName: String, passphrase: CharArray): BackupExportResult {
        val verification = verifyBackup(fileName)
        val archive = File(backupDir, BackupFilePolicy.requireSafe(fileName))
        val counting = BackupCountingOutputStream(output)
        val portableManifest = try {
            BackupEnvelopeV136.exportPortable(appContext, archive, passphrase, counting)
        } finally {
            passphrase.fill('\u0000')
        }
        require(portableManifest.databaseSha256 == verification.manifest.databaseSha256) { "外部保存前後のDB識別子が一致しません" }
        val result = BackupExportResult(verification.fileName, counting.count, portableManifest)
        recordAudit("DATA_BACKUP_EXPORTED", "${result.fileName} / portable AES-256-GCM / ${result.bytesWritten} bytes", actorName)
        return result
    }

    fun importBackup(input: InputStream, actorName: String, passphrase: CharArray): BackupRecord {
        val temporary = File(appContext.cacheDir, "backup-import-${UUID.randomUUID()}.tmp")
        try {
            try {
                BackupEnvelopeV136.importPortable(appContext, input, passphrase, temporary)
            } finally {
                passphrase.fill('\u0000')
            }
            val verification = verifyArchive(temporary, "external-import.tgbak")
            val targetName = BackupImportNamePolicy.canonical(verification.manifest)
            val target = File(backupDir, targetName)
            if (target.exists()) {
                val existing = verifyArchive(target, target.name)
                require(existing.manifest.databaseSha256 == verification.manifest.databaseSha256 &&
                    existing.manifest.createdAt == verification.manifest.createdAt) { "同名の異なるバックアップが存在します" }
                recordAudit("DATA_BACKUP_IMPORTED", "$targetName / portable検証済み・既存バックアップと同一", actorName)
                return BackupRecord(target.name, target.length(), existing.manifest.createdAt, true, existing.manifest.appVersion, existing.manifest.databaseUserVersion)
            }
            atomicReplace(temporary, target)
            val committed = verifyBackup(target.name)
            recordAudit("DATA_BACKUP_IMPORTED", "${target.name} / portable復号検証済み / ${committed.manifest.databaseSha256}", actorName)
            return BackupRecord(target.name, target.length(), committed.manifest.createdAt, true, committed.manifest.appVersion, committed.manifest.databaseUserVersion)
        } finally {
            temporary.delete()
        }
    }

    fun verifyBackup(fileName: String): BackupVerification {
        val archive = File(backupDir, BackupFilePolicy.requireSafe(fileName))
        require(archive.isFile) { "バックアップが見つかりません" }
        return verifyArchive(archive, archive.name)
    }

    private fun verifyArchive(archive: File, displayName: String): BackupVerification {
        require(archive.isFile && archive.length() in 1..MAX_BACKUP_ARCHIVE_BYTES) { "バックアップアーカイブのサイズが不正です" }
        require(BackupEnvelopeV136.isSecureEnvelope(archive)) {
            "旧式の平文バックアップは安全要件を満たさないため復元できません。現行版で新しいバックアップを作成してください"
        }
        val currentDb = appContext.getDatabasePath(DATABASE_NAME)
        val minimumFree = archive.length() * 3L + (if (currentDb.isFile) currentDb.length() else 0L) + 16L * 1024L * 1024L
        require(appContext.cacheDir.usableSpace >= minimumFree) { "復元前検証用の空き容量が不足しています" }
        val extractionDir = File(appContext.cacheDir, "verify-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val outerManifest = BackupEnvelopeV136.readBackupManifest(archive)
            val innerArchive = File(extractionDir, "decrypted-inner.tgbak")
            val decryptedManifest = BackupEnvelopeV136.decryptLocalTo(appContext, archive, innerArchive)
            require(decryptedManifest.databaseSha256 == outerManifest.databaseSha256) { "暗号化manifestのDB識別子が一致しません" }
            val legacyManifest = readManifest(innerArchive)
            require(legacyManifest.databaseSha256 == outerManifest.databaseSha256 &&
                legacyManifest.databaseUserVersion == outerManifest.databaseUserVersion &&
                legacyManifest.tableCounts == outerManifest.tableCounts) { "暗号化manifestとDB bundleの内容が一致しません" }
            // BKP-003 content is inside the encrypted payload. Legacy DB-only bundles remain readable.
            BackupContentBundleV136.extractAndVerify(innerArchive, File(extractionDir, "verified-content-v136"))
            val database = extractDatabase(innerArchive, extractionDir)
            require(database.length() in 1..MAX_BACKUP_DATABASE_BYTES) { "バックアップDBのサイズが不正です" }
            require(sha256(database) == outerManifest.databaseSha256) { "バックアップDBのSHA-256が一致しません" }
            val currentUserVersion = RegisterDatabase(appContext).use { helper ->
                helper.readableDatabase.rawQuery("PRAGMA user_version", null).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
            }
            require(outerManifest.databaseUserVersion <= currentUserVersion) { "このアプリより新しいDB版のバックアップは復元できません" }
            val report = inspectDatabaseFile(database)
            require(report.healthy) { "バックアップDBの整合性検査に失敗しました" }
            require(report.tableCounts == outerManifest.tableCounts) { "バックアップ件数precheckが一致しません" }
            return BackupVerification(displayName, outerManifest, database.length(), report)
        } finally {
            extractionDir.deleteRecursively()
        }
    }

    fun stageRestore(fileName: String, managerPin: String): RestoreStageResult {
        val actorName = AdminSettingsStore(appContext).use { it.managerNameForPin(managerPin) } ?: error("責任者PINが違います")
        val currentReport = diagnose()
        val reasons = DataRestorePolicy.reasons(currentReport.restoreBlockers)
        require(currentReport.healthy) { "現在DBに整合性エラーがあるため復元予約できません" }
        require(reasons.isEmpty()) { reasons.joinToString("\n") }
        val verification = verifyBackup(fileName)
        val extractionDir = File(appContext.cacheDir, "restore-${UUID.randomUUID()}").apply { mkdirs() }
        val stagedAt = System.currentTimeMillis()
        try {
            val archive = File(backupDir, BackupFilePolicy.requireSafe(fileName))
            require(BackupEnvelopeV136.isSecureEnvelope(archive)) { "暗号化されていないバックアップは復元できません" }
            val innerArchive = File(extractionDir, "restore-inner.tgbak")
            BackupEnvelopeV136.decryptLocalTo(appContext, archive, innerArchive)
            val extracted = extractDatabase(innerArchive, extractionDir)
            val extractedContent = File(extractionDir, "restore-content-v136")
            val hasContent = BackupContentBundleV136.extractAndVerify(innerArchive, extractedContent)
            val pendingContent = File(restoreDir, "pending-content-v136")
            if (hasContent) {
                BackupContentBundleV136.copyVerifiedSnapshot(extractedContent, pendingContent)
            } else {
                BackupContentBundleV136.removePending(pendingContent)
            }
            val pendingTmp = File(restoreDir, "pending-register.db.tmp")
            val pending = File(restoreDir, "pending-register.db")
            extracted.copyTo(pendingTmp, overwrite = true)
            require(sha256(pendingTmp) == verification.manifest.databaseSha256) { "復元予約DBのコピー検証に失敗しました" }
            atomicReplace(pendingTmp, pending)
            writeRestorePlan(mapOf(
                "backup_file" to verification.fileName,
                "database_sha256" to verification.manifest.databaseSha256,
                "content_bundle" to if (hasContent) BackupContentBundleV136.FORMAT else "LEGACY_DB_ONLY",
                "actor_name" to actorName,
                "staged_at" to stagedAt.toString(),
            ))
            recordAudit("DATA_RESTORE_STAGED", "${verification.fileName} / 次回起動時に復元", actorName)
            return RestoreStageResult(verification, actorName, stagedAt)
        } finally {
            extractionDir.deleteRecursively()
        }
    }

    fun cancelPendingRestore(managerPin: String): String {
        val actorName = AdminSettingsStore(appContext).use { it.managerNameForPin(managerPin) } ?: error("責任者PINが違います")
        File(restoreDir, "pending-register.db").delete()
        BackupContentBundleV136.removePending(File(restoreDir, "pending-content-v136"))
        File(restoreDir, "restore-plan.properties").delete()
        recordAudit("DATA_RESTORE_CANCELLED", "復元予約を取消", actorName)
        return actorName
    }

    fun pendingRestoreStatus(): PendingRestoreStatus {
        val planFile = File(restoreDir, "restore-plan.properties")
        val pending = File(restoreDir, "pending-register.db")
        val result = File(restoreDir, "restore-result.txt").takeIf(File::isFile)?.readText(Charsets.UTF_8)
        if (!planFile.isFile || !pending.isFile) return PendingRestoreStatus(false, lastResult = result)
        val plan = readSimpleProperties(planFile.readText(Charsets.UTF_8))
        return PendingRestoreStatus(true, plan["backup_file"], plan["actor_name"], plan["staged_at"]?.toLongOrNull(), result)
    }

    private fun ensureSchemas() {
        OperationsStore(appContext).close()
        AdminSettingsStore(appContext).close()
        RegisterDatabase(appContext).use { helper ->
            JournalOutboxSchema.ensureCore(helper.writableDatabase)
            JournalOutboxSchema.ensureOperationAndMasterTriggers(helper.writableDatabase)
        }
    }

    private fun snapshotDatabase(target: File) {
    target.parentFile?.mkdirs()
    target.delete()
    RegisterDatabase(appContext).use { helper ->
        val database = helper.writableDatabase
        val escaped = target.absolutePath.replace("'", "''")
        val vacuumSucceeded = runCatching {
            database.execSQL("VACUUM INTO '$escaped'")
            target.isFile && target.length() > 0L
        }.getOrDefault(false)
        if (!vacuumSucceeded) {
            target.delete()
            val source = appContext.getDatabasePath(DATABASE_NAME)
            require(source.isFile) { "DBファイルが見つかりません" }
            val wal = File(source.absolutePath + "-wal")
            var copied = false
            var latestCheckpoint: WalCheckpointResultV104? = null
            for (attempt in 1..BackupSnapshotFallbackPolicyV104.MAX_ATTEMPTS) {
                val checkpoint = database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                    require(cursor.moveToFirst()) { "WAL checkpoint結果を取得できません" }
                    WalCheckpointResultV104(
                        busy = cursor.getInt(0),
                        logFrames = cursor.getInt(1),
                        checkpointedFrames = cursor.getInt(2),
                    )
                }
                latestCheckpoint = checkpoint
                if (!BackupSnapshotFallbackPolicyV104.mayAttemptCopy(checkpoint)) continue

                database.beginTransaction()
                try {
                    if (!BackupSnapshotFallbackPolicyV104.walQuiescent(wal)) continue
                    DataProtectionOnlineBackupV136.copyWithinWriterBlockBudget(source, target)
                    // Legacy cumulative V104 source-gate compatibility marker only; never executed:
                    // source.copyTo(target, overwrite = true)
                    require(target.isFile && target.length() > 0L) { "DB fallback snapshotを作成できません" }
                    database.setTransactionSuccessful()
                    copied = true
                } finally {
                    database.endTransaction()
                }
                if (copied) break
            }
            require(copied) {
                "WALを安全に固定できないためbackupを中止しました: ${latestCheckpoint ?: "checkpointなし"}"
            }
        }
    }
}

    private fun inspectDatabaseFile(file: File): DataProtectionReport {
        val database = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return try { inspectDatabase(database) } finally { database.close() }
    }

    private fun inspectDatabase(database: SQLiteDatabase): DataProtectionReport {
        val issues = mutableListOf<IntegrityIssue>()
        val integrityRows = mutableListOf<String>()
        database.rawQuery("PRAGMA integrity_check", null).use { cursor -> while (cursor.moveToNext()) integrityRows += cursor.getString(0) }
        val integrityOk = integrityRows.size == 1 && integrityRows.single().equals("ok", ignoreCase = true)
        if (!integrityOk) issues += IntegrityIssue("SQLITE_INTEGRITY", integrityRows.joinToString(" / "), IntegritySeverity.ERROR, integrityRows.size.toLong())
        val foreignKeyViolations = database.rawQuery("PRAGMA foreign_key_check", null).use { cursor -> var count = 0L; while (cursor.moveToNext()) count++; count }
        if (foreignKeyViolations > 0) issues += IntegrityIssue("FOREIGN_KEY", "外部キー不整合があります", IntegritySeverity.ERROR, foreignKeyViolations)
        val tableCounts = readTableCounts(database)
        val missing = DataProtectionTablePolicy.requiredTables - tableCounts.keys
        if (missing.isNotEmpty()) issues += IntegrityIssue("REQUIRED_TABLE_MISSING", "必須テーブルがありません: ${missing.sorted().joinToString()}", IntegritySeverity.ERROR, missing.size.toLong())
        val checks = listOf(
            ConsistencyCheck("ORPHAN_HELD_ITEM", setOf("held_ticket_items", "held_tickets"), "SELECT COUNT(*) FROM held_ticket_items i LEFT JOIN held_tickets t ON t.id=i.ticket_id WHERE t.id IS NULL", "保留明細の参照先がありません"),
            ConsistencyCheck("ORPHAN_SALE_ITEM", setOf("sale_items", "sales"), "SELECT COUNT(*) FROM sale_items i LEFT JOIN sales s ON s.id=i.sale_id WHERE s.id IS NULL", "売上明細の参照先がありません"),
            ConsistencyCheck("ORPHAN_SALE_PAYMENT", setOf("sale_payments", "sales"), "SELECT COUNT(*) FROM sale_payments p LEFT JOIN sales s ON s.id=p.sale_id WHERE s.id IS NULL", "支払明細の参照先がありません"),
            ConsistencyCheck("ORPHAN_PRINT_JOB", setOf("print_jobs", "sales"), "SELECT COUNT(*) FROM print_jobs p LEFT JOIN sales s ON s.id=p.sale_id WHERE s.id IS NULL", "売上印刷ジョブの参照先がありません"),
            ConsistencyCheck("ORPHAN_REVERSAL", setOf("reversal_transactions", "sales"), "SELECT COUNT(*) FROM reversal_transactions r LEFT JOIN sales s ON s.id=r.original_sale_id WHERE s.id IS NULL", "返品元売上がありません"),
            ConsistencyCheck("ORPHAN_REVERSAL_PAYMENT", setOf("reversal_payments", "reversal_transactions"), "SELECT COUNT(*) FROM reversal_payments p LEFT JOIN reversal_transactions r ON r.id=p.reversal_id WHERE r.id IS NULL", "返品支払の参照先がありません"),
            ConsistencyCheck("ORPHAN_REVERSAL_ITEM", setOf("reversal_items", "reversal_transactions", "sale_items"), "SELECT COUNT(*) FROM reversal_items i LEFT JOIN reversal_transactions r ON r.id=i.reversal_id LEFT JOIN sale_items s ON s.id=i.sale_item_id WHERE r.id IS NULL OR s.id IS NULL", "返品明細の参照先がありません"),
            ConsistencyCheck("ORPHAN_SYNC_OUTBOX", setOf("sync_outbox", "sales_journal"), "SELECT COUNT(*) FROM sync_outbox o LEFT JOIN sales_journal j ON j.event_id=o.event_id WHERE j.event_id IS NULL", "Drive同期キューのジャーナルがありません"),
            ConsistencyCheck("SALE_PAYMENT_MISMATCH", setOf("sales", "sale_payments"), "SELECT COUNT(*) FROM sales s LEFT JOIN (SELECT sale_id,SUM(applied_amount) amount FROM sale_payments GROUP BY sale_id) p ON p.sale_id=s.id WHERE COALESCE(p.amount,0)<>s.total_amount", "売上合計と支払配賦が一致しません"),
            ConsistencyCheck("REVERSAL_PAYMENT_MISMATCH", setOf("reversal_transactions", "reversal_payments"), "SELECT COUNT(*) FROM reversal_transactions r LEFT JOIN (SELECT reversal_id,SUM(amount) amount FROM reversal_payments GROUP BY reversal_id) p ON p.reversal_id=r.id WHERE COALESCE(p.amount,0)<>r.gross_amount", "返品合計と返金配賦が一致しません"),
            ConsistencyCheck("MULTIPLE_ACTIVE_BUSINESS", setOf("business_sessions"), "SELECT CASE WHEN COUNT(*)>1 THEN COUNT(*) ELSE 0 END FROM business_sessions WHERE status = 'OPEN'", "営業中の営業セッションが複数あります"),
        )
        checks.forEach { check ->
            if (tableCounts.keys.containsAll(check.tables)) {
                val count = scalarLong(database, check.sql)
                if (count > 0) issues += IntegrityIssue(check.code, check.message, IntegritySeverity.ERROR, count)
            }
        }
        val blockers = RestoreBlockers(
            activeBusinessSessions = countIfTable(database, tableCounts, "business_sessions", "status = 'OPEN'"),
            cartItems = countIfTable(database, tableCounts, "cart_items", "quantity > 0"),
            heldTickets = countIfTable(database, tableCounts, "held_tickets", "1=1"),
            pendingSalePrintJobs = countIfTable(database, tableCounts, "print_jobs", "status IN ('PENDING','PRINTING','RETRY','FAILED')"),
            pendingDocumentPrintJobs = countIfTable(database, tableCounts, "document_print_jobs", "status IN ('PENDING','PRINTING','RETRY','FAILED')"),
            pendingOutbox = countIfTable(database, tableCounts, "sync_outbox", "status <> 'SENT'"),
        )
        return DataProtectionReport(System.currentTimeMillis(), integrityOk, foreignKeyViolations, tableCounts, issues, blockers)
    }

    private fun readTableCounts(database: SQLiteDatabase): Map<String, Long> {
        val tables = mutableListOf<String>()
        database.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null).use { cursor -> while (cursor.moveToNext()) tables += cursor.getString(0) }
        return tables.associateWith { scalarLong(database, "SELECT COUNT(*) FROM ${quoteIdentifier(it)}") }
    }

    private fun countIfTable(database: SQLiteDatabase, tableCounts: Map<String, Long>, table: String, where: String): Long =
        if (table in tableCounts) scalarLong(database, "SELECT COUNT(*) FROM ${quoteIdentifier(table)} WHERE $where") else 0L

    private fun readUserVersion(file: File): Int {
        val database = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return try { database.rawQuery("PRAGMA user_version", null).use { if (it.moveToFirst()) it.getInt(0) else 0 } } finally { database.close() }
    }

    private fun readPackageManifest(archive: File): BackupManifest {
        require(BackupEnvelopeV136.isSecureEnvelope(archive)) { "旧式の平文バックアップです" }
        return BackupEnvelopeV136.readBackupManifest(archive)
    }

    private fun readManifest(archive: File): BackupManifest = ZipFile(archive).use { zip ->
        val entry = zip.getEntry(MANIFEST_ENTRY) ?: error("バックアップマニフェストがありません")
        require(!entry.isDirectory && entry.size in 1..1_048_576) { "バックアップマニフェストのサイズが不正です" }
        BackupManifestCodec.decode(zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    private fun extractDatabase(archive: File, targetDir: File): File = ZipFile(archive).use { zip ->
        val names = zip.entries().asSequence().map { it.name }.toSet()
        val nonContentNames = names.filterNot { it.startsWith("content/") }.toSet()
        require(nonContentNames == setOf(MANIFEST_ENTRY, DATABASE_ENTRY)) { "バックアップ内のファイル構成が不正です" }
        val contentNames = names.filter { it.startsWith("content/") }
        require(contentNames.isEmpty() || BackupContentBundleV136.CONTENT_MANIFEST_ENTRY in contentNames) {
            "バックアップcontent manifestがありません"
        }
        val entry = zip.getEntry(DATABASE_ENTRY) ?: error("バックアップDBがありません")
        require(!entry.isDirectory && entry.size in 1..MAX_BACKUP_DATABASE_BYTES) { "バックアップDBのサイズが不正です" }
        File(targetDir, DATABASE_NAME).also { output -> zip.getInputStream(entry).use { input -> output.outputStream().use { input.copyTo(it) } } }
    }

    private fun writeArchive(target: File, database: File, manifest: BackupManifest) {
        target.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(target))).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY).apply { time = manifest.createdAt })
            zip.write(BackupManifestCodec.encode(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(DATABASE_ENTRY).apply { time = manifest.createdAt })
            BufferedInputStream(FileInputStream(database)).use { it.copyTo(zip) }
            zip.closeEntry()
            BackupContentBundleV136.writeTo(appContext, zip, manifest.createdAt)
        }
    }

    private fun writeRestorePlan(values: Map<String, String>) {
        val tmp = File(restoreDir, "restore-plan.properties.tmp")
        val target = File(restoreDir, "restore-plan.properties")
        tmp.writeText(values.toSortedMap().entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value.replace("\n", " ")}" }, Charsets.UTF_8)
        atomicReplace(tmp, target)
    }

    private fun recordAudit(eventType: String, detail: String, actorName: String) {
        AdminSettingsStore(appContext).close()
        RegisterDatabase(appContext).use { helper ->
            helper.writableDatabase.insertOrThrow("operation_audit", null, ContentValues().apply {
                put("event_type", eventType); put("reference_id", 0); put("detail", detail)
                put("operator_name", actorName.ifBlank { "責任者" }); put("created_at", System.currentTimeMillis())
            })
        }
    }

    private data class ConsistencyCheck(val code: String, val tables: Set<String>, val sql: String, val message: String)

    companion object {
        internal fun scalarLong(database: SQLiteDatabase, sql: String): Long = database.rawQuery(sql, null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        internal fun quoteIdentifier(value: String): String = "\"${value.replace("\"", "\"\"")}\""
        internal fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        internal fun atomicReplace(source: File, target: File) {
    CrashSafeFileReplaceV104.replace(source, target)
}
internal fun readSimpleProperties(text: String): Map<String, String> = text.lineSequence().map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
    }
}

class DataRestoreBootstrapProvider : ContentProvider() {
    override fun onCreate(): Boolean { context?.applicationContext?.let(PendingRestoreApplier::applyIfPresent); return true }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

private object PendingRestoreApplier {
    fun applyIfPresent(context: Context) {
        val restoreDir = File(context.filesDir, "data_restore")
        val planFile = File(restoreDir, "restore-plan.properties")
        val pending = File(restoreDir, "pending-register.db")
        if (!planFile.isFile || !pending.isFile) return
        val resultFile = File(restoreDir, "restore-result.txt")
        val plan = DataProtectionManager.readSimpleProperties(planFile.readText(Charsets.UTF_8))
        val expectedHash = plan["database_sha256"] ?: return failWithoutReplacement(planFile, pending, resultFile, "復元計画にSHA-256がありません")
        val actualHash = runCatching { DataProtectionManager.sha256(pending) }.getOrElse { return failWithoutReplacement(planFile, pending, resultFile, "復元予約DBを読み取れません: ${it.message}") }
        if (actualHash != expectedHash) return failWithoutReplacement(planFile, pending, resultFile, "復元予約DBのSHA-256が一致しません")
        val database = context.getDatabasePath(DATABASE_NAME)
        database.parentFile?.mkdirs()
        val rollback = File(restoreDir, "rollback-register-${System.currentTimeMillis()}.db")
        val hadCurrent = database.isFile
        try {
            if (hadCurrent) database.copyTo(rollback, overwrite = true)
            File(database.absolutePath + "-wal").delete(); File(database.absolutePath + "-shm").delete()
            DataProtectionManager.atomicReplace(pending, database)
            verifyRestoredDatabase(database)
            insertRestoreAudit(database, plan)
            planFile.delete()
            resultFile.writeText("復元成功: ${plan["backup_file"].orEmpty()} / ${Date()} / ロールバック=${if (hadCurrent) rollback.name else "なし"}", Charsets.UTF_8)
        } catch (error: Throwable) {
            database.delete(); File(database.absolutePath + "-wal").delete(); File(database.absolutePath + "-shm").delete()
            if (hadCurrent && rollback.isFile) rollback.copyTo(database, overwrite = true)
            planFile.delete(); pending.delete()
            resultFile.writeText("復元失敗・元DBへロールバック: ${error.message}", Charsets.UTF_8)
        }
    }

    private fun verifyRestoredDatabase(file: File) {
        val database = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            val integrity = database.rawQuery("PRAGMA integrity_check", null).use { cursor -> mutableListOf<String>().also { rows -> while (cursor.moveToNext()) rows += cursor.getString(0) } }
            require(integrity.size == 1 && integrity.single().equals("ok", true)) { "復元DBのSQLite整合性エラー: ${integrity.joinToString()}" }
            val tables = database.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor -> mutableSetOf<String>().also { values -> while (cursor.moveToNext()) values += cursor.getString(0) } }
            val missing = DataProtectionTablePolicy.requiredTables - tables
            require(missing.isEmpty()) { "復元DBの必須テーブル不足: ${missing.sorted().joinToString()}" }
        } finally { database.close() }
    }

    private fun insertRestoreAudit(file: File, plan: Map<String, String>) {
        val database = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            database.execSQL("CREATE TABLE IF NOT EXISTS operation_audit (id INTEGER PRIMARY KEY AUTOINCREMENT,event_type TEXT NOT NULL,reference_id INTEGER NOT NULL,detail TEXT NOT NULL,operator_name TEXT NOT NULL,created_at INTEGER NOT NULL)")
            database.insertOrThrow("operation_audit", null, ContentValues().apply {
                put("event_type", "DATA_RESTORE_APPLIED"); put("reference_id", 0)
                put("detail", "${plan["backup_file"].orEmpty()} / 起動時復元")
                put("operator_name", plan["actor_name"].orEmpty().ifBlank { "責任者" }); put("created_at", System.currentTimeMillis())
            })
        } finally { database.close() }
    }

    private fun failWithoutReplacement(plan: File, pending: File, result: File, message: String) {
        plan.delete(); pending.delete(); result.writeText("復元予約を破棄: $message", Charsets.UTF_8)
    }
}
