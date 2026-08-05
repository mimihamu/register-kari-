package jp.co.tenposinfo.register.plus

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

data class SalesReportFilter(
    val businessDate: String? = null,
    val storeId: String? = null,
    val terminalId: String? = null,
)

data class SalesReportFilterOptions(
    val businessDates: List<String> = emptyList(),
    val storeIds: List<String> = emptyList(),
    val terminalIds: List<String> = emptyList(),
)

data class SalesJournalReportEntry(
    val duplicateImportKey: String,
    val eventType: String,
    val storeId: String,
    val terminalId: String,
    val businessDate: String,
    val aggregateId: String,
    val occurredAt: Long,
    val payloadSchema: String,
    val payloadJson: String,
    val totalAmount: Long?,
    val sourceName: String,
)

data class SalesAmountBreakdown(
    val label: String,
    val amount: Long,
)

data class SalesReportDetail(
    val duplicateImportKey: String,
    val eventType: String,
    val storeId: String,
    val terminalId: String,
    val businessDate: String,
    val aggregateId: String,
    val occurredAt: Long,
    val signedAmount: Long?,
    val originalSaleId: String?,
    val payloadSchema: String,
    val payloadJson: String,
    val sourceName: String,
)

data class SalesReport(
    val filter: SalesReportFilter,
    val grossSales: Long,
    val reversalAmount: Long,
    val netSales: Long,
    val saleCount: Int,
    val reversalCount: Int,
    val activeTransactionCount: Int,
    val averageTicket: Long?,
    val missingAmountCount: Int,
    val unmatchedReversalCount: Int,
    val ignoredEventCount: Int,
    val totalsComplete: Boolean,
    val paymentBreakdown: List<SalesAmountBreakdown>,
    val paymentBreakdownComplete: Boolean,
    val taxBreakdown: List<SalesAmountBreakdown>,
    val taxBreakdownComplete: Boolean,
    val details: List<SalesReportDetail>,
) {
    companion object {
        fun empty(filter: SalesReportFilter = SalesReportFilter()): SalesReport = SalesReport(
            filter = filter,
            grossSales = 0L,
            reversalAmount = 0L,
            netSales = 0L,
            saleCount = 0,
            reversalCount = 0,
            activeTransactionCount = 0,
            averageTicket = null,
            missingAmountCount = 0,
            unmatchedReversalCount = 0,
            ignoredEventCount = 0,
            totalsComplete = true,
            paymentBreakdown = emptyList(),
            paymentBreakdownComplete = false,
            taxBreakdown = emptyList(),
            taxBreakdownComplete = false,
            details = emptyList(),
        )
    }
}

