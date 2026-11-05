package jp.co.tenposinfo.register

/** A persisted payment allocation must never overstate the cart total. */
internal object PaymentDraftIntegrityV181 {
    const val REVIEW_MESSAGE =
        "支払入力の整合性を確認できません。再決済せず売上履歴を確認し、責任者に連絡してください"

    fun validAllocations(allocations: List<PaymentAllocation>, total: Long): Boolean {
        if (total < 0L) return false
        return runCatching {
            var paid = 0L
            for (allocation in allocations) {
                require(allocation.appliedAmount > 0L)
                require(allocation.receivedAmount >= allocation.appliedAmount)
                paid = Math.addExact(paid, allocation.appliedAmount)
                require(paid <= total)
            }
            true
        }.getOrDefault(false)
    }
}
