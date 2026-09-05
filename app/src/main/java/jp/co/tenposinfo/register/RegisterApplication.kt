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
 * 販売画面へ管理導線・権限制御・営業日制御を重ねるアプリケーション層。
 * v0.8では認証済み担当者の権限だけを有効にし、印刷Workerもここで起動する。
 */
class RegisterApplication : Application(), Application.ActivityLifecycleCallbacks {
    override fun onCreate() {
        super.onCreate()
        CrashReportRuntimeV138.install(this)
        PrinterConfigurationRegistry.reload(this)
        AutomaticPrintScheduler.schedule(this)
        AutoBackupPeriodicScheduler.apply(this)
        ExternalBackupScheduler.apply(this)
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        when (activity) {
            is MainActivity -> updateMainActivity(activity)
            is BusinessStartActivityV030 -> guardBusinessStartActivity(activity)
            is OperationsHubActivityV030,
            is SettlementActivityV030,
            is SettlementHistoryActivityV030,
            is OperationsActivity -> guardManagementActivity(activity)
            is AdminSettingsActivity,
            is DataProtectionActivity,
            is AutoBackupSettingsActivity,
            is ExternalBackupSettingsActivity,
            is OutboxDeliverySettingsActivity -> guardSettingsActivity(activity)
        }
    }

    private fun updateMainActivity(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val operator = OperatorSessionRegistry.current(activity.applicationContext)
        if (operator != null) OperatorSessionRegistry.touch(activity.applicationContext)
        updateSalesPermissionGate(activity, content, operator)
        updateBusinessDayGate(activity, content, operator)
        AutomaticPrintScheduler.enqueueNow(activity.applicationContext)
    }

    private fun updateActionButtons(
        activity: Activity,
        content: ViewGroup,
        operator: AuthenticatedOperator?,
    ) {
        val managementAllowed = operator?.let {
            ManagementNavigationPolicyV030.canOpenManagement(it.permissions)
        } == true
        ensureActionButton(
            activity = activity,
            content = content,
            tagValue = MANAGEMENT_BUTTON_TAG,
            visible = managementAllowed,
            text = "レジ管理",
            topMargin = 68,
            endMargin = 12,
            filled = true,
        ) { activity.startActivity(operationsIntent(activity)) }

        ensureActionButton(
            activity = activity,
            content = content,
            tagValue = SETTINGS_BUTTON_TAG,
            visible = operator?.allows(RegisterPermission.SETTINGS) == true,
            text = "設定",
            topMargin = 68,
            endMargin = 154,
            filled = false,
        ) { activity.startActivity(Intent(activity, AdminSettingsActivity::class.java)) }
    }

