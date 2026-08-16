package jp.co.tenposinfo.register

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/** Initializes the v1.35 TAX-004 registration guard before the sales UI starts. */
class MixedTaxCartBootstrapProviderV135 : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.applicationContext?.let(MixedTaxCartRuntimeV135::initialize)
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
