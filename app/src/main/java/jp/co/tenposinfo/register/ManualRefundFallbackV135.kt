package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.util.Locale

internal data class ApprovedRefundContextV135(
    val originalSaleId: Long,
    val reversalType: ReversalType,
    val requestId: String,
    val actorName: String,
)

internal data class PendingManualRefundV135(
    val originalSaleId: Long,
    val reversalType: ReversalType,
    val requestId: String,
    val refundTotal: Long,
    val suggestedMethod: String,
    val actorName: String,
    val createdAt: Long,
)

internal data class SelectedManualRefundV135(
    val requestId: String,
    val refundTotal: Long,
    val method: String,
    val selectedAt: Long,
)

internal enum class ManualRefundMethodV135(
    val storageValue: String,
    val displayName: String,
) {
    CASH(PaymentMethod.CASH.name, "現金"),
    CARD(PaymentMethod.CARD.name, "カード（端末返金）"),
    GIFT_CERTIFICATE(PaymentMethod.GIFT_CERTIFICATE.name, "商品券"),
    ACCOUNT_RECEIVABLE(PaymentMethod.ACCOUNT_RECEIVABLE.name, "掛売"),
    OTHER("OTHER", "その他"),
}

internal object ManualRefundFallbackPolicyV135 {
    fun requiresManualSelection(originalPayments: List<PaymentTotal>): Boolean =
        originalPayments.none { it.amount > 0 }

    fun validSelectedMethod(
        refundTotal: Long,
        selection: SelectedManualRefundV135?,
    ): String? = selection
        ?.takeIf { it.refundTotal == refundTotal }
        ?.method
        ?.takeIf { method -> ManualRefundMethodV135.entries.any { it.storageValue == method } }
}

internal class ManualRefundMethodSelectionRequiredV135(message: String) : IllegalStateException(message)

/**
 * v1.35 COR-008 manual refund fallback.
 *
 * The existing reversal screen already verifies REVERSAL permission and manager PIN.
 * This runtime is entered only after that approval. If the original positive payment
 * breakdown cannot be restored, the database transaction is deliberately aborted,
 * a manager-only refund-method selection screen is launched, and the same request is
 * retried with the durable one-shot selection.
 */
internal object ManualRefundFallbackRuntimeV135 {
    private const val PREFS = "manual_refund_fallback_v135"
    private const val TTL_MILLIS = 10 * 60 * 1000L
    private val approved = ThreadLocal<ApprovedRefundContextV135?>()

    @Volatile
    private var applicationContext: Context? = null

    fun <T> withApprovedContext(
        context: Context,
        approvedContext: ApprovedRefundContextV135,
        block: () -> T,
    ): T {
        val previous = approved.get()
        applicationContext = context.applicationContext
        approved.set(approvedContext)
        return try {
            block()
        } finally {
            if (previous == null) approved.remove() else approved.set(previous)
        }
    }

    /**
     * Returns null outside the manager-approved production path so the existing pure
     * calculation helper retains its legacy fallback behavior in tests/read-only tools.
     * Inside the approved path this either returns the manager selection or aborts the
     * current DB transaction and launches the selection Activity.
     */
    fun resolveMethodOrRequest(
        refundTotal: Long,
        suggestedMethod: String,
    ): String? {
        val approvedContext = approved.get() ?: return null
        val context = applicationContext
            ?: throw IllegalStateException("返金方法選択の初期化に失敗しました")
        val existing = loadSelection(context, approvedContext.requestId)
        val valid = existing
            ?.takeUnless { isExpired(it.selectedAt) }
            ?.let { ManualRefundFallbackPolicyV135.validSelectedMethod(refundTotal, it) }
        if (valid != null) return valid
        if (existing != null) clearSelection(context, approvedContext.requestId)

        val pending = PendingManualRefundV135(
            originalSaleId = approvedContext.originalSaleId,
            reversalType = approvedContext.reversalType,
            requestId = approvedContext.requestId,
            refundTotal = refundTotal,
            suggestedMethod = suggestedMethod,
            actorName = approvedContext.actorName,
            createdAt = System.currentTimeMillis(),
        )
        check(savePending(context, pending)) { "返金方法選択要求を保存できませんでした" }
        context.startActivity(
            Intent(context, ManualRefundMethodActivityV135::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
        )
        throw ManualRefundMethodSelectionRequiredV135(
            "元決済の返金内訳を自動復元できません。責任者が返金方法を選択し、元画面で返品・取消をもう一度実行してください。",
        )
    }

    fun pending(context: Context): PendingManualRefundV135? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val requestId = prefs.getString("pending_request_id", null) ?: return null
        val type = prefs.getString("pending_type", null)
            ?.let { runCatching { ReversalType.valueOf(it) }.getOrNull() }
            ?: return null
        val pending = PendingManualRefundV135(
            originalSaleId = prefs.getLong("pending_sale_id", 0L),
            reversalType = type,
            requestId = requestId,
            refundTotal = prefs.getLong("pending_refund_total", 0L),
            suggestedMethod = prefs.getString("pending_suggested_method", "OTHER") ?: "OTHER",
            actorName = prefs.getString("pending_actor", "責任者") ?: "責任者",
            createdAt = prefs.getLong("pending_created_at", 0L),
        )
        if (
            pending.originalSaleId <= 0L ||
            pending.refundTotal <= 0L ||
            pending.requestId.isBlank() ||
            isExpired(pending.createdAt)
        ) {
            clearPending(context, requestId)
            return null
        }
        return pending
    }

