package jp.co.tenposinfo.register

import java.time.LocalDate

internal data class SalesHistoryCriteria(
    val query: String = "",
    val minAmount: Long? = null,
    val maxAmount: Long? = null,
    val businessDateFrom: String = "",
    val businessDateTo: String = "",
)

internal data class SalesHistoryCriteriaValidation(
    val valid: Boolean,
    val businessDateFrom: String?,
    val businessDateTo: String?,
    val message: String? = null,
)

internal data class BusinessDateSaleRecord(
    val summary: SaleSummaryRecord,
    val businessDate: String?,
    val businessSessionId: Long?,
)

internal object SalesHistoryLookupPolicy {
    const val RECENT_LOAD_LIMIT = 1_000

    fun validate(criteria: SalesHistoryCriteria): SalesHistoryCriteriaValidation {
        val min = criteria.minAmount
        val max = criteria.maxAmount
        if (min != null && max != null && min > max) {
            return SalesHistoryCriteriaValidation(false, null, null, "金額範囲は『以上 ≤ 以下』になるよう入力してください")
        }

        val from = parseBusinessDate(criteria.businessDateFrom)
        if (criteria.businessDateFrom.isNotBlank() && from == null) {
            return SalesHistoryCriteriaValidation(false, null, null, "営業日FromはYYYY-MM-DD形式で入力してください")
        }
        val to = parseBusinessDate(criteria.businessDateTo)
        if (criteria.businessDateTo.isNotBlank() && to == null) {
            return SalesHistoryCriteriaValidation(false, null, null, "営業日ToはYYYY-MM-DD形式で入力してください")
        }
        if (from != null && to != null && from.isAfter(to)) {
            return SalesHistoryCriteriaValidation(false, from.toString(), to.toString(), "営業日範囲はFrom ≤ Toになるよう入力してください")
        }
        return SalesHistoryCriteriaValidation(
            valid = true,
            businessDateFrom = from?.toString(),
            businessDateTo = to?.toString(),
        )
    }

    fun filter(
        sales: List<SaleSummaryRecord>,
        criteria: SalesHistoryCriteria,
    ): List<SaleSummaryRecord> {
        val validation = validate(criteria)
        if (!validation.valid) return emptyList()
        // SaleSummaryRecord itself intentionally remains the v0.15 immutable sales snapshot.
        // Business-date filtering uses filterBusinessDate() so legacy callers cannot silently
        // infer a business date from createdAt.
        if (validation.businessDateFrom != null || validation.businessDateTo != null) return emptyList()

        val query = criteria.query.trim().removePrefix("#").lowercase()
        val min = criteria.minAmount
        val max = criteria.maxAmount
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

    fun filterBusinessDate(
        sales: List<BusinessDateSaleRecord>,
        criteria: SalesHistoryCriteria,
    ): List<BusinessDateSaleRecord> {
        val validation = validate(criteria)
        if (!validation.valid) return emptyList()

        val query = criteria.query.trim().removePrefix("#").lowercase()
        val min = criteria.minAmount
        val max = criteria.maxAmount
        val from = validation.businessDateFrom
        val to = validation.businessDateTo

        return sales.filter { record ->
            val sale = record.summary
            val businessDate = record.businessDate
            val matchesQuery = query.isBlank() ||
                sale.id.toString().contains(query) ||
                sale.operatorName.lowercase().contains(query) ||
                sale.paymentLabel.lowercase().contains(query) ||
                businessDate?.lowercase()?.contains(query) == true
            val matchesMin = min == null || sale.totalAmount >= min
            val matchesMax = max == null || sale.totalAmount <= max
            val matchesBusinessDate = when {
                from == null && to == null -> true
                businessDate == null -> false
                from != null && businessDate < from -> false
                to != null && businessDate > to -> false
                else -> true
            }
            matchesQuery && matchesMin && matchesMax && matchesBusinessDate
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

    private fun parseBusinessDate(raw: String): LocalDate? =
        raw.trim().takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
