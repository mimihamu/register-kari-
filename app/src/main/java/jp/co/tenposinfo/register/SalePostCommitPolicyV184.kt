package jp.co.tenposinfo.register

/**
 * Commit success is durable: failures to update peripheral displays or clear a
 * redundant payment draft must never be confused with a failed sale.
 */
internal object SalePostCommitPolicyV184 {
    const val DRAFT_CLEANUP_WARNING =
        "売上は確定済みです。支払入力データの整理に失敗しました。再会計せず、次の取引から整理を再試行してください。"
    const val NONCRITICAL_WARNING =
        "売上は確定済みです。表示・印刷予約・周辺機器の一部を確認してください。再会計しないでください。"

    fun canStartNextSale(draftCleanupPending: Boolean): Boolean = !draftCleanupPending
}
