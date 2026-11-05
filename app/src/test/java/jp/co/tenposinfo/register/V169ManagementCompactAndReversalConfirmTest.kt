package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

/** v1.69: compact management + deliberate confirmation for existing-sale reversals. */
class V169ManagementCompactAndReversalConfirmTest {
    private val path = File("src/main/java/jp/co/tenposinfo/register")
    private fun code(name: String) = File(path, name).readText()

    @Test fun compactLandscapeUsesThreeActionColumnsAndShortTiles() {
        assertEquals(2, HubManagementLayoutV143.columns(460))
        assertEquals(3, HubManagementLayoutV143.columns(480))
        assertEquals(3, HubManagementLayoutV143.columns(720))
        assertEquals(70, HubManagementLayoutV143.tileHeightDp(350))
        assertEquals(86, HubManagementLayoutV143.tileHeightDp(600))
        val hub = code("OperationsHubActivityV030.kt")
        assertTrue(hub.contains("val showAllDetails = !metrics.isCompact || detailsExpanded"))
        assertTrue(hub.contains("if (detailsExpanded) \"閉じる\" else \"詳細\""))
        assertTrue(hub.contains("if (summary.pendingPrints > 0 || showAllDetails)"))
        assertTrue(hub.contains("if (showAllDetails)"))
        assertTrue(hub.contains("HubAmountV030(\"純売上\""))
        assertTrue(hub.contains("HubAmountV030(\"レジマイナス\""))
    }

    @Test fun receiptLinkedReversalRequiresFinalNonDismissableModal() {
        val code = code("OperationsActivity.kt")
        val screen = code.substring(code.indexOf("private fun ReversalScreen("),
            code.indexOf("private val reversalQuantityInputRegex"))
        assertTrue(screen.contains("var showReversalConfirm by remember"))
        assertTrue(screen.contains("onClick = { showReversalConfirm = true }"))
        assertTrue(screen.contains("RegisterModalV154("))
        assertTrue(screen.contains("onDismissRequest = {}"))
        assertTrue(screen.contains("Text(\"元売上 No."))
        assertTrue(screen.contains("Text(\"処理："))
        assertTrue(screen.contains("\"返金予定 "))
        assertTrue(screen.contains("enabled = canExecute"))
        assertTrue(screen.contains("if (saleId != null && canExecute)"))
        assertTrue(screen.contains("RegisterModalCancelV154(onClick = { showReversalConfirm = false })"))
        assertTrue(screen.contains("destructive = true"))
        assertFalse(screen.contains("return@Button\n                        val result = onExecute"))
    }

    @Test fun reversalKeepsSameIdOnFailedSubmissionAndResetsOnlyOnSuccess() {
        val screen = code("OperationsActivity.kt").substring(
            code("OperationsActivity.kt").indexOf("private fun ReversalScreen("))
        assertTrue(screen.contains("onExecute(saleId, type, quantityHundredths, reason, pin, requestId)"))
        assertTrue(screen.contains("if (result != null)"))
        assertTrue(screen.contains("requestId = UUID.randomUUID().toString()"))
        assertTrue(screen.contains("ManagerApprovalV145.canUseCurrentManagerSession"))
    }
}
