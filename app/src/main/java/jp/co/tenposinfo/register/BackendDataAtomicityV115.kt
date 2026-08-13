package jp.co.tenposinfo.register

internal object PrintQueueAtomicityV115 {
    fun mayClaim(status: PrintJobStatus): Boolean =
        status == PrintJobStatus.PENDING || status == PrintJobStatus.RETRY

    fun mayRetry(status: PrintJobStatus): Boolean =
        status == PrintJobStatus.PENDING || status == PrintJobStatus.RETRY || status == PrintJobStatus.FAILED
}

internal object ReversalConcurrencySafetyV115 {
    fun requireSnapshotUnchanged(
        originalQuantity: Int,
        snapshotReturnedQuantity: Int,
        snapshotRefundedDiscount: Long,
        currentReturnedQuantity: Int,
        currentRefundedDiscount: Long,
        requestedQuantity: Int,
        cancel: Boolean,
    ) {
        check(currentReturnedQuantity == snapshotReturnedQuantity && currentRefundedDiscount == snapshotRefundedDiscount) {
            "返品状態が変更されました。画面を更新してから再実行してください"
        }
        val remaining = (originalQuantity - currentReturnedQuantity).coerceAtLeast(0)
        check(requestedQuantity in 1..remaining) {
            "返品可能数量が変更されました。画面を更新してから再実行してください"
        }
        if (cancel) {
            check(currentReturnedQuantity == 0) {
                "一部返品済みの売上は取消できません"
            }
        }
    }
}
