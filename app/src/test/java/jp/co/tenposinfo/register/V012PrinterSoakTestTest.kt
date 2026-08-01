package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V012PrinterSoakTestTest {
    @Test
    fun planValidationAcceptsConfiguredRange() {
        assertNull(
            PrinterSoakTestPolicy.validationError(
                PrinterSoakTestPlan(totalPrints = 20, intervalMillis = 5_000L, cutEachPrint = false),
            ),
        )
        assertTrue(
            PrinterSoakTestPolicy.validationError(
                PrinterSoakTestPlan(totalPrints = 0, intervalMillis = 5_000L, cutEachPrint = false),
            )!!.contains("印刷回数"),
        )
        assertTrue(
            PrinterSoakTestPolicy.validationError(
                PrinterSoakTestPlan(totalPrints = 20, intervalMillis = 500L, cutEachPrint = false),
            )!!.contains("印刷間隔"),
        )
    }

    @Test
    fun onlyReadyStatusCanSendNextPage() {
        assertTrue(PrinterSoakTestPolicy.canSend(status()))
        assertFalse(PrinterSoakTestPolicy.canSend(status(paperNearEnd = true)))
        assertFalse(PrinterSoakTestPolicy.canSend(status(coverOpen = true)))
        assertFalse(PrinterSoakTestPolicy.canSend(status(cutterError = true)))
    }

    @Test
    fun uncertainDeliveryRequiresPaperConfirmationAndNoAutomaticResend() {
        val message = PrinterSoakTestPolicy.stoppedByFailureMessage(
            PrinterTransportException(
                phase = PrinterDeliveryPhase.WRITE_STARTED,
                cause = IllegalStateException("LAN切断"),
            ),
        )
        assertTrue(message.contains("送信結果が不明"))
        assertTrue(message.contains("自動再送しない"))
    }

    @Test
    fun preWriteFailureStopsForManualRestart() {
        val message = PrinterSoakTestPolicy.stoppedByFailureMessage(
            PrinterTransportException(
                phase = PrinterDeliveryPhase.CONNECTING,
                cause = IllegalStateException("接続失敗"),
            ),
        )
        assertTrue(message.contains("送信前"))
        assertTrue(message.contains("手動で再開"))
    }

    @Test
    fun testPageNeverRequestsDrawerOperation() {
        val configuration = PrinterConfiguration(
            name = "試験機",
            host = "192.0.2.10",
            enabled = true,
            drawerEnabled = true,
        )
        val text = PrinterSoakTestPolicy.pageText(
            sequence = 3,
            total = 20,
            configuration = configuration,
            startedAt = 1_700_000_000_000L,
        )
        assertTrue(text.contains("3 / 20"))
        assertTrue(text.contains("売上レシートではありません"))
        val payload = PrinterCommandEncoder.encodeText(
            text = text,
            configuration = configuration,
            openDrawer = false,
            appendCut = false,
        )
        val drawerCommandPrefix = byteArrayOf(0x1B, 0x70)
        assertEquals(-1, payload.indexOfSubsequence(drawerCommandPrefix))
    }

    private fun status(
        coverOpen: Boolean = false,
        cutterError: Boolean = false,
        paperNearEnd: Boolean = false,
    ): PrinterRealtimeStatus = PrinterRealtimeStatus(
        checkedAt = 1L,
        elapsedMillis = 10L,
        online = true,
        drawerSignalHigh = false,
        waitingForOnlineRecovery = false,
        feedButtonPressed = false,
        coverOpen = coverOpen,
        paperFeedStopped = false,
        offlineErrorPresent = false,
        recoverableError = false,
        cutterError = cutterError,
        unrecoverableError = false,
        autoRecoverableError = false,
        paperNearEnd = paperNearEnd,
        paperOut = false,
        protocolValid = true,
        rawStatus = byteArrayOf(0x12, 0x12, 0x12, 0x12),
    )

    private fun ByteArray.indexOfSubsequence(target: ByteArray): Int {
        if (target.isEmpty()) return 0
        for (index in 0..size - target.size) {
            if (target.indices.all { offset -> this[index + offset] == target[offset] }) return index
        }
        return -1
    }
}
