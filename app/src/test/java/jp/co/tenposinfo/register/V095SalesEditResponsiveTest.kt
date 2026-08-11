package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V095SalesEditResponsiveTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    private val mainSource: String by lazy {
        File(root, "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt").readText()
    }

    private fun lineEditSource(): String {
        val start = mainSource.indexOf("private fun LineEditScreen(")
        val end = mainSource.indexOf("private fun DiscountScreen(", start + 1)
        assertTrue("LineEditScreen source not found", start >= 0)
        assertTrue("DiscountScreen source not found after line edit", end > start)
        return mainSource.substring(start, end)
    }

    private fun discountSource(): String {
        val start = mainSource.indexOf("private fun DiscountScreen(")
        val end = mainSource.indexOf("private fun TicketListScreen(", start + 1)
        assertTrue("DiscountScreen source not found", start >= 0)
        assertTrue("TicketListScreen source not found after discount", end > start)
        return mainSource.substring(start, end)
    }

    @Test
    fun lineEditUsesResponsiveMetricsAndIndependentScrollFallbacks() {
        val source = lineEditSource()

        assertTrue(source.contains("val responsive = rememberRegisterResponsiveMetrics()"))
        assertTrue(source.contains("val editScroll = rememberScrollState()"))
        assertTrue(source.contains("val editSummaryScroll = rememberScrollState()"))
        assertTrue(source.contains("verticalScroll(editScroll)"))
        assertTrue(source.contains("verticalScroll(editSummaryScroll)"))
        assertTrue(source.contains("responsive.screenPaddingDp.dp"))
        assertTrue(source.contains("responsive.panelGapDp.dp"))
    }

    @Test
    fun lineEditCompactsWithoutRemovingNormalLayout() {
        val source = lineEditSource()

        assertTrue(source.contains("weight(if (responsive.isCompact) 1.28f else 1f)"))
        assertTrue(source.contains("Modifier.weight(0.72f)"))
        assertTrue(source.contains("Modifier.width(330.dp)"))
        assertTrue(source.contains("height(if (responsive.isCompact) 88.dp else 110.dp)"))
        assertTrue(source.contains("fontSize = if (responsive.isCompact) 22.sp else 28.sp"))
        assertFalse(source.contains("Row(Modifier.weight(1f).padding(20.dp)"))
    }

    @Test
    fun discountUsesResponsiveTwoPaneWeightsAndScrollFallbacks() {
        val source = discountSource()

        assertTrue(source.contains("val responsive = rememberRegisterResponsiveMetrics()"))
        assertTrue(source.contains("val discountEditScroll = rememberScrollState()"))
        assertTrue(source.contains("val discountPreviewScroll = rememberScrollState()"))
        assertTrue(source.contains("verticalScroll(discountEditScroll)"))
        assertTrue(source.contains("verticalScroll(discountPreviewScroll)"))
        assertTrue(source.contains("weight(if (responsive.isCompact) 1.08f else 1f)"))
        assertTrue(source.contains("Modifier.weight(0.92f)"))
        assertTrue(source.contains("Modifier.width(390.dp)"))
        assertFalse(source.contains("Row(Modifier.weight(1f).padding(20.dp)"))
    }

    @Test
    fun editAndDiscountBusinessRulesRemainIntact() {
        val line = lineEditSource()
        val discount = discountSource()

        assertTrue(line.contains("product = item.product.withLegacyTaxCategory(category)"))
        assertTrue(line.contains("quantity = parsedQuantity"))
        assertTrue(line.contains("unitPrice = parsedUnitPrice"))
        assertTrue(line.contains("discountAmount = parsedDiscount"))
        assertTrue(line.contains("note = note.trim()"))
        assertTrue(line.contains("onDiscount = onDiscount" ).not() || line.contains("onClick = onDiscount"))

        assertTrue(discount.contains("DiscountEngine.applyToItem"))
        assertTrue(discount.contains("DiscountEngine.applyToTransaction"))
        assertTrue(discount.contains("TaxEngine.calculate(items)"))
        assertTrue(discount.contains("TaxEngine.calculate(preview)"))
        assertTrue(discount.contains("BottomActions(onBack, \"適用\", { onApply(preview) }"))
    }

    @Test
    fun releaseDocumentationKeepsRealDeviceVerificationDeferred() {
        val notes = File(root, "docs/V0.95_RELEASE_NOTES.md")
        val requirements = File(root, "docs/V0.95_SALES_EDIT_RESPONSIVE.md")
        assertTrue(notes.isFile)
        assertTrue(requirements.isFile)
        assertTrue(notes.readText().contains("最終総合実機試験へ繰越"))
        assertTrue(requirements.readText().contains("最終総合実機試験"))

        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        assertTrue(workflow.contains(":app:testDebugUnitTest"))
        assertTrue(workflow.contains(":customer-display:testDebugUnitTest"))
        assertTrue(workflow.contains(":management-app:testDebugUnitTest"))
        assertTrue(workflow.contains("APK_RELEASE_INTEGRITY_GATE=true"))
        assertTrue(workflow.contains("SALES_EDIT_RESPONSIVE=true"))
    }
}
