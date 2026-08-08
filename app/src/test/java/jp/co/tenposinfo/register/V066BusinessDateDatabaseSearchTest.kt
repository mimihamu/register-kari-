package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V066BusinessDateDatabaseSearchTest {
    @Test
    fun databaseQueryBindsValuesAndCombinesAllCriteria() {
        val spec = SalesHistoryLookupPolicy.buildDatabaseQuery(
            SalesHistoryCriteria(
                query = "山田%_",
                minAmount = 5_000,
                maxAmount = 10_000,
                businessDateFrom = "2026-07-01",
                businessDateTo = "2026-07-31",
            ),
            businessDateColumnAvailable = true,
        )
        requireNotNull(spec)

        assertTrue(spec.whereSql.contains("CAST(id AS TEXT) LIKE ?"))
        assertTrue(spec.whereSql.contains("LOWER(operator_name) LIKE ?"))
        assertTrue(spec.whereSql.contains("LOWER(payment_method) LIKE ?"))
        assertTrue(spec.whereSql.contains("LOWER(business_date) LIKE ?"))
        assertTrue(spec.whereSql.contains("total_amount >= ?"))
        assertTrue(spec.whereSql.contains("total_amount <= ?"))
        assertTrue(spec.whereSql.contains("business_date >= ?"))
        assertTrue(spec.whereSql.contains("business_date <= ?"))
        assertFalse(spec.whereSql.contains("山田"))
        assertEquals("%山田\\%\\_%", spec.args.first())
        assertEquals("2026-07-01", spec.args[spec.args.size - 2])
        assertEquals("2026-07-31", spec.args.last())
    }

    @Test
    fun oldDatabaseWithoutBusinessDateFailsClosedOnlyForDateRange() {
        assertNull(
            SalesHistoryLookupPolicy.buildDatabaseQuery(
                SalesHistoryCriteria(businessDateFrom = "2026-07-01"),
                businessDateColumnAvailable = false,
            ),
        )

        val noDate = SalesHistoryLookupPolicy.buildDatabaseQuery(
            SalesHistoryCriteria(query = "現金"),
            businessDateColumnAvailable = false,
        )
        requireNotNull(noDate)
        assertFalse(noDate.whereSql.contains("business_date"))
        assertEquals(3, noDate.args.size)
    }

    @Test
    fun pagingBoundaryIsStable() {
        assertEquals(200, SalesHistoryLookupPolicy.DATABASE_PAGE_SIZE)
        val page = BusinessDateSalesQueryPage(
            records = emptyList(),
            offset = 400,
            pageSize = SalesHistoryLookupPolicy.DATABASE_PAGE_SIZE,
            hasNext = true,
        )
        assertEquals(400, page.offset)
        assertEquals(200, page.pageSize)
        assertTrue(page.hasNext)
    }

    @Test
    fun sourceUsesDirectBoundDatabaseSearchAndKeepsSalesReadOnly() {
        val root = File("..")
        val activity = File("src/main/java/jp/co/tenposinfo/register/BusinessDateSalesLookupActivity.kt").readText()
        val policy = File("src/main/java/jp/co/tenposinfo/register/SalesHistoryLookup.kt").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        val docs = File(root, "docs/V0.66_BUSINESS_DATE_DATABASE_SEARCH.md")
        val notes = File(root, "docs/V0.66_RELEASE_NOTES.md")

        assertTrue(activity.contains("store.search(appliedCriteria, pageOffset)"))
        assertTrue(activity.contains("LIMIT ? OFFSET ?"))
        assertTrue(activity.contains("safePageSize + 1"))
        assertTrue(activity.contains("selectionArgs.toTypedArray()"))
        assertTrue(activity.contains("SQLite直接検索"))
        assertTrue(activity.contains("前へ"))
        assertTrue(activity.contains("次へ"))
        assertFalse(activity.contains("listRecent("))
        assertFalse(activity.contains("SalesHistoryLookupPolicy.RECENT_LOAD_LIMIT"))
        assertFalse(activity.contains("UPDATE sales"))
        assertFalse(activity.contains("ALTER TABLE"))
        assertTrue(activity.contains("BusinessDateLookupAmountRow"))
        assertTrue(policy.contains("buildDatabaseQuery"))
        assertTrue(policy.contains("escapeLike"))
        assertTrue(policy.contains("ESCAPE"))
        assertTrue(build.contains("versionCode = 96"))
        assertTrue(build.contains("versionName = \"0.66.0-dev.1\""))
        assertTrue(workflow.contains("V066BusinessDateDatabaseSearchTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_v0.66.0_dev1_business_date_database_search_debug.apk"))
        assertTrue(docs.isFile)
        assertTrue(notes.isFile)
    }
}
