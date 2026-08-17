package jp.co.tenposinfo.register

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/** Initializes v1.35 guards before the sales / operations UI starts. */
class MixedTaxCartBootstrapProviderV135 : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.applicationContext?.let { appContext ->
            MixedTaxCartRuntimeV135.initialize(appContext)
            // Install guest-count sales/held-ticket compatibility columns and triggers before any UI
            // can create or recall a ticket.
            SaleGuestCountRuntimeV135(appContext).close()
            SettlementReportingRuntimeV135.initialize(appContext)
            SettlementTaxBreakdownRuntimeV135.initialize(appContext)
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
