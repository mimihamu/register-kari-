package jp.co.tenposinfo.register

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V077SettlementHistorySalesDrilldownTest {
    @Test
    fun settlementHistoryDrilldownUsesExactSavedSessionAndRechecksPermission() {
        val root = File("..")
        val screen = File("src/main/java/jp/co/tenposinfo/register/SettlementHistoryScreenV027.kt").readText()
        val operations = File("src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt").readText()
        val navigation = File("src/main/java/jp/co/tenposinfo/register/BusinessDateSalesLookupNavigation.kt").readText()
        val salesPolicy = File("src/main/java/jp/co/tenposinfo/register/SalesHistoryLookup.kt").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        val docs = File(root, "docs/V0.77_SETTLEMENT_HISTORY_SALES_DRILLDOWN.md")
        val notes = File(root, "docs/V0.77_RELEASE_NOTES.md")

        assertTrue(screen.contains("onOpenSalesDetail: (SettlementRecord) -> Unit"))
        assertTrue(screen.contains("この営業セッションの売上明細"))
        assertTrue(screen.contains("RegisterPermission.VIEW_SALES in permissions"))
        assertTrue(screen.contains("selected.businessSessionId > 0L"))
        assertTrue(operations.contains("onOpenSalesDetail = { record ->"))
        assertTrue(operations.contains("OperatorSessionRegistry.current(appContext)"))
        assertTrue(operations.contains("current?.allows(RegisterPermission.VIEW_SALES) == true"))
        assertTrue(operations.contains("record.businessDate"))
        assertTrue(operations.contains("record.businessSessionId"))
        assertTrue(operations.contains("BusinessDateSalesLookupNavigation.intent"))
        assertTrue(navigation.contains("LocalDate.parse"))
        assertTrue(navigation.contains("if (sessionId <= 0L) return null"))
        assertTrue(salesPolicy.contains("business_session_id = ?"))
        assertFalse(screen.contains("UPDATE sales"))
        assertFalse(screen.contains("DELETE FROM sales"))

        assertTrue(build.contains("versionCode = 107"))
        assertTrue(build.contains("versionName = \"0.77.0-dev.1\""))
        assertTrue(workflow.contains("V077SettlementHistorySalesDrilldownTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_v0.77.0_dev1_settlement_history_sales_drilldown_debug.apk"))
        assertTrue(docs.isFile)
        assertTrue(notes.isFile)
    }
}
