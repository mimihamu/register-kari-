package jp.co.tenposinfo.register

/**
 * Z精算直前の未処理確認。
 * 未会計伝票は売上確定前のため精算を禁止し、未印刷データは責任者が明示確認した場合のみ許可する。
 */
data class ZSettlementPreflightResult(
    val mayProceed: Boolean,
    val heldTickets: Int,
    val pendingPrints: Int,
    val requiresPendingPrintAcknowledgement: Boolean,
    val message: String?,
)

object ZSettlementPreflightPolicy {
    fun evaluate(
        heldTickets: Int,
        pendingPrints: Int,
        pendingPrintsAcknowledged: Boolean,
    ): ZSettlementPreflightResult {
        require(heldTickets >= 0) { "未会計伝票件数が不正です" }
        require(pendingPrints >= 0) { "未印刷件数が不正です" }

        if (heldTickets > 0) {
            return ZSettlementPreflightResult(
                mayProceed = false,
                heldTickets = heldTickets,
                pendingPrints = pendingPrints,
                requiresPendingPrintAcknowledgement = pendingPrints > 0,
                message = "未会計伝票が${heldTickets}件あります。会計または伝票取消を完了してからZ精算してください",
            )
        }

        if (pendingPrints > 0 && !pendingPrintsAcknowledged) {
            return ZSettlementPreflightResult(
                mayProceed = false,
                heldTickets = 0,
                pendingPrints = pendingPrints,
                requiresPendingPrintAcknowledgement = true,
                message = "未印刷データが${pendingPrints}件あります。内容を確認し、未印刷のまま精算することを承認してください",
            )
        }

        return ZSettlementPreflightResult(
            mayProceed = true,
            heldTickets = 0,
            pendingPrints = pendingPrints,
            requiresPendingPrintAcknowledgement = pendingPrints > 0,
            message = null,
        )
    }
}

/** 旧SETTLEMENT権限を、X点検とZ精算へ安全に展開する互換レイヤー。 */
object RegisterPermissionCompatibilityV026 {
    val selectablePermissions: List<RegisterPermission>
        get() = RegisterPermission.entries.filterNot { it == RegisterPermission.SETTLEMENT }

    fun expand(stored: Set<RegisterPermission>): Set<RegisterPermission> {
        if (RegisterPermission.SETTLEMENT !in stored) return stored
        return stored + RegisterPermission.X_INSPECTION + RegisterPermission.Z_SETTLEMENT
    }

    fun normalizeForSave(selected: Set<RegisterPermission>): Set<RegisterPermission> =
        selected - RegisterPermission.SETTLEMENT
}