    fun select(
        context: Context,
        pending: PendingManualRefundV135,
        method: ManualRefundMethodV135,
    ) {
        val latest = pending(context)
        check(latest?.requestId == pending.requestId) { "返金方法選択要求が更新されました。元画面からやり直してください" }
        val detail = buildString {
            append("自動返金内訳を復元できないため責任者が返金方法を選択")
            append(" / 元売上 No.${pending.originalSaleId}")
            append(" / ${pending.reversalType.displayName}")
            append(" / 返金 ${pending.refundTotal}円")
            append(" / 選択=${method.storageValue}")
            append(" / 従来推定=${pending.suggestedMethod}")
            append(" / request=${pending.requestId}")
        }
        check(
            ManualRefundFallbackAuditV135.record(
                context = context,
                eventType = "REFUND_METHOD_OVERRIDE_SELECTED",
                referenceId = pending.originalSaleId,
                detail = detail,
                operatorName = pending.actorName,
            ),
        ) { "返金方法選択の監査履歴を保存できませんでした" }

        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.edit()
            .putString(selectionMethodKey(pending.requestId), method.storageValue)
            .putLong(selectionAmountKey(pending.requestId), pending.refundTotal)
            .putLong(selectionTimeKey(pending.requestId), System.currentTimeMillis())
            .remove("pending_request_id")
            .remove("pending_sale_id")
            .remove("pending_type")
            .remove("pending_refund_total")
            .remove("pending_suggested_method")
            .remove("pending_actor")
            .remove("pending_created_at")
            .commit()
        check(saved) { "返金方法の選択を保存できませんでした" }
    }

    /** Clears the one-shot selection only after the reversal transaction committed. */
    fun complete(
        context: Context,
        approvedContext: ApprovedRefundContextV135,
        refundTotal: Long,
    ) {
        val selection = loadSelection(context, approvedContext.requestId)
        if (ManualRefundFallbackPolicyV135.validSelectedMethod(refundTotal, selection) != null) {
            clearSelection(context, approvedContext.requestId)
        }
        clearPending(context, approvedContext.requestId)
    }

    fun cancelPending(context: Context, requestId: String) {
        clearPending(context, requestId)
    }

    private fun savePending(context: Context, pending: PendingManualRefundV135): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("pending_request_id", pending.requestId)
            .putLong("pending_sale_id", pending.originalSaleId)
            .putString("pending_type", pending.reversalType.name)
            .putLong("pending_refund_total", pending.refundTotal)
            .putString("pending_suggested_method", pending.suggestedMethod)
            .putString("pending_actor", pending.actorName)
            .putLong("pending_created_at", pending.createdAt)
            .commit()

