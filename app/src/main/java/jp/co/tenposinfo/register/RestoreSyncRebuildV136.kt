package jp.co.tenposinfo.register

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** Formal v2.5 BKP-006 / BKP-018 restore-time synchronization reconstruction. */
data class RestoreSyncRebuildResultV136(
    val journalCount: Int,
    val existingOutboxCount: Int,
    val rebuiltCount: Int,
    val remainingMissingCount: Int,
    val preservedSentCount: Int,
    val preservedAckCount: Int,
    val preservedDocumentIdCount: Int,
)

/**
 * Keeps the identity that originally owned an outbox event.
 * A spare-terminal restore changes the runtime identity after reconstruction; old events must
 * still produce the old duplicateImportKey so Drive idempotency can find the existing document.
 */
object OutboxIdentitySnapshotV136 {
    fun choose(
        sourceStoreId: String?,
        sourceTerminalId: String?,
        sourceGeneration: Long?,
        fallback: SalesJournalIdentity,
    ): SalesJournalIdentity {
        val store = sourceStoreId?.trim().orEmpty()
        val terminal = sourceTerminalId?.trim().orEmpty()
        val generation = sourceGeneration ?: 0L
        return if (store.isNotBlank() && terminal.isNotBlank() && generation >= 1L) {
            SalesJournalIdentity(store, terminal, generation)
        } else {
            fallback
        }
    }

    fun resolve(db: SQLiteDatabase, eventId: String): SalesJournalIdentity {
        val fallback = SalesJournalIdentityStore.resolve(db)
        if (!hasColumn(db, "sync_outbox", "source_store_id") ||
            !hasColumn(db, "sync_outbox", "source_terminal_id") ||
            !hasColumn(db, "sync_outbox", "source_generation")) return fallback
        return db.rawQuery(
            "SELECT source_store_id, source_terminal_id, source_generation FROM sync_outbox WHERE event_id=? LIMIT 1",
            arrayOf(eventId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use fallback
            choose(
                sourceStoreId = if (cursor.isNull(0)) null else cursor.getString(0),
                sourceTerminalId = if (cursor.isNull(1)) null else cursor.getString(1),
                sourceGeneration = if (cursor.isNull(2)) null else cursor.getLong(2),
                fallback = fallback,
            )
        }
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            var found = false
            while (cursor.moveToNext()) {
                if (nameIndex >= 0 && cursor.getString(nameIndex) == column) {
                    found = true
                    break
                }
            }
            found
        }
}

object RestoreSyncRebuildV136 {
    private data class MissingJournal(
        val eventId: String,
        val businessDate: String,
        val eventType: String,
        val aggregateId: String,
        val createdAt: Long,
    )

    /**
     * Rebuilds only missing outbox rows from the restored local journal.
     * Existing outbox rows, SENT state, Drive ACK and documentId rows are never reset or deleted.
     * There is deliberately no Drive download/import path in this routine.
     */
    fun rebuild(databaseFile: File): RestoreSyncRebuildResultV136 {
        val db = SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        return try {
            rebuild(db)
        } finally {
            db.close()
        }
    }

