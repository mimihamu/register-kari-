package jp.co.tenposinfo.register

/**
 * v1.36 / Issue #137 #22: receipt text must wrap without dropping characters.
 * ASCII/half-width characters consume one logical column; other characters consume two,
 * matching the existing ReceiptRenderer width model.
 */
object ReceiptLineWrapV136 {
    fun displayWidth(value: String): Int = value.sumOf(::charWidth)

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

        value.forEach { char ->
            val charWidth = charWidth(char)
            if (current.isNotEmpty() && used + charWidth > width) {
                result += current.toString()
                current.setLength(0)
                used = 0
            }
            // A single character can only exceed width when an invalid sub-2-column width is supplied.
            // Keep the character rather than silently dropping it.
            current.append(char)
            used += charWidth
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }

    private fun charWidth(char: Char): Int = if (char.code <= 0xFF) 1 else 2
}
