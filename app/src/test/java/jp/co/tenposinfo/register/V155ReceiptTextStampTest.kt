package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V155ReceiptTextStampTest {
    @Test fun resolvesFiveFormalFieldsWithPerLineSettings() {
        val settings = ReceiptTextStampSettingsV136(
            lines = ReceiptTextStampFieldV136.entries.map { field ->
                ReceiptTextStampLineSettingV136(
                    field = field,
                    enabled = true,
                    alignment = if (field == ReceiptTextStampFieldV136.ADDRESS) ReceiptTextStampAlignmentV136.LEFT else ReceiptTextStampAlignmentV136.CENTER,
                    bold = field == ReceiptTextStampFieldV136.STORE_NAME,
                    magnification = if (field == ReceiptTextStampFieldV136.STORE_NAME) 2 else 1,
                )
            },
        )
        val store = StoreBasicSettingsV135(
            storeName = "つぐレジ商店",
            branchName = "越谷店",
            postalCode = "343-0000",
            address1 = "埼玉県越谷市",
            address2 = "1-2-3",
            phone = "048-000-0000",
            registrationNumber = "T1234567890123",
        )
        val lines = ReceiptTextStampPolicyV136.resolve(settings, store)
        assertEquals(5, lines.size)
        assertEquals("つぐレジ商店", lines[0].text)
        assertEquals("越谷店", lines[1].text)
        assertEquals("343-0000 埼玉県越谷市 1-2-3", lines[2].text)
        assertEquals("TEL 048-000-0000", lines[3].text)
        assertEquals("登録番号 T1234567890123", lines[4].text)
        assertEquals(ReceiptTextStampAlignmentV136.LEFT, lines[2].alignment)
        assertTrue(lines[0].bold)
        assertEquals(2, lines[0].magnification)
    }

    @Test fun disabledOrBlankFieldsAreNotPrinted() {
        val settings = ReceiptTextStampSettingsV136(
            lines = ReceiptTextStampPolicyV136.defaults().map {
                if (it.field == ReceiptTextStampFieldV136.STORE_NAME) it.copy(enabled = false) else it
            },
        )
        val lines = ReceiptTextStampPolicyV136.resolve(
            settings,
            StoreBasicSettingsV135(storeName = "店", branchName = "", address1 = "", phone = "", registrationNumber = ""),
        )
        assertTrue(lines.isEmpty())
    }

    @Test fun magnificationIsClampedToEscPosSupportedContract() {
        val settings = ReceiptTextStampSettingsV136(
            lines = listOf(
                ReceiptTextStampLineSettingV136(
                    ReceiptTextStampFieldV136.STORE_NAME,
                    enabled = true,
                    magnification = 99,
                ),
            ),
        )
        val normalized = ReceiptTextStampPolicyV136.normalize(settings)
        assertEquals(5, normalized.lines.size)
        assertEquals(4, normalized.lines.first { it.field == ReceiptTextStampFieldV136.STORE_NAME }.magnification)
    }

    @Test fun escPosContainsAlignmentBoldMagnificationAndResets() {
        val line = ReceiptTextStampResolvedLineV136(
            field = ReceiptTextStampFieldV136.STORE_NAME,
            text = "TEST",
            alignment = ReceiptTextStampAlignmentV136.CENTER,
            bold = true,
            magnification = 2,
        )
        val bytes = ReceiptTextStampEscPosV136.encode(
            listOf(line),
            PrinterConfiguration(profile = PrinterProfile.GENERIC_ESC_POS),
        )
        fun contains(sequence: ByteArray): Boolean = bytes.indices.any { start ->
            start + sequence.size <= bytes.size && sequence.indices.all { bytes[start + it] == sequence[it] }
        }
        assertTrue(contains(byteArrayOf(0x1B, 0x61, 0x01)))
        assertTrue(contains(byteArrayOf(0x1B, 0x45, 0x01)))
        assertTrue(contains(byteArrayOf(0x1D, 0x21, 0x11)))
        assertTrue(contains("TEST".toByteArray(Charsets.US_ASCII)))
        assertTrue(contains(byteArrayOf(0x1D, 0x21, 0x00)))
        assertTrue(contains(byteArrayOf(0x1B, 0x45, 0x00)))
        assertFalse(bytes.isEmpty())
    }
}
