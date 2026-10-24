from pathlib import Path

root = Path('.')

def replace_once(path: str, old: str, new: str):
    p = root / path
    text = p.read_text()
    if old not in text:
        raise SystemExit(f'missing patch anchor: {path}: {old[:120]!r}')
    p.write_text(text.replace(old, new, 1))

# ---------------------------------------------------------------------------
# REGISTER: immutable outbox document store
# ---------------------------------------------------------------------------
outbox_doc = r'''package jp.co.tenposinfo.register

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
'''
(root / 'app/src/main/java/jp/co/tenposinfo/register/OutboxDocumentV150.kt').write_text(outbox_doc)

business = 'app/src/main/java/jp/co/tenposinfo/register/BusinessSyncFoundation.kt'
replace_once(business, '    MENU_REVISION,\n}', '    MENU_REVISION,\n    MENU_APPLY_RESULT,\n}')
replace_once(
    business,
    '        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sales_journal_business_date ON sales_journal(business_date, event_type, created_at)")\n    }',
    '        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sales_journal_business_date ON sales_journal(business_date, event_type, created_at)")\n        OutboxDocumentV150.ensureSchema(db)\n    }',
)
replace_once(
    business,
    '    fun updateFolderName(db: SQLiteDatabase, folderName: String) {',
    '''    fun recordMenuApplyResult(
        db: SQLiteDatabase,
        revisionId: Long,
        status: String,
        itemCount: Int,
        reason: String,
        actor: String,
        createdAt: Long = System.currentTimeMillis(),
    ): String {
        ensureCore(db)
        val cleanStatus = status.trim().ifBlank { "UNKNOWN" }.take(80)
        val eventId = "menu-apply-result-$revisionId-$createdAt-$cleanStatus"
        val payload = org.json.JSONObject()
            .put("revisionId", revisionId)
            .put("status", cleanStatus)
            .put("itemCount", itemCount)
            .put("reason", reason.take(1000))
            .put("actor", actor.take(100))
            .toString()
        val folder = db.rawQuery(
            "SELECT setting_value FROM sync_runtime_settings WHERE setting_key='folder_name' LIMIT 1",
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else "つぐレジ" }
        insertJournalAndOutbox(
            db = db,
            eventId = eventId,
            businessDate = BusinessDateResolver.current(db).toString(),
            eventType = JournalEventType.MENU_APPLY_RESULT.name,
            aggregateId = revisionId.toString(),
            payloadJson = payload,
            createdAt = createdAt,
            folderName = folder,
        )
        OutboxDocumentV150.materialize(db, eventId)
        return eventId
    }

    fun updateFolderName(db: SQLiteDatabase, folderName: String) {''',
)
replace_once(
    business,
    '''        val now = System.currentTimeMillis()
        recoverStaleProcessing(now)''',
    '''        val now = System.currentTimeMillis()
        // Pre-v1.50 rows are converted once as an explicit migration. New rows must already have
        // immutable bytes from their business-finalization transaction.
        OutboxDocumentV150.backfillLegacyMissing(db)
        recoverStaleProcessing(now)''',
)
replace_once(
    business,
    '''                val payload = OutboxPayloadAssembler.build(db, record)
                val target = File(folder, record.objectKey)
                target.parentFile?.mkdirs()
                target.writeText(payload, Charsets.UTF_8)''',
    '''                val payloadBytes = OutboxDocumentV150.loadVerifiedBytes(db, record.eventId)
                val target = File(folder, record.objectKey)
                target.parentFile?.mkdirs()
                target.writeBytes(payloadBytes)''',
)

replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt',
    '            SaleTaxSnapshotStoreV136.enrichSaleJournal(this, saleId)\n            PrintDocumentSnapshotV136.persistSaleSnapshot(',
    '            SaleTaxSnapshotStoreV136.enrichSaleJournal(this, saleId)\n            OutboxDocumentV150.materializeLatest(this, JournalEventType.SALE.name, saleId.toString())\n            PrintDocumentSnapshotV136.persistSaleSnapshot(',
)

replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/OperationsStore.kt',
    '''            insertAudit(
                eventType = type.name,''',
    '''            OutboxDocumentV150.materializeLatest(this, JournalEventType.SETTLEMENT.name, id.toString())
            insertAudit(
                eventType = type.name,''',
)
replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/AdvancedOperationsStore.kt',
    '            insertAudit(type.name, id, "営業日 ${summary.businessDate} / セッションNo.${session.id} / 純売上 ${summary.netSales}円 / 現金差異 ${variance}円", operatorName, now)',
    '            OutboxDocumentV150.materializeLatest(this, JournalEventType.SETTLEMENT.name, id.toString())\n            insertAudit(type.name, id, "営業日 ${summary.businessDate} / セッションNo.${session.id} / 純売上 ${summary.netSales}円 / 現金差異 ${variance}円", operatorName, now)',
)

