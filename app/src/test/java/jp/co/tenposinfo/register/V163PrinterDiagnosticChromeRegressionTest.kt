package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Avoid full-width white strips in printer diagnostics while preserving critical warnings. */
class V163PrinterDiagnosticChromeRegressionTest {
    private val dir = File("src/main/java/jp/co/tenposinfo/register")
    private fun source(name: String): String = File(dir, name).readText()

    @Test fun remainingSevenPrinterScreensUseUnifiedSecondaryStrip() {
        val screens = listOf(
            "PrinterNotificationSettingsActivity.kt",
            "PrinterSoakTestActivity.kt",
            "PrinterSoakTestHistoryActivity.kt",
            "PrinterStatusAnalysisActivity.kt",
            "PrinterStatusLabActivity.kt",
            "PrinterStatusProbeActivity.kt",
            "PrinterStatusValidationActivity.kt",
        )
        for (name in screens) {
            val src = source(name)
            assertTrue("$name has secondary strip", src.contains(".background(RegisterSecondaryActionBarColorV159)"))
            assertFalse("$name must have no white background strip", src.contains(".background(Color.White)"))
            assertTrue("$name has unified header", src.contains("RegisterTopBarV141("))
            assertTrue("$name has system safe area", src.contains("RegisterSafeAreaV141 {"))
        }
    }

    @Test fun printerWarningsAndActionsAreNotRemovedForCosmeticChange() {
        assertTrue(source("PrinterSoakTestActivity.kt").contains("最新結果をCSV保存"))
        assertTrue(source("PrinterSoakTestActivity.kt").contains("印字・カット・紙詰まり・重複の有無は必ず目視確認"))
        assertTrue(source("PrinterStatusAnalysisActivity.kt").contains("候補は自動判定や正式プロトコルではありません"))
        assertTrue(source("PrinterSoakTestHistoryActivity.kt").contains("送信結果不明の試験は、用紙を確認"))
        assertTrue(source("PrinterStatusProbeActivity.kt").contains("結果をCSV保存"))
        assertTrue(source("PrinterStatusLabActivity.kt").contains("STAR／汎用の互換性確認完了を意味しません"))
        assertTrue(source("PrinterStatusValidationActivity.kt").contains("保存済みデータの閲覧・CSV・候補審査専用"))
        assertTrue(source("PrinterNotificationSettingsActivity.kt").contains("ロール紙と排紙口を確認"))
    }

    @Test fun sharedBackButtonIsTransparentWhiteOutlineAndLegibleOnDarkAndRed() {
        val s = source("RegisterTopBarV141.kt")
        assertTrue(s.contains("border = BorderStroke(1.dp, Color.White)"))
        assertTrue(s.contains("containerColor = Color.Transparent"))
        assertTrue(s.contains("contentColor = Color.White"))
        assertFalse(s.contains("containerColor = Color.White"))
        assertTrue(s.contains("backgroundColor: Color = RegisterTopBarColorV141"))
        assertTrue(s.contains(".background(backgroundColor)"))
        assertTrue(s.contains("WindowInsets.safeDrawing"))
    }
}
