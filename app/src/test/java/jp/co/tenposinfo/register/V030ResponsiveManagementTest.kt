package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V030ResponsiveManagementTest {
    private fun source(name: String) = File("src/main/java/jp/co/tenposinfo/register/$name").readText()

    @Test
    fun managementScreensUseSharedResponsiveMetricsAndScrollFallback() {
        val files = listOf(
            "BusinessStartActivityV030.kt",
            "OperationsHubActivityV030.kt",
            "SettlementActivityV030.kt",
            "SettlementHistoryActivityV030.kt",
        )

        files.forEach { name ->
            val text = source(name)
            assertTrue("$name must use shared responsive metrics", text.contains("rememberRegisterResponsiveMetrics()"))
            assertTrue("$name must react to constraints", text.contains("BoxWithConstraints"))
            assertTrue("$name must expose compact scroll fallback", text.contains("verticalScroll"))
            assertTrue("$name must use the Tsuguregi navy header", text.contains("0xFF173F6B"))
            assertTrue("$name must retain 48dp reachable operations", text.contains("min = 48.dp") || text.contains("MIN_TOUCH_DP"))
        }
    }

    @Test
    fun registerManagementEntryRoutesToResponsiveHub() {
        val application = source("RegisterApplication.kt")

        assertTrue(application.contains("OperationsHubActivityV030::class.java"))
        assertTrue(application.contains("BusinessStartActivityV030::class.java"))
        assertTrue(application.contains("SettlementActivityV030"))
        assertTrue(application.contains("SettlementHistoryActivityV030"))
        assertFalse(application.contains("RegisterPermission.SETTLEMENT"))
    }

    @Test
    fun settlementWritePathStillUsesSecureCoordinator() {
        val settlement = source("SettlementActivityV030.kt")
        val business = source("BusinessStartActivityV030.kt")
        val history = source("SettlementHistoryActivityV030.kt")

        assertTrue(settlement.contains("SecureOperationsCoordinator"))
        assertTrue(settlement.contains("secureStore.recordSettlement"))
        assertTrue(business.contains("secureStore.startBusinessDay"))
        assertTrue(history.contains("secureStore.reprintSettlement"))
    }

    @Test
    fun responsiveHubKeepsLegacyManagementFunctionsReachable() {
        val hub = source("OperationsHubActivityV030.kt")

        assertTrue(hub.contains("その他の管理機能"))
        assertTrue(hub.contains("OperationsActivity::class.java"))
        assertTrue(hub.contains("当日売上・入出金・返品取消"))
    }
}
