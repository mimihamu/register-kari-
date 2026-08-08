package jp.co.tenposinfo.register

import android.content.Context
import android.content.Intent
import java.time.LocalDate

internal data class BusinessDateSalesLookupContext(
    val businessDate: String,
    val businessSessionId: Long,
)

internal object BusinessDateSalesLookupNavigation {
    private const val EXTRA_BUSINESS_DATE = "business_date_sales_lookup.business_date"
    private const val EXTRA_BUSINESS_SESSION_ID = "business_date_sales_lookup.business_session_id"

    fun intent(
        context: Context,
        businessDate: String,
        businessSessionId: Long,
    ): Intent = Intent(context, BusinessDateSalesLookupActivity::class.java).apply {
        putExtra(EXTRA_BUSINESS_DATE, businessDate)
        putExtra(EXTRA_BUSINESS_SESSION_ID, businessSessionId)
    }

    fun requestedContext(intent: Intent?): BusinessDateSalesLookupContext? {
        val rawDate = intent?.getStringExtra(EXTRA_BUSINESS_DATE)?.trim().orEmpty()
        val normalizedDate = runCatching { LocalDate.parse(rawDate).toString() }.getOrNull() ?: return null
        val sessionId = intent?.getLongExtra(EXTRA_BUSINESS_SESSION_ID, -1L) ?: -1L
        if (sessionId <= 0L) return null
        return BusinessDateSalesLookupContext(normalizedDate, sessionId)
    }
}
