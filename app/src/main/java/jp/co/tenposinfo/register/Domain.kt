package jp.co.tenposinfo.register

enum class TaxCategory(
    val displayName: String,
    val symbol: String,
    val ratePercent: Int,
    val taxIncluded: Boolean,
    val taxable: Boolean,
) {
    NON_TAXABLE("非課税", "非", 0, false, false),
    INCLUDED_10("10%内税", "内", 10, true, true),
    EXCLUDED_10("10%外税", "外", 10, false, true),
    INCLUDED_8("8%内税", "内※", 8, true, true),
    EXCLUDED_8("8%外税", "外※", 8, false, true),
}

data class Product(
    val id: String,
    val name: String,
    val unitPrice: Long,
    val taxCategory: TaxCategory,
    val displayOrder: Int,
)

data class CartItem(
    val product: Product,
    val quantity: Int,
) {
    init {
        require(quantity > 0) { "quantity must be greater than zero" }
    }

    val baseAmount: Long get() = product.unitPrice * quantity
}

data class TaxBucket(
    val category: TaxCategory,
    val netAmount: Long,
    val taxAmount: Long,
    val grossAmount: Long,
)

data class TaxSummary(
    val buckets: List<TaxBucket>,
) {
    val netAmount: Long get() = buckets.sumOf { it.netAmount }
    val taxAmount: Long get() = buckets.sumOf { it.taxAmount }
    val grossAmount: Long get() = buckets.sumOf { it.grossAmount }
}

enum class MixedTaxPolicy {
    ALLOW,
    WARN,
    BLOCK,
}

data class MixedTaxResult(
    val hasMixedTax: Boolean,
    val message: String?,
)

object TaxEngine {
    /**
     * 税区分ごとの合計額に対して一度だけ税計算し、1円未満を切り捨てる。
     * 外税商品のunitPriceは税抜、内税商品のunitPriceは税込として扱う。
     */
    fun calculate(items: List<CartItem>): TaxSummary {
        val buckets = items
            .groupBy { it.product.taxCategory }
            .map { (category, rows) ->
                val amount = rows.sumOf { it.baseAmount }
                when {
                    !category.taxable -> TaxBucket(
                        category = category,
                        netAmount = amount,
                        taxAmount = 0,
                        grossAmount = amount,
                    )

                    category.taxIncluded -> {
                        val tax = amount * category.ratePercent / (100 + category.ratePercent)
                        TaxBucket(
                            category = category,
                            netAmount = amount - tax,
                            taxAmount = tax,
                            grossAmount = amount,
                        )
                    }

                    else -> {
                        val tax = amount * category.ratePercent / 100
                        TaxBucket(
                            category = category,
                            netAmount = amount,
                            taxAmount = tax,
                            grossAmount = amount + tax,
                        )
                    }
                }
            }
            .sortedBy { it.category.ordinal }
        return TaxSummary(buckets)
    }

    fun validateMixedTax(items: List<CartItem>, policy: MixedTaxPolicy): MixedTaxResult {
        val categories = items.map { it.product.taxCategory }.toSet()
        val mixed10 = TaxCategory.INCLUDED_10 in categories && TaxCategory.EXCLUDED_10 in categories
        val mixed8 = TaxCategory.INCLUDED_8 in categories && TaxCategory.EXCLUDED_8 in categories
        val mixed = mixed10 || mixed8
        if (!mixed) return MixedTaxResult(false, null)

        val message = "同一税率の内税商品と外税商品が混在しています"
        if (policy == MixedTaxPolicy.BLOCK) {
            throw IllegalStateException(message)
        }
        return MixedTaxResult(true, if (policy == MixedTaxPolicy.WARN) message else null)
    }
}
