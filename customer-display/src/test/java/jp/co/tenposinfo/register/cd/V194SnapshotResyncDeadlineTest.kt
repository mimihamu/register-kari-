package jp.co.tenposinfo.register.cd

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reconnect after a lost resync response, even if keepalive traffic continues. */
class V194SnapshotResyncDeadlineTest {
    private fun source() = File(
        "src/main/java/jp/co/tenposinfo/register/cd/CustomerDisplayWebSocketClient.kt",
    ).readText()

    @Test fun deadlineUsesMonotonicElapsedTimeAndExactBoundary() {
        val start = 100_000_000_000L
        val deadline = TimeUnit.MILLISECONDS.toNanos(
            CustomerDisplayResyncDeadlineV194.MAX_AWAIT_MILLIS,
        )
        assertFalse(CustomerDisplayResyncDeadlineV194.exceeded(null, start + deadline))
        assertFalse(CustomerDisplayResyncDeadlineV194.exceeded(start, start))
        assertFalse(CustomerDisplayResyncDeadlineV194.exceeded(start, start + deadline - 1L))
        assertTrue(CustomerDisplayResyncDeadlineV194.exceeded(start, start + deadline))
        assertTrue(CustomerDisplayResyncDeadlineV194.exceeded(start, start + deadline + 1L))
        assertFalse(CustomerDisplayResyncDeadlineV194.exceeded(start, start - 1L))
    }

    @Test fun requestRecordsStartAndAcceptedSnapshotClearsDeadline() {
        val s = source()
        val reader = s.substringAfter("    private fun readFrames(")
            .substringBefore("    private fun notifyConnected()")
        assertTrue(reader.contains("var snapshotRequestStartedNanos: Long? = null"))
        assertTrue(reader.contains("snapshotRequestStartedNanos = System.nanoTime()"))
        assertTrue(reader.contains("snapshotRequestStartedNanos = null"))
        assertTrue(reader.indexOf("snapshotRequestStartedNanos = System.nanoTime()") <
            reader.indexOf("writeMaskedFrame(output, 0x1, request.toByteArray"))
    }

    @Test fun continuousUpdatesAndIdleTimeoutBothTriggerReconnect() {
        val reader = source().substringAfter("    private fun readFrames(")
            .substringBefore("    private fun notifyConnected()")
        assertTrue(reader.contains("while (running.get()) {\n            // The POS"))
        assertTrue(reader.contains("CustomerDisplayResyncDeadlineV194.exceeded("))
        assertTrue(reader.contains("throw EOFException(CustomerDisplayResyncDeadlineV194.RECONNECT_MESSAGE)"))
        assertTrue(reader.contains("} catch (_: SocketTimeoutException) {"))
        assertTrue(reader.indexOf("snapshotRequestStartedNanos, System.nanoTime()") <
            reader.indexOf("val first = try {"))
        assertTrue(reader.indexOf("val first = try {") <
            reader.lastIndexOf("snapshotRequestStartedNanos, System.nanoTime()"))
        assertTrue(source().contains("reconnectLoop()"))
        assertTrue(source().contains("connectAndRead {"))
    }

    @Test fun staleAmountIsHiddenDuringResyncAndNewTransport() {
        val s = source()
        assertTrue(s.contains("notifyResyncRequired()"))
        assertTrue(s.contains("latestSnapshot = null"))
        assertTrue(s.contains("previousSnapshot = incoming"))
        assertTrue(s.contains("CustomerDisplayDeliveryPolicy.decide(previousSnapshot, incoming, awaitingSnapshot)"))
    }
}
