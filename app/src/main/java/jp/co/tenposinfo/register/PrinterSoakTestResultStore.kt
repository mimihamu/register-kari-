package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class PrinterSoakTestRunStatus(val displayName: String) {
    RUNNING("実行中"),
    COMPLETED("完了"),
    STOPPED("停止"),
    FAILED("失敗"),
}

enum class PrinterSoakTestStepOutcome(val displayName: String) {
    SENT("送信完了"),
    STOPPED_BY_STATUS("状態異常で停止"),
    STATUS_QUERY_FAILED("状態取得失敗"),
    SEND_FAILED_BEFORE_WRITE("送信前失敗"),
    SEND_RESULT_UNKNOWN("送信結果不明"),
}

data class PrinterSoakTestRunRecord(
    val id: Long,
    val startedAt: Long,
    val finishedAt: Long?,
    val totalPlanned: Int,
    val completedCount: Int,
    val intervalMillis: Long,
    val cutEachPrint: Boolean,
    val status: PrinterSoakTestRunStatus,
    val printerName: String,
    val host: String,
    val port: Int,
    val paperWidthMm: Int,
    val profileName: String,
    val actorName: String,
    val summary: String,
    val csvPath: String?,
)

data class PrinterSoakTestStepRecord(
    val sequence: Int,
    val checkedAt: Long,
    val statusLevel: String,
    val statusSummary: String,
    val rawHex: String,
    val statusElapsedMillis: Long,
    val sentAt: Long?,
    val outcome: PrinterSoakTestStepOutcome,
    val detail: String,
)

data class PrinterSoakTestStoredResult(
    val runId: Long,
    val csvPath: String?,
    val csvText: String,
)

object PrinterSoakTestCsv {
    private val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss.SSS", Locale.JAPAN)

    fun render(run: PrinterSoakTestRunRecord, steps: List<PrinterSoakTestStepRecord>): String = buildString {
        appendRow("つぐレジ プリンター連続印刷試験結果")
        appendRow("試験ID", run.id.toString())
        appendRow("開始日時", format(run.startedAt))
        appendRow("終了日時", run.finishedAt?.let(::format).orEmpty())
        appendRow("結果", run.status.displayName)
        appendRow("予定回数", run.totalPlanned.toString())
        appendRow("完了回数", run.completedCount.toString())
        appendRow("印刷間隔ms", run.intervalMillis.toString())
        appendRow("1枚ごとカット", if (run.cutEachPrint) "あり" else "なし")
        appendRow("プリンター", run.printerName)
        appendRow("接続先", "${run.host}:${run.port}")
        appendRow("用紙幅", "${run.paperWidthMm}mm")
        appendRow("プロファイル", run.profileName)
        appendRow("実行者", run.actorName)
        appendRow("概要", run.summary)
        append('\n')
        appendRow(
            "回数",
            "状態確認日時",
            "状態レベル",
            "状態概要",
            "RAW",
            "状態応答ms",
            "送信日時",
            "結果",
            "詳細",
        )
        steps.sortedBy { it.sequence }.forEach { step ->
            appendRow(
                step.sequence.toString(),
                format(step.checkedAt),
                step.statusLevel,
                step.statusSummary,
                step.rawHex,
                step.statusElapsedMillis.toString(),
                step.sentAt?.let(::format).orEmpty(),
                step.outcome.displayName,
                step.detail,
            )
        }
    }

    fun escape(value: String): String {
        val normalized = value.replace("\r\n", "\n").replace('\r', '\n')
        val escaped = normalized.replace("\"", "\"\"")
        return if (normalized.any { it == ',' || it == '\"' || it == '\n' }) "\"$escaped\"" else escaped
    }

    private fun StringBuilder.appendRow(vararg values: String) {
        append(values.joinToString(",") { escape(it) })
        append('\n')
    }

    private fun format(epochMillis: Long): String = synchronized(dateFormat) {
        dateFormat.format(Date(epochMillis))
    }
}

