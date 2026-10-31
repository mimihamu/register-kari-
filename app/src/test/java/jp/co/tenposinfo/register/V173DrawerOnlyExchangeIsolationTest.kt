package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** No new exchange amounts in the cash ledger; sales-only drawer opens reject burst taps. */
class V173DrawerOnlyExchangeIsolationTest {
    private val base = File("src/main/java/jp/co/tenposinfo/register")
    private fun src(name: String) = File(base, name).readText()

    @Test fun onlyDepositsAndWithdrawalsCanCreateNewCashMovements() {
        assertTrue(CashMovementEntryPolicyV173.mayRecord(CashMovementType.IN))
        assertTrue(CashMovementEntryPolicyV173.mayRecord(CashMovementType.OUT))
        assertFalse(CashMovementEntryPolicyV173.mayRecord(CashMovementType.EXCHANGE))
        assertTrue(runCatching { CashMovementEntryPolicyV173.requireRecordable(CashMovementType.EXCHANGE) }.isFailure)
        for (name in listOf("OperationsStore.kt", "SecureOperationsCoordinator.kt")) {
            assertTrue(name, src(name).contains("CashMovementEntryPolicyV173.requireRecordable(type)"))
        }
        assertTrue(src("OperationsStore.kt").contains("EXCHANGE(\"両替\", 0)"))
        assertTrue(src("OperationsActivity.kt").contains("CashMovementType.EXCHANGE -> \"±"))
        assertFalse(src("OperationsActivity.kt").contains("OpChoiceButton(\"両替\""))
    }

    @Test fun burstTapCooldownIsMonotonicAndSafeAfterClockReset() {
        val min = SalesExchangeTapPolicyV173.MIN_INTERVAL_MS
        assertEquals(1500L, min)
        assertTrue(SalesExchangeTapPolicyV173.mayStart(5_000L, null))
        assertFalse(SalesExchangeTapPolicyV173.mayStart(5_001L, 5_000L))
        assertFalse(SalesExchangeTapPolicyV173.mayStart(5_000L + min - 1, 5_000L))
        assertTrue(SalesExchangeTapPolicyV173.mayStart(5_000L + min, 5_000L))
        assertTrue(SalesExchangeTapPolicyV173.mayStart(0L, 5_000L))
    }

    @Test fun salesRequestDoesNotTouchAmountsAndStillHasEventAudit() {
        val sales = src("MainActivity.kt")
        assertTrue(sales.contains("android.os.SystemClock.elapsedRealtime()"))
        assertTrue(sales.contains("SalesExchangeTapPolicyV173.mayStart(tappedAt, lastExchangeDrawerTapAt)"))
        assertTrue(sales.contains("lastExchangeDrawerTapAt = tappedAt"))
        assertTrue(sales.contains("if (!exchangeDrawerInProgress &&"))
        assertTrue(sales.contains("enabled = !exchangeDrawerInProgress"))
        assertTrue(sales.contains("Modifier.weight(0.75f).fillMaxHeight()"))
        val drawer=src("SalesExchangeDrawerV172.kt")
        assertTrue(drawer.contains("CashDrawerOpenContextV136.EXCHANGE"))
        assertTrue(drawer.contains("SalesExchangeDrawerV172") || drawer.contains("SalesExchangeDrawerV172.open"))
        assertFalse(drawer.contains("recordCashMovement("))
        assertFalse(drawer.contains("recordSale("))
        assertTrue(drawer.contains("SALES_EXCHANGE:"))
        assertTrue(src("CashDrawerSafetyV136.kt").contains("cash_drawer_events"))
    }
}
