package jp.co.tenposinfo.register

import java.io.IOException
import org.junit.Assert.assertEquals
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
    fun receiptPaperWidthSelectionRemainsCompatible() {
        assertEquals(ReceiptPaper.MM58, ReceiptPaper.fromWidth(58))
        assertEquals(ReceiptPaper.MM80, ReceiptPaper.fromWidth(80))
    }
}
