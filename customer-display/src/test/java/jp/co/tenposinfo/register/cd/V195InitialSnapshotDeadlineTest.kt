package jp.co.tenposinfo.register.cd

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Initial socket handshake must not strand CD with a permanent blank screen. */
class V195InitialSnapshotDeadlineTest {
    private fun source(): String = File(
        "src/main/java/jp/co/tenposinfo/register/cd/CustomerDisplayWebSocketClient.kt",
    ).readText()

    @Test fun beforeAndAtDeadlineWithoutSnapshot() {
        val at = 1_000_000_000L
        val duration = TimeUnit.MILLISECONDS.toNanos(
            CustomerDisplayInitialSnapshotDeadlineV195.MAX_AWAIT_MILLIS,
        )
        assertFalse(CustomerDisplayInitialSnapshotDeadlineV195.exceeded(at, false, at))
        assertFalse(CustomerDisplayInitialSnapshotDeadlineV195.exceeded(at, false, at + duration - 1))
        assertTrue(CustomerDisplayInitialSnapshotDeadlineV195.exceeded(at, false, at + duration))
        assertTrue(CustomerDisplayInitialSnapshotDeadlineV195.exceeded(at, false, at + duration + 1))
        assertFalse(CustomerDisplayInitialSnapshotDeadlineV195.exceeded(at, false, at - 1))
    }

    @Test fun snapshotStopsTheInitialDeadlineEvenAfterLongConnection() {
        val started = 23L
        val later = started + TimeUnit.HOURS.toNanos(1)
        assertFalse(CustomerDisplayInitialSnapshotDeadlineV195.exceeded(started, true, later))
        assertTrue(CustomerDisplayInitialSnapshotDeadlineV195.exceeded(started, false, later))
    }

    @Test fun mainFrameReaderChecksBothTrafficAndSocketTimeoutPaths() {
        val reader = source().substringAfter("    private fun readFrames(")
            .substringBefore("    private fun notifyConnected()")
        assertTrue(reader.contains("val connectedAtNanos = System.nanoTime()"))
        assertTrue(reader.contains("CustomerDisplayInitialSnapshotDeadlineV195.exceeded("))
        assertTrue(reader.contains("throw EOFException(CustomerDisplayInitialSnapshotDeadlineV195.RECONNECT_MESSAGE)"))
        val mainCheck = reader.indexOf("CustomerDisplayInitialSnapshotDeadlineV195.exceeded(")
        val inputRead = reader.indexOf("val first = try {")
        val idleCheck = reader.lastIndexOf("CustomerDisplayInitialSnapshotDeadlineV195.exceeded(")
        assertTrue(mainCheck in 0 until inputRead)
        assertTrue(idleCheck > inputRead)
        assertTrue(reader.contains("previousSnapshot != null, System.nanoTime()"))
        assertTrue(reader.contains("previousSnapshot = incoming"))
        assertTrue(reader.contains("CustomerDisplayDeliveryPolicy.decide(previousSnapshot, incoming, awaitingSnapshot)"))
    }

    @Test fun noPrematureTrustOnHandshakeOrKeepalive() {
        val s = source()
        assertTrue(s.contains("onHandshakeComplete()"))
        assertTrue(s.contains("notifyConnected()"))
        assertTrue(s.contains("latestSnapshot = null"))
        assertTrue(s.contains("reconnectLoop()"))
        assertTrue(s.contains("CustomerDisplayResyncDeadlineV194.exceeded("))
        assertTrue(s.contains("CustomerDisplayInitialSnapshotDeadlineV195.exceeded("))
    }
}