catalog = 'app/src/main/java/jp/co/tenposinfo/register/DynamicCatalogRuntime.kt'
replace_once(
    catalog,
    '''            return db.transaction {
                val revisionId = insertOrThrow(''',
    '''            return db.transaction {
                val revisionCreatedAt = System.currentTimeMillis()
                val revisionId = insertOrThrow(''',
)
replace_once(catalog, '                        put("created_at", System.currentTimeMillis())', '                        put("created_at", revisionCreatedAt)')
replace_once(
    catalog,
    '                audit(this, "MENU_REVISION_SCHEDULED", revisionId.toString(), "$cleanName / $cleanDate / ${metadata.size}商品", actor)\n                revisionId',
    '                audit(this, "MENU_REVISION_SCHEDULED", revisionId.toString(), "$cleanName / $cleanDate / ${metadata.size}商品", actor)\n                OutboxDocumentV150.materializeLatest(this, JournalEventType.MENU_REVISION.name, revisionId.toString())\n                revisionId',
)
replace_once(
    catalog,
    '                audit(this, "MENU_REVISION_APPLY_FAILED", revision.id.toString(), "validation / $message", actor)\n            }',
    '''                audit(this, "MENU_REVISION_APPLY_FAILED", revision.id.toString(), "validation / $message", actor)
                JournalOutboxSchema.recordMenuApplyResult(
                    db = this,
                    revisionId = revision.id,
                    status = "FAILED_VALIDATION",
                    itemCount = items.size,
                    reason = message,
                    actor = actor,
                )
            }''',
)
replace_once(
    catalog,
    '''            audit(
                db,
                "MENU_REVISION_APPLIED",
                revision.id.toString(),
                "snapshotRows=${capturedSnapshot.rows.size} / masterRevision=${capturedSnapshot.catalogRevision} / applied=${items.size}",
                actor,
            )
            db.setTransactionSuccessful()''',
    '''            audit(
                db,
                "MENU_REVISION_APPLIED",
                revision.id.toString(),
                "snapshotRows=${capturedSnapshot.rows.size} / masterRevision=${capturedSnapshot.catalogRevision} / applied=${items.size}",
                actor,
            )
            JournalOutboxSchema.recordMenuApplyResult(
                db = db,
                revisionId = revision.id,
                status = "APPLIED",
                itemCount = items.size,
                reason = "適用完了",
                actor = actor,
            )
            db.setTransactionSuccessful()''',
)
replace_once(
    catalog,
    '''                    audit(
                        db,
                        "MENU_REVISION_APPLY_ROLLED_BACK",
                        revision.id.toString(),
                        "snapshotRows=${captured?.rows?.size ?: 0} / ${original.message ?: original.javaClass.simpleName}",
                        actor,
                    )
                    db.setTransactionSuccessful()''',
    '''                    audit(
                        db,
                        "MENU_REVISION_APPLY_ROLLED_BACK",
                        revision.id.toString(),
                        "snapshotRows=${captured?.rows?.size ?: 0} / ${original.message ?: original.javaClass.simpleName}",
                        actor,
                    )
                    JournalOutboxSchema.recordMenuApplyResult(
                        db = db,
                        revisionId = revision.id,
                        status = "ROLLED_BACK",
                        itemCount = items.size,
                        reason = original.message ?: original.javaClass.simpleName,
                        actor = actor,
                    )
                    db.setTransactionSuccessful()''',
)

# Restore: convert any restored pre-v1.50 rows while the restored DB is still the authoritative snapshot.
replace_once(
    'app/src/main/java/jp/co/tenposinfo/register/RestoreSyncRebuildV136.kt',
    '''        val remainingMissing = missingCount(db)
        require(remainingMissing == 0) { "BKP-006同期キュー再構築後も不足行があります: $remainingMissing" }''',
    '''        val remainingMissing = missingCount(db)
        require(remainingMissing == 0) { "BKP-006同期キュー再構築後も不足行があります: $remainingMissing" }
        OutboxDocumentV150.backfillLegacyMissing(db, limit = 5_000)''',
)

