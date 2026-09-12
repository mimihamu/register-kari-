package jp.co.tenposinfo.register.plus

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