object SalesReportCalculator {
    fun calculate(
        entries: List<SalesJournalReportEntry>,
        filter: SalesReportFilter = SalesReportFilter(),
    ): SalesReport {
        val filtered = entries.filter { entry ->
            (filter.businessDate == null || entry.businessDate == filter.businessDate) &&
                (filter.storeId == null || entry.storeId == filter.storeId) &&
                (filter.terminalId == null || entry.terminalId == filter.terminalId)
        }
        val sales = filtered.filter { it.eventType == EVENT_SALE }
        val reversals = filtered.filter { it.eventType == EVENT_REVERSAL }
        val ignoredEventCount = filtered.size - sales.size - reversals.size

        val resolvedAmounts = filtered.associateWith(::resolvedAmount)
        val grossSales = sales.sumOf { resolvedAmounts[it] ?: 0L }
        val reversalAmount = reversals.sumOf { resolvedAmounts[it] ?: 0L }
        val missingAmountCount = (sales + reversals).count { resolvedAmounts[it] == null }
        val totalsComplete = missingAmountCount == 0

        val scopedSales = sales.associateBy { scopedAggregateId(it, it.aggregateId) }
        val matchedReversalScopes = mutableSetOf<String>()
        var unmatchedReversalCount = 0
        reversals.forEach { reversal ->
            val originalSaleId = originalSaleId(reversal.payloadJson)
            val scopedOriginal = originalSaleId?.let { scopedAggregateId(reversal, it) }
            if (scopedOriginal != null && scopedOriginal in scopedSales) {
                matchedReversalScopes += scopedOriginal
            } else {
                unmatchedReversalCount += 1
            }
        }
        val activeTransactionCount = max(0, scopedSales.size - matchedReversalScopes.size)
        val netSales = grossSales - reversalAmount
        val averageTicket = if (
            totalsComplete &&
            unmatchedReversalCount == 0 &&
            activeTransactionCount > 0
        ) {
            netSales / activeTransactionCount
        } else {
            null
        }

        val paymentResult = aggregateBreakdown(
            sales = sales,
            reversals = reversals,
            amountResolver = resolvedAmounts,
            extractor = ::paymentBreakdown,
        )
        val taxResult = aggregateBreakdown(
            sales = sales,
            reversals = reversals,
            amountResolver = resolvedAmounts,
            extractor = ::taxBreakdown,
        )

        val details = filtered
            .sortedByDescending(SalesJournalReportEntry::occurredAt)
            .map { entry ->
                val amount = resolvedAmounts[entry]
                SalesReportDetail(
                    duplicateImportKey = entry.duplicateImportKey,
                    eventType = entry.eventType,
                    storeId = entry.storeId,
                    terminalId = entry.terminalId,
                    businessDate = entry.businessDate,
                    aggregateId = entry.aggregateId,
                    occurredAt = entry.occurredAt,
                    signedAmount = when (entry.eventType) {
                        EVENT_SALE -> amount
                        EVENT_REVERSAL -> amount?.let { -it }
                        else -> null
                    },
                    originalSaleId = if (entry.eventType == EVENT_REVERSAL) {
                        originalSaleId(entry.payloadJson)
                    } else {
                        null
                    },
                    payloadSchema = entry.payloadSchema,
                    payloadJson = entry.payloadJson,
                    sourceName = entry.sourceName,
                )
            }

        return SalesReport(
            filter = filter,
            grossSales = grossSales,
            reversalAmount = reversalAmount,
            netSales = netSales,
            saleCount = sales.size,
            reversalCount = reversals.size,
            activeTransactionCount = activeTransactionCount,
            averageTicket = averageTicket,
            missingAmountCount = missingAmountCount,
            unmatchedReversalCount = unmatchedReversalCount,
            ignoredEventCount = ignoredEventCount,
            totalsComplete = totalsComplete,
            paymentBreakdown = paymentResult.items,
            paymentBreakdownComplete = paymentResult.complete,
            taxBreakdown = taxResult.items,
            taxBreakdownComplete = taxResult.complete,
            details = details,
        )
    }

    private data class BreakdownResult(
        val items: List<SalesAmountBreakdown>,
        val complete: Boolean,
    )

    private fun aggregateBreakdown(
        sales: List<SalesJournalReportEntry>,
        reversals: List<SalesJournalReportEntry>,
        amountResolver: Map<SalesJournalReportEntry, Long?>,
        extractor: (String, Long?) -> Map<String, Long>,
    ): BreakdownResult {
        val totals = linkedMapOf<String, Long>()
        var complete = sales.isNotEmpty() || reversals.isNotEmpty()
        (sales + reversals).forEach { entry ->
            val amount = amountResolver[entry]
            val extracted = extractor(entry.payloadJson, amount)
            if (extracted.isEmpty()) complete = false
            val sign = if (entry.eventType == EVENT_REVERSAL) -1L else 1L
            extracted.forEach { (label, value) ->
                totals[label] = (totals[label] ?: 0L) + (value * sign)
            }
        }
        return BreakdownResult(
            items = totals
                .filterValues { it != 0L }
                .map { SalesAmountBreakdown(it.key, it.value) }
                .sortedByDescending { kotlin.math.abs(it.amount) },
            complete = complete && totals.isNotEmpty(),
        )
    }

