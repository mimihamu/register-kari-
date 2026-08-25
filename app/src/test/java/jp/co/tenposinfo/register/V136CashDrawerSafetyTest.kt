package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V136CashDrawerSafetyTest {
    private fun config(
        sale: Boolean = true,
        refund: Boolean = true,
        movement: Boolean = true,
        exchange: Boolean = true,
        standalone: Boolean = false,
    ) = PrinterConfiguration(
        host = "127.0.0.1",
        enabled = true,
        drawerEnabled = true,
        drawerOpenOnCashSale = sale,
        drawerOpenOnCashRefund = refund,
        drawerOpenOnCashMovement = movement,
        drawerOpenOnExchange = exchange,
        drawerStandaloneEnabled = standalone,
        drawerOpenReasonRequired = true,
        drawerOnMillis = 50,
    )

    @Test
    fun onlyFormalBusinessContextsAreAllowedBySettings() {
        val c = config()
        assertTrue(CashDrawerSafetyPolicyV136.shouldOpen(CashDrawerOpenContextV136.CASH_SALE, c, true))
        assertTrue(CashDrawerSafetyPolicyV136.shouldOpen(CashDrawerOpenContextV136.CASH_REFUND, c, true))
        assertTrue(CashDrawerSafetyPolicyV136.shouldOpen(CashDrawerOpenContextV136.CASH_IN, c))
        assertTrue(CashDrawerSafetyPolicyV136.shouldOpen(CashDrawerOpenContextV136.CASH_OUT, c))
        assertTrue(CashDrawerSafetyPolicyV136.shouldOpen(CashDrawerOpenContextV136.EXCHANGE, c))
        assertFalse(CashDrawerSafetyPolicyV136.shouldOpen(CashDrawerOpenContextV136.STANDALONE, c))
    }

    @Test
    fun nonCashSaleAndRefundNeverOpenDrawer() {
        val c = config()
        assertFalse(CashDrawerSafetyPolicyV136.shouldOpen(CashDrawerOpenContextV136.CASH_SALE, c, false))
        assertFalse(CashDrawerSafetyPolicyV136.shouldOpen(CashDrawerOpenContextV136.CASH_REFUND, c, false))
    }

    @Test
    fun pulseMinimumMatchesFormalFiftyMilliseconds() {
        assertTrue(CashDrawerSafetyPolicyV136.MIN_OPEN_PULSE_MS == 50)
        CashDrawerSafetyPolicyV136.validatePulse(config().copy(drawerOnMillis = 50))
        assertTrue(runCatching { CashDrawerSafetyPolicyV136.validatePulse(config().copy(drawerOnMillis = 49)) }.isFailure)
    }

    @Test
    fun sourceHasDurableOnceOnlyClaimAndNoReceiptSideEffect() {
        val root = File("src/main/java/jp/co/tenposinfo/register")
        val drawer = File(root, "CashDrawerSafetyV136.kt").readText()
        val receipt = File(root, "Receipt.kt").readText()
        val secure = File(root, "SecureOperationsCoordinator.kt").readText()
        val manual = File(root, "ManualReturnV135.kt").readText()
        val operations = File(root, "OperationsStore.kt").readText()
        val settings = File(root, "AdminSettingsStore.kt").readText()

        assertTrue(drawer.contains("event_key TEXT NOT NULL UNIQUE"))
        assertTrue(drawer.contains("SQLiteDatabase.CONFLICT_IGNORE"))
        assertTrue(drawer.contains("CASH_DRAWER_DUPLICATE_SUPPRESSED"))
        assertTrue(drawer.contains("FAILED_OR_UNCERTAIN"))
        assertTrue(receipt.contains("openDrawer = false"))
        assertTrue(secure.contains("eventKey = \"CASH_MOVEMENT:$movementId\""))
        assertTrue(secure.contains("eventKey = \"REVERSAL:${result.reversalId}\""))
        assertTrue(manual.contains("eventKey = \"MANUAL_RETURN:${result.manualReturnId}\""))
        assertTrue(operations.contains("EXCHANGE(\"両替\", 0)"))
        assertTrue(settings.contains("drawer_standalone_enabled INTEGER NOT NULL DEFAULT 0"))
        assertTrue(settings.contains("drawer_open_reason_required INTEGER NOT NULL DEFAULT 1"))
    }
}
