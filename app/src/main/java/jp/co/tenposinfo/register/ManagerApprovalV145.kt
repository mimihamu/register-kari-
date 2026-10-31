package jp.co.tenposinfo.register

import android.content.Context

/**
 * A manager can approve by their active session only when the account was explicitly set
 * to PIN-free login. Other operators must supply a registered manager's PIN.
 *
 * Every PIN-free approval rechecks the operator's role and PIN policy in the database.
 * An empty PIN never authorizes an ordinary cashier.
 */
internal object ManagerApprovalV145 {
    fun canUseCurrentManagerSession(context: Context): Boolean =
        pinFreeSessionManagerName(context) != null

    fun resolve(context: Context, suppliedPin: String): String? =
        if (suppliedPin.isNotBlank()) {
            AdminSettingsStore(context.applicationContext).use { it.managerNameForPin(suppliedPin) }
        } else {
            pinFreeSessionManagerName(context)
        }

    private fun pinFreeSessionManagerName(context: Context): String? {
        val session = OperatorSessionRegistry.current(context.applicationContext) ?: return null
        if (!session.isManager) return null
        return AdminSettingsStore(context.applicationContext).use {
            it.managerNameForPinFreeSession(session.id)
        }
    }
}
