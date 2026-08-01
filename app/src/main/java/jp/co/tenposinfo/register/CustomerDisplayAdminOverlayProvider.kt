package jp.co.tenposinfo.register

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.res.ColorStateList
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout

class CustomerDisplayAdminOverlayProvider : ContentProvider() {
    private var callbacks: Application.ActivityLifecycleCallbacks? = null

    override fun onCreate(): Boolean {
        val application = context?.applicationContext as? Application ?: return false
        callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is AdminSettingsActivity) attachButton(activity)
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (activity is AdminSettingsActivity) removeButton(activity)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        }.also(application::registerActivityLifecycleCallbacks)
        return true
    }

    private fun attachButton(activity: Activity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (root.findViewWithTag<Button>(BUTTON_TAG) != null) return
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val button = Button(activity).apply {
            tag = BUTTON_TAG
            text = "顧客表示"
            textSize = 16f
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(72, 74, 155))
            stateListAnimator = null
            elevation = dp(6).toFloat()
            contentDescription = "つぐレジ CDの接続設定を開く"
            setOnClickListener {
                activity.startActivity(Intent(activity, CustomerDisplaySettingsActivity::class.java))
            }
        }
        val params = FrameLayout.LayoutParams(dp(230), dp(54), Gravity.END or Gravity.BOTTOM).apply {
            rightMargin = dp(18)
            bottomMargin = dp(146)
        }
        root.addView(button, params)
    }

    private fun removeButton(activity: Activity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        root.findViewWithTag<Button>(BUTTON_TAG)?.let(root::removeView)
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

    private companion object {
        const val BUTTON_TAG = "tsuguregi_customer_display_admin_button"
    }
}
