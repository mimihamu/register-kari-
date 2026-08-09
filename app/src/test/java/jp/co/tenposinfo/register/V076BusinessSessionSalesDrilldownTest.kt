package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V076BusinessSessionSalesDrilldownTest {
    @Test
    fun databaseQueryBindsExactBusinessDateAndSession() {
        val criteria = SalesHistoryCriteria(
            businessDateFrom = "2026-08-08",
            businessDateTo = "2026-08-08",
            businessSessionId = 42L,
        )
        val query = SalesHistoryLookupPolicy.buildDatabaseQuery(
            criteria = criteria,
            businessDateColumnAvailable = true,
            businessSessionIdColumnAvailable = true,
        )
        requireNotNull(query)
        assertTrue(query.whereSql.contains("business_date >= ?"))
        assertTrue(query.whereSql.contains("business_date <= ?"))
        assertTrue(query.whereSql.contains("business_session_id = ?"))
        assertEquals(listOf("2026-08-08", "2026-08-08", "42"), query.args)
    }

    @Test
    fun sessionCriterionFailsClosedWhenLegacyColumnIsUnavailable() {
        val criteria = SalesHistoryCriteria(
            businessDateFrom = "2026-08-08",
            businessDateTo = "2026-08-08",
            businessSessionId = 42L,
        )
        assertNull(
            SalesHistoryLookupPolicy.buildDatabaseQuery(
                criteria = criteria,
                businessDateColumnAvailable = true,
                businessSessionIdColumnAvailable = false,
            ),
        )
        assertFalse(SalesHistoryLookupPolicy.validate(criteria.copy(businessSessionId = 0L)).valid)
    }

    @Test
    fun attributedInMemoryFilterDoesNotMixSameDateDifferentSessions() {
        val sale1 = record(1L, "2026-08-08", 42L)
        val sale2 = record(2L, "2026-08-08", 43L)
        val result = SalesHistoryLookupPolicy.filterBusinessDate(
            listOf(sale1, sale2),
            SalesHistoryCriteria(
                businessDateFrom = "2026-08-08",
                businessDateTo = "2026-08-08",
                businessSessionId = 42L,
            ),
        )
        assertEquals(listOf(1L), result.map { it.summary.id })
    }

    @Test
    fun sourceLocksScr510ContextAndRequiresExplicitUnlock() {
        val root = File("..")
        val lookup = File("src/main/java/jp/co/tenposinfo/register/BusinessDateSalesLookupActivity.kt").readText()
        val navigation = File("src/main/java/jp/co/tenposinfo/register/BusinessDateSalesLookupNavigation.kt").readText()
        val operations = File("src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt").readText()
        val salesPolicy = File("src/main/java/jp/co/tenposinfo/register/SalesHistoryLookup.kt").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        val docs = File(root, "docs/V0.76_BUSINESS_SESSION_SALES_DRILLDOWN.md")
        val notes = File(root, "docs/V0.76_RELEASE_NOTES.md")

        assertTrue(navigation.contains("LocalDate.parse"))
        assertTrue(navigation.contains("businessSessionId"))
        assertTrue(salesPolicy.contains("business_session_id = ?"))
        assertTrue(lookup.contains("businessSessionIdColumnAvailable"))
        assertTrue(lookup.contains("requestedContext"))
        assertTrue(lookup.contains("contextLocked"))
        assertTrue(lookup.contains("固定解除・同日全売上を表示"))
        assertTrue(lookup.contains("enabled = !contextLocked"))
        assertTrue(operations.contains("この営業セッションの売上明細"))
        assertTrue(operations.contains("BusinessDateSalesLookupNavigation.intent"))
        assertTrue(operations.contains("RegisterPermission.VIEW_SALES"))
        assertFalse(lookup.contains("BusinessSessionSchema.ensure"))
        assertFalse(lookup.contains("UPDATE sales"))
        assertFalse(lookup.contains("ALTER TABLE"))

        assertTrue(build.contains("versionCode = 107"))
        assertTrue(build.contains("versionName = \"0.77.0-dev.1\""))
        assertTrue(workflow.contains("V076BusinessSessionSalesDrilldownTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_v0.77.0_dev1_settlement_history_sales_drilldown_debug.apk"))
        assertTrue(docs.isFile)
        assertTrue(notes.isFile)
    }

    private fun record(id: Long, businessDate: String, sessionId: Long) = BusinessDateSaleRecord(
        summary = SaleSummaryRecord(
            id = id,
            operatorName = "担当",
            paymentLabel = "現金",
            totalAmount = 1_000L,
            taxAmount = 90L,
            changeAmount = 0L,
            createdAt = 1L,
            printCount = 1,
        ),
        businessDate = businessDate,
        businessSessionId = sessionId,
    )
}
