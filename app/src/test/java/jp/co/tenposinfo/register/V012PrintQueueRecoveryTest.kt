package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V012PrintQueueRecoveryTest {
    @Test
    fun interruptedPrintingRequiresManualConfirmation() {
        assertEquals(
            PrintJobStatus.FAILED,
            InterruptedPrintRecoveryPolicy.recoveredStatus(PrintJobStatus.PRINTING),
        )
        assertTrue(InterruptedPrintRecoveryPolicy.ERROR_MESSAGE.contains("紙が出ていないことを確認"))
    }

    @Test
    fun completedAndWaitingJobsAreNotChanged() {
        listOf(
            PrintJobStatus.PENDING,
            PrintJobStatus.RETRY,
            PrintJobStatus.FAILED,
            PrintJobStatus.COMPLETED,
        ).forEach { status ->
            assertEquals(status, InterruptedPrintRecoveryPolicy.recoveredStatus(status))
        }
    }
}
