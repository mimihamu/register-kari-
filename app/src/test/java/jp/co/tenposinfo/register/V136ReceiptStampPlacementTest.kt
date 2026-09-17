package jp.co.tenposinfo.register

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V136ReceiptStampPlacementTest {
    private val begin = byteArrayOf(0x1B, 0x40, 0x1B, 0x74, 0x01)
    private val cut = byteArrayOf(0x1D, 0x56, 0x42, 0x00)
    private val stamp = byteArrayOf(0x55, 0x66)

    @Test fun formalDefaultsAreReceiptTopAndReceiptDocumentBottom() {
        val template = ReceiptStampTemplateV136()
        assertEquals(ReceiptStampPlacementV136.TOP, template.placement(ReceiptStampDocumentV136.SALES_RECEIPT))
        assertEquals(ReceiptStampPlacementV136.BOTTOM, template.placement(ReceiptStampDocumentV136.RECEIPT))
    }

    @Test fun topPlacementIsInsertedBeforeEveryDocument() {
        val payload = begin + byteArrayOf(0x11) + cut + begin + byteArrayOf(0x22) + cut
        val result = ReceiptStampDocumentComposerV136.compose(payload, stamp, ReceiptStampPlacementV136.TOP)
        assertArrayEquals(stamp + begin + byteArrayOf(0x11) + cut + stamp + begin + byteArrayOf(0x22) + cut, result)
    }

    @Test fun bottomPlacementIsInsertedImmediatelyBeforeEachCut() {
        val payload = begin + byteArrayOf(0x11) + cut + begin + byteArrayOf(0x22) + cut
        val result = ReceiptStampDocumentComposerV136.compose(payload, stamp, ReceiptStampPlacementV136.BOTTOM)
        assertArrayEquals(begin + byteArrayOf(0x11) + stamp + cut + begin + byteArrayOf(0x22) + stamp + cut, result)
    }

    @Test fun bottomPlacementWithoutCutAppendsAtDocumentEnd() {
        val payload = begin + byteArrayOf(0x11, 0x22)
        val result = ReceiptStampDocumentComposerV136.compose(payload, stamp, ReceiptStampPlacementV136.BOTTOM)
        assertArrayEquals(payload + stamp, result)
    }

    @Test fun bottomPlacementHandlesCutAndCutlessDocumentsIndependently() {
        val payload = begin + byteArrayOf(0x11) + begin + byteArrayOf(0x22) + cut
        val result = ReceiptStampDocumentComposerV136.compose(payload, stamp, ReceiptStampPlacementV136.BOTTOM)
        assertArrayEquals(begin + byteArrayOf(0x11) + stamp + begin + byteArrayOf(0x22) + stamp + cut, result)
        assertEquals(2, ReceiptStampDocumentComposerV136.countDocuments(payload))
    }

    @Test fun payloadWithoutDocumentMarkerStillCountsAsOneDocument() {
        assertEquals(1, ReceiptStampDocumentComposerV136.countDocuments(byteArrayOf(0x11, 0x22)))
        assertEquals(0, ReceiptStampDocumentComposerV136.countDocuments(byteArrayOf()))
    }

    @Test fun noneAndEmptyStampPreservePayload() {
        val payload = begin + byteArrayOf(0x11) + cut
        assertArrayEquals(payload, ReceiptStampDocumentComposerV136.compose(payload, stamp, ReceiptStampPlacementV136.NONE))
        assertArrayEquals(payload, ReceiptStampDocumentComposerV136.compose(payload, byteArrayOf(), ReceiptStampPlacementV136.TOP))
    }

    @Test fun composerDoesNotMutateOriginalPayload() {
        val payload = begin + byteArrayOf(0x11) + cut
        val before = payload.copyOf()
        ReceiptStampDocumentComposerV136.compose(payload, stamp, ReceiptStampPlacementV136.BOTTOM)
        assertTrue(payload.contentEquals(before))
    }
}
