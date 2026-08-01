package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase

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
    val payloadCsv: String,
    val status: PrinterStatusProfileCandidateStatus,
    val createdBy: String,
    val reviewedAt: Long?,
    val reviewedBy: String?,
    val reviewNote: String,
) {
    val runtimeApplied: Boolean = false
}

object PrinterStatusProfileCandidatePolicy {
    const val MAX_REVIEW_NOTE = 500

    fun canCreate(report: PrinterStatusValidationReport): Boolean =
        report.evidenceReadyForReview &&
            report.overallConfidence.rank >= PrinterEvidenceConfidence.MEDIUM.rank &&
            report.stableChangeCount > 0

    fun creationError(report: PrinterStatusValidationReport): String? = when {
        report.evidenceReadyForReview.not() ->
            report.blockers.firstOrNull() ?: "実機証跡がレビュー条件を満たしていません"
        report.overallConfidence.rank < PrinterEvidenceConfidence.MEDIUM.rank ->
            "総合信頼度が中以上ではありません"
        report.stableChangeCount <= 0 -> "安定した変化ビット候補がありません"
        else -> null
    }

    fun normalizeReviewNote(value: String): String = value
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .trim()
        .take(MAX_REVIEW_NOTE)

    fun canReview(status: PrinterStatusProfileCandidateStatus): Boolean =
        status == PrinterStatusProfileCandidateStatus.DRAFT
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
        val now = System.currentTimeMillis()
        val id = db.insertOrThrow(
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
            detail = "${report.key.displayName} / 信頼度${report.overallConfidence.displayName} / 差分${report.stableChangeCount}ビット / 元履歴${report.sourceRecordIds.joinToString("/")} / runtime適用なし",
            actor = actor,
            createdAt = now,
        )
        return requireNotNull(load(id))
    }

    fun review(
        id: Long,
        status: PrinterStatusProfileCandidateStatus,
        note: String,
        actor: String,
    ): PrinterStatusProfileCandidateRecord? {
        require(status != PrinterStatusProfileCandidateStatus.DRAFT) { "レビュー結果を指定してください" }
        val current = load(id) ?: return null
        if (!PrinterStatusProfileCandidatePolicy.canReview(current.status)) return current
        val normalizedNote = PrinterStatusProfileCandidatePolicy.normalizeReviewNote(note)
        require(normalizedNote.isNotBlank()) { "承認・却下理由を入力してください" }
        val now = System.currentTimeMillis()
        val updated = db.update(
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
        if (updated <= 0) return load(id)
        insertAudit(
            eventType = when (status) {
                PrinterStatusProfileCandidateStatus.APPROVED -> "PRINTER_STATUS_PROFILE_CANDIDATE_APPROVED"
                PrinterStatusProfileCandidateStatus.REJECTED -> "PRINTER_STATUS_PROFILE_CANDIDATE_REJECTED"
                PrinterStatusProfileCandidateStatus.DRAFT -> error("DRAFTはレビュー結果ではありません")
            },
            referenceId = id,
            detail = "${current.printerModel} / ${current.emulationMode} / ${normalizedNote.take(300)} / runtime適用なし",
            actor = actor,
            createdAt = now,
        )
        return load(id)
    }

    fun load(id: Long): PrinterStatusProfileCandidateRecord? = db.query(
        "printer_status_profile_candidates",
        COLUMNS,
        "id = ?",
        arrayOf(id.toString()),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toRecord() else null }

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
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_printer_status_candidate_created ON printer_status_profile_candidates(created_at DESC)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_printer_status_candidate_device ON printer_status_profile_candidates(profile_key, preset_key, host, port, printer_model, emulation_mode, created_at DESC)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_printer_status_candidate_status ON printer_status_profile_candidates(status_key, created_at DESC)",
        )
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
        payloadCsv = getString(11),
        status = runCatching { PrinterStatusProfileCandidateStatus.valueOf(getString(12)) }
            .getOrDefault(PrinterStatusProfileCandidateStatus.DRAFT),
        createdBy = getString(13),
        reviewedAt = if (isNull(14)) null else getLong(14),
        reviewedBy = if (isNull(15)) null else getString(15),
        reviewNote = getString(16),
    )

    private fun insertAudit(
        eventType: String,
        referenceId: Long,
        detail: String,
        actor: String,
        createdAt: Long,
    ) {
        if (!SchemaMigration.tableExists(db, "operation_audit")) return
        db.insert(
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

    private companion object {
        val COLUMNS = arrayOf(
            "id", "created_at", "profile_key", "preset_key", "host", "port",
            "printer_model", "emulation_mode", "confidence_key", "source_record_ids",
            "stable_change_count", "payload_csv", "status_key", "created_by",
            "reviewed_at", "reviewed_by", "review_note",
        )
    }
}
