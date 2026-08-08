package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V040ManagementSalesReportingIntegrationTest {
    @Test
    fun reportingCalculatorRepositoryUiDocsAndWorkflowStayConnected() {
        val root = File("..")
        val sourceRoot = File("src/main/java/jp/co/tenposinfo/register/plus")
        val reporting = File(sourceRoot, "SalesReporting.kt").readText()
        val repository = File(sourceRoot, "SalesJournalImportRepository.kt").readText()
        val database = File(sourceRoot, "ManagementDatabase.kt").readText()
        val activity = File(sourceRoot, "MainActivity.kt").readText()
        val mobileScreen = File(sourceRoot, "ManagementMobileScreen.kt").readText()
        val calculatorTest = File(
            "src/test/java/jp/co/tenposinfo/register/plus/SalesReportCalculatorTest.kt",
        )
        val plusBuild = File("build.gradle.kts").readText()
        val registerBuild = File(root, "app/build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        val docs = File(root, "docs/V0.40_TSUGUREGI_PLUS_SALES_REPORTING.md").readText()
        val notes = File(root, "docs/V0.40_RELEASE_NOTES.md").readText()

        for (token in listOf(
            "data class SalesReportFilter",
            "data class SalesReport",
            "object SalesReportCalculator",
            "EVENT_SALE",
            "EVENT_REVERSAL",
            "unmatchedReversalCount",
            "paymentBreakdownComplete",
            "taxBreakdownComplete",
        )) assertTrue(reporting.contains(token))

        assertTrue(repository.contains("fun reportFilterOptions"))
        assertTrue(repository.contains("fun salesReport"))
        assertTrue(repository.contains("ORDER BY occurred_at DESC"))
        assertTrue(database.contains("DATABASE_VERSION = 4"))
        assertTrue(database.contains("idx_imported_journal_report"))
        assertTrue(activity.contains("TsuguRegiPlusFolderSyncScreen"))
        assertTrue(mobileScreen.contains("売上概要"))
        assertTrue(mobileScreen.contains("純売上"))
        assertTrue(mobileScreen.contains("客単価"))
        assertTrue(mobileScreen.contains("支払方法別"))
        assertTrue(mobileScreen.contains("税額内訳"))
        assertTrue(mobileScreen.contains("取引確認"))
        assertTrue(mobileScreen.contains("集計対象外"))
        assertTrue(calculatorTest.isFile)
        assertTrue(calculatorTest.readText().contains("SalesReportCalculator"))

        assertTrue(registerBuild.contains("versionCode = 105"))
        assertTrue(registerBuild.contains("versionName = \"0.75.0-dev.1\""))
        assertTrue(plusBuild.contains("versionCode = 14"))
        assertTrue(plusBuild.contains("versionName = \"0.14.0-dev.1\""))
        assertTrue(workflow.contains(":management-app:testDebugUnitTest"))
        assertTrue(workflow.contains(":management-app:assembleDebug"))
        assertTrue(workflow.contains("TSUGUREGI_PLUS_v0.14.0_dev1_sync_operations_dashboard_debug.apk"))
        assertTrue(docs.contains("REVERSAL"))
        assertTrue(docs.contains("売上集計から除外"))
        assertTrue(notes.contains("営業日・店舗・端末"))
        assertFalse(File(root, "tools/v040_apply.py").exists())
        assertFalse(File(root, ".github/workflows/v040-apply.yml").exists())
    }
}