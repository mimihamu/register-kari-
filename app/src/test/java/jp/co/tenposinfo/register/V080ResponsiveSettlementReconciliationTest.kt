package jp.co.tenposinfo.register

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V080ResponsiveSettlementReconciliationTest {
    @Test
    fun reconciliationRequiresViewSalesAndMatchingSettlementPermission() {
        assertTrue(
            SettlementHistoryReconciliationPolicyV080.canReconcile(
                setOf(RegisterPermission.VIEW_SALES, RegisterPermission.X_INSPECTION),
                SettlementReportType.X_INSPECTION,
            ),
        )
        assertTrue(
            SettlementHistoryReconciliationPolicyV080.canReconcile(
                setOf(RegisterPermission.VIEW_SALES, RegisterPermission.Z_SETTLEMENT),
                SettlementReportType.Z_SETTLEMENT,
            ),
        )
        assertFalse(
            SettlementHistoryReconciliationPolicyV080.canReconcile(
                setOf(RegisterPermission.X_INSPECTION),
                SettlementReportType.X_INSPECTION,
            ),
        )
        assertFalse(
            SettlementHistoryReconciliationPolicyV080.canReconcile(
                setOf(RegisterPermission.VIEW_SALES, RegisterPermission.X_INSPECTION),
                SettlementReportType.Z_SETTLEMENT,
            ),
        )
    }

    @Test
    fun responsiveHistoryWiresV078ComparisonAndV079AppendOnlyAudit() {
        val root = File("..")
        val action = File("src/main/java/jp/co/tenposinfo/register/SettlementHistoryReconciliationV080.kt").readText()
        val responsive = File("src/main/java/jp/co/tenposinfo/register/SettlementHistoryActivityV030.kt").readText()
        val audit = File("src/main/java/jp/co/tenposinfo/register/SettlementReconciliationAuditV079.kt").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        val docs = File(root, "docs/V0.80_RESPONSIVE_SETTLEMENT_RECONCILIATION.md")
        val notes = File(root, "docs/V0.80_RELEASE_NOTES.md")

        assertTrue(action.contains("OperatorSessionRegistry.current(appContext)"))
        assertTrue(action.contains("SettlementReconciliationPolicyV078.compare"))
        assertTrue(action.contains("store.summaryForSession(latestRecord.businessSessionId)"))
        assertTrue(action.contains("auditStore.append(comparison, current.name)"))
        assertTrue(action.contains("整合確認を監査履歴へ記録しました"))
        assertTrue(action.contains("保存値と現在DBを照合"))
        assertTrue(action.contains("SETTLEMENT_RECONCILIATION").not())
        assertFalse(action.contains("UPDATE settlement_reports"))
        assertFalse(action.contains("DELETE FROM settlement_reports"))
        assertFalse(action.contains("UPDATE sales"))
        assertFalse(action.contains("DELETE FROM sales"))

        assertTrue(responsive.contains("SettlementHistoryReconciliationActionV080(selected, permissions)"))
        assertTrue(audit.contains("db.insertOrThrow(\"operation_audit\""))
        assertFalse(audit.contains("UPDATE operation_audit"))
        assertFalse(audit.contains("DELETE FROM operation_audit"))

        assertTrue(build.contains("applicationId = \"jp.co.tenposinfo.register\""))
        assertTrue(build.contains("compileSdk = 36"))
        assertTrue(workflow.contains("V080ResponsiveSettlementReconciliationTest.kt"))
        assertTrue(workflow.contains(":app:testDebugUnitTest"))
        assertTrue(workflow.contains(":app:assembleDebug"))
        assertTrue(docs.isFile)
        assertTrue(notes.isFile)
    }
}
