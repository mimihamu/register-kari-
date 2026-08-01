package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase

data class PrinterStatusProbeHistoryRecord(
    val id: Long,
    val startedAt: Long,
    val profile: PrinterProfile,
    val preset: PrinterStatusProbePreset,
    val verification: PrinterStatusVerification,
    val host: String,
    val port: Int,
    val elapsedMillis: Long,
    val requestHex: String,
    val responseHex: String,
    val responseAscii: String,
    val responseSize: Int,
    val success: Boolean,
    val parsedLevel: PrinterStatusLevel?,
    val parsedSummary: String?,
    val protocolValid: Boolean?,
    val errorMessage: String?,
    val actor: String,
    val createdAt: Long,
)

object PrinterStatusProbeRetentionPolicy {
    const val DEFAULT_DAYS = 180
    const val MIN_DAYS = 1
    const val MAX_DAYS = 365
    const val MAX_ROWS = 1_000
    private const val DAY_MILLIS = 24L * 60L * 60L * 1_000L

    fun normalize(days: Int): Int = days.coerceIn(MIN_DAYS, MAX_DAYS)

    fun cutoff(now: Long, days: Int): Long = now - normalize(days) * DAY_MILLIS
}

class PrinterStatusProbeStore(context: Context) : AutoCloseable {
    private val database = RegisterDatabase(context.applicationContext)
    private val db: SQLiteDatabase = database.writableDatabase

    init {
        ensureSchema()
        prune(recordAudit = false)
    }

    override fun close() = database.close()

    fun retentionDays(): Int = db.rawQuery(
        "SELECT retention_days FROM printer_status_probe_settings WHERE id = 1",
        null,
    ).use { cursor ->
        if (cursor.moveToFirst()) {
            PrinterStatusProbeRetentionPolicy.normalize(cursor.getInt(0))
        } else {
            PrinterStatusProbeRetentionPolicy.DEFAULT_DAYS
        }
    }

    fun saveRetentionDays(days: Int, actor: String) {
        val normalized = PrinterStatusProbeRetentionPolicy.normalize(days)
        val now = System.currentTimeMillis()
        db.update(
            "printer_status_probe_settings",
            ContentValues().apply {
                put("retention_days", normalized)
                put("updated_by", actor.ifBlank { "system" })
                put("updated_at", now)
            },
            "id = 1",
            null,
        )
        insertAudit(
            eventType = "PRINTER_STATUS_PROBE_RETENTION_UPDATED",
            detail = "RAWプローブ履歴保持${normalized}日",
            actor = actor,
            createdAt = now,
        )
        prune(now = now, actor = actor)
    }

    fun recordSuccess(
        configuration: PrinterConfiguration,
        result: PrinterStatusProbeResult,
        actor: String,
    ): PrinterStatusProbeHistoryRecord {
        val parsed = result.parsedEpsonStatus
        val now = System.currentTimeMillis()
        val id = db.insertOrThrow(
            "printer_status_probe_history",
            null,
            ContentValues().apply {
                put("started_at", result.startedAt)
                put("profile_key", configuration.profile.name)
                put("preset_key", result.preset.name)
                put("verification_key", PrinterStatusCapabilityRegistry.forProfile(configuration.profile).verification.name)
                put("host", result.host)
                put("port", result.port)
                put("elapsed_millis", result.elapsedMillis)
                put("request_hex", result.requestHex)
                put("response_hex", result.responseHex)
                put("response_ascii", result.responseAscii)
                put("response_size", result.responseBytes.size)
                put("success", 1)
                if (parsed == null) {
                    putNull("parsed_level")
                    putNull("parsed_summary")
                    putNull("protocol_valid")
                } else {
                    put("parsed_level", parsed.level.name)
                    put("parsed_summary", parsed.summary)
                    put("protocol_valid", if (parsed.protocolValid) 1 else 0)
                }
                putNull("error_message")
                put("actor", actor.ifBlank { "system" })
                put("created_at", now)
            },
        )
        insertAudit(
            eventType = "PRINTER_STATUS_PROBE_SUCCEEDED",
            detail = "${configuration.profile.displayName} / ${result.preset.displayName} / ${result.host}:${result.port} / 受信${result.responseBytes.size}バイト / ${result.responseHex.take(300)}",
            actor = actor,
            createdAt = now,
        )
        prune(now = now, actor = actor)
        return requireNotNull(load(id))
    }

