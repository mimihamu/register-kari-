package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V140SalesUsabilityTest {
    private val root = File("src/main/java/jp/co/tenposinfo/register")
    private fun source(name: String) = File(root, name).readText()

    @Test fun salesKeypadGetsLargerReservedWidth() {
        val samples = listOf(
            RegisterResponsiveLayoutPolicy.metrics(800, 480),
            RegisterResponsiveLayoutPolicy.metrics(1280, 800),
            RegisterResponsiveLayoutPolicy.metrics(1920, 1080)
        )
        samples.forEach {
            assertTrue(it.salesKeypadWeight >= 0.36f)
            assertEquals(1f, it.salesListWeight + it.salesKeypadWeight + it.salesProductsWeight, 0.001f)
        }
        for (height in listOf(270, 420, 600, 850)) {
            val k = RegisterResponsiveLayoutPolicy.keypadMetrics(
                availableHeightDp = height, functionRows = 2, preferredMinKeyDp = 72)
            assertTrue(k.keyHeightDp >= 72)
            assertTrue(k.keyHeightDp <= RegisterResponsiveLayoutPolicy.MAX_KEY_HEIGHT_DP)
        }
        assertTrue(RegisterLayoutPolicy.COMPACT_KEY_HEIGHT_DP >= 56)
        assertTrue(RegisterResponsiveLayoutPolicy.keypadMetrics(
            availableHeightDp = 270, functionRows = 2, preferredMinKeyDp = 72).scrollRequired)
        assertEquals(48, RegisterResponsiveLayoutPolicy.keypadMetrics(
            availableHeightDp = 270, functionRows = 2).keyHeightDp)
    }

    @Test fun printerErrorDialogReplacesLayoutShiftingBanner() {
        val main = source("MainActivity.kt")
        val sales = main.substring(main.indexOf("private fun SalesScreen("), main.indexOf("private fun LineEditScreen("))
        assertTrue(sales.contains("AlertDialog("))
        assertTrue(sales.contains("プリンターエラー"))
        assertTrue(sales.contains("onPrinterStatus()"))
        assertTrue(sales.contains("dismissedPrinterError"))
        assertFalse(sales.contains("PrinterHealthBanner(printerHealth, onPrinterStatus)"))
        assertTrue(sales.contains("preferredMinKeyDp = 72"))
        val payment = main.substring(main.indexOf("private fun PaymentScreen("), main.indexOf("private fun CompleteScreen("))
        assertTrue(payment.contains("preferredMinKeyDp = 72"))
        assertTrue(sales.contains("buttonHeightDp = keypad.keyHeightDp"))
    }

    @Test fun managementHubDirectlyExposesAllDailyOperations() {
        val hub = source("OperationsHubActivityV030.kt")
        val operations = source("OperationsActivity.kt")
        val route = source("OperationsLegacyRouteV140.kt")
        assertTrue(hub.contains("\"当日売上\""))
        assertTrue(hub.contains("\"入出金\""))
        assertTrue(hub.contains("\"返品・取消\""))
        assertFalse(hub.contains("private fun HubLegacyPanelV030("))
        assertTrue(hub.contains("OperationsLegacyRouteV140.intent(this, destination)"))
        assertTrue(hub.contains("HubHeaderV030(metrics, openSettings)"))
        assertTrue(hub.contains("Intent(this, AdminSettingsActivity::class.java)"))
        assertTrue(hub.contains("RegisterPermission.BUSINESS_START in permissions"))
        assertTrue(hub.contains("openBusiness,"))
        assertTrue(operations.contains("OperationsLegacyRouteV140.requiredPermission"))
        assertTrue(operations.contains("returnToHubOrMenu()"))
        assertTrue(route.contains("OperationsActivity::class.java"))
        assertEquals(RegisterPermission.VIEW_SALES, OperationsLegacyRouteV140.requiredPermission(OperationsLegacyRouteV140.DAILY_SALES))
        assertEquals(RegisterPermission.CASH_MOVEMENT, OperationsLegacyRouteV140.requiredPermission(OperationsLegacyRouteV140.CASH_MOVEMENT))
        assertEquals(RegisterPermission.REVERSAL, OperationsLegacyRouteV140.requiredPermission(OperationsLegacyRouteV140.REVERSAL))
        assertNull(OperationsLegacyRouteV140.requiredPermission("unknown"))
    }

    @Test fun crashReportIsAccessibleInAuthenticatedSettingsNotFloatingOnSales() {
        val crash = source("CrashReportV138.kt")
        val settings = source("AdminSettingsActivity.kt")
        assertTrue(crash.contains("Thread.setDefaultUncaughtExceptionHandler"))
        assertTrue(crash.contains("fun sharePending(activity: Activity): Boolean"))
        assertTrue(crash.contains("Intent.ACTION_SEND"))
        assertFalse(crash.contains("content.addView("))
        assertFalse(crash.contains("Gravity.END or Gravity.BOTTOM"))
        assertTrue(settings.contains("CrashReportRuntimeV138.hasPending(context)"))
        assertTrue(settings.contains("CrashReportUiV138.sharePending(activity)"))
    }
}
