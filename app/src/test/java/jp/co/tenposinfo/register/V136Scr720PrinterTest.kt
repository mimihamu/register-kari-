package jp.co.tenposinfo.register

import java.nio.charset.Charset
import org.junit.Assert.assertTrue
import org.junit.Test

class V136Scr720PrinterTest {
    @Test
    fun testDocumentContainsTextRegistrationAndImageRaster() {
        val document = PrinterPaperWidthTestV136.buildAll(
            ReceiptPaper.MM80,
            "2026-09-15T00:00:00Z",
        )

        assertTrue(document.contains("[SCR-720 テスト印刷]"))
        assertTrue(document.contains("文字: つぐレジ SCR-720 印字確認"))
        assertTrue(document.contains("登録番号: ${PrinterPaperWidthTestV136.SCR720_SAMPLE_REGISTRATION_NUMBER}"))
        assertTrue(document.contains("画像: 8x8 チェッカーパターン"))

        val controlText = PrinterPaperWidthTestV136.scr720DiagnosticControlText()
        PrinterProfile.entries.forEach { profile ->
            val encoded = controlText.toByteArray(Charset.forName(profile.charsetName))
            assertTrue(encoded.containsSequence(byteArrayOf(0x1D, 0x76, 0x30, 0x00)))
            assertTrue(encoded.containsSequence(byteArrayOf(0x55, 0x2A, 0x55, 0x2A)))
        }
    }

    @Test
    fun existingPrinterTestCompletesQrRasterAndCutInOnePayload() {
        val configuration = PrinterConfiguration(
            host = "192.0.2.10",
            paperWidthMm = 80,
            printableDotWidth = 576,
            feedLines = 5,
            cutMode = PrinterCutMode.PARTIAL,
        )
        val text = buildString {
            append(PrinterPaperWidthTestV136.buildAll(ReceiptPaper.MM80, "2026-09-15T00:00:00Z"))
            append('\n')
            append(PrinterProfilePrintabilityV136.diagnosticControlText(configuration.profile))
        }
        val payload = PrinterCommandEncoder.encodeText(text, configuration, appendCut = true)

        val raster = byteArrayOf(0x1D, 0x76, 0x30, 0x00)
        val qr = byteArrayOf(0x1D, 0x28, 0x6B)
        val partialCut = byteArrayOf(0x1D, 0x56, 0x42, 0x00)

        assertTrue(payload.containsSequence(raster))
        assertTrue(payload.containsSequence(qr))
        assertTrue(payload.containsSequence(PrinterPaperWidthTestV136.SCR720_SAMPLE_REGISTRATION_NUMBER.toByteArray(Charsets.US_ASCII)))
        assertTrue(payload.takeLast(partialCut.size).toByteArray().contentEquals(partialCut))
    }

    private fun ByteArray.containsSequence(sequence: ByteArray): Boolean {
        if (sequence.isEmpty() || sequence.size > size) return false
        for (start in 0..size - sequence.size) {
            var matches = true
            for (offset in sequence.indices) {
                if (this[start + offset] != sequence[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }
}