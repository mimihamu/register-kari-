package jp.co.tenposinfo.register

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Formal v2.5 §16.3 unsupported-character fallback.
 *
 * Raw ESC/POS bytes are produced only by this app-owned raster encoder. User text never becomes
 * an ESC/POS command. The platform renderer can install a rasterizer for an unsupported grapheme;
 * if it cannot render the grapheme, callers retain the final □ fallback.
 */
object PrinterGlyphFallbackV136 {
    @Volatile
    var rasterizer: ((String, PrinterConfiguration) -> MonochromeRasterV136?)? = null

    fun installAndroidRasterizer() {
        rasterizer = { grapheme, configuration -> renderAndroidGlyph(grapheme, configuration) }
    }

    fun encodeOrNull(grapheme: String, configuration: PrinterConfiguration): ByteArray? =
        rasterizer?.invoke(grapheme, configuration)?.let(ReceiptStampEscPosV136::encodeRaster)

    private fun renderAndroidGlyph(
        grapheme: String,
        configuration: PrinterConfiguration,
    ): MonochromeRasterV136? {
        if (grapheme.isBlank()) return null
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 32f
            typeface = Typeface.DEFAULT
        }
        if (!paint.hasGlyph(grapheme)) return null
        val bounds = android.graphics.Rect()
        paint.getTextBounds(grapheme, 0, grapheme.length, bounds)
        val width = (bounds.width() + 8).coerceAtLeast(16)
        val height = (bounds.height() + 8).coerceAtLeast(16)
        val maxWidth = configuration.printableDotWidth.coerceAtLeast(16)
        val bitmap = Bitmap.createBitmap(width.coerceAtMost(maxWidth), height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawText(grapheme, 4f - bounds.left, 4f - bounds.top, paint)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        bitmap.recycle()
        return ReceiptStampRasterizerV136.rasterize(
            image = ArgbImageV136(bitmapWidth(pixels, width.coerceAtMost(maxWidth), height), height, pixels),
            settings = ReceiptStampSettingsV136(threshold = 160),
            paper = PrinterPaperSettingPolicy.paper(configuration),
            printableDotWidth = maxWidth,
        )
    }

    private fun bitmapWidth(pixels: IntArray, expectedWidth: Int, height: Int): Int =
        if (height > 0 && pixels.size % height == 0) pixels.size / height else expectedWidth
}
