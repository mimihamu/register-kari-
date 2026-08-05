package jp.co.tenposinfo.register

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

data class SalesJournalIdentity(
    val storeId: String,
    val terminalId: String,
)

/**
 * つぐレジ＋が売上ジャーナルを安全に取り込むための固定契約。
 *
 * schemaVersion は後方互換を壊す変更時だけ増やす。
 * payload はv0.35以前の種別別JSONをそのまま保持し、段階的な移行を可能にする。
 */
object SalesJournalJsonContract {
    const val SCHEMA = "jp.co.tenposinfo.tsuguregi.sales-journal"
    const val SCHEMA_VERSION = 1
    const val MINIMUM_READER_VERSION = 1
    const val DUPLICATE_KEY_VERSION = 1

    private val legacySchemas = setOf(
        "register.sale.v2",
        "register.reversal.v2",
        "register.settlement.v1",
        "register.menu-revision.v1",
        "register.generic.v1",
    )
    private val schemaPattern = Regex("\"schema\"\\s*:\\s*\"([^\"]+)\"")
    private val schemaVersionPattern = Regex("\"schemaVersion\"\\s*:\\s*(\\d+)")

    fun wrap(
        record: JournalOutboxRecord,
        legacyPayload: String,
        identity: SalesJournalIdentity,
    ): String {
        val payload = legacyPayload.trim()
        require(payload.startsWith("{") && payload.endsWith("}")) { "payload must be a JSON object" }
        val payloadSchema = extractSchema(payload) ?: "register.generic.v1"
        val eventType = canonicalEventType(record.eventType, payload)
        val duplicateImportKey = duplicateImportKey(
            identity = identity,
            eventId = record.eventId,
            businessDate = record.businessDate,
            eventType = eventType,
        )
        return buildString {
            append('{')
            append("\"schema\":\"").append(SCHEMA).append("\",")
            append("\"schemaVersion\":").append(SCHEMA_VERSION).append(',')
            append("\"minimumReaderVersion\":").append(MINIMUM_READER_VERSION).append(',')
            append("\"duplicateKeyVersion\":").append(DUPLICATE_KEY_VERSION).append(',')
            append("\"eventId\":\"").append(escape(record.eventId)).append("\",")
            append("\"duplicateImportKey\":\"").append(duplicateImportKey).append("\",")
            append("\"eventType\":\"").append(eventType).append("\",")
            append("\"storeId\":\"").append(escape(identity.storeId)).append("\",")
            append("\"terminalId\":\"").append(escape(identity.terminalId)).append("\",")
            append("\"businessDate\":\"").append(escape(record.businessDate)).append("\",")
            append("\"aggregateId\":\"").append(escape(record.aggregateId)).append("\",")
            append("\"occurredAt\":").append(record.createdAt).append(',')
            append("\"payloadSchema\":\"").append(escape(payloadSchema)).append("\",")
            append("\"payload\":").append(payload)
            append('}')
        }
    }

    fun duplicateImportKey(
        identity: SalesJournalIdentity,
        eventId: String,
        businessDate: String,
        eventType: String,
    ): String {
        val source = listOf(
            DUPLICATE_KEY_VERSION.toString(),
            identity.storeId,
            identity.terminalId,
            businessDate,
            eventType,
            eventId,
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
        return "sj$DUPLICATE_KEY_VERSION-" + digest.joinToString("") { "%02x".format(it) }
    }

    fun canonicalEventType(eventType: String, payload: String): String = when (eventType.uppercase(Locale.ROOT)) {
        JournalEventType.SALE.name -> "SALE"
        JournalEventType.REVERSAL.name -> "REVERSAL"
        JournalEventType.SETTLEMENT.name -> {
            val reportType = extractString(payload, "type").orEmpty().uppercase(Locale.ROOT)
            if (
                reportType == "Z" ||
                reportType == "Z_SETTLEMENT" ||
                reportType == "DAILY_CLOSE" ||
                reportType.contains("精算")
            ) "Z_SETTLEMENT" else "INSPECTION"
        }
        JournalEventType.CASH_MOVEMENT.name -> "CASH_MOVEMENT"
        JournalEventType.BUSINESS_OPEN.name -> "BUSINESS_OPEN"
        JournalEventType.BUSINESS_STATE.name -> "BUSINESS_STATE"
        JournalEventType.MENU_REVISION.name -> "MENU_REVISION"
        else -> eventType.uppercase(Locale.ROOT)
    }

    fun extractSchema(json: String): String? = schemaPattern.find(json)?.groupValues?.get(1)

    fun supports(json: String): Boolean {
        val schema = extractSchema(json) ?: return false
        if (schema in legacySchemas) return true
        if (schema != SCHEMA) return false
        val version = schemaVersionPattern.find(json)?.groupValues?.get(1)?.toIntOrNull() ?: return false
        return version in MINIMUM_READER_VERSION..SCHEMA_VERSION
    }

    private fun extractString(json: String, key: String): String? =
        Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"([^\"]*)\"")
            .find(json)
            ?.groupValues
            ?.get(1)

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}

/**
 * 店舗IDは設定前でも明示的な未設定値を出力し、端末IDはDB内に一度生成して固定する。
 * これにより外部資格情報や端末権限を追加せず、端末単位の重複防止キーを安定生成できる。
 */
object SalesJournalIdentityStore {
    private const val STORE_ID_KEY = "sales_journal_store_id"
    private const val TERMINAL_ID_KEY = "sales_journal_terminal_id"
    private const val DEFAULT_STORE_ID = "STORE-UNCONFIGURED"

    fun resolve(db: SQLiteDatabase): SalesJournalIdentity {
        ensureSettingsTable(db)
        putIfMissing(db, STORE_ID_KEY, DEFAULT_STORE_ID)
        putIfMissing(db, TERMINAL_ID_KEY, "TERMINAL-${UUID.randomUUID().toString().uppercase(Locale.ROOT)}")
        return SalesJournalIdentity(
            storeId = read(db, STORE_ID_KEY) ?: DEFAULT_STORE_ID,
            terminalId = read(db, TERMINAL_ID_KEY) ?: error("terminal id was not persisted"),
        )
    }

    fun update(db: SQLiteDatabase, storeId: String, terminalId: String) {
        ensureSettingsTable(db)
        put(db, STORE_ID_KEY, normalize(storeId, "storeId"))
        put(db, TERMINAL_ID_KEY, normalize(terminalId, "terminalId"))
    }

    private fun normalize(value: String, label: String): String {
        val normalized = value.trim().map { char ->
            when {
                char.isLetterOrDigit() -> char
                char == '-' || char == '_' || char == '.' -> char
                else -> '_'
            }
        }.joinToString("").trim('_').take(80)
        require(normalized.isNotBlank()) { "$label must not be blank" }
        return normalized
    }

    private fun ensureSettingsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_runtime_settings (
                setting_key TEXT PRIMARY KEY,
                setting_value TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun putIfMissing(db: SQLiteDatabase, key: String, value: String) {
        db.insertWithOnConflict(
            "sync_runtime_settings",
            null,
            ContentValues().apply {
                put("setting_key", key)
                put("setting_value", value)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    private fun put(db: SQLiteDatabase, key: String, value: String) {
        db.insertWithOnConflict(
            "sync_runtime_settings",
            null,
            ContentValues().apply {
                put("setting_key", key)
                put("setting_value", value)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun read(db: SQLiteDatabase, key: String): String? = db.rawQuery(
        "SELECT setting_value FROM sync_runtime_settings WHERE setting_key = ?",
        arrayOf(key),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}
