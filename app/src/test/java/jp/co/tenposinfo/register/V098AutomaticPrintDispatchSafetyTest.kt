package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V098AutomaticPrintDispatchSafetyTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    private fun sale(
        id: Long,
        createdAt: Long,
        status: PrintJobStatus = PrintJobStatus.PENDING,
    ) = PrintJobRecord(
        id = id,
        saleId = id + 1000,
        paperWidthMm = 80,
        status = status,
        attemptCount = 0,
        lastError = null,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun document(
        id: Long,
        createdAt: Long,
        status: PrintJobStatus = PrintJobStatus.PENDING,
    ) = DocumentPrintJobRecord(
        id = id,
        documentType = OperationDocumentType.SETTLEMENT_REPORT,
        referenceId = id + 2000,
        paperWidthMm = 80,
        status = status,
        attemptCount = 0,
        lastError = null,
        payloadText = "test",
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    @Test
    fun oldestCandidateIsSharedAcrossSaleAndDocumentQueues() {
        assertEquals(
            AutomaticPrintCandidateSource.DOCUMENT,
            AutomaticPrintQueuePolicy.oldestCandidate(
                saleJob = sale(1, 200),
                documentJobs = listOf(document(2, 100)),
            )?.source,
        )
        assertEquals(
            AutomaticPrintCandidateSource.SALE_RECEIPT,
            AutomaticPrintQueuePolicy.oldestCandidate(
                saleJob = sale(1, 100),
                documentJobs = listOf(document(2, 200)),
            )?.source,
        )
    }

    @Test
    fun documentSelectionUsesOldestPrintableInsteadOfNewestListOrder() {
        val candidate = AutomaticPrintQueuePolicy.oldestCandidate(
            saleJob = null,
            documentJobs = listOf(
                document(30, 300),
                document(10, 100),
                document(20, 200),
            ),
        )
        assertEquals(10L, candidate?.sourceId)
        assertEquals(100L, candidate?.createdAt)
    }

    @Test
    fun completedFailedAndDiscardedJobsAreNotAutomaticCandidates() {
        assertNull(
            AutomaticPrintQueuePolicy.oldestCandidate(
                saleJob = sale(1, 100, PrintJobStatus.COMPLETED),
                documentJobs = listOf(
                    document(2, 90, PrintJobStatus.FAILED),
                    document(3, 80, PrintJobStatus.DISCARDED),
                ),
            ),
        )
        assertEquals(
            4L,
            AutomaticPrintQueuePolicy.oldestCandidate(
                saleJob = null,
                documentJobs = listOf(document(4, 70, PrintJobStatus.RETRY)),
            )?.sourceId,
        )
    }

    @Test
    fun batchLimitIsGlobalAndFirstFailureStopsTheRun() {
        assertEquals(20, AutomaticPrintQueuePolicy.MAX_JOBS_PER_RUN)
        assertFalse(AutomaticPrintQueuePolicy.batchLimitReached(19))
        assertTrue(AutomaticPrintQueuePolicy.batchLimitReached(20))
        assertFalse(AutomaticPrintQueuePolicy.shouldStopAfterAttempt(true))
        assertTrue(AutomaticPrintQueuePolicy.shouldStopAfterAttempt(false))
    }

    @Test
    fun workerRetriesForFailureOrRemainingBacklog() {
        assertTrue(AutomaticPrintPolicy.shouldRetry(true, attempted = 1, failures = 1))
        assertTrue(
            AutomaticPrintPolicy.shouldRetry(
                configurationUsable = true,
                attempted = 20,
                failures = 0,
                pendingAfterBatch = true,
            ),
        )
        assertFalse(AutomaticPrintPolicy.shouldRetry(true, attempted = 5, failures = 0))
        assertFalse(AutomaticPrintPolicy.shouldRetry(false, attempted = 20, failures = 0, pendingAfterBatch = true))
    }

    @Test
    fun workerSourceUsesSingleFlightSharedOrderingAndFailFastLoop() {
        val source = File(
            root,
            "app/src/main/java/jp/co/tenposinfo/register/AutomaticPrintWorker.kt",
        ).readText()
        assertTrue(source.contains("AutomaticPrintWorkerRunGate.tryAcquire()"))
        assertTrue(source.contains("AutomaticPrintWorkerRunGate.release()"))
        assertTrue(source.contains("while (!AutomaticPrintQueuePolicy.batchLimitReached(attempted))"))
        assertTrue(source.contains("AutomaticPrintQueuePolicy.oldestCandidate("))
        assertTrue(source.contains("AutomaticPrintQueuePolicy.shouldStopAfterAttempt("))
        assertTrue(source.contains("failures++"))
        assertTrue(source.contains("break"))
        assertFalse(source.contains("processed >= MAX_JOBS_PER_RUN"))
        assertFalse(source.contains("index < MAX_JOBS_PER_RUN"))
    }

    @Test
    fun existingUnknownDeliveryAndPrinterProtocolSafetyRemainInPlace() {
        val receipt = File(root, "app/src/main/java/jp/co/tenposinfo/register/Receipt.kt").readText()
        val documentSafety = File(root, "app/src/main/java/jp/co/tenposinfo/register/DocumentPrintSafety.kt").readText()
        val capability = File(root, "app/src/main/java/jp/co/tenposinfo/register/PrinterStatusCapability.kt").readText()
        assertTrue(receipt.contains("PrinterDeliveryPhase.WRITE_STARTED"))
        assertTrue(receipt.contains("MANUAL_CONFIRMATION_REQUIRED"))
        assertTrue(documentSafety.contains("UNKNOWN_DELIVERY_PREFIX"))
        assertTrue(documentSafety.contains("PrintJobStatus.FAILED"))
        assertTrue(capability.contains("PrinterProfile.EPSON_TM_JAPAN"))
        assertTrue(capability.contains("automaticQueryAllowed = true"))
    }

    @Test
    fun v098ReleaseIdentityAndSafetyRemainDocumentedWhileCurrentCiKeepsFlags() {
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        val notes = File(root, "docs/V0.98_RELEASE_NOTES.md").readText()
        val requirements = File(root, "docs/V0.98_AUTOMATIC_PRINT_DISPATCH_SAFETY.md").readText()

        assertTrue(notes.contains("versionCode `128`"))
        assertTrue(notes.contains("0.98.0-dev.1"))
        assertTrue(workflow.contains("AUTOMATIC_PRINT_DISPATCH_SAFETY=true"))
        assertTrue(workflow.contains("AUTOMATIC_PRINT_GLOBAL_BATCH_LIMIT=20"))
        assertTrue(workflow.contains("AUTOMATIC_PRINT_SINGLE_FLIGHT=true"))
        assertTrue(notes.contains("最終総合実機試験へ繰越"))
        assertTrue(requirements.contains("最終総合実機試験へ繰越"))
    }

    @Test
    fun dispatchSafetyChangeDoesNotAddDestructiveSalesDataStatements() {
        val worker = File(root, "app/src/main/java/jp/co/tenposinfo/register/AutomaticPrintWorker.kt").readText()
        val requirements = File(root, "docs/V0.98_AUTOMATIC_PRINT_DISPATCH_SAFETY.md").readText()
        val source = worker + requirements
        assertFalse(source.contains("DELETE FROM sales", ignoreCase = true))
        assertFalse(source.contains("UPDATE sales", ignoreCase = true))
        assertFalse(source.contains("DROP TABLE", ignoreCase = true))
    }
}
