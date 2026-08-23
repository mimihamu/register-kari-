package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase

/** Formal v2.5 §16.9: uncertain delivery must never overwrite/retry the original PrintJob. */
object PrintJobUncertainPolicyV136 {
    const val MIN_REASON_LENGTH = 4

    fun isUncertain(status: PrintJobStatus): Boolean =
        status == PrintJobStatus.SENDING || status == PrintJobStatus.PRINTING

    fun requireDecisionInput(reason: String, operatorId: String) {
        require(reason.trim().length >= MIN_REASON_LENGTH) { "判断理由を4文字以上で入力してください" }
        require(operatorId.isNotBlank()) { "担当者が必要です" }
    }
}

class PrintJobUncertainSafetyStoreV136(context: Context) : AutoCloseable {
    private val database = RegisterDatabase(context.applicationContext)
    private val db = database.writableDatabase

    init {
        ensureSchema()
    }

    override fun close() = database.close()

    fun resolveAsPrinted(job: UnifiedPrintJob, reason: String, operatorId: String) {
        PrintJobUncertainPolicyV136.requireDecisionInput(reason, operatorId)
        db.inPrintSafetyTransaction {
  val source = requireUncertain(job)
  val now = System.currentTimeMillis()
  val table = source.first
  val updated = update(
      table,
      ContentValues().apply {
          put("status", PrintJobStatus.COMPLETED.name)
          put("last_error", "印刷済みの可能性を担当者確認で完了扱い：${reason.trim()}".take(500))
          put("uncertain_resolution", "COMPLETED_AS_PRINTED")
          put("uncertain_resolved_at", now)
          put("uncertain_resolved_by", operatorId.trim().take(100))
          put("updated_at", now)
      },
      "id = ? AND status IN (?, ?)",
      arrayOf(job.sourceId.toString(), PrintJobStatus.SENDING.name, PrintJobStatus.PRINTING.name),
  )
  check(updated == 1) { "印刷ジョブの状態が変更されたため完了扱いにできませんでした" }
  if (job.type == UnifiedPrintJobType.SALE_RECEIPT) {
      execSQL("UPDATE sales SET print_count = print_count + 1 WHERE id = ?", arrayOf(source.second))
  }
  insertAudit(
      eventType = "PRINT_JOB_UNCERTAIN_COMPLETED",
      referenceId = job.sourceId,
      detail = "${job.type.name}; source_job_id=${job.sourceId}; reason=${reason.trim()}",
      operatorId = operatorId,
      createdAt = now,
  )
        }
    }

    fun createReprint(job: UnifiedPrintJob, reason: String, operatorId: String): Long {
        PrintJobUncertainPolicyV136.requireDecisionInput(reason, operatorId)
        return db.inPrintSafetyTransaction {
  val source = requireUncertain(job)
  val now = System.currentTimeMillis()
  val newJobId = if (job.type == UnifiedPrintJobType.SALE_RECEIPT) {
      val row = query(
          "print_jobs",
          arrayOf("sale_id", "paper_width_mm"),
          "id = ?",
          arrayOf(job.sourceId.toString()),
          null, null, null, "1",
      ).use { cursor ->
          check(cursor.moveToFirst()) { "元の売上印刷ジョブが見つかりません" }
          cursor.getLong(0) to cursor.getInt(1)
      }
      insertOrThrow(
          "print_jobs",
          null,
          ContentValues().apply {
              put("sale_id", row.first)
              put("paper_width_mm", row.second)
              put("status", PrintJobStatus.PENDING.name)
              put("attempt_count", 0)
              putNull("last_error")
              put("created_at", now)
              put("updated_at", now)
              put("source_job_id", job.sourceId)
              put("reprint_reason", reason.trim().take(500))
              put("reprint_operator_id", operatorId.trim().take(100))
          },
      )
  } else {
      val row = query(
          "document_print_jobs",
          arrayOf("document_type", "reference_id", "paper_width_mm", "payload_text"),
          "id = ?",
          arrayOf(job.sourceId.toString()),
          null, null, null, "1",
      ).use { cursor ->
          check(cursor.moveToFirst()) { "元の業務帳票印刷ジョブが見つかりません" }
          arrayOf(cursor.getString(0), cursor.getLong(1), cursor.getInt(2), cursor.getString(3))
      }
      insertOrThrow(
          "document_print_jobs",
          null,
          ContentValues().apply {
              put("document_type", row[0] as String)
              put("reference_id", row[1] as Long)
              put("paper_width_mm", row[2] as Int)
              put("status", PrintJobStatus.PENDING.name)
              put("attempt_count", 0)
              putNull("last_error")
              put("payload_text", row[3] as String)
              put("created_at", now)
              put("updated_at", now)
              put("source_job_id", job.sourceId)
              put("reprint_reason", reason.trim().take(500))
              put("reprint_operator_id", operatorId.trim().take(100))
          },
      )
  }

  val updated = update(
      source.first,
      ContentValues().apply {
          put("status", PrintJobStatus.DISCARDED.name)
          put("last_error", "印刷結果不明。再印刷Job.$newJobIdへ引継ぎ：${reason.trim()}".take(500))
          put("uncertain_resolution", "REPRINT_CREATED")
          put("uncertain_resolved_at", now)
          put("uncertain_resolved_by", operatorId.trim().take(100))
          put("updated_at", now)
      },
      "id = ? AND status IN (?, ?)",
      arrayOf(job.sourceId.toString(), PrintJobStatus.SENDING.name, PrintJobStatus.PRINTING.name),
  )
  check(updated == 1) { "印刷ジョブの状態が変更されたため再印刷を登録できませんでした" }
  insertAudit(
      eventType = "PRINT_JOB_UNCERTAIN_REPRINT_CREATED",
      referenceId = job.sourceId,
      detail = "${job.type.name}; source_job_id=${job.sourceId}; new_job_id=$newJobId; reason=${reason.trim()}",
      operatorId = operatorId,
      createdAt = now,
  )
  newJobId
        }
    }

