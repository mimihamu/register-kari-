package jp.co.tenposinfo.register

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V012PrinterMonitoringTest {
    @Test
    fun preflightDisabledAlwaysAllowsAutomaticPrinting() {
        assertTrue(AutomaticPrinterPreflightPolicy.mayContinue(false, null))
    }

    @Test
    fun readyAndWarningAllowAutomaticPrinting() {
        assertTrue(AutomaticPrinterPreflightPolicy.mayContinue(true, status()))
        assertTrue(AutomaticPrinterPreflightPolicy.mayContinue(true, status(paperNearEnd = true)))
    }

    @Test
    fun offlineAndErrorBlockAutomaticPrinting() {
        assertFalse(AutomaticPrinterPreflightPolicy.mayContinue(true, status(online = false)))
        assertFalse(AutomaticPrinterPreflightPolicy.mayContinue(true, status(paperOut = true)))
        assertFalse(AutomaticPrinterPreflightPolicy.mayContinue(true, status(cutterError = true)))
        assertFalse(AutomaticPrinterPreflightPolicy.mayContinue(true, null))
    }

    private fun status(
        online: Boolean = true,
        paperNearEnd: Boolean = false,
        paperOut: Boolean = false,
        cutterError: Boolean = false,
    ) = PrinterRealtimeStatus(
        checkedAt = 1L,
        elapsedMillis = 10L,
        online = online,
        drawerSignalHigh = false,
        waitingForOnlineRecovery = false,
        feedButtonPressed = false,
        coverOpen = false,
        paperFeedStopped = false,
        offlineErrorPresent = false,
        recoverableError = false,
        cutterError = cutterError,
        unrecoverableError = false,
        autoRecoverableError = false,
        paperNearEnd = paperNearEnd,
        paperOut = paperOut,
        protocolValid = true,
        rawStatus = byteArrayOf(0x12, 0x12, 0x12, 0x12),
    )
}
