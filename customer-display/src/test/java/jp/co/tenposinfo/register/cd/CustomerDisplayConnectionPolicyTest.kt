package jp.co.tenposinfo.register.cd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerDisplayConnectionPolicyTest {
    @Test
    fun screenTransitionGraceKeepsSocketAcrossActivityRecreation() {
        assertEquals(4_000L, CustomerDisplayConnectionPolicy.SCREEN_TRANSITION_GRACE_MS)
    }

    @Test
    fun establishedConnectionRestartsFromFastRetry() {
        assertEquals(
            500L,
            CustomerDisplayConnectionPolicy.retryDelayMillis(
                failedAttempts = 4,
                hadEstablishedConnection = true,
            ),
        )
        assertEquals(
            0,
            CustomerDisplayConnectionPolicy.nextFailedAttemptCount(
                failedAttempts = 4,
                hadEstablishedConnection = true,
            ),
        )
    }

    @Test
    fun repeatedInitialFailuresUseBoundedBackoff() {
        assertEquals(500L, CustomerDisplayConnectionPolicy.retryDelayMillis(0, false))
        assertEquals(1_000L, CustomerDisplayConnectionPolicy.retryDelayMillis(1, false))
        assertEquals(2_000L, CustomerDisplayConnectionPolicy.retryDelayMillis(2, false))
        assertEquals(5_000L, CustomerDisplayConnectionPolicy.retryDelayMillis(3, false))
        assertEquals(10_000L, CustomerDisplayConnectionPolicy.retryDelayMillis(99, false))
        assertEquals(4, CustomerDisplayConnectionPolicy.nextFailedAttemptCount(99, false))
    }

    @Test
    fun heartbeatDetectsHalfOpenConnectionWithinBoundedTime() {
        assertEquals(15_000, CustomerDisplayConnectionPolicy.HEARTBEAT_INTERVAL_MS)
        assertEquals(1, CustomerDisplayConnectionPolicy.MAX_MISSED_HEARTBEATS)
        val maximumDetectionMillis =
            CustomerDisplayConnectionPolicy.HEARTBEAT_INTERVAL_MS.toLong() *
                (CustomerDisplayConnectionPolicy.MAX_MISSED_HEARTBEATS + 1)
        assertTrue(maximumDetectionMillis <= 30_000L)
    }
}
