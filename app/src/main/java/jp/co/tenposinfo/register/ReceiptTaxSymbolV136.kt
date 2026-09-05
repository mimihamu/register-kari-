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
}
