package jp.co.tenposinfo.register.cd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerDisplayConnectionPresentationPolicyTest {
    @Test
    fun initialConnectionFailureCanBeShownImmediately() {
        assertEquals(
            0L,
            CustomerDisplayConnectionPresentationPolicy.disconnectDelayMillis(
                hasPresentedSnapshot = false,
            ),
        )
    }

    @Test
    fun establishedDisplayKeepsCurrentScreenDuringShortOutage() {
        assertEquals(
            3_000L,
            CustomerDisplayConnectionPresentationPolicy.disconnectDelayMillis(
                hasPresentedSnapshot = true,
            ),
        )
    }

    @Test
    fun lastSnapshotIsReplayedOnlyWhileDisconnectIsNotVisible() {
        assertTrue(
            CustomerDisplayConnectionPresentationPolicy.shouldReplayLastSnapshot(
                hasPresentedSnapshot = true,
                visibleDisconnected = false,
            ),
        )
        assertFalse(
            CustomerDisplayConnectionPresentationPolicy.shouldReplayLastSnapshot(
                hasPresentedSnapshot = true,
                visibleDisconnected = true,
            ),
        )
        assertFalse(
            CustomerDisplayConnectionPresentationPolicy.shouldReplayLastSnapshot(
                hasPresentedSnapshot = false,
                visibleDisconnected = false,
            ),
        )
    }

    @Test
    fun connectionEventLogIsBoundedAndNewestFirst() {
        CustomerDisplayConnectionEventLog.clear()
        repeat(120) { index ->
            CustomerDisplayConnectionEventLog.record(
                CustomerDisplayConnectionEventType.MODE_CHANGED,
                "event-$index",
            )
        }

        val entries = CustomerDisplayConnectionEventLog.snapshot(limit = 100)
        assertEquals(100, entries.size)
        assertEquals("event-119", entries.first().message)
        assertEquals("event-20", entries.last().message)
    }
}
