package jp.co.tenposinfo.register

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V136ReceiptStampMultiCopyTest {
    @Test
    fun stampIsInsertedBeforeEveryEncodedReceiptDocument() {
        listOf(1, 2, 3).forEach { copies ->
            val configuration = PrinterConfiguration(cutMode = PrinterCutMode.PARTIAL)
            val payload = buildPayload(copies, configuration)
            val stamp = byteArrayOf(0x55, 0x66, 0x77)

            val composed = ReceiptStampPayloadComposerV136.prependToEachDocument(payload, stamp)

            assertEquals(copies, ReceiptStampPayloadComposerV136.countDocuments(payload))
            assertEquals(copies, countSequence(composed, stamp))
            assertEquals(copies, countStampedDocuments(composed, stamp))
        }
    }

    @Test
    fun copyDetectionDoesNotDependOnCutCommand() {
        val configuration = PrinterConfiguration(cutMode = PrinterCutMode.NONE)
        val payload = buildPayload(3, configuration)
        val stamp = byteArrayOf(0x21, 0x22)

        val composed = ReceiptStampPayloadComposerV136.prependToEachDocument(payload, stamp)

        assertEquals(3, ReceiptStampPayloadComposerV136.countDocuments(payload))
        assertEquals(3, countStampedDocuments(composed, stamp))
    }

    @Test
    fun disabledStampLeavesPayloadByteIdentical() {
        val payload = buildPayload(2, PrinterConfiguration())
        assertArrayEquals(
            payload,
            ReceiptStampPayloadComposerV136.prependToEachDocument(payload, ByteArray(0)),
        )
    }

    @Test
    fun defensiveFallbackStillPrefixesUnknownPayloadOnce() {
        val payload = byteArrayOf(0x01, 0x02, 0x03)
        val stamp = byteArrayOf(0x11, 0x12)
        val composed = ReceiptStampPayloadComposerV136.prependToEachDocument(payload, stamp)
        assertArrayEquals(byteArrayOf(0x11, 0x12, 0x01, 0x02, 0x03), composed)
    }

    private fun buildPayload(copies: Int, configuration: PrinterConfiguration): ByteArray {
        var payload = ByteArray(0)
        repeat(copies) { copyIndex ->
            payload += PrinterCommandEncoder.encodeText(
                text = "COPY-${copyIndex + 1}",
                configuration = configuration,
                appendCut = true,
            )
        }
        return payload
    }

    private fun countStampedDocuments(payload: ByteArray, stamp: ByteArray): Int {
        val marker = byteArrayOf(0x1B, 0x40, 0x1B, 0x74)
        var count = 0
        var index = 0
        while (index <= payload.size - marker.size) {
            if (matches(payload, index, marker)) {
                val stampStart = index - stamp.size
                if (stampStart >= 0 && matches(payload, stampStart, stamp)) count++
                index += marker.size
            } else {
                index++
            }
        }
        return count
    }

    private fun countSequence(payload: ByteArray, target: ByteArray): Int {
        if (target.isEmpty()) return 0
        var count = 0
        var index = 0
        while (index <= payload.size - target.size) {
            if (matches(payload, index, target)) {
                count++
                index += target.size
            } else {
                index++
            }
        }
        return count
    }

    private fun matches(payload: ByteArray, offset: Int, target: ByteArray): Boolean {
        if (offset < 0 || offset + target.size > payload.size) return false
        return target.indices.all { payload[offset + it] == target[it] }
    }
}