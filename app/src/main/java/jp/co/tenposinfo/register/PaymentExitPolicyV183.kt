package jp.co.tenposinfo.register

/**
 * UC-08 safety: leaving payment with recorded allocations would erase the draft
 * and commit key, potentially making an already processed external tender
 * impossible to reconcile. Require explicit reversal/void verification first.
 */
internal object PaymentExitPolicyV183 {
    const val BLOCKED_EXIT_MESSAGE =
        "支払配分が残っています。戻る前に会計画面で配分を取り消してください。" +
        "カード・電子決済などが実際に処理済みの場合は、決済端末側の取消も確認してください。"

    fun mustKeepPaymentOpen(savedAllocationCount: Int): Boolean {
        require(savedAllocationCount >= 0) { "allocation count cannot be negative" }
        return savedAllocationCount > 0
    }
}
