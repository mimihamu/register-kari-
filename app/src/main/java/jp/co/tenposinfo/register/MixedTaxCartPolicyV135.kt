package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.widget.Toast
import androidx.compose.runtime.snapshots.SnapshotStateList

enum class MixedTaxCartActionV135 {
    ADD,
    WARN_AND_ADD,
    DENY,
}

data class MixedTaxCartDecisionV135(
    val action: MixedTaxCartActionV135,
    val introducedMixedRates: Set<Int> = emptySet(),
    val message: String? = null,
) {
    val mayAdd: Boolean get() = action != MixedTaxCartActionV135.DENY
    val requiresWarningHistory: Boolean get() = action == MixedTaxCartActionV135.WARN_AND_ADD
}

/**
 * v1.35 TAX-004 registration-time guard.
 *
 * Only a newly introduced same-rate included/excluded mix is controlled.
 * Different tax rates (for example included 10% + excluded 8%) stay allowed.
 * Existing mixed rates do not repeatedly warn when an unrelated product is added.
 */
object MixedTaxCartPolicyV135 {
    fun evaluate(
        existingItems: List<CartItem>,
        candidate: CartItem,
        policy: MixedTaxPolicy,
    ): MixedTaxCartDecisionV135 {
        val before = mixedRates(existingItems)
        val after = mixedRates(existingItems + candidate)
        val introduced = (after - before).toSortedSet()
        if (introduced.isEmpty()) return MixedTaxCartDecisionV135(MixedTaxCartActionV135.ADD)

        val rates = introduced.joinToString("・") { "$it%" }
        val baseMessage = "同一税率の内税商品と外税商品が混在します（$rates）"
        return when (policy) {
            MixedTaxPolicy.ALLOW -> MixedTaxCartDecisionV135(
                action = MixedTaxCartActionV135.ADD,
                introducedMixedRates = introduced,
            )
            MixedTaxPolicy.WARN -> MixedTaxCartDecisionV135(
                action = MixedTaxCartActionV135.WARN_AND_ADD,
                introducedMixedRates = introduced,
                message = "$baseMessage。商品は追加しますが、会計確定前に混在内容の確認が必要です。",
            )
            MixedTaxPolicy.BLOCK -> MixedTaxCartDecisionV135(
                action = MixedTaxCartActionV135.DENY,
                introducedMixedRates = introduced,
                message = "$baseMessage。設定が「禁止」のため、この商品は追加できません。",
            )
        }
    }

    internal fun mixedRates(items: List<CartItem>): Set<Int> = items
        .asSequence()
        .filter { it.product.taxable }
        .groupBy { it.product.taxRatePercent }
        .filterValues { rows -> rows.map { it.product.taxIncluded }.toSet().size > 1 }
        .keys
}

/**
 * Initialized by MixedTaxCartBootstrapProviderV135 before MainActivity starts.
 * Settings are loaded on each candidate registration so a saved policy change is
 * reflected without relying on a stale in-memory copy.
 */
object MixedTaxCartRuntimeV135 {
    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        TaxInvoiceSettingsRegistry.initialize(context.applicationContext)
    }

    fun policy(): MixedTaxPolicy = appContext
        ?.let { TaxInvoiceSettingsStore(it).load().mixedTaxPolicy }
        ?: TaxInvoiceSettingsRegistry.current().mixedTaxPolicy

    fun handleNotice(
        decision: MixedTaxCartDecisionV135,
        candidate: CartItem,
    ): Boolean {
        val context = appContext
        if (context == null) {
            // Bootstrap failure must never allow a newly mixed registration silently.
            return decision.action == MixedTaxCartActionV135.ADD
        }
        val message = decision.message ?: return true
        return when (decision.action) {
            MixedTaxCartActionV135.ADD -> true
            MixedTaxCartActionV135.WARN_AND_ADD -> {
                val recorded = MixedTaxWarningAuditV135.record(context, decision, candidate, "MIXED_TAX_WARN")
                if (recorded) {
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    true
                } else {
                    Toast.makeText(
                        context,
                        "税混在警告の履歴を保存できないため、商品を追加しませんでした。",
                        Toast.LENGTH_LONG,
                    ).show()
                    false
                }
            }
            MixedTaxCartActionV135.DENY -> {
                MixedTaxWarningAuditV135.record(context, decision, candidate, "MIXED_TAX_DENY")
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                false
            }
        }
    }
}

/**
 * MainActivity already registers a new product with `cart += CartItem(...)`.
 * This type-specific operator is more specific than Kotlin's generic
 * MutableCollection.plusAssign and therefore inserts TAX-004 before mutation.
 */
operator fun SnapshotStateList<CartItem>.plusAssign(candidate: CartItem) {
    val decision = MixedTaxCartPolicyV135.evaluate(
        existingItems = toList(),
        candidate = candidate,
        policy = MixedTaxCartRuntimeV135.policy(),
    )
    if (!decision.mayAdd) {
        MixedTaxCartRuntimeV135.handleNotice(decision, candidate)
        return
    }
    if (decision.requiresWarningHistory && !MixedTaxCartRuntimeV135.handleNotice(decision, candidate)) {
        return
    }
    add(candidate)
}

private object MixedTaxWarningAuditV135 {
    fun record(
        context: Context,
        decision: MixedTaxCartDecisionV135,
        candidate: CartItem,
        eventType: String,
    ): Boolean = runCatching {
        val helper = RegisterDatabase(context.applicationContext)
        try {
            val database = helper.writableDatabase
            ensureAuditTable(database)
            val operatorName = OperatorSessionRegistry.current(context.applicationContext)?.name ?: "SYSTEM"
            val rates = decision.introducedMixedRates.sorted().joinToString("・") { "$it%" }
            database.insertOrThrow(
                "operation_audit",
                null,
                ContentValues().apply {
                    put("event_type", eventType)
                    put("reference_id", 0L)
                    put(
                        "detail",
                        "商品登録時の税混在制御 / 商品=${candidate.product.id}:${candidate.product.name} / 対象税率=$rates / policy=${MixedTaxCartRuntimeV135.policy().name}",
                    )
                    put("operator_name", operatorName)
                    put("created_at", System.currentTimeMillis())
                },
            )
        } finally {
            helper.close()
        }
    }.isSuccess

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
