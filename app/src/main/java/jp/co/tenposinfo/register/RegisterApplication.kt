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
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 販売画面の状態を変更せず、仕様上の［メニュー］導線を追加する。
 * v0.6では営業開始前・Z精算後の販売を画面上でブロックする。
 */
class RegisterApplication : Application(), Application.ActivityLifecycleCallbacks {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is MainActivity) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        addManagementButton(activity, content)
        updateBusinessDayGate(activity, content)
    }

    private fun addManagementButton(activity: Activity, content: ViewGroup) {
        if (content.findViewWithTag<View>(MANAGEMENT_BUTTON_TAG) != null) return
        val button = Button(activity).apply {
            tag = MANAGEMENT_BUTTON_TAG
            text = "メニュー"
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(23, 63, 107))
            elevation = dp(activity, 8).toFloat()
            setOnClickListener { activity.startActivity(Intent(activity, OperationsActivity::class.java)) }
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

    private fun updateBusinessDayGate(activity: Activity, content: ViewGroup) {
        val existing = content.findViewWithTag<View>(BUSINESS_GATE_TAG)
        val open = runCatching { AdvancedOperationsStore.isBusinessOpen(activity.applicationContext) }.getOrDefault(false)
        if (open) {
            if (existing != null) content.removeView(existing)
            return
        }
        if (existing != null) return

        val overlay = FrameLayout(activity).apply {
            tag = BUSINESS_GATE_TAG
            setBackgroundColor(Color.argb(238, 244, 247, 250))
            elevation = dp(activity, 24).toFloat()
        }
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(activity, 36), dp(activity, 28), dp(activity, 36), dp(activity, 28))
            setBackgroundColor(Color.WHITE)
            elevation = dp(activity, 10).toFloat()
        }
        panel.addView(TextView(activity).apply {
            text = "営業開始が必要です"
            textSize = 30f
            setTextColor(Color.rgb(23, 63, 107))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(activity, 520), dp(activity, 58)))
        panel.addView(TextView(activity).apply {
            text = "開始釣銭を登録して営業を開始してください。\nZ精算後は営業終了を完了するまで販売できません。"
            textSize = 17f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(activity, 620), dp(activity, 88)))
        panel.addView(Button(activity).apply {
            text = "営業開始・終了画面へ"
            isAllCaps = false
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(25, 118, 185))
            setOnClickListener { activity.startActivity(Intent(activity, OperationsActivity::class.java)) }
        }, LinearLayout.LayoutParams(dp(activity, 320), dp(activity, 58)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(activity, 12)
        })
        overlay.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))
        content.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
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
        const val BUSINESS_GATE_TAG = "register-business-day-gate"
    }
}
