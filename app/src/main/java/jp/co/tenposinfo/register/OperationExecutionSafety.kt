package jp.co.tenposinfo.register

import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

object OperationsIdempotencyPolicy {
    fun reversalKey(originalSaleId: Long): String {
        require(originalSaleId > 0) { "originalSaleId must be positive" }
        return "REVERSAL:$originalSaleId"
    }

    fun settlementKey(type: SettlementReportType, businessDate: LocalDate): String? = when (type) {
        SettlementReportType.X_INSPECTION -> null
        SettlementReportType.Z_SETTLEMENT -> "Z_SETTLEMENT:$businessDate"
    }
}

/**
 * 同一プロセス内で同じ管理操作が連打・多重起動されることを防止する。
 * 永続的な二重実行防止はOperationsStoreの操作キーとSQLiteトランザクションが担当する。
 */
class OperationExecutionGuard {
    private val activeKeys = ConcurrentHashMap.newKeySet<String>()

    fun <T> runExclusive(
        key: String,
        duplicateMessage: String,
        block: () -> T,
    ): T {
        require(key.isNotBlank()) { "key must not be blank" }
        if (!activeKeys.add(key)) throw IllegalStateException(duplicateMessage)
        return try {
            block()
        } finally {
            activeKeys.remove(key)
        }
    }
}