# ---------------------------------------------------------------------------
# PLUS: immutable ACK outbox, created inside import transaction and uploaded as stored bytes
# ---------------------------------------------------------------------------
plus_ack = r'''package jp.co.tenposinfo.register.plus

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest
import java.util.UUID

enum class ImportAckResultV150 { IMPORTED, DUPLICATE, REJECTED }

data class ImportAckV150(
    val eventId: String,
    val duplicateImportKey: String,
    val result: ImportAckResultV150,
    val message: String,
)

object PlusAckOutboxV150 {
    const val TABLE = "outbox_document"
    private const val META = "sync_outbox_meta"
    private const val ROLE = "sales-journal-ack"
    private const val APP = "tsuguregi"

    fun ensureSchema(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS $META(setting_key TEXT PRIMARY KEY, setting_value TEXT NOT NULL)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE (
                document_id TEXT PRIMARY KEY NOT NULL,
                document_type TEXT NOT NULL,
                source_business_id TEXT NOT NULL,
                schema_version INTEGER NOT NULL,
                canonical_payload_bytes BLOB NOT NULL,
                sha256 TEXT NOT NULL CHECK(length(sha256)=64),
                producer_id TEXT NOT NULL,
                sequence_no INTEGER NOT NULL,
                completion_mode TEXT NOT NULL,
                status TEXT NOT NULL,
                remote_file_id TEXT,
                attempt_count INTEGER NOT NULL DEFAULT 0,
                next_attempt_at INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_plus_v150_outbox_sequence ON $TABLE(producer_id,sequence_no)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_plus_v150_outbox_status ON $TABLE(status,next_attempt_at,created_at)")
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS trg_plus_v150_outbox_immutable
            BEFORE UPDATE OF document_id,document_type,source_business_id,schema_version,canonical_payload_bytes,
                             sha256,producer_id,sequence_no,completion_mode,created_at
            ON $TABLE
            BEGIN
                SELECT RAISE(ABORT, 'SYN_ACK_OUTBOX_DOCUMENT_IMMUTABLE');
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS trg_plus_v150_outbox_no_delete
            BEFORE DELETE ON $TABLE
            BEGIN
                SELECT RAISE(ABORT, 'SYN_ACK_OUTBOX_DOCUMENT_DELETE_FORBIDDEN');
            END
            """.trimIndent(),
        )
    }

    fun materialize(
        db: SQLiteDatabase,
        runId: Long,
        completedAt: Long,
        acknowledgements: List<ImportAckV150>,
    ) {
        if (acknowledgements.isEmpty()) return
        ensureSchema(db)
        val producerId = producerId(db)
        acknowledgements.forEachIndexed { index, ack ->
            val sequenceNo = Math.addExact(Math.multiplyExact(runId, 100_000L), (index + 1).toLong())
            val documentId = UUID.nameUUIDFromBytes(
                "TSUGUREGI-PLUS:IMPORT-ACK:$producerId:$runId:${index + 1}:${ack.eventId}:${ack.result.name}"
                    .toByteArray(Charsets.UTF_8),
            ).toString()
            val payload = buildString {
                append('{')
                append("\"schema\":\"jp.co.tenposinfo.tsuguregi.import-ack\",")
                append("\"schemaVersion\":1,")
                append("\"documentType\":\"IMPORT_ACK\",")
                append("\"documentId\":\"").append(escape(documentId)).append("\",")
                append("\"sourceBusinessId\":\"").append(escape(ack.eventId)).append("\",")
                append("\"sourceEventId\":\"").append(escape(ack.eventId)).append("\",")
                append("\"duplicateImportKey\":\"").append(escape(ack.duplicateImportKey)).append("\",")
                append("\"producerId\":\"").append(escape(producerId)).append("\",")
                append("\"sequenceNo\":").append(sequenceNo).append(',')
                append("\"completionMode\":\"UPLOAD_CONFIRMED\",")
                append("\"result\":\"").append(ack.result.name).append("\",")
                append("\"message\":\"").append(escape(ack.message.take(1000))).append("\",")
                append("\"createdAt\":").append(completedAt)
                append('}')
            }.toByteArray(Charsets.UTF_8)
            val hash = sha256(payload)
            db.insertOrThrow(
                TABLE,
                null,
                ContentValues().apply {
                    put("document_id", documentId)
                    put("document_type", "IMPORT_ACK")
                    put("source_business_id", ack.eventId)
                    put("schema_version", 1)
                    put("canonical_payload_bytes", payload)
                    put("sha256", hash)
                    put("producer_id", producerId)
                    put("sequence_no", sequenceNo)
                    put("completion_mode", "UPLOAD_CONFIRMED")
                    put("status", "PENDING")
                    put("created_at", completedAt)
                },
            )
        }
    }

    /** Uploads only the exact BLOB committed by the import transaction; no business reserialization. */
    fun deliverPending(db: SQLiteDatabase, client: GoogleDriveSyncRestClient, limit: Int = 200): Int {
        ensureSchema(db)
        val now = System.currentTimeMillis()
        val rows = db.rawQuery(
            """
            SELECT document_id,canonical_payload_bytes,sha256,attempt_count
            FROM $TABLE
            WHERE document_type='IMPORT_ACK' AND status IN ('PENDING','RETRY') AND next_attempt_at<=?
            ORDER BY created_at ASC, sequence_no ASC
            LIMIT ?
            """.trimIndent(),
            arrayOf(now.toString(), limit.coerceIn(1, 1_000).toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    AckRow(cursor.getString(0), cursor.getBlob(1), cursor.getString(2), cursor.getInt(3)),
                )
            }
        }
        var delivered = 0
        rows.forEach { row ->
            require(sha256(row.bytes) == row.sha256) { "ACK immutable bytes SHA-256 mismatch: ${row.documentId}" }
            try {
                val query = "mimeType='application/json' and trashed=false" +
                    prop("app", APP) + prop("role", ROLE) + prop("ackDocumentId", row.documentId)
                val existing = client.findOne(query)
                if (existing != null) {
                    require(existing.appProperties["contentSha256"] == row.sha256) {
                        "同じACK documentIdのDrive内容が一致しません"
                    }
                    markCompleted(db, row.documentId, existing.id)
                    delivered += 1
                } else {
                    val remote = client.createJson(
                        name = "ack-${row.documentId}.json",
                        bytes = row.bytes,
                        appProperties = mapOf(
                            "app" to APP,
                            "role" to ROLE,
                            "ackDocumentId" to row.documentId,
                            "contentSha256" to row.sha256,
                        ),
                    )
                    markCompleted(db, row.documentId, remote.id)
                    delivered += 1
                }
            } catch (error: Throwable) {
                val category = GoogleDriveSyncErrorPolicy.classify(error)
                if (!category.retryable) throw error
                val attempts = row.attemptCount + 1
                db.update(
                    TABLE,
                    ContentValues().apply {
                        put("status", "RETRY")
                        put("attempt_count", attempts)
                        put("next_attempt_at", now + retryDelay(attempts))
                        put("last_error", (error.message ?: error.javaClass.simpleName).take(500))
                    },
                    "document_id=?",
                    arrayOf(row.documentId),
                )
                throw error
            }
        }
        return delivered
    }

    private fun producerId(db: SQLiteDatabase): String {
        db.rawQuery("SELECT setting_value FROM $META WHERE setting_key='producer_id'", null).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        val generated = UUID.randomUUID().toString()
        db.insertOrThrow(
            META,
            null,
            ContentValues().apply { put("setting_key", "producer_id"); put("setting_value", generated) },
        )
        return generated
    }

    private fun markCompleted(db: SQLiteDatabase, documentId: String, remoteId: String) {
        db.update(
            TABLE,
            ContentValues().apply {
                put("status", "COMPLETED")
                put("remote_file_id", remoteId)
                put("next_attempt_at", 0)
                putNull("last_error")
            },
            "document_id=?",
            arrayOf(documentId),
        )
    }

    private fun prop(key: String, value: String): String =
        " and appProperties has { key='${GoogleDriveSyncRestClient.quoted(key)}' and value='${GoogleDriveSyncRestClient.quoted(value)}' }"

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun retryDelay(attempt: Int): Long = when (attempt) {
        1 -> 60_000L
        2 -> 5 * 60_000L
        3 -> 15 * 60_000L
        else -> 60 * 60_000L
    }

    private fun escape(value: String): String = buildString {
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }

    private data class AckRow(
        val documentId: String,
        val bytes: ByteArray,
        val sha256: String,
        val attemptCount: Int,
    )
}
'''
(root / 'management-app/src/main/java/jp/co/tenposinfo/register/plus/PlusAckOutboxV150.kt').write_text(plus_ack)