    fun rebuild(db: SQLiteDatabase): RestoreSyncRebuildResultV136 {
        JournalOutboxSchema.ensureCore(db)
        val sourceIdentity = SalesJournalIdentityStore.resolve(db)
        val folderName = readFolderName(db)
        val journalCount = scalarInt(db, "SELECT COUNT(*) FROM sales_journal")
        val existingOutboxCount = scalarInt(db, "SELECT COUNT(*) FROM sync_outbox")
        val sentBefore = scalarInt(db, "SELECT COUNT(*) FROM sync_outbox WHERE status='SENT'")
        val ackBefore = driveSuccessCount(db, requireDocumentId = false)
        val documentIdBefore = driveSuccessCount(db, requireDocumentId = true)
        val missing = missingJournalRows(db)

        db.beginTransaction()
        var rebuilt = 0
        try {
            missing.forEach { row ->
                val inserted = db.insertWithOnConflict(
                    "sync_outbox",
                    null,
                    ContentValues().apply {
                        put("event_id", row.eventId)
                        put("destination", "GOOGLE_DRIVE")
                        put("object_key", reconstructedObjectKey(folderName, row))
                        put("status", SyncOutboxStatus.PENDING.name)
                        put("attempt_count", 0)
                        put("next_attempt_at", 0)
                        putNull("last_error")
                        putNull("processing_started_at")
                        putNull("lease_until")
                        putNull("worker_token")
                        put("source_store_id", sourceIdentity.storeId)
                        put("source_terminal_id", sourceIdentity.terminalId)
                        put("source_generation", sourceIdentity.generation)
                        put("created_at", row.createdAt)
                        put("updated_at", row.createdAt)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
                if (inserted != -1L) rebuilt++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        val remaining = missingCount(db)
        require(remaining == 0) { "BKP-006同期再構築後もoutbox不足が残っています: $remaining" }
        OutboxDocumentV150.backfillLegacyMissing(db, limit = 5_000)
        val sentAfter = scalarInt(db, "SELECT COUNT(*) FROM sync_outbox WHERE status='SENT'")
        val ackAfter = driveSuccessCount(db, requireDocumentId = false)
        val documentIdAfter = driveSuccessCount(db, requireDocumentId = true)
        require(sentAfter == sentBefore) { "BKP-006再構築が既存SENT状態を変更しました" }
        require(ackAfter == ackBefore && documentIdAfter == documentIdBefore) {
            "BKP-006再構築が既存Drive ACK/documentIdを変更しました"
        }
        return RestoreSyncRebuildResultV136(
            journalCount = journalCount,
            existingOutboxCount = existingOutboxCount,
            rebuiltCount = rebuilt,
            remainingMissingCount = remaining,
            preservedSentCount = sentAfter,
            preservedAckCount = ackAfter,
            preservedDocumentIdCount = documentIdAfter,
        )
    }

    private fun missingJournalRows(db: SQLiteDatabase): List<MissingJournal> = db.rawQuery(
        """
        SELECT j.event_id, j.business_date, j.event_type, j.aggregate_id, j.created_at
        FROM sales_journal j
        LEFT JOIN sync_outbox o ON o.event_id=j.event_id
        WHERE o.event_id IS NULL
        ORDER BY j.created_at ASC, j.event_id ASC
        """.trimIndent(),
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(MissingJournal(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getLong(4)))
            }
        }
    }

    private fun reconstructedObjectKey(folderName: String, row: MissingJournal): String {
        val file = when (row.eventType) {
            JournalEventType.CASH_MOVEMENT.name -> "cash-${OutboxObjectKey.sanitizeSegment(row.aggregateId)}.json"
            JournalEventType.BUSINESS_OPEN.name -> "business-open-${OutboxObjectKey.sanitizeSegment(row.aggregateId)}.json"
            JournalEventType.MENU_REVISION.name -> "menu-revision-${OutboxObjectKey.sanitizeSegment(row.aggregateId)}.json"
            JournalEventType.BUSINESS_STATE.name -> {
                val state = OutboxObjectKey.sanitizeSegment(row.eventId.substringAfterLast('-'))
                "business-state-${OutboxObjectKey.sanitizeSegment(row.aggregateId)}-$state.json"
            }
            else -> "${OutboxObjectKey.sanitizeSegment(row.eventType.lowercase())}-${OutboxObjectKey.sanitizeSegment(row.aggregateId)}.json"
        }
        return listOf(
            OutboxObjectKey.sanitizeSegment(folderName),
            OutboxObjectKey.sanitizeSegment(row.businessDate),
            file,
        ).joinToString("/")
    }

    private fun readFolderName(db: SQLiteDatabase): String = db.rawQuery(
        "SELECT setting_value FROM sync_runtime_settings WHERE setting_key='folder_name' LIMIT 1",
        null,
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0).takeIf(String::isNotBlank) ?: "つぐレジ" else "つぐレジ"
    }

    private fun missingCount(db: SQLiteDatabase): Int = scalarInt(
        db,
        "SELECT COUNT(*) FROM sales_journal j LEFT JOIN sync_outbox o ON o.event_id=j.event_id WHERE o.event_id IS NULL",
    )

    private fun driveSuccessCount(db: SQLiteDatabase, requireDocumentId: Boolean): Int {
        if (!tableExists(db, "drive_api_uploads")) return 0
        val documentClause = if (requireDocumentId) " AND file_id IS NOT NULL AND TRIM(file_id) <> ''" else ""
        return scalarInt(db, "SELECT COUNT(*) FROM drive_api_uploads WHERE status='SUCCEEDED'$documentClause")
    }

    private fun scalarInt(db: SQLiteDatabase, sql: String): Int =
        db.rawQuery(sql, null).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
        arrayOf(table),
    ).use { it.moveToFirst() }
}
