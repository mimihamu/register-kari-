package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V136PrinterTextSafetyTest {
    @Test
    fun unsupportedCharactersAreReplacedAndReported() {
        val result = PrinterTextSafetyV136.sanitize("商品😀〜", "Shift_JIS")
        assertEquals("商品□～", result.text)
        assertTrue(result.substitutions.any { it.original == "😀" && it.replacement == "□" })
        assertTrue(result.substitutions.any { it.original == "〜" && it.replacement == "～" })
    }

    @Test
    fun lineBreaksControlsAndBlankRunsAreNormalized() {
        val result = PrinterTextSafetyV136.sanitize("A\r\n\rB\u0000\n\n\n\n\nC", "Shift_JIS")
        assertEquals("A\n\nB\n\n\n\nC", result.text)
        assertTrue(!result.text.contains('\u0000'))
    }

    @Test
    fun auditSinkReceivesOriginalAndReplacement() {
        val captured = mutableListOf<PrinterTextAuditEntryV136>()
        PrinterTextAuditV136.sink = { captured += it }
        try {
            val configuration = PrinterConfiguration(profile = PrinterProfile.EPSON_TM_JAPAN)
            PrinterCommandEncoder.encodeText("😀", configuration, appendCut = false)
            assertTrue(captured.any { it.original == "😀" && it.replacement == "□" && it.charsetName == "Shift_JIS" })
        } finally {
            PrinterTextAuditV136.sink = null
        }
    }

}