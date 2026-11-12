package jp.co.tenposinfo.register

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun formalMultiCopyReceiptHasPerCopyOrdinal() {
        val data = ReceiptData(
            storeName = "つぐレジ店",
            registrationNumber = "",
            saleId = 136L,
            createdAt = 0L,
            operatorName = "担当者",
            items = emptyList(),
            taxSummary = TaxEngine.calculate(emptyList()),
            payments = emptyList(),
            changeAmount = 0L,
            documentCopies = 3,
        )
        val first = ReceiptRenderer.render(data, ReceiptPaper.MM58, copyOrdinal = 1, copyTotal = 3)
        val second = ReceiptRenderer.render(data, ReceiptPaper.MM58, copyOrdinal = 2, copyTotal = 3)
        val third = ReceiptRenderer.render(data, ReceiptPaper.MM58, copyOrdinal = 3, copyTotal = 3)

        assertTrue(first.contains("部数 1/3"))
        assertTrue(second.contains("部数 2/3"))
        assertTrue(third.contains("部数 3/3"))
    }

    @Test
    fun singleCopyDoesNotAddOrdinalNoise() {
        val data = ReceiptData(
            storeName = "つぐレジ店",
            registrationNumber = "",
            saleId = 137L,
            createdAt = 0L,
            operatorName = "担当者",
            items = emptyList(),
            taxSummary = TaxEngine.calculate(emptyList()),
            payments = emptyList(),
            changeAmount = 0L,
            documentCopies = 1,
        )
        assertTrue(!ReceiptRenderer.render(data, ReceiptPaper.MM58).contains("部数 1/1"))
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
