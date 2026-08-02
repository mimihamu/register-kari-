from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/jp/co/tenposinfo/register"
TEST = ROOT / "app/src/test/java/jp/co/tenposinfo/register"
DOCS = ROOT / "docs"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, got {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


(APP / "DataProtection.kt").write_text(r'''package jp.co.tenposinfo.register

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
        if (blockers.activeBusinessSessions > 0) add("営業中またはZ精算後の営業日があります")
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
                val manifest = readManifest(file)
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
            writeArchive(temporaryArchive, stagedDatabase, manifest)
            require(temporaryArchive.length() > 0L) { "バックアップアーカイブを作成できませんでした" }
            atomicReplace(temporaryArchive, finalArchive)
            recordAudit("DATA_BACKUP_CREATED", "${finalArchive.name} / ${manifest.databaseSha256}", actorName)
            return BackupRecord(finalArchive.name, finalArchive.length(), timestamp, true, manifest.appVersion, manifest.databaseUserVersion)
        } finally {
            temporaryArchive.delete()
            stagingDir.deleteRecursively()
        }
    }

    fun verifyBackup(fileName: String): BackupVerification {
        val archive = File(backupDir, BackupFilePolicy.requireSafe(fileName))
        require(archive.isFile) { "バックアップが見つかりません" }
        val extractionDir = File(appContext.cacheDir, "verify-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val manifest = readManifest(archive)
            val database = extractDatabase(archive, extractionDir)
            require(database.length() in 1..MAX_BACKUP_DATABASE_BYTES) { "バックアップDBのサイズが不正です" }
            require(sha256(database) == manifest.databaseSha256) { "バックアップDBのSHA-256が一致しません" }
            val currentUserVersion = RegisterDatabase(appContext).use { helper ->
                helper.readableDatabase.rawQuery("PRAGMA user_version", null).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
            }
            require(manifest.databaseUserVersion <= currentUserVersion) { "このアプリより新しいDB版のバックアップは復元できません" }
            val report = inspectDatabaseFile(database)
            require(report.healthy) { "バックアップDBの整合性検査に失敗しました" }
            return BackupVerification(archive.name, manifest, database.length(), report)
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
            val extracted = extractDatabase(archive, extractionDir)
            val pendingTmp = File(restoreDir, "pending-register.db.tmp")
            val pending = File(restoreDir, "pending-register.db")
            extracted.copyTo(pendingTmp, overwrite = true)
            require(sha256(pendingTmp) == verification.manifest.databaseSha256) { "復元予約DBのコピー検証に失敗しました" }
            atomicReplace(pendingTmp, pending)
            writeRestorePlan(mapOf(
                "backup_file" to verification.fileName,
                "database_sha256" to verification.manifest.databaseSha256,
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
            database.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor -> while (cursor.moveToNext()) Unit }
            val escaped = target.absolutePath.replace("'", "''")
            val vacuumSucceeded = runCatching {
                database.execSQL("VACUUM INTO '$escaped'")
                target.isFile && target.length() > 0L
            }.getOrDefault(false)
            if (!vacuumSucceeded) {
                target.delete()
                database.beginTransaction()
                try {
                    val source = appContext.getDatabasePath(DATABASE_NAME)
                    require(source.isFile) { "DBファイルが見つかりません" }
                    source.copyTo(target, overwrite = true)
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
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
            ConsistencyCheck("MULTIPLE_ACTIVE_BUSINESS", setOf("business_sessions"), "SELECT CASE WHEN COUNT(*)>1 THEN COUNT(*) ELSE 0 END FROM business_sessions WHERE status IN ('OPEN','Z_SETTLED')", "営業中または終了待ちの営業日が複数あります"),
        )
        checks.forEach { check ->
            if (tableCounts.keys.containsAll(check.tables)) {
                val count = scalarLong(database, check.sql)
                if (count > 0) issues += IntegrityIssue(check.code, check.message, IntegritySeverity.ERROR, count)
            }
        }
        val blockers = RestoreBlockers(
            activeBusinessSessions = countIfTable(database, tableCounts, "business_sessions", "status IN ('OPEN','Z_SETTLED')"),
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

    private fun readManifest(archive: File): BackupManifest = ZipFile(archive).use { zip ->
        val entry = zip.getEntry(MANIFEST_ENTRY) ?: error("バックアップマニフェストがありません")
        require(!entry.isDirectory && entry.size in 1..1_048_576) { "バックアップマニフェストのサイズが不正です" }
        BackupManifestCodec.decode(zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    private fun extractDatabase(archive: File, targetDir: File): File = ZipFile(archive).use { zip ->
        val names = zip.entries().asSequence().map { it.name }.toSet()
        require(names == setOf(MANIFEST_ENTRY, DATABASE_ENTRY)) { "バックアップ内のファイル構成が不正です" }
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
            target.parentFile?.mkdirs()
            if (target.exists()) target.delete()
            if (!source.renameTo(target)) { source.copyTo(target, overwrite = true); require(source.delete()) { "一時ファイルを削除できません" } }
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
''', encoding="utf-8")

(APP / "DataProtectionActivity.kt").write_text(r'''package jp.co.tenposinfo.register

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DpNavy = Color(0xFF173F6B)
private val DpBlue = Color(0xFF1976B9)
private val DpGreen = Color(0xFF2E7D32)
private val DpDanger = Color(0xFFC62828)
private val DpBackground = Color(0xFFF4F7FA)
private val DpSelected = Color(0xFFEAF3FA)

class DataProtectionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { DataProtectionScreen(onClose = { finish() }) } }
    }
}

@Composable
private fun DataProtectionScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { DataProtectionManager(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<DataProtectionReport?>(null) }
    var backups by remember { mutableStateOf<List<BackupRecord>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf(manager.pendingRestoreStatus()) }
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("診断を実行してください") }
    var busy by remember { mutableStateOf(false) }

    fun runTask(task: suspend () -> String) {
        if (busy) return
        scope.launch {
            busy = true
            message = runCatching { task() }.getOrElse { "エラー: ${it.message}" }
            backups = withContext(Dispatchers.IO) { manager.listBackups() }
            pending = manager.pendingRestoreStatus()
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        backups = withContext(Dispatchers.IO) { manager.listBackups() }
        report = withContext(Dispatchers.IO) { manager.diagnose() }
        message = if (report?.healthy == true) "DB整合性は正常です" else "DB整合性エラーを確認してください"
    }

    Surface(Modifier.fillMaxSize(), color = DpBackground) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(72.dp).background(DpNavy).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("SCR-767", color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(18.dp))
                Text("データ保全・バックアップ・復元", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f)); Text("v${BuildConfig.VERSION_NAME}", color = Color.White)
            }
            Row(Modifier.weight(1f).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Card(Modifier.width(470.dp).fillMaxHeight(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Text("整合性診断", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = DpNavy)
                        Spacer(Modifier.height(8.dp))
                        val current = report
                        Text(when { current == null -> "未診断"; current.healthy -> "正常"; else -> "要確認" }, color = if (current?.healthy == true) DpGreen else DpDanger, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        if (current != null) {
                            Text("SQLite: ${if (current.sqliteIntegrityOk) "OK" else "NG"} / 外部キー違反 ${current.foreignKeyViolationCount}件")
                            Text("テーブル ${current.tableCounts.size}件 / 診断 ${formatTime(current.checkedAt)}")
                            Spacer(Modifier.height(8.dp)); Text("復元前ブロッカー", fontWeight = FontWeight.Bold)
                            val reasons = DataRestorePolicy.reasons(current.restoreBlockers)
                            Text(if (reasons.isEmpty()) "なし" else reasons.joinToString("\n"), color = if (reasons.isEmpty()) DpGreen else DpDanger)
                            Spacer(Modifier.height(8.dp))
                            LazyColumn(Modifier.weight(1f)) {
                                items(current.issues) { issue -> Text("${issue.code}: ${issue.message}${if (issue.count > 0) " (${issue.count})" else ""}", color = if (issue.severity == IntegritySeverity.ERROR) DpDanger else Color.DarkGray, modifier = Modifier.padding(vertical = 3.dp)) }
                            }
                        } else Spacer(Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { runTask { report = withContext(Dispatchers.IO) { manager.diagnose() }; if (report?.healthy == true) "DB整合性は正常です" else "DB整合性エラーがあります" } }, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = DpBlue)) { Text("再診断") }
                            Button(onClick = { runTask { val actor = OperatorSessionRegistry.current(context.applicationContext)?.name ?: "責任者"; val backup = withContext(Dispatchers.IO) { manager.createBackup(actor) }; "バックアップ作成: ${backup.fileName}" } }, enabled = !busy && current?.healthy == true, colors = ButtonDefaults.buttonColors(containerColor = DpGreen)) { Text("バックアップ作成") }
                        }
                    }
                }
                Card(Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Text("バックアップ一覧", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = DpNavy); Spacer(Modifier.weight(1f)); Text("${backups.size}件") }
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(Modifier.weight(1f)) {
                            items(backups, key = { it.fileName }) { backup ->
                                Column(Modifier.fillMaxWidth().background(if (selected == backup.fileName) DpSelected else Color.Transparent).clickable { selected = backup.fileName }.padding(10.dp)) {
                                    Text(backup.fileName, fontWeight = FontWeight.Bold)
                                    Text("${backup.sizeBytes} bytes / ${formatTime(backup.createdAt)} / ${backup.appVersion ?: "不明"}")
                                    if (!backup.valid) Text(backup.error.orEmpty(), color = DpDanger)
                                }
                            }
                        }
                        if (pending.staged) Text("復元予約済み: ${pending.backupFileName}\nアプリを完全終了して再起動すると適用します。", color = DpDanger, fontWeight = FontWeight.Bold)
                        pending.lastResult?.let { Text(it, color = Color.DarkGray, fontSize = 13.sp) }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(8) }, label = { Text("復元・取消用 責任者PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { val file = selected ?: return@OutlinedButton; runTask { val verified = withContext(Dispatchers.IO) { manager.verifyBackup(file) }; "検証成功: ${verified.fileName} / SHA-256 ${verified.manifest.databaseSha256.take(12)}…" } }, enabled = !busy && selected != null) { Text("検証") }
                            Button(onClick = { val file = selected ?: return@Button; runTask { val staged = withContext(Dispatchers.IO) { manager.stageRestore(file, pin) }; pin = ""; "復元予約: ${staged.backup.fileName}。アプリを完全終了して再起動してください。" } }, enabled = !busy && selected != null && pin.length >= 4 && report?.restoreReady == true && !pending.staged, colors = ButtonDefaults.buttonColors(containerColor = DpDanger)) { Text("次回起動時に復元") }
                            OutlinedButton(onClick = { runTask { val actor = withContext(Dispatchers.IO) { manager.cancelPendingRestore(pin) }; pin = ""; "復元予約を取り消しました（$actor）" } }, enabled = !busy && pending.staged && pin.length >= 4) { Text("予約取消") }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(82.dp).background(Color.White).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, color = if (message.startsWith("エラー")) DpDanger else DpNavy, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f)); OutlinedButton(onClick = onClose, enabled = !busy, modifier = Modifier.width(220.dp).height(54.dp)) { Text("設定へ戻る") }
            }
        }
    }
}

private fun formatTime(value: Long): String = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date(value))
''', encoding="utf-8")

(TEST / "V021DataProtectionPolicyTest.kt").write_text(r'''package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V021DataProtectionPolicyTest {
    @Test fun manifestRoundTripPreservesHashAndCounts() {
        val source = BackupManifest(createdAt = 123456789L, appVersion = "0.21.0-dev.1", databaseUserVersion = 4, databaseSha256 = "a".repeat(64), tableCounts = mapOf("sales" to 10L, "sale_items" to 24L))
        assertEquals(source, BackupManifestCodec.decode(BackupManifestCodec.encode(source)))
    }
    @Test fun unsafeBackupNameIsRejected() {
        assertTrue(runCatching { BackupFilePolicy.requireSafe("TSUGUREGI_backup_20260802_120000.tgbak") }.isSuccess)
        assertTrue(runCatching { BackupFilePolicy.requireSafe("../register.db.tgbak") }.isFailure)
        assertTrue(runCatching { BackupFilePolicy.requireSafe("backup.zip") }.isFailure)
    }
    @Test fun restoreRequiresClosedAndDrainedRegister() {
        assertTrue(DataRestorePolicy.mayStage(RestoreBlockers()))
        val blocked = RestoreBlockers(activeBusinessSessions = 1, heldTickets = 2, pendingSalePrintJobs = 1, pendingOutbox = 3)
        assertFalse(DataRestorePolicy.mayStage(blocked)); assertEquals(4, DataRestorePolicy.reasons(blocked).size)
    }
    @Test fun requiredTableSetIncludesFinancialAndRecoveryData() {
        assertTrue("sales" in DataProtectionTablePolicy.requiredTables); assertTrue("sale_payments" in DataProtectionTablePolicy.requiredTables)
        assertTrue("reversal_transactions" in DataProtectionTablePolicy.requiredTables); assertTrue("business_sessions" in DataProtectionTablePolicy.requiredTables)
        assertTrue("sync_outbox" in DataProtectionTablePolicy.requiredTables); assertTrue("operation_audit" in DataProtectionTablePolicy.requiredTables)
    }
}
''', encoding="utf-8")

(DOCS / "V0.21_DATA_PROTECTION.md").write_text(r'''# つぐレジ v0.21 データ保全

## 対象

- SQLite `register.db` の整合性診断
- 業務データの参照・金額整合性診断
- SHA-256付き原子的バックアップ
- 復元前の営業状態・未処理データ検査
- 次回起動時のロールバック付き復元

## 診断

`PRAGMA integrity_check`、`PRAGMA foreign_key_check`に加え、売上・明細・支払・印刷、返品・返金、保留伝票、Drive Outboxの孤立参照を確認する。売上金額と支払配賦、返品金額と返金配賦も照合する。

## バックアップ

SQLiteの一貫したスナップショットを作り、DB本体とマニフェストを`.tgbak`へ格納する。マニフェストにはアプリ版、DB user_version、作成日時、全テーブル件数、DB SHA-256を記録する。一時ファイル完成後に正式名へ切り替えるため、作成途中ファイルは正式バックアップとして扱わない。

## 復元

責任者PINを再認証し、営業中・未会計カート・保留伝票・未完了印刷・未送信Drive Outboxがなく、現在DBとバックアップDBが正常な場合だけ復元予約できる。実行中DBは直接置換せず、次回起動時に最優先Providerが現在DBをロールバック用に退避してから置換する。置換後の検査に失敗した場合は元DBへ戻す。

## 監査

`DATA_BACKUP_CREATED`、`DATA_RESTORE_STAGED`、`DATA_RESTORE_CANCELLED`、`DATA_RESTORE_APPLIED`を`operation_audit`へ記録する。
''', encoding="utf-8")

manifest = ROOT / "app/src/main/AndroidManifest.xml"
replace_once(manifest, '''        <provider
            android:name=".CatalogBootstrapProvider"''', '''        <provider
            android:name=".DataRestoreBootstrapProvider"
            android:authorities="${applicationId}.data-restore-bootstrap"
            android:exported="false"
            android:initOrder="10" />

        <provider
            android:name=".CatalogBootstrapProvider"''', "restore bootstrap provider")
replace_once(manifest, '''        <activity
            android:name=".OperationsActivity"''', '''        <activity
            android:name=".DataProtectionActivity"
            android:exported="false"
            android:screenOrientation="landscape" />

        <activity
            android:name=".OperationsActivity"''', "data protection activity")

application = APP / "RegisterApplication.kt"
replace_once(application, "            is AdminSettingsActivity -> guardSettingsActivity(activity)\n", "            is AdminSettingsActivity, is DataProtectionActivity -> guardSettingsActivity(activity)\n", "data protection permission gate")

admin = APP / "AdminSettingsActivity.kt"
replace_once(admin, '''                    onPrinterTools = { context.startActivity(Intent(context, PrinterToolsHubActivity::class.java)) },
                    onSecurity = { screen = AdminScreen.SECURITY },''', '''                    onPrinterTools = { context.startActivity(Intent(context, PrinterToolsHubActivity::class.java)) },
                    onDataProtection = { context.startActivity(Intent(context, DataProtectionActivity::class.java)) },
                    onSecurity = { screen = AdminScreen.SECURITY },''', "admin data protection callback")
replace_once(admin, '''    onPrinterTools: () -> Unit,
    onSecurity: () -> Unit,''', '''    onPrinterTools: () -> Unit,
    onDataProtection: () -> Unit,
    onSecurity: () -> Unit,''', "admin menu parameter")
replace_once(admin, '''                Row(Modifier.weight(0.72f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AsMenuTile("責任者PIN", "責任者PINを安全に更新", AsPaleYellow, Modifier.weight(1f), onSecurity)
                    Spacer(Modifier.weight(2f))
                }''', '''                Row(Modifier.weight(0.72f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AsMenuTile("責任者PIN", "責任者PINを安全に更新", AsPaleYellow, Modifier.weight(1f), onSecurity)
                    AsMenuTile("データ保全", "整合性診断、バックアップ、復元", Color(0xFFE8F3EE), Modifier.weight(1f), onDataProtection)
                    Spacer(Modifier.weight(1f))
                }''', "admin data protection tile")
replace_once(admin, '            Text("初期責任者PIN：0000", color = Color.Gray)\n', '            Text("登録済みの責任者PINを入力してください", color = Color.Gray)\n', "remove fixed manager pin hint")

build = ROOT / "app/build.gradle.kts"
replace_once(build, '        versionCode = 50\n', '        versionCode = 51\n', "version code")
replace_once(build, '        versionName = "0.20.0-dev.1"\n', '        versionName = "0.21.0-dev.1"\n', "version name")
