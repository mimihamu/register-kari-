package jp.co.tenposinfo.register

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * 営業中は端末の暦日ではなく、営業開始時に確定した営業日を使用する。
 * 日跨ぎ営業中のメニュー改定、ジャーナル、同期ファイルの所属日を統一する。
 */
object BusinessDateResolver {
    fun current(context: Context): LocalDate = RegisterDatabase(context.applicationContext).use { database ->
        current(database.readableDatabase)
    }

    fun current(db: SQLiteDatabase, calendarDate: LocalDate = LocalDate.now()): LocalDate = runCatching {
        db.rawQuery(
            """
            SELECT business_date
            FROM business_sessions
            WHERE status IN ('OPEN', 'Z_SETTLED')
            ORDER BY opened_at DESC
            LIMIT 1
            """.trimIndent(),
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) LocalDate.parse(cursor.getString(0)) else calendarDate
        }
    }.getOrDefault(calendarDate)
}

object BusinessDatePolicy {
    fun resolve(activeBusinessDate: String?, calendarDate: LocalDate): LocalDate =
        activeBusinessDate?.takeIf { it.isNotBlank() }?.let(LocalDate::parse) ?: calendarDate
}

enum class JournalEventType {
    SALE,
    REVERSAL,
    SETTLEMENT,
    CASH_MOVEMENT,
    BUSINESS_OPEN,
    BUSINESS_STATE,
    MENU_REVISION,
}

enum class SyncOutboxStatus {
    PENDING,
    PROCESSING,
    RETRY,
    STAGED,
    SENT,
    FAILED,
}

