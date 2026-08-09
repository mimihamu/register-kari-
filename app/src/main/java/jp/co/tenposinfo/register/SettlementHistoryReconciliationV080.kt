package jp.co.tenposinfo.register

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** v0.80: レスポンシブSCR-520からv0.78/v0.79の整合確認と監査を安全に実行する。 */
object SettlementHistoryReconciliationPolicyV080 {
    fun canReconcile(
        permissions: Set<RegisterPermission>,
        type: SettlementReportType,
    ): Boolean =
        RegisterPermission.VIEW_SALES in permissions &&
            SettlementHistoryPolicyV027.permissionFor(type) in permissions
}

@Composable
internal fun SettlementHistoryReconciliationActionV080(
    selected: SettlementRecord,
    permissions: Set<RegisterPermission>,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val store = remember { OperationsStore(appContext) }
    val auditStore = remember { SettlementReconciliationAuditStoreV079(appContext) }
    var reconciliation by remember(selected.id) { mutableStateOf<SettlementReconciliationResult?>(null) }
    var reconciliationError by remember(selected.id) { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            auditStore.close()
            store.close()
        }
    }

    val canReconcile = SettlementHistoryReconciliationPolicyV080.canReconcile(permissions, selected.type)
    Spacer(Modifier.height(6.dp))
    OutlinedButton(
        onClick = {
            reconciliation = null
            reconciliationError = null
            val result = runCatching {
                val current = OperatorSessionRegistry.current(appContext)
                    ?: error("ログインセッションが失効しています")
                val requiredPermission = SettlementHistoryPolicyV027.permissionFor(selected.type)
                check(current.allows(RegisterPermission.VIEW_SALES)) { "売上参照の権限がありません" }
                check(current.allows(requiredPermission)) { "${requiredPermission.displayName}の権限がありません" }

                val latestRecord = store.settlementById(selected.id)
                    ?: error("点検・精算履歴No.${selected.id}が見つかりません")
                check(latestRecord.businessSessionId == selected.businessSessionId) { "営業セッションが一致しません" }
                check(latestRecord.type == selected.type) { "点検・精算種別が一致しません" }

                val comparison = SettlementReconciliationPolicyV078.compare(
                    latestRecord,
                    store.summaryForSession(latestRecord.businessSessionId),
                )
                // v0.79のfail-closed方針を維持。監査INSERT成功後のみ結果を表示する。
                auditStore.append(comparison, current.name)
                comparison.copy(
                    message = comparison.message + "\n整合確認を監査履歴へ記録しました。",
                )
            }
            result.onSuccess { reconciliation = it }
                .onFailure { reconciliationError = it.message ?: "整合確認に失敗しました" }
        },
        enabled = canReconcile,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("保存値と現在DBを照合", fontWeight = FontWeight.Bold)
    }
    Text(
        when {
            canReconcile -> "照合を実行すると結果（OK / INFO / ALERT）を追記専用の監査履歴へ記録します。"
            RegisterPermission.VIEW_SALES !in permissions -> "整合確認には売上参照権限が必要です。"
            else -> "${SettlementHistoryPolicyV027.permissionFor(selected.type).displayName}の権限が必要です。"
        },
        color = Color.DarkGray,
    )
    Spacer(Modifier.height(6.dp))
    OutlinedButton(
        onClick = {
            context.startActivity(Intent(context, SettlementReconciliationAuditLedgerActivityV081::class.java))
        },
        enabled = SettlementReconciliationAuditLedgerPolicyV081.canView(permissions),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("整合確認監査台帳を開く", fontWeight = FontWeight.Bold)
    }
    Text(
        "過去の整合確認結果をOK / INFO / ALERT、レポートNo.、担当者、営業日・セッションで検索できます。",
        color = Color.DarkGray,
    )

    reconciliationError?.let { error ->
        Spacer(Modifier.height(4.dp))
        Text(error, color = Color(0xFFC62828), fontWeight = FontWeight.SemiBold)
    }

    reconciliation?.let { result ->
        AlertDialog(
            onDismissRequest = { reconciliation = null },
            title = { Text("整合確認 ${result.severity.name}") },
            text = {
                Column {
                    Text(result.message, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (result.fullSnapshot) "保存snapshot: 完全保存" else "保存snapshot: 旧形式（比較範囲限定）",
                        color = Color.DarkGray,
                    )
                    Spacer(Modifier.height(6.dp))
                    result.fields.forEach { field ->
                        Text(
                            "${field.label}: 保存 ${field.savedValue} / 現在 ${field.currentValue}" +
                                if (field.matches) "" else "  ← 差異",
                            color = if (field.matches) Color.DarkGray else Color(0xFFC62828),
                            fontWeight = if (field.matches) FontWeight.Normal else FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "未印刷・未会計伝票は営業セッション単位で現在値を再現できないため照合対象外です。",
                        color = Color.DarkGray,
                    )
                }
            },
            confirmButton = {
                OutlinedButton(onClick = { reconciliation = null }) { Text("閉じる") }
            },
        )
    }
}
