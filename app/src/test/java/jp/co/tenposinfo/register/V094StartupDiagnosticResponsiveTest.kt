package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V094StartupDiagnosticResponsiveTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    private val mainSource: String by lazy {
        File(root, "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt").readText()
    }

    private fun diagnosticSource(): String {
        val start = mainSource.indexOf("private fun DiagnosticScreen(")
        val end = mainSource.indexOf("private fun StatusRow(", start + 1)
        assertTrue("DiagnosticScreen source not found", start >= 0)
        assertTrue("StatusRow source not found", end > start)
        return mainSource.substring(start, end)
    }

    @Test
    fun diagnosticUsesSharedResponsiveMetricsAndScrollFallback() {
        val source = diagnosticSource()

        assertTrue(source.contains("val responsive = rememberRegisterResponsiveMetrics()"))
        assertTrue(source.contains("val diagnosticScroll = rememberScrollState()"))
        assertTrue(source.contains(".verticalScroll(diagnosticScroll)"))
        assertTrue(source.contains("responsive.screenPaddingDp.dp"))
        assertTrue(source.contains("if (responsive.isCompact) Arrangement.Top else Arrangement.Center"))
    }

    @Test
    fun compactDiagnosticRemovesFixedWidthBlockerButNormalLayoutIsPreserved() {
        val source = diagnosticSource()

        assertTrue(source.contains("Modifier.fillMaxWidth().heightIn(min = 250.dp)"))
        assertTrue(source.contains("Modifier.width(700.dp).height(RegisterLayoutPolicy.DIAGNOSTIC_CARD_HEIGHT_DP.dp)"))
        assertTrue(source.contains("Modifier.fillMaxWidth().height(54.dp)"))
        assertTrue(source.contains("Modifier.width(340.dp).height(54.dp)"))
        assertTrue(source.contains("診断完了・担当者選択へ"))
    }

    @Test
    fun startupFlowStillMovesOnlyToLogin() {
        val routeStart = mainSource.indexOf("AppScreen.DIAGNOSTIC -> DiagnosticScreen(")
        val routeEnd = mainSource.indexOf("AppScreen.LOGIN -> LoginScreen(", routeStart + 1)
        assertTrue(routeStart >= 0)
        assertTrue(routeEnd > routeStart)
        val route = mainSource.substring(routeStart, routeEnd)

        assertTrue(route.contains("onComplete = { screen = AppScreen.LOGIN }"))
        assertFalse(route.contains("saveSale("))
        assertFalse(route.contains("DELETE FROM", ignoreCase = true))
        assertFalse(route.contains("DROP TABLE", ignoreCase = true))
    }

    @Test
    fun releaseDocumentationKeepsRealDeviceVerificationDeferred() {
        val notes = File(root, "docs/V0.94_RELEASE_NOTES.md")
        val requirements = File(root, "docs/V0.94_STARTUP_DIAGNOSTIC_RESPONSIVE.md")
        assertTrue(notes.isFile)
        assertTrue(requirements.isFile)
        assertTrue(notes.readText().contains("最終総合実機試験へ繰越"))
        assertTrue(requirements.readText().contains("最終総合実機試験"))

        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        assertTrue(workflow.contains(":app:testDebugUnitTest"))
        assertTrue(workflow.contains(":customer-display:testDebugUnitTest"))
        assertTrue(workflow.contains(":management-app:testDebugUnitTest"))
        assertTrue(workflow.contains("APK_RELEASE_INTEGRITY_GATE=true"))
    }
}