class PrinterSoakTestResultStore(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val baseDatabase = RegisterDatabase(appContext)
    private val db: SQLiteDatabase = baseDatabase.writableDatabase
    private val exportDirectory = File(appContext.filesDir, "printer_soak_tests")

    init {
        ensureSchema()
    }

    override fun close() = baseDatabase.close()

    fun start(
        plan: PrinterSoakTestPlan,
        configuration: PrinterConfiguration,
        actor: String,
        startedAt: Long = System.currentTimeMillis(),
    ): Long = transaction {
        val runId = insertOrThrow(
            "printer_soak_test_runs",
            null,
            ContentValues().apply {
                put("started_at", startedAt)
                putNull("finished_at")
                put("total_planned", plan.totalPrints)
                put("completed_count", 0)
                put("interval_millis", plan.intervalMillis)
                put("cut_each_print", if (plan.cutEachPrint) 1 else 0)
                put("status", PrinterSoakTestRunStatus.RUNNING.name)
                put("printer_name", configuration.name)
                put("host", configuration.host)
                put("port", configuration.port)
                put("paper_width_mm", configuration.paperWidthMm)
                put("profile_name", configuration.profile.displayName)
                put("actor_name", actor)
                put("summary", "連続印刷試験を開始")
                putNull("csv_path")
            },
        )
        insertAudit(
            eventType = "PRINTER_SOAK_TEST_STARTED",
            referenceId = runId,
            detail = "${configuration.name} ${configuration.host}:${configuration.port} / ${plan.totalPrints}回 / ${plan.intervalMillis}ms / カット${if (plan.cutEachPrint) "あり" else "なし"}",
            actor = actor,
            createdAt = startedAt,
        )
        runId
    }

    fun recordStep(runId: Long, step: PrinterSoakTestStepRecord) = transaction {
        insertOrThrow(
            "printer_soak_test_steps",
            null,
            ContentValues().apply {
                put("run_id", runId)
                put("sequence_no", step.sequence)
                put("checked_at", step.checkedAt)
                put("status_level", step.statusLevel)
                put("status_summary", step.statusSummary)
                put("raw_hex", step.rawHex)
                put("status_elapsed_millis", step.statusElapsedMillis)
                if (step.sentAt == null) putNull("sent_at") else put("sent_at", step.sentAt)
                put("outcome", step.outcome.name)
                put("detail", step.detail.take(1_000))
            },
        )
    }

    fun finish(
        runId: Long,
        status: PrinterSoakTestRunStatus,
        completedCount: Int,
        summary: String,
        actor: String,
        finishedAt: Long = System.currentTimeMillis(),
    ): PrinterSoakTestStoredResult {
        require(status != PrinterSoakTestRunStatus.RUNNING)
        transaction {
            update(
                "printer_soak_test_runs",
                ContentValues().apply {
                    put("finished_at", finishedAt)
                    put("completed_count", completedCount.coerceAtLeast(0))
                    put("status", status.name)
                    put("summary", summary.take(1_000))
                },
                "id = ? AND status = ?",
                arrayOf(runId.toString(), PrinterSoakTestRunStatus.RUNNING.name),
            )
        }

        val run = requireNotNull(loadRun(runId)) { "連続印刷試験結果が見つかりません" }
        val csvText = PrinterSoakTestCsv.render(run, listSteps(runId))
        val csvPath = runCatching {
            exportDirectory.mkdirs()
            val file = File(exportDirectory, "TSUGUREGI_printer_soak_test_${runId}_${finishedAt}.csv")
            file.writeText("\uFEFF$csvText", Charsets.UTF_8)
            file.absolutePath
        }.getOrNull()

        transaction {
            update(
                "printer_soak_test_runs",
                ContentValues().apply {
                    if (csvPath == null) putNull("csv_path") else put("csv_path", csvPath)
                },
                "id = ?",
                arrayOf(runId.toString()),
            )
            insertAudit(
                eventType = when (status) {
                    PrinterSoakTestRunStatus.COMPLETED -> "PRINTER_SOAK_TEST_COMPLETED"
                    PrinterSoakTestRunStatus.STOPPED -> "PRINTER_SOAK_TEST_STOPPED"
                    PrinterSoakTestRunStatus.FAILED -> "PRINTER_SOAK_TEST_FAILED"
                    PrinterSoakTestRunStatus.RUNNING -> error("RUNNINGは終了状態ではありません")
                },
                referenceId = runId,
                detail = "${completedCount}/${run.totalPlanned} / ${summary.take(500)} / CSV=${csvPath ?: "保存失敗"}",
                actor = actor,
                createdAt = finishedAt,
            )
        }
        return PrinterSoakTestStoredResult(runId, csvPath, csvText)
    }

    fun recordCsvExport(runId: Long, actor: String, destination: String) = transaction {
        insertAudit(
            eventType = "PRINTER_SOAK_TEST_CSV_EXPORTED",
            referenceId = runId,
            detail = destination.take(500),
            actor = actor,
            createdAt = System.currentTimeMillis(),
        )
    }

    fun loadRun(runId: Long): PrinterSoakTestRunRecord? = db.query(
        "printer_soak_test_runs",
        RUN_COLUMNS,
        "id = ?",
        arrayOf(runId.toString()),
        null,
        null,
        null,
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toRunRecord() else null }

    fun listRecent(limit: Int = 20): List<PrinterSoakTestRunRecord> {
        val result = mutableListOf<PrinterSoakTestRunRecord>()
        db.query(
            "printer_soak_test_runs",
            RUN_COLUMNS,
            null,
            null,
            null,
            null,
            "started_at DESC, id DESC",
            limit.coerceIn(1, 100).toString(),
        ).use { cursor -> while (cursor.moveToNext()) result += cursor.toRunRecord() }
        return result
    }

    fun listSteps(runId: Long): List<PrinterSoakTestStepRecord> {
        val result = mutableListOf<PrinterSoakTestStepRecord>()
        db.query(
            "printer_soak_test_steps",
            arrayOf(
                "sequence_no", "checked_at", "status_level", "status_summary", "raw_hex",
                "status_elapsed_millis", "sent_at", "outcome", "detail",
            ),
            "run_id = ?",
            arrayOf(runId.toString()),
            null,
            null,
            "sequence_no ASC, id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += PrinterSoakTestStepRecord(
                    sequence = cursor.getInt(0),
                    checkedAt = cursor.getLong(1),
                    statusLevel = cursor.getString(2),
                    statusSummary = cursor.getString(3),
                    rawHex = cursor.getString(4),
                    statusElapsedMillis = cursor.getLong(5),
                    sentAt = if (cursor.isNull(6)) null else cursor.getLong(6),
                    outcome = enumValueOrDefault(cursor.getString(7), PrinterSoakTestStepOutcome.STATUS_QUERY_FAILED),
                    detail = cursor.getString(8),
                )
            }
        }
        return result
    }

    private fun ensureSchema() {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS printer_soak_test_runs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at INTEGER NOT NULL,
                finished_at INTEGER,
                total_planned INTEGER NOT NULL,
                completed_count INTEGER NOT NULL DEFAULT 0,
                interval_millis INTEGER NOT NULL,
                cut_each_print INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL,
                printer_name TEXT NOT NULL,
                host TEXT NOT NULL,
                port INTEGER NOT NULL,
                paper_width_mm INTEGER NOT NULL,
                profile_name TEXT NOT NULL,
                actor_name TEXT NOT NULL,
                summary TEXT NOT NULL DEFAULT '',
                csv_path TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS printer_soak_test_steps (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                run_id INTEGER NOT NULL,
                sequence_no INTEGER NOT NULL,
                checked_at INTEGER NOT NULL,
                status_level TEXT NOT NULL DEFAULT '',
                status_summary TEXT NOT NULL DEFAULT '',
                raw_hex TEXT NOT NULL DEFAULT '',
                status_elapsed_millis INTEGER NOT NULL DEFAULT 0,
                sent_at INTEGER,
                outcome TEXT NOT NULL,
                detail TEXT NOT NULL DEFAULT '',
                UNIQUE(run_id, sequence_no),
                FOREIGN KEY(run_id) REFERENCES printer_soak_test_runs(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_printer_soak_runs_started ON printer_soak_test_runs(started_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_printer_soak_steps_run ON printer_soak_test_steps(run_id, sequence_no)")
    }

    private fun insertAudit(eventType: String, referenceId: Long, detail: String, actor: String, createdAt: Long) {
        db.insertOrThrow(
            "operation_audit",
            null,
            ContentValues().apply {
                put("event_type", eventType)
                put("reference_id", referenceId)
                put("detail", detail.take(1_000))
                put("operator_name", actor.ifBlank { "プリンター連続試験" })
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

    private fun android.database.Cursor.toRunRecord(): PrinterSoakTestRunRecord = PrinterSoakTestRunRecord(
        id = getLong(0),
        startedAt = getLong(1),
        finishedAt = if (isNull(2)) null else getLong(2),
        totalPlanned = getInt(3),
        completedCount = getInt(4),
        intervalMillis = getLong(5),
        cutEachPrint = getInt(6) != 0,
        status = enumValueOrDefault(getString(7), PrinterSoakTestRunStatus.FAILED),
        printerName = getString(8),
        host = getString(9),
        port = getInt(10),
        paperWidthMm = getInt(11),
        profileName = getString(12),
        actorName = getString(13),
        summary = getString(14),
        csvPath = if (isNull(15)) null else getString(15),
    )

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T =
        runCatching { enumValueOf<T>(value.orEmpty()) }.getOrDefault(default)

    private companion object {
        val RUN_COLUMNS = arrayOf(
            "id", "started_at", "finished_at", "total_planned", "completed_count",
            "interval_millis", "cut_each_print", "status", "printer_name", "host", "port",
            "paper_width_mm", "profile_name", "actor_name", "summary", "csv_path",
        )
    }
}
