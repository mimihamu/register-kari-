package jp.co.tenposinfo.register

/** Safety gates for interrupted tender drafts after cart replacement / incomplete migrations. */
internal object PaymentDraftRecoveryPolicyV182 {
    const val OTHER_CART_REVIEW_MESSAGE =
        "別の取引の支払記録が残っています。記録は消去せず、売上履歴と決済端末の結果を確認してください"
    const val ORPHAN_REVIEW_MESSAGE =
        "支払記録の一部が残っています。再決済せず売上履歴と決済端末の結果を確認してください"
    const val STORAGE_REVIEW_MESSAGE =
        "支払データを読み取れません。再決済せず売上履歴を確認し、責任者に連絡してください"

    /** Without any saved allocations, an old empty draft may be discarded safely. */
    fun mustPreserveDifferentCart(savedAllocationCount: Int): Boolean {
        require(savedAllocationCount >= 0) { "savedAllocationCount must not be negative" }
        return savedAllocationCount > 0
    }
}
