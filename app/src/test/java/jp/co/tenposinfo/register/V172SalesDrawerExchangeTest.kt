package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V172SalesDrawerExchangeTest {
    private val root = File("src/main/java/jp/co/tenposinfo/register")
    private fun src(name: String): String = File(root,name).readText()

    @Test fun salesScreenOffersDrawerOnlyExchangeInBothLandscapeLayouts() {
        val s=src("MainActivity.kt")
        assertTrue(s.contains("onExchangeDrawer = {"))
        assertTrue(s.contains("exchangeDrawerInProgress = true"))
        assertTrue(s.contains("withContext(kotlinx.coroutines.Dispatchers.IO)"))
        assertTrue(s.contains("SalesExchangeDrawerV172.open(context.applicationContext)"))
        assertTrue(s.contains("onExchangeDrawer: (() -> Unit)? = null"))
        assertTrue(s.contains("enabled = !exchangeDrawerInProgress"))
        assertTrue(s.split("Text(\"両替\", maxLines = 1)").size - 1 == 2)
        assertTrue(s.contains("if (onExchangeDrawer != null)"))
        assertTrue(src("ManualReturnActivityV135.kt").contains("minusMode = true"))
        assertFalse(src("ManualReturnActivityV135.kt").contains("onExchangeDrawer ="))
    }

    @Test fun exchangeUsesExistingDrawerPolicyAndMakesNoCashLedgerChanges() {
        val action=src("SalesExchangeDrawerV172.kt")
        assertTrue(action.contains("operator?.allows(RegisterPermission.SALES) == true"))
        assertTrue(action.contains("OperatorSessionRegistry.current(appContext)"))
        assertTrue(action.contains("CashDrawerOpenContextV136.EXCHANGE"))
        assertTrue(action.contains("CashDrawerSafetyPolicyV136.shouldOpen("))
        assertTrue(action.contains("eventKey = \"SALES_EXCHANGE:"))
        assertTrue(action.contains("reason = \"販売画面の両替\""))
        assertTrue(action.contains("CashDrawerRuntimeV136.dispatch("))
        assertFalse(action.contains("recordCashMovement("))
        assertFalse(action.contains("recordSale("))
        assertTrue(src("CashDrawerSafetyV136.kt").contains("event_key TEXT NOT NULL UNIQUE"))
        assertTrue(src("CashDrawerSafetyV136.kt").contains("CASH_DRAWER_OPEN_FAILED_OR_UNCERTAIN"))
    }

    @Test fun cashMovementInputShowsOnlyInAndOutButPastExchangeIsReadable() {
        val operations=src("OperationsActivity.kt")
        val start=operations.indexOf("private fun CashMovementScreen(")
        val end=operations.indexOf("private fun ReversalScreen(",start)
        val cash=operations.substring(start,end)
        assertTrue(cash.contains("OpChoiceButton(\"入金\""))
        assertTrue(cash.contains("OpChoiceButton(\"出金\""))
        assertFalse(cash.contains("OpChoiceButton(\"両替\""))
        assertTrue(cash.contains("CashMovementType.EXCHANGE -> \"±"))
        assertTrue(src("OperationsStore.kt").contains("EXCHANGE(\"両替\", 0)"))
        assertTrue(src("SecureOperationsCoordinator.kt").contains("CashMovementType.EXCHANGE -> CashDrawerOpenContextV136.EXCHANGE"))
    }

    @Test fun salesCashierCanOpenAndMissingSalesPermissionCannot() {
        val cashier=AuthenticatedOperator(
            id=1L,code="TEST",name="担当",role=OperatorRole.CASHIER,
            permissions=OperatorPermissionPolicy.defaults(OperatorRole.CASHIER),
        )
        assertTrue(SalesExchangeDrawerV172.canRequest(cashier))
        assertFalse(SalesExchangeDrawerV172.canRequest(cashier.copy(permissions=emptySet())))
        assertFalse(SalesExchangeDrawerV172.canRequest(null))
    }
}
