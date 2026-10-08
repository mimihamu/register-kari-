package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V136Scr720DocumentStampPlacementTest {
    private val start = byteArrayOf(0x1B, 0x40, 0x1B, 0x74, 0x00)
    private val fullCut = byteArrayOf(0x1D, 0x56, 0x41, 0x00)
    private val partialCut = byteArrayOf(0x1D, 0x56, 0x42, 0x00)
    private val stamp = byteArrayOf(0x55, 0x66)

    @Test
    fun formalDefaultsAreReceiptTopAndVoucherBottom() {
        assertEquals(DocumentStampPlacementV136.TOP, DocumentStampPlacementPolicyV136.defaultFor(DocumentPrintKindV136.SALE_RECEIPT))
        assertEquals(DocumentStampPlacementV136.BOTTOM, DocumentStampPlacementPolicyV136.defaultFor(DocumentPrintKindV136.RECEIPT_VOUCHER))
        assertEquals(DocumentStampPlacementV136.NONE, DocumentStampPlacementPolicyV136.defaultFor(DocumentPrintKindV136.PROVISIONAL_RECEIPT))
        assertFalse(DocumentStampPlacementPolicyV136.supports(DocumentPrintKindV136.SETTLEMENT))
    }

    @Test
    fun topPlacementPreservesExistingPerDocumentPrefixContract() {
        val payload = start + byteArrayOf(0x11) + fullCut
        assertArrayEquals(
            stamp + payload,
            DocumentStampPayloadComposerV136.apply(payload, stamp, DocumentStampPlacementV136.TOP),
        )
    }

    @Test
    fun bottomPlacementIsInsertedBeforeFullAndPartialCut() {
        val full = start + byteArrayOf(0x11, 0x0A) + fullCut
        val partial = start + byteArrayOf(0x22, 0x0A) + partialCut
        assertArrayEquals(
            start + byteArrayOf(0x11, 0x0A) + stamp + fullCut,
            DocumentStampPayloadComposerV136.apply(full, stamp, DocumentStampPlacementV136.BOTTOM),
        )
        assertArrayEquals(
            start + byteArrayOf(0x22, 0x0A) + stamp + partialCut,
            DocumentStampPayloadComposerV136.apply(partial, stamp, DocumentStampPlacementV136.BOTTOM),
        )
    }

    @Test
    fun bottomPlacementAppliesToEveryPhysicalDocumentAndNoCutFallsBackToEnd() {
        val first = start + byteArrayOf(0x01) + fullCut
        val second = start + byteArrayOf(0x02) + partialCut
        assertArrayEquals(
            start + byteArrayOf(0x01) + stamp + fullCut + start + byteArrayOf(0x02) + stamp + partialCut,
            DocumentStampPayloadComposerV136.apply(first + second, stamp, DocumentStampPlacementV136.BOTTOM),
        )
        val noCut = start + byteArrayOf(0x33)
        assertArrayEquals(
            noCut + stamp,
            DocumentStampPayloadComposerV136.apply(noCut, stamp, DocumentStampPlacementV136.BOTTOM),
        )
    }

    @Test
    fun noneOrBlankStampIsByteIdentical() {
        val payload = start + byteArrayOf(0x44) + fullCut
        assertArrayEquals(payload, DocumentStampPayloadComposerV136.apply(payload, stamp, DocumentStampPlacementV136.NONE))
        assertArrayEquals(payload, DocumentStampPayloadComposerV136.apply(payload, ByteArray(0), DocumentStampPlacementV136.BOTTOM))
    }

    @Test
    fun voucherJobFreezesSnapshotAndSenderHashesFinalStampedBytes() {
        val voucher = File("src/main/java/jp/co/tenposinfo/register/ReceiptVoucher.kt").readText()
        val advanced = File("src/main/java/jp/co/tenposinfo/register/AdvancedOperationsStore.kt").readText()
        val frozen = File("src/main/java/jp/co/tenposinfo/register/Syn003FrozenPrintPayloadV136.kt").readText()
        val settings = File("src/main/java/jp/co/tenposinfo/register/DocumentPrintSettingsV136.kt").readText()

        assertTrue(voucher.contains("DocumentStampJobSchemaV136.capture"))
        assertTrue(voucher.contains("DocumentStampJobSchemaV136.putInto(this, stampSnapshot)"))
        assertTrue(voucher.contains("suppressIssuerHeader = documentStampSnapshot.hasStamp"))
        assertTrue(advanced.contains("val stampSnapshot = DocumentStampJobSchemaV136.load(db, jobId)"))
        assertTrue(advanced.contains("payload = finalPayload"))
        assertTrue(advanced.contains("gateway.send(finalPayload)"))
        assertTrue(frozen.contains("documentPrintSetting.stampPlacement"))
        assertTrue(frozen.contains("\\\"stampPlacement\\\""))
        assertTrue(settings.contains(".stamp_placement"))
    }

    @Test
    fun issuerHeaderSuppressionOnlyAppliesWhenStampExists() {
        val plain = ReceiptVoucherDocumentData(
            issuanceId = 1L,
            saleId = 2L,
            sequenceNo = 1,
            sequenceCount = 1,
            amount = 1_000L,
            addressee = "",
            purpose = "飲食代",
            operatorName = "担当",
            issuedAt = 0L,
            issuer = InvoiceIssuerProfile(
                storeName = "つぐレジ店",
                address = "住所",
                phone = "03-0000-0000",
                registrationNumber = "T1234567890123",
            ),
        )
        val stamped = plain.copy(suppressIssuerHeader = true)
        val plainText = ReceiptVoucherRenderer.render(plain, ReceiptPaper.MM58)
        val stampedText = ReceiptVoucherRenderer.render(stamped, ReceiptPaper.MM58)
        assertTrue(plainText.contains("つぐレジ店"))
        assertTrue(plainText.contains("登録番号"))
        assertFalse(stampedText.contains("つぐレジ店"))
        assertFalse(stampedText.contains("登録番号 T1234567890123"))
        assertTrue(stampedText.contains("【領収書】"))
    }
}
