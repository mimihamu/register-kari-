package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * v1.35 TAX-004: persists the explicit payment-screen acknowledgement required by WARN mode.
 *
 * The registration-time warning audit proves when the mixed state was introduced. This record is
 * intentionally separate: it proves who acknowledged the mixed state before payment completion,
 * when it was acknowledged, and which rates were involved.
 */
internal object MixedTaxPaymentAcknowledgementV135 {
    const val EVENT_TYPE = "MIXED_TAX_PAYMENT_ACK"

    fun targetRates(items: List<CartItem>): Set<Int> = MixedTaxCartPolicyV135
        .mixedRates(items)
        .toSortedSet()

    fun auditDetail(items: List<CartItem>): String {
        val rates = targetRates(items)
        require(rates.isNotEmpty()) { "mixed tax acknowledgement requires a same-rate included/excluded mix" }
        return "会計確定前の税混在確認 / 対象税率=${rates.joinToString("・") { "$it%" }} / policy=${MixedTaxPolicy.WARN.name}"
    }

    fun record(context: Context, items: List<CartItem>): Boolean {
        val detail = runCatching { auditDetail(items) }.getOrElse { return false }
        return runCatching {
            val helper = RegisterDatabase(context.applicationContext)
            try {
                val database = helper.writableDatabase
                ensureAuditTable(database)
                val operatorName = OperatorSessionRegistry.current(context.applicationContext)?.name ?: "SYSTEM"
                database.insertOrThrow(
                    "operation_audit",
                    null,
                    ContentValues().apply {
                        put("event_type", EVENT_TYPE)
                        put("reference_id", 0L)
                        put("detail", detail)
                        put("operator_name", operatorName)
                        put("created_at", System.currentTimeMillis())
                    },
                )
            } finally {
                helper.close()
            }
        }.isSuccess
    }

    private fun ensureAuditTable(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS operation_audit (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_type TEXT NOT NULL,
                reference_id INTEGER NOT NULL,
                detail TEXT NOT NULL,
                operator_name TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }
}
