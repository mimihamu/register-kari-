package jp.co.tenposinfo.register

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

    fun encodeOrNull(grapheme: String, configuration: PrinterConfiguration): ByteArray? =
        rasterizer?.invoke(grapheme, configuration)?.let(ReceiptStampEscPosV136::encodeRaster)
}
