package jp.co.tenposinfo.register

import java.util.concurrent.ConcurrentHashMap

object OperationsIdempotencyPolicy {
    fun reversalKey(originalSaleId: Long): String {
        require(originalSaleId > 0) { "originalSaleId must be positive" }
        return "REVERSAL:$originalSaleId"
    }

    fun reversalRequestKey(type: ReversalType, originalSaleId: Long, requestId: String): String =
        PartialReturnPolicy.operationKey(type, originalSaleId, requestId)

    fun settlementKey(type: SettlementReportType, businessSessionId: Long): String? {
        require(businessSessionId > 0L) { "businessSessionId must be positive" }
        return when (type) {
            SettlementReportType.X_INSPECTION -> null
            SettlementReportType.Z_SETTLEMENT -> "Z_SETTLEMENT:SESSION:$businessSessionId"
        }
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
