package jp.co.tenposinfo.register

data class ReturnableSaleLine(
    val saleItemId: Long,
    val productId: String,
    val productName: String,
    val unitPrice: Long,
    val taxCategory: TaxCategory,
    val taxKey: String = taxCategory.name,
    val taxLabel: String = taxCategory.displayName,
    val taxRatePercent: Int = taxCategory.ratePercent,
    val taxIncluded: Boolean = taxCategory.taxIncluded,
    val taxable: Boolean = taxCategory.taxable,
    val reduced: Boolean = taxCategory.symbol.contains("※"),
    val taxSymbol: String = taxCategory.symbol,
    val originalQuantity: Int,
    val originalDiscount: Long,
    val note: String,
    val returnedQuantity: Int,
    val refundedDiscount: Long,
) {
    val remainingQuantity: Int get() = (originalQuantity - returnedQuantity).coerceAtLeast(0)
    val remainingDiscount: Long get() = (originalDiscount - refundedDiscount).coerceAtLeast(0)

    fun toReturnItem(quantity: Int): CartItem {
        require(quantity in 1..remainingQuantity) { "返品数量が残数を超えています" }
        val allocatedDiscount = if (quantity == remainingQuantity) {
            remainingDiscount
        } else {
            (originalDiscount * quantity / originalQuantity).coerceAtMost(remainingDiscount)
        }
        val product = TaxSnapshot(
            key = taxKey,
            label = taxLabel,
            ratePercent = taxRatePercent,
            taxIncluded = taxIncluded,
            taxable = taxable,
            reduced = reduced,
            symbol = taxSymbol,
        ).applyTo(Product(productId, productName, unitPrice, taxCategory, saleItemId.toInt()))
        return CartItem(product, quantity, unitPrice, allocatedDiscount, note)
    }
}

data class PartialReversalResult(
    val reversalId: Long,
    val refundAmount: Long,
    val printJobId: Long,
    val previewText: String,
)

object PartialReturnPolicy {
    fun operationKey(type: ReversalType, originalSaleId: Long, requestId: String): String {
        require(originalSaleId > 0) { "originalSaleId must be positive" }
        return when (type) {
            ReversalType.CANCEL -> "CANCEL:$originalSaleId"
            ReversalType.RETURN -> {
                require(requestId.isNotBlank()) { "requestId must not be blank" }
                "RETURN:$originalSaleId:${requestId.trim()}"
            }
        }
    }

    fun select(
        type: ReversalType,
        lines: List<ReturnableSaleLine>,
        requestedQuantities: Map<Long, Int>,
    ): List<Pair<ReturnableSaleLine, CartItem>> {
        require(lines.isNotEmpty()) { "元売上の商品明細が見つかりません" }
        if (type == ReversalType.CANCEL) {
            require(lines.all { it.returnedQuantity == 0 }) { "一部返品済みの売上は取消できません" }
        }
        val selected = lines.mapNotNull { line ->
            val quantity = when (type) {
                ReversalType.CANCEL -> line.remainingQuantity
                ReversalType.RETURN -> requestedQuantities[line.saleItemId] ?: 0
            }
            require(quantity >= 0) { "返品数量は0以上で指定してください" }
            if (quantity == 0) null else line to line.toReturnItem(quantity)
        }
        require(selected.isNotEmpty()) { "返品する商品と数量を選択してください" }
        return selected
    }

    fun allocateRefundPayments(
        refundTotal: Long,
        originalPayments: List<PaymentTotal>,
        fallbackMethod: String = "OTHER",
    ): List<PaymentTotal> {
        require(refundTotal > 0) { "返金額が0円です" }
        val source = originalPayments.filter { it.amount > 0 }
        if (source.isEmpty()) {
            // v1.35 COR-008:
            // Production reversal writes run inside SecureOperationsCoordinator's approved
            // refund context. In that context a missing original payment breakdown must not
            // silently fall back to CASH/OTHER; a manager explicitly selects the refund method.
            // Calls outside that approved runtime context retain the legacy pure-function
            // fallback so existing calculations/tests and read-only tooling remain compatible.
            val selectedMethod = ManualRefundFallbackRuntimeV135.resolveMethodOrRequest(
                refundTotal = refundTotal,
                suggestedMethod = fallbackMethod,
            ) ?: fallbackMethod
            return listOf(PaymentTotal(selectedMethod, refundTotal))
        }
        val sourceTotal = source.sumOf { it.amount }.coerceAtLeast(1)
        val result = mutableListOf<PaymentTotal>()
        var allocated = 0L
        source.forEachIndexed { index, payment ->
            val amount = if (index == source.lastIndex) {
                refundTotal - allocated
            } else {
                refundTotal * payment.amount / sourceTotal
            }.coerceAtLeast(0)
            allocated += amount
            if (amount > 0) result += PaymentTotal(payment.method, amount)
        }
        check(result.sumOf { it.amount } == refundTotal) { "返金内訳の配賦に失敗しました" }
        return result
    }
}
