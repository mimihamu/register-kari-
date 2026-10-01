package jp.co.tenposinfo.register

/**
 * Formal v2.5 §16.3 receipt emphasis policy.
 *
 * Styling is decided after layout and encoded as ESC/POS bytes separately from
 * printable text, so user-entered text can never inject printer control codes.
 */
object ReceiptPrintEmphasisV136 {
    fun shouldEmphasize(line: String, paper: ReceiptPaper? = null): Boolean {
        if (paper != null && ReceiptLineWrapV136.displayWidth(line) > paper.charsPerLine) return false
        val value = line.trim()
        return value == "領収書／レシート" ||
            value == "【再発行】" ||
            value == "【再印字】" ||
            value == "【取消レシート】" ||
            value == "【返品レシート】" ||
            value == "【Z精算票】" ||
            value == "【X点検票】" ||
            value.startsWith("合計 ") ||
            value.startsWith("お釣り ") ||
            value.startsWith("返金合計")
    }
}
