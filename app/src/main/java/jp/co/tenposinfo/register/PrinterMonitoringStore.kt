package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase

data class PrinterRuntimeSettings(
    val preflightEnabled: Boolean = true,
    val historyEnabled: Boolean = true,
    val updatedBy: String = "system",
    val updatedAt: Long = 0L,
)

data class PrinterStatusHistoryRecord(
    val id: Long,
    val printerName: String,
    val host: String,
    val port: Int,
    val profile: PrinterProfile,
    val level: PrinterStatusLevel?,
    val summary: String,
    val rawHex: String,
    val elapsedMillis: Long,
    val success: Boolean,
    val checkedBy: String,
    val checkedAt: Long,
)

/**
 * プリンター診断設定と確認履歴をレジ本体のSQLiteへ保存する。
 * 状態確認失敗も履歴として残し、監査ログから追跡できるようにする。
 */
class PrinterMonitoringStore(context: Context) : AutoCloseable {
    private val database = RegisterDatabase(context.applicationContext)
    private val db: SQLiteDatabase = database.writableDatabase

    init {
        ensureSchema()
    }

    override fun close() = database.close()

    fun loadSettings(): PrinterRuntimeSettings = db.query(
        "printer_runtime_settings",
        arrayOf("preflight_enabled", "history_enabled", "updated_by", "updated_at"),
        "id = 1",
        null,
        null,
        null,
        null,
    ).use { cursor ->
        if (!cursor.moveToFirst()) PrinterRuntimeSettings() else PrinterRuntimeSettings(
            preflightEnabled = cursor.getInt(0) != 0,
            historyEnabled = cursor.getInt(1) != 0,
            updatedBy = cursor.getString(2),
            updatedAt = cursor.getLong(3),
        )
    }

