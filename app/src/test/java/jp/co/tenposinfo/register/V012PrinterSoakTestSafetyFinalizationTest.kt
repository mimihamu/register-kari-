package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V012PrinterSoakTestSafetyFinalizationTest {
    @Test
    fun epsonIsAllowedForSoakTest() {
        val decision = PrinterSoakTestCapabilityPolicy.evaluate(
            profile = PrinterProfile.EPSON_TM_JAPAN,
            statusProtocol = PrinterStatusProtocol.EPSON_DLE_EOT,
        )

        assertTrue(decision.allowed)
    }

    @Test
    fun starGenericAndNoneAreRejectedBeforeRunCreation() {
        val star = PrinterSoakTestCapabilityPolicy.evaluate(
            PrinterProfile.STAR_ESC_POS,
            PrinterStatusProtocol.ESC_POS_DLE_EOT_COMPATIBLE,
        )
        val generic = PrinterSoakTestCapabilityPolicy.evaluate(
            PrinterProfile.GENERIC_ESC_POS,
            PrinterStatusProtocol.ESC_POS_DLE_EOT_COMPATIBLE,
        )
        val none = PrinterSoakTestCapabilityPolicy.evaluate(
            PrinterProfile.EPSON_TM_JAPAN,
            PrinterStatusProtocol.NONE,
        )

        assertFalse(star.allowed)
        assertFalse(generic.allowed)
        assertFalse(none.allowed)
        assertTrue(star.reason.contains("開始できません"))
        assertTrue(generic.reason.contains("開始できません"))
        assertTrue(none.reason.contains("状態取得非対応"))
    }

    @Test
    fun completedResultIsPreservedOnLateFinishCall() {
        val first = PrinterSoakTestFinishPolicy.resolve(
            currentStatus = PrinterSoakTestRunStatus.RUNNING,
            currentCompletedCount = 0,
            currentSummary = "実行中",
            requestedCompletedCount = 10,
            requestedSummary = "10件完了",
        )
        val second = PrinterSoakTestFinishPolicy.resolve(
            currentStatus = PrinterSoakTestRunStatus.COMPLETED,
            currentCompletedCount = first.completedCount,
            currentSummary = first.summary,
            requestedCompletedCount = 3,
            requestedSummary = "後発の停止処理",
        )

        assertTrue(first.shouldFinalize)
        assertFalse(second.shouldFinalize)
        assertEquals(10, second.completedCount)
        assertEquals("10件完了", second.summary)
    }

    @Test
    fun stoppedAndFailedResultsAreAlsoIdempotent() {
        listOf(
            PrinterSoakTestRunStatus.STOPPED,
            PrinterSoakTestRunStatus.FAILED,
        ).forEach { status ->
            val resolution = PrinterSoakTestFinishPolicy.resolve(
                currentStatus = status,
                currentCompletedCount = 7,
                currentSummary = "既存結果",
                requestedCompletedCount = 1,
                requestedSummary = "後発結果",
            )
            assertFalse(resolution.shouldFinalize)
            assertEquals(7, resolution.completedCount)
            assertEquals("既存結果", resolution.summary)
        }
    }
}