repo = 'management-app/src/main/java/jp/co/tenposinfo/register/plus/SalesJournalImportRepository.kt'
replace_once(
    repo,
    '''        var imported = 0
        var duplicate = 0
        var rejected = 0

        db.beginTransaction()''',
    '''        var imported = 0
        var duplicate = 0
        var rejected = 0
        val acknowledgements = mutableListOf<ImportAckV150>()

        db.beginTransaction()''',
)
replace_once(
    repo,
    '''                                if (SalesJournalImportPolicy.isDuplicateInsertResult(rowId)) {
                                    duplicate += 1
                                } else {
                                    imported += 1
                                }''',
    '''                                if (SalesJournalImportPolicy.isDuplicateInsertResult(rowId)) {
                                    duplicate += 1
                                    acknowledgements += ImportAckV150(
                                        parsed.envelope.eventId,
                                        parsed.envelope.duplicateImportKey,
                                        ImportAckResultV150.DUPLICATE,
                                        "既存取込済み",
                                    )
                                } else {
                                    imported += 1
                                    acknowledgements += ImportAckV150(
                                        parsed.envelope.eventId,
                                        parsed.envelope.duplicateImportKey,
                                        ImportAckResultV150.IMPORTED,
                                        "取込完了",
                                    )
                                }''',
)
replace_once(
    repo,
    '''                            SalesJournalReplayDecisionV118.IDENTICAL -> {
                                duplicate += 1
                            }''',
    '''                            SalesJournalReplayDecisionV118.IDENTICAL -> {
                                duplicate += 1
                                acknowledgements += ImportAckV150(
                                    parsed.envelope.eventId,
                                    parsed.envelope.duplicateImportKey,
                                    ImportAckResultV150.DUPLICATE,
                                    "同一document取込済み",
                                )
                            }''',
)
replace_once(
    repo,
    '''                                rejected += 1
                            }
                        }
                    }
                }
            }

            val completedAt = nowMillis()''',
    '''                                rejected += 1
                                acknowledgements += ImportAckV150(
                                    parsed.envelope.eventId,
                                    parsed.envelope.duplicateImportKey,
                                    ImportAckResultV150.REJECTED,
                                    "duplicateImportKey内容不一致",
                                )
                            }
                        }
                    }
                }
            }

            val completedAt = nowMillis()''',
)
replace_once(
    repo,
    '''            beforeCommit(db)
            db.setTransactionSuccessful()''',
    '''            // SYN-001: ACK bytes are frozen before this import transaction commits.
            PlusAckOutboxV150.materialize(db, runId, completedAt, acknowledgements)
            beforeCommit(db)
            db.setTransactionSuccessful()''',
)

