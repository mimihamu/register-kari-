package jp.co.tenposinfo.register

/**
 * Formal v2.5 §16.3 receipt emphasis policy.
 *
 * Styling is decided after layout and encoded as ESC/POS bytes separately from
 * printable text, so user-entered text can never inject printer control codes.
 */
enum class ReceiptEmphasisStyleV136 {
    NONE,
    BOLD,
    BOLD_DOUBLE_HEIGHT,
}

object ReceiptPrintEmphasisV136 {
    fun styleFor(line: String, paper: ReceiptPaper? = null): ReceiptEmphasisStyleV136 {
        if (paper != null && ReceiptLineWrapV136.displayWidth(line) > paper.charsPerLine) {
            return ReceiptEmphasisStyleV136.NONE
        }
        val value = line.trim()
        val emphasized = value == "領収書／レシート" ||
            value == "【再発行】" ||
            value == "【再印字】" ||
            value == "【取消レシート】" ||
            value == "【返品レシート】" ||
            value == "【Z精算票】" ||
            value == "【X点検票】" ||
            value.startsWith("合計 ") ||
            value.startsWith("お釣り ") ||
            value.startsWith("返金合計")
        if (!emphasized) return ReceiptEmphasisStyleV136.NONE
        return if (
            value.startsWith("合計 ") ||
            value.startsWith("お釣り ") ||
            value.startsWith("返金合計")
        ) {
            ReceiptEmphasisStyleV136.BOLD_DOUBLE_HEIGHT
        } else {
            ReceiptEmphasisStyleV136.BOLD
        }
    }

    fun shouldEmphasize(line: String, paper: ReceiptPaper? = null): Boolean =
        styleFor(line, paper) != ReceiptEmphasisStyleV136.NONE
}
