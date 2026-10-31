package jp.co.tenposinfo.register

import android.content.Context
import java.util.UUID

/**
 * Drawer-only exchange is launched from normal sales, not from cash in/out.
 * The request has no financial amount and never changes cash movement totals.
 * The transport is called off the UI thread. Every request uses an immutable
 * unique event key, so a failed/uncertain pulse cannot be retried automatically.
 */
internal object SalesExchangeDrawerV172 {
    fun canRequest(operator: AuthenticatedOperator?): Boolean =
        operator?.allows(RegisterPermission.SALES) == true

    fun open(context: Context): Result<Boolean> = runCatching {
        val appContext = context.applicationContext
        val operator = OperatorSessionRegistry.current(appContext)
        check(canRequest(operator)) { "販売権限のある担当者でログインしてください" }
        val config = PrinterPaperSettingPolicy.currentConfiguration(appContext)
        check(CashDrawerSafetyPolicyV136.shouldOpen(CashDrawerOpenContextV136.EXCHANGE, config)) {
            "ドロアの両替開放設定、プリンター接続を確認してください"
        }
        CashDrawerRuntimeV136.dispatch(
            context = appContext,
            openContext = CashDrawerOpenContextV136.EXCHANGE,
            referenceId = 0L, // A drawer-only action has no sale or movement record.
            eventKey = "SALES_EXCHANGE:${UUID.randomUUID()}",
            reason = "販売画面の両替",
            actor = requireNotNull(operator).name,
        ).getOrThrow()
    }
}
