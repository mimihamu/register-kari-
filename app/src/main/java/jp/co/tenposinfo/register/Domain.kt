package jp.co.tenposinfo.register

import java.math.BigInteger

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
    val taxKey: String = taxCategory.name,
    val taxLabel: String = taxCategory.displayName,
    val taxSymbol: String = taxCategory.symbol,
    val taxRatePercent: Int = taxCategory.ratePercent,
    val taxIncluded: Boolean = taxCategory.taxIncluded,
    val taxable: Boolean = taxCategory.taxable,
    val reducedTax: Boolean = taxCategory.symbol.contains("※"),
    val buttonColor: String = "BLUE",
    val pageNo: Int = ((displayOrder.coerceAtLeast(1) - 1) / 24) + 1,
    val slotNo: Int = ((displayOrder.coerceAtLeast(1) - 1) % 24) + 1,
    val kana: String = "",
    val barcode: String = "",
) {
    fun withLegacyTaxCategory(category: TaxCategory): Product = copy(
        taxCategory = category,
        taxKey = category.name,
        taxLabel = category.displayName,
        taxSymbol = category.symbol,
        taxRatePercent = category.ratePercent,
        taxIncluded = category.taxIncluded,
        taxable = category.taxable,
        reducedTax = category.symbol.contains("※"),
    )
}

data class CartItem(
    val product: Product,
    val quantity: Int,
    val unitPrice: Long = product.unitPrice,
    val discountAmount: Long = 0,
    val note: String = "",
    val lineId: String = "",
) {
    init {
        require(quantity > 0) { "quantity must be greater than zero" }
        require(unitPrice >= 0) { "unitPrice must not be negative" }
        require(discountAmount >= 0) { "discountAmount must not be negative" }
        require(discountAmount <= unitPrice * quantity) { "discount exceeds line amount" }
    }

    val amountBeforeDiscount: Long get() = unitPrice * quantity
    val baseAmount: Long get() = amountBeforeDiscount - discountAmount
}

data class TaxBucket(
    val ratePercent: Int,
    val taxable: Boolean,
    val sourceCategories: Set<TaxCategory>,
    val netAmount: Long,
    val taxAmount: Long,
    val grossAmount: Long,
    val sourceTaxKeys: Set<String> = sourceCategories.mapTo(linkedSetOf()) { it.name },
)

data class TaxSummary(val buckets: List<TaxBucket>) {
    val netAmount: Long get() = buckets.sumOf { it.netAmount }
    val taxAmount: Long get() = buckets.sumOf { it.taxAmount }
    val grossAmount: Long get() = buckets.sumOf { it.grossAmount }
}

enum class MixedTaxPolicy { ALLOW, WARN, BLOCK }

data class MixedTaxResult(val hasMixedTax: Boolean, val message: String?)

object TaxEngine {
    /**
     * 値引後の金額を税率単位に合算し、1インボイス・税率ごとに一度だけ端数処理する。
     * 商品が任意税率マスターを使用している場合も、商品スナップショットの税率・内外税方式で計算する。
     */
    private fun floorMultiplyDivide(amount: Long, multiplier: Int, divisor: Int): Long {
        require(amount >= 0) { "amount must not be negative" }
        require(multiplier >= 0) { "multiplier must not be negative" }
        require(divisor > 0) { "divisor must be positive" }
        return BigInteger.valueOf(amount)
            .multiply(BigInteger.valueOf(multiplier.toLong()))
            .divide(BigInteger.valueOf(divisor.toLong()))
            .longValueExact()
    }

    /**
     * 同率の内税・外税が混在する場合、内税部分と外税部分の正確な税相当額を
     * 分数のまま加算し、税率単位で最後に一度だけ切り捨てる。
     */
    private fun mixedTaxAmount(includedGross: Long, excludedNet: Long, rate: Int): Long {
        val rateValue = BigInteger.valueOf(rate.toLong())
        val hundred = BigInteger.valueOf(100L)
        val grossDivisor = BigInteger.valueOf((100L + rate).toLong())
        val includedNumerator = BigInteger.valueOf(includedGross)
            .multiply(rateValue)
            .multiply(hundred)
        val excludedNumerator = BigInteger.valueOf(excludedNet)
            .multiply(rateValue)
            .multiply(grossDivisor)
        return includedNumerator
            .add(excludedNumerator)
            .divide(hundred.multiply(grossDivisor))
            .longValueExact()
    }

