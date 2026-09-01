from pathlib import Path

ROOT = Path('.')


def replace_once(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one anchor, found {count}")
    p.write_text(text.replace(old, new, 1))

business = "app/src/main/java/jp/co/tenposinfo/register/BusinessSyncFoundation.kt"
replace_once(
    business,
    '''        SchemaMigration.ensureColumn(db, "sync_outbox", "worker_token", "TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_outbox_status ON sync_outbox(status, next_attempt_at, created_at)")
''',
    '''        SchemaMigration.ensureColumn(db, "sync_outbox", "worker_token", "TEXT")
        // BKP-006/BKP-018: outbox is immutable with respect to terminal migration.
        // Snapshot the identity that owned the event so a restored spare terminal can
        // regenerate the exact old duplicate key instead of re-labelling it as a new terminal event.
        SchemaMigration.ensureColumn(db, "sync_outbox", "source_store_id", "TEXT")
        SchemaMigration.ensureColumn(db, "sync_outbox", "source_terminal_id", "TEXT")
        SchemaMigration.ensureColumn(db, "sync_outbox", "source_generation", "INTEGER")
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS trg_v136_sync_outbox_identity_snapshot
            AFTER INSERT ON sync_outbox
            WHEN NEW.source_store_id IS NULL OR NEW.source_terminal_id IS NULL OR NEW.source_generation IS NULL
            BEGIN
                UPDATE sync_outbox
                SET source_store_id = COALESCE(
                        NEW.source_store_id,
                        (SELECT setting_value FROM sync_runtime_settings WHERE setting_key='sales_journal_store_id'),
                        'STORE-UNCONFIGURED'
                    ),
                    source_terminal_id = COALESCE(
                        NEW.source_terminal_id,
                        (SELECT setting_value FROM sync_runtime_settings WHERE setting_key='sales_journal_terminal_id')
                    ),
                    source_generation = COALESCE(
                        NEW.source_generation,
                        CAST((SELECT setting_value FROM sync_runtime_settings WHERE setting_key='sales_journal_terminal_generation') AS INTEGER),
                        1
                    )
                WHERE id = NEW.id;
            END
            """.trimIndent(),
        )
        val identitySnapshot = SalesJournalIdentityStore.resolve(db)
        db.execSQL(
            """
            UPDATE sync_outbox
            SET source_store_id = COALESCE(source_store_id, ?),
                source_terminal_id = COALESCE(source_terminal_id, ?),
                source_generation = COALESCE(source_generation, ?)
            WHERE source_store_id IS NULL OR source_terminal_id IS NULL OR source_generation IS NULL
            """.trimIndent(),
            arrayOf<Any>(identitySnapshot.storeId, identitySnapshot.terminalId, identitySnapshot.generation),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_outbox_status ON sync_outbox(status, next_attempt_at, created_at)")
''',
)

replace_once(
    business,
    '''            identity = SalesJournalIdentityStore.resolve(db),
''',
    '''            identity = OutboxIdentitySnapshotV136.resolve(db, record.eventId),
''',
)

bootstrap = "app/src/main/java/jp/co/tenposinfo/register/DataRestoreBootstrapV086.kt"
replace_once(
    bootstrap,
    '''            // BKP-005: identity/generationと採番floorをrollback境界内で確定する。
            RestoreTerminalMigrationV136.apply(database, plan)

            // migration後のschemaへ監査を書き込み、その書込み後にも正本DBを最終検証する。
            insertRestoreAudit(database, plan)
''',
    '''            // BKP-006/BKP-018: terminalId切替より先に不足outboxを復元DB自身から再構築する。
            // この順序により旧イベントのsource identityを固定し、Driveから売上を逆輸入しない。
            val syncRebuild = RestoreSyncRebuildV136.rebuild(database)

            // BKP-005: identity/generationと採番floorをrollback境界内で確定する。
            RestoreTerminalMigrationV136.apply(database, plan)

            // migration後のschemaへ監査を書き込み、その書込み後にも正本DBを最終検証する。
            insertRestoreAudit(database, plan, syncRebuild)
''',
)

replace_once(
    bootstrap,
    '''                    " / confirmed-max=${plan["remote_ack_max_sale_id"].orEmpty()}",
''',
    '''                    " / confirmed-max=${plan["remote_ack_max_sale_id"].orEmpty()}" +
                    " / BKP-006=rebuild:${syncRebuild.rebuiltCount}" +
                    ",missing:${syncRebuild.remainingMissingCount}" +
                    ",sent-preserved:${syncRebuild.preservedSentCount}" +
                    ",ack-preserved:${syncRebuild.preservedAckCount}" +
                    ",documentId-preserved:${syncRebuild.preservedDocumentIdCount}",
''',
)

replace_once(
    bootstrap,
    '''    private fun insertRestoreAudit(file: File, plan: Map<String, String>) {
''',
    '''    private fun insertRestoreAudit(
        file: File,
        plan: Map<String, String>,
        syncRebuild: RestoreSyncRebuildResultV136,
    ) {
''',
)

replace_once(
    bootstrap,
    '''                            "confirmed-max=${plan["remote_ack_max_sale_id"].orEmpty()}",
''',
    '''                            "confirmed-max=${plan["remote_ack_max_sale_id"].orEmpty()} / " +
                            "BKP-006=rebuild:${syncRebuild.rebuiltCount}," +
                            "missing:${syncRebuild.remainingMissingCount}," +
                            "sent-preserved:${syncRebuild.preservedSentCount}," +
                            "ack-preserved:${syncRebuild.preservedAckCount}," +
                            "documentId-preserved:${syncRebuild.preservedDocumentIdCount}",
''',
)

restore_file = ROOT / "app/src/main/java/jp/co/tenposinfo/register/RestoreSyncRebuildV136.kt"
restore_file.write_text(r'''package jp.co.tenposinfo.register

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

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
''')

test = ROOT / "app/src/test/java/jp/co/tenposinfo/register/V144Bkp006SyncRebuildTest.kt"
test.write_text(r'''package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V144Bkp006SyncRebuildTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) File(current, "app") else current
    }

    @Test
    fun originalOutboxIdentityWinsAfterSpareTerminalMigration() {
        val old = OutboxIdentitySnapshotV136.choose(
            sourceStoreId = "STORE-A",
            sourceTerminalId = "TERMINAL-OLD",
            sourceGeneration = 4,
            fallback = SalesJournalIdentity("STORE-A", "TERMINAL-NEW", 5),
        )
        assertEquals("STORE-A", old.storeId)
        assertEquals("TERMINAL-OLD", old.terminalId)
        assertEquals(4L, old.generation)
    }

    @Test
    fun incompleteIdentitySnapshotFailsBackToPersistedRuntimeIdentity() {
        val current = SalesJournalIdentity("STORE-A", "TERMINAL-NEW", 5)
        assertEquals(current, OutboxIdentitySnapshotV136.choose("STORE-A", null, 4, current))
        assertEquals(current, OutboxIdentitySnapshotV136.choose("STORE-A", "TERMINAL-OLD", 0, current))
    }

    @Test
    fun sourceWiresRestoreRebuildBeforeTerminalSwitchAndPreservesAckContract() {
        fun source(name: String) = File(root, "src/main/java/jp/co/tenposinfo/register/$name").readText()
        val business = source("BusinessSyncFoundation.kt")
        val rebuild = source("RestoreSyncRebuildV136.kt")
        val bootstrap = source("DataRestoreBootstrapV086.kt")
        val drive = source("GoogleDriveDirectUpload.kt")

        assertTrue(business.contains("source_store_id"))
        assertTrue(business.contains("source_terminal_id"))
        assertTrue(business.contains("source_generation"))
        assertTrue(business.contains("OutboxIdentitySnapshotV136.resolve(db, record.eventId)"))
        assertTrue(rebuild.contains("LEFT JOIN sync_outbox"))
        assertTrue(rebuild.contains("WHERE o.event_id IS NULL"))
        assertTrue(rebuild.contains("SyncOutboxStatus.PENDING.name"))
        assertTrue(rebuild.contains("drive_api_uploads"))
        assertTrue(rebuild.contains("status='SUCCEEDED'"))
        assertTrue(rebuild.contains("file_id IS NOT NULL"))
        assertTrue(!rebuild.contains("GoogleDriveService.read"))
        val rebuildIndex = bootstrap.indexOf("RestoreSyncRebuildV136.rebuild(database)")
        val migrationIndex = bootstrap.indexOf("RestoreTerminalMigrationV136.apply(database, plan)")
        assertTrue(rebuildIndex >= 0)
        assertTrue(migrationIndex > rebuildIndex)
        assertTrue(bootstrap.contains("ack-preserved"))
        assertTrue(bootstrap.contains("documentId-preserved"))
        assertTrue(drive.contains("duplicateKey"))
        assertTrue(drive.contains("file_id"))
    }
}
''')

print("BKP-006/BKP-018 patch applied")
