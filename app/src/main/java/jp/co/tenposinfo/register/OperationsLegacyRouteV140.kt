package jp.co.tenposinfo.register

import android.content.Context
import android.content.Intent

/** Direct entry from the unified management screen; original actions still enforce permissions. */
internal object OperationsLegacyRouteV140 {
    const val EXTRA_INITIAL_SCREEN = "jp.co.tenposinfo.register.OPERATIONS_INITIAL_SCREEN"
    const val DAILY_SALES = "daily_sales"
    const val CASH_MOVEMENT = "cash_movement"
    const val REVERSAL = "reversal"

    fun intent(context: Context, destination: String): Intent {
        require(requiredPermission(destination) != null) { "Unknown management destination" }
        return Intent(context, OperationsActivity::class.java)
            .putExtra(EXTRA_INITIAL_SCREEN, destination)
    }

    fun requested(intent: Intent): String? =
        intent.getStringExtra(EXTRA_INITIAL_SCREEN)?.takeIf { requiredPermission(it) != null }

    fun requiredPermission(destination: String?): RegisterPermission? = when (destination) {
        DAILY_SALES -> RegisterPermission.VIEW_SALES
        CASH_MOVEMENT -> RegisterPermission.CASH_MOVEMENT
        REVERSAL -> RegisterPermission.REVERSAL
        else -> null
    }
}
