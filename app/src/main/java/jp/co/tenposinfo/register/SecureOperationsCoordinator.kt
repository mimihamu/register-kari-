package jp.co.tenposinfo.register

import android.content.Context

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
 */
class SecureOperationsCoordinator(
    context: Context,
    private val store: OperationsStore,
) {
    private val appContext = context.applicationContext

    fun recordCashMovement(type: CashMovementType, amount: Long, reason: String): Long {
        val operator = requireOperator(OperationsAction.CASH_MOVEMENT)
        return store.recordCashMovement(type, amount, reason, OperationsActorFormatter.direct(operator))
    }

    fun recordSettlement(
        type: SettlementReportType,
        actualCash: Long?,
        managerPin: String,
    ): Long {
        val operator = requireOperator(OperationsAction.SETTLEMENT)
        val actor = if (OperationsAuthorizationPolicy.requiresManagerApproval(OperationsAction.SETTLEMENT, type)) {
            OperationsActorFormatter.approved(operator, requireManagerName(managerPin))
        } else {
            OperationsActorFormatter.direct(operator)
        }
        return store.recordSettlement(type, actualCash, actor)
    }

    fun createFullReversal(
        originalSaleId: Long,
        type: ReversalType,
        reason: String,
        managerPin: String,
    ): Long {
        val operator = requireOperator(OperationsAction.REVERSAL)
        val managerName = requireManagerName(managerPin)
        return store.createFullReversal(
            originalSaleId,
            type,
            reason,
            OperationsActorFormatter.approved(operator, managerName),
        )
    }

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
