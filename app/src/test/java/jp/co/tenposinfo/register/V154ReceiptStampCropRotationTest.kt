package jp.co.tenposinfo.register

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class V154ReceiptStampCropRotationTest {
    @Test
    fun cropRemovesEqualMarginsBeforeRasterization() {
        val pixels = IntArray(16) { it }
        val transformed = ReceiptStampTransformV136.apply(
            ArgbImageV136(width = 4, height = 4, pixels = pixels),
            ReceiptStampSettingsV136(cropPercent = 25),
        )

        assertEquals(2, transformed.width)
        assertEquals(2, transformed.height)
        assertArrayEquals(intArrayOf(5, 6, 9, 10), transformed.pixels)
    }

    @Test
    fun clockwiseRotationPreservesEverySourcePixel() {
        val source = ArgbImageV136(
            width = 3,
            height = 2,
            pixels = intArrayOf(1, 2, 3, 4, 5, 6),
        )

        val rotated90 = ReceiptStampTransformV136.apply(
            source,
            ReceiptStampSettingsV136(rotationDegrees = 90),
        )
        assertEquals(2, rotated90.width)
        assertEquals(3, rotated90.height)
        assertArrayEquals(intArrayOf(4, 1, 5, 2, 6, 3), rotated90.pixels)

        val rotated180 = ReceiptStampTransformV136.apply(
            source,
            ReceiptStampSettingsV136(rotationDegrees = 180),
        )
        assertEquals(3, rotated180.width)
        assertEquals(2, rotated180.height)
        assertArrayEquals(intArrayOf(6, 5, 4, 3, 2, 1), rotated180.pixels)

        val rotated270 = ReceiptStampTransformV136.apply(
            source,
            ReceiptStampSettingsV136(rotationDegrees = 270),
        )
        assertEquals(2, rotated270.width)
        assertEquals(3, rotated270.height)
        assertArrayEquals(intArrayOf(3, 6, 2, 5, 1, 4), rotated270.pixels)
    }

    @Test
    fun normalizationFailsSafeForUnsupportedRotationAndBoundsCrop() {
        val normalized = ReceiptStampPolicyV136.normalize(
            ReceiptStampSettingsV136(rotationDegrees = 45, cropPercent = 99),
        )
        assertEquals(0, normalized.rotationDegrees)
        assertEquals(ReceiptStampPolicyV136.MAX_CROP_PERCENT, normalized.cropPercent)
    }

    @Test
    fun paperFitUsesDimensionsAfterRotation() {
        val source = ArgbImageV136(
            width = 10,
            height = 600,
            pixels = IntArray(6_000) { 0xFFFFFFFF.toInt() },
        )
        val raster = ReceiptStampRasterizerV136.rasterize(
            source,
            ReceiptStampSettingsV136(rotationDegrees = 90),
            ReceiptPaper.MM58,
        )
        assertEquals(ReceiptStampPolicyV136.MM58_MAX_DOTS, raster.widthDots)
        assertEquals(6, raster.heightDots)
    }
}
