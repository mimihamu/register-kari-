package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class V141RegisterUiConsistencyTest {
    private val root = File("src/main/java/jp/co/tenposinfo/register")
    private fun source(name: String) = File(root, name).readText()

    @Test fun operatorAndActivePaymentKeypadsUseResponsiveSizing() {
        val main = source("MainActivity.kt")
        val login = main.substring(main.indexOf("private fun LoginScreen("), main.indexOf("internal fun SalesScreen("))
        val payment = source("PaymentScreenV135.kt")
        assertTrue(login.contains("preferredMinKeyDp = 72"))
        assertTrue(login.contains("buttonHeightDp = keypad.keyHeightDp"))
        assertTrue(login.contains("keypad.scrollRequired"))
        assertTrue(payment.contains("preferredMinKeyDp = 72"))
        assertTrue(payment.contains("keyHeightDp = keypad.keyHeightDp"))
        assertTrue(payment.contains("keypad.scrollRequired"))
        assertFalse(payment.contains(".height(39.dp)"))
    }

    @Test fun commonTitleBarUsesSystemInsetAndNoOverlappingTitle() {
        val chrome = source("RegisterTopBarV141.kt")
        assertTrue(chrome.contains("WindowInsets.safeDrawing"))
        assertTrue(chrome.contains("WindowInsets.statusBars"))
        assertTrue(chrome.contains("0xFF173F6B"))
        assertTrue(chrome.contains("Modifier.weight(1f)"))
        assertTrue(chrome.contains("TextOverflow.Ellipsis"))
        for (name in listOf(
            "MainActivity.kt", "OperationsHubActivityV030.kt",
            "BusinessStartActivityV030.kt", "AdminSettingsActivity.kt",
            "OperationsActivity.kt"
        )) {
            assertTrue(name, source(name).contains("RegisterSafeAreaV141 {"))
            assertTrue(name, source(name).contains("RegisterTopBarV141("))
        }
        assertTrue(source("PaymentScreenV135.kt").contains("RegisterTopBarV141("))
    }

    @Test fun keypadMetricsKeepSeventyTwoDpAndScrollSmallScreens() {
        val available = listOf(260, 340, 520, 800)
        available.forEach { height ->
            val login = RegisterResponsiveLayoutPolicy.keypadMetrics(height, 0, 104, 72)
            val payment = RegisterResponsiveLayoutPolicy.keypadMetrics(height, 2, 134, 72)
            assertTrue(login.keyHeightDp >= 72)
            assertTrue(payment.keyHeightDp >= 72)
            assertTrue(login.keyHeightDp <= 96)
            assertTrue(payment.keyHeightDp <= 96)
        }
        assertTrue(RegisterResponsiveLayoutPolicy.keypadMetrics(260, 0, 104, 72).scrollRequired)
        assertTrue(RegisterResponsiveLayoutPolicy.keypadMetrics(340, 2, 134, 72).scrollRequired)
    }
}
