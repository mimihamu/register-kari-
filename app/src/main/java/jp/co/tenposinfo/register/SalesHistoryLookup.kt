package jp.co.tenposinfo.register

internal data class SalesHistoryCriteria(
    val query: String = "",
    val minAmount: Long? = null,
    val maxAmount: Long? = null,
)

internal object SalesHistoryLookupPolicy {
    const val RECENT_LOAD_LIMIT = 1_000

    fun filter(
        sales: List<SaleSummaryRecord>,
        criteria: SalesHistoryCriteria,
    ): List<SaleSummaryRecord> {
        val query = criteria.query.trim().removePrefix("#").lowercase()
        val min = criteria.minAmount
        val max = criteria.maxAmount
        if (min != null && max != null && min > max) return emptyList()

        return sales.filter { sale ->
            val matchesQuery = query.isBlank() ||
                sale.id.toString().contains(query) ||
                sale.operatorName.lowercase().contains(query) ||
                sale.paymentLabel.lowercase().contains(query)
            val matchesMin = min == null || sale.totalAmount >= min
            val matchesMax = max == null || sale.totalAmount <= max
            matchesQuery && matchesMin && matchesMax
        }
    }

    fun parseDirectSaleId(raw: String): Long? =
        raw.trim().removePrefix("#").takeIf { it.isNotBlank() && it.all(Char::isDigit) }
            ?.toLongOrNull()
            ?.takeIf { it > 0L }

    fun includeRequestedSale(
        recentSales: List<SaleSummaryRecord>,
        requestedSale: SaleSummaryRecord?,
    ): List<SaleSummaryRecord> {
        if (requestedSale == null || recentSales.any { it.id == requestedSale.id }) return recentSales
        return listOf(requestedSale) + recentSales
    }
}
