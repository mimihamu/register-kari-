package jp.co.tenposinfo.register

/**
 * v2.5 RCP-BATCH-SET-001 representative preview policy.
 * A large batch must never expand all tickets in the confirmation UI.
 */
internal data class ReceiptVoucherBatchPreviewRepresentativeV136(
    val sequenceNo: Int,
    val label: String,
)

internal object ReceiptVoucherBatchPreviewPolicyV136 {
    fun representatives(
        copies: Int,
        remainderDifferenceSequence: Int? = null,
    ): List<ReceiptVoucherBatchPreviewRepresentativeV136> {
        require(copies in ReceiptVoucherBatchSettingsV135.MIN_BATCH_COPIES..ReceiptVoucherBatchSettingsV135.MAX_BATCH_COPIES) {
            "一括領収書の枚数は1～999枚で指定してください"
        }
        val labels = linkedMapOf<Int, String>()
        labels[1] = if (copies == 1) "1枚目・最終票" else "1枚目"
        if (remainderDifferenceSequence != null && remainderDifferenceSequence in 2 until copies) {
            labels[remainderDifferenceSequence] = "端数差票"
        }
        if (copies > 1) labels[copies] = "最終票"
        return labels.map { (sequenceNo, label) ->
            ReceiptVoucherBatchPreviewRepresentativeV136(sequenceNo, label)
        }
    }
}
