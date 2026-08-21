package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V135Period001SalesReportRangeTest {
    @Test
    fun inclusiveFromToRangeAggregatesOnlyRequestedBusinessDates() {
        val report = SalesReportCalculator.calculate(
            entries = listOf(
                sale("before", "2026-07-31", 900),
                sale("from", "2026-08-01", 1_000),
                sale("middle", "2026-08-02", 2_000),
                sale("to", "2026-08-03", 3_000),
                sale("after", "2026-08-04", 4_000),
            ),
            filter = SalesReportFilter(
                businessDateFrom = "2026-08-01",
                businessDateTo = "2026-08-03",
            ),
        )

        assertEquals(6_000L, report.netSales)
        assertEquals(3, report.saleCount)
        assertEquals(
            setOf("from", "middle", "to"),
            report.details.map { it.duplicateImportKey }.toSet(),
        )
    }

    @Test
    fun exactBusinessDateRemainsBackwardCompatibleAndTakesPrecedence() {
        val filter = SalesReportFilter(
            businessDate = "2026-08-10",
            businessDateFrom = "2026-08-01",
            businessDateTo = "2026-08-31",
        )
        val report = SalesReportCalculator.calculate(
            entries = listOf(
                sale("exact", "2026-08-10", 1_500),
                sale("range-only", "2026-08-11", 9_000),
            ),
            filter = filter,
        )

        assertNull(SalesReportPeriodPolicy.validationError(filter))
        assertEquals(1_500L, report.netSales)
        assertEquals("exact", report.details.single().duplicateImportKey)
    }

    @Test
    fun thirtyOneDayRangeIsAcceptedAndLongerRangeFailsClosed() {
        val accepted = SalesReportFilter(
            businessDateFrom = "2026-08-01",
            businessDateTo = "2026-08-31",
        )
        val rejected = accepted.copy(businessDateTo = "2026-09-01")

        assertNull(SalesReportPeriodPolicy.validationError(accepted))
        assertNotNull(SalesReportPeriodPolicy.validationError(rejected))

        val acceptedReport = SalesReportCalculator.calculate(
            listOf(sale("end", "2026-08-31", 3_100)),
            accepted,
        )
        val rejectedReport = SalesReportCalculator.calculate(
            listOf(sale("would-match", "2026-08-15", 8_150)),
            rejected,
        )
        assertEquals(3_100L, acceptedReport.netSales)
        assertEquals(0L, rejectedReport.netSales)
        assertTrue(rejectedReport.details.isEmpty())
    }

    @Test
    fun reversedOrPartialRangesFailClosed() {
        val reversed = SalesReportFilter(
            businessDateFrom = "2026-08-05",
            businessDateTo = "2026-08-04",
        )
        val partial = SalesReportFilter(businessDateFrom = "2026-08-05")

        assertNotNull(SalesReportPeriodPolicy.validationError(reversed))
        assertNotNull(SalesReportPeriodPolicy.validationError(partial))
        assertTrue(
            SalesReportCalculator.calculate(
                listOf(sale("reversed", "2026-08-05", 1_000)),
                reversed,
            ).details.isEmpty(),
        )
        assertTrue(
            SalesReportCalculator.calculate(
                listOf(sale("partial", "2026-08-05", 1_000)),
                partial,
            ).details.isEmpty(),
        )
    }

    @Test
    fun toDateChoicesRespectStartDateAndMaximumRange() {
        val choices = SalesReportPeriodPolicy.selectableToDates(
            from = "2026-08-02",
            dates = listOf(
                "2026-08-01",
                "2026-08-02",
                "2026-09-01",
                "2026-09-02",
            ),
        )

        assertEquals(listOf("2026-08-02", "2026-09-01"), choices)
    }

    private fun sale(
        key: String,
        businessDate: String,
        amount: Long,
    ): SalesJournalReportEntry = SalesJournalReportEntry(
        duplicateImportKey = key,
        eventType = SalesReportCalculator.EVENT_SALE,
        storeId = "STORE-001",
        terminalId = "TERMINAL-001",
        businessDate = businessDate,
        aggregateId = key,
        occurredAt = 1_754_377_200_000L + key.hashCode(),
        payloadSchema = "register.sale.v2",
        payloadJson = """{"totalAmount":$amount}""",
        totalAmount = amount,
        sourceName = "$key.json",
    )
}
