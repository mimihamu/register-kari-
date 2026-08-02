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
    fun reconnectWithinGraceNeverMakesDisconnectVisible() {
        val state = CustomerDisplayConnectionVisibilityState()
        state.onSnapshot()

        val loss = state.onTransportLost("screen transition")

        assertEquals(3_000L, loss.delayMillis)
        assertFalse(loss.notifyImmediately)
        assertFalse(state.visibleDisconnected)
        assertTrue(state.shouldPresentAsConnected())

        state.onConnected()

        assertFalse(state.revealDisconnectedIfCurrent(loss.generation))
        assertFalse(state.visibleDisconnected)
        assertTrue(state.shouldPresentAsConnected())
    }

    @Test
    fun outageLongerThanGraceMakesDisconnectVisible() {
        val state = CustomerDisplayConnectionVisibilityState()
        state.onSnapshot()
        val loss = state.onTransportLost("wifi disconnected")

        assertTrue(state.revealDisconnectedIfCurrent(loss.generation))
        assertTrue(state.visibleDisconnected)
        assertFalse(state.shouldPresentAsConnected())
        assertEquals("wifi disconnected", state.latestDisconnectReason)
    }

    @Test
    fun staleTimeoutCannotOverrideNewerConnectionOrLoss() {
        val state = CustomerDisplayConnectionVisibilityState()
        state.onSnapshot()
        val firstLoss = state.onTransportLost("first")
        state.onConnected()
        val secondLoss = state.onTransportLost("second")

        assertFalse(state.revealDisconnectedIfCurrent(firstLoss.generation))
        assertTrue(state.revealDisconnectedIfCurrent(secondLoss.generation))
        assertEquals("second", state.latestDisconnectReason)
    }

    @Test
    fun initialFailureIsVisibleWithoutSnapshot() {
        val state = CustomerDisplayConnectionVisibilityState()
        val loss = state.onTransportLost("unreachable")

        assertTrue(loss.notifyImmediately)
        assertEquals(0L, loss.delayMillis)
        assertTrue(state.visibleDisconnected)
        assertFalse(state.shouldPresentAsConnected())
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
