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

    @Test
    fun pollingRunsOnlyWhileSalesScreenIsVisibleAndActivityIsResumed() {
        assertTrue(PrinterHealthUiPolicy.shouldPoll(isSalesScreenVisible = true, isActivityResumed = true))
        assertFalse(PrinterHealthUiPolicy.shouldPoll(isSalesScreenVisible = false, isActivityResumed = true))
        assertFalse(PrinterHealthUiPolicy.shouldPoll(isSalesScreenVisible = true, isActivityResumed = false))
        assertFalse(PrinterHealthUiPolicy.shouldPoll(isSalesScreenVisible = false, isActivityResumed = false))
    }

    @Test
    fun checkedTimeIsMarkedStaleAfterThirtySeconds() {
        val snapshot = healthSnapshot(checkedAt = 1_000L)

        assertFalse(PrinterHealthUiPolicy.isStale(snapshot, nowMillis = 30_999L))
        assertTrue(PrinterHealthUiPolicy.isStale(snapshot, nowMillis = 31_000L))
        assertFalse(PrinterHealthUiPolicy.checkedAtLabel(snapshot, 30_999L).contains("古い情報"))
        assertTrue(PrinterHealthUiPolicy.checkedAtLabel(snapshot, 31_000L).contains("古い情報"))
    }

    @Test
    fun neverCheckedSnapshotIsShownAsUnconfirmedAndStale() {
        val snapshot = PrinterHealthSnapshot.checking()

        assertTrue(PrinterHealthUiPolicy.isStale(snapshot, nowMillis = 1L))
        assertTrue(PrinterHealthUiPolicy.checkedAtLabel(snapshot, 1L).contains("未確認"))
    }

    private fun healthSnapshot(checkedAt: Long) = PrinterHealthSnapshot(
        level = PrinterHealthLevel.READY,
        title = "オンライン",
        detail = "127.0.0.1:9100",
        checkedAt = checkedAt,
        printerName = "テストプリンター",
    )

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
