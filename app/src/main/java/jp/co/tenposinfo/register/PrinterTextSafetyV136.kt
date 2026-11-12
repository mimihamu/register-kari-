package jp.co.tenposinfo.register

import android.content.ContentValues
import android.content.Context
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.BreakIterator
import java.util.Locale

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

    fun installPersistentSink(context: Context) {
        val appContext = context.applicationContext
        sink = { entry ->
            runCatching {
                RegisterDatabase(appContext).use { database ->
                    val db = database.writableDatabase
                    OperationAuditSchemaV136.ensure(db)
                    db.insertOrThrow(
                        "operation_audit",
                        null,
                        ContentValues().apply {
                            put("event_type", "PRINTER_TEXT_SUBSTITUTION")
                            put("reference_id", 0L)
                            put(
                                "detail",
                                "original=${entry.original}; replacement=${entry.replacement}; charset=${entry.charsetName}",
                            )
                            put("operator_name", OperatorSessionRegistry.lastKnownName().orEmpty().ifBlank { "SYSTEM" })
                            put("created_at", System.currentTimeMillis())
                        },
                    )
                }
            }
        }
    }

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

data class PrinterTextUnitV136(
    val original: String,
    val printableText: String?,
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

    fun units(text: String, charsetName: String): List<PrinterTextUnitV136> {
        val charset = Charset.forName(charsetName)
        val normalized = normalizeLineBreaksAndControls(text)
        return graphemeLikeUnits(normalized).map { unit ->
            if (unit == "\n") {
                PrinterTextUnitV136(unit, unit)
            } else {
                val registered = replacements[unit] ?: unit
                PrinterTextUnitV136(unit, registered.takeIf { canEncode(charset, it) })
            }
        }
    }

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
        if (value.isEmpty()) return emptyList()
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(value)
        val units = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            units += value.substring(start, end)
            start = end
            end = iterator.next()
        }
        return units
    }

    private fun canEncode(charset: Charset, value: String): Boolean =
        charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .canEncode(CharBuffer.wrap(value))
}
