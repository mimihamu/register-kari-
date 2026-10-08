package jp.co.tenposinfo.register

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest
import java.util.UUID

/** Formal v2.5 SYN-001/SYN-002 immutable transport document. */
object OutboxDocumentV150 {
    const val TABLE = "outbox_document"
    const val SCHEMA_VERSION = 1
    const val STATUS_PENDING = "PENDING"
    const val COMPLETION_ACK_REQUIRED = "ACK_REQUIRED"
    const val COMPLETION_UPLOAD_CONFIRMED = "UPLOAD_CONFIRMED"

    data class Stored(
        val documentId: String,
        val eventId: String,
        val documentType: String,
        val sourceBusinessId: String,
        val schemaVersion: Int,
        val canonicalPayloadBytes: ByteArray,
        val sha256: String,
        val producerId: String,
        val sequenceNo: Long,
        val completionMode: String,
        val status: String,
        val createdAt: Long,
        val legacyMaterialized: Boolean,
    )

    fun ensureSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE (
                document_id TEXT PRIMARY KEY NOT NULL,
                event_id TEXT NOT NULL UNIQUE,
                document_type TEXT NOT NULL,
                source_business_id TEXT NOT NULL,
                schema_version INTEGER NOT NULL,
                canonical_payload_bytes BLOB NOT NULL,
                sha256 TEXT NOT NULL CHECK(length(sha256) = 64),
                producer_id TEXT NOT NULL,
                sequence_no INTEGER NOT NULL,
                completion_mode TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                legacy_materialized INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(event_id) REFERENCES sales_journal(event_id) ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_v150_outbox_document_sequence ON $TABLE(producer_id, sequence_no)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_v150_outbox_document_status ON $TABLE(status, created_at)")
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS trg_v150_outbox_document_immutable
            BEFORE UPDATE OF document_id,event_id,document_type,source_business_id,schema_version,
                             canonical_payload_bytes,sha256,producer_id,sequence_no,completion_mode,created_at,
                             legacy_materialized
            ON $TABLE
            BEGIN
                SELECT RAISE(ABORT, 'SYN_OUTBOX_DOCUMENT_IMMUTABLE');
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS trg_v150_outbox_document_no_delete
            BEFORE DELETE ON $TABLE
            BEGIN
                SELECT RAISE(ABORT, 'SYN_OUTBOX_DOCUMENT_DELETE_FORBIDDEN');
            END
            """.trimIndent(),
        )
    }

    fun documentIdFor(eventId: String): String = UUID.nameUUIDFromBytes(
        "TSUGUREGI:SYN-001:$eventId".toByteArray(Charsets.UTF_8),
    ).toString()

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    fun materializeLatest(
        db: SQLiteDatabase,
        eventType: String,
        aggregateId: String,
        legacyMaterialized: Boolean = false,
    ): Stored {
        val eventId = db.rawQuery(
            "SELECT event_id FROM sales_journal WHERE event_type=? AND aggregate_id=? ORDER BY created_at DESC, rowid DESC LIMIT 1",
            arrayOf(eventType, aggregateId),
        ).use { cursor ->
            require(cursor.moveToFirst()) { "同期ジャーナルが見つかりません: $eventType/$aggregateId" }
            cursor.getString(0)
        }
        return materialize(db, eventId, legacyMaterialized)
    }

    fun materialize(
        db: SQLiteDatabase,
        eventId: String,
        legacyMaterialized: Boolean = false,
    ): Stored {
        ensureSchema(db)
        load(db, eventId)?.let { stored ->
            verify(stored)
            return stored
        }
        val record = db.rawQuery(
            """
            SELECT o.id, o.event_id, j.business_date, j.event_type, j.aggregate_id,
                   o.object_key, o.status, o.attempt_count, o.last_error, o.created_at, o.updated_at
            FROM sync_outbox o
            INNER JOIN sales_journal j ON j.event_id=o.event_id
            WHERE o.event_id=?
            LIMIT 1
            """.trimIndent(),
            arrayOf(eventId),
        ).use { cursor ->
            require(cursor.moveToFirst()) { "同期キューが見つかりません: $eventId" }
            JournalOutboxRecord(
                id = cursor.getLong(0),
                eventId = cursor.getString(1),
                businessDate = cursor.getString(2),
                eventType = cursor.getString(3),
                aggregateId = cursor.getString(4),
                objectKey = cursor.getString(5),
                status = SyncOutboxStatus.valueOf(cursor.getString(6)),
                attemptCount = cursor.getInt(7),
                lastError = if (cursor.isNull(8)) null else cursor.getString(8),
                createdAt = cursor.getLong(9),
                updatedAt = cursor.getLong(10),
            )
        }
        // SYN-001: this is the only serializer call for a new business document and is invoked
        // inside the caller's business-finalization SQLite transaction.
        val bytes = OutboxPayloadAssembler.build(db, record).toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty()) { "同期documentが空です" }
        val identity = OutboxIdentitySnapshotV136.resolve(db, record.eventId)
        val hash = sha256(bytes)
        val stored = Stored(
            documentId = documentIdFor(record.eventId),
            eventId = record.eventId,
            documentType = documentType(record.eventType),
            sourceBusinessId = record.aggregateId,
            schemaVersion = SCHEMA_VERSION,
            canonicalPayloadBytes = bytes,
            sha256 = hash,
            producerId = identity.terminalId,
            sequenceNo = record.id,
            completionMode = completionMode(record.eventType),
            status = STATUS_PENDING,
            createdAt = record.createdAt,
            legacyMaterialized = legacyMaterialized,
        )
        db.insertOrThrow(
            TABLE,
            null,
            ContentValues().apply {
                put("document_id", stored.documentId)
                put("event_id", stored.eventId)
                put("document_type", stored.documentType)
                put("source_business_id", stored.sourceBusinessId)
                put("schema_version", stored.schemaVersion)
                put("canonical_payload_bytes", stored.canonicalPayloadBytes)
                put("sha256", stored.sha256)
                put("producer_id", stored.producerId)
                put("sequence_no", stored.sequenceNo)
                put("completion_mode", stored.completionMode)
                put("status", stored.status)
                put("created_at", stored.createdAt)
                put("legacy_materialized", if (legacyMaterialized) 1 else 0)
            },
        )
        verify(stored)
        return stored
    }

    /** One-time compatibility migration for rows created before SYN-001 existed. */
    fun backfillLegacyMissing(db: SQLiteDatabase, limit: Int = 500): Int {
        ensureSchema(db)
        val ids = db.rawQuery(
            """
            SELECT o.event_id
            FROM sync_outbox o
            LEFT JOIN $TABLE d ON d.event_id=o.event_id
            WHERE d.event_id IS NULL
            ORDER BY o.created_at ASC, o.id ASC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.coerceIn(1, 5_000).toString()),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        ids.forEach { materialize(db, it, legacyMaterialized = true) }
        return ids.size
    }

    fun loadVerifiedBytes(db: SQLiteDatabase, eventId: String): ByteArray {
        val stored = load(db, eventId) ?: error("SYN-001 outbox_documentがありません: $eventId")
        verify(stored)
        return stored.canonicalPayloadBytes
    }

    private fun load(db: SQLiteDatabase, eventId: String): Stored? = db.rawQuery(
        """
        SELECT document_id,event_id,document_type,source_business_id,schema_version,
               canonical_payload_bytes,sha256,producer_id,sequence_no,completion_mode,status,created_at,legacy_materialized
        FROM $TABLE WHERE event_id=? LIMIT 1
        """.trimIndent(),
        arrayOf(eventId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else Stored(
            documentId = cursor.getString(0), eventId = cursor.getString(1), documentType = cursor.getString(2),
            sourceBusinessId = cursor.getString(3), schemaVersion = cursor.getInt(4), canonicalPayloadBytes = cursor.getBlob(5),
            sha256 = cursor.getString(6), producerId = cursor.getString(7), sequenceNo = cursor.getLong(8),
            completionMode = cursor.getString(9), status = cursor.getString(10), createdAt = cursor.getLong(11),
            legacyMaterialized = cursor.getInt(12) != 0,
        )
    }

    private fun verify(stored: Stored) {
        require(stored.documentId == documentIdFor(stored.eventId)) { "SYN-001 documentIdが一致しません" }
        require(stored.schemaVersion > 0) { "SYN-001 schemaVersionが不正です" }
        require(stored.sequenceNo > 0L) { "SYN-001 sequenceNoが不正です" }
        require(stored.producerId.isNotBlank()) { "SYN-001 producerIdがありません" }
        require(sha256(stored.canonicalPayloadBytes) == stored.sha256) { "SYN-002 immutable bytes SHA-256が一致しません" }
    }

    private fun documentType(eventType: String): String = when (eventType) {
        JournalEventType.SALE.name -> "SALE_EVENT"
        JournalEventType.SETTLEMENT.name -> "SETTLEMENT"
        JournalEventType.MENU_APPLY_RESULT.name -> "MENU_APPLY_RESULT"
        JournalEventType.REVERSAL.name -> "REVERSAL_EVENT"
        else -> eventType
    }

    private fun completionMode(eventType: String): String = when (eventType) {
        JournalEventType.SALE.name,
        JournalEventType.SETTLEMENT.name,
        JournalEventType.REVERSAL.name,
        -> COMPLETION_ACK_REQUIRED
        else -> COMPLETION_UPLOAD_CONFIRMED
    }
}
