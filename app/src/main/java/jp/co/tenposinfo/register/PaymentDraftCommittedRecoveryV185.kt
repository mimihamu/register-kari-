package jp.co.tenposinfo.register

/** Post-crash cleanup is allowed only for a real, matching, fully paid sale. */
internal object PaymentDraftCommittedRecoveryV185 {
    fun canRelease(
        savedFingerprint: String,
        allocations: List<PaymentAllocation>,
        committedSale: SaleCommitIdempotencySchema.ExistingCommit?,
    ): Boolean {
        if (committedSale == null || committedSale.saleId <= 0L) return false
        if (committedSale.cartFingerprint != savedFingerprint) return false
        if (!PaymentDraftIntegrityV181.validAllocations(allocations, committedSale.totalAmount)) {
            return false
        }
        return allocations.sumOf { it.appliedAmount } == committedSale.totalAmount
    }
}
