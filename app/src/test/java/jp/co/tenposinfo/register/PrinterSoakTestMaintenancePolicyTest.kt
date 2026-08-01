package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrinterSoakTestMaintenancePolicyTest {
    @Test
    fun retentionDaysAreClampedToSupportedRange() {
        assertEquals(1, PrinterSoakTestMaintenancePolicy.normalizeRetentionDays(-10))
        assertEquals(90, PrinterSoakTestMaintenancePolicy.normalizeRetentionDays(90))
        assertEquals(365, PrinterSoakTestMaintenancePolicy.normalizeRetentionDays(999))
    }

    @Test
    fun onlyRunningTestIsRecovered() {
        assertTrue(PrinterSoakTestMaintenancePolicy.shouldRecover(PrinterSoakTestRunStatus.RUNNING))
        assertFalse(PrinterSoakTestMaintenancePolicy.shouldRecover(PrinterSoakTestRunStatus.COMPLETED))
        assertFalse(PrinterSoakTestMaintenancePolicy.shouldRecover(PrinterSoakTestRunStatus.STOPPED))
        assertFalse(PrinterSoakTestMaintenancePolicy.shouldRecover(PrinterSoakTestRunStatus.FAILED))
    }

    @Test
    fun recoverySummaryRequiresPaperConfirmationAndDisablesAutomaticResend() {
        val summary = PrinterSoakTestMaintenancePolicy.recoverySummary(7, 20)

        assertTrue(summary.contains("7/20"))
        assertTrue(summary.contains("最後の用紙"))
        assertTrue(summary.contains("自動再開・自動再送は行いません"))
    }

    @Test
    fun recoverySummaryNeverReportsCompletedCountAboveTotal() {
        assertTrue(PrinterSoakTestMaintenancePolicy.recoverySummary(5, 3).contains("5/5"))
    }
}
