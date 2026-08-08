package jp.co.tenposinfo.register

import android.content.Context
import android.content.Intent

internal data class ReceiptVoucherSaleContext(
    val requestedSaleId: Long?,
    val selectedSaleId: Long?,
    val selectionLocked: Boolean,
    val requestedSaleUnavailable: Boolean,
)

internal object ReceiptVoucherNavigation {
    const val EXTRA_SALE_ID = "jp.co.tenposinfo.register.extra.RECEIPT_VOUCHER_SALE_ID"

    fun issuanceIntent(context: Context, saleId: Long? = null): Intent =
        Intent(context, ReceiptVoucherActivity::class.java).apply {
            if (saleId != null && saleId > 0L) putExtra(EXTRA_SALE_ID, saleId)
        }

    fun ledgerIntent(context: Context, saleId: Long? = null): Intent =
        Intent(context, ReceiptVoucherLedgerActivity::class.java).apply {
            if (saleId != null && saleId > 0L) putExtra(EXTRA_SALE_ID, saleId)
        }

    fun requestedSaleId(intent: Intent?): Long? {
        val value = intent?.getLongExtra(EXTRA_SALE_ID, -1L) ?: return null
        return value.takeIf { it > 0L }
    }

    fun resolveSaleContext(
        requestedSaleId: Long?,
        availableSaleIds: Collection<Long>,
    ): ReceiptVoucherSaleContext {
        val selected = when {
            requestedSaleId != null && requestedSaleId in availableSaleIds -> requestedSaleId
            else -> availableSaleIds.firstOrNull()
        }
        return ReceiptVoucherSaleContext(
            requestedSaleId = requestedSaleId,
            selectedSaleId = selected,
            selectionLocked = requestedSaleId != null && selected == requestedSaleId,
            requestedSaleUnavailable = requestedSaleId != null && requestedSaleId !in availableSaleIds,
        )
    }

    fun resolveInitialSaleId(requestedSaleId: Long?, availableSaleIds: Collection<Long>): Long? =
        resolveSaleContext(requestedSaleId, availableSaleIds).selectedSaleId
}
