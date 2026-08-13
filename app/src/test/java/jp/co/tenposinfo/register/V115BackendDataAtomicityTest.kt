package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V115BackendDataAtomicityTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    @Test
    fun salePrintQueueUsesAtomicClaimAndIdempotentCompletion() {
        val source = source("RegisterDatabase.kt")
        assertTrue(source.contains("fun claimNextPrintableJob(): PrintJobRecord?"))
        assertTrue(source.contains("fun claimPrintJob(jobId: Long): PrintJobRecord?"))
        assertTrue(source.contains("id = ? AND status = ? AND attempt_count = ?"))
        val completion = source.substring(
            source.indexOf("    fun markPrintCompleted(jobId: Long)"),
            source.indexOf("    fun markPrintFailed", source.indexOf("    fun markPrintCompleted(jobId: Long)")),
        )
        assertTrue(completion.contains("if (current.status == PrintJobStatus.COMPLETED)"))
        assertTrue(completion.indexOf("check(updated == 1)") < completion.indexOf("print_count = print_count + 1"))
        assertFalse(source.contains("private fun updatePrintJob("))
        val discard = source.substring(source.indexOf("    fun discardPrintJob("), source.indexOf("    private fun loadPrintJob(", source.indexOf("    fun discardPrintJob(")))
        assertTrue(discard.contains("val current = loadPrintJob(jobId)"))
        assertFalse(discard.contains("listPrintJobs(500)"))
    }

    @Test
    fun automaticAndManualSalePrintingClaimBeforeSend() {
        val automatic = source("Receipt.kt").substringAfter("class PrintQueueProcessor(")
        assertTrue(automatic.contains("database.claimNextPrintableJob()"))
        assertFalse(automatic.contains("database.markPrintStarted(job.id)"))
        val unified = source("UnifiedPrintQueue.kt")
        val sale = unified.substring(
            unified.indexOf("    private fun printSaleJob("),
            unified.indexOf("    private fun requireCurrentStatus", unified.indexOf("    private fun printSaleJob(")),
        )
        assertTrue(sale.contains("salesDatabase.loadPrintJob"))
        assertTrue(sale.contains("salesDatabase.claimPrintJob"))
        assertFalse(sale.contains("listPrintJobs(500)"))
    }

    @Test
    fun documentQueueUsesExactLookupAndCompareAndSet() {
        val source = source("AdvancedOperationsStore.kt")
        assertTrue(source.contains("fun loadDocumentPrintJob(jobId: Long)"))
        val process = source.substring(
            source.indexOf("    fun processDocumentPrint(jobId: Long"),
            source.indexOf("    private fun SQLiteDatabase.insertDocumentJob", source.indexOf("    fun processDocumentPrint(jobId: Long")),
        )
        assertTrue(process.contains("id = ? AND status = ? AND attempt_count = ?"))
        assertTrue(process.contains("if (claimed != 1)"))
        assertTrue(process.contains("PrintJobStatus.PRINTING.name"))
        assertTrue(process.contains("PrinterRetrySafety.classify(error)"))
        assertTrue(process.contains("PrinterFailureDisposition.MANUAL_CONFIRMATION_REQUIRED"))
        assertFalse(process.contains("listDocumentPrintJobs(500)"))
    }

    @Test
    fun reversalAndCashOperationsAreFencedInsideTransactions() {
        val source = source("AdvancedOperationsStore.kt")
        val reversalTx = source.indexOf("val reversalId = db.transaction {")
        val reversalInsert = source.indexOf("\"reversal_transactions\"", reversalTx)
        assertTrue(source.indexOf("requireSessionStillOpen(session.id)", reversalTx) in (reversalTx + 1) until reversalInsert)
        assertTrue(source.indexOf("requireReversalSnapshotCurrent(selected, type)", reversalTx) in (reversalTx + 1) until reversalInsert)
        val cashTx = source.indexOf("return db.transaction {", source.indexOf("fun recordCashMovement"))
        val cashInsert = source.indexOf("\"cash_movements\"", cashTx)
        assertTrue(source.indexOf("requireSessionStillOpen(session.id)", cashTx) in (cashTx + 1) until cashInsert)
        val settlementTx = source.indexOf("val reportId = db.transaction {", source.indexOf("fun recordSettlement"))
        val settlementInsert = source.indexOf("\"settlement_reports\"", settlementTx)
        assertTrue(source.indexOf("requireSessionStillOpen(session.id)", settlementTx) in (settlementTx + 1) until settlementInsert)
        assertTrue(source.indexOf("val summary = dailySummary", settlementTx) in (settlementTx + 1) until settlementInsert)
    }

    @Test
    fun reversalPolicyRejectsStaleSnapshotAndOverReturn() {
        ReversalConcurrencySafetyV115.requireSnapshotUnchanged(3, 0, 0, 0, 0, 2, false)
        val stale = runCatching {
            ReversalConcurrencySafetyV115.requireSnapshotUnchanged(3, 0, 0, 1, 0, 1, false)
        }
        assertTrue(stale.isFailure)
        val over = runCatching {
            ReversalConcurrencySafetyV115.requireSnapshotUnchanged(3, 2, 20, 2, 20, 2, false)
        }
        assertTrue(over.isFailure)
    }

    @Test
    fun releaseIdentityDocsAndCiFlagsArePresent() {
        val gradle = File(root, "app/build.gradle.kts").readText()
        assertTrue(gradle.contains("versionCode = 145"))
        assertTrue(gradle.contains("versionName = \"1.15.0-dev.1\""))
        assertTrue(File(root, "docs/V1.15_BACKEND_DATA_ATOMICITY.md").isFile)
        assertTrue(File(root, "docs/V1.15_RELEASE_NOTES.md").readText().contains("最終総合実機試験"))
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        assertTrue(workflow.contains("BACKEND_DATA_PRINT_CAS=true"))
        assertTrue(workflow.contains("BACKEND_DATA_PRINT_COMPLETION_IDEMPOTENT=true"))
        assertTrue(workflow.contains("BACKEND_DATA_REVERSAL_FENCING=true"))
        assertTrue(workflow.contains("BACKEND_DATA_SESSION_FENCING=true"))
        assertTrue(workflow.contains("BACKEND_DATA_SETTLEMENT_SNAPSHOT_ATOMIC=true"))
        assertTrue(workflow.contains("BACKEND_DATA_DOCUMENT_PRINT_UNCERTAIN_NO_RETRY=true"))
    }

    private fun source(name: String): String =
        File(root, "app/src/main/java/jp/co/tenposinfo/register/$name").readText()
}