    private fun loadSelection(context: Context, requestId: String): SelectedManualRefundV135? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val method = prefs.getString(selectionMethodKey(requestId), null) ?: return null
        return SelectedManualRefundV135(
            requestId = requestId,
            refundTotal = prefs.getLong(selectionAmountKey(requestId), 0L),
            method = method,
            selectedAt = prefs.getLong(selectionTimeKey(requestId), 0L),
        )
    }

    private fun clearSelection(context: Context, requestId: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(selectionMethodKey(requestId))
            .remove(selectionAmountKey(requestId))
            .remove(selectionTimeKey(requestId))
            .apply()
    }

    private fun clearPending(context: Context, requestId: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("pending_request_id", null) != requestId) return
        prefs.edit()
            .remove("pending_request_id")
            .remove("pending_sale_id")
            .remove("pending_type")
            .remove("pending_refund_total")
            .remove("pending_suggested_method")
            .remove("pending_actor")
            .remove("pending_created_at")
            .apply()
    }

    private fun isExpired(createdAt: Long): Boolean =
        createdAt <= 0L || System.currentTimeMillis() - createdAt > TTL_MILLIS

    private fun selectionMethodKey(requestId: String) = "selection_method_$requestId"
    private fun selectionAmountKey(requestId: String) = "selection_amount_$requestId"
    private fun selectionTimeKey(requestId: String) = "selection_time_$requestId"
}

class ManualRefundMethodActivityV135 : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureRegisterSystemBars(window)
        setContent {
            MaterialTheme {
                var pending by remember {
                    mutableStateOf(ManualRefundFallbackRuntimeV135.pending(applicationContext))
                }
                var message by remember { mutableStateOf<String?>(null) }
                ManualRefundMethodScreenV135(
                    pending = pending,
                    message = message,
                    onSelect = { method ->
                        val target = pending
                        if (target == null) {
                            message = "返金方法選択要求がありません"
                        } else {
                            runCatching {
                                ManualRefundFallbackRuntimeV135.select(applicationContext, target, method)
                            }.onSuccess {
                                pending = null
                                finish()
                            }.onFailure { error ->
                                message = error.message ?: "返金方法を保存できませんでした"
                            }
                        }
                    },
                    onCancel = {
                        pending?.let { ManualRefundFallbackRuntimeV135.cancelPending(applicationContext, it.requestId) }
                        finish()
                    },
                )
            }
        }
    }
}

@Composable
private fun ManualRefundMethodScreenV135(
    pending: PendingManualRefundV135?,
    message: String?,
    onSelect: (ManualRefundMethodV135) -> Unit,
    onCancel: () -> Unit,
) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("返金方法選択（責任者）", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (pending == null) {
                Text(message ?: "有効な返金方法選択要求はありません。元の返品・取消画面からやり直してください。")
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onCancel) { Text("戻る") }
                return@Column
            }

            Text("元決済の支払内訳を自動復元できないため、返金方法の指定が必要です。")
            Text("元売上 No.${pending.originalSaleId} / ${pending.reversalType.displayName}")
            Text(
                "返金予定 ${NumberFormat.getCurrencyInstance(Locale.JAPAN).format(pending.refundTotal)}",
                fontWeight = FontWeight.Bold,
            )
            Text("この画面は返金方法の記録です。カード等は決済端末側の返金操作を完了してから選択してください。")
            if (pending.suggestedMethod != "OTHER") {
                Text("旧データからの推定: ${pending.suggestedMethod}")
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            ManualRefundMethodV135.entries.chunked(3).forEach { rowMethods ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    rowMethods.forEach { method ->
                        Button(
                            onClick = { onSelect(method) },
                            modifier = Modifier.weight(1f),
                        ) { Text(method.displayName) }
                    }
                    repeat(3 - rowMethods.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("選択せず戻る")
            }
            Text("選択後は元の返品・取消画面で同じ操作をもう一度実行してください。")
        }
    }
}

private object ManualRefundFallbackAuditV135 {
    fun record(
        context: Context,
        eventType: String,
        referenceId: Long,
        detail: String,
        operatorName: String,
    ): Boolean = runCatching {
        val helper = RegisterDatabase(context.applicationContext)
        try {
            val database = helper.writableDatabase
            ensureAuditTable(database)
            database.insertOrThrow(
                "operation_audit",
                null,
                ContentValues().apply {
                    put("event_type", eventType)
                    put("reference_id", referenceId)
                    put("detail", detail)
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