    private fun ensureActionButton(
        activity: Activity,
        content: ViewGroup,
        tagValue: String,
        visible: Boolean,
        text: String,
        topMargin: Int,
        endMargin: Int,
        filled: Boolean,
        action: () -> Unit,
    ) {
        val existing = content.findViewWithTag<View>(tagValue)
        if (!visible) {
            if (existing != null) content.removeView(existing)
            return
        }
        if (existing != null) return
        val button = Button(activity).apply {
            tag = tagValue
            this.text = text
            isAllCaps = false
            textSize = 16f
            if (filled) {
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(23, 63, 107))
            } else {
                setTextColor(Color.rgb(23, 63, 107))
                setBackgroundColor(Color.WHITE)
            }
            elevation = dp(activity, 8).toFloat()
            setOnClickListener { action() }
        }
        content.addView(
            button,
            FrameLayout.LayoutParams(dp(activity, if (filled) 132 else 116), dp(activity, 48), Gravity.TOP or Gravity.END).apply {
                this.topMargin = dp(activity, topMargin)
                marginEnd = dp(activity, endMargin)
            },
        )
    }

    private fun updateSalesPermissionGate(
        activity: Activity,
        content: ViewGroup,
        operator: AuthenticatedOperator?,
    ) {
        val existing = content.findViewWithTag<View>(SALES_PERMISSION_GATE_TAG)
        if (operator == null || operator.allows(RegisterPermission.SALES)) {
            if (existing != null) content.removeView(existing)
            return
        }
        if (existing != null) return

        val buttons = mutableListOf<Pair<String, () -> Unit>>()
        if (ManagementNavigationPolicyV030.canOpenManagement(operator.permissions)) {
            buttons += "レジ管理を開く" to { activity.startActivity(operationsIntent(activity)) }
        }
        if (operator.allows(RegisterPermission.SETTINGS)) {
            buttons += "各種設定を開く" to { activity.startActivity(Intent(activity, AdminSettingsActivity::class.java)) }
        }
        buttons += "担当者を切替" to {
            OperatorSessionRegistry.logout(activity.applicationContext)
            activity.recreate()
        }
        content.addView(
            createGateOverlay(
                activity = activity,
                tagValue = SALES_PERMISSION_GATE_TAG,
                title = "販売権限がありません",
                message = "${operator.name}には販売操作の権限がありません。許可された管理機能を使用するか、担当者を切り替えてください。",
                buttons = buttons,
            ),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    private fun updateBusinessDayGate(
        activity: Activity,
        content: ViewGroup,
        operator: AuthenticatedOperator?,
    ) {
        val existing = content.findViewWithTag<View>(BUSINESS_GATE_TAG)
        if (operator == null || !operator.allows(RegisterPermission.SALES)) {
            if (existing != null) content.removeView(existing)
            return
        }
        val open = runCatching { isCanonicalBusinessSessionOpen(activity) }.getOrDefault(false)
        if (open) {
            if (existing != null) content.removeView(existing)
            return
        }
        if (existing != null) return

        val buttons = mutableListOf<Pair<String, () -> Unit>>()
        if (BusinessStartNavigationPolicyV030.canOpenBusinessStart(operator.permissions)) {
            buttons += "営業開始・状態画面へ" to {
                activity.startActivity(
                    operationsIntent(
                        activity = activity,
                        initialScreen = OperationsNavigationContractV030.OPEN_BUSINESS_START,
                    ),
                )
            }
        }
        if (operator.allows(RegisterPermission.SETTINGS)) {
            buttons += "各種設定" to { activity.startActivity(Intent(activity, AdminSettingsActivity::class.java)) }
        }
        buttons += "担当者を切替" to {
            OperatorSessionRegistry.logout(activity.applicationContext)
            activity.recreate()
        }
        content.addView(
            createGateOverlay(
                activity = activity,
                tagValue = BUSINESS_GATE_TAG,
                title = "営業開始が必要です",
                message = "開始釣銭を登録して営業を開始してください。\nZ精算を実行すると営業は終了し、同じ営業日でも再度営業開始できます。",
                buttons = buttons,
            ),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    private fun operationsIntent(activity: Activity, initialScreen: String? = null): Intent =
        if (OperationsNavigationContractV030.requestsBusinessStart(initialScreen)) {
            Intent(activity, BusinessStartActivityV030::class.java)
        } else {
            Intent(activity, OperationsHubActivityV030::class.java)
        }

    private fun isCanonicalBusinessSessionOpen(activity: Activity): Boolean {
        val store = OperationsStore(activity.applicationContext)
        return try {
            store.activeBusinessSession()?.status == BusinessSessionStatus.OPEN
        } finally {
            store.close()
        }
    }

    private fun guardBusinessStartActivity(activity: Activity) {
        val operator = OperatorSessionRegistry.current(activity.applicationContext)
        val allowed = operator?.let {
            OperationsAccessPolicyV030.canOpenBusinessStart(it.permissions)
        } == true
        if (allowed) {
            OperatorSessionRegistry.touch(activity.applicationContext)
            return
        }
        installActivityGate(activity, "営業開始・状態画面にはZ精算権限が必要です")
    }

    private fun guardManagementActivity(activity: Activity) {
        val operator = OperatorSessionRegistry.current(activity.applicationContext)
        val allowed = operator?.let {
            OperationsAccessPolicyV030.canEnter(it.permissions)
        } == true
        if (allowed) {
            OperatorSessionRegistry.touch(activity.applicationContext)
            return
        }
        installActivityGate(activity, "レジ管理権限がありません")
    }

    private fun guardSettingsActivity(activity: Activity) {
        val operator = OperatorSessionRegistry.current(activity.applicationContext)
        if (operator?.isManager == true && operator.allows(RegisterPermission.SETTINGS)) {
            OperatorSessionRegistry.touch(activity.applicationContext)
            return
        }
        installActivityGate(activity, "各種設定は責任者権限が必要です")
    }

    private fun installActivityGate(activity: Activity, message: String) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(ACTIVITY_PERMISSION_GATE_TAG) != null) return
        content.addView(
            createGateOverlay(
                activity = activity,
                tagValue = ACTIVITY_PERMISSION_GATE_TAG,
                title = "アクセスできません",
                message = message,
                buttons = listOf("閉じる" to { activity.finish() }),
            ),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    private fun createGateOverlay(
        activity: Activity,
        tagValue: String,
        title: String,
        message: String,
        buttons: List<Pair<String, () -> Unit>>,
    ): FrameLayout {
        val overlay = FrameLayout(activity).apply {
            tag = tagValue
            setBackgroundColor(Color.argb(242, 244, 247, 250))
            elevation = dp(activity, 28).toFloat()
        }
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(activity, 36), dp(activity, 28), dp(activity, 36), dp(activity, 28))
            setBackgroundColor(Color.WHITE)
            elevation = dp(activity, 10).toFloat()
        }
        panel.addView(TextView(activity).apply {
            text = title
            textSize = 30f
            setTextColor(Color.rgb(23, 63, 107))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(activity, 620), dp(activity, 58)))
        panel.addView(TextView(activity).apply {
            text = message
            textSize = 17f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(activity, 680), dp(activity, 96)))
        buttons.forEachIndexed { index, (label, action) ->
            panel.addView(Button(activity).apply {
                text = label
                isAllCaps = false
                textSize = 17f
                setTextColor(if (index == 0) Color.WHITE else Color.rgb(23, 63, 107))
                setBackgroundColor(if (index == 0) Color.rgb(25, 118, 185) else Color.WHITE)
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(dp(activity, 340), dp(activity, 54)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(activity, 10)
            })
        }
        overlay.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        return overlay
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
        const val SETTINGS_BUTTON_TAG = "register-settings-menu"
        const val BUSINESS_GATE_TAG = "register-business-day-gate"
        const val SALES_PERMISSION_GATE_TAG = "register-sales-permission-gate"
        const val ACTIVITY_PERMISSION_GATE_TAG = "register-activity-permission-gate"
    }
}
