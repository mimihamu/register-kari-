package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File


data class PrinterSoakTestRecoveryReport(
    val recoveredRunIds: List<Long>,
) {
    val recoveredCount: Int get() = recoveredRunIds.size
}

data class PrinterSoakTestPruneReport(
    val deletedRunIds: List<Long>,
    val deletedCsvFiles: Int,
) {
    val deletedCount: Int get() = deletedRunIds.size
}

class PrinterSoakTestMaintenance(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val resultStore = PrinterSoakTestResultStore(appContext)
    private val baseDatabase = RegisterDatabase(appContext)
    private val db: SQLiteDatabase = baseDatabase.writableDatabase
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val exportDirectory = File(appContext.filesDir, "printer_soak_tests")

    override fun close() {
        resultStore.close()
        baseDatabase.close()
    }

    fun retentionDays(): Int = PrinterSoakTestMaintenancePolicy.normalizeRetentionDays(
        preferences.getInt(
            KEY_RETENTION_DAYS,
            PrinterSoakTestMaintenancePolicy.DEFAULT_RETENTION_DAYS,
        ),
    )

    fun saveRetentionDays(value: Int, actor: String): PrinterSoakTestPruneReport {
        val normalized = PrinterSoakTestMaintenancePolicy.normalizeRetentionDays(value)
        preferences.edit().putInt(KEY_RETENTION_DAYS, normalized).apply()
        insertAudit(
            eventType = "PRINTER_SOAK_TEST_RETENTION_UPDATED",
            referenceId = normalized.toLong(),
            detail = "連続印刷試験履歴の保持期間を${normalized}日に変更",
            actor = actor,
            createdAt = System.currentTimeMillis(),
        )
        return prune(actor = actor)
    }

    fun recoverInterruptedRuns(now: Long = System.currentTimeMillis()): PrinterSoakTestRecoveryReport {
        val candidates = mutableListOf<Pair<Long, Int>>()
        db.query(
            "printer_soak_test_runs",
            arrayOf("id", "total_planned"),
            "status = ?",
            arrayOf(PrinterSoakTestRunStatus.RUNNING.name),
            null,
            null,
            "started_at ASC, id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) candidates += cursor.getLong(0) to cursor.getInt(1)
        }

        val recovered = mutableListOf<Long>()
        candidates.forEach { (runId, totalPlanned) ->
            val completedCount = countSentSteps(runId)
            val summary = PrinterSoakTestMaintenancePolicy.recoverySummary(completedCount, totalPlanned)
            val claimed = transaction {
                val updated = update(
                    "printer_soak_test_runs",
                    ContentValues().apply {
                        put("finished_at", now)
                        put("completed_count", completedCount)
                        put("status", PrinterSoakTestRunStatus.STOPPED.name)
                        put("summary", summary)
                    },
                    "id = ? AND status = ?",
                    arrayOf(runId.toString(), PrinterSoakTestRunStatus.RUNNING.name),
                )
                if (updated == 1) {
                    insertAuditInternal(
                        eventType = "PRINTER_SOAK_TEST_RECOVERED",
                        referenceId = runId,
                        detail = summary,
                        actor = "システム起動回収",
                        createdAt = now,
                    )
                }
                updated == 1
            }
            if (claimed) {
                val csvPath = writeInternalCsv(runId, now)
                db.update(
                    "printer_soak_test_runs",
                    ContentValues().apply {
                        if (csvPath == null) putNull("csv_path") else put("csv_path", csvPath)
                    },
                    "id = ?",
                    arrayOf(runId.toString()),
                )
                recovered += runId
            }
        }
        return PrinterSoakTestRecoveryReport(recovered)
    }

    fun regenerateCsv(
        runId: Long,
        actor: String,
        now: Long = System.currentTimeMillis(),
    ): PrinterSoakTestStoredResult {
        val run = requireNotNull(resultStore.loadRun(runId)) { "試験結果が見つかりません" }
        require(run.status != PrinterSoakTestRunStatus.RUNNING) { "実行中の試験はCSV出力できません" }
        val csvText = PrinterSoakTestCsv.render(run, resultStore.listSteps(runId))
        val csvPath = writeInternalCsvText(runId, now, csvText)
        db.update(
            "printer_soak_test_runs",
            ContentValues().apply {
                if (csvPath == null) putNull("csv_path") else put("csv_path", csvPath)
            },
            "id = ?",
            arrayOf(runId.toString()),
        )
        insertAudit(
            eventType = "PRINTER_SOAK_TEST_CSV_REGENERATED",
            referenceId = runId,
            detail = csvPath ?: "端末内CSVの再生成に失敗",
            actor = actor,
            createdAt = now,
        )
        return PrinterSoakTestStoredResult(runId, csvPath, csvText)
    }

    fun deleteRun(runId: Long, actor: String, now: Long = System.currentTimeMillis()): Boolean {
        val run = resultStore.loadRun(runId) ?: return false
        require(run.status != PrinterSoakTestRunStatus.RUNNING) { "実行中の試験は削除できません" }
        val deleted = transaction {
            delete("printer_soak_test_steps", "run_id = ?", arrayOf(runId.toString()))
            val count = delete("printer_soak_test_runs", "id = ?", arrayOf(runId.toString()))
            if (count == 1) {
                insertAuditInternal(
                    eventType = "PRINTER_SOAK_TEST_DELETED",
                    referenceId = runId,
                    detail = "${run.status.displayName} ${run.completedCount}/${run.totalPlanned} / ${run.summary.take(500)}",
                    actor = actor,
                    createdAt = now,
                )
            }
            count == 1
        }
        if (deleted) run.csvPath?.let { runCatching { File(it).delete() } }
        return deleted
    }

    fun prune(
        actor: String,
        now: Long = System.currentTimeMillis(),
    ): PrinterSoakTestPruneReport {
        val cutoff = now - retentionDays().toLong() * DAY_MILLIS
        val targets = linkedMapOf<Long, String?>()
        db.query(
            "printer_soak_test_runs",
            arrayOf("id", "csv_path"),
            "status != ? AND started_at < ?",
            arrayOf(PrinterSoakTestRunStatus.RUNNING.name, cutoff.toString()),
            null,
            null,
            "started_at ASC, id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                targets[cursor.getLong(0)] = if (cursor.isNull(1)) null else cursor.getString(1)
            }
        }

        db.rawQuery(
            """
            SELECT id, csv_path
            FROM printer_soak_test_runs
            WHERE status != ?
            ORDER BY started_at DESC, id DESC
            LIMIT -1 OFFSET ${PrinterSoakTestMaintenancePolicy.MAX_STORED_RUNS}
            """.trimIndent(),
            arrayOf(PrinterSoakTestRunStatus.RUNNING.name),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                targets.putIfAbsent(cursor.getLong(0), if (cursor.isNull(1)) null else cursor.getString(1))
            }
        }

        if (targets.isEmpty()) return PrinterSoakTestPruneReport(emptyList(), 0)
        val deletedIds = mutableListOf<Long>()
        transaction {
            targets.keys.forEach { runId ->
                delete("printer_soak_test_steps", "run_id = ?", arrayOf(runId.toString()))
                if (delete("printer_soak_test_runs", "id = ?", arrayOf(runId.toString())) == 1) {
                    deletedIds += runId
                }
            }
            if (deletedIds.isNotEmpty()) {
                insertAuditInternal(
                    eventType = "PRINTER_SOAK_TEST_HISTORY_PRUNED",
                    referenceId = deletedIds.size.toLong(),
                    detail = "保持${retentionDays()}日・最大${PrinterSoakTestMaintenancePolicy.MAX_STORED_RUNS}件 / 削除ID=${deletedIds.joinToString(",")}",
                    actor = actor,
                    createdAt = now,
                )
            }
        }

        var deletedFiles = 0
        deletedIds.forEach { runId ->
            targets[runId]?.let { path ->
                if (runCatching { File(path).delete() }.getOrDefault(false)) deletedFiles++
            }
        }
        return PrinterSoakTestPruneReport(deletedIds, deletedFiles)
    }

    private fun countSentSteps(runId: Long): Int = db.rawQuery(
        "SELECT COUNT(*) FROM printer_soak_test_steps WHERE run_id = ? AND outcome = ?",
        arrayOf(runId.toString(), PrinterSoakTestStepOutcome.SENT.name),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    private fun writeInternalCsv(runId: Long, now: Long): String? {
        val run = resultStore.loadRun(runId) ?: return null
        val csvText = PrinterSoakTestCsv.render(run, resultStore.listSteps(runId))
        return writeInternalCsvText(runId, now, csvText)
    }

    private fun writeInternalCsvText(runId: Long, now: Long, csvText: String): String? = runCatching {
        exportDirectory.mkdirs()
        val file = File(exportDirectory, "TSUGUREGI_printer_soak_test_${runId}_${now}.csv")
        file.writeText("\uFEFF$csvText", Charsets.UTF_8)
        file.absolutePath
    }.getOrNull()

    private fun insertAudit(
        eventType: String,
        referenceId: Long,
        detail: String,
        actor: String,
        createdAt: Long,
    ) = transaction {
        insertAuditInternal(eventType, referenceId, detail, actor, createdAt)
    }

    private fun SQLiteDatabase.insertAuditInternal(
        eventType: String,
        referenceId: Long,
        detail: String,
        actor: String,
        createdAt: Long,
    ) {
        insertOrThrow(
            "operation_audit",
            null,
            ContentValues().apply {
                put("event_type", eventType)
                put("reference_id", referenceId)
                put("detail", detail.take(1_000))
                put("operator_name", actor.ifBlank { "プリンター試験保守" })
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
        const val PREFERENCES_NAME = "printer_soak_test_maintenance"
        const val KEY_RETENTION_DAYS = "retention_days"
        const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
    }
}

object PrinterSoakTestStartupRecovery {
    fun recover(context: Context): Result<PrinterSoakTestRecoveryReport> = runCatching {
        PrinterSoakTestMaintenance(context).use { maintenance ->
            maintenance.recoverInterruptedRuns()
        }
    }
}
