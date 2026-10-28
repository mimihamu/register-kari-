package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** All supplementary POS activities must preserve the Android 15+ system icon area. */
class V142SystemBarSafeAreaTest {
    private val root = File("src/main/java/jp/co/tenposinfo/register")
    private fun source(fileName: String): String = File(root, fileName).readText()

    @Test fun secondaryComposeActivitiesUseConsistentSafeAreas() {
        val secondaryActivities = listOf(
            "PrinterStatusActivity.kt",
            "PrinterToolsHubActivity.kt",
            "CustomerDisplaySettingsActivity.kt",
            "DataProtectionActivity.kt",
            "ReceiptVoucherLedgerActivity.kt",
            "PrinterSoakTestActivity.kt",
            "PrinterNotificationSettingsActivity.kt",
            "PrinterStatusAnalysisActivity.kt",
            "PrinterStatusLabActivity.kt",
            "PrinterStatusProbeActivity.kt",
            "PrinterStatusValidationActivity.kt",
            "PrinterSoakTestHistoryActivity.kt",
        )
        for (name in secondaryActivities) {
            val src = source(name)
            assertTrue("$name sets consistent system bar colors", src.contains("configureRegisterSystemBars(window)"))
            assertTrue("$name has a safe system inset content wrapper", src.contains("RegisterSafeAreaV141 {"))
            assertTrue("$name is still a Compose activity", src.contains("setContent {"))
        }
    }

    @Test fun registerMinusUsesRedStatusBarAndSafeComposeInsets() {
        val src = source("ManualReturnActivityV135.kt")
        assertTrue(src.contains("configureRegisterSystemBars(window)"))
        assertTrue(src.contains("window.statusBarColor = android.graphics.Color.rgb(180, 35, 24)"))
        assertTrue(src.contains("RegisterSafeAreaV141(topBarColor = Color(0xFFB42318))"))
        assertTrue(src.contains("coordinator.create(request, managerPin)"))
    }

    @Test fun sharedSafeAreaMaintainsHeaderColorAndSystemPadding() {
        val src = source("RegisterTopBarV141.kt")
        assertTrue(src.contains("WindowInsets.statusBars"))
        assertTrue(src.contains("WindowInsets.safeDrawing"))
        assertTrue(src.contains("RegisterTopBarColorV141"))
        assertTrue(src.contains("TextOverflow.Ellipsis"))
    }
}
