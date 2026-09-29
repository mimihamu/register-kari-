package jp.co.tenposinfo.register

import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Formal v2.5 §16.3 text safety shared by every ESC/POS document.
 *
 * Registered textual substitutions are applied first. Characters that still cannot be represented
 * by the selected printer charset fall back to □. The returned substitutions allow callers to
 * persist/audit the original text without silently losing it.
 */
data class PrinterTextSubstitutionV136(
    val original: String,
    val replacement: String,
)


data class PrinterTextAuditEntryV136(
    val original: String,
    val replacement: String,
    val charsetName: String,
)

object PrinterTextAuditV136 {
    @Volatile
    var sink: ((PrinterTextAuditEntryV136) -> Unit)? = null

    fun record(substitution: PrinterTextSubstitutionV136, charsetName: String) {
        sink?.invoke(
            PrinterTextAuditEntryV136(
                original = substitution.original,
                replacement = substitution.replacement,
                charsetName = charsetName,
            ),
        )
    }
}

data class PrinterTextSafetyResultV136(
    val text: String,
    val substitutions: List<PrinterTextSubstitutionV136>,
)

object PrinterTextSafetyV136 {
    private val replacements = mapOf(
        "〜" to "~",
        "−" to "-",
        "—" to "-",
        "―" to "-",
        "’" to "'",
        "“" to "\"",
        "”" to "\"",
    )

    fun sanitize(text: String, charsetName: String): PrinterTextSafetyResultV136 {
        val charset = Charset.forName(charsetName)
        val normalized = normalizeLineBreaksAndControls(text)
        val substitutions = mutableListOf<PrinterTextSubstitutionV136>()
        val out = StringBuilder()

        graphemeLikeUnits(normalized).forEach { unit ->
            if (unit == "\n") {
                out.append(unit)
                return@forEach
            }
            val registered = replacements[unit] ?: unit
            val printable = if (canEncode(charset, registered)) registered else "□"
            if (printable != unit) {
                substitutions += PrinterTextSubstitutionV136(unit, printable)
            }
            out.append(printable)
        }
        return PrinterTextSafetyResultV136(limitBlankLines(out.toString()), substitutions)
    }

    private fun normalizeLineBreaksAndControls(value: String): String {
        val normalized = value.replace("\r\n", "\n").replace('\r', '\n')
        val out = StringBuilder()
        var offset = 0
        while (offset < normalized.length) {
            val codePoint = normalized.codePointAt(offset)
            if (codePoint == '\n'.code || codePoint == '\t'.code || !Character.isISOControl(codePoint)) {
                out.appendCodePoint(codePoint)
            }
            offset += Character.charCount(codePoint)
        }
        return out.toString().replace('\t', ' ')
    }

    private fun limitBlankLines(value: String): String {
        val lines = value.split("\n")
        val out = mutableListOf<String>()
        var blankRun = 0
        lines.forEach { line ->
            if (line.isEmpty()) {
                blankRun += 1
                if (blankRun <= 3) out += line
            } else {
                blankRun = 0
                out += line
            }
        }
        return out.joinToString("\n")
    }

    private fun graphemeLikeUnits(value: String): List<String> {
        val units = mutableListOf<String>()
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            units += String(Character.toChars(codePoint))
            offset += Character.charCount(codePoint)
        }
        return units
    }

    private fun canEncode(charset: Charset, value: String): Boolean =
        charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .canEncode(CharBuffer.wrap(value))
}
