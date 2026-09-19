package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.util.UUID
import java.util.concurrent.Executors

enum class CashDrawerOpenContextV136(val displayName: String) {
    CASH_SALE("現金会計"),
    CASH_REFUND("現金返金"),
    CASH_IN("入金"),
    CASH_OUT("出金"),
    EXCHANGE("両替"),
    DIAGNOSTIC_TEST("ドロアテスト"),
    STANDALONE("単独開放"),
}

/** Formal v2.5 CSH-004: only explicitly authorised business contexts may pulse the drawer. */
object CashDrawerSafetyPolicyV136 {
    const val MIN_OPEN_PULSE_MS = 50
    const val MAX_OPEN_PULSE_MS = 500

    fun shouldOpen(
        context: CashDrawerOpenContextV136,
        configuration: PrinterConfiguration,
        hasCashPayment: Boolean = true,
    ): Boolean {
        if (!configuration.drawerEnabled || !configuration.profile.supportsDrawer) return false
        val endpointAvailable = configuration.host.isNotBlank() && configuration.port in 1..65535
        if (!endpointAvailable) return false
        if (context != CashDrawerOpenContextV136.DIAGNOSTIC_TEST && !configuration.usable) return false
        return when (context) {
            CashDrawerOpenContextV136.CASH_SALE -> configuration.drawerOpenOnCashSale && hasCashPayment
            CashDrawerOpenContextV136.CASH_REFUND -> configuration.drawerOpenOnCashRefund && hasCashPayment
            CashDrawerOpenContextV136.CASH_IN,
            CashDrawerOpenContextV136.CASH_OUT -> configuration.drawerOpenOnCashMovement
            CashDrawerOpenContextV136.EXCHANGE -> configuration.drawerOpenOnExchange
            CashDrawerOpenContextV136.DIAGNOSTIC_TEST -> true
            CashDrawerOpenContextV136.STANDALONE -> configuration.drawerStandaloneEnabled
        }
    }

    fun requireReason(configuration: PrinterConfiguration, reason: String): String {
        val clean = reason.trim()
        if (configuration.drawerOpenReasonRequired) {
            require(clean.isNotBlank()) { "ドロア開放理由を入力してください" }
        }
        return clean.ifBlank { "理由未設定" }
    }

    fun validatePulse(configuration: PrinterConfiguration) {
        require(configuration.drawerOnMillis in MIN_OPEN_PULSE_MS..MAX_OPEN_PULSE_MS) {
            "ドロアON時間は${MIN_OPEN_PULSE_MS}～${MAX_OPEN_PULSE_MS}msです"
        }
    }
}

/**
 * Network I/O is not transactional with SQLite. Claim the business event before sending the pulse,
 * then never automatically send the same event key again. A process/network failure therefore
 * fails closed instead of risking a second physical drawer opening.
 */
class CashDrawerSafetyStoreV136(context: Context) : AutoCloseable {
    private val database = RegisterDatabase(context.applicationContext)
    private val db: SQLiteDatabase = database.writableDatabase

    init {
        ensureSchema()
    }

    fun claim(
        eventKey: String,
        context: CashDrawerOpenContextV136,
        referenceId: Long,
        reason: String,
        actor: String,
    ): Boolean {
        val cleanKey = eventKey.trim().take(160)
        require(cleanKey.isNotBlank()) { "ドロアイベントキーが必要です" }
        val now = System.currentTimeMillis()
        val rowId = db.insertWithOnConflict(
            "cash_drawer_events",
            null,
            ContentValues().apply {
                put("event_key", cleanKey)
                put("open_context", context.name)
                put("reference_id", referenceId)
                put("reason", reason.take(300))
                put("operator_name", actor.trim().ifBlank { "SYSTEM" }.take(100))
                put("status", "CLAIMED")
                put("created_at", now)
                put("updated_at", now)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        return rowId != -1L
    }

    fun complete(eventKey: String, succeeded: Boolean, error: String?) {
        db.update(
            "cash_drawer_events",
            ContentValues().apply {
                put("status", if (succeeded) "SUCCEEDED" else "FAILED_OR_UNCERTAIN")
                if (error.isNullOrBlank()) putNull("last_error") else put("last_error", error.take(500))
                put("updated_at", System.currentTimeMillis())
            },
            "event_key = ? AND status = ?",
            arrayOf(eventKey.trim().take(160), "CLAIMED"),
        )
    }

    private fun ensureSchema() {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cash_drawer_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_key TEXT NOT NULL UNIQUE,
                open_context TEXT NOT NULL,
                reference_id INTEGER NOT NULL,
                reason TEXT NOT NULL,
                operator_name TEXT NOT NULL,
                status TEXT NOT NULL,
                last_error TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_cash_drawer_events_reference ON cash_drawer_events(open_context, reference_id, created_at)",
        )
    }

    override fun close() = database.close()
}

object CashDrawerRuntimeV136 {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tsuguregi-cash-drawer").apply { isDaemon = true }
    }

