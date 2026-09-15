package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V136PrintJobUncertainDeliverySafetyTest {
    @Test
    fun sendingAndLegacyPrintingAreUncertainAndNotNormalRetryCandidates() {
        assertTrue(PrintJobUncertainPolicyV136.isUncertain(PrintJobStatus.SENDING))
        assertTrue(PrintJobUncertainPolicyV136.isUncertain(PrintJobStatus.PRINTING))
        assertFalse(UnifiedPrintJobActionPolicy.mayRetry(PrintJobStatus.SENDING))
        assertFalse(UnifiedPrintJobActionPolicy.mayPrint(PrintJobStatus.SENDING))
    }

    @Test
    fun normalAutomaticQueueNeverSelectsSending() {
        val worker = File("src/main/java/jp/co/tenposinfo/register/AutomaticPrintWorker.kt").readText()
        assertTrue(worker.contains("job.status == PrintJobStatus.PENDING || job.status == PrintJobStatus.RETRY"))
        assertFalse(worker.contains("job.status == PrintJobStatus.SENDING ||"))
    }

    @Test
    fun saleAndDocumentClaimsPersistSendingBeforeTransport() {
        val saleDb = File("src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt").readText()
        val documents = File("src/main/java/jp/co/tenposinfo/register/AdvancedOperationsStore.kt").readText()
        assertTrue(saleDb.contains("put(\"status\", PrintJobStatus.SENDING.name)"))
        assertTrue(documents.contains("put(\"status\", PrintJobStatus.SENDING.name)"))
    }

    @Test
    fun uncertainReprintCreatesNewJobAndKeepsLineageReasonAndOperator() {
        val source = File("src/main/java/jp/co/tenposinfo/register/PrintJobUncertainSafetyV136.kt").readText()
        assertTrue(source.contains("put(\"source_job_id\", job.sourceId)"))
        assertTrue(source.contains("put(\"reprint_reason\", reason.trim().take(500))"))
        assertTrue(source.contains("put(\"reprint_operator_id\", operatorId.trim().take(100))"))
        assertTrue(source.contains("PRINT_JOB_UNCERTAIN_REPRINT_CREATED"))
        assertTrue(source.contains("new_job_id=\$newJobId"))
    }

    @Test
    fun uiRequiresExplicitDecisionForPossiblyPrintedJob() {
        val ui = File("src/main/java/jp/co/tenposinfo/register/UnifiedPrintQueueActivity.kt").readText()
        assertTrue(ui.contains("印刷済みの可能性"))
        assertTrue(ui.contains("完了扱い"))
        assertTrue(ui.contains("再印刷"))
        assertTrue(ui.contains("判断理由（4文字以上・監査ログへ保存）"))
    }
}
