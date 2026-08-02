package jp.co.tenposinfo.register

import android.content.Context
import java.time.LocalDate

enum class OperationsAction(
    val permission: RegisterPermission,
    val managerApprovalRequired: Boolean,
) {
    DAILY_SALES(RegisterPermission.VIEW_SALES, false),
    SETTLEMENT(RegisterPermission.SETTLEMENT, false),
    CASH_MOVEMENT(RegisterPermission.CASH_MOVEMENT, false),
    REVERSAL(RegisterPermission.REVERSAL, true),
}

object OperationsAuthorizationPolicy {
    fun canAccess(operator: AuthenticatedOperator?, action: OperationsAction): Boolean =
        operator?.allows(action.permission) == true

    fun requiresManagerApproval(action: OperationsAction, settlementType: SettlementReportType? = null): Boolean =
        action.managerApprovalRequired || settlementType == SettlementReportType.Z_SETTLEMENT
}

object OperationsActorFormatter {
    fun direct(operator: AuthenticatedOperator): String = operator.name

    fun approved(operator: AuthenticatedOperator, managerName: String): String =
        "${operator.name}（承認:${managerName}）"
}

/**
 * 管理操作の書込直前に、現在のログインセッション・個別権限・責任者PINを再検証する。
 * UI表示だけの権限制御に依存せず、停止済み担当者や失効セッションからの書込を拒否する。
 * 同一プロセス内の連打はOperationExecutionGuardで拒否し、永続的な重複はDB操作キーで拒否する。
 */
class SecureOperationsCoordinator(
    context: Context,
    private val store: OperationsStore,
) {
    private val appContext = context.applicationContext
    private val executionGuard = OperationExecutionGuard()

    fun startBusinessDay(businessDate: LocalDate, openingCash: Long): Long =
        executionGuard.runExclusive("BUSINESS_OPEN:$businessDate", "営業開始を処理中です") {
            val operator = requireOperator(OperationsAction.SETTLEMENT)
            store.startBusinessDay(businessDate, openingCash, OperationsActorFormatter.direct(operator))
        }

    fun endBusinessDay(actualCash: Long, managerPin: String): Long {
        val businessDate = store.activeBusinessSession()?.businessDate ?: LocalDate.now().toString()
        return executionGuard.runExclusive("BUSINESS_CLOSE:$businessDate", "営業終了を処理中です") {
            val operator = requireOperator(OperationsAction.SETTLEMENT)
            val managerName = requireManagerName(managerPin)
            store.endBusinessDay(
                actualCash,
                OperationsActorFormatter.approved(operator, managerName),
            )
        }
    }

    fun recordCashMovement(type: CashMovementType, amount: Long, reason: String): Long {
        val operator = requireOperator(OperationsAction.CASH_MOVEMENT)
        return store.recordCashMovement(type, amount, reason, OperationsActorFormatter.direct(operator))
    }

    fun recordSettlement(
        type: SettlementReportType,
        actualCash: Long?,
        managerPin: String,
    ): Long {
        val businessDate = store.activeBusinessSession()?.businessDate
            ?: throw IllegalStateException("営業中の営業日がありません")
        val persistentKey = if (type == SettlementReportType.Z_SETTLEMENT) {
            "Z_SETTLEMENT:$businessDate"
        } else {
            null
        }
        val executionKey = persistentKey ?: "X_INSPECTION:$businessDate"
        return executionGuard.runExclusive(executionKey, "点検・精算を処理中です") {
            val operator = requireOperator(OperationsAction.SETTLEMENT)
            val actor = if (OperationsAuthorizationPolicy.requiresManagerApproval(OperationsAction.SETTLEMENT, type)) {
                OperationsActorFormatter.approved(operator, requireManagerName(managerPin))
            } else {
                OperationsActorFormatter.direct(operator)
            }
            store.recordSettlement(type, actualCash, actor)
        }
    }

    fun createReversal(
        originalSaleId: Long,
        type: ReversalType,
        requestedQuantities: Map<Long, Int>,
        reason: String,
        managerPin: String,
        paperWidthMm: Int,
        requestId: String,
    ): PartialReversalResult {
        val executionKey = OperationsIdempotencyPolicy.reversalKey(originalSaleId)
        return executionGuard.runExclusive(executionKey, "返品・取消を処理中です") {
            val operator = requireOperator(OperationsAction.REVERSAL)
            val managerName = requireManagerName(managerPin)
            store.createReversal(
                originalSaleId = originalSaleId,
                type = type,
                requestedQuantities = requestedQuantities,
                reason = reason,
                operatorName = OperationsActorFormatter.approved(operator, managerName),
                paperWidthMm = paperWidthMm,
                requestId = requestId,
            )
        }
    }

    fun createFullReversal(
        originalSaleId: Long,
        type: ReversalType,
        reason: String,
        managerPin: String,
    ): Long = createReversal(
        originalSaleId = originalSaleId,
        type = type,
        requestedQuantities = if (type == ReversalType.RETURN) {
            store.loadReturnableLines(originalSaleId).associate { it.saleItemId to it.remainingQuantity }
        } else emptyMap(),
        reason = reason,
        managerPin = managerPin,
        paperWidthMm = 80,
        requestId = "FULL-${type.name}",
    ).reversalId

    private fun requireOperator(action: OperationsAction): AuthenticatedOperator {
        val operator = OperatorSessionRegistry.current(appContext)
            ?: throw SecurityException("ログインセッションが失効しています。販売画面から再ログインしてください")
        if (!OperationsAuthorizationPolicy.canAccess(operator, action)) {
            throw SecurityException("${action.permission.displayName}の権限がありません")
        }
        return operator
    }

    private fun requireManagerName(pin: String): String {
        require(pin.isNotBlank()) { "責任者PINを入力してください" }
        return AdminSettingsStore(appContext).use { it.managerNameForPin(pin) }
            ?: throw SecurityException("責任者PINが違います")
    }
}