    fun dispatch(
        context: Context,
        openContext: CashDrawerOpenContextV136,
        referenceId: Long,
        eventKey: String,
        reason: String,
        actor: String,
        hasCashPayment: Boolean = true,
    ): Result<Boolean> {
        val appContext = context.applicationContext
        val configuration = PrinterPaperSettingPolicy.currentConfiguration(appContext)
        return dispatchWithConfiguration(
            appContext = appContext,
            configuration = configuration,
            openContext = openContext,
            referenceId = referenceId,
            eventKey = eventKey,
            reason = reason,
            actor = actor,
            hasCashPayment = hasCashPayment,
        )
    }

    fun dispatchAsync(
        context: Context,
        openContext: CashDrawerOpenContextV136,
        referenceId: Long,
        eventKey: String,
        reason: String,
        actor: String,
        hasCashPayment: Boolean = true,
    ) {
        val appContext = context.applicationContext
        executor.execute {
            runCatching {
                dispatch(
                    context = appContext,
                    openContext = openContext,
                    referenceId = referenceId,
                    eventKey = eventKey,
                    reason = reason,
                    actor = actor,
                    hasCashPayment = hasCashPayment,
                ).getOrThrow()
            }
        }
    }

    fun dispatchDiagnostic(
        context: Context,
        configuration: PrinterConfiguration,
        actor: String,
    ): Result<Boolean> = dispatchWithConfiguration(
        appContext = context.applicationContext,
        configuration = configuration,
        openContext = CashDrawerOpenContextV136.DIAGNOSTIC_TEST,
        referenceId = 1L,
        eventKey = "DIAGNOSTIC:${System.currentTimeMillis()}:${UUID.randomUUID()}",
        reason = "プリンター設定ドロアテスト",
        actor = actor,
        hasCashPayment = true,
    )

    private fun dispatchWithConfiguration(
        appContext: Context,
        configuration: PrinterConfiguration,
        openContext: CashDrawerOpenContextV136,
        referenceId: Long,
        eventKey: String,
        reason: String,
        actor: String,
        hasCashPayment: Boolean,
    ): Result<Boolean> {
        if (!CashDrawerSafetyPolicyV136.shouldOpen(openContext, configuration, hasCashPayment)) {
            return Result.success(false)
        }
        CashDrawerSafetyPolicyV136.validatePulse(configuration)
        val cleanReason = CashDrawerSafetyPolicyV136.requireReason(configuration, reason)
        val cleanActor = actor.trim().ifBlank { "SYSTEM" }
        val cleanEventKey = eventKey.trim().take(160)

        val claimed = CashDrawerSafetyStoreV136(appContext).use { store ->
            store.claim(cleanEventKey, openContext, referenceId, cleanReason, cleanActor)
        }
        if (!claimed) {
            audit(
                appContext,
                eventType = "CASH_DRAWER_DUPLICATE_SUPPRESSED",
                referenceId = referenceId,
                detail = "${openContext.displayName} / reason=$cleanReason / event=$cleanEventKey",
                actor = cleanActor,
            )
            return Result.success(false)
        }

        val sendResult = runCatching {
            TcpEscPosPrinterGateway(
                host = configuration.host.trim(),
                port = configuration.port,
                timeoutMillis = configuration.timeoutMillis,
            ).send(PrinterCommandEncoder.drawerOnly(configuration)).getOrThrow()
        }

        CashDrawerSafetyStoreV136(appContext).use { store ->
            store.complete(
                eventKey = cleanEventKey,
                succeeded = sendResult.isSuccess,
                error = sendResult.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName },
            )
        }
        audit(
            appContext,
            eventType = if (sendResult.isSuccess) {
                "CASH_DRAWER_OPEN_SUCCEEDED"
            } else {
                "CASH_DRAWER_OPEN_FAILED_OR_UNCERTAIN"
            },
            referenceId = referenceId,
            detail = buildString {
                append(openContext.displayName)
                append(" / reason=").append(cleanReason)
                append(" / event=").append(cleanEventKey)
                append(" / ").append(configuration.host.trim()).append(':').append(configuration.port)
                sendResult.exceptionOrNull()?.let { append(" / ").append(it.message ?: it.javaClass.simpleName) }
            },
            actor = cleanActor,
        )
        return sendResult.map { true }
    }

    private fun audit(
        context: Context,
        eventType: String,
        referenceId: Long,
        detail: String,
        actor: String,
    ) {
        runCatching {
            AdminSettingsStore(context).use { store ->
                store.recordOperationalAudit(eventType, referenceId, detail, actor)
            }
        }
    }
}
