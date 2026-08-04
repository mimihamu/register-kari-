package jp.co.tenposinfo.register

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V012PrinterReliabilityTest {
    @Test
    fun failureBeforeWriteCanBeAutomaticallyRetried() {
        assertEquals(
            PrinterFailureDisposition.SAFE_TO_RETRY,
            PrinterRetrySafety.classify(PrinterDeliveryPhase.CONNECTING),
        )
        assertEquals(
            PrinterFailureDisposition.SAFE_TO_RETRY,
            PrinterRetrySafety.classify(PrinterDeliveryPhase.CONNECTED),
        )
    }

    @Test
    fun failureAfterWriteStartedRequiresManualConfirmation() {
        assertEquals(
            PrinterFailureDisposition.MANUAL_CONFIRMATION_REQUIRED,
            PrinterRetrySafety.classify(PrinterDeliveryPhase.WRITE_STARTED),
        )
        assertEquals(
            PrinterFailureDisposition.MANUAL_CONFIRMATION_REQUIRED,
            PrinterRetrySafety.classify(PrinterDeliveryPhase.FLUSHED),
        )
    }

    @Test
    fun wrappedUnknownDeliveryFailureIsDetected() {
        val transport = PrinterTransportException(
            PrinterDeliveryPhase.WRITE_STARTED,
            IOException("connection reset"),
        )
        val wrapped = IllegalStateException("worker failed", transport)
        assertEquals(
            PrinterFailureDisposition.MANUAL_CONFIRMATION_REQUIRED,
            PrinterRetrySafety.classify(wrapped),
        )
        assertTrue(transport.message.orEmpty().contains("自動再試行しません"))
    }

    @Test
    fun ordinaryFailureRemainsRetryable() {
        assertEquals(
            PrinterFailureDisposition.SAFE_TO_RETRY,
            PrinterRetrySafety.classify(IOException("connection refused")),
        )
    }

    @Test
    fun operationDocumentUnknownDeliveryStopsAutomaticRetry() {
        val error = PrinterTransportException(
            PrinterDeliveryPhase.WRITE_STARTED,
            IOException("connection reset"),
        )
        assertEquals(PrintJobStatus.FAILED, DocumentPrintFailurePolicy.statusAfterFailure(1, error))
        assertTrue(DocumentPrintFailurePolicy.shouldStopAutomaticRetry(error))
        assertTrue(DocumentPrintFailurePolicy.shouldStopAutomaticRetry(error.message))
        assertTrue(error.message.orEmpty().startsWith(DocumentPrintFailurePolicy.UNKNOWN_DELIVERY_PREFIX))
    }

    @Test
    fun operationDocumentConnectionFailureCanRetryUntilLimit() {
        val error = PrinterTransportException(
            PrinterDeliveryPhase.CONNECTING,
            IOException("connection refused"),
        )
        assertEquals(PrintJobStatus.RETRY, DocumentPrintFailurePolicy.statusAfterFailure(1, error))
        assertEquals(PrintJobStatus.FAILED, DocumentPrintFailurePolicy.statusAfterFailure(5, error))
        assertFalse(DocumentPrintFailurePolicy.shouldStopAutomaticRetry(error))
        assertFalse(DocumentPrintFailurePolicy.shouldStopAutomaticRetry(error.message))
    }

    @Test
    fun printerSettingControlsReceiptPaperWidth() {
        assertEquals(58, PrinterPaperSettingPolicy.normalizeWidthMm(58))
        assertEquals(80, PrinterPaperSettingPolicy.normalizeWidthMm(80))
        assertEquals(ReceiptPaper.MM58, PrinterPaperSettingPolicy.paper(PrinterConfiguration(paperWidthMm = 58)))
        assertEquals(ReceiptPaper.MM80, PrinterPaperSettingPolicy.paper(PrinterConfiguration(paperWidthMm = 80)))
    }
}