mdb = 'management-app/src/main/java/jp/co/tenposinfo/register/plus/ManagementDatabase.kt'
replace_once(mdb, '        createImportedJournalReplayGuardV117(db)\n        createIndexes(db)', '        createImportedJournalReplayGuardV117(db)\n        PlusAckOutboxV150.ensureSchema(db)\n        createIndexes(db)')
replace_once(
    mdb,
    '''        if (oldVersion < 6) {
            ensureDriveRemoteVersionV119(db)
        }
        require(newVersion <= DATABASE_VERSION)''',
    '''        if (oldVersion < 6) {
            ensureDriveRemoteVersionV119(db)
        }
        if (oldVersion < 7) {
            PlusAckOutboxV150.ensureSchema(db)
        }
        require(newVersion <= DATABASE_VERSION)''',
)
replace_once(
    mdb,
    '''        super.onOpen(db)
        SalesJournalImportCompatibilityResetV124.ensureCurrent(appContext, db)''',
    '''        super.onOpen(db)
        PlusAckOutboxV150.ensureSchema(db)
        SalesJournalImportCompatibilityResetV124.ensureCurrent(appContext, db)''',
)
replace_once(mdb, '        const val DATABASE_VERSION = 6', '        const val DATABASE_VERSION = 7')

contract = 'management-app/src/main/java/jp/co/tenposinfo/register/plus/SalesJournalImportContract.kt'
replace_once(contract, '        "MENU_REVISION",\n    )', '        "MENU_REVISION",\n        "MENU_APPLY_RESULT",\n    )')

