package jp.co.tenposinfo.register

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V012PrinterHealthMonitorTest {
    @Test
    fun `warning and error states require attention`() {
        assertTrue(PrinterHealthPolicy.requiresAttention(snapshot(PrinterHealthLevel.WARNING)))
        assertTrue(PrinterHealthPolicy.requiresAttention(snapshot(PrinterHealthLevel.ERROR)))
        assertTrue(PrinterHealthPolicy.requiresAttention(snapshot(PrinterHealthLevel.UNCONFIGURED)))
    }

    @Test
    fun `ready disabled and checking states do not require attention`() {
        assertFalse(PrinterHealthPolicy.requiresAttention(snapshot(PrinterHealthLevel.READY)))
        assertFalse(PrinterHealthPolicy.requiresAttention(snapshot(PrinterHealthLevel.DISABLED)))
        assertFalse(PrinterHealthPolicy.requiresAttention(snapshot(PrinterHealthLevel.CHECKING)))
    }

    private fun snapshot(level: PrinterHealthLevel) = PrinterHealthSnapshot(
        level = level,
        title = "test",
        detail = "test",
        checkedAt = 0L,
        printerName = "test",
    )
}
