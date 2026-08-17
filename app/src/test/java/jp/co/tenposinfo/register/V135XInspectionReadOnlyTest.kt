package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.35 REP-003: X点検 is a live read-only report and never creates a fixed settlement snapshot. */
class V135XInspectionReadOnlyTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    private fun source(name: String): String = File(
        root,
        "app/src/main/java/jp/co/tenposinfo/register/$name",
    ).readText()

    @Test
    fun storeExposesReadOnlyXAggregateAndPersistentSettlementIsZOnly() {
        val store = source("OperationsStore.kt")
        assertTrue(store.contains("fun inspectX(): DailyOperationsSummary = dailySummary()"))

        val start = store.indexOf("fun recordSettlement(")
        val end = store.indexOf("fun recentSettlements(", start)
        val body = store.substring(start, end)
        assertTrue(body.contains("require(type == SettlementReportType.Z_SETTLEMENT)"))
        assertTrue(body.contains("insertOrThrow(\n                \"settlement_reports\""))
        assertTrue(body.contains("insertDocumentJob("))
    }

    @Test
    fun coordinatorReadOnlyXPathHasNoSettlementPrintOrBackupSideEffects() {
        val coordinator = source("SecureOperationsCoordinator.kt")
        val start = coordinator.indexOf("fun inspectX(): DailyOperationsSummary")
        val end = coordinator.indexOf("fun recordSettlement(", start)
        val body = coordinator.substring(start, end)
        assertTrue(body.contains("requireOperator(OperationsAction.X_INSPECTION)"))
        assertTrue(body.contains("store.inspectX()"))
        assertFalse(body.contains("store.recordSettlement"))
        assertFalse(body.contains("AutomaticPrintScheduler"))
        assertFalse(body.contains("AutoBackupScheduler"))

        val persistentStart = coordinator.indexOf("fun recordSettlement(")
        val persistentEnd = coordinator.indexOf("fun reprintSettlement(", persistentStart)
        val persistent = coordinator.substring(persistentStart, persistentEnd)
        assertTrue(persistent.contains("require(type == SettlementReportType.Z_SETTLEMENT)"))
    }

    @Test
    fun mainOperationsXScreenRefreshesLiveDataAndShowsNoCurrentXHistory() {
        val activity = source("OperationsActivity.kt")
        val xStart = activity.indexOf("OperationsScreen.X_INSPECTION -> SettlementScreen(")
        val zStart = activity.indexOf("OperationsScreen.Z_SETTLEMENT -> SettlementScreen(", xStart)
        val xRoute = activity.substring(xStart, zStart)

        assertTrue(xRoute.contains("reportType = SettlementReportType.X_INSPECTION"))
        assertTrue(xRoute.contains("history = emptyList()"))
        assertTrue(xRoute.contains("onExecute = { _, _, _ -> inspectX() }"))
        assertFalse(xRoute.contains("executeSettlement("))
        assertTrue(activity.contains("X点検を更新しました（固定スナップショットは保存しません）"))
        assertTrue(activity.contains("固定履歴・固定帳票・印刷ジョブは作成しません"))
    }

    @Test
    fun standaloneSettlementRouteKeepsXOutOfPersistedHistoryAndWritePath() {
        val activity = source("SettlementActivityV030.kt")
        assertTrue(activity.contains("if (reportType == SettlementReportType.X_INSPECTION) {\n            emptyList()"))
        assertTrue(activity.contains("val result = runCatching { secureStore.inspectX() }"))
        assertTrue(activity.contains("固定履歴・固定帳票・印刷ジョブは作成しません"))

        val executeStart = activity.indexOf("onExecute = { actualCash, managerPin, pendingAcknowledged ->")
        val executeEnd = activity.indexOf("operator = OperatorSessionRegistry.current(appContext)", executeStart)
        val execute = activity.substring(executeStart, executeEnd)
        assertTrue(execute.contains("if (reportType == SettlementReportType.X_INSPECTION)"))
        assertTrue(execute.contains("secureStore.inspectX()"))
        assertTrue(execute.contains("secureStore.recordSettlement("))
    }

    @Test
    fun zSettlementSafetyAndLegacyHistoricalXReprintSupportRemainAvailable() {
        val activity = source("OperationsActivity.kt")
        val store = source("OperationsStore.kt")
        assertTrue(activity.contains("Z精算して営業を終了しますか？"))
        assertTrue(activity.contains("reportType = SettlementReportType.Z_SETTLEMENT"))
        assertTrue(store.contains("SettlementActualCashSafetyV105.effectiveActualCash("))
        assertTrue(store.contains("ZSettlementPreflightPolicy.evaluate("))
        // Existing historical rows from older releases are kept non-destructively and remain readable/reprintable.
        assertTrue(store.contains("fun settlementById(reportId: Long): SettlementRecord?"))
        assertTrue(store.contains("fun reprintSettlement("))
    }
}