drive = 'management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveDirectSync.kt'
replace_once(drive, 'import java.io.IOException\n', 'import java.io.ByteArrayOutputStream\nimport java.io.IOException\n')
replace_once(
    drive,
    '''    fun download(fileId: String): ByteArray = executeBytes(
        method = "GET",
        url = "$DRIVE_FILES_URL/${encodePath(fileId)}?alt=media&supportsAllDrives=false",
    )

    private fun execute(method: String, url: String): String =''',
    '''    fun download(fileId: String): ByteArray = executeBytes(
        method = "GET",
        url = "$DRIVE_FILES_URL/${encodePath(fileId)}?alt=media&supportsAllDrives=false",
    )

    fun findOne(query: String): GoogleDriveSyncRemoteFile? {
        val fields = "files(id,name,modifiedTime,version,size,appProperties)"
        val url = buildString {
            append(DRIVE_FILES_URL)
            append("?spaces=drive&supportsAllDrives=false&pageSize=10")
            append("&fields=").append(encode(fields))
            append("&q=").append(encode(query))
        }
        val files = JSONObject(execute("GET", url)).optJSONArray("files") ?: JSONArray()
        if (files.length() == 0) return null
        val item = files.getJSONObject(0)
        val properties = linkedMapOf<String, String>()
        item.optJSONObject("appProperties")?.let { source ->
            source.keys().forEach { key -> properties[key] = source.optString(key) }
        }
        return GoogleDriveSyncRemoteFile(
            id = item.getString("id"),
            name = item.optString("name"),
            modifiedTime = item.optString("modifiedTime"),
            version = item.optString("version").takeIf(String::isNotBlank),
            size = item.optString("size").toLongOrNull(),
            appProperties = properties,
        )
    }

    fun createJson(
        name: String,
        bytes: ByteArray,
        appProperties: Map<String, String>,
    ): GoogleDriveSyncRemoteFile {
        val metadata = JSONObject()
            .put("name", name)
            .put("mimeType", "application/json")
            .put("parents", JSONArray().put("root"))
            .put("appProperties", JSONObject().also { target -> appProperties.forEach { (k, v) -> target.put(k, v) } })
        val boundary = "tsuguregi-plus-${System.nanoTime()}"
        val out = ByteArrayOutputStream()
        out.write("--$boundary\\r\\nContent-Type: application/json; charset=UTF-8\\r\\n\\r\\n".toByteArray())
        out.write(metadata.toString().toByteArray(Charsets.UTF_8))
        out.write("\\r\\n--$boundary\\r\\nContent-Type: application/json\\r\\n\\r\\n".toByteArray())
        out.write(bytes)
        out.write("\\r\\n--$boundary--\\r\\n".toByteArray())
        val root = JSONObject(
            executeBytes(
                method = "POST",
                url = "$DRIVE_UPLOAD_URL?uploadType=multipart&supportsAllDrives=false&fields=id,name,modifiedTime,version,size,appProperties",
                requestBody = out.toByteArray(),
                contentType = "multipart/related; boundary=$boundary",
            ).toString(Charsets.UTF_8),
        )
        val properties = linkedMapOf<String, String>()
        root.optJSONObject("appProperties")?.let { source -> source.keys().forEach { key -> properties[key] = source.optString(key) } }
        return GoogleDriveSyncRemoteFile(
            id = root.getString("id"), name = root.optString("name"), modifiedTime = root.optString("modifiedTime"),
            version = root.optString("version").takeIf(String::isNotBlank), size = root.optString("size").toLongOrNull(),
            appProperties = properties,
        )
    }

    private fun execute(method: String, url: String): String =''',
)
replace_once(
    drive,
    '''    private fun executeBytes(method: String, url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 20_000
            connection.readTimeout = 45_000
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode''',
    '''    private fun executeBytes(
        method: String,
        url: String,
        requestBody: ByteArray? = null,
        contentType: String? = null,
    ): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 20_000
            connection.readTimeout = 45_000
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")
            if (requestBody != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType ?: "application/json")
                connection.setFixedLengthStreamingMode(requestBody.size)
                connection.outputStream.use { it.write(requestBody) }
            }
            val code = connection.responseCode''',
)
replace_once(
    drive,
    '        const val DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files"\n        const val APP = "tsuguregi"',
    '        const val DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files"\n        const val DRIVE_UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"\n        const val APP = "tsuguregi"',
)
replace_once(
    drive,
    '''                val client = GoogleDriveSyncRestClient(accessToken)
                val visitedPageTokens = mutableSetOf<String>()''',
    '''                val client = GoogleDriveSyncRestClient(accessToken)
                // Retry any ACK committed by a previous run before reading more journal files.
                PlusAckOutboxV150.deliverPending(initialDb, client)
                val visitedPageTokens = mutableSetOf<String>()''',
)
replace_once(
    drive,
    '''                    } finally {
                        pageDb.endTransaction()
                    }

                    val pageResult = checkNotNull(committedPageResult)''',
    '''                    } finally {
                        pageDb.endTransaction()
                    }
                    // SYN-002: upload the exact ACK BLOB committed above; never regenerate from imported_journal.
                    PlusAckOutboxV150.deliverPending(pageDb, client)

                    val pageResult = checkNotNull(committedPageResult)''',
)