    fun recordFailure(
        configuration: PrinterConfiguration,
        preset: PrinterStatusProbePreset,
        error: Throwable,
        actor: String,
        startedAt: Long = System.currentTimeMillis(),
    ): PrinterStatusProbeHistoryRecord {
        val now = System.currentTimeMillis()
        val message = error.message ?: error.javaClass.simpleName
        val id = db.insertOrThrow(
            "printer_status_probe_history",
            null,
            ContentValues().apply {
                put("started_at", startedAt)
                put("profile_key", configuration.profile.name)
                put("preset_key", preset.name)
                put("verification_key", PrinterStatusCapabilityRegistry.forProfile(configuration.profile).verification.name)
                put("host", configuration.host.trim())
                put("port", configuration.port)
                put("elapsed_millis", 0L)
                put("request_hex", preset.requestBytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) })
                put("response_hex", "")
                put("response_ascii", "")
                put("response_size", 0)
                put("success", 0)
                putNull("parsed_level")
                putNull("parsed_summary")
                putNull("protocol_valid")
                put("error_message", message.take(1_000))
                put("actor", actor.ifBlank { "system" })
                put("created_at", now)
            },
        )
        insertAudit(
            eventType = "PRINTER_STATUS_PROBE_FAILED",
            detail = "${configuration.profile.displayName} / ${preset.displayName} / ${configuration.host}:${configuration.port} / ${message.take(500)}",
            actor = actor,
            createdAt = now,
        )
        prune(now = now, actor = actor)
        return requireNotNull(load(id))
    }

    fun load(id: Long): PrinterStatusProbeHistoryRecord? = db.query(
        "printer_status_probe_history",
        COLUMNS,
        "id = ?",
        arrayOf(id.toString()),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toProbeRecord() else null }

    fun listRecent(limit: Int = 200): List<PrinterStatusProbeHistoryRecord> {
        val records = mutableListOf<PrinterStatusProbeHistoryRecord>()
        db.query(
            "printer_status_probe_history",
            COLUMNS,
            null,
            null,
            null,
            null,
            "started_at DESC, id DESC",
            limit.coerceIn(1, PrinterStatusProbeRetentionPolicy.MAX_ROWS).toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) records += cursor.toProbeRecord()
        }
        return records
    }

    fun delete(ids: Collection<Long>, actor: String): Int {
        val normalized = ids.distinct().filter { it > 0 }
        if (normalized.isEmpty()) return 0
        val placeholders = normalized.joinToString(",") { "?" }
        val deleted = db.delete(
            "printer_status_probe_history",
            "id IN ($placeholders)",
            normalized.map(Long::toString).toTypedArray(),
        )
        insertAudit(
            eventType = "PRINTER_STATUS_PROBE_HISTORY_DELETED",
            detail = "RAWプローブ履歴${deleted}件を削除 / IDs=${normalized.joinToString(",")}",
            actor = actor,
            createdAt = System.currentTimeMillis(),
        )
        return deleted
    }

    fun prune(
        now: Long = System.currentTimeMillis(),
        actor: String = "system",
        recordAudit: Boolean = true,
    ): Int {
        val days = retentionDays()
        val cutoff = PrinterStatusProbeRetentionPolicy.cutoff(now, days)
        var deleted = db.delete(
            "printer_status_probe_history",
            "started_at < ?",
            arrayOf(cutoff.toString()),
        )
        val count = db.rawQuery("SELECT COUNT(*) FROM printer_status_probe_history", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
        val overflow = (count - PrinterStatusProbeRetentionPolicy.MAX_ROWS).coerceAtLeast(0L)
        if (overflow > 0) {
            deleted += db.delete(
                "printer_status_probe_history",
                "id IN (SELECT id FROM printer_status_probe_history ORDER BY started_at ASC, id ASC LIMIT ?)",
                arrayOf(overflow.toString()),
            )
        }
        if (deleted > 0 && recordAudit) {
            insertAudit(
                eventType = "PRINTER_STATUS_PROBE_HISTORY_PRUNED",
                detail = "保持${days}日／最大${PrinterStatusProbeRetentionPolicy.MAX_ROWS}件により${deleted}件を自動削除",
                actor = actor,
                createdAt = now,
            )
        }
        return deleted
    }

    private fun ensureSchema() {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS printer_status_probe_settings (
                id INTEGER PRIMARY KEY CHECK(id = 1),
                retention_days INTEGER NOT NULL DEFAULT 180,
                updated_by TEXT NOT NULL DEFAULT 'system',
                updated_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO printer_status_probe_settings(id, retention_days, updated_by, updated_at)
            VALUES(1, 180, 'system', 0)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS printer_status_probe_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at INTEGER NOT NULL,
                profile_key TEXT NOT NULL,
                preset_key TEXT NOT NULL,
                verification_key TEXT NOT NULL,
                host TEXT NOT NULL,
                port INTEGER NOT NULL,
                elapsed_millis INTEGER NOT NULL DEFAULT 0,
                request_hex TEXT NOT NULL DEFAULT '',
                response_hex TEXT NOT NULL DEFAULT '',
                response_ascii TEXT NOT NULL DEFAULT '',
                response_size INTEGER NOT NULL DEFAULT 0,
                success INTEGER NOT NULL,
                parsed_level TEXT,
                parsed_summary TEXT,
                protocol_valid INTEGER,
                error_message TEXT,
                actor TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_printer_status_probe_started ON printer_status_probe_history(started_at DESC)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_printer_status_probe_profile ON printer_status_probe_history(profile_key, started_at DESC)",
        )
    }

    private fun android.database.Cursor.toProbeRecord(): PrinterStatusProbeHistoryRecord =
        PrinterStatusProbeHistoryRecord(
            id = getLong(0),
            startedAt = getLong(1),
            profile = runCatching { PrinterProfile.valueOf(getString(2)) }
                .getOrDefault(PrinterProfile.GENERIC_ESC_POS),
            preset = runCatching { PrinterStatusProbePreset.valueOf(getString(3)) }
                .getOrDefault(PrinterStatusProbePreset.TCP_CONNECT_ONLY),
            verification = runCatching { PrinterStatusVerification.valueOf(getString(4)) }
                .getOrDefault(PrinterStatusVerification.EXPERIMENTAL_COMPATIBILITY),
            host = getString(5),
            port = getInt(6),
            elapsedMillis = getLong(7),
            requestHex = getString(8),
            responseHex = getString(9),
            responseAscii = getString(10),
            responseSize = getInt(11),
            success = getInt(12) != 0,
            parsedLevel = if (isNull(13)) null else runCatching { PrinterStatusLevel.valueOf(getString(13)) }.getOrNull(),
            parsedSummary = if (isNull(14)) null else getString(14),
            protocolValid = if (isNull(15)) null else getInt(15) != 0,
            errorMessage = if (isNull(16)) null else getString(16),
            actor = getString(17),
            createdAt = getLong(18),
        )

    private fun insertAudit(eventType: String, detail: String, actor: String, createdAt: Long) {
        if (!SchemaMigration.tableExists(db, "operation_audit")) return
        db.insert(
            "operation_audit",
            null,
            ContentValues().apply {
                put("event_type", eventType)
                put("reference_id", 0L)
                put("detail", detail)
                put("operator_name", actor.ifBlank { "system" })
                put("created_at", createdAt)
            },
        )
    }

    private companion object {
        val COLUMNS = arrayOf(
            "id", "started_at", "profile_key", "preset_key", "verification_key",
            "host", "port", "elapsed_millis", "request_hex", "response_hex",
            "response_ascii", "response_size", "success", "parsed_level",
            "parsed_summary", "protocol_valid", "error_message", "actor", "created_at",
        )
    }
}