data class JournalOutboxRecord(
    val id: Long,
    val eventId: String,
    val businessDate: String,
    val eventType: String,
    val aggregateId: String,
    val objectKey: String,
    val status: SyncOutboxStatus,
    val attemptCount: Int,
    val lastError: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

data class JournalOutboxSummary(
    val journalCount: Int,
    val pendingCount: Int,
    val retryCount: Int,
    val stagedCount: Int,
    val sentCount: Int,
    val failedCount: Int,
)

object OutboxObjectKey {
    fun sanitizeSegment(value: String): String {
        val normalized = value.trim().map { char ->
            when {
                char.isLetterOrDigit() -> char
                char == '-' || char == '_' || char == '.' -> char
                else -> '_'
            }
        }.joinToString("")
        return normalized.trim('_').take(80).ifBlank { "unnamed" }
    }

    fun build(folderName: String, businessDate: String, eventType: String, aggregateId: String): String =
        listOf(
            sanitizeSegment(folderName),
            sanitizeSegment(businessDate),
            "${sanitizeSegment(eventType.lowercase())}-${sanitizeSegment(aggregateId)}.json",
        ).joinToString("/")
}

object JournalOutboxSchema {
    fun ensureCore(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sales_journal (
                event_id TEXT PRIMARY KEY,
                business_date TEXT NOT NULL,
                event_type TEXT NOT NULL,
                aggregate_id TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_outbox (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_id TEXT NOT NULL UNIQUE,
                destination TEXT NOT NULL,
                object_key TEXT NOT NULL,
                status TEXT NOT NULL,
                attempt_count INTEGER NOT NULL DEFAULT 0,
                next_attempt_at INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(event_id) REFERENCES sales_journal(event_id)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_outbox_status ON sync_outbox(status, next_attempt_at, created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sales_journal_business_date ON sales_journal(business_date, event_type, created_at)")
    }

    /** 売上確定トランザクション内から呼び、売上と同期対象を同時に確定する。 */
    fun recordSale(
        db: SQLiteDatabase,
        saleId: Long,
        totalAmount: Long,
        taxAmount: Long,
        createdAt: Long,
    ) {
        ensureCore(db)
        val businessDate = BusinessDateResolver.current(db).toString()
        val eventId = "sale-$saleId-$createdAt"
        val payload = "{\"saleId\":$saleId,\"totalAmount\":$totalAmount,\"taxAmount\":$taxAmount}"
        insertJournalAndOutbox(
            db = db,
            eventId = eventId,
            businessDate = businessDate,
            eventType = JournalEventType.SALE.name,
            aggregateId = saleId.toString(),
            payloadJson = payload,
            createdAt = createdAt,
        )
    }

    fun ensureOperationAndMasterTriggers(db: SQLiteDatabase) {
        ensureCore(db)
        createTrigger(
            db,
            "trg_v11_reversal_journal",
            """
            CREATE TRIGGER IF NOT EXISTS trg_v11_reversal_journal
            AFTER INSERT ON reversal_transactions
            BEGIN
                INSERT OR IGNORE INTO sales_journal(event_id, business_date, event_type, aggregate_id, payload_json, created_at)
                VALUES(
                    'reversal-' || NEW.id || '-' || NEW.created_at,
                    COALESCE((SELECT business_date FROM business_sessions WHERE status IN ('OPEN','Z_SETTLED') ORDER BY opened_at DESC LIMIT 1), strftime('%Y-%m-%d', NEW.created_at / 1000, 'unixepoch', 'localtime')),
                    'REVERSAL', CAST(NEW.id AS TEXT),
                    '{"reversalId":' || NEW.id || ',"originalSaleId":' || NEW.original_sale_id || ',"amount":' || NEW.gross_amount || '}',
                    NEW.created_at
                );
                INSERT OR IGNORE INTO sync_outbox(event_id, destination, object_key, status, attempt_count, next_attempt_at, created_at, updated_at)
                SELECT event_id, 'GOOGLE_DRIVE', 'REGISTER/' || business_date || '/reversal-' || aggregate_id || '.json', 'PENDING', 0, 0, created_at, created_at
                FROM sales_journal WHERE event_id = 'reversal-' || NEW.id || '-' || NEW.created_at;
            END
            """.trimIndent(),
        )
        createTrigger(
            db,
            "trg_v11_settlement_journal",
            """
            CREATE TRIGGER IF NOT EXISTS trg_v11_settlement_journal
            AFTER INSERT ON settlement_reports
            BEGIN
                INSERT OR IGNORE INTO sales_journal(event_id, business_date, event_type, aggregate_id, payload_json, created_at)
                VALUES(
                    'settlement-' || NEW.id || '-' || NEW.created_at,
                    NEW.business_date,
                    'SETTLEMENT', CAST(NEW.id AS TEXT),
                    '{"reportId":' || NEW.id || ',"netSales":' || NEW.net_sales || ',"variance":' || NEW.variance || '}',
                    NEW.created_at
                );
                INSERT OR IGNORE INTO sync_outbox(event_id, destination, object_key, status, attempt_count, next_attempt_at, created_at, updated_at)
                SELECT event_id, 'GOOGLE_DRIVE', 'REGISTER/' || business_date || '/settlement-' || aggregate_id || '.json', 'PENDING', 0, 0, created_at, created_at
                FROM sales_journal WHERE event_id = 'settlement-' || NEW.id || '-' || NEW.created_at;
            END
            """.trimIndent(),
        )
        createTrigger(
            db,
            "trg_v11_cash_movement_journal",
            """
            CREATE TRIGGER IF NOT EXISTS trg_v11_cash_movement_journal
            AFTER INSERT ON cash_movements
            BEGIN
                INSERT OR IGNORE INTO sales_journal(event_id, business_date, event_type, aggregate_id, payload_json, created_at)
                VALUES(
                    'cash-' || NEW.id || '-' || NEW.created_at,
                    COALESCE((SELECT business_date FROM business_sessions WHERE status IN ('OPEN','Z_SETTLED') ORDER BY opened_at DESC LIMIT 1), strftime('%Y-%m-%d', NEW.created_at / 1000, 'unixepoch', 'localtime')),
                    'CASH_MOVEMENT', CAST(NEW.id AS TEXT),
                    '{"movementId":' || NEW.id || ',"amount":' || NEW.amount || '}',
                    NEW.created_at
                );
                INSERT OR IGNORE INTO sync_outbox(event_id, destination, object_key, status, attempt_count, next_attempt_at, created_at, updated_at)
                SELECT event_id, 'GOOGLE_DRIVE', 'REGISTER/' || business_date || '/cash-' || aggregate_id || '.json', 'PENDING', 0, 0, created_at, created_at
                FROM sales_journal WHERE event_id = 'cash-' || NEW.id || '-' || NEW.created_at;
            END
            """.trimIndent(),
        )
        createTrigger(
            db,
            "trg_v11_business_open_journal",
            """
            CREATE TRIGGER IF NOT EXISTS trg_v11_business_open_journal
            AFTER INSERT ON business_sessions
            BEGIN
                INSERT OR IGNORE INTO sales_journal(event_id, business_date, event_type, aggregate_id, payload_json, created_at)
                VALUES(
                    'business-open-' || NEW.id || '-' || NEW.opened_at,
                    NEW.business_date,
                    'BUSINESS_OPEN', CAST(NEW.id AS TEXT),
                    '{"sessionId":' || NEW.id || ',"openingCash":' || NEW.opening_cash || '}',
                    NEW.opened_at
                );
                INSERT OR IGNORE INTO sync_outbox(event_id, destination, object_key, status, attempt_count, next_attempt_at, created_at, updated_at)
                SELECT event_id, 'GOOGLE_DRIVE', 'REGISTER/' || business_date || '/business-open-' || aggregate_id || '.json', 'PENDING', 0, 0, created_at, created_at
                FROM sales_journal WHERE event_id = 'business-open-' || NEW.id || '-' || NEW.opened_at;
            END
            """.trimIndent(),
        )
        createTrigger(
            db,
            "trg_v11_business_state_journal",
            """
            CREATE TRIGGER IF NOT EXISTS trg_v11_business_state_journal
            AFTER UPDATE OF status ON business_sessions
            WHEN OLD.status <> NEW.status
            BEGIN
                INSERT OR IGNORE INTO sales_journal(event_id, business_date, event_type, aggregate_id, payload_json, created_at)
                VALUES(
                    'business-state-' || NEW.id || '-' || COALESCE(NEW.closed_at, strftime('%s','now') * 1000) || '-' || NEW.status,
                    NEW.business_date,
                    'BUSINESS_STATE', CAST(NEW.id AS TEXT),
                    '{"sessionId":' || NEW.id || '}',
                    COALESCE(NEW.closed_at, strftime('%s','now') * 1000)
                );
                INSERT OR IGNORE INTO sync_outbox(event_id, destination, object_key, status, attempt_count, next_attempt_at, created_at, updated_at)
                SELECT event_id, 'GOOGLE_DRIVE', 'REGISTER/' || business_date || '/business-state-' || aggregate_id || '-' || NEW.status || '.json', 'PENDING', 0, 0, created_at, created_at
                FROM sales_journal WHERE event_id = 'business-state-' || NEW.id || '-' || COALESCE(NEW.closed_at, strftime('%s','now') * 1000) || '-' || NEW.status;
            END
            """.trimIndent(),
        )
        createTrigger(
            db,
            "trg_v11_menu_revision_journal",
            """
            CREATE TRIGGER IF NOT EXISTS trg_v11_menu_revision_journal
            AFTER INSERT ON menu_revisions
            BEGIN
                INSERT OR IGNORE INTO sales_journal(event_id, business_date, event_type, aggregate_id, payload_json, created_at)
                VALUES(
                    'menu-revision-' || NEW.id || '-' || NEW.created_at,
                    NEW.effective_date,
                    'MENU_REVISION', CAST(NEW.id AS TEXT),
                    '{"revisionId":' || NEW.id || '}',
                    NEW.created_at
                );
                INSERT OR IGNORE INTO sync_outbox(event_id, destination, object_key, status, attempt_count, next_attempt_at, created_at, updated_at)
                SELECT event_id, 'GOOGLE_DRIVE', 'REGISTER/' || business_date || '/menu-revision-' || aggregate_id || '.json', 'PENDING', 0, 0, created_at, created_at
                FROM sales_journal WHERE event_id = 'menu-revision-' || NEW.id || '-' || NEW.created_at;
            END
            """.trimIndent(),
        )
    }

    private fun createTrigger(db: SQLiteDatabase, name: String, sql: String) {
        runCatching { db.execSQL(sql) }.getOrElse { error("同期トリガー $name の作成に失敗しました: ${it.message}") }
    }

    private fun insertJournalAndOutbox(
        db: SQLiteDatabase,
        eventId: String,
        businessDate: String,
        eventType: String,
        aggregateId: String,
        payloadJson: String,
        createdAt: Long,
    ) {
        db.insertWithOnConflict(
            "sales_journal",
            null,
            ContentValues().apply {
                put("event_id", eventId)
                put("business_date", businessDate)
                put("event_type", eventType)
                put("aggregate_id", aggregateId)
                put("payload_json", payloadJson)
                put("created_at", createdAt)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        db.insertWithOnConflict(
            "sync_outbox",
            null,
            ContentValues().apply {
                put("event_id", eventId)
                put("destination", "GOOGLE_DRIVE")
                put("object_key", OutboxObjectKey.build("REGISTER", businessDate, eventType, aggregateId))
                put("status", SyncOutboxStatus.PENDING.name)
                put("attempt_count", 0)
                put("next_attempt_at", 0)
                put("created_at", createdAt)
                put("updated_at", createdAt)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }
}

class JournalOutboxStore(context: Context) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val database = RegisterDatabase(applicationContext)
    private val db = database.writableDatabase

    init {
        JournalOutboxSchema.ensureCore(db)
    }

    override fun close() = database.close()

    fun summary(): JournalOutboxSummary {
        val counts = linkedMapOf<String, Int>()
        db.rawQuery("SELECT status, COUNT(*) FROM sync_outbox GROUP BY status", null).use { cursor ->
            while (cursor.moveToNext()) counts[cursor.getString(0)] = cursor.getInt(1)
        }
        return JournalOutboxSummary(
            journalCount = scalarInt("SELECT COUNT(*) FROM sales_journal"),
            pendingCount = counts[SyncOutboxStatus.PENDING.name] ?: 0,
            retryCount = counts[SyncOutboxStatus.RETRY.name] ?: 0,
            stagedCount = counts[SyncOutboxStatus.STAGED.name] ?: 0,
            sentCount = counts[SyncOutboxStatus.SENT.name] ?: 0,
            failedCount = counts[SyncOutboxStatus.FAILED.name] ?: 0,
        )
    }

    fun list(limit: Int = 200): List<JournalOutboxRecord> {
        val result = mutableListOf<JournalOutboxRecord>()
        db.rawQuery(
            """
            SELECT o.id, o.event_id, j.business_date, j.event_type, j.aggregate_id,
                   o.object_key, o.status, o.attempt_count, o.last_error, o.created_at, o.updated_at
            FROM sync_outbox o
            INNER JOIN sales_journal j ON j.event_id = o.event_id
            ORDER BY o.created_at DESC, o.id DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.coerceIn(1, 1000).toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toOutboxRecord()
        }
        return result
    }

    fun stagePending(limit: Int = 100): Int {
        val folder = stagingRoot()
        folder.mkdirs()
        val candidates = db.rawQuery(
            """
            SELECT o.id, o.event_id, j.business_date, j.event_type, j.aggregate_id,
                   o.object_key, o.status, o.attempt_count, o.last_error, o.created_at, o.updated_at
            FROM sync_outbox o
            INNER JOIN sales_journal j ON j.event_id = o.event_id
            WHERE o.status IN ('PENDING','RETRY') AND o.next_attempt_at <= ?
            ORDER BY o.created_at ASC, o.id ASC
            LIMIT ?
            """.trimIndent(),
            arrayOf(System.currentTimeMillis().toString(), limit.coerceIn(1, 500).toString()),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toOutboxRecord()) }
        }
        var completed = 0
        candidates.forEach { record ->
            markProcessing(record)
            runCatching {
                val payload = OutboxPayloadAssembler.build(db, record)
                val target = File(folder, record.objectKey)
                target.parentFile?.mkdirs()
                target.writeText(payload, Charsets.UTF_8)
                markStaged(record.id)
                completed++
            }.onFailure { error ->
                markRetry(record, error)
            }
        }
        return completed
    }

    fun requeueStaged(): Int {
        val now = System.currentTimeMillis()
        return db.update(
            "sync_outbox",
            ContentValues().apply {
                put("status", SyncOutboxStatus.PENDING.name)
                put("next_attempt_at", 0)
                putNull("last_error")
                put("updated_at", now)
            },
            "status = ?",
            arrayOf(SyncOutboxStatus.STAGED.name),
        )
    }

    fun stagingRoot(): File = File(applicationContext.filesDir, "drive-sync-staging")

    private fun markProcessing(record: JournalOutboxRecord) {
        db.update(
            "sync_outbox",
            ContentValues().apply {
                put("status", SyncOutboxStatus.PROCESSING.name)
                put("attempt_count", record.attemptCount + 1)
                put("updated_at", System.currentTimeMillis())
            },
            "id = ?",
            arrayOf(record.id.toString()),
        )
    }

    private fun markStaged(id: Long) {
        db.update(
            "sync_outbox",
            ContentValues().apply {
                put("status", SyncOutboxStatus.STAGED.name)
                putNull("last_error")
                put("updated_at", System.currentTimeMillis())
            },
            "id = ?",
            arrayOf(id.toString()),
        )
    }

    private fun markRetry(record: JournalOutboxRecord, error: Throwable) {
        val attempts = record.attemptCount + 1
        val permanent = attempts >= 5
        db.update(
            "sync_outbox",
            ContentValues().apply {
                put("status", if (permanent) SyncOutboxStatus.FAILED.name else SyncOutboxStatus.RETRY.name)
                put("next_attempt_at", if (permanent) Long.MAX_VALUE else System.currentTimeMillis() + retryDelay(attempts))
                put("last_error", (error.message ?: error.javaClass.simpleName).take(500))
                put("updated_at", System.currentTimeMillis())
            },
            "id = ?",
            arrayOf(record.id.toString()),
        )
    }

    private fun retryDelay(attempt: Int): Long = when (attempt) {
        1 -> 60_000L
        2 -> 5 * 60_000L
        3 -> 30 * 60_000L
        else -> 2 * 60 * 60_000L
    }

    private fun scalarInt(sql: String): Int = db.rawQuery(sql, null).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    private fun Cursor.toOutboxRecord() = JournalOutboxRecord(
        id = getLong(0),
        eventId = getString(1),
        businessDate = getString(2),
        eventType = getString(3),
        aggregateId = getString(4),
        objectKey = getString(5),
        status = SyncOutboxStatus.valueOf(getString(6)),
        attemptCount = getInt(7),
        lastError = if (isNull(8)) null else getString(8),
        createdAt = getLong(9),
        updatedAt = getLong(10),
    )
}

object OutboxPayloadAssembler {
    fun build(db: SQLiteDatabase, record: JournalOutboxRecord): String = when (record.eventType) {
        JournalEventType.SALE.name -> salePayload(db, record)
        JournalEventType.REVERSAL.name -> reversalPayload(db, record)
        JournalEventType.SETTLEMENT.name -> settlementPayload(db, record)
        JournalEventType.MENU_REVISION.name -> menuRevisionPayload(db, record)
        else -> genericPayload(db, record)
    }

    private fun salePayload(db: SQLiteDatabase, record: JournalOutboxRecord): String {
        val saleId = record.aggregateId.toLong()
        val header = db.rawQuery(
            "SELECT operator_name, payment_method, net_amount, tax_amount, total_amount, deposit_amount, change_amount, created_at FROM sales WHERE id = ?",
            arrayOf(saleId.toString()),
        ).use { cursor ->
            require(cursor.moveToFirst()) { "売上が見つかりません" }
            listOf(
                cursor.getString(0), cursor.getString(1), cursor.getLong(2).toString(), cursor.getLong(3).toString(),
                cursor.getLong(4).toString(), cursor.getLong(5).toString(), cursor.getLong(6).toString(), cursor.getLong(7).toString(),
            )
        }
        val items = db.rawQuery(
            "SELECT product_id, product_name, unit_price, tax_category, quantity, discount_amount, note FROM sale_items WHERE sale_id = ? ORDER BY id",
            arrayOf(saleId.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        "{\"productId\":\"${escape(cursor.getString(0))}\",\"name\":\"${escape(cursor.getString(1))}\",\"unitPrice\":${cursor.getLong(2)},\"taxCategory\":\"${escape(cursor.getString(3))}\",\"quantity\":${cursor.getInt(4)},\"discount\":${cursor.getLong(5)},\"note\":\"${escape(cursor.getString(6))}\"}",
                    )
                }
            }.joinToString(",")
        }
        val payments = db.rawQuery(
            "SELECT payment_method, applied_amount, received_amount FROM sale_payments WHERE sale_id = ? ORDER BY sequence_no",
            arrayOf(saleId.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add("{\"method\":\"${escape(cursor.getString(0))}\",\"applied\":${cursor.getLong(1)},\"received\":${cursor.getLong(2)}}")
                }
            }.joinToString(",")
        }
        return """{"schema":"register.sale.v1","eventId":"${escape(record.eventId)}","businessDate":"${escape(record.businessDate)}","saleId":$saleId,"operator":"${escape(header[0])}","paymentLabel":"${escape(header[1])}","netAmount":${header[2]},"taxAmount":${header[3]},"totalAmount":${header[4]},"depositAmount":${header[5]},"changeAmount":${header[6]},"createdAt":${header[7]},"items":[$items],"payments":[$payments]}"""
    }

    private fun reversalPayload(db: SQLiteDatabase, record: JournalOutboxRecord): String {
        val id = record.aggregateId.toLong()
        return db.rawQuery(
            "SELECT original_sale_id, reversal_type, gross_amount, reason, operator_name, created_at FROM reversal_transactions WHERE id = ?",
            arrayOf(id.toString()),
        ).use { cursor ->
            require(cursor.moveToFirst()) { "返品・取消が見つかりません" }
            """{"schema":"register.reversal.v1","eventId":"${escape(record.eventId)}","businessDate":"${escape(record.businessDate)}","reversalId":$id,"originalSaleId":${cursor.getLong(0)},"type":"${escape(cursor.getString(1))}","grossAmount":${cursor.getLong(2)},"reason":"${escape(cursor.getString(3))}","operator":"${escape(cursor.getString(4))}","createdAt":${cursor.getLong(5)}}"""
        }
    }

    private fun settlementPayload(db: SQLiteDatabase, record: JournalOutboxRecord): String {
        val id = record.aggregateId.toLong()
        return db.rawQuery(
            "SELECT business_date, report_type, sales_gross, reversal_gross, net_sales, expected_cash, actual_cash, variance, transaction_count, reversal_count, operator_name, created_at FROM settlement_reports WHERE id = ?",
            arrayOf(id.toString()),
        ).use { cursor ->
            require(cursor.moveToFirst()) { "点検・精算票が見つかりません" }
            """{"schema":"register.settlement.v1","eventId":"${escape(record.eventId)}","businessDate":"${escape(cursor.getString(0))}","reportId":$id,"type":"${escape(cursor.getString(1))}","salesGross":${cursor.getLong(2)},"reversalGross":${cursor.getLong(3)},"netSales":${cursor.getLong(4)},"expectedCash":${cursor.getLong(5)},"actualCash":${cursor.getLong(6)},"variance":${cursor.getLong(7)},"transactionCount":${cursor.getInt(8)},"reversalCount":${cursor.getInt(9)},"operator":"${escape(cursor.getString(10))}","createdAt":${cursor.getLong(11)}}"""
        }
    }

    private fun menuRevisionPayload(db: SQLiteDatabase, record: JournalOutboxRecord): String {
        val id = record.aggregateId.toLong()
        val header = db.rawQuery(
            "SELECT name, effective_date, status, created_by, created_at FROM menu_revisions WHERE id = ?",
            arrayOf(id.toString()),
        ).use { cursor ->
            require(cursor.moveToFirst()) { "メニュー改定が見つかりません" }
            listOf(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getLong(4).toString())
        }
        val products = db.rawQuery(
            "SELECT product_id, product_name, enabled, unit_price, tax_key, button_color, page_no, slot_no FROM menu_revision_products WHERE revision_id = ? ORDER BY display_order, product_id",
            arrayOf(id.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add("{\"productId\":\"${escape(cursor.getString(0))}\",\"name\":\"${escape(cursor.getString(1))}\",\"enabled\":${cursor.getInt(2) != 0},\"unitPrice\":${cursor.getLong(3)},\"taxKey\":\"${escape(cursor.getString(4))}\",\"buttonColor\":\"${escape(cursor.getString(5))}\",\"pageNo\":${cursor.getInt(6)},\"slotNo\":${cursor.getInt(7)}}")
                }
            }.joinToString(",")
        }
        return """{"schema":"register.menu-revision.v1","eventId":"${escape(record.eventId)}","revisionId":$id,"name":"${escape(header[0])}","effectiveDate":"${escape(header[1])}","status":"${escape(header[2])}","createdBy":"${escape(header[3])}","createdAt":${header[4]},"products":[$products]}"""
    }

    private fun genericPayload(db: SQLiteDatabase, record: JournalOutboxRecord): String {
        val payload = db.rawQuery("SELECT payload_json FROM sales_journal WHERE event_id = ?", arrayOf(record.eventId)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else "{}"
        }
        return """{"schema":"register.event.v1","eventId":"${escape(record.eventId)}","businessDate":"${escape(record.businessDate)}","eventType":"${escape(record.eventType)}","aggregateId":"${escape(record.aggregateId)}","payload":$payload}"""
    }

    private fun escape(value: String): String = buildString {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
}

data class DriveSyncFoundationSettings(
    val automaticStaging: Boolean,
    val folderName: String,
)

object DriveSyncSettingsStore {
    private const val PREFS = "drive_sync_foundation"
    private const val KEY_AUTOMATIC = "automatic_staging"
    private const val KEY_FOLDER = "folder_name"

    fun load(context: Context): DriveSyncFoundationSettings {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return DriveSyncFoundationSettings(
            automaticStaging = preferences.getBoolean(KEY_AUTOMATIC, true),
            folderName = preferences.getString(KEY_FOLDER, "REGISTER") ?: "REGISTER",
        )
    }

    fun save(context: Context, settings: DriveSyncFoundationSettings) {
        val folder = OutboxObjectKey.sanitizeSegment(settings.folderName)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTOMATIC, settings.automaticStaging)
            .putString(KEY_FOLDER, folder)
            .apply()
    }
}

class DriveOutboxWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val settings = DriveSyncSettingsStore.load(applicationContext)
        if (!settings.automaticStaging) return Result.success()
        return runCatching {
            JournalOutboxStore(applicationContext).use { it.stagePending(100) }
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }
}

object DriveOutboxScheduler {
    private const val PERIODIC_NAME = "register-drive-outbox-periodic"
    private const val IMMEDIATE_NAME = "register-drive-outbox-immediate"

    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<DriveOutboxWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun enqueueNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<DriveOutboxWorker>().build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}

/**
 * 起動時に拡張テーブルとジャーナルトリガーを準備する。
 * 実Google Drive OAuthとアップロードは後続版で接続し、v0.11は耐障害Outboxとファイル生成までを担当する。
 */
class JournalOutboxBootstrapProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return false
        runCatching {
            AdvancedOperationsStore(appContext).also { it.close() }
            DynamicCatalogStore(appContext).also { it.close() }
            RegisterDatabase(appContext).use { database ->
                JournalOutboxSchema.ensureCore(database.writableDatabase)
                JournalOutboxSchema.ensureOperationAndMasterTriggers(database.writableDatabase)
            }
            DriveOutboxScheduler.ensurePeriodic(appContext)
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
