package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V032PrintQueueOperationsTest {
    private fun source(name: String) =
        File("src/main/java/jp/co/tenposinfo/register/$name").readText()

    private fun job(
        status: PrintJobStatus = PrintJobStatus.FAILED,
        type: UnifiedPrintJobType = UnifiedPrintJobType.SALE_RECEIPT,
        attempts: Int = 1,
        error: String? = "connection refused",
        createdAt: Long = 1_000L,
        referenceId: Long = 100L,
    ) = UnifiedPrintJob(
        key = "SALE:1",
        sourceId = 1L,
        type = type,
        referenceId = referenceId,
        paperWidthMm = 80,
        status = status,
        attemptCount = attempts,
        lastError = error,
        previewText = "テスト印字",
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    @Test
    fun discardedIsTerminalAndDoesNotRecoverToAnotherStatus() {
        assertFalse(UnifiedPrintJobActionPolicy.mayRetry(PrintJobStatus.DISCARDED))
        assertFalse(UnifiedPrintJobActionPolicy.mayPrint(PrintJobStatus.DISCARDED))
        assertFalse(UnifiedPrintJobActionPolicy.mayDiscard(PrintJobStatus.DISCARDED))
        assertEquals(
            PrintJobStatus.DISCARDED,
            InterruptedPrintRecoveryPolicy.recoveredStatus(PrintJobStatus.DISCARDED),
        )
    }

    @Test
    fun onlyUnfinishedActionableJobsCanBeOperated() {
        for (status in listOf(PrintJobStatus.PENDING, PrintJobStatus.RETRY, PrintJobStatus.FAILED)) {
            assertTrue(UnifiedPrintJobActionPolicy.mayRetry(status))
            assertTrue(UnifiedPrintJobActionPolicy.mayPrint(status))
            assertTrue(UnifiedPrintJobActionPolicy.mayDiscard(status))
        }
        for (status in listOf(PrintJobStatus.PRINTING, PrintJobStatus.COMPLETED, PrintJobStatus.DISCARDED)) {
            assertFalse(UnifiedPrintJobActionPolicy.mayRetry(status))
            assertFalse(UnifiedPrintJobActionPolicy.mayPrint(status))
            assertFalse(UnifiedPrintJobActionPolicy.mayDiscard(status))
        }
    }

    @Test
    fun failureReasonIsClassifiedForOperatorGuidance() {
        assertEquals(UnifiedPrintFailureCategory.CONNECTION, UnifiedPrintFailureClassifier.classify("connection refused"))
        assertEquals(UnifiedPrintFailureCategory.TIMEOUT, UnifiedPrintFailureClassifier.classify("read timed out"))
        assertEquals(UnifiedPrintFailureCategory.PAPER, UnifiedPrintFailureClassifier.classify("用紙切れ"))
        assertEquals(UnifiedPrintFailureCategory.COVER, UnifiedPrintFailureClassifier.classify("cover open"))
        assertEquals(UnifiedPrintFailureCategory.CUTTER, UnifiedPrintFailureClassifier.classify("カッターエラー"))
        assertEquals(
            UnifiedPrintFailureCategory.DELIVERY_UNKNOWN,
            UnifiedPrintFailureClassifier.classify("${DocumentPrintFailurePolicy.UNKNOWN_DELIVERY_PREFIX} connection reset"),
        )
        assertEquals(
            UnifiedPrintFailureCategory.INTERRUPTED,
            UnifiedPrintFailureClassifier.classify(InterruptedPrintRecoveryPolicy.ERROR_MESSAGE),
        )
        assertEquals(UnifiedPrintFailureCategory.NONE, UnifiedPrintFailureClassifier.classify(null))
    }

    @Test
    fun queueFiltersByStatusTypeTimeAttemptsAndQuery() {
        val now = 40L * 24L * 60L * 60L * 1_000L
        val jobs = listOf(
            job(status = PrintJobStatus.FAILED, type = UnifiedPrintJobType.SALE_RECEIPT, attempts = 3, createdAt = now - 1_000L, referenceId = 501L),
            job(status = PrintJobStatus.COMPLETED, type = UnifiedPrintJobType.SETTLEMENT_REPORT, attempts = 1, createdAt = now - 2_000L, referenceId = 502L),
            job(status = PrintJobStatus.DISCARDED, type = UnifiedPrintJobType.REVERSAL_RECEIPT, attempts = 0, createdAt = now - 8L * 24L * 60L * 60L * 1_000L, referenceId = 503L),
        )
        val criteria = UnifiedPrintQueueCriteria(
            status = UnifiedPrintStatusFilter.ACTION_REQUIRED,
            type = UnifiedPrintTypeFilter.SALE,
            time = UnifiedPrintTimeFilter.LAST_7_DAYS,
            attempts = UnifiedPrintAttemptFilter.THREE_OR_MORE,
            query = "501",
        )
        assertEquals(listOf(501L), UnifiedPrintQueueFilterPolicy.filter(jobs, criteria, now).map { it.referenceId })
    }

    @Test
    fun summarySeparatesDiscardedFromActiveJobs() {
        val summary = UnifiedPrintQueueSummary.from(
            listOf(
                job(status = PrintJobStatus.PENDING),
                job(status = PrintJobStatus.RETRY),
                job(status = PrintJobStatus.FAILED),
                job(status = PrintJobStatus.PRINTING),
                job(status = PrintJobStatus.COMPLETED),
                job(status = PrintJobStatus.DISCARDED),
            ),
        )
        assertEquals(4, summary.active)
        assertEquals(2, summary.actionRequired)
        assertEquals(1, summary.discarded)
    }

    @Test
    fun destructiveActionsRequireManagerApprovalAndAudit() {
        val queue = source("UnifiedPrintQueue.kt")
        val activity = source("UnifiedPrintQueueActivity.kt")
        val settings = source("AdminSettingsStore.kt")

        assertTrue(queue.contains("settingsStore.managerNameForPin(managerPin)"))
        assertTrue(queue.contains("PRINT_JOB_FORCE_SEND_SUCCEEDED"))
        assertTrue(queue.contains("PRINT_JOB_FORCE_SEND_FAILED"))
        assertTrue(queue.contains("PRINT_JOB_RETRY_REQUESTED"))
        assertTrue(settings.contains("fun recordOperationalAudit("))
        assertTrue(source("RegisterDatabase.kt").contains("writableDatabase.runInTransaction"))
        assertTrue(source("AdvancedOperationsStore.kt").contains("db.beginTransaction()"))
        assertTrue(source("RegisterDatabase.kt").contains("PRINT_JOB_DISCARDED"))
        assertTrue(source("AdvancedOperationsStore.kt").contains("PRINT_JOB_DISCARDED"))
        assertTrue(activity.contains("紙が出ていないことを目視確認しました"))
        assertTrue(activity.contains("破棄理由（4文字以上・監査ログへ保存）"))
        assertTrue(activity.contains("責任者承認付き強制印刷"))
    }

    @Test
    fun discardedJobsAreExcludedFromSettlementPendingCount() {
        for (name in listOf("OperationsStore.kt", "AdvancedOperationsStore.kt")) {
            val text = source(name)
            assertTrue(text.contains("status NOT IN (?, ?)"))
            assertTrue(text.contains("PrintJobStatus.DISCARDED.name"))
        }
    }

    @Test
    fun queueScreenUsesSharedResponsivePolicyAndNoPrintTimeWidthChoice() {
        val activity = source("UnifiedPrintQueueActivity.kt")
        assertTrue(activity.contains("rememberRegisterResponsiveMetrics()"))
        assertTrue(activity.contains("BoxWithConstraints"))
        assertTrue(activity.contains("verticalScroll"))
        assertTrue(activity.contains("印刷時指定なし"))
        assertFalse(activity.contains("ChoiceButton(\"58mm\""))
        assertFalse(activity.contains("ChoiceButton(\"80mm\""))
    }
}
