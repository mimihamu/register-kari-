package jp.co.tenposinfo.register

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout

/**
 * 販売画面の状態を変更せず、仕様上の［メニュー］導線を追加する。
 * 管理画面から戻ると既存の販売カートを保持したまま復帰する。
 */
class RegisterApplication : Application(), Application.ActivityLifecycleCallbacks {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is MainActivity) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(MANAGEMENT_BUTTON_TAG) != null) return

        val button = Button(activity).apply {
            tag = MANAGEMENT_BUTTON_TAG
            text = "メニュー"
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(23, 63, 107))
            elevation = dp(activity, 8).toFloat()
            setOnClickListener {
                activity.startActivity(Intent(activity, OperationsActivity::class.java))
            }
        }
        val params = FrameLayout.LayoutParams(
            dp(activity, 132),
            dp(activity, 48),
            Gravity.TOP or Gravity.END,
        ).apply {
            topMargin = dp(activity, 68)
            marginEnd = dp(activity, 12)
        }
        content.addView(button, params)
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val MANAGEMENT_BUTTON_TAG = "register-management-menu"
    }
}
