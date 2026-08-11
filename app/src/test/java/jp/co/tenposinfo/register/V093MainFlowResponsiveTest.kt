package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V093MainFlowResponsiveTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    private val mainSource: String by lazy {
        File(root, "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt").readText()
    }

    private fun functionSource(name: String, nextName: String): String {
        val start = mainSource.indexOf("private fun $name(")
        val end = mainSource.indexOf("private fun $nextName(", start + 1)
        assertTrue("$name source not found", start >= 0)
        assertTrue("$nextName source not found", end > start)
        return mainSource.substring(start, end)
    }

    @Test
    fun compactPolicyCovers800x480AndLargeFont() {
        assertEquals(
            RegisterWindowClass.COMPACT,
            RegisterResponsiveLayoutPolicy.classify(widthDp = 800, heightDp = 480, fontScale = 1f),
        )
        assertEquals(
            RegisterWindowClass.COMPACT,
            RegisterResponsiveLayoutPolicy.classify(widthDp = 1280, heightDp = 720, fontScale = 1.30f),
        )
        assertEquals(
            RegisterWindowClass.MEDIUM,
            RegisterResponsiveLayoutPolicy.classify(widthDp = 1280, heightDp = 720, fontScale = 1f),
        )
    }

    @Test
    fun loginUsesIndependentScrollAndSharedCompactKeypadMetrics() {
        val source = functionSource("LoginScreen", "SalesScreen")

        assertTrue(source.contains("val responsive = rememberRegisterResponsiveMetrics()"))
        assertTrue(source.contains("val operatorScroll = rememberScrollState()"))
        assertTrue(source.contains("val pinScroll = rememberScrollState()"))
        assertTrue(source.contains(".verticalScroll(operatorScroll)"))
        assertTrue(source.contains("if (responsive.isCompact) Modifier.verticalScroll(pinScroll)"))
        assertTrue(source.contains("val pinPanelWidthDp = if (responsive.isCompact) 260 else 390"))
        assertTrue(source.contains("val operatorButtonHeightDp = if (responsive.isCompact) 58 else 82"))
        assertTrue(source.contains("RegisterLayoutPolicy.COMPACT_VALUE_HEIGHT_DP"))
        assertTrue(source.contains("RegisterLayoutPolicy.COMPACT_KEY_HEIGHT_DP"))
        assertTrue(source.contains("RegisterLayoutPolicy.COMPACT_KEY_GAP_DP"))
        assertTrue(source.contains("bottomActionLabel = \"ログイン\""))
        assertTrue(source.contains("selectedId?.let { onLogin(it, pin) }"))
    }

    @Test
    fun completeUsesCompactTwoByTwoActionsAndLargeNextButton() {
        val source = functionSource("CompleteScreen", "SalesHistoryScreen")

        assertTrue(source.contains("val responsive = rememberRegisterResponsiveMetrics()"))
        assertTrue(source.contains("val compactScroll = rememberScrollState()"))
        assertTrue(source.contains("if (responsive.isCompact) Modifier.verticalScroll(compactScroll)"))
        assertTrue(source.contains("if (responsive.isCompact) {"))
        assertTrue(source.contains("Text(\"レシート\", maxLines = 1)"))
        assertTrue(source.contains("Text(\"領収書\", maxLines = 1)"))
        assertTrue(source.contains("Text(\"印刷キュー\", maxLines = 1)"))
        assertTrue(source.contains("Text(\"売上一覧\", maxLines = 1)"))
        assertTrue(source.contains("Modifier.weight(1f).height(48.dp)"))
        assertTrue(source.contains("Modifier.fillMaxWidth().height(54.dp)"))
    }

    @Test
    fun normalCompleteActionRowAndBusinessCallbacksArePreserved() {
        val complete = functionSource("CompleteScreen", "SalesHistoryScreen")
        val register = mainSource.substring(
            mainSource.indexOf("AppScreen.COMPLETE -> CompleteScreen("),
            mainSource.indexOf("AppScreen.SALES_HISTORY -> SalesHistoryScreen("),
        )

        assertTrue(complete.contains("Text(\"レシート確認\")"))
        assertTrue(complete.contains("Text(\"領収書発行\")"))
        assertTrue(complete.contains("Text(\"統合印刷キュー\")"))
        assertTrue(complete.contains("Text(\"売上一覧\")"))
        assertTrue(complete.contains("BlueButton(\"次の取引\", onNext, Modifier.width(180.dp).height(54.dp))"))
        assertTrue(register.contains("SaleReceiptNavigation.intent(context, saleId)"))
        assertTrue(register.contains("ReceiptVoucherNavigation.issuanceIntent(context, lastSaleId)"))
        assertTrue(register.contains("onHistory = { screen = AppScreen.SALES_HISTORY }"))
        assertTrue(register.contains("onQueue = { openUnifiedPrintQueue() }"))
        assertTrue(register.contains("onNext = { screen = AppScreen.SALES }"))
    }

    @Test
    fun responsiveChangeDoesNotIntroduceBusinessDataMutation() {
        val login = functionSource("LoginScreen", "SalesScreen")
        val complete = functionSource("CompleteScreen", "SalesHistoryScreen")
        val source = login + complete

        assertFalse(source.contains("DELETE FROM", ignoreCase = true))
        assertFalse(source.contains("UPDATE sales", ignoreCase = true))
        assertFalse(source.contains("DROP TABLE", ignoreCase = true))
        assertFalse(source.contains("writableDatabase", ignoreCase = true))
        assertFalse(source.contains("TaxEngine"))
        assertFalse(source.contains("saveSale("))
    }

    @Test
    fun releaseNotesKeepRealDeviceVerificationDeferred() {
        val notes = File(root, "docs/V0.93_RELEASE_NOTES.md")
        val requirements = File(root, "docs/V0.93_MAIN_FLOW_RESPONSIVE.md")
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
