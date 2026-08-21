package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V135Period001SourceContractTest {
    @Test
    fun periodRangeImplementationStaysConnectedToPlusAndExistingRegisterRanges() {
        val root = File("..")
        val plusSource = File("src/main/java/jp/co/tenposinfo/register/plus")
        val reporting = File(plusSource, "SalesReporting.kt").readText()
        val periodUi = File(plusSource, "SalesReportPeriodFilterBar.kt").readText()
        val wrapper = File(plusSource, "ManagementPeriodWrapperV135.kt").readText()
        val docs = File(root, "docs/V1.35_PERIOD_001_REPORT_DATE_RANGE.md").readText()
        val registerTests = File(root, "app/src/test/java/jp/co/tenposinfo/register")

        for (token in listOf(
            "businessDateFrom",
            "businessDateTo",
            "SalesReportPeriodPolicy",
            "MAX_RANGE_DAYS = 31L",
            "SalesReportPeriodPolicy.matches",
        )) assertTrue(reporting.contains(token))

        for (token in listOf(
            "SalesReportPeriodFilterBarV135",
            "開始",
            "終了",
            "全期間",
            "selectableToDates",
        )) assertTrue(periodUi.contains(token))

        assertTrue(wrapper.contains("state: MutableState<ManagementUiState>"))
        assertTrue(wrapper.contains("SalesReportPeriodFilterBarV135"))
        assertTrue(wrapper.contains("state as State<ManagementUiState>"))

        assertTrue(File(registerTests, "V071SaleReceiptReprintPeriodIndexTest.kt").isFile)
        assertTrue(File(registerTests, "V072SaleReceiptReprintCustomRangeTest.kt").isFile)
        assertTrue(docs.contains("Issue #137"))
        assertTrue(docs.contains("最大31日"))
        assertTrue(docs.contains("実機未確認"))
    }
}
