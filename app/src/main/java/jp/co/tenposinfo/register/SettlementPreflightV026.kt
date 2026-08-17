package jp.co.tenposinfo.register

/** REP-003で表示する精算前確認項目。 */
enum class SettlementPreflightCategoryV135(val displayName: String) {
    OPEN_TICKETS("未会計伝票"),
    INCOMPLETE_PAYMENT("未完了決済"),
    PENDING_PRINT("未印刷"),
    BACKUP_FAILURE("バックアップ失敗"),
    ACTUAL_CASH_MISSING("現金実査未入力"),
}

/**
 * 精算前警告ごとの継続可否設定。
 * BLOCK は解消必須、ACKNOWLEDGE は責任者の明示確認後のみ継続可能。
 */
enum class SettlementPreflightContinuationV135(val displayName: String) {
    BLOCK("継続不可"),
    ACKNOWLEDGE("確認後に継続可"),
}

data class SettlementPreflightItemV135(
    val category: SettlementPreflightCategoryV135,
    val active: Boolean,
    val detail: String,
    val continuation: SettlementPreflightContinuationV135,
    val acknowledged: Boolean = false,
) {
    val mayProceed: Boolean
        get() = !active || when (continuation) {
            SettlementPreflightContinuationV135.BLOCK -> false
            SettlementPreflightContinuationV135.ACKNOWLEDGE -> acknowledged
        }

    val statusText: String
        get() = when {
            !active -> "問題なし"
            continuation == SettlementPreflightContinuationV135.BLOCK -> "要解消・継続不可"
            acknowledged -> "責任者確認済み"
            else -> "責任者確認が必要"
        }
}

/** Z精算直前のREP-003確認結果。旧v0.26フィールドも互換維持する。 */
data class ZSettlementPreflightResult(
    val mayProceed: Boolean,
    val heldTickets: Int,
    val pendingPrints: Int,
    val requiresPendingPrintAcknowledgement: Boolean,
    val message: String?,
    val openCartItems: Int = 0,
    val incompletePayments: Int = 0,
    val backupFailureMessage: String? = null,
    val actualCashMissing: Boolean = false,
    val requiresBackupFailureAcknowledgement: Boolean = false,
    val items: List<SettlementPreflightItemV135> = emptyList(),
)

object ZSettlementPreflightPolicy {
    /**
     * REP-003の5項目を必ず評価する。
     * 未会計・未完了決済・現金実査未入力は解消必須。
     * 未印刷・直近バックアップ失敗は責任者の明示確認後のみ継続できる。
     */
    fun evaluate(
        heldTickets: Int,
        pendingPrints: Int,
        pendingPrintsAcknowledged: Boolean,
        openCartItems: Int = 0,
        incompletePayments: Int = 0,
        backupFailureMessage: String? = null,
        actualCashEntered: Boolean = true,
        backupFailureAcknowledged: Boolean = false,
    ): ZSettlementPreflightResult {
        require(heldTickets >= 0) { "未会計伝票件数が不正です" }
        require(pendingPrints >= 0) { "未印刷件数が不正です" }
        require(openCartItems >= 0) { "販売途中明細件数が不正です" }
        require(incompletePayments >= 0) { "未完了決済件数が不正です" }

        val openTicketCount = heldTickets + if (openCartItems > 0) 1 else 0
        val items = listOf(
            SettlementPreflightItemV135(
                category = SettlementPreflightCategoryV135.OPEN_TICKETS,
                active = openTicketCount > 0,
                detail = when {
                    heldTickets > 0 && openCartItems > 0 ->
                        "未会計伝票が${heldTickets}件、販売途中取引が1件（${openCartItems}明細）あります。会計または取消を完了してください"
                    heldTickets > 0 ->
                        "未会計伝票が${heldTickets}件あります。会計または伝票取消を完了してください"
                    openCartItems > 0 ->
                        "販売途中取引が1件（${openCartItems}明細）あります。会計または取引取消を完了してください"
                    else -> "未会計伝票・販売途中取引はありません"
                },
                continuation = SettlementPreflightContinuationV135.BLOCK,
            ),
            SettlementPreflightItemV135(
                category = SettlementPreflightCategoryV135.INCOMPLETE_PAYMENT,
                active = incompletePayments > 0,
                detail = if (incompletePayments > 0) {
                    "未完了決済が${incompletePayments}件あります。決済を完了または取り消してください"
                } else {
                    "未完了決済はありません"
                },
                continuation = SettlementPreflightContinuationV135.BLOCK,
            ),
            SettlementPreflightItemV135(
                category = SettlementPreflightCategoryV135.PENDING_PRINT,
                active = pendingPrints > 0,
                detail = if (pendingPrints > 0) {
                    "未印刷データが${pendingPrints}件あります"
                } else {
                    "未印刷データはありません"
                },
                continuation = SettlementPreflightContinuationV135.ACKNOWLEDGE,
                acknowledged = pendingPrintsAcknowledged,
            ),
            SettlementPreflightItemV135(
                category = SettlementPreflightCategoryV135.BACKUP_FAILURE,
                active = !backupFailureMessage.isNullOrBlank(),
                detail = backupFailureMessage?.let { "直近バックアップ失敗: $it" }
                    ?: "直近バックアップに失敗はありません",
                continuation = SettlementPreflightContinuationV135.ACKNOWLEDGE,
                acknowledged = backupFailureAcknowledged,
            ),
            SettlementPreflightItemV135(
                category = SettlementPreflightCategoryV135.ACTUAL_CASH_MISSING,
                active = !actualCashEntered,
                detail = if (actualCashEntered) {
                    "現金実査は入力済みです"
                } else {
                    SettlementActualCashSafetyV105.Z_REQUIRED_MESSAGE
                },
                continuation = SettlementPreflightContinuationV135.BLOCK,
            ),
        )
        val blocked = items.firstOrNull { !it.mayProceed }
        return ZSettlementPreflightResult(
            mayProceed = blocked == null,
            heldTickets = heldTickets,
            pendingPrints = pendingPrints,
            requiresPendingPrintAcknowledgement = pendingPrints > 0,
            message = blocked?.detail,
            openCartItems = openCartItems,
            incompletePayments = incompletePayments,
            backupFailureMessage = backupFailureMessage,
            actualCashMissing = !actualCashEntered,
            requiresBackupFailureAcknowledgement = !backupFailureMessage.isNullOrBlank(),
            items = items,
        )
    }
}

/** 旧SETTLEMENT権限を、営業開始・X点検・Z精算へ安全に展開する互換レイヤー。 */
object RegisterPermissionCompatibilityV026 {
    val selectablePermissions: List<RegisterPermission>
        get() = RegisterPermission.entries.filterNot { it == RegisterPermission.SETTLEMENT }

    fun expand(stored: Set<RegisterPermission>): Set<RegisterPermission> {
        if (RegisterPermission.SETTLEMENT !in stored) return stored
        return stored + RegisterPermission.BUSINESS_START + RegisterPermission.X_INSPECTION + RegisterPermission.Z_SETTLEMENT
    }

    fun normalizeForSave(selected: Set<RegisterPermission>): Set<RegisterPermission> =
        selected - RegisterPermission.SETTLEMENT
}
