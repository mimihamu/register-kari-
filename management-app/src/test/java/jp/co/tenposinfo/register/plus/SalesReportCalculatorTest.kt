package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SalesReportCalculatorTest {
    @Test
    fun saleAndMatchedReversalProduceNetSalesAndActiveTransactions() {
        val report = SalesReportCalculator.calculate(
            listOf(
                entry(
                    key = "sale-1",
                    eventType = "SALE",
                    aggregateId = "1",
                    amount = 1_000,
                    payload = """{"totalAmount":1000}""",
                ),
                entry(
                    key = "sale-2",
                    eventType = "SALE",
                    aggregateId = "2",
                    amount = 2_000,
                    payload = """{"totalAmount":2000}""",
                ),
                entry(
                    key = "reversal-1",
                    eventType = "REVERSAL",
                    aggregateId = "10",
                    amount = 1_000,
                    payload = """{"originalSaleId":1,"amount":1000}""",
                ),
            ),
        )

        assertEquals(3_000L, report.grossSales)
        assertEquals(1_000L, report.reversalAmount)
        assertEquals(2_000L, report.netSales)
        assertEquals(2, report.saleCount)
        assertEquals(1, report.reversalCount)
        assertEquals(1, report.activeTransactionCount)
        assertEquals(2_000L, report.averageTicket)
        assertEquals(0, report.unmatchedReversalCount)
        assertTrue(report.totalsComplete)
    }

    @Test
    fun nonSalesEventsAreExcludedFromSalesTotals() {
        val report = SalesReportCalculator.calculate(
            listOf(
                entry("sale", "SALE", "1", 1_500, """{"totalAmount":1500}"""),
                entry("cash", "CASH_MOVEMENT", "2", 99_999, """{"amount":99999}"""),
                entry("inspection", "INSPECTION", "3", 50_000, """{"netSales":50000}"""),
            ),
        )

        assertEquals(1_500L, report.netSales)
        assertEquals(1, report.saleCount)
        assertEquals(2, report.ignoredEventCount)
        assertNull(report.details.first { it.eventType == "CASH_MOVEMENT" }.signedAmount)
    }

    @Test
    fun missingAmountPreventsAverageTicketAndMarksTotalsIncomplete() {
        val report = SalesReportCalculator.calculate(
            listOf(
                entry("sale-known", "SALE", "1", 1_000, """{"totalAmount":1000}"""),
                entry("sale-missing", "SALE", "2", null, """{"saleId":2}"""),
            ),
        )

        assertEquals(1_000L, report.netSales)
        assertEquals(1, report.missingAmountCount)
        assertFalse(report.totalsComplete)
        assertNull(report.averageTicket)
    }

    @Test
    fun businessDateStoreAndTerminalFiltersAreAppliedTogether() {
        val entries = listOf(
            entry("target", "SALE", "1", 1_000, """{"totalAmount":1000}"""),
            entry(
                key = "other-date",
                eventType = "SALE",
                aggregateId = "2",
                amount = 2_000,
                payload = """{"totalAmount":2000}""",
                businessDate = "2026-08-04",
            ),
            entry(
                key = "other-store",
                eventType = "SALE",
                aggregateId = "3",
                amount = 3_000,
                payload = """{"totalAmount":3000}""",
                storeId = "STORE-002",
            ),
            entry(
                key = "other-terminal",
                eventType = "SALE",
                aggregateId = "4",
                amount = 4_000,
                payload = """{"totalAmount":4000}""",
                terminalId = "TERMINAL-002",
            ),
        )

        val report = SalesReportCalculator.calculate(
            entries,
            SalesReportFilter(
                businessDate = "2026-08-05",
                storeId = "STORE-001",
                terminalId = "TERMINAL-001",
            ),
        )

        assertEquals(1_000L, report.netSales)
        assertEquals(1, report.saleCount)
        assertEquals("target", report.details.single().duplicateImportKey)
    }

    @Test
    fun paymentAndTaxBreakdownsSubtractReversalValues() {
        val report = SalesReportCalculator.calculate(
            listOf(
                entry(
                    key = "sale",
                    eventType = "SALE",
                    aggregateId = "1",
                    amount = 1_100,
                    payload = """
                        {
                          "totalAmount":1100,
                          "payments":[{"method":"現金","amount":1100}],
                          "taxTotals":[{"ratePercent":10,"taxAmount":100}]
                        }
                    """.trimIndent(),
                ),
                entry(
                    key = "reversal",
                    eventType = "REVERSAL",
                    aggregateId = "2",
                    amount = 550,
                    payload = """
                        {
                          "originalSaleId":1,
                          "amount":550,
                          "payments":[{"method":"現金","amount":550}],
                          "taxTotals":[{"ratePercent":10,"taxAmount":50}]
                        }
                    """.trimIndent(),
                ),
            ),
        )

        assertEquals(listOf(SalesAmountBreakdown("現金", 550)), report.paymentBreakdown)
        assertEquals(listOf(SalesAmountBreakdown("10%", 50)), report.taxBreakdown)
        assertTrue(report.paymentBreakdownComplete)
        assertTrue(report.taxBreakdownComplete)
    }

    private fun entry(
        key: String,
        eventType: String,
        aggregateId: String,
        amount: Long?,
        payload: String,
        businessDate: String = "2026-08-05",
        storeId: String = "STORE-001",
        terminalId: String = "TERMINAL-001",
    ): SalesJournalReportEntry = SalesJournalReportEntry(
        duplicateImportKey = key,
        eventType = eventType,
        storeId = storeId,
        terminalId = terminalId,
        businessDate = businessDate,
        aggregateId = aggregateId,
        occurredAt = 1_754_377_200_000L + key.hashCode(),
        payloadSchema = if (eventType == "REVERSAL") "register.reversal.v2" else "register.sale.v2",
        payloadJson = payload,
        totalAmount = amount,
        sourceName = "$key.json",
    )
}