# ---------------------------------------------------------------------------
# Focused source/contract tests
# ---------------------------------------------------------------------------
app_test = r'''package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class V150Syn001002ImmutableOutboxDocumentTest {
    private val appRoot = File(System.getProperty("user.dir")).let { if (File(it, "app").isDirectory) File(it, "app") else it }
    private fun source(name: String) = File(appRoot, "src/main/java/jp/co/tenposinfo/register/$name").readText()

    @Test fun formalFieldsAndImmutabilityArePersisted() {
        val text = source("OutboxDocumentV150.kt")
        listOf(
            "document_id", "document_type", "source_business_id", "schema_version",
            "canonical_payload_bytes BLOB", "sha256", "producer_id", "sequence_no",
            "completion_mode", "status", "SYN_OUTBOX_DOCUMENT_IMMUTABLE", "SYN_OUTBOX_DOCUMENT_DELETE_FORBIDDEN",
        ).forEach { assertTrue("missing $it", text.contains(it)) }
        assertTrue(text.contains("MessageDigest.getInstance(\"SHA-256\")"))
        assertTrue(text.contains("OutboxPayloadAssembler.build(db, record)"))
        assertTrue(text.contains("legacyMaterialized = true"))
    }

    @Test fun workerStagesOnlyStoredVerifiedBytes() {
        val text = source("BusinessSyncFoundation.kt")
        val start = text.indexOf("fun stagePending")
        val end = text.indexOf("fun recoverStaleProcessing", start)
        val stage = text.substring(start, end)
        assertTrue(stage.contains("OutboxDocumentV150.loadVerifiedBytes"))
        assertTrue(!stage.contains("OutboxPayloadAssembler.build"))
        assertTrue(stage.contains("writeBytes(payloadBytes)"))
    }

    @Test fun saleAndSettlementMaterializeInsideFinalizationTransactions() {
        val register = source("RegisterDatabase.kt")
        assertTrue(register.indexOf("SaleTaxSnapshotStoreV136.enrichSaleJournal") < register.indexOf("OutboxDocumentV150.materializeLatest(this, JournalEventType.SALE.name"))
        assertTrue(source("OperationsStore.kt").contains("OutboxDocumentV150.materializeLatest(this, JournalEventType.SETTLEMENT.name, id.toString())"))
        assertTrue(source("AdvancedOperationsStore.kt").contains("OutboxDocumentV150.materializeLatest(this, JournalEventType.SETTLEMENT.name, id.toString())"))
    }

    @Test fun menuApplicationResultIsMaterializedForAllCommittedOutcomes() {
        val catalog = source("DynamicCatalogRuntime.kt")
        val foundation = source("BusinessSyncFoundation.kt")
        assertTrue(foundation.contains("MENU_APPLY_RESULT"))
        assertTrue(catalog.contains("status = \"FAILED_VALIDATION\""))
        assertTrue(catalog.contains("status = \"APPLIED\""))
        assertTrue(catalog.contains("status = \"ROLLED_BACK\""))
        assertTrue(foundation.contains("OutboxDocumentV150.materialize(db, eventId)"))
    }
}
'''
(root / 'app/src/test/java/jp/co/tenposinfo/register/V150Syn001002ImmutableOutboxDocumentTest.kt').write_text(app_test)

