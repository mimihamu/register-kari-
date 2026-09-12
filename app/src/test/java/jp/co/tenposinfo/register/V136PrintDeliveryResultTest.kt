package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V136PrintDeliveryResultTest {
    private fun enabled(profile: PrinterProfile) = PrinterConfiguration(
        host = "192.0.2.10",
        port = 9100,
        enabled = true,
        profile = profile,
    )

    private fun healthyStatus() = PrinterRealtimeStatus(
        checkedAt = 1L,
        elapsedMillis = 1L,
        online = true,
        drawerSignalHigh = false,
        waitingForOnlineRecovery = false,
        feedButtonPressed = false,
        coverOpen = false,
        paperFeedStopped = false,
        offlineErrorPresent = false,
        recoverableError = false,
        cutterError = false,
        unrecoverableError = false,
        autoRecoverableError = false,
        paperNearEnd = false,
        paperOut = false,
        protocolValid = true,
        rawStatus = byteArrayOf(0x12, 0x12, 0x12, 0x12),
    )

    @Test
    fun vendorDocumentedEpsonHealthyPostSendStatusIsPrinted() {
        var queried = false
        val result = PrintDeliveryConfirmationPolicyV136.confirm(enabled(PrinterProfile.EPSON_TM_JAPAN)) {
            queried = true
            Result.success(healthyStatus())
        }
        assertTrue(queried)
        assertEquals(PrintDeliveryResultV136.PRINTED, result.getOrThrow())
    }

    @Test
    fun unverifiedCompatibleProfilesAreAcceptedWithoutAutomaticStatusQuery() {
        listOf(PrinterProfile.STAR_ESC_POS, PrinterProfile.GENERIC_ESC_POS).forEach { profile ->
            var queried = false
            val result = PrintDeliveryConfirmationPolicyV136.confirm(enabled(profile)) {
                queried = true
                Result.success(healthyStatus())
            }
            assertFalse(queried)
            assertEquals(PrintDeliveryResultV136.ACCEPTED, result.getOrThrow())
        }
    }

    @Test
    fun blockingPostSendStatusRequiresManualConfirmationInsteadOfRetry() {
        val result = PrintDeliveryConfirmationPolicyV136.confirm(enabled(PrinterProfile.EPSON_TM_JAPAN)) {
            Result.success(healthyStatus().copy(paperOut = true))
        }
        val error = result.exceptionOrNull()
        assertTrue(error is PrinterDeliveryConfirmationExceptionV136)
        assertTrue(error?.message.orEmpty().contains("送信結果が不明"))
        assertEquals(
            PrinterFailureDisposition.MANUAL_CONFIRMATION_REQUIRED,
            PrinterRetrySafety.classify(error!!),
        )
    }

    @Test
    fun postSendQueryFailureAlsoRequiresManualConfirmation() {
        val result = PrintDeliveryConfirmationPolicyV136.confirm(enabled(PrinterProfile.EPSON_TM_JAPAN)) {
            Result.failure(IllegalStateException("status timeout"))
        }
        val error = result.exceptionOrNull()!!
        assertEquals(
            PrinterFailureDisposition.MANUAL_CONFIRMATION_REQUIRED,
            PrinterRetrySafety.classify(error),
        )
    }

    @Test
    fun resultSchemaIsAdditiveAndLegacyCompletedRowsAreNotBackfilled() {
        val source = File("src/main/java/jp/co/tenposinfo/register/PrintDeliveryResultV136.kt").readText()
        assertTrue(source.contains("ALTER TABLE \$table ADD COLUMN delivery_result TEXT"))
        assertTrue(source.contains("status IN (?, ?)"))
        assertFalse(source.contains("UPDATE print_jobs SET delivery_result"))
        assertFalse(source.contains("UPDATE document_print_jobs SET delivery_result"))
    }

    @Test
    fun automaticAndManualPathsWrapExactJobBeforeNormalCompletionTransition() {
        val worker = File("src/main/java/jp/co/tenposinfo/register/AutomaticPrintWorker.kt").readText()
        val manual = File("src/main/java/jp/co/tenposinfo/register/UnifiedPrintQueue.kt").readText()
        assertTrue(worker.contains("kind = PrintDeliveryJobKindV136.SALE_RECEIPT"))
        assertTrue(worker.contains("kind = PrintDeliveryJobKindV136.DOCUMENT"))
        assertTrue(worker.contains("jobId = candidate.sourceId"))
        assertTrue(manual.contains("kind = PrintDeliveryJobKindV136.SALE_RECEIPT"))
        assertTrue(manual.contains("kind = PrintDeliveryJobKindV136.DOCUMENT"))
        assertTrue(manual.contains("jobId = job.sourceId"))
    }

    @Test
    fun explicitUncertainCompletionRecordsPrintedResult() {
        val source = File("src/main/java/jp/co/tenposinfo/register/PrintJobUncertainSafetyV136.kt").readText()
        assertTrue(source.contains("put(\"delivery_result\", PrintDeliveryResultV136.PRINTED.name)"))
    }
}
