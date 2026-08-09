package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * v0.79 点検・精算の整合確認を既存の追記専用 operation_audit へ記録する。
 * 売上・税・支払・点検精算snapshot・印刷ジョブは更新しない。
 */
object SettlementReconciliationAuditPolicyV079 {
    const val EVENT_PREFIX = "SETTLEMENT_RECONCILIATION_"

    fun eventType(result: SettlementReconciliationResult): String =
        EVENT_PREFIX + result.severity.name

    fun detail(result: SettlementReconciliationResult): String = buildString {
        append(result.reportType.displayName)
        append(" No.")
        append(result.reportId)
        append(" / 営業日 ")
        append(result.businessDate)
        append(" / セッションNo.")
        append(result.businessSessionId)
        append(" / 判定 ")
        append(result.severity.name)
        append(" / snapshot ")
        append(if (result.fullSnapshot) "FULL" else "LEGACY")
        append(" / 差異 ")
        append(result.differences.size)
        append("件")
        if (result.differences.isNotEmpty()) {
            append(" / ")
            append(
                result.differences.joinToString("; ") { field ->
                    "${field.label}: ${field.savedValue} -> ${field.currentValue}"
                },
            )
        }
    }
}

class SettlementReconciliationAuditStoreV079(context: Context) : AutoCloseable {
    private val baseDatabase = RegisterDatabase(context.applicationContext)
    private val db: SQLiteDatabase = baseDatabase.writableDatabase

    fun append(
        result: SettlementReconciliationResult,
        operatorName: String,
        createdAt: Long = System.currentTimeMillis(),
    ): Long {
        require(operatorName.isNotBlank()) { "整合確認の担当者を特定できません" }
        val values = ContentValues().apply {
            put("event_type", SettlementReconciliationAuditPolicyV079.eventType(result))
            put("reference_id", result.reportId)
            put("detail", SettlementReconciliationAuditPolicyV079.detail(result))
            put("operator_name", operatorName.trim())
            put("created_at", createdAt)
        }
        return db.insertOrThrow("operation_audit", null, values)
    }

    override fun close() = baseDatabase.close()
}
