package jp.co.tenposinfo.register

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Applicationの既存実装を壊さず、商品マスターの初期化・販売プロファイル・改定予約を反映する。
 */
class CatalogBootstrapProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return false
        DatabaseStartupSchemaBootstrapV085.ensureBeforeUi(appContext)
        TaxInvoiceSettingsRegistry.initialize(appContext)
        val token = runCatching {
            CatalogMasterStore(appContext).use { it.synchronizeEffectiveProducts() }
        }.getOrNull()
        runCatching { DynamicCatalogStore(appContext).close() }
        if (token != null) CatalogRuntimeState.saveToken(appContext, token)
        (appContext as? Application)?.registerActivityLifecycleCallbacks(CatalogLifecycleCallbacks())
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

private class CatalogLifecycleCallbacks : Application.ActivityLifecycleCallbacks {
    override fun onActivityResumed(activity: Activity) {
        when (activity) {
            is MainActivity -> synchronizeAndRefresh(activity)
            is CatalogSettingsActivity,
            is DynamicCatalogSettingsActivity,
            is MenuRevisionEditorActivity,
            is SyncSettingsActivity,
            is TaxInvoiceSettingsActivity,
            -> guardSettingsActivity(activity)
        }
    }

    private fun synchronizeAndRefresh(activity: MainActivity) {
        val token = runCatching {
            CatalogMasterStore(activity.applicationContext).use { it.synchronizeEffectiveProducts() }
        }.getOrNull() ?: return
        val previous = CatalogRuntimeState.token(activity.applicationContext)
        if (previous != token) {
            CatalogRuntimeState.saveToken(activity.applicationContext, token)
            activity.recreate()
        }
    }

    private fun guardSettingsActivity(activity: Activity) {
        val operator = OperatorSessionRegistry.current(activity.applicationContext)
        if (operator?.isManager == true && operator.allows(RegisterPermission.SETTINGS)) {
            OperatorSessionRegistry.touch(activity.applicationContext)
        } else {
            activity.finish()
        }
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

}

object CatalogRuntimeState {
    private const val PREFS = "catalog_runtime_state"
    private const val KEY_TOKEN = "effective_catalog_token"

    fun token(context: android.content.Context): String? =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString(KEY_TOKEN, null)

    fun saveToken(context: android.content.Context, token: String) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putString(KEY_TOKEN, token).apply()
    }
}
