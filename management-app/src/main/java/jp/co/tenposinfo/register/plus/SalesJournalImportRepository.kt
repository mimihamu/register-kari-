package jp.co.tenposinfo.register.plus

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

data class SalesJournalImportDocument(
    val sourceName: String,
    val sourceUri: String?,
    val rawJson: String?,
    val loadErrorCode: ImportRejectionCode? = null,
    val loadErrorMessage: String? = null,
)

data class ImportBatchResult(
    val runId: Long,
    val sourceCount: Int,
    val importedCount: Int,
    val duplicateCount: Int,
    val rejectedCount: Int,
    val completedAt: Long,
)

data class ImportDashboard(
    val totalImported: Int,
    val totalRejected: Int,
    val distinctStores: Int,
    val latestImportedAt: Long?,
    val eventTypeCounts: Map<String, Int>,
)

data class ImportedJournalSummary(
    val duplicateImportKey: String,
    val eventType: String,
    val storeId: String,
    val terminalId: String,
    val businessDate: String,
    val totalAmount: Long?,
    val sourceName: String,
    val importedAt: Long,
)

data class ImportRunSummary(
    val id: Long,
    val startedAt: Long,
    val completedAt: Long?,
    val sourceCount: Int,
    val importedCount: Int,
    val duplicateCount: Int,
    val rejectedCount: Int,
    val status: String,
)

data class ImportRejectionSummary(
    val id: Long,
    val sourceName: String,
    val rejectionCode: String,
    val message: String,
    val createdAt: Long,
    val sourceUri: String? = null,
)

object SalesJournalImportPolicy {
    fun isDuplicateInsertResult(rowId: Long): Boolean = rowId == -1L
}

