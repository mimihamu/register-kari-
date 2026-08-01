package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest

enum class PrinterStatusProfileCandidateStatus(val displayName: String) {
    DRAFT("未承認"),
    APPROVED("承認済み"),
    REJECTED("却下"),
}

data class PrinterStatusProfileCandidateRecord(
    val id: Long,
    val createdAt: Long,
    val profile: PrinterProfile,
    val preset: PrinterStatusProbePreset,
    val host: String,
    val port: Int,
    val printerModel: String,
    val emulationMode: String,
    val confidence: PrinterEvidenceConfidence,
    val sourceRecordIds: String,
    val stableChangeCount: Int,
    val evidenceKey: String,
    val analysisSnapshot: String,
    val payloadCsv: String,
    val status: PrinterStatusProfileCandidateStatus,
    val createdBy: String,
    val reviewedAt: Long?,
    val reviewedBy: String?,
    val reviewNote: String,
    val runtimeApplied: Boolean,
)

object PrinterStatusProfileCandidatePolicy {
    const val MAX_REVIEW_NOTE = 500

    fun canCreate(report: PrinterStatusValidationReport): Boolean =
        report.evidenceReadyForReview &&
            report.overallConfidence.rank >= PrinterEvidenceConfidence.MEDIUM.rank &&
            report.stableChangeCount > 0 &&
            report.sourceRecordIds.isNotEmpty() &&
            report.sourceRecordIds.all { it > 0L }

    fun creationError(report: PrinterStatusValidationReport): String? = when {
        report.evidenceReadyForReview.not() ->
            report.blockers.firstOrNull() ?: "実機証跡がレビュー条件を満たしていません"
        report.overallConfidence.rank < PrinterEvidenceConfidence.MEDIUM.rank ->
            "総合信頼度が中以上ではありません"
        report.stableChangeCount <= 0 -> "安定した変化ビット候補がありません"
        report.sourceRecordIds.isEmpty() -> "元RAW履歴IDがありません"
        report.sourceRecordIds.any { it <= 0L } -> "未保存の元RAW履歴が含まれます"
        else -> null
    }

    fun normalizeReviewNote(value: String): String = value
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .trim()
        .take(MAX_REVIEW_NOTE)

    fun requireReviewNote(value: String): String {
        val normalized = normalizeReviewNote(value)
        require(normalized.isNotBlank()) { "承認・却下理由を入力してください" }
        return normalized
    }

    fun canReview(status: PrinterStatusProfileCandidateStatus): Boolean =
        status == PrinterStatusProfileCandidateStatus.DRAFT

