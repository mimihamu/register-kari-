package jp.co.tenposinfo.register

/**
 * TAX-011: レシートへ印字する税区分記号を、売上時点の税snapshot属性から確定する。
 *
 * 税率そのもの（例: 8%）から軽減税率を推測しない。reducedTax が唯一の判定根拠。
 * 税マスタの自由入力 symbol は設定表示用として残し、正式レシートの5種記号には使用しない。
 */
object ReceiptTaxSymbolV136 {
    fun fromProduct(product: Product): String = fromSnapshot(
        taxable = product.taxable,
        taxIncluded = product.taxIncluded,
        reducedTax = product.reducedTax,
    )

    fun fromSnapshot(
        taxable: Boolean,
        taxIncluded: Boolean,
        reducedTax: Boolean,
    ): String {
        if (!taxable) return "非"
        return when {
            taxIncluded && reducedTax -> "内※"
            taxIncluded -> "内"
            reducedTax -> "外※"
            else -> "外"
        }
    }

    /**
     * 80mmは税記号専用列を標準とする。プリンターの論理桁設定が狭く、
     * 商品名・数量・単価・金額を維持できる余地がない場合は商品名末尾方式へ戻す。
     */
    fun canUseDedicatedColumn(
        lineWidth: Int,
        taxColumnWidth: Int = 5,
        minimumNameWidth: Int = 16,
    ): Boolean = taxColumnWidth > 0 &&
        minimumNameWidth > 0 &&
        lineWidth - taxColumnWidth >= minimumNameWidth
}
