package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V078SettlementReconciliationTest {
    private fun saved(
        type: SettlementReportType,
        snapshotVersion: Int = SettlementSnapshotSchemaV027.SNAPSHOT_VERSION,
        netSales: Long = 9_000,
    ) = SettlementRecord(
        id = 10,
        businessSessionId = 7,
        businessDate = "2026-08-09",
        type = type,
        salesGross = 10_000,
        reversalGross = 1_000,
        netSales = netSales,
        expectedCash = 8_000,
        actualCash = 8_000,
        variance = 0,
        transactionCount = 5,
        reversalCount = 1,
        pendingPrints = 0,
        heldTickets = 0,
        operatorName = "担当",
        createdAt = 1_000,
        openingCash = 2_000,
        cashIn = 500,
        cashOut = 200,
        snapshotVersion = snapshotVersion,
    )

    private fun current(netSales: Long = 9_000) = DailyOperationsSummary(
        businessSessionId = 7,
        businessDate = "2026-08-09",
        salesGross = 10_000,
        reversalGross = 1_000,
        netSales = netSales,
        transactionCount = 5,
        reversalCount = 1,
        paymentTotals = emptyList(),
        openingCash = 2_000,
        cashIn = 500,
        cashOut = 200,
        expectedCash = 8_000,
        pendingPrints = 99,
        heldTickets = 99,
        settled = true,
    )

    @Test
    fun completeZSnapshotExactMatchIsOk() {
        val result = SettlementReconciliationPolicyV078.compare(
            saved(SettlementReportType.Z_SETTLEMENT),
            current(),
        )
        assertTrue(result.exactMatch)
        assertTrue(result.differences.isEmpty())
        assertEquals(SettlementReconciliationSeverity.OK, result.severity)
    }

    @Test
    fun zMismatchIsAlertButXMismatchIsInformational() {
        val z = SettlementReconciliationPolicyV078.compare(
            saved(SettlementReportType.Z_SETTLEMENT),
            current(netSales = 9_500),
        )
        assertFalse(z.exactMatch)
        assertEquals(SettlementReconciliationSeverity.ALERT, z.severity)
        assertTrue(z.differences.any { it.label == "純売上" })
        assertTrue(z.message.contains("監査"))

        val x = SettlementReconciliationPolicyV078.compare(
            saved(SettlementReportType.X_INSPECTION),
            current(netSales = 9_500),
        )
        assertEquals(SettlementReconciliationSeverity.INFO, x.severity)
        assertTrue(x.message.contains("後の取引"))
    }

    @Test
    fun legacySnapshotDoesNotPretendToBeExact() {
        val result = SettlementReconciliationPolicyV078.compare(
            saved(SettlementReportType.Z_SETTLEMENT, snapshotVersion = 0),
            current(),
        )
        assertFalse(result.exactMatch)
        assertFalse(result.fullSnapshot)
        assertEquals(SettlementReconciliationSeverity.INFO, result.severity)
        assertFalse(result.fields.any { it.label == "開始釣銭" })
    }

    @Test
    fun sourceIsPermissionCheckedAndBusinessDataReadOnly() {
        val root = File("..")
        val policy = File("src/main/java/jp/co/tenposinfo/register/SettlementReconciliationV078.kt").readText()
        val screen = File("src/main/java/jp/co/tenposinfo/register/SettlementHistoryScreenV027.kt").readText()
        val operations = File("src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()

        assertTrue(screen.contains("保存値と現在DBを照合"))
        assertTrue(screen.contains("reconciliationLoader"))
        assertTrue(screen.contains("AlertDialog"))
        assertTrue(operations.contains("SettlementReconciliationPolicyV078.compare"))
        assertTrue(operations.contains("store.settlementById(record.id)"))
        assertTrue(operations.contains("store.summaryForSession(latestRecord.businessSessionId)"))
        assertTrue(operations.contains("current?.allows(RegisterPermission.VIEW_SALES) == true"))
        assertTrue(operations.contains("current.allows(reportPermission)"))
        assertFalse(policy.contains("UPDATE "))
        assertFalse(policy.contains("DELETE FROM"))
        assertFalse(screen.contains("UPDATE settlement_reports"))
        assertFalse(screen.contains("DELETE FROM settlement_reports"))

        val versionCode = Regex("versionCode\\s*=\\s*(\\d+)")
            .find(build)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val versionMinor = Regex("versionName\\s*=\\s*\"0\\.(\\d+)\\.0-dev\\.1\"")
            .find(build)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        assertTrue(versionCode >= 108)
        assertTrue(versionMinor >= 78)

        assertTrue(workflow.contains("V078SettlementReconciliationTest.kt"))
        assertTrue(workflow.contains("SETTLEMENT_RECONCILIATION=true"))
        assertTrue(workflow.contains("SETTLEMENT_RECONCILIATION_X_DIFFERENCE_INFORMATIONAL=true"))
        assertTrue(workflow.contains("SETTLEMENT_RECONCILIATION_Z_DIFFERENCE_ALERT=true"))
        assertTrue(File(root, "docs/V0.78_SETTLEMENT_RECONCILIATION.md").isFile)
        assertTrue(File(root, "docs/V0.78_RELEASE_NOTES.md").isFile)
    }
}
