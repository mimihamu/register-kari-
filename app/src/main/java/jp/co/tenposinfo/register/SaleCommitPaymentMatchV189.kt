package jp.co.tenposinfo.register

/** A sale retry / stale draft cleanup must match every tender, not just the total. */
internal object SaleCommitPaymentMatchV189 {
    const val REVIEW_MESSAGE =
        "確定済み売上の支払内訳と一致しません。再決済せず売上履歴と決済端末を確認してください"

    fun sameSequence(
        persistedSalePayments: List<PaymentAllocation>,
        requested: List<PaymentAllocation>,
    ): Boolean = persistedSalePayments == requested

    fun contiguousSequenceNumbers(sequences: List<Int>): Boolean =
        sequences.withIndex().all { (index, sequence) -> sequence == index + 1 }
}
