package jp.co.tenposinfo.register

/** A tender may only be removed through a durable, audited cancellation. */
internal object PaymentTenderCancellationPolicyV187 {
    fun needsReversalVerification(method: PaymentMethod): Boolean =
        method != PaymentMethod.CASH

    fun requireReversalVerification(method: PaymentMethod, verified: Boolean) {
        require(!needsReversalVerification(method) || verified) {
            "外部決済の取消・返金または金券の返却を確認してください"
        }
    }

    /** The ordinary draft writer may add tender, but cannot delete or edit old entries. */
    fun requireAppendOnly(
        persisted: List<PaymentAllocation>,
        requested: List<PaymentAllocation>,
    ) {
        require(requested.size >= persisted.size &&
            requested.take(persisted.size) == persisted
        ) { "登録済みの支払は直接変更できません。取消履歴を残してから操作してください" }
    }
}