    fun calculate(items: List<CartItem>): TaxSummary {
        val taxableBuckets = items
            .filter { it.product.taxable }
            .groupBy { it.product.taxRatePercent }
            .map { (rate, rows) ->
                require(rate in 1..100) { "tax rate must be between 1 and 100" }
                val includedGross = rows.filter { it.product.taxIncluded }.sumOf { it.baseAmount }
                val excludedNet = rows.filterNot { it.product.taxIncluded }.sumOf { it.baseAmount }
                val categories = rows.map { it.product.taxCategory }.toSet()
                val keys = rows.mapTo(linkedSetOf()) { it.product.taxKey }

                val net: Long
                val tax: Long
                val gross: Long
                when {
                    includedGross > 0 && excludedNet > 0 -> {
                        val excludedTax = floorMultiplyDivide(excludedNet, rate, 100)
                        gross = Math.addExact(Math.addExact(includedGross, excludedNet), excludedTax)
                        tax = mixedTaxAmount(includedGross, excludedNet, rate)
                        net = gross - tax
                    }
                    includedGross > 0 -> {
                        tax = floorMultiplyDivide(includedGross, rate, 100 + rate)
                        net = includedGross - tax
                        gross = includedGross
                    }
                    else -> {
                        net = excludedNet
                        tax = floorMultiplyDivide(net, rate, 100)
                        gross = Math.addExact(net, tax)
                    }
                }
                TaxBucket(rate, true, categories, net, tax, gross, keys)
            }

        val nonTaxableRows = items.filterNot { it.product.taxable }
        val nonTaxable = nonTaxableRows.sumOf { it.baseAmount }
        val buckets = taxableBuckets.toMutableList()
        if (nonTaxable > 0) {
            buckets += TaxBucket(
                ratePercent = 0,
                taxable = false,
                sourceCategories = nonTaxableRows.map { it.product.taxCategory }.toSet()
                    .ifEmpty { setOf(TaxCategory.NON_TAXABLE) },
                netAmount = nonTaxable,
                taxAmount = 0,
                grossAmount = nonTaxable,
                sourceTaxKeys = nonTaxableRows.mapTo(linkedSetOf()) { it.product.taxKey },
            )
        }
        return TaxSummary(buckets.sortedWith(compareBy<TaxBucket> { !it.taxable }.thenByDescending { it.ratePercent }))
    }

    fun validateMixedTax(items: List<CartItem>, policy: MixedTaxPolicy): MixedTaxResult {
        val mixedRates = items
            .filter { it.product.taxable }
            .groupBy { it.product.taxRatePercent }
            .filterValues { rows -> rows.map { it.product.taxIncluded }.toSet().size > 1 }
            .keys
            .sorted()
        if (mixedRates.isEmpty()) return MixedTaxResult(false, null)
        val message = "同一税率の内税商品と外税商品が混在しています（${mixedRates.joinToString("・") { "$it%" }}）"
        if (policy == MixedTaxPolicy.BLOCK) throw IllegalStateException(message)
        return MixedTaxResult(true, if (policy == MixedTaxPolicy.WARN) message else null)
    }
}

enum class DiscountType { FIXED, PERCENT }
enum class DiscountScope { ITEM, TRANSACTION }

object DiscountEngine {
    fun applyToItem(item: CartItem, type: DiscountType, value: Long): CartItem {
        require(value >= 0) { "discount value must not be negative" }
        val available = item.amountBeforeDiscount - item.discountAmount
        val requested = when (type) {
            DiscountType.FIXED -> value
            DiscountType.PERCENT -> item.amountBeforeDiscount * value / 10_000
        }
        val additional = requested.coerceIn(0, available)
        return item.copy(discountAmount = item.discountAmount + additional)
    }

    fun applyToTransaction(items: List<CartItem>, type: DiscountType, value: Long): List<CartItem> {
        if (items.isEmpty()) return items
        require(value >= 0) { "discount value must not be negative" }
        val availableByRow = items.map { it.amountBeforeDiscount - it.discountAmount }
        val availableTotal = availableByRow.sum()
        if (availableTotal <= 0) return items
        val requested = when (type) {
            DiscountType.FIXED -> value
            DiscountType.PERCENT -> availableTotal * value / 10_000
        }.coerceIn(0, availableTotal)

        var allocated = 0L
        return items.mapIndexed { index, item ->
            val share = if (index == items.lastIndex) {
                requested - allocated
            } else {
                requested * availableByRow[index] / availableTotal
            }.coerceAtMost(availableByRow[index])
            allocated += share
            item.copy(discountAmount = item.discountAmount + share)
        }
    }
}

enum class PaymentMethod(val displayName: String) {
    CASH("現金"),
    CARD("クレジット"),
    GIFT_CERTIFICATE("商品券"),
    ACCOUNT_RECEIVABLE("掛売"),
    OTHER("その他"),
}

data class PaymentAllocation(
    val method: PaymentMethod,
    val appliedAmount: Long,
    val receivedAmount: Long,
) {
    init {
        require(appliedAmount > 0) { "appliedAmount must be positive" }
        require(receivedAmount >= appliedAmount) { "receivedAmount must cover appliedAmount" }
    }
}

data class PaymentState(val allocations: List<PaymentAllocation> = emptyList()) {
    val paidAmount: Long get() = allocations.sumOf { it.appliedAmount }
    fun remaining(total: Long): Long = (total - paidAmount).coerceAtLeast(0)
    val changeAmount: Long get() = allocations.sumOf { (it.receivedAmount - it.appliedAmount).coerceAtLeast(0) }
}

object PaymentEngine {
    fun addPayment(
        state: PaymentState,
        total: Long,
        method: PaymentMethod,
        inputAmount: Long?,
    ): PaymentState {
        val remaining = state.remaining(total)
        require(remaining > 0) { "payment is already complete" }
        val allocation = if (method == PaymentMethod.CASH) {
            val received = inputAmount ?: remaining
            require(received > 0) { "cash received must be positive" }
            PaymentAllocation(method, received.coerceAtMost(remaining), received)
        } else {
            val applied = inputAmount ?: remaining
            require(applied > 0) { "payment amount must be positive" }
            require(applied <= remaining) { "non-cash payment must not exceed remaining amount" }
            PaymentAllocation(method, applied, applied)
        }
        return state.copy(allocations = state.allocations + allocation)
    }

    fun removeAt(state: PaymentState, index: Int): PaymentState {
        require(index in state.allocations.indices) { "invalid payment index" }
        return state.copy(allocations = state.allocations.filterIndexed { i, _ -> i != index })
    }
}