plus_test = r'''package jp.co.tenposinfo.register.plus

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class V150Syn001002AckOutboxTest {
    private val module = File(System.getProperty("user.dir")).let { if (File(it, "management-app").isDirectory) File(it, "management-app") else it }
    private fun source(name: String) = File(module, "src/main/java/jp/co/tenposinfo/register/plus/$name").readText()

    @Test fun ackHasFormalImmutableOutboxFields() {
        val text = source("PlusAckOutboxV150.kt")
        listOf(
            "document_id", "document_type", "source_business_id", "schema_version", "canonical_payload_bytes BLOB",
            "sha256", "producer_id", "sequence_no", "completion_mode", "status", "SYN_ACK_OUTBOX_DOCUMENT_IMMUTABLE",
        ).forEach { assertTrue("missing $it", text.contains(it)) }
        assertTrue(text.contains("ImportAckResultV150.IMPORTED") || source("SalesJournalImportRepository.kt").contains("ImportAckResultV150.IMPORTED"))
    }

    @Test fun ackMaterializesBeforeImportCommit() {
        val text = source("SalesJournalImportRepository.kt")
        val materialize = text.indexOf("PlusAckOutboxV150.materialize")
        val hook = text.indexOf("beforeCommit(db)", materialize)
        val successful = text.indexOf("db.setTransactionSuccessful()", materialize)
        assertTrue(materialize > 0 && hook > materialize && successful > hook)
        assertTrue(text.contains("ImportAckResultV150.DUPLICATE"))
        assertTrue(text.contains("ImportAckResultV150.REJECTED"))
    }

    @Test fun driveDeliveryUsesStoredBlobAndHashOnly() {
        val ack = source("PlusAckOutboxV150.kt")
        assertTrue(ack.contains("SELECT document_id,canonical_payload_bytes,sha256,attempt_count"))
        assertTrue(ack.contains("client.createJson"))
        assertTrue(ack.contains("bytes = row.bytes"))
        assertTrue(!ack.contains("FROM imported_journal"))
        val drive = source("GoogleDriveDirectSync.kt")
        assertTrue(drive.contains("PlusAckOutboxV150.deliverPending"))
        assertTrue(drive.contains("DRIVE_UPLOAD_URL"))
    }

    @Test fun menuApplyResultIsAcceptedWithoutBlockingDriveImport() {
        assertTrue(source("SalesJournalImportContract.kt").contains("\"MENU_APPLY_RESULT\""))
    }
}
'''
(root / 'management-app/src/test/java/jp/co/tenposinfo/register/plus/V150Syn001002AckOutboxTest.kt').write_text(plus_test)

(root / 'docs/V1.36_SYN_001_002_IMMUTABLE_OUTBOX_DOCUMENT.md').write_text(r'''# v1.36 SYN-001 / SYN-002 不変 outbox_document

正式仕様 v2.5 の SYN-001 / SYN-002 を正本とする。

## 実装

- `outbox_document` に documentId / documentType / sourceBusinessId / schemaVersion / canonicalPayloadBytes / sha256 / producerId / sequenceNo / completionMode / status を永続化する。
- SALE_EVENT は税snapshotを含む売上ジャーナルを確定した直後、売上DB transactionをcommitする前に一度だけJSON bytesを生成する。
- SETTLEMENT は精算レコード・snapshotを確定する同じtransaction内でbytesを生成する。通常/高度運用の両保存経路を同じ契約にした。
- MENU_APPLY_RESULT は検証失敗 / 適用成功 / snapshot rollback成功の各「確定結果」を同じtransaction内でjournal + outbox_documentとして作る。
- つぐレジ＋は IMPORTED / DUPLICATE / REJECTED のACKを取込transaction内で不変bytes化する。ACKのproducerIdは管理DB内で一度生成したUUIDを保持する。
- Worker/Drive配送は `canonical_payload_bytes` と保存済みSHA-256のみを使用し、業務テーブル・現在マスター・新serializerから再生成しない。
- `outbox_document` のbytes/hash/識別子/sequence/completionMode等はSQLite triggerでUPDATE/DELETE禁止。status等の配送状態だけを後続仕様で更新できる。
- v1.50以前の既存 `sync_outbox` は互換移行として一度だけ `legacy_materialized=1` で凍結する。以後の再試行では同じbytesを使う。
- BKP-006復元後再構築でも旧行を復元DBから一度だけ凍結し、その後は再シリアライズしない。

## ACK配送

つぐレジ＋のDrive取込で業務transactionがcommitした後、同transactionで作成済みのACK BLOBをDrive APIへ送信する。`ackDocumentId + contentSha256` で重複確認し、応答消失後の再試行でも二重ACKを作らない。ACK JSONは `role=sales-journal-ack` のappPropertiesを持つため、売上ジャーナルの読取queryには混入しない。

## 後続

SYN-004の正式状態機械（HELD_UNTIL_SETTLEMENT / PENDING / UPLOADING / UPLOADED / ACKED / COMPLETED / RETRY / AUTH_REQUIRED / PERMISSION_DENIED / FAILED_PERMANENT / DISCARDED）は次項で旧 `sync_outbox` 状態との互換移行を行う。本項ではbytes不変性と原子生成を先に固定し、既存配送状態を破壊しない。

## 実機未確認

- 実Google DriveでSALE/SETTLEMENTのupload後、つぐレジ＋取込→ACK upload→REGISTER側ACK確認までの往復。
- 回線断・HTTP応答消失を挟んだ再試行時にDrive上のdocument/ACKが二重化しないこと。
- 実端末でZ精算中・メニュー適用中にプロセスkillした場合、業務確定とoutbox_documentが片方だけ残らないこと。
''')

print('SYN-001/002 patch applied')