    fun saveSettings(preflightEnabled: Boolean, historyEnabled: Boolean, actor: String) {
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            db.update(
                "printer_runtime_settings",
                ContentValues().apply {
                    put("preflight_enabled", if (preflightEnabled) 1 else 0)
                    put("history_enabled", if (historyEnabled) 1 else 0)
                    put("updated_by", actor.ifBlank { "system" })
                    put("updated_at", now)
                },
                "id = 1",
                null,
            )
            insertAudit(
                eventType = "PRINTER_RUNTIME_SETTINGS_UPDATED",
                detail = "印刷前状態確認=${if (preflightEnabled) "有効" else "無効"} / 状態履歴=${if (historyEnabled) "有効" else "無効"}",
                actor = actor,
                createdAt = now,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun recordStatus(
        configuration: PrinterConfiguration,
        status: PrinterRealtimeStatus,
        checkedBy: String,
    ) {
        if (!loadSettings().historyEnabled) return
        insertHistory(
            configuration = configuration,
            level = status.level,
            summary = status.summary,
            rawHex = status.rawHex,
            elapsedMillis = status.elapsedMillis,
            success = true,
            checkedBy = checkedBy,
            checkedAt = status.checkedAt,
        )
    }

    fun recordFailure(
        configuration: PrinterConfiguration,
        error: Throwable,
        checkedBy: String,
        checkedAt: Long = System.currentTimeMillis(),
    ) {
        if (!loadSettings().historyEnabled) return
        insertHistory(
            configuration = configuration,
            level = null,
            summary = error.message ?: error.javaClass.simpleName,
            rawHex = "",
            elapsedMillis = 0L,
            success = false,
            checkedBy = checkedBy,
            checkedAt = checkedAt,
        )
    }

    fun listHistory(limit: Int = 100): List<PrinterStatusHistoryRecord> {
        val result = mutableListOf<PrinterStatusHistoryRecord>()
        db.query(
            "printer_status_history",
            arrayOf(
                "id", "printer_name", "host", "port", "profile_key", "level_key",
                "summary", "raw_hex", "elapsed_millis", "success", "checked_by", "checked_at",
            ),
            null,
            null,
            null,
            null,
            "checked_at DESC, id DESC",
            limit.coerceIn(1, 2_000).toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += PrinterStatusHistoryRecord(
                    id = cursor.getLong(0),
                    printerName = cursor.getString(1),
                    host = cursor.getString(2),
                    port = cursor.getInt(3),
                    profile = runCatching { PrinterProfile.valueOf(cursor.getString(4)) }
                        .getOrDefault(PrinterProfile.GENERIC_ESC_POS),
                    level = if (cursor.isNull(5)) null else runCatching {
                        PrinterStatusLevel.valueOf(cursor.getString(5))
                    }.getOrNull(),
                    summary = cursor.getString(6),
                    rawHex = cursor.getString(7),
                    elapsedMillis = cursor.getLong(8),
                    success = cursor.getInt(9) != 0,
                    checkedBy = cursor.getString(10),
                    checkedAt = cursor.getLong(11),
                )
            }
        }
        return result
    }

    fun clearHistory(actor: String) {
        val deleted = db.delete("printer_status_history", null, null)
        insertAudit(
            eventType = "PRINTER_STATUS_HISTORY_CLEARED",
            detail = "プリンター状態履歴 ${deleted}件を消去",
            actor = actor,
            createdAt = System.currentTimeMillis(),
        )
    }

    private fun insertHistory(
        configuration: PrinterConfiguration,
        level: PrinterStatusLevel?,
        summary: String,
        rawHex: String,
        elapsedMillis: Long,
        success: Boolean,
        checkedBy: String,
        checkedAt: Long,
    ) {
        db.beginTransaction()
        try {
            db.insertOrThrow(
                "printer_status_history",
                null,
                ContentValues().apply {
                    put("printer_name", configuration.name)
                    put("host", configuration.host)
                    put("port", configuration.port)
                    put("profile_key", configuration.profile.name)
                    if (level == null) putNull("level_key") else put("level_key", level.name)
                    put("summary", summary.take(500))
                    put("raw_hex", rawHex.take(100))
                    put("elapsed_millis", elapsedMillis)
                    put("success", if (success) 1 else 0)
                    put("checked_by", checkedBy.ifBlank { "system" })
                    put("checked_at", checkedAt)
                },
            )
            insertAudit(
                eventType = if (success) "PRINTER_STATUS_CHECKED" else "PRINTER_STATUS_FAILED",
                detail = "${configuration.host}:${configuration.port} / ${level?.displayName ?: "通信失敗"} / ${summary.take(300)}",
                actor = checkedBy,
                createdAt = checkedAt,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun insertAudit(eventType: String, detail: String, actor: String, createdAt: Long) {
        if (!SchemaMigration.tableExists(db, "operation_audit")) return
        db.insert(
            "operation_audit",
            null,
            ContentValues().apply {
                put("event_type", eventType)
                put("reference_id", 1L)
                put("detail", detail)
                put("operator_name", actor.ifBlank { "system" })
                put("created_at", createdAt)
            },
        )
    }

    private fun ensureSchema() {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS printer_runtime_settings (
                id INTEGER PRIMARY KEY CHECK(id = 1),
                preflight_enabled INTEGER NOT NULL DEFAULT 1,
                history_enabled INTEGER NOT NULL DEFAULT 1,
                updated_by TEXT NOT NULL DEFAULT 'system',
                updated_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO printer_runtime_settings(
                id, preflight_enabled, history_enabled, updated_by, updated_at
            ) VALUES(1, 1, 1, 'system', 0)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS printer_status_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                printer_name TEXT NOT NULL,
                host TEXT NOT NULL,
                port INTEGER NOT NULL,
                profile_key TEXT NOT NULL,
                level_key TEXT,
                summary TEXT NOT NULL,
                raw_hex TEXT NOT NULL DEFAULT '',
                elapsed_millis INTEGER NOT NULL DEFAULT 0,
                success INTEGER NOT NULL,
                checked_by TEXT NOT NULL,
                checked_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_printer_status_history_checked_at ON printer_status_history(checked_at DESC)",
        )
    }
}
