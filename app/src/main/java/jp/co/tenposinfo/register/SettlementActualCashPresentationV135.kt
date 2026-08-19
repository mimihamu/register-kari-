package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * UC-12 / REP-001 presentation metadata for optional X-inspection cash counts.
 *
 * The legacy settlement row keeps a numeric actual_cash value for backward compatibility: when an
 * X inspection is submitted without a physical cash count, SettlementActualCashSafetyV105 stores the
 * theoretical amount. v2.5, however, requires the report to leave physical cash and variance blank
 * when the operator did not enter them.
 *
 * This sidecar records only whether the value was explicitly entered. The current-thread marker also
 * makes the first print payload correct while the settlement row is still being committed. If a
 * process dies after the settlement commit but before the sidecar bind, the already persisted print
 * payload is used as a recovery hint so a later PDF/reprint does not fabricate a cash count.
 */
internal object SettlementActualCashPresentationV135 {
    private const val TABLE = "settlement_actual_cash_input_v135"
    private const val ACTUAL_CASH_LABEL = "現金実査"

    private val currentInput = ThreadLocal<Boolean?>()

    @Volatile
    private var applicationContext: Context? = null

    @Synchronized
    fun initialize(context: Context) {
        val appContext = context.applicationContext
        val helper = RegisterDatabase(appContext)
        try {
            ensureSchema(helper.writableDatabase)
            applicationContext = appContext
        } finally {
            helper.close()
        }
    }

    fun <T> withInput(context: Context, entered: Boolean, block: () -> T): T {
        if (applicationContext == null) initialize(context)
        val previous = currentInput.get()
        currentInput.set(entered)
        return try {
            block()
        } finally {
            if (previous == null) currentInput.remove() else currentInput.set(previous)
        }
    }

    fun bind(context: Context, reportId: Long, entered: Boolean) {
        require(reportId > 0L) { "reportId must be positive" }
        if (applicationContext == null) initialize(context)
        val helper = RegisterDatabase(context.applicationContext)
        try {
            val db = helper.writableDatabase
            ensureSchema(db)
            db.insertWithOnConflict(
                TABLE,
                null,
                ContentValues().apply {
                    put("report_id", reportId)
                    put("actual_cash_entered", if (entered) 1 else 0)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        } finally {
            helper.close()
        }
    }

    fun wasEntered(reportId: Long): Boolean {
        currentInput.get()?.let { return it }
        if (reportId <= 0L) return true
        val context = applicationContext ?: return true
        val helper = RegisterDatabase(context)
        return try {
            val db = helper.readableDatabase
            ensureSchema(db)
            db.query(
                TABLE,
                arrayOf("actual_cash_entered"),
                "report_id = ?",
                arrayOf(reportId.toString()),
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                if (cursor.moveToFirst()) return@use cursor.getInt(0) != 0
                recoverFromPersistedPayload(db, reportId)
            }
        } finally {
            helper.close()
        }
    }

    private fun recoverFromPersistedPayload(db: SQLiteDatabase, reportId: Long): Boolean =
        db.query(
            "document_print_jobs",
            arrayOf("payload_text"),
            "document_type = ? AND reference_id = ?",
            arrayOf(OperationDocumentType.SETTLEMENT_REPORT.name, reportId.toString()),
            null,
            null,
            "id ASC",
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use true
            val actualCashLine = cursor.getString(0)
                .lineSequence()
                .firstOrNull { it.trimStart().startsWith(ACTUAL_CASH_LABEL) }
                ?: return@use true
            actualCashLine.any(Char::isDigit)
        }

    private fun ensureSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE (
                report_id INTEGER PRIMARY KEY,
                actual_cash_entered INTEGER NOT NULL,
                FOREIGN KEY(report_id) REFERENCES settlement_reports(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }
}
