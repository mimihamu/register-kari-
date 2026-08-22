package jp.co.tenposinfo.register

/**
 * v1.36 / RCP-001: 58mm / 80mm の選択結果を実印字前に目視確認するための
 * 文書レイアウトテストデータ。
 *
 * 紙幅の唯一の変換元は [ReceiptPaper] とし、58mm=32桁 / 80mm=48桁を
 * 接続テスト側でもレシート描画側と同じ値で使用する。
 */
enum class PrinterPaperTestDocumentV136(val displayName: String) {
    SALE_RECEIPT("販売レシート"),
    RECEIPT_VOUCHER("領収書"),
    PROVISIONAL_RECEIPT("仮締め票"),
    SETTLEMENT("精算票"),
}

object PrinterPaperWidthTestV136 {
    fun buildAll(paper: ReceiptPaper, generatedAt: String): String = buildString {
        append("58/80mm 文書レイアウトテスト\n")
        append("選択用紙 ${paper.widthMm}mm / 論理幅 ${paper.charsPerLine}桁\n")
        append("生成 $generatedAt\n")
        PrinterPaperTestDocumentV136.entries.forEachIndexed { index, document ->
            if (index > 0) append('\n')
            append(buildDocument(document, paper))
        }
    }

    fun buildDocument(document: PrinterPaperTestDocumentV136, paper: ReceiptPaper): String = buildString {
        val separator = separator(paper)
        append("[${document.displayName}] ${paper.widthMm}mm / ${paper.charsPerLine}桁\n")
        append(separator).append('\n')
        append(sampleLine(document)).append('\n')
        append(ruler(paper)).append('\n')
        append(separator).append('\n')
    }

    fun separator(paper: ReceiptPaper): String = "-".repeat(paper.charsPerLine)

    fun ruler(paper: ReceiptPaper): String = buildString(paper.charsPerLine) {
        for (index in 1..paper.charsPerLine) append(index % 10)
    }

    private fun sampleLine(document: PrinterPaperTestDocumentV136): String = when (document) {
        PrinterPaperTestDocumentV136.SALE_RECEIPT -> "商品A / 合計  ¥1,000"
        PrinterPaperTestDocumentV136.RECEIPT_VOUCHER -> "領収金額  ¥1,000"
        PrinterPaperTestDocumentV136.PROVISIONAL_RECEIPT -> "仮締め合計  ¥1,000"
        PrinterPaperTestDocumentV136.SETTLEMENT -> "精算売上  ¥1,000"
    }
}
