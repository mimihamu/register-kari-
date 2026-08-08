package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V065BusinessDateSalesLookupTest {
    private fun record(
        id: Long,
        businessDate: String?,
        sessionId: Long? = null,
        amount: Long = 4_000,
    ) = BusinessDateSaleRecord(
        summary = SaleSummaryRecord(
            id = id,
            operatorName = if (id % 2L == 0L) "佐藤" else "山田",
            paymentLabel = if (id % 2L == 0L) "カード" else "現金",
            totalAmount = amount,
            taxAmount = 0,
            changeAmount = 0,
            createdAt = id * 100,
            printCount = 0,
        ),
        businessDate = businessDate,
        businessSessionId = sessionId,
    )

    @Test
    fun filtersByPersistedBusinessDateAndNotCreatedAt() {
        val sales = listOf(
            record(1, "2026-08-07", 11),
            record(2, "2026-08-08", 12),
            record(3, null, null),
        )
        val result = SalesHistoryLookupPolicy.filterBusinessDate(
            sales,
            SalesHistoryCriteria(
                businessDateFrom = "2026-08-07",
                businessDateTo = "2026-08-07",
            ),
        )
        assertEquals(listOf(1L), result.map { it.summary.id })
        assertEquals(100L, result.single().summary.createdAt)
    }

    @Test
    fun supportsOpenRangesAndKeepsLegacyNullOnlyWithoutDateCondition() {
        val sales = listOf(
            record(1, "2026-08-06"),
            record(2, "2026-08-07"),
            record(3, "2026-08-08"),
            record(4, null),
        )
        assertEquals(
            listOf(2L, 3L),
            SalesHistoryLookupPolicy.filterBusinessDate(
                sales,
                SalesHistoryCriteria(businessDateFrom = "2026-08-07"),
            ).map { it.summary.id },
        )
        assertEquals(
            listOf(1L, 2L),
            SalesHistoryLookupPolicy.filterBusinessDate(
                sales,
                SalesHistoryCriteria(businessDateTo = "2026-08-07"),
            ).map { it.summary.id },
        )
        assertEquals(4, SalesHistoryLookupPolicy.filterBusinessDate(sales, SalesHistoryCriteria()).size)
    }

    @Test
    fun invalidDateAndReverseRangeFailClosed() {
        val badFormat = SalesHistoryLookupPolicy.validate(
            SalesHistoryCriteria(businessDateFrom = "2026-8-7"),
        )
        assertFalse(badFormat.valid)
        assertTrue(badFormat.message.orEmpty().contains("YYYY-MM-DD"))

        val reversed = SalesHistoryLookupPolicy.validate(
            SalesHistoryCriteria(
                businessDateFrom = "2026-08-08",
                businessDateTo = "2026-08-07",
            ),
        )
        assertFalse(reversed.valid)
        assertTrue(reversed.message.orEmpty().contains("From ≤ To"))
    }

    @Test
    fun combinesBusinessDateWithExistingSearchAndAmounts() {
        val sales = listOf(
            record(1, "2026-08-07", amount = 4_000),
            record(2, "2026-08-07", amount = 8_000),
            record(3, "2026-08-08", amount = 12_000),
        )
        val result = SalesHistoryLookupPolicy.filterBusinessDate(
            sales,
            SalesHistoryCriteria(
                query = "カード",
                minAmount = 5_000,
                maxAmount = 10_000,
                businessDateFrom = "2026-08-07",
                businessDateTo = "2026-08-07",
            ),
        )
        assertEquals(listOf(2L), result.map { it.summary.id })
    }

    @Test
    fun sourceKeepsLookupReadOnlyAndConnectsAuthorizedOperations() {
        val activity = File("src/main/java/jp/co/tenposinfo/register/BusinessDateSalesLookupActivity.kt").readText()
        val hub = File("src/main/java/jp/co/tenposinfo/register/OperationsHubActivityV030.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val workflow = File("../.github/workflows/build-apk.yml").readText()

        assertTrue(activity.contains("SchemaMigration.hasColumn"))
        assertTrue(activity.contains("NULL"))
        assertFalse(activity.contains("BusinessSessionSchema.ensure"))
        assertFalse(activity.contains("UPDATE sales"))
        assertFalse(activity.contains("ALTER TABLE"))
        assertTrue(activity.contains("営業日From"))
        assertTrue(activity.contains("営業日未記録"))
        assertTrue(activity.contains("ReceiptVoucherNavigation.issuanceIntent"))
        assertTrue(activity.contains("ReversalNavigation.intent"))
        assertTrue(activity.contains("current.allows(RegisterPermission.VIEW_SALES)"))
        assertTrue(activity.contains("current.allows(RegisterPermission.REVERSAL)"))
        assertTrue(hub.contains("BusinessDateSalesLookupActivity::class.java"))
        assertTrue(hub.contains("営業日別 売上検索"))
        assertTrue(manifest.contains(".BusinessDateSalesLookupActivity"))
        assertTrue(workflow.contains("V065BusinessDateSalesLookupTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_v0.70.0_dev1_sale_receipt_reprint_database_paging_debug.apk"))
        assertTrue(workflow.contains("TSUGUREGI-v0.70.0-dev1-sale-receipt-reprint-database-paging-apks"))
    }
}