    private fun SQLiteDatabase.requireUncertain(job: UnifiedPrintJob): Pair<String, Long> {
        val table = if (job.type == UnifiedPrintJobType.SALE_RECEIPT) "print_jobs" else "document_print_jobs"
        val referenceColumn = if (job.type == UnifiedPrintJobType.SALE_RECEIPT) "sale_id" else "reference_id"
        val current = query(
  table,
  arrayOf(referenceColumn, "status"),
  "id = ?",
  arrayOf(job.sourceId.toString()),
  null, null, null, "1",
        ).use { cursor ->
  check(cursor.moveToFirst()) { "印刷ジョブが見つかりません" }
  cursor.getLong(0) to PrintJobStatus.valueOf(cursor.getString(1))
        }
        check(PrintJobUncertainPolicyV136.isUncertain(current.second)) {
  "印刷ジョブは既に別の状態へ変更されています"
        }
        return table to current.first
    }

    private fun ensureSchema() {
        OperationAuditSchemaV136.ensure(db)
        listOf("print_jobs", "document_print_jobs").forEach { table ->
  ensureColumn(table, "source_job_id", "INTEGER")
  ensureColumn(table, "reprint_reason", "TEXT")
  ensureColumn(table, "reprint_operator_id", "TEXT")
  ensureColumn(table, "uncertain_resolution", "TEXT")
  ensureColumn(table, "uncertain_resolved_at", "INTEGER")
  ensureColumn(table, "uncertain_resolved_by", "TEXT")
  db.execSQL("CREATE INDEX IF NOT EXISTS idx_${table}_source_job_v136 ON $table(source_job_id)")
        }
    }

    private fun ensureColumn(table: String, column: String, definition: String) {
        val exists = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
  val nameIndex = cursor.getColumnIndex("name")
  var found = false
  while (cursor.moveToNext()) {
      if (cursor.getString(nameIndex) == column) {
          found = true
          break
      }
  }
  found
        }
        if (!exists) db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
    }

    private fun SQLiteDatabase.insertAudit(
        eventType: String,
        referenceId: Long,
        detail: String,
        operatorId: String,
        createdAt: Long,
    ) {
        insertOrThrow(
  "operation_audit",
  null,
  ContentValues().apply {
      put("event_type", eventType)
      put("reference_id", referenceId)
      put("detail", detail.take(1_000))
      put("operator_name", operatorId.trim().take(100))
      put("created_at", createdAt)
  },
        )
    }
}

private inline fun <T> SQLiteDatabase.inPrintSafetyTransaction(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    return try {
        val result = block()
        setTransactionSuccessful()
        result
    } finally {
        endTransaction()
    }
}
