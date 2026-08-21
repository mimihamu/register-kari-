package jp.co.tenposinfo.register

import android.content.Context

/** Issue #137 #19 / formal v2.5 RCP-002. */
object ReceiptAutoPrintPolicyV136 {
    fun shouldCreateAutomaticReceiptJob(receiptAutoPrintEnabled: Boolean): Boolean =
        receiptAutoPrintEnabled

    fun shouldOpenDrawerSeparately(
        receiptAutoPrintEnabled: Boolean,
        printerUsable: Boolean,
        drawerEnabled: Boolean,
        drawerOpenOnCashSale: Boolean,
        hasCashPayment: Boolean,
    ): Boolean =
        !receiptAutoPrintEnabled &&
            printerUsable &&
            drawerEnabled &&
            drawerOpenOnCashSale &&
            hasCashPayment
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
    ): Result<Boolean> {
        val appContext = context.applicationContext
        val configuration = PrinterPaperSettingPolicy.currentConfiguration(appContext)
        val shouldOpen = ReceiptAutoPrintPolicyV136.shouldOpenDrawerSeparately(
            receiptAutoPrintEnabled = configuration.receiptAutoPrintEnabled,
            printerUsable = configuration.usable,
            drawerEnabled = configuration.drawerEnabled,
            drawerOpenOnCashSale = configuration.drawerOpenOnCashSale,
            hasCashPayment = paymentState.allocations.any { it.method == PaymentMethod.CASH },
        )
        if (!shouldOpen) return Result.success(false)

        val sendResult = TcpEscPosPrinterGateway(
            host = configuration.host,
            port = configuration.port,
            timeoutMillis = configuration.timeoutMillis,
        ).send(PrinterCommandEncoder.drawerOnly(configuration))

        runCatching {
            AdminSettingsStore(appContext).use { store ->
                store.recordOperationalAudit(
                    eventType = if (sendResult.isSuccess) {
                        "SALE_DRAWER_AUTO_OPEN_SUCCEEDED"
                    } else {
                        "SALE_DRAWER_AUTO_OPEN_FAILED"
                    },
                    referenceId = saleId,
                    detail = buildString {
                        append("receiptAutoPrint=OFF / ")
                        append(configuration.host).append(':').append(configuration.port)
                        sendResult.exceptionOrNull()?.let { error ->
                            append(" / ").append(error.message ?: error.javaClass.simpleName)
                        }
                    },
                    actor = actor.ifBlank { "SYSTEM" },
                )
            }
        }
        return sendResult.map { true }
    }
}
