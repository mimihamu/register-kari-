package jp.co.tenposinfo.register

import android.content.Context
import java.time.LocalDate

enum class OperationsAction(
    val permission: RegisterPermission,
    val managerApprovalRequired: Boolean,
) {
    DAILY_SALES(RegisterPermission.VIEW_SALES, false),
    X_INSPECTION(RegisterPermission.X_INSPECTION, false),
    Z_SETTLEMENT(RegisterPermission.Z_SETTLEMENT, false),
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
            val operator = requireOperator(OperationsAction.Z_SETTLEMENT)
            store.startBusinessDay(businessDate, openingCash, OperationsActorFormatter.direct(operator))
        }

    fun recordCashMovement(type: CashMovementType, amount: Long, reason: String): Long {
        val operator = requireOperator(OperationsAction.CASH_MOVEMENT)
        return store.recordCashMovement(type, amount, reason, OperationsActorFormatter.direct(operator))
    }

    fun recordSettlement(
        type: SettlementReportType,
        actualCash: Long?,
        managerPin: String,
        pendingPrintsAcknowledged: Boolean = false,
    ): Long {
        SettlementActualCashSafetyV105.validate(type, actualCash)
        val session = store.activeBusinessSession()
            ?: throw IllegalStateException("営業中の営業日がありません")
        val businessDate = session.businessDate
        val persistentKey = OperationsIdempotencyPolicy.settlementKey(type, session.id)
        val executionKey = persistentKey ?: "X_INSPECTION:SESSION:${session.id}"
        val action = when (type) {
            SettlementReportType.X_INSPECTION -> OperationsAction.X_INSPECTION
            SettlementReportType.Z_SETTLEMENT -> OperationsAction.Z_SETTLEMENT
        }
        var backupActor = "責任者"
        val settlementId = executionGuard.runExclusive(executionKey, "点検・精算を処理中です") {
            val operator = requireOperator(action)
            val actor = if (OperationsAuthorizationPolicy.requiresManagerApproval(action, type)) {
                OperationsActorFormatter.approved(operator, requireManagerName(managerPin))
            } else {
                OperationsActorFormatter.direct(operator)
            }
            backupActor = actor
            store.recordSettlement(type, actualCash, actor, pendingPrintsAcknowledged)
        }

        runCatching { AutomaticPrintScheduler.enqueueNow(appContext) }

        if (AutoBackupTriggerPolicy.shouldEnqueue(type, settlementCommitted = true)) {
            runCatching {
                AutoBackupScheduler.enqueueZSettlement(
                    context = appContext,
                    businessDate = businessDate,
                    businessSessionId = session.id,
                    settlementId = settlementId,
                    actorName = backupActor,
                )
            }.onFailure { error ->
                runCatching {
                    AutoBackupStatusStore(appContext).completed(
                        BackupCreationReason.Z_SETTLEMENT,
                        AutoBackupResultState.FAILED,
                        error.message ?: error.javaClass.simpleName,
                    )
                    AutoBackupAudit.record(
                        appContext,
                        "DATA_BACKUP_AUTO_FAILED",
                        "Z精算確定後の要求登録に失敗: ${error.message}",
                        backupActor,
                        settlementId,
                    )
                }
            }
        }
        return settlementId
    }

    fun reprintSettlement(
        reportId: Long,
        managerPin: String,
    ): Long {
        val record = store.settlementById(reportId)
            ?: throw IllegalArgumentException("点検・精算履歴No.${reportId}が見つかりません")
        val action = when (record.type) {
            SettlementReportType.X_INSPECTION -> OperationsAction.X_INSPECTION
            SettlementReportType.Z_SETTLEMENT -> OperationsAction.Z_SETTLEMENT
        }
        return executionGuard.runExclusive(
            "SETTLEMENT_REPRINT:${reportId}",
            "点検・精算票を再印字処理中です",
        ) {
            val operator = requireOperator(action)
            val actor = if (record.type == SettlementReportType.Z_SETTLEMENT) {
                OperationsActorFormatter.approved(operator, requireManagerName(managerPin))
            } else {
                OperationsActorFormatter.direct(operator)
            }
            val jobId = store.reprintSettlement(reportId, actor)
            runCatching { AutomaticPrintScheduler.enqueueNow(appContext) }
            jobId
        }
    }

    fun createReversal(
        originalSaleId: Long,
        type: ReversalType,
        requestedQuantities: Map<Long, Int>,
        reason: String,
        managerPin: String,
        requestId: String,
    ): PartialReversalResult {
        val executionKey = OperationsIdempotencyPolicy.reversalKey(originalSaleId)
        return executionGuard.runExclusive(executionKey, "返品・取消を処理中です") {
            val operator = requireOperator(OperationsAction.REVERSAL)
            val managerName = requireManagerName(managerPin)
            val actor = OperationsActorFormatter.approved(operator, managerName)
            val refundContext = ApprovedRefundContextV135(
                originalSaleId = originalSaleId,
                reversalType = type,
                requestId = requestId,
                actorName = actor,
            )
            val result = ManualRefundFallbackRuntimeV135.withApprovedContext(
                context = appContext,
                approvedContext = refundContext,
            ) {
                store.createReversal(
                    originalSaleId = originalSaleId,
                    type = type,
                    requestedQuantities = requestedQuantities,
                    reason = reason,
                    operatorName = actor,
                    requestId = requestId,
                )
            }
            ManualRefundFallbackRuntimeV135.complete(appContext, refundContext, result.refundAmount)
            result
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
