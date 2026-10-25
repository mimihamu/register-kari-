package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.5 UI regression: short but wide screens keep two working panes.
 * Print/Z-settlement permissions, actions and uncertain-send safety must remain reachable.
 */
class V152LandscapeTwoPaneOperationsTest {
    private val root = File("src/main/java/jp/co/tenposinfo/register")
    private fun read(name: String) = File(root, name).readText()

    @Test fun settlementShowsInputAndSwitchablePreviewHistoryAt800Dp() {
        val code = read("SettlementActivityV030.kt")
        assertTrue(code.contains("val compact = maxWidth < 700.dp"))
        assertTrue(code.contains("val twoPane = maxWidth < 1_080.dp"))
        assertTrue(code.contains("} else if (twoPane) {"))
        assertFalse(code.contains("maxHeight < 560.dp"))
        assertTrue(code.contains("var rightPanelV152 by remember"))
        assertTrue(code.contains("rightPanelV152 = 1"))
        assertTrue(code.contains("SettlementHistoryPanelV030(Modifier.weight(1f).fillMaxWidth()"))
        assertTrue(code.contains("SettlementPreviewPanelV030("))
        assertTrue(code.contains("scrollPanel = true"))
        assertTrue(code.contains("showConfirmation = true"))
        assertTrue(code.contains("SettlementPdfExportPolicyV135.fileName"))
    }

    @Test fun historyRemainsTwoPaneWithExplicitPrintablePreviewAndPermissionChecks() {
        val code = read("SettlementHistoryActivityV030.kt")
        assertTrue(code.contains("val compact = maxWidth < 700.dp"))
        assertTrue(code.contains("val twoPane = maxWidth < 1_100.dp"))
        assertTrue(code.contains("} else if (twoPane) {"))
        assertFalse(code.contains("maxHeight < 560.dp"))
        assertTrue(code.contains("detailPaneV152 = 1"))
        assertTrue(code.contains("HistoryDetailV030("))
        assertTrue(code.contains("HistoryPreviewV030("))
        assertTrue(code.contains("scrollPanel: Boolean = false"))
        assertTrue(code.contains("SettlementHistoryPolicyV027.canReprint("))
        assertTrue(code.contains("ManagerApprovalV145.canUseCurrentManagerSession"))
    }

    @Test fun printQueueKeepsJobListDetailAndPrinterStatusWithoutLongVerticalStack() {
        val code = read("UnifiedPrintQueueActivity.kt")
        assertTrue(code.contains("val stacked = maxWidth < 700.dp"))
        assertTrue(code.contains("val twoPane = maxWidth < 1_180.dp"))
        assertTrue(code.contains("} else if (twoPane) {"))
        assertFalse(code.contains("maxHeight < 620.dp"))
        assertTrue(code.contains("queuePanelV152 = 0"))
        assertTrue(code.contains("queuePanelV152 = 1"))
        assertTrue(code.contains("scrollPanel = true"))
        assertTrue(code.contains("JobListPanel("))
        assertTrue(code.contains("JobDetailPanel("))
        assertTrue(code.contains("PrinterSummaryPanel("))
        assertTrue(code.contains("PrintJobUncertainPolicyV136.isUncertain"))
        assertTrue(code.contains("onReprintUncertain = ::executeReprintUncertain"))
        assertTrue(code.contains("if (!working) onClose()"))
    }

    @Test fun selectedTabsHaveVisualFocusAndDetailPreviewIsBounded() {
        for (screen in listOf("SettlementActivityV030.kt", "SettlementHistoryActivityV030.kt", "UnifiedPrintQueueActivity.kt")) {
            val code = read(screen)
            assertTrue(screen, code.contains("border = BorderStroke(if ("))
            assertTrue(screen, code.contains("Modifier.verticalScroll(rememberScrollState())"))
        }
        val queue = read("UnifiedPrintQueueActivity.kt")
        assertTrue(queue.contains("if (scrollPanel) Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 300.dp)"))
        assertTrue(queue.contains("if (!scrollPanel) Spacer(Modifier.weight(1f))"))
    }
}
