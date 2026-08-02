package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V025InspectionSettlementNavigationTest {
    private val source = File("src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt").readText()

    @Test
    fun inspectionAndSettlementHaveSeparateRoutes() {
        assertTrue(source.contains("OperationsScreen.X_INSPECTION"))
        assertTrue(source.contains("OperationsScreen.Z_SETTLEMENT"))
        assertFalse(source.contains("OperationsScreen.SETTLEMENT"))
        assertTrue(source.contains("onXInspection"))
        assertTrue(source.contains("onZSettlement"))
    }

    @Test
    fun reportTypeCannotBeChangedInsideEachScreen() {
        assertFalse(source.contains("var reportType by remember"))
        assertFalse(source.contains("OpChoiceButton(\"X点検\""))
        assertTrue(source.contains("reportType = SettlementReportType.X_INSPECTION"))
        assertTrue(source.contains("reportType = SettlementReportType.Z_SETTLEMENT"))
    }

    @Test
    fun zSettlementRequiresExplicitConfirmation() {
        assertTrue(source.contains("Z精算して営業を終了しますか？"))
        assertTrue(source.contains("完了後、この営業セッションでは販売できません。"))
        assertTrue(source.contains("enabled = pin.isNotBlank()"))
    }

    @Test
    fun cashMovementDoesNotUseSettlementReportType() {
        val start = source.indexOf("OperationsScreen.CASH_MOVEMENT")
        val end = source.indexOf("OperationsScreen.REVERSAL", start)
        val segment = source.substring(start, end)
        assertFalse(segment.contains("SettlementReportType"))
        assertTrue(segment.contains("type.displayName"))
    }
}