    private fun resolvedAmount(entry: SalesJournalReportEntry): Long? =
        entry.totalAmount ?: runCatching {
            val payload = JSONObject(entry.payloadJson)
            firstLong(payload, "totalAmount", "grossAmount", "amount")
        }.getOrNull()

    private fun originalSaleId(payloadJson: String): String? = runCatching {
        val payload = JSONObject(payloadJson)
        when {
            payload.has("originalSaleId") && !payload.isNull("originalSaleId") ->
                payload.get("originalSaleId").toString().trim().takeIf(String::isNotEmpty)
            else -> null
        }
    }.getOrNull()

    private fun paymentBreakdown(payloadJson: String, resolvedAmount: Long?): Map<String, Long> =
        runCatching {
            val payload = JSONObject(payloadJson)
            val result = linkedMapOf<String, Long>()
            readBreakdownContainer(payload.opt("paymentBreakdown"), result)
            readBreakdownContainer(payload.opt("payments"), result)
            if (result.isEmpty()) {
                val label = firstText(payload, "paymentMethod", "paymentType", "tenderType")
                val amount = firstLong(payload, "paymentAmount", "paidAmount") ?: resolvedAmount
                if (label != null && amount != null) result[label] = amount
            }
            result
        }.getOrDefault(emptyMap())

    private fun taxBreakdown(payloadJson: String, @Suppress("UNUSED_PARAMETER") resolvedAmount: Long?): Map<String, Long> =
        runCatching {
            val payload = JSONObject(payloadJson)
            val result = linkedMapOf<String, Long>()
            val totals = payload.optJSONArray("taxTotals")
            if (totals != null) {
                for (index in 0 until totals.length()) {
                    val row = totals.optJSONObject(index) ?: continue
                    val rate = firstText(row, "ratePercent", "taxRate", "rate")
                    val category = firstText(row, "taxCategory", "category", "name")
                    val label = when {
                        rate != null && category != null -> "$rate% $category"
                        rate != null -> "$rate%"
                        category != null -> category
                        else -> "税額"
                    }
                    val amount = firstLong(row, "taxAmount", "amount") ?: continue
                    result[label] = (result[label] ?: 0L) + amount
                }
            }
            if (result.isEmpty()) {
                firstLong(payload, "taxAmount")?.let { result["税額合計"] = it }
            }
            result
        }.getOrDefault(emptyMap())

    private fun readBreakdownContainer(value: Any?, result: MutableMap<String, Long>) {
        when (value) {
            is JSONObject -> {
                value.keys().forEach { key ->
                    val child = value.opt(key)
                    val amount = when (child) {
                        is Number -> child.toLong()
                        is JSONObject -> firstLong(child, "amount", "paidAmount", "value")
                        else -> child?.toString()?.toLongOrNull()
                    }
                    if (amount != null) result[key] = (result[key] ?: 0L) + amount
                }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    val row = value.optJSONObject(index) ?: continue
                    val label = firstText(row, "method", "paymentMethod", "type", "name") ?: "支払${index + 1}"
                    val amount = firstLong(row, "amount", "paidAmount", "value") ?: continue
                    result[label] = (result[label] ?: 0L) + amount
                }
            }
        }
    }

    private fun firstLong(json: JSONObject, vararg names: String): Long? {
        names.forEach { name ->
            if (json.has(name) && !json.isNull(name)) {
                val value = json.opt(name)
                when (value) {
                    is Number -> return value.toLong()
                    else -> value?.toString()?.toLongOrNull()?.let { return it }
                }
            }
        }
        return null
    }

    private fun firstText(json: JSONObject, vararg names: String): String? {
        names.forEach { name ->
            if (json.has(name) && !json.isNull(name)) {
                json.opt(name)?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let { return it }
            }
        }
        return null
    }

    private fun scopedAggregateId(entry: SalesJournalReportEntry, aggregateId: String): String =
        listOf(entry.storeId, entry.terminalId, aggregateId).joinToString("|")

    const val EVENT_SALE = "SALE"
    const val EVENT_REVERSAL = "REVERSAL"
}
