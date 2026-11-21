package jp.co.tenposinfo.register

/**
 * A second payment screen may reuse the same in-progress draft, but must not
 * overwrite persisted allocations belonging to another sale or a damaged draft.
 * Empty drafts are replaceable; the store separately checks for committed keys.
 */
internal object PaymentDraftSaveFenceV186 {
    fun requireSameDraft(
        hasSavedAllocations: Boolean,
        savedFingerprint: String?,
        savedCommitKey: String?,
        nextFingerprint: String,
        nextCommitKey: String,
    ) {
        if (!hasSavedAllocations) return
        require(
            savedFingerprint == nextFingerprint &&
                savedCommitKey == nextCommitKey &&
                PaymentCommitKey.isValid(savedCommitKey),
        ) {
            "別取引または識別不能な支払記録が残っています。再決済せず売上履歴と決済端末を確認してください"
        }
    }
}
