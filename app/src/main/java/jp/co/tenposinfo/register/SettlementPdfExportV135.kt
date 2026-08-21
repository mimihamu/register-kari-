package jp.co.tenposinfo.register

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import java.io.IOException

internal object SettlementPdfExportPolicyV135 {
    const val MIME_TYPE = "application/pdf"
    const val PAGE_WIDTH = 595
    const val PAGE_HEIGHT = 842
    const val MARGIN = 36f
    const val TEXT_SIZE = 10f
    const val LINE_HEIGHT = 14f

    fun fileName(record: SettlementRecord): String {
        val type = if (record.type == SettlementReportType.Z_SETTLEMENT) "Z" else "X"
        val date = record.businessDate.replace(Regex("[^0-9]"), "")
        return "TSUGUREGI_${type}_${date}_No${record.id}.pdf"
    }

    fun linesPerPage(): Int =
        ((PAGE_HEIGHT - MARGIN * 2) / LINE_HEIGHT).toInt().coerceAtLeast(1)
}

/**
 * REP-001 PDF保存。
 * 保存済みX/Zスナップショットを OperationsStore.previewSettlement() で再構成し、
 * 印刷と同じ論理帳票内容を Storage Access Framework の出力先へPDF化する。
 */
internal object SettlementPdfExportV135 {
    fun write(context: Context, reportId: Long, destination: Uri) {
        val payload = OperationsStore(context.applicationContext).useForPdfV135 { store ->
            store.previewSettlement(reportId)
        }
        writePayload(context, payload, destination)
    }

    private fun writePayload(context: Context, payload: String, destination: Uri) {
        val lines = payload.lines()
        val perPage = SettlementPdfExportPolicyV135.linesPerPage()
        val document = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = SettlementPdfExportPolicyV135.TEXT_SIZE
            typeface = android.graphics.Typeface.MONOSPACE
        }
        try {
            lines.chunked(perPage).forEachIndexed { pageIndex, pageLines ->
                val pageInfo = PdfDocument.PageInfo.Builder(
                    SettlementPdfExportPolicyV135.PAGE_WIDTH,
                    SettlementPdfExportPolicyV135.PAGE_HEIGHT,
                    pageIndex + 1,
                ).create()
                val page = document.startPage(pageInfo)
                var y = SettlementPdfExportPolicyV135.MARGIN + SettlementPdfExportPolicyV135.TEXT_SIZE
                pageLines.forEach { line ->
                    page.canvas.drawText(line, SettlementPdfExportPolicyV135.MARGIN, y, paint)
                    y += SettlementPdfExportPolicyV135.LINE_HEIGHT
                }
                document.finishPage(page)
            }
            if (lines.isEmpty()) {
                val pageInfo = PdfDocument.PageInfo.Builder(
                    SettlementPdfExportPolicyV135.PAGE_WIDTH,
                    SettlementPdfExportPolicyV135.PAGE_HEIGHT,
                    1,
                ).create()
                val page = document.startPage(pageInfo)
                document.finishPage(page)
            }
            val resolver = context.contentResolver
            resolver.openOutputStream(destination, "w")?.use { output ->
                document.writeTo(output)
            } ?: throw IOException("PDF保存先を開けませんでした")
        } finally {
            document.close()
        }
    }
}

private inline fun <T> OperationsStore.useForPdfV135(block: (OperationsStore) -> T): T =
    try {
        block(this)
    } finally {
        close()
    }
