package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Test

class V012PrinterHistoryRetentionTest {
    @Test
    fun retentionDaysAreBounded() {
        assertEquals(1, PrinterHistoryRetentionPolicy.normalize(0))
        assertEquals(30, PrinterHistoryRetentionPolicy.normalize(30))
        assertEquals(365, PrinterHistoryRetentionPolicy.normalize(999))
    }

    @Test
    fun cutoffUsesNormalizedRetention() {
        val day = 24L * 60L * 60L * 1_000L
        val now = 400L * day
        assertEquals(399L * day, PrinterHistoryRetentionPolicy.cutoff(now, 0))
        assertEquals(370L * day, PrinterHistoryRetentionPolicy.cutoff(now, 30))
        assertEquals(35L * day, PrinterHistoryRetentionPolicy.cutoff(now, 999))
    }
}
