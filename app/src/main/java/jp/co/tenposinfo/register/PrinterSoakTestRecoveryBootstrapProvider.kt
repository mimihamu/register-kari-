package jp.co.tenposinfo.register

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log

class PrinterSoakTestRecoveryBootstrapProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return false
        PrinterSoakTestStartupRecovery.recover(appContext)
            .onSuccess { report ->
                if (report.recoveredCount > 0) {
                    Log.w(TAG, "Recovered interrupted printer soak tests: ${report.recoveredRunIds}")
                }
            }
            .onFailure { error ->
                Log.e(TAG, "Failed to recover interrupted printer soak tests", error)
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

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        const val TAG = "SoakTestRecovery"
    }
}
