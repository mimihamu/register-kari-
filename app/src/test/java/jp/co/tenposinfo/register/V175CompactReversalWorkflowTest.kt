package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 800x480 returns must show a full-width editable step, not three crushed panels. */
class V175CompactReversalWorkflowTest {
    private val src = File("src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt").readText()
    private val screen = src.substring(src.indexOf("private fun ReversalScreen("), src.indexOf("private val reversalQuantityInputRegex"))

    @Test fun shortLandscapeUsesStepNavigationAndWideDisplayKeepsAllPanels() {
        assertTrue(ReversalLayoutPolicyV175.useSteps(800))
        assertTrue(ReversalLayoutPolicyV175.useSteps(1024))
        assertTrue(ReversalLayoutPolicyV175.useSteps(1249))
        assertFalse(ReversalLayoutPolicyV175.useSteps(1250))
        assertFalse(ReversalLayoutPolicyV175.useSteps(1920))
        assertTrue(screen.contains("listOf(\"元売上\", \"商品・数量\", \"確定\")"))
        assertTrue(screen.contains("compactStepV175 == 0"))
        assertTrue(screen.contains("compactStepV175 == 1"))
        assertTrue(screen.contains("compactStepV175 == 2"))
        assertTrue(screen.contains("if (lines.isNotEmpty() && compactReversal) compactStepV175 = 1"))
    }

    @Test fun confirmationStaysOutsideScrollAndRetainsAuthorizationAndIdempotency() {
        val scroll = screen.indexOf("Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())")
        val btn = screen.indexOf("onClick = { showReversalConfirm = true }")
        assertTrue(scroll > 0 && btn > scroll)
        assertTrue(screen.contains("Text(\"責任者PIN\")"))
        assertTrue(screen.contains("ManagerApprovalV145.canUseCurrentManagerSession"))
        assertTrue(screen.contains("enabled = canExecute"))
        assertTrue(screen.contains("onExecute(saleId, type, quantityHundredths, reason, pin, requestId)"))
        assertTrue(screen.contains("requestId = UUID.randomUUID().toString()"))
        assertTrue(screen.contains("RegisterModalV154("))
        assertTrue(screen.contains("onManualReturn"))
    }
}
