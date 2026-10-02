package jp.co.tenposinfo.register

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class V136PrinterGlyphFallbackTest {
    @Test
    fun unsupportedGlyphUsesOnlyAppGeneratedRasterBytes() {
        val raster = MonochromeRasterV136(
            widthDots = 8,
            heightDots = 1,
            bytesPerRow = 1,
            data = byteArrayOf(0x80.toByte()),
        )
        PrinterGlyphFallbackV136.rasterizer = { grapheme, _ ->
            if (grapheme == "😀") raster else null
        }
        try {
            val configuration = PrinterConfiguration(profile = PrinterProfile.EPSON_TM_JAPAN)
            val payload = PrinterCommandEncoder.encodeStyledText("😀", configuration, appendCut = false)
            val rasterCommand = byteArrayOf(0x1D, 0x76, 0x30, 0x00)
            assertTrue(payload.asList().windowed(rasterCommand.size).any { it == rasterCommand.asList() })
            assertFalse(payload.toString(Charsets.ISO_8859_1).contains("□"))
        } finally {
            PrinterGlyphFallbackV136.rasterizer = null
        }
    }

    @Test
    fun unsupportedGlyphStillFallsBackToSquareWhenRasterUnavailable() {
        PrinterGlyphFallbackV136.rasterizer = null
        val configuration = PrinterConfiguration(profile = PrinterProfile.EPSON_TM_JAPAN)
        val payload = PrinterCommandEncoder.encodeStyledText("😀", configuration, appendCut = false)
        assertTrue(payload.toString(Charset.forName("Shift_JIS")).contains("□"))
    }
}
