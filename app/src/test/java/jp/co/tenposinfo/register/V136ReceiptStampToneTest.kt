package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V136ReceiptStampToneTest {
    @Test
    fun formalPaperWidthsAre384And576DotsAndAspectRatioIsPreserved() {
        assertEquals(384, ReceiptStampPolicyV136.maxWidthDots(ReceiptPaper.MM58))
        assertEquals(576, ReceiptStampPolicyV136.maxWidthDots(ReceiptPaper.MM80))
        assertEquals(384 to 48, ReceiptStampPolicyV136.fitDimensions(800, 100, ReceiptPaper.MM58))
        assertEquals(576 to 72, ReceiptStampPolicyV136.fitDimensions(800, 100, ReceiptPaper.MM80))
        assertEquals(200 to 50, ReceiptStampPolicyV136.fitDimensions(200, 50, ReceiptPaper.MM58))
    }

    @Test
    fun transparentPixelsBecomeWhiteBeforeMonochromeConversion() {
        val image = ArgbImageV136(
            width = 2,
            height = 1,
            pixels = intArrayOf(
                0x00000000,
                0xFF000000.toInt(),
            ),
        )
        val raster = ReceiptStampRasterizerV136.rasterize(
            image,
            ReceiptStampSettingsV136(enabled = true, threshold = 128),
            ReceiptPaper.MM58,
        )
        assertFalse(raster.isBlack(0, 0))
        assertTrue(raster.isBlack(1, 0))
        assertEquals(255.0, ReceiptStampRasterizerV136.adjustedLuminance(0x00000000, 0), 0.001)
    }

    @Test
    fun brightnessAndThresholdActuallyChangeOutputBits() {
        val image = ArgbImageV136(
            width = 8,
            height = 1,
            pixels = IntArray(8) { 0xFF646464.toInt() },
        )
        val dark = ReceiptStampRasterizerV136.rasterize(
            image,
            ReceiptStampSettingsV136(enabled = true, brightness = 0, threshold = 128),
            ReceiptPaper.MM58,
        )
        val light = ReceiptStampRasterizerV136.rasterize(
            image,
            ReceiptStampSettingsV136(enabled = true, brightness = 50, threshold = 128),
            ReceiptPaper.MM58,
        )
        assertNotEquals(dark.data.toList(), light.data.toList())
        assertTrue((0 until 8).all { dark.isBlack(it, 0) })
        assertTrue((0 until 8).none { light.isBlack(it, 0) })

        val gray130 = ArgbImageV136(1, 1, intArrayOf(0xFF828282.toInt()))
        val threshold120 = ReceiptStampRasterizerV136.rasterize(
            gray130,
            ReceiptStampSettingsV136(enabled = true, threshold = 120),
            ReceiptPaper.MM58,
        )
        val threshold140 = ReceiptStampRasterizerV136.rasterize(
            gray130,
            ReceiptStampSettingsV136(enabled = true, threshold = 140),
            ReceiptPaper.MM58,
        )
        assertFalse(threshold120.isBlack(0, 0))
        assertTrue(threshold140.isBlack(0, 0))
    }

    @Test
    fun bayerAndFloydSteinbergAreDeterministicMonochromeOptions() {
        val pixels = IntArray(16 * 4) { index ->
            val gray = 80 + (index % 16) * 7
            (0xFF000000.toInt() or (gray shl 16) or (gray shl 8) or gray)
        }
        val image = ArgbImageV136(16, 4, pixels)
        val bayerA = ReceiptStampRasterizerV136.rasterize(
            image,
            ReceiptStampSettingsV136(enabled = true, dither = ReceiptStampDitherV136.BAYER_4X4),
            ReceiptPaper.MM58,
        )
        val bayerB = ReceiptStampRasterizerV136.rasterize(
            image,
            ReceiptStampSettingsV136(enabled = true, dither = ReceiptStampDitherV136.BAYER_4X4),
            ReceiptPaper.MM58,
        )
        val floyd = ReceiptStampRasterizerV136.rasterize(
            image,
            ReceiptStampSettingsV136(enabled = true, dither = ReceiptStampDitherV136.FLOYD_STEINBERG),
            ReceiptPaper.MM58,
        )
        assertEquals(bayerA.data.toList(), bayerB.data.toList())
        assertNotEquals(bayerA.data.toList(), floyd.data.toList())
    }

    @Test
    fun escPosRasterCommandCarriesPackedWidthAndHeightWithoutCutCommand() {
        val raster = MonochromeRasterV136(
            widthDots = 16,
            heightDots = 2,
            bytesPerRow = 2,
            data = byteArrayOf(0x80.toByte(), 0x01, 0xFF.toByte(), 0x00),
        )
        val encoded = ReceiptStampEscPosV136.encodeRaster(raster)
        assertEquals(0x1B, encoded[0].toInt() and 0xFF)
        assertEquals(0x40, encoded[1].toInt() and 0xFF)
        assertEquals(0x1D, encoded[5].toInt() and 0xFF)
        assertEquals(0x76, encoded[6].toInt() and 0xFF)
        assertEquals(0x30, encoded[7].toInt() and 0xFF)
        assertEquals(2, encoded[9].toInt() and 0xFF)
        assertEquals(2, encoded[11].toInt() and 0xFF)
        assertFalse(encoded.toList().windowed(2).any { (a, b) ->
            (a.toInt() and 0xFF) == 0x1D && (b.toInt() and 0xFF) == 0x56
        })
    }

    @Test
    fun settingsUiAndBothAutomaticAndManualSalePathsUseStampGateway() {
        val settingsUi = File("src/main/java/jp/co/tenposinfo/register/DocumentPrintSettingsV136.kt").readText()
        val automatic = File("src/main/java/jp/co/tenposinfo/register/AutomaticPrintWorker.kt").readText()
        val manual = File("src/main/java/jp/co/tenposinfo/register/UnifiedPrintQueue.kt").readText()
        val stampSource = File("src/main/java/jp/co/tenposinfo/register/ReceiptStampV136.kt").readText()

        assertTrue(settingsUi.contains("ReceiptStampSettingsPanelV136()"))
        assertTrue(automatic.contains("ReceiptStampGatewayV136("))
        assertTrue(manual.contains("ReceiptStampGatewayV136("))
        assertTrue(stampSource.contains("PNGまたはJPEG"))
        assertTrue(stampSource.contains("透過背景は白"))
        assertTrue(stampSource.contains("MM58_MAX_DOTS = 384"))
        assertTrue(stampSource.contains("MM80_MAX_DOTS = 576"))
        assertTrue(stampSource.contains("BAYER_4X4"))
        assertTrue(stampSource.contains("FLOYD_STEINBERG"))
    }
}
