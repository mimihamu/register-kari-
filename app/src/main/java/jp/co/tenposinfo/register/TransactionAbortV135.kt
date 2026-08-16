package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

internal data class TransactionAbortSnapshotV135(
    val lineCount: Int,
    val totalQuantity: Int,
    val grossAmount: Long,
)

internal object TransactionAbortPolicyV135 {
    fun normalizedReason(reason: String): String {
        val normalized = reason.trim()
        require(normalized.isNotEmpty()) { "取引中止理由を入力してください" }
        return normalized
    }

    fun snapshot(items: List<CartItem>): TransactionAbortSnapshotV135 {
        require(items.isNotEmpty()) { "中止する未確定取引がありません" }
        return TransactionAbortSnapshotV135(
            lineCount = items.size,
            totalQuantity = items.sumOf { it.quantity },
            grossAmount = TaxEngine.calculate(items).grossAmount,
        )
    }
}

/**
 * COR-004: 未確定取引の中止。
 *
 * operation_audit への監査記録と作業中カート削除を同一SQLite transactionで確定する。
 * 確定売上 sales / sale_items / sale_payments は一切作成・更新しない。
 */
internal class TransactionAbortCoordinatorV135(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val database = RegisterDatabase(appContext)

    init {
        // operation_audit / business_sessions を既存業務機能と同じschemaで用意する。
        OperationsStore(appContext).close()
    }

    fun abort(items: List<CartItem>, reason: String): TransactionAbortSnapshotV135 {
        val operator = OperatorSessionRegistry.current(appContext)
            ?: throw SecurityException("ログインセッションが失効しています。再ログインしてから取引中止してください")
        val normalizedReason = TransactionAbortPolicyV135.normalizedReason(reason)
        val snapshot = TransactionAbortPolicyV135.snapshot(items)
        val now = System.currentTimeMillis()
        val db = database.writableDatabase
        val businessDate = db.rawQuery(
            "SELECT business_date FROM business_sessions WHERE status = ? ORDER BY opened_at DESC LIMIT 1",
            arrayOf(BusinessSessionStatus.OPEN.name),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else "未開始" }

        db.beginTransaction()
        try {
            // 監査を先に書き、後続の作業カート削除まで成功した時だけtransactionをcommitする。
            db.insertOrThrow(
                "operation_audit",
                null,
                ContentValues().apply {
                    put("event_type", "TRANSACTION_ABORT")
                    put("reference_id", 0L)
                    put(
                        "detail",
                        "取引中止 / 営業日:$businessDate / 理由:$normalizedReason / 明細:${snapshot.lineCount} / 点数:${snapshot.totalQuantity} / 金額:${snapshot.grossAmount}円",
                    )
                    put("operator_name", operator.name)
                    put("created_at", now)
                },
            )
            // cart_items は未確定の作業領域。確定取引テーブルは変更しない。
            db.delete("cart_items", null, null)
            db.delete(
                "line_tax_snapshots",
                "scope = ? AND owner_id = ?",
                arrayOf(LineTaxSnapshotStore.SCOPE_CART, "0"),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return snapshot
    }

    override fun close() = database.close()
}

/**
 * 販売画面の既存「取引中止」ボタンをCOR-004準拠にする。
 * 監査transaction成功前には onAbortCommitted を絶対に呼ばない。
 */
@Composable
internal fun TransactionAbortButtonV135(
    items: List<CartItem>,
    modifier: Modifier = Modifier,
    onAbortCommitted: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var dialogOpen by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Button(
        onClick = {
            errorMessage = null
            reason = ""
            dialogOpen = true
        },
        enabled = items.isNotEmpty(),
        modifier = modifier,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = androidx.compose.ui.graphics.Color(0xFFFBE9E7),
            contentColor = MaterialTheme.colorScheme.error,
        ),
    ) {
        Text("取引中止", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1)
    }

    if (!dialogOpen) return

    AlertDialog(
        onDismissRequest = {
            errorMessage = null
            dialogOpen = false
        },
        title = { Text("未確定取引を中止") },
        text = {
            Column {
                Text("売上には計上せず、現在の明細を破棄します。中止理由と担当者を監査ログへ記録します。")
                OutlinedTextField(
                    value = reason,
                    onValueChange = {
                        reason = it.take(120)
                        errorMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("取引中止理由（必須）") },
                    supportingText = { Text("例：注文取消、入力やり直し、お客様都合") },
                    minLines = 2,
                    maxLines = 4,
                )
                errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cartSnapshot = items.toList()
                    runCatching {
                        TransactionAbortCoordinatorV135(context.applicationContext).use { coordinator ->
                            coordinator.abort(cartSnapshot, reason)
                        }
                    }.onSuccess {
                        // DB上の監査＋作業カート削除がcommit済みになってから画面上のカートを消す。
                        onAbortCommitted()
                        dialogOpen = false
                        reason = ""
                        errorMessage = null
                    }.onFailure { error ->
                        errorMessage = error.message ?: "取引中止を記録できませんでした。明細は保持します"
                    }
                },
                enabled = reason.isNotBlank() && items.isNotEmpty(),
            ) { Text("中止を確定") }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    errorMessage = null
                    dialogOpen = false
                },
            ) { Text("戻る") }
        },
    )
}
