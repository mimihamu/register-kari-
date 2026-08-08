package jp.co.tenposinfo.register

import android.content.Context
import android.content.Intent

internal data class SaleReceiptReprintContext(
    val saleId: Long?,
    val mayOpen: Boolean,
)

internal object SaleReceiptNavigation {
    const val EXTRA_SALE_ID = "jp.co.tenposinfo.register.extra.SALE_RECEIPT_ID"

    fun intent(context: Context, saleId: Long): Intent =
        Intent(context, SaleReceiptReprintActivity::class.java).apply {
            if (saleId > 0L) putExtra(EXTRA_SALE_ID, saleId)
        }

    fun requestedSaleId(intent: Intent?): Long? =
        intent?.getLongExtra(EXTRA_SALE_ID, 0L)?.takeIf { it > 0L }

    fun resolve(requestedSaleId: Long?, saleExists: Boolean): SaleReceiptReprintContext =
        SaleReceiptReprintContext(
            saleId = requestedSaleId?.takeIf { it > 0L && saleExists },
            mayOpen = requestedSaleId != null && requestedSaleId > 0L && saleExists,
        )
}
