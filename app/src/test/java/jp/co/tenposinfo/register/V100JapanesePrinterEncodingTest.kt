package jp.co.tenposinfo.register

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V100JapanesePrinterEncodingTest {
    private val epson = PrinterConfiguration(
        profile = PrinterProfile.EPSON_TM_JAPAN,
        cutMode = PrinterCutMode.NONE,
    )

    @Test
    fun epsonJapanProfileSelectsShiftJisKanjiSystemAfterInitialize() {
        assertEquals("Shift_JIS", PrinterProfile.EPSON_TM_JAPAN.charsetName)
        assertEquals(1, PrinterProfile.EPSON_TM_JAPAN.kanjiCodeSystem!!)
        assertArrayEquals(
            byteArrayOf(
                0x1B, 0x40,
                0x1B, 0x74, 0x01,
                0x1C, 0x43, 0x01,
                0x1B, 0x61, 0x00,
            ),
            PrinterCommandEncoder.beginDocument(epson),
        )
    }

    @Test
    fun genericAndStarProfilesDoNotReceiveEpsonKanjiSystemCommand() {
        assertNull(PrinterProfile.GENERIC_ESC_POS.kanjiCodeSystem)
        assertNull(PrinterProfile.STAR_ESC_POS.kanjiCodeSystem)
        val fsCShiftJis = byteArrayOf(0x1C, 0x43, 0x01)
        assertFalse(
            PrinterCommandEncoder.beginDocument(
                PrinterConfiguration(profile = PrinterProfile.GENERIC_ESC_POS),
            ).containsBytes(fsCShiftJis),
        )
        assertFalse(
            PrinterCommandEncoder.beginDocument(
                PrinterConfiguration(profile = PrinterProfile.STAR_ESC_POS),
            ).containsBytes(fsCShiftJis),
        )
    }

    @Test
    fun tsuguregiJapaneseNameUsesExpectedShiftJisBytes() {
        val payload = PrinterCommandEncoder.encodeText(
            text = "つぐレジ",
            configuration = epson,
            appendCut = false,
        )
        val expectedShiftJis = byteArrayOf(
            0x82.toByte(), 0xC2.toByte(),
            0x82.toByte(), 0xAE.toByte(),
            0x83.toByte(), 0x8C.toByte(),
            0x83.toByte(), 0x57,
        )
        val utf8 = byteArrayOf(
            0xE3.toByte(), 0x81.toByte(), 0xA4.toByte(),
            0xE3.toByte(), 0x81.toByte(), 0x90.toByte(),
        )
        assertTrue(payload.containsBytes(expectedShiftJis))
        assertFalse(payload.containsBytes(utf8))
    }

    @Test
    fun receiptKanjiAndTaxSymbolsUseExpectedShiftJisBytes() {
        val payload = PrinterCommandEncoder.encodeText(
            text = "領収書 内 外 内※ 外※ 円 ￥",
            configuration = epson,
            appendCut = false,
        )
        assertTrue(
            payload.containsBytes(
                byteArrayOf(
                    0x97.toByte(), 0xCC.toByte(),
                    0x8E.toByte(), 0xFB.toByte(),
                    0x8F.toByte(), 0x91.toByte(),
                ),
            ),
        )
        assertTrue(payload.containsBytes(byteArrayOf(0x93.toByte(), 0xE0.toByte()))) // 内
        assertTrue(payload.containsBytes(byteArrayOf(0x8A.toByte(), 0x4F))) // 外
        assertTrue(payload.containsBytes(byteArrayOf(0x81.toByte(), 0xA6.toByte()))) // ※
        assertTrue(payload.containsBytes(byteArrayOf(0x89.toByte(), 0x7E))) // 円
        assertTrue(payload.containsBytes(byteArrayOf(0x81.toByte(), 0x8F.toByte()))) // ￥
    }

    @Test
    fun shiftJisSelectionComesAfterEscInitializeAndBeforeJapaneseText() {
        val payload = PrinterCommandEncoder.encodeText(
            text = "日本語",
            configuration = epson,
            appendCut = false,
        )
        val initializeIndex = payload.indexOfBytes(byteArrayOf(0x1B, 0x40))
        val kanjiSystemIndex = payload.indexOfBytes(byteArrayOf(0x1C, 0x43, 0x01))
        val japaneseIndex = payload.indexOfBytes(
            byteArrayOf(0x93.toByte(), 0xFA.toByte(), 0x96.toByte(), 0x7B, 0x8C.toByte(), 0xEA.toByte()),
        )
        assertEquals(0, initializeIndex)
        assertTrue(kanjiSystemIndex > initializeIndex)
        assertTrue(japaneseIndex > kanjiSystemIndex)
    }

    @Test
    fun existingSoakTestProvidesOneSheetJapanesePhysicalRetestPath() {
        assertEquals(1, PrinterSoakTestPolicy.MIN_PRINTS)
        val text = PrinterSoakTestPolicy.pageText(
            sequence = 1,
            total = 1,
            configuration = epson,
            startedAt = 0L,
        )
        assertTrue(text.contains("つぐレジ 連続印刷試験"))
        assertTrue(text.contains("あいうえお アイウエオ"))
        assertTrue(text.contains("日本語印字・通信・連続動作確認"))
        val payload = PrinterCommandEncoder.encodeText(text, epson, appendCut = false)
        assertTrue(payload.containsBytes(byteArrayOf(0x1C, 0x43, 0x01)))
        assertTrue(payload.containsBytes(byteArrayOf(0x93.toByte(), 0xFA.toByte()))) // 日
    }
}

private fun ByteArray.containsBytes(sequence: ByteArray): Boolean = indexOfBytes(sequence) >= 0

private fun ByteArray.indexOfBytes(sequence: ByteArray): Int {
    if (sequence.isEmpty()) return 0
    if (sequence.size > size) return -1
    for (start in 0..size - sequence.size) {
        if (sequence.indices.all { offset -> this[start + offset] == sequence[offset] }) return start
    }
    return -1
}
