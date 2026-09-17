package jp.co.tenposinfo.register

import java.io.ByteArrayOutputStream

/** Formal v2.5 §8.14 SCR-720: document-specific stamp placement. */
enum class ReceiptStampDocumentV136(val displayName: String) {
    SALES_RECEIPT("レシート"),
    RECEIPT("領収書"),
}

enum class ReceiptStampPlacementV136(val displayName: String) {
    TOP("上端"),
    BOTTOM("下端"),
    NONE("印字しない"),
}

data class ReceiptStampTemplateV136(
    val salesReceipt: ReceiptStampPlacementV136 = ReceiptStampPlacementV136.TOP,
    val receipt: ReceiptStampPlacementV136 = ReceiptStampPlacementV136.BOTTOM,
) {
    fun placement(document: ReceiptStampDocumentV136): ReceiptStampPlacementV136 = when (document) {
        ReceiptStampDocumentV136.SALES_RECEIPT -> salesReceipt
        ReceiptStampDocumentV136.RECEIPT -> receipt
    }
}

/**
 * SCR-720 placement composer.
 * TOP inserts immediately before PrinterCommandEncoder.beginDocument().
 * BOTTOM inserts immediately before the ESC/POS cut command when present, otherwise at payload end.
 * This keeps feed/cut semantics intact and never mutates the source payload.
 */
object ReceiptStampDocumentComposerV136 {
    private val documentMarker = byteArrayOf(0x1B, 0x40, 0x1B, 0x74)
    private val partialCut = byteArrayOf(0x1D, 0x56, 0x42, 0x00)
    private val fullCut = byteArrayOf(0x1D, 0x56, 0x41, 0x00)

    fun compose(
        payload: ByteArray,
        stamp: ByteArray,
        placement: ReceiptStampPlacementV136,
    ): ByteArray {
        if (payload.isEmpty() || stamp.isEmpty() || placement == ReceiptStampPlacementV136.NONE) {
            return payload.copyOf()
        }
        return when (placement) {
            ReceiptStampPlacementV136.TOP -> insertBeforeEachDocument(payload, stamp)
            ReceiptStampPlacementV136.BOTTOM -> insertBeforeEachCutOrEnd(payload, stamp)
            ReceiptStampPlacementV136.NONE -> payload.copyOf()
        }
    }

    private fun insertBeforeEachDocument(payload: ByteArray, stamp: ByteArray): ByteArray {
        val starts = findAll(payload, documentMarker)
        if (starts.isEmpty()) return stamp + payload
        return insertAt(payload, stamp, starts)
    }

    private fun insertBeforeEachCutOrEnd(payload: ByteArray, stamp: ByteArray): ByteArray {
        val cuts = (findAll(payload, partialCut) + findAll(payload, fullCut)).sorted()
        if (cuts.isEmpty()) return payload + stamp
        return insertAt(payload, stamp, cuts)
    }

    private fun insertAt(payload: ByteArray, bytes: ByteArray, offsets: List<Int>): ByteArray {
        val output = ByteArrayOutputStream(payload.size + bytes.size * offsets.size)
        var cursor = 0
        offsets.forEach { offset ->
            require(offset >= cursor && offset <= payload.size)
            output.write(payload, cursor, offset - cursor)
            output.write(bytes)
            cursor = offset
        }
        output.write(payload, cursor, payload.size - cursor)
        return output.toByteArray()
    }

    private fun findAll(payload: ByteArray, marker: ByteArray): List<Int> {
        val result = mutableListOf<Int>()
        var index = 0
        while (index <= payload.size - marker.size) {
            var matches = true
            for (i in marker.indices) {
                if (payload[index + i] != marker[i]) {
                    matches = false
                    break
                }
            }
            if (matches) {
                result += index
                index += marker.size
            } else {
                index++
            }
        }
        return result
    }
}
