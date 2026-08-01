package jp.co.tenposinfo.register

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V012PrinterStatusTest {
    @Test
    fun requestQueriesFourRealtimeStatusesInOneBatch() {
        assertArrayEquals(
            byteArrayOf(
                0x10, 0x04, 0x01,
                0x10, 0x04, 0x02,
                0x10, 0x04, 0x03,
                0x10, 0x04, 0x04,
            ),
            PrinterRealtimeStatusProtocol.requestBytes(),
        )
    }

    @Test
    fun normalResponseIsReady() {
        val status = PrinterRealtimeStatusProtocol.parse(
            byteArrayOf(0x12, 0x12, 0x12, 0x12),
            checkedAt = 123L,
            elapsedMillis = 45L,
        )

        assertEquals(PrinterStatusLevel.READY, status.level)
        assertEquals("印刷可能です", status.summary)
        assertTrue(status.online)
        assertFalse(status.coverOpen)
        assertFalse(status.paperOut)
        assertFalse(status.paperNearEnd)
        assertTrue(status.protocolValid)
        assertEquals("12 12 12 12", status.rawHex)
        assertEquals(45L, status.elapsedMillis)
    }

    @Test
    fun coverPaperAndCutterErrorsAreDetected() {
        val status = PrinterRealtimeStatusProtocol.parse(
            byteArrayOf(
                0x1A,
                0x76,
                0x7E,
                0x7E,
            ),
        )

        assertEquals(PrinterStatusLevel.ERROR, status.level)
        assertTrue(status.coverOpen)
        assertTrue(status.paperFeedStopped)
        assertTrue(status.offlineErrorPresent)
        assertTrue(status.recoverableError)
        assertTrue(status.cutterError)
        assertTrue(status.unrecoverableError)
        assertTrue(status.autoRecoverableError)
        assertTrue(status.paperNearEnd)
        assertTrue(status.paperOut)
        assertFalse(status.online)
    }

    @Test
    fun nearEndOnlyIsWarning() {
        val status = PrinterRealtimeStatusProtocol.parse(
            byteArrayOf(0x12, 0x12, 0x12, 0x1E),
        )

        assertEquals(PrinterStatusLevel.WARNING, status.level)
        assertTrue(status.paperNearEnd)
        assertFalse(status.paperOut)
        assertEquals("ロール紙残量が少なくなっています", status.summary)
    }

    @Test
    fun invalidFixedBitsAreReportedWithoutDiscardingStatus() {
        val status = PrinterRealtimeStatusProtocol.parse(
            byteArrayOf(0x00, 0x00, 0x00, 0x00),
        )

        assertFalse(status.protocolValid)
        assertEquals(PrinterStatusLevel.WARNING, status.level)
        assertEquals("応答形式を確認してください", status.summary)
    }
}
