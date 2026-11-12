package jp.co.tenposinfo.register

import java.text.BreakIterator
import java.util.Locale

/**
 * v1.36 / Issue #137 #22 / formal v2.5 §16.3:
 * receipt text wraps without dropping characters and never splits a Unicode grapheme.
 * Width is measured per grapheme/code point rather than Kotlin String.length/Char count.
 */
object ReceiptLineWrapV136 {
    fun displayWidth(value: String): Int = graphemes(value).sumOf(::graphemeWidth)

    fun wrap(value: String, width: Int): List<String> {
        require(width > 0) { "width must be positive" }
        val normalized = value.replace("\r\n", "\n").replace('\r', '\n')
        return normalized.split('\n').flatMap { logicalLine ->
            wrapSingleLine(logicalLine, width)
        }
    }

    private fun wrapSingleLine(value: String, width: Int): List<String> {
        if (value.isEmpty()) return listOf("")
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var used = 0

        graphemes(value).forEach { grapheme ->
            val graphemeWidth = graphemeWidth(grapheme)
            if (current.isNotEmpty() && used + graphemeWidth > width) {
                result += current.toString()
                current.setLength(0)
                used = 0
            }
            current.append(grapheme)
            used += graphemeWidth
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }

    private fun graphemes(value: String): List<String> {
        if (value.isEmpty()) return emptyList()
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(value)
        val result = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            result += value.substring(start, end)
            start = end
            end = iterator.next()
        }
        return result
    }

    private fun graphemeWidth(grapheme: String): Int {
        var width = 0
        var offset = 0
        while (offset < grapheme.length) {
            val codePoint = grapheme.codePointAt(offset)
            val type = Character.getType(codePoint)
            if (
                type != Character.NON_SPACING_MARK.toInt() &&
                type != Character.COMBINING_SPACING_MARK.toInt() &&
                type != Character.ENCLOSING_MARK.toInt()
            ) {
                width = maxOf(width, codePointWidth(codePoint))
            }
            offset += Character.charCount(codePoint)
        }
        return width.coerceAtLeast(1)
    }

    private fun codePointWidth(codePoint: Int): Int =
        if (codePoint <= 0xFF) 1 else 2
}
