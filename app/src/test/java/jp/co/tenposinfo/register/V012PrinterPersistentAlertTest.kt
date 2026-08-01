package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V012PrinterPersistentAlertTest {
    @Test
    fun errorStartsIncidentWithoutImmediateNotification() {
        val decision = PrinterPersistentAlertPolicy.evaluate(
            previous = PrinterPersistentAlertState(),
            level = PrinterHealthLevel.ERROR,
            nowMillis = 1_000L,
        )

        assertTrue(decision.active)
        assertEquals(1_000L, decision.incidentStartedAt)
        assertEquals(0L, decision.durationMillis)
        assertFalse(decision.notificationDue)
    }

    @Test
    fun notificationBecomesDueAfterOneMinute() {
        val decision = PrinterPersistentAlertPolicy.evaluate(
            previous = PrinterPersistentAlertState(incidentStartedAt = 1_000L),
            level = PrinterHealthLevel.ERROR,
            nowMillis = 61_000L,
        )

        assertTrue(decision.notificationDue)
        assertEquals(60_000L, decision.durationMillis)
    }

    @Test
    fun notificationDoesNotRepeatBeforeThirtyMinutes() {
        val decision = PrinterPersistentAlertPolicy.evaluate(
            previous = PrinterPersistentAlertState(
                incidentStartedAt = 1_000L,
                lastNotifiedAt = 61_000L,
            ),
            level = PrinterHealthLevel.ERROR,
            nowMillis = 61_000L + PrinterPersistentAlertPolicy.REMIND_AFTER_MILLIS - 1L,
        )

        assertFalse(decision.notificationDue)
    }

    @Test
    fun notificationRepeatsAfterThirtyMinutes() {
        val decision = PrinterPersistentAlertPolicy.evaluate(
            previous = PrinterPersistentAlertState(
                incidentStartedAt = 1_000L,
                lastNotifiedAt = 61_000L,
            ),
            level = PrinterHealthLevel.ERROR,
            nowMillis = 61_000L + PrinterPersistentAlertPolicy.REMIND_AFTER_MILLIS,
        )

        assertTrue(decision.notificationDue)
    }

    @Test
    fun readyClearsIncidentAndNotification() {
        val decision = PrinterPersistentAlertPolicy.evaluate(
            previous = PrinterPersistentAlertState(
                incidentStartedAt = 1_000L,
                lastNotifiedAt = 61_000L,
            ),
            level = PrinterHealthLevel.READY,
            nowMillis = 70_000L,
        )

        assertFalse(decision.active)
        assertTrue(decision.clearNotification)
        assertEquals(0L, decision.incidentStartedAt)
    }

    @Test
    fun warningAndDisabledDoNotCreatePersistentAlert() {
        assertFalse(PrinterPersistentAlertPolicy.isAlertable(PrinterHealthLevel.WARNING))
        assertFalse(PrinterPersistentAlertPolicy.isAlertable(PrinterHealthLevel.DISABLED))
        assertTrue(PrinterPersistentAlertPolicy.isAlertable(PrinterHealthLevel.UNCONFIGURED))
    }

    @Test
    fun clockRollbackRestartsIncidentSafely() {
        val decision = PrinterPersistentAlertPolicy.evaluate(
            previous = PrinterPersistentAlertState(incidentStartedAt = 100_000L),
            level = PrinterHealthLevel.ERROR,
            nowMillis = 50_000L,
        )

        assertEquals(50_000L, decision.incidentStartedAt)
        assertEquals(0L, decision.durationMillis)
        assertFalse(decision.notificationDue)
    }
}