class SalesJournalImportRepository(
    private val database: ManagementDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun importDocuments(documents: List<SalesJournalImportDocument>): ImportBatchResult {
        val db = database.writableDatabase
        val startedAt = nowMillis()
        var imported = 0
        var duplicate = 0
        var rejected = 0

        db.beginTransaction()
        try {
            val runId = db.insertOrThrow(
                "import_runs",
                null,
                ContentValues().apply {
                    put("started_at", startedAt)
                    put("source_count", documents.size)
                    put("status", STATUS_RUNNING)
                },
            )

            documents.forEach { document ->
                val loadErrorCode = document.loadErrorCode
                if (loadErrorCode != null) {
                    insertRejection(
                        db = db,
                        runId = runId,
                        document = document,
                        code = loadErrorCode,
                        message = document.loadErrorMessage ?: "ファイルを読み込めませんでした",
                    )
                    rejected += 1
                    return@forEach
                }

                val rawJson = document.rawJson.orEmpty()
                if (rawJson.toByteArray(Charsets.UTF_8).size > SalesJournalImportContract.MAX_DOCUMENT_BYTES) {
                    insertRejection(
                        db = db,
                        runId = runId,
                        document = document,
                        code = ImportRejectionCode.DOCUMENT_TOO_LARGE,
                        message = "JSONファイルが20MiBを超えています",
                    )
                    rejected += 1
                    return@forEach
                }

                when (val parsed = SalesJournalImportContract.parse(rawJson)) {
                    is JournalParseResult.Rejected -> {
                        insertRejection(
                            db = db,
                            runId = runId,
                            document = document,
                            code = parsed.code,
                            message = parsed.message,
                        )
                        rejected += 1
                    }

                    is JournalParseResult.Accepted -> {
                        when (SalesJournalReplayConflictPolicyV118.decide(db, parsed.envelope)) {
                            SalesJournalReplayDecisionV118.NEW -> {
                                val rowId = insertEnvelope(
                                    db = db,
                                    runId = runId,
                                    document = document,
                                    envelope = parsed.envelope,
                                    importedAt = nowMillis(),
                                )
                                if (SalesJournalImportPolicy.isDuplicateInsertResult(rowId)) {
                                    duplicate += 1
                                } else {
                                    imported += 1
                                }
                            }

                            SalesJournalReplayDecisionV118.IDENTICAL -> {
                                duplicate += 1
                            }

                            SalesJournalReplayDecisionV118.CONFLICT -> {
                                insertRejection(
                                    db = db,
                                    runId = runId,
                                    document = document,
                                    code = ImportRejectionCode.DUPLICATE_KEY_MISMATCH,
                                    message = "同一duplicateImportKeyの既存データと業務内容が一致しません",
                                )
                                rejected += 1
                            }
                        }
                    }
                }
            }

            val completedAt = nowMillis()
            db.update(
                "import_runs",
                ContentValues().apply {
                    put("completed_at", completedAt)
                    put("imported_count", imported)
                    put("duplicate_count", duplicate)
                    put("rejected_count", rejected)
                    put(
                        "status",
                        if (rejected > 0) STATUS_COMPLETED_WITH_ERRORS else STATUS_COMPLETED,
                    )
                },
                "id=?",
                arrayOf(runId.toString()),
            )
            db.setTransactionSuccessful()

            return ImportBatchResult(
                runId = runId,
                sourceCount = documents.size,
                importedCount = imported,
                duplicateCount = duplicate,
                rejectedCount = rejected,
                completedAt = completedAt,
            )
        } finally {
            db.endTransaction()
        }
    }

    fun dashboard(): ImportDashboard {
        val db = database.readableDatabase
        val totalImported = db.singleInt("SELECT COUNT(*) FROM imported_journal")
        val totalRejected = db.singleInt("SELECT COUNT(*) FROM import_rejections")
        val distinctStores = db.singleInt(
            "SELECT COUNT(DISTINCT store_id) FROM imported_journal",
        )
        val latestImportedAt = db.rawQuery(
            "SELECT MAX(imported_at) FROM imported_journal",
            null,
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
        val eventTypeCounts = linkedMapOf<String, Int>()
        db.rawQuery(
            """
            SELECT event_type, COUNT(*)
            FROM imported_journal
            GROUP BY event_type
            ORDER BY event_type
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                eventTypeCounts[cursor.getString(0)] = cursor.getInt(1)
            }
        }
        return ImportDashboard(
            totalImported = totalImported,
            totalRejected = totalRejected,
            distinctStores = distinctStores,
            latestImportedAt = latestImportedAt,
            eventTypeCounts = eventTypeCounts,
        )
    }

    fun reportFilterOptions(): SalesReportFilterOptions {
        val db = database.readableDatabase
        return SalesReportFilterOptions(
            businessDates = db.singleStrings(
                "SELECT DISTINCT business_date FROM imported_journal ORDER BY business_date DESC",
            ),
            storeIds = db.singleStrings(
                "SELECT DISTINCT store_id FROM imported_journal ORDER BY store_id",
            ),
            terminalIds = db.singleStrings(
                "SELECT DISTINCT terminal_id FROM imported_journal ORDER BY terminal_id",
            ),
        )
    }

    fun salesReport(filter: SalesReportFilter): SalesReport {
        val clauses = mutableListOf<String>()
        val arguments = mutableListOf<String>()
        filter.businessDate?.let {
            clauses += "business_date=?"
            arguments += it
        }
        filter.storeId?.let {
            clauses += "store_id=?"
            arguments += it
        }
        filter.terminalId?.let {
            clauses += "terminal_id=?"
            arguments += it
        }
        val where = if (clauses.isEmpty()) "" else "WHERE ${clauses.joinToString(" AND ")}"
        val entries = mutableListOf<SalesJournalReportEntry>()
        database.readableDatabase.rawQuery(
            """
            SELECT duplicate_import_key, event_type, store_id, terminal_id,
                   business_date, aggregate_id, occurred_at, payload_schema,
                   payload_json, total_amount, source_name
            FROM imported_journal
            $where
            ORDER BY occurred_at DESC, imported_at DESC
            """.trimIndent(),
            arguments.toTypedArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                entries += SalesJournalReportEntry(
                    duplicateImportKey = cursor.getString(0),
                    eventType = cursor.getString(1),
                    storeId = cursor.getString(2),
                    terminalId = cursor.getString(3),
                    businessDate = cursor.getString(4),
                    aggregateId = cursor.getString(5),
                    occurredAt = cursor.getLong(6),
                    payloadSchema = cursor.getString(7),
                    payloadJson = cursor.getString(8),
                    totalAmount = if (cursor.isNull(9)) null else cursor.getLong(9),
                    sourceName = cursor.getString(10),
                )
            }
        }
        return SalesReportCalculator.calculate(entries, filter)
    }

    fun recentImports(limit: Int = 20): List<ImportedJournalSummary> {
        require(limit in 1..100)
        val rows = mutableListOf<ImportedJournalSummary>()
        database.readableDatabase.rawQuery(
            """
            SELECT duplicate_import_key, event_type, store_id, terminal_id,
                   business_date, total_amount, source_name, imported_at
            FROM imported_journal
            ORDER BY imported_at DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += ImportedJournalSummary(
                    duplicateImportKey = cursor.getString(0),
                    eventType = cursor.getString(1),
                    storeId = cursor.getString(2),
                    terminalId = cursor.getString(3),
                    businessDate = cursor.getString(4),
                    totalAmount = if (cursor.isNull(5)) null else cursor.getLong(5),
                    sourceName = cursor.getString(6),
                    importedAt = cursor.getLong(7),
                )
            }
        }
        return rows
    }

    fun recentRuns(limit: Int = 10): List<ImportRunSummary> {
        require(limit in 1..100)
        val rows = mutableListOf<ImportRunSummary>()
        database.readableDatabase.rawQuery(
            """
            SELECT id, started_at, completed_at, source_count,
                   imported_count, duplicate_count, rejected_count, status
            FROM import_runs
            ORDER BY id DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += ImportRunSummary(
                    id = cursor.getLong(0),
                    startedAt = cursor.getLong(1),
                    completedAt = if (cursor.isNull(2)) null else cursor.getLong(2),
                    sourceCount = cursor.getInt(3),
                    importedCount = cursor.getInt(4),
                    duplicateCount = cursor.getInt(5),
                    rejectedCount = cursor.getInt(6),
                    status = cursor.getString(7),
                )
            }
        }
        return rows
    }

    fun recentRejections(limit: Int = 20): List<ImportRejectionSummary> {
        require(limit in 1..100)
        val rows = mutableListOf<ImportRejectionSummary>()
        database.readableDatabase.rawQuery(
            """
            SELECT id, source_name, rejection_code, message, created_at, source_uri
            FROM import_rejections
            ORDER BY id DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += ImportRejectionSummary(
                    id = cursor.getLong(0),
                    sourceName = cursor.getString(1),
                    rejectionCode = cursor.getString(2),
                    message = cursor.getString(3),
                    createdAt = cursor.getLong(4),
                    sourceUri = if (cursor.isNull(5)) null else cursor.getString(5),
                )
            }
        }
        return rows
    }

    fun rejection(id: Long): ImportRejectionSummary? {
        require(id > 0L)
        return database.readableDatabase.rawQuery(
            """
            SELECT id, source_name, rejection_code, message, created_at, source_uri
            FROM import_rejections
            WHERE id=?
            LIMIT 1
            """.trimIndent(),
            arrayOf(id.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                ImportRejectionSummary(
                    id = cursor.getLong(0),
                    sourceName = cursor.getString(1),
                    rejectionCode = cursor.getString(2),
                    message = cursor.getString(3),
                    createdAt = cursor.getLong(4),
                    sourceUri = if (cursor.isNull(5)) null else cursor.getString(5),
                )
            }
        }
    }

    private fun insertEnvelope(
        db: SQLiteDatabase,
        runId: Long,
        document: SalesJournalImportDocument,
        envelope: SalesJournalEnvelope,
        importedAt: Long,
    ): Long = db.insertWithOnConflict(
        "imported_journal",
        null,
        ContentValues().apply {
            put("duplicate_import_key", envelope.duplicateImportKey)
            put("schema_version", envelope.schemaVersion)
            put("minimum_reader_version", envelope.minimumReaderVersion)
            put("duplicate_key_version", envelope.duplicateKeyVersion)
            put("event_id", envelope.eventId)
            put("event_type", envelope.eventType)
            put("store_id", envelope.storeId)
            put("terminal_id", envelope.terminalId)
            put("business_date", envelope.businessDate)
            put("aggregate_id", envelope.aggregateId)
            put("occurred_at", envelope.occurredAt)
            put("payload_schema", envelope.payloadSchema)
            put("payload_json", envelope.payloadJson)
            if (envelope.totalAmount == null) {
                putNull("total_amount")
            } else {
                put("total_amount", envelope.totalAmount)
            }
            put("source_name", document.sourceName)
            if (document.sourceUri == null) putNull("source_uri") else put("source_uri", document.sourceUri)
            put("raw_json", envelope.rawJson)
            put("imported_at", importedAt)
            put("import_run_id", runId)
        },
        SQLiteDatabase.CONFLICT_IGNORE,
    )

    private fun insertRejection(
        db: SQLiteDatabase,
        runId: Long,
        document: SalesJournalImportDocument,
        code: ImportRejectionCode,
        message: String,
    ) {
        val raw = document.rawJson
        db.insertOrThrow(
            "import_rejections",
            null,
            ContentValues().apply {
                put("import_run_id", runId)
                put("source_name", document.sourceName)
                if (document.sourceUri == null) putNull("source_uri") else put("source_uri", document.sourceUri)
                put("rejection_code", code.name)
                put("message", message.take(500))
                if (raw == null) {
                    putNull("raw_preview")
                    putNull("raw_sha256")
                } else {
                    put("raw_preview", raw.take(RAW_PREVIEW_CHARS))
                    put("raw_sha256", SalesJournalImportContract.sha256(raw))
                }
                put("created_at", nowMillis())
            },
        )
    }

    private fun SQLiteDatabase.singleInt(sql: String): Int = rawQuery(sql, null).use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }

    private fun SQLiteDatabase.singleStrings(sql: String): List<String> = rawQuery(sql, null).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
    }

    companion object {
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_COMPLETED_WITH_ERRORS = "COMPLETED_WITH_ERRORS"
        const val RAW_PREVIEW_CHARS = 4_096
    }
}
