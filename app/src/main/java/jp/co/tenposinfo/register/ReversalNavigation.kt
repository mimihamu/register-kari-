package jp.co.tenposinfo.register

import android.content.Context
import android.content.Intent

internal data class ReversalSaleContext(
    val requestedSaleId: Long?,
    val saleExists: Boolean,
    val alreadyCompleted: Boolean,
) {
    val mayOpenLocked: Boolean
        get() = requestedSaleId != null && saleExists && !alreadyCompleted
}

internal object ReversalNavigation {
    const val EXTRA_SALE_ID = "jp.co.tenposinfo.register.extra.REVERSAL_SALE_ID"

    fun intent(context: Context, saleId: Long): Intent =
        Intent(context, OperationsActivity::class.java).apply {
            if (saleId > 0L) putExtra(EXTRA_SALE_ID, saleId)
        }

    fun requestedSaleId(intent: Intent?): Long? {
        val value = intent?.getLongExtra(EXTRA_SALE_ID, -1L) ?: return null
        return value.takeIf { it > 0L }
    }

    fun resolve(
        requestedSaleId: Long?,
        saleExists: Boolean,
        alreadyCompleted: Boolean,
    ): ReversalSaleContext = ReversalSaleContext(
        requestedSaleId = requestedSaleId,
        saleExists = requestedSaleId != null && saleExists,
        alreadyCompleted = requestedSaleId != null && alreadyCompleted,
    )
}
