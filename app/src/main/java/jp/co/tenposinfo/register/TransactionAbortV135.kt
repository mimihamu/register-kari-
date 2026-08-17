package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

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
            if (SchemaMigration.tableExists(db, "sale_guest_count_pending_v135")) {
                // 中止した取引の客数を次取引へ持ち越さない。
                db.delete("sale_guest_count_pending_v135", "id = 1", null)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return snapshot
    }

    override fun close() = database.close()
}

/**
 * 販売画面のv1.35追加操作。
 *
 * 既存の「取引中止」領域を二分して「客数」と「取引中止」を提供する。
 * 客数は会計件数から推測せず、担当者が明示入力した値だけを次の確定売上へ保存する。
 */
@Composable
internal fun TransactionAbortButtonV135(
    items: List<CartItem>,
    modifier: Modifier = Modifier,
    onAbortCommitted: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val guestRuntime = remember(context.applicationContext) {
        SaleGuestCountRuntimeV135(context.applicationContext)
    }
    var guestCount by remember { mutableStateOf(guestRuntime.current()) }
    var guestDialogOpen by remember { mutableStateOf(false) }
    var guestInput by remember { mutableStateOf("") }
    var guestError by remember { mutableStateOf<String?>(null) }
    var abortDialogOpen by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(guestRuntime) {
        onDispose { guestRuntime.close() }
    }
    LaunchedEffect(items.isEmpty()) {
        if (items.isEmpty() && guestCount != 0) {
            guestRuntime.clear()
            guestCount = 0
        }
    }

    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(
            onClick = {
                guestInput = guestCount.takeIf { it > 0 }?.toString().orEmpty()
                guestError = null
                guestDialogOpen = true
            },
            enabled = items.isNotEmpty(),
            modifier = Modifier.weight(1f).fillMaxHeight(),
        ) {
            Text(
                if (guestCount > 0) "客数 ${guestCount}名" else "客数",
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                maxLines = 1,
            )
        }
        Button(
            onClick = {
                errorMessage = null
                reason = ""
                abortDialogOpen = true
            },
            enabled = items.isNotEmpty(),
            modifier = Modifier.weight(1f).fillMaxHeight(),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = androidx.compose.ui.graphics.Color(0xFFFBE9E7),
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text("取引中止", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1)
        }
    }

    if (guestDialogOpen) {
        AlertDialog(
            onDismissRequest = {
                guestError = null
                guestDialogOpen = false
            },
            title = { Text("客数を入力") },
            text = {
                Column {
                    Text("この取引の実際のお客様人数を入力します。未入力の場合は0名として保存し、会計件数からは推測しません。")
                    OutlinedTextField(
                        value = guestInput,
                        onValueChange = {
                            guestInput = it.filter(Char::isDigit).take(3)
                            guestError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("客数（1〜999名）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    guestError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        runCatching {
                            guestRuntime.set(guestInput.toIntOrNull() ?: 0)
                        }.onSuccess {
                            guestCount = guestInput.toInt()
                            guestDialogOpen = false
                            guestError = null
                        }.onFailure { error ->
                            guestError = error.message ?: "客数を保存できませんでした"
                        }
                    },
                    enabled = guestInput.toIntOrNull() != null,
                ) { Text("客数を設定") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            guestRuntime.clear()
                            guestCount = 0
                            guestInput = ""
                            guestError = null
                            guestDialogOpen = false
                        },
                    ) { Text("未入力に戻す") }
                    TextButton(
                        onClick = {
                            guestError = null
                            guestDialogOpen = false
                        },
                    ) { Text("戻る") }
                }
            },
        )
    }

    if (!abortDialogOpen) return

    AlertDialog(
        onDismissRequest = {
            errorMessage = null
            abortDialogOpen = false
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
                        guestCount = 0
                        onAbortCommitted()
                        abortDialogOpen = false
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
                    abortDialogOpen = false
                },
            ) { Text("戻る") }
        },
    )
}
