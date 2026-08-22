package jp.co.tenposinfo.register

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class V136ReceiptStampMultiCopyTest {
    private val documentStart = byteArrayOf(0x1B, 0x40, 0x1B, 0x74)
    private val stamp = byteArrayOf(0x55, 0x66, 0x77)

    @Test
    fun oneDocument_receivesOneStampPrefix() {
        val payload = documentStart + byteArrayOf(0x00, 0x10, 0x11)

        val composed = ReceiptStampPayloadComposerV136.prependToEachDocument(payload, stamp)

        assertEquals(1, ReceiptStampPayloadComposerV136.countDocuments(payload))
        assertArrayEquals(stamp + payload, composed)
    }

    @Test
    fun multipleDocuments_receiveStampBeforeEveryDocumentStart() {
        val first = documentStart + byteArrayOf(0x00, 0x01, 0x02)
        val second = documentStart + byteArrayOf(0x00, 0x03)
        val third = documentStart + byteArrayOf(0x00, 0x04, 0x05)
        val payload = first + second + third

        val composed = ReceiptStampPayloadComposerV136.prependToEachDocument(payload, stamp)

        assertEquals(3, ReceiptStampPayloadComposerV136.countDocuments(payload))
        assertArrayEquals(stamp + first + stamp + second + stamp + third, composed)
    }

    @Test
    fun blankStamp_isByteIdentical() {
        val payload = documentStart + byteArrayOf(0x00, 0x21)

        assertArrayEquals(
            payload,
            ReceiptStampPayloadComposerV136.prependToEachDocument(payload, ByteArray(0)),
        )
    }

    @Test
    fun unknownPayload_fallsBackToSinglePrefix() {
        val payload = byteArrayOf(0x01, 0x02, 0x03)

        assertEquals(0, ReceiptStampPayloadComposerV136.countDocuments(payload))
        assertArrayEquals(stamp + payload, ReceiptStampPayloadComposerV136.prependToEachDocument(payload, stamp))
    }
}
