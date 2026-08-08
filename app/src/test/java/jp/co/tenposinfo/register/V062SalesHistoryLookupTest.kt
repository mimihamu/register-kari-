package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V062SalesHistoryLookupTest {
    private fun sale(
        id: Long,
        operator: String,
        payment: String,
        amount: Long,
    ) = SaleSummaryRecord(
        id = id,
        operatorName = operator,
        paymentLabel = payment,
        totalAmount = amount,
        taxAmount = 0L,
        changeAmount = 0L,
        createdAt = id,
        printCount = 0,
    )

    @Test
    fun filtersBySaleOperatorPaymentAndAmountRange() {
        val sales = listOf(
            sale(123, "山田", "現金", 4_000),
            sale(124, "佐藤", "カード", 8_000),
            sale(125, "山田", "カード", 12_000),
        )
        assertEquals(listOf(123L), SalesHistoryLookupPolicy.filter(sales, SalesHistoryCriteria(query = "#123")).map { it.id })
        assertEquals(listOf(123L, 125L), SalesHistoryLookupPolicy.filter(sales, SalesHistoryCriteria(query = "山田")).map { it.id })
        assertEquals(listOf(124L, 125L), SalesHistoryLookupPolicy.filter(sales, SalesHistoryCriteria(query = "カード")).map { it.id })
        assertEquals(listOf(124L), SalesHistoryLookupPolicy.filter(sales, SalesHistoryCriteria(minAmount = 5_000, maxAmount = 10_000)).map { it.id })
        assertTrue(SalesHistoryLookupPolicy.filter(sales, SalesHistoryCriteria(minAmount = 20_000, maxAmount = 10_000)).isEmpty())
    }

    @Test
    fun parsesDirectSaleNumberSafely() {
        assertEquals(123L, SalesHistoryLookupPolicy.parseDirectSaleId("#123"))
        assertEquals(456L, SalesHistoryLookupPolicy.parseDirectSaleId(" 456 "))
        assertNull(SalesHistoryLookupPolicy.parseDirectSaleId("0"))
        assertNull(SalesHistoryLookupPolicy.parseDirectSaleId("12A"))
        assertNull(SalesHistoryLookupPolicy.parseDirectSaleId(""))
    }

    @Test
    fun requestedOldSaleIsAddedOnceAheadOfRecentWindow() {
        val recent = listOf(sale(300, "A", "現金", 1_000), sale(299, "B", "現金", 1_000))
        val old = sale(10, "C", "カード", 2_000)
        assertEquals(listOf(10L, 300L, 299L), SalesHistoryLookupPolicy.includeRequestedSale(recent, old).map { it.id })
        assertEquals(listOf(300L, 299L), SalesHistoryLookupPolicy.includeRequestedSale(recent, recent.first()).map { it.id })
    }

    @Test
    fun sourceConnectsDirectLookupAndOldReceiptVoucherSaleResolution() {
        val root = File("..")
        val main = File("src/main/java/jp/co/tenposinfo/register/MainActivity.kt").readText()
        val voucher = File("src/main/java/jp/co/tenposinfo/register/ReceiptVoucherActivity.kt").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()

        assertTrue(main.contains("database.listSales(SalesHistoryLookupPolicy.RECENT_LOAD_LIMIT)"))
        assertTrue(main.contains("val detail = database.loadSaleDetail(saleId)"))
        assertTrue(main.contains("売上No.直接表示"))
        assertTrue(main.contains("金額以上"))
        assertTrue(main.contains("条件に一致する売上はありません"))
        assertTrue(voucher.contains("database.loadSaleDetail(it)?.summary"))
        assertTrue(voucher.contains("SalesHistoryLookupPolicy.includeRequestedSale"))
        assertTrue(workflow.contains("V062SalesHistoryLookupTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_v0.66.0_dev1_business_date_database_search_debug.apk"))
    }
}
