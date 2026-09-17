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
 * BOTTOM handles each document independently: before that document's cut command, or at that
 * document's end when no cut exists. Source payloads are never mutated.
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
            ReceiptStampPlacementV136.BOTTOM -> insertAtEachDocumentBottom(payload, stamp)
            ReceiptStampPlacementV136.NONE -> payload.copyOf()
        }
    }

    fun countDocuments(payload: ByteArray): Int {
        if (payload.isEmpty()) return 0
        val starts = findAll(payload, documentMarker)
        return starts.size.coerceAtLeast(1)
    }

    private fun insertBeforeEachDocument(payload: ByteArray, stamp: ByteArray): ByteArray {
        val starts = findAll(payload, documentMarker)
        if (starts.isEmpty()) return stamp + payload
        return insertAt(payload, stamp, starts)
    }

    private fun insertAtEachDocumentBottom(payload: ByteArray, stamp: ByteArray): ByteArray {
        val starts = findAll(payload, documentMarker)
        if (starts.isEmpty()) {
            val cut = firstCutInRange(payload, 0, payload.size)
            return insertAt(payload, stamp, listOf(cut ?: payload.size))
        }

        val offsets = ArrayList<Int>(starts.size)
        starts.forEachIndexed { index, start ->
            val end = starts.getOrElse(index + 1) { payload.size }
            offsets += firstCutInRange(payload, start, end) ?: end
        }
        return insertAt(payload, stamp, offsets)
    }

    private fun firstCutInRange(payload: ByteArray, start: Int, end: Int): Int? {
        val partial = findFirst(payload, partialCut, start, end)
        val full = findFirst(payload, fullCut, start, end)
        return listOfNotNull(partial, full).minOrNull()
    }

    private fun findFirst(payload: ByteArray, marker: ByteArray, start: Int, end: Int): Int? {
        var index = start.coerceAtLeast(0)
        val last = (end - marker.size).coerceAtMost(payload.size - marker.size)
        while (index <= last) {
            var matches = true
            for (i in marker.indices) {
                if (payload[index + i] != marker[i]) {
                    matches = false
                    break
                }
            }
            if (matches) return index
            index++
        }
        return null
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
