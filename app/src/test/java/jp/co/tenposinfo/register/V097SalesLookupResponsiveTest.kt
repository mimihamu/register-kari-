package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V097SalesLookupResponsiveTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }
    private val mainSource = File(root, "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt").readText()

    @Test
    fun currentVersionIsV097() {
        val gradle = File(root, "app/build.gradle.kts").readText()
        assertTrue(gradle.contains("versionCode = 127"))
        assertTrue(gradle.contains("versionName = \"0.97.0-dev.1\""))
    }

    @Test
    fun salesHistoryUsesResponsiveFiltersAndCompactRows() {
        assertTrue(mainSource.contains("val historyResponsive = rememberRegisterResponsiveMetrics()"))
        assertTrue(mainSource.contains("val historyFilterScroll = rememberScrollState()"))
        assertTrue(mainSource.contains("Modifier.heightIn(max = 190.dp).verticalScroll(historyFilterScroll)"))
        assertTrue(mainSource.contains("if (historyResponsive.isCompact)"))
        assertTrue(mainSource.contains("Modifier.width(96.dp).height(48.dp)"))
        assertTrue(mainSource.contains("Modifier.width(132.dp).height(48.dp)"))
        assertTrue(mainSource.contains("fontSize = 11.sp"))
    }

    @Test
    fun salesHistoryNormalLayoutAndLookupCallbacksRemain() {
        assertTrue(mainSource.contains("modifier = Modifier.width(150.dp)"))
        assertTrue(mainSource.contains("Modifier.width(210.dp)"))
        assertTrue(mainSource.contains("Text(\"#${'$'}{sale.id}\", Modifier.width(80.dp)"))
        assertTrue(mainSource.contains("Text(yen(sale.totalAmount), Modifier.width(130.dp)"))
        assertTrue(mainSource.contains("if (!onDirectLookup(saleId))"))
        assertTrue(mainSource.contains("onOpen(sale)"))
        assertTrue(mainSource.contains("OutlinedButton(onClick = onQueue)"))
    }

    @Test
    fun saleDetailUsesResponsiveSummaryWithFixedActionArea() {
        assertTrue(mainSource.contains("val detailResponsive = rememberRegisterResponsiveMetrics()"))
        assertTrue(mainSource.contains("val detailSummaryScroll = rememberScrollState()"))
        assertTrue(mainSource.contains("Modifier.weight(0.9f)"))
        assertTrue(mainSource.contains("Modifier.width(380.dp)"))
        assertTrue(mainSource.contains("Column(Modifier.weight(1f).verticalScroll(detailSummaryScroll))"))
        assertTrue(mainSource.contains("if (detailResponsive.isCompact) 46.dp else 52.dp"))
    }

    @Test
    fun receiptVoucherAndReversalCallbacksRemain() {
        assertTrue(mainSource.contains("\"レシート／再印字\","))
        assertTrue(mainSource.contains("onReceipt,"))
        assertTrue(mainSource.contains("onClick = onVoucher"))
        assertTrue(mainSource.contains("onClick = onReverse"))
        assertTrue(mainSource.contains("if (canReverse)"))
    }

    @Test
    fun responsiveChangeDoesNotAddBusinessDataMutation() {
        val requirements = File(root, "docs/V0.97_SALES_LOOKUP_RESPONSIVE.md").readText()
        assertFalse(requirements.contains("DELETE FROM", ignoreCase = true))
        assertFalse(requirements.contains("UPDATE sales", ignoreCase = true))
        assertFalse(requirements.contains("DROP TABLE", ignoreCase = true))
        assertTrue(requirements.contains("業務ロジックは変更しない"))
    }

    @Test
    fun finalDeviceAcceptanceRemainsDeferredAndCiIsCumulative() {
        val notes = File(root, "docs/V0.97_RELEASE_NOTES.md").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        assertTrue(notes.contains("最終総合実機試験へ繰越"))
        assertTrue(workflow.contains("Verify cumulative v0.14-v0.97 sources"))
        assertTrue(workflow.contains(":app:testDebugUnitTest"))
        assertTrue(workflow.contains("POS_VERSION_CODE: 127"))
        assertTrue(workflow.contains("POS_VERSION_NAME: 0.97.0-dev.1"))
    }
}
