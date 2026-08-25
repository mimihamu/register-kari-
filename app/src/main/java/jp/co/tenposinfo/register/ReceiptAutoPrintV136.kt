package jp.co.tenposinfo.register

import android.content.Context

/** Issue #137 #19 / formal v2.5 RCP-002. */
object ReceiptAutoPrintPolicyV136 {
    fun shouldCreateAutomaticReceiptJob(receiptAutoPrintEnabled: Boolean): Boolean =
        receiptAutoPrintEnabled

    /** CSH-004: drawer opening is driven by the committed cash sale, never by print mode. */
    fun shouldOpenDrawerAfterCommittedCashSale(
        printerUsable: Boolean,
        drawerEnabled: Boolean,
        drawerOpenOnCashSale: Boolean,
        hasCashPayment: Boolean,
    ): Boolean =
        printerUsable && drawerEnabled && drawerOpenOnCashSale && hasCashPayment
}

/**
 * Initial automatic jobs are created at exactly the sale timestamp. A manual after-receipt is
 * deliberately created later, so it remains a REPRINT even when print_count is still zero because
 * automatic issuing was disabled.
 */
object ReceiptReprintPolicyV136 {
    fun isReprint(
        jobCreatedAt: Long,
        saleCreatedAt: Long,
        completedPrintCount: Int,
    ): Boolean = completedPrintCount > 0 || jobCreatedAt > saleCreatedAt
}

object ReceiptAutoPrintRuntimeV136 {
    fun dispatchDrawerIfNeeded(
        context: Context,
        paymentState: PaymentState,
        saleId: Long,
        actor: String,
    ): Result<Boolean> = CashDrawerRuntimeV136.dispatch(
        context = context.applicationContext,
        openContext = CashDrawerOpenContextV136.CASH_SALE,
        referenceId = saleId,
        eventKey = "SALE:$saleId",
        reason = "現金会計",
        actor = actor.ifBlank { "SYSTEM" },
        hasCashPayment = paymentState.allocations.any { it.method == PaymentMethod.CASH },
    )
}
