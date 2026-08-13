package jp.co.tenposinfo.register.plus

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

class FolderImportRepository(
    private val database: ManagementDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun knownFingerprints(treeUri: String): Map<String, String> {
        val rows = linkedMapOf<String, String>()
        database.readableDatabase.rawQuery(
            """
            SELECT source_uri, content_sha256
            FROM folder_import_files
            WHERE tree_uri=?
            """.trimIndent(),
            arrayOf(treeUri),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows[cursor.getString(0)] = cursor.getString(1)
            }
        }
        return rows
    }

    fun recordProcessedFiles(
        treeUri: String,
        files: List<FolderImportFileMark>,
    ) {
        if (files.isEmpty()) return
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val processedAt = nowMillis()
            files.forEach { file ->
                db.insertWithOnConflict(
                    "folder_import_files",
                    null,
                    ContentValues().apply {
                        put("source_uri", file.sourceUri)
                        put("tree_uri", treeUri)
                        put("display_name", file.displayName)
                        put("content_sha256", file.contentSha256)
                        put("last_processed_at", processedAt)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearTreeHistory(treeUri: String): Int = database.writableDatabase.delete(
        "folder_import_files",
        "tree_uri=?",
        arrayOf(treeUri),
    )
}

internal enum class SalesJournalReplayDecisionV118 {
    NEW,
    IDENTICAL,
    CONFLICT,
}

internal object SalesJournalReplayConflictPolicyV118 {
    private val columns = arrayOf(
        "schema_version",
        "minimum_reader_version",
        "duplicate_key_version",
        "event_id",
        "event_type",
        "store_id",
        "terminal_id",
        "business_date",
        "aggregate_id",
        "occurred_at",
        "payload_schema",
        "payload_json",
        "total_amount",
    )

    fun decide(
        db: SQLiteDatabase,
        envelope: SalesJournalEnvelope,
    ): SalesJournalReplayDecisionV118 = db.query(
        "imported_journal",
        columns,
        "duplicate_import_key=?",
        arrayOf(envelope.duplicateImportKey),
        null,
        null,
        null,
        "1",
    ).use { cursor ->
        if (!cursor.moveToFirst()) {
            return@use SalesJournalReplayDecisionV118.NEW
        }

        val totalAmount = if (cursor.isNull(12)) null else cursor.getLong(12)
        val identical =
            cursor.getInt(0) == envelope.schemaVersion &&
                cursor.getInt(1) == envelope.minimumReaderVersion &&
                cursor.getInt(2) == envelope.duplicateKeyVersion &&
                cursor.getString(3) == envelope.eventId &&
                cursor.getString(4) == envelope.eventType &&
                cursor.getString(5) == envelope.storeId &&
                cursor.getString(6) == envelope.terminalId &&
                cursor.getString(7) == envelope.businessDate &&
                cursor.getString(8) == envelope.aggregateId &&
                cursor.getLong(9) == envelope.occurredAt &&
                cursor.getString(10) == envelope.payloadSchema &&
                cursor.getString(11) == envelope.payloadJson &&
                totalAmount == envelope.totalAmount

        if (identical) SalesJournalReplayDecisionV118.IDENTICAL else SalesJournalReplayDecisionV118.CONFLICT
    }
}
