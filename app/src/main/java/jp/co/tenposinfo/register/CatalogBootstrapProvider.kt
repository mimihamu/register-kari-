package jp.co.tenposinfo.register

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout

/**
 * Applicationの既存実装を壊さず、商品マスターの初期化・販売プロファイル・改定予約を反映する。
 */
class CatalogBootstrapProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return false
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
            is MainActivity -> {
                synchronizeAndRefresh(activity)
            }
            is CatalogSettingsActivity -> {
                guardSettingsActivity(activity)
                installDynamicCatalogButton(activity)
            }
            is DynamicCatalogSettingsActivity -> {
                guardSettingsActivity(activity)
                installRevisionEditorButton(activity)
                installSyncButton(activity)
            }
            is MenuRevisionEditorActivity, is SyncSettingsActivity -> guardSettingsActivity(activity)
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

    private fun installCatalogButton(activity: MainActivity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val operator = OperatorSessionRegistry.current(activity.applicationContext)
        val existing = content.findViewWithTag<View>(BUTTON_TAG)
        val allowed = operator?.isManager == true && operator.allows(RegisterPermission.SETTINGS)
        if (!allowed) {
            if (existing != null) content.removeView(existing)
            return
        }
        if (existing != null) return
        val button = Button(activity).apply {
            tag = BUTTON_TAG
            text = "商品設定"
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.rgb(23, 63, 107))
            setBackgroundColor(Color.WHITE)
            elevation = dp(activity, 8).toFloat()
            setOnClickListener { activity.startActivity(Intent(activity, CatalogSettingsActivity::class.java)) }
        }
        content.addView(
            button,
            FrameLayout.LayoutParams(dp(activity, 132), dp(activity, 48), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(activity, 68)
                marginEnd = dp(activity, 282)
            },
        )
    }

    private fun installDynamicCatalogButton(activity: CatalogSettingsActivity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(DYNAMIC_BUTTON_TAG) != null) return
        val button = Button(activity).apply {
            tag = DYNAMIC_BUTTON_TAG
            text = "任意税率・改定"
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(25, 118, 185))
            elevation = dp(activity, 10).toFloat()
            setOnClickListener { activity.startActivity(Intent(activity, DynamicCatalogSettingsActivity::class.java)) }
        }
        content.addView(
            button,
            FrameLayout.LayoutParams(dp(activity, 176), dp(activity, 48), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(activity, 70)
                marginEnd = dp(activity, 18)
            },
        )
    }

    private fun installRevisionEditorButton(activity: DynamicCatalogSettingsActivity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(REVISION_EDITOR_BUTTON_TAG) != null) return
        val button = Button(activity).apply {
            tag = REVISION_EDITOR_BUTTON_TAG
            text = "改定内容編集"
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.rgb(23, 63, 107))
            setBackgroundColor(Color.WHITE)
            elevation = dp(activity, 10).toFloat()
            setOnClickListener { activity.startActivity(Intent(activity, MenuRevisionEditorActivity::class.java)) }
        }
        content.addView(
            button,
            FrameLayout.LayoutParams(dp(activity, 154), dp(activity, 48), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(activity, 70)
                marginEnd = dp(activity, 196)
            },
        )
    }

    private fun installSyncButton(activity: DynamicCatalogSettingsActivity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(SYNC_BUTTON_TAG) != null) return
        val button = Button(activity).apply {
            tag = SYNC_BUTTON_TAG
            text = "同期基盤"
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(25, 118, 185))
            elevation = dp(activity, 10).toFloat()
            setOnClickListener { activity.startActivity(Intent(activity, SyncSettingsActivity::class.java)) }
        }
        content.addView(
            button,
            FrameLayout.LayoutParams(dp(activity, 160), dp(activity, 48), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(activity, 70)
                marginEnd = dp(activity, 18)
            },
        )
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

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    companion object {
        private const val BUTTON_TAG = "register-catalog-settings"
        private const val DYNAMIC_BUTTON_TAG = "register-dynamic-catalog-settings"
        private const val REVISION_EDITOR_BUTTON_TAG = "register-revision-editor"
        private const val SYNC_BUTTON_TAG = "register-sync-foundation"
    }
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
