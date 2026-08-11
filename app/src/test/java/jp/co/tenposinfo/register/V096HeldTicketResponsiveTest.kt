package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V096HeldTicketResponsiveTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    private val mainSource: String by lazy {
        File(root, "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt").readText()
    }

    private fun ticketListSource(): String {
        val start = mainSource.indexOf("private fun TicketListScreen(")
        val end = mainSource.indexOf("private fun TicketSplitScreen(", start + 1)
        assertTrue("TicketListScreen source not found", start >= 0)
        assertTrue("TicketSplitScreen boundary not found", end > start)
        return mainSource.substring(start, end)
    }

    private fun ticketSplitSource(): String {
        val start = mainSource.indexOf("private fun TicketSplitScreen(")
        val end = mainSource.indexOf("private fun PaymentScreen(", start + 1)
        assertTrue("TicketSplitScreen source not found", start >= 0)
        assertTrue("PaymentScreen boundary not found", end > start)
        return mainSource.substring(start, end)
    }

    @Test
    fun ticketListUsesResponsiveLayoutWithoutRemovingNormalActions() {
        val source = ticketListSource()

        assertTrue(source.contains("val ticketResponsive = rememberRegisterResponsiveMetrics()"))
        assertTrue(source.contains("if (ticketResponsive.isCompact)"))
        assertTrue(source.contains("Modifier.weight(1.25f).height(42.dp)"))
        assertTrue(source.contains("Modifier.width(if (currentCartCount > 0) 145.dp else 105.dp)"))
        assertTrue(source.contains("Modifier.weight(1f).height(40.dp)"))
        assertTrue(source.contains("Modifier.width(100.dp)"))
    }

    @Test
    fun ticketListKeepsHeldTicketOperationsAndSafetyFlow() {
        val source = ticketListSource()

        assertTrue(source.contains("onLoad(ticket)"))
        assertTrue(source.contains("onRename(ticket, editingName)"))
        assertTrue(source.contains("onDelete(ticket)"))
        assertTrue(source.contains("onSplit(ticket)"))
        assertTrue(source.contains("onMerge(source, ticket)"))
        assertTrue(source.contains("pendingDeleteId == ticket.id"))
        assertTrue(source.contains("pendingMergeTargetId == ticket.id"))
        assertTrue(source.contains("HeldTicketSafetyPolicy.MAX_NAME_LENGTH"))
    }

    @Test
    fun ticketSplitUsesResponsiveSummaryAndScrollFallback() {
        val source = ticketSplitSource()

        assertTrue(source.contains("val responsive = rememberRegisterResponsiveMetrics()"))
        assertTrue(source.contains("val splitSummaryScroll = rememberScrollState()"))
        assertTrue(source.contains("Modifier.weight(0.8f)"))
        assertTrue(source.contains("Modifier.width(350.dp)"))
        assertTrue(source.contains("verticalScroll(splitSummaryScroll)"))
        assertTrue(source.contains("Modifier.width(if (responsive.isCompact) 108.dp else 130.dp)"))
    }

    @Test
    fun ticketSplitKeepsValidationTaxSnapshotAndConfirmContract() {
        val source = ticketSplitSource()

        assertTrue(source.contains("HeldTicketOperationsUiPolicy.validateSplit(items, rawQuantities, newName)"))
        assertTrue(source.contains("validation.movedQuantities"))
        assertTrue(source.contains("confirmEnabled = validation.canConfirm"))
        assertTrue(source.contains("行値引は数量比で按分"))
        assertTrue(source.contains("税スナップショットは双方へ維持"))
    }

    @Test
    fun releaseDocumentationAndWorkflowKeepFinalDeviceVerificationDeferred() {
        val notes = File(root, "docs/V0.96_RELEASE_NOTES.md")
        val requirements = File(root, "docs/V0.96_HELD_TICKET_RESPONSIVE.md")
        assertTrue(notes.isFile)
        assertTrue(requirements.isFile)
        assertTrue(notes.readText().contains("最終総合実機試験へ繰越"))
        assertTrue(requirements.readText().contains("最終総合実機試験"))

        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        assertTrue(workflow.contains("Verify cumulative v0.14-v0.96 sources"))
        assertTrue(workflow.contains("POS_VERSION_CODE: 126"))
        assertTrue(workflow.contains("POS_VERSION_NAME: 0.96.0-dev.1"))
        assertTrue(workflow.contains("HELD_TICKET_RESPONSIVE=true"))
        assertTrue(workflow.contains("APK_RELEASE_INTEGRITY_GATE=true"))
    }

    @Test
    fun responsiveChangeDoesNotIntroduceDestructiveSalesDataStatements() {
        val source = ticketListSource() + ticketSplitSource()
        assertFalse(source.contains("DELETE FROM sales", ignoreCase = true))
        assertFalse(source.contains("UPDATE sales", ignoreCase = true))
        assertFalse(source.contains("DROP TABLE", ignoreCase = true))
    }
}