    fun evidenceKey(report: PrinterStatusValidationReport): String {
        val canonical = buildString {
            append(report.key.profile.name).append('|')
            append(report.key.preset.name).append('|')
            append(report.key.host.trim()).append('|')
            append(report.key.port).append('|')
            append(report.key.printerModel.trim()).append('|')
            append(report.key.emulationMode.trim()).append('|')
            append(report.sourceRecordIds.distinct().sorted().joinToString(","))
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun analysisSnapshot(report: PrinterStatusValidationReport): String = buildString {
        appendLine("schema=printer-status-profile-candidate-v1")
        appendLine("confidence=${report.overallConfidence.name}")
        appendLine("reviewable=${report.evidenceReadyForReview}")
        appendLine("sourceRecordIds=${report.sourceRecordIds.joinToString(",")}")
        appendLine("stableChangeCount=${report.stableChangeCount}")
        appendLine("outlierCount=${report.totalOutlierCount}")
        appendLine("responseLengthMismatchCount=${report.responseLengthMismatchCount}")
        report.evidence.forEach { item ->
            append(item.condition.name).append('|')
            append(item.expectation.name).append('|')
            append("total=").append(item.totalCount).append('|')
            append("success=").append(item.successCount).append('|')
            append("failure=").append(item.failureCount).append('|')
            append("agreement=").append(item.cluster?.agreementPercent ?: -1).append('|')
            append("outliers=").append(item.cluster?.outlierCount ?: 0).append('|')
            append("confidence=").append(item.confidence.name).append('|')
            append("ready=").append(item.ready).append('|')
            append("source=").append(item.sourceRecordIds.joinToString("/")).append('|')
            appendLine("reason=${item.reason.replace("\n", " ")}")
        }
    }

    fun runtimeAppliedAfterReview(): Boolean = false
}

class PrinterStatusProfileCandidateStore(context: Context) : AutoCloseable {
    private val database = RegisterDatabase(context.applicationContext)
    private val db: SQLiteDatabase = database.writableDatabase

    init {
        ensureSchema()
    }

    override fun close() = database.close()

    fun createDraft(
        report: PrinterStatusValidationReport,
        actor: String,
    ): PrinterStatusProfileCandidateRecord {
        val error = PrinterStatusProfileCandidatePolicy.creationError(report)
        require(error == null) { error ?: "候補を作成できません" }
        val evidenceKey = PrinterStatusProfileCandidatePolicy.evidenceKey(report)
        val now = System.currentTimeMillis()
        return transaction {
            require(loadByEvidenceKeyInternal(evidenceKey) == null) {
                "同一の元RAW証跡から候補は既に作成されています"
            }
            val id = insertOrThrow(
                "printer_status_profile_candidates",
                null,
                ContentValues().apply {
                    put("created_at", now)
                    put("profile_key", report.key.profile.name)
                    put("preset_key", report.key.preset.name)
                    put("host", report.key.host)
                    put("port", report.key.port)
                    put("printer_model", report.key.printerModel)
                    put("emulation_mode", report.key.emulationMode)
                    put("confidence_key", report.overallConfidence.name)
                    put("source_record_ids", report.sourceRecordIds.joinToString(","))
                    put("stable_change_count", report.stableChangeCount)
                    put("evidence_key", evidenceKey)
                    put("analysis_snapshot", PrinterStatusProfileCandidatePolicy.analysisSnapshot(report))
                    put("payload_csv", PrinterStatusValidationCsv.render(report))
                    put("status_key", PrinterStatusProfileCandidateStatus.DRAFT.name)
                    put("created_by", actor.ifBlank { "責任者" })
                    putNull("reviewed_at")
                    putNull("reviewed_by")
                    put("review_note", "")
                    put("runtime_applied", 0)
                },
            )
            insertAudit(
                eventType = "PRINTER_STATUS_PROFILE_CANDIDATE_CREATED",
                referenceId = id,
                detail = "${report.key.displayName} / 信頼度${report.overallConfidence.displayName} / " +
                    "差分${report.stableChangeCount}ビット / 元履歴${report.sourceRecordIds.joinToString("/")} / " +
                    "evidence=${evidenceKey.take(16)} / runtime適用なし",
                actor = actor,
                createdAt = now,
            )
            requireNotNull(loadInternal(id))
        }
    }

    fun review(
        id: Long,
        status: PrinterStatusProfileCandidateStatus,
        note: String,
        actor: String,
    ): PrinterStatusProfileCandidateRecord? {
        require(status != PrinterStatusProfileCandidateStatus.DRAFT) { "レビュー結果を指定してください" }
        val normalizedNote = PrinterStatusProfileCandidatePolicy.requireReviewNote(note)
        val now = System.currentTimeMillis()
        return transaction {
            val current = loadInternal(id) ?: return@transaction null
            if (!PrinterStatusProfileCandidatePolicy.canReview(current.status)) return@transaction current
            val updated = update(
                "printer_status_profile_candidates",
                ContentValues().apply {
                    put("status_key", status.name)
                    put("reviewed_at", now)
                    put("reviewed_by", actor.ifBlank { "責任者" })
                    put("review_note", normalizedNote)
                    put("runtime_applied", 0)
                },
                "id = ? AND status_key = ?",
                arrayOf(id.toString(), PrinterStatusProfileCandidateStatus.DRAFT.name),
            )
            if (updated <= 0) return@transaction loadInternal(id)
            insertAudit(
                eventType = when (status) {
                    PrinterStatusProfileCandidateStatus.APPROVED ->
                        "PRINTER_STATUS_PROFILE_CANDIDATE_APPROVED"
                    PrinterStatusProfileCandidateStatus.REJECTED ->
                        "PRINTER_STATUS_PROFILE_CANDIDATE_REJECTED"
                    PrinterStatusProfileCandidateStatus.DRAFT ->
                        error("DRAFTはレビュー結果ではありません")
                },
                referenceId = id,
                detail = "${current.printerModel} / ${current.emulationMode} / " +
                    "${normalizedNote.take(300)} / runtime適用なし / 正式反映はコードレビューと実装変更が必要",
                actor = actor,
                createdAt = now,
            )
            loadInternal(id)
        }
    }

    fun load(id: Long): PrinterStatusProfileCandidateRecord? = loadInternal(id)

    fun loadByEvidenceKey(evidenceKey: String): PrinterStatusProfileCandidateRecord? =
        loadByEvidenceKeyInternal(evidenceKey)

    fun listRecent(limit: Int = 100): List<PrinterStatusProfileCandidateRecord> {
        val result = mutableListOf<PrinterStatusProfileCandidateRecord>()
        db.query(
            "printer_status_profile_candidates",
            COLUMNS,
            null,
            null,
            null,
            null,
            "created_at DESC, id DESC",
            limit.coerceIn(1, 500).toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toRecord()
        }
        return result
    }

    fun listForKey(key: PrinterStatusProbeDeviceKey, limit: Int = 100): List<PrinterStatusProfileCandidateRecord> =
        listRecent(limit).filter { record ->
            record.profile == key.profile &&
                record.preset == key.preset &&
                record.host == key.host &&
                record.port == key.port &&
                record.printerModel == key.printerModel &&
                record.emulationMode == key.emulationMode
        }

    private fun loadInternal(id: Long): PrinterStatusProfileCandidateRecord? = db.query(
        "printer_status_profile_candidates",
        COLUMNS,
        "id = ?",
        arrayOf(id.toString()),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toRecord() else null }

    private fun loadByEvidenceKeyInternal(evidenceKey: String): PrinterStatusProfileCandidateRecord? = db.query(
        "printer_status_profile_candidates",
        COLUMNS,
        "evidence_key = ?",
        arrayOf(evidenceKey),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toRecord() else null }

    private fun ensureSchema() {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS printer_status_profile_candidates (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at INTEGER NOT NULL,
                profile_key TEXT NOT NULL,
                preset_key TEXT NOT NULL,
                host TEXT NOT NULL,
                port INTEGER NOT NULL,
                printer_model TEXT NOT NULL,
                emulation_mode TEXT NOT NULL,
                confidence_key TEXT NOT NULL,
                source_record_ids TEXT NOT NULL,
                stable_change_count INTEGER NOT NULL DEFAULT 0,
                evidence_key TEXT NOT NULL DEFAULT '',
                analysis_snapshot TEXT NOT NULL DEFAULT '',
                payload_csv TEXT NOT NULL,
                status_key TEXT NOT NULL,
                created_by TEXT NOT NULL,
                reviewed_at INTEGER,
                reviewed_by TEXT,
                review_note TEXT NOT NULL DEFAULT '',
                runtime_applied INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        ensureColumn("printer_status_profile_candidates", "evidence_key", "TEXT NOT NULL DEFAULT ''")
        ensureColumn("printer_status_profile_candidates", "analysis_snapshot", "TEXT NOT NULL DEFAULT ''")
        ensureColumn("printer_status_profile_candidates", "runtime_applied", "INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "UPDATE printer_status_profile_candidates SET evidence_key = 'legacy:' || id " +
                "WHERE evidence_key IS NULL OR evidence_key = ''",
        )
        db.execSQL(
            "UPDATE printer_status_profile_candidates SET runtime_applied = 0 WHERE runtime_applied <> 0",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS ux_printer_status_candidate_evidence " +
                "ON printer_status_profile_candidates(evidence_key)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_printer_status_candidate_created " +
                "ON printer_status_profile_candidates(created_at DESC)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_printer_status_candidate_device " +
                "ON printer_status_profile_candidates(profile_key, preset_key, host, port, " +
                "printer_model, emulation_mode, created_at DESC)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_printer_status_candidate_status " +
                "ON printer_status_profile_candidates(status_key, created_at DESC)",
        )
    }

    private fun ensureColumn(table: String, column: String, definition: String) {
        val exists = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
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
        if (!exists) db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
    }

    private fun android.database.Cursor.toRecord() = PrinterStatusProfileCandidateRecord(
        id = getLong(0),
        createdAt = getLong(1),
        profile = runCatching { PrinterProfile.valueOf(getString(2)) }
            .getOrDefault(PrinterProfile.GENERIC_ESC_POS),
        preset = runCatching { PrinterStatusProbePreset.valueOf(getString(3)) }
            .getOrDefault(PrinterStatusProbePreset.TCP_CONNECT_ONLY),
        host = getString(4),
        port = getInt(5),
        printerModel = getString(6),
        emulationMode = getString(7),
        confidence = runCatching { PrinterEvidenceConfidence.valueOf(getString(8)) }
            .getOrDefault(PrinterEvidenceConfidence.NOT_READY),
        sourceRecordIds = getString(9),
        stableChangeCount = getInt(10),
        evidenceKey = getString(11),
        analysisSnapshot = getString(12),
        payloadCsv = getString(13),
        status = runCatching { PrinterStatusProfileCandidateStatus.valueOf(getString(14)) }
            .getOrDefault(PrinterStatusProfileCandidateStatus.DRAFT),
        createdBy = getString(15),
        reviewedAt = if (isNull(16)) null else getLong(16),
        reviewedBy = if (isNull(17)) null else getString(17),
        reviewNote = getString(18),
        runtimeApplied = getInt(19) != 0,
    )

    private fun insertAudit(
        eventType: String,
        referenceId: Long,
        detail: String,
        actor: String,
        createdAt: Long,
    ) {
        if (!SchemaMigration.tableExists(db, "operation_audit")) return
        db.insertOrThrow(
            "operation_audit",
            null,
            ContentValues().apply {
                put("event_type", eventType)
                put("reference_id", referenceId)
                put("detail", detail.take(1_000))
                put("operator_name", actor.ifBlank { "責任者" })
                put("created_at", createdAt)
            },
        )
    }

    private inline fun <T> transaction(block: SQLiteDatabase.() -> T): T {
        db.beginTransaction()
        return try {
            val value = db.block()
            db.setTransactionSuccessful()
            value
        } finally {
            db.endTransaction()
        }
    }

    private companion object {
        val COLUMNS = arrayOf(
            "id", "created_at", "profile_key", "preset_key", "host", "port",
            "printer_model", "emulation_mode", "confidence_key", "source_record_ids",
            "stable_change_count", "evidence_key", "analysis_snapshot", "payload_csv",
            "status_key", "created_by", "reviewed_at", "reviewed_by", "review_note",
            "runtime_applied",
        )
    }
}
