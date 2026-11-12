package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class V170SystemBarEdgeToEdgeTest {
    private fun pos(n: String) = File("src/main/java/jp/co/tenposinfo/register", n).readText()

    @Test fun allAppFamiliesOwnThePixelsBehindAndroidSystemBars() {
        val paths = listOf(
            "src/main/java/jp/co/tenposinfo/register/RegisterUiChromeV028.kt",
            "../management-app/src/main/java/jp/co/tenposinfo/register/plus/PlusScreenChromeV148.kt",
            "../management-app/src/main/java/jp/co/tenposinfo/register/plus/MainActivity.kt",
            "../customer-display/src/main/java/jp/co/tenposinfo/register/cd/MainActivity.kt",
        )
        for (path in paths) {
            val s = File(path).readText()
            assertTrue(path, s.contains("WindowCompat.setDecorFitsSystemWindows(window, false)"))
            assertTrue(path, s.contains("window.isNavigationBarContrastEnforced = false"))
            assertTrue(path, s.contains("window.isStatusBarContrastEnforced = false"))
            assertTrue(path, s.contains("isAppearanceLightStatusBars = false"))
            assertTrue(path, s.contains("isAppearanceLightNavigationBars = false"))
        }
        val safe = pos("RegisterTopBarV141.kt")
        assertTrue(safe.contains("Modifier.fillMaxSize().background(topBarColor)"))
        assertTrue(safe.contains("WindowInsets.safeDrawing"))
        val plus = File(paths[1]).readText()
        assertTrue(plus.contains("Modifier.fillMaxSize().background(PlusNavyV148)"))
    }

    @Test fun nativeUpdateScreensAlsoRespectInsets() {
        val paths = listOf(
            "src/main/java/jp/co/tenposinfo/register/AppDistributionUpdateActivityV146.kt",
            "../management-app/src/main/java/jp/co/tenposinfo/register/plus/AppDistributionUpdateActivityV146.kt",
            "../customer-display/src/main/java/jp/co/tenposinfo/register/cd/AppDistributionUpdateActivityV146.kt",
        )
        for (path in paths) {
            val src = File(path).readText()
            assertTrue(path, src.contains("WindowCompat.setDecorFitsSystemWindows(window, false)"))
            assertTrue(path, src.contains("ViewCompat.setOnApplyWindowInsetsListener"))
            assertTrue(path, src.contains("WindowInsetsCompat.Type.systemBars()"))
        }
    }
}
