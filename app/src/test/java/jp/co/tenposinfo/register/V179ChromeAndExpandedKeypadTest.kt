package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.79: no separate white/status stripe and accessible numeric entry on compact tablets. */
class V179ChromeAndExpandedKeypadTest {
    private val root = File("src/main/java/jp/co/tenposinfo/register")
    private fun source(name: String) = File(root, name).readText()

    @Test fun headerGrowsOnlyAtLargeTextScales() {
        assertEquals(56, RegisterTopBarSizingV179.heightDp(56, 1.0f))
        assertEquals(68, RegisterTopBarSizingV179.heightDp(56, 1.2f))
        assertEquals(76, RegisterTopBarSizingV179.heightDp(56, 1.5f))
        assertEquals(80, RegisterTopBarSizingV179.heightDp(80, 1.5f))
        assertTrue(source("RegisterTopBarV141.kt").contains("RegisterTopBarSizingV179.heightDp(metrics.headerHeightDp, fontScale)"))
    }

    @Test fun minusUsesSameDeepRedForStatusNavigationBackgroundAndToolbar() {
        val red = source("RegisterTopBarV141.kt")
        val screen = source("ManualReturnActivityV135.kt")
        val chrome = source("RegisterUiChromeV028.kt")
        assertTrue(red.contains("RegisterMinusTopBarColorV179 = Color(0xFFB42318)"))
        assertTrue(screen.contains("configureRegisterSystemBars(window, android.graphics.Color.rgb(180, 35, 24))"))
        assertTrue(screen.contains("RegisterSafeAreaV141(topBarColor = RegisterMinusTopBarColorV179)"))
        assertTrue(chrome.contains("window.decorView.setBackgroundColor(barColor)"))
        assertTrue(chrome.contains("window.statusBarColor = barColor"))
        assertTrue(chrome.contains("window.navigationBarColor = barColor"))
        assertTrue(chrome.contains("isAppearanceLightStatusBars = false"))
    }

    @Test fun loginSalesAndBothPaymentPathsUseTheOriginalNumericCallbacks() {
        val main = source("MainActivity.kt")
        val uc08 = source("PaymentScreenV135.kt")
        assertTrue(main.contains("drawPinPad(keypad.keyHeightDp, keypad.gapDp)"))
        assertTrue(main.contains("drawSalePad(keypad.keyHeightDp, keypad.gapDp)"))
        assertTrue(main.contains("drawPaymentPad(keypad.keyHeightDp, keypad.gapDp)"))
        assertTrue(main.contains("RegisterExpandedKeypadV179("))
        assertTrue(main.contains("backgroundColor = if (minusMode) RegisterMinusTopBarColorV179 else Navy"))
        assertTrue(uc08.contains("paymentPadOpenV179"))
        assertTrue(uc08.contains("RegisterExpandedKeypadV179("))
        assertTrue(uc08.contains("paymentPadOpenV179 = false"))
        assertTrue(main.contains("salePadOpenV179 = false"))
        assertTrue(main.contains("pinPadOpenV179 = false"))
        assertTrue(uc08.contains("onCash = { add(PaymentMethod.CASH) }"))
    }

    @Test fun expandedPadIsDismissibleAndOnlyUsesExistingNumericCallbacks() {
        val pad = source("RegisterExpandedKeypadV179.kt")
        assertTrue(pad.contains("Dialog("))
        assertTrue(pad.contains("onDismissRequest = onClose"))
        assertTrue(pad.contains("keypad(metrics.keyHeightDp, metrics.gapDp)"))
        assertTrue(pad.contains("preferredMinKeyDp = 72"))
        assertTrue(pad.contains("verticalScroll(rememberScrollState())"))
        assertTrue(!pad.contains("onExecute("))
        assertTrue(!pad.contains("PaymentEngine"))
    }
}
