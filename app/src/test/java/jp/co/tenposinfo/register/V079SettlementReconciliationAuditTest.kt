package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V079SettlementReconciliationAuditTest {
    private fun result(
        severity: SettlementReconciliationSeverity,
        differences: Boolean = false,
    ) = SettlementReconciliationResult(
        reportId = 42,
        reportType = SettlementReportType.Z_SETTLEMENT,
        businessSessionId = 7,
        businessDate = "2026-08-09",
        fullSnapshot = true,
        fields = if (differences) {
            listOf(
                SettlementReconciliationField("純売上", "9000", "9500"),
                SettlementReconciliationField("現金理論", "8000", "8500"),
            )
        } else {
            listOf(SettlementReconciliationField("純売上", "9000", "9000"))
        },
        severity = severity,
        message = "test",
    )

    @Test
    fun auditEventTypePreservesReconciliationSeverity() {
        assertEquals(
            "SETTLEMENT_RECONCILIATION_OK",
            SettlementReconciliationAuditPolicyV079.eventType(result(SettlementReconciliationSeverity.OK)),
        )
        assertEquals(
            "SETTLEMENT_RECONCILIATION_INFO",
            SettlementReconciliationAuditPolicyV079.eventType(result(SettlementReconciliationSeverity.INFO)),
        )
        assertEquals(
            "SETTLEMENT_RECONCILIATION_ALERT",
            SettlementReconciliationAuditPolicyV079.eventType(result(SettlementReconciliationSeverity.ALERT)),
        )
    }

    @Test
    fun auditDetailKeepsReportSessionSnapshotAndDifferenceEvidence() {
        val detail = SettlementReconciliationAuditPolicyV079.detail(
            result(SettlementReconciliationSeverity.ALERT, differences = true),
        )
        assertTrue(detail.contains("Z精算 No.42"))
        assertTrue(detail.contains("営業日 2026-08-09"))
        assertTrue(detail.contains("セッションNo.7"))
        assertTrue(detail.contains("判定 ALERT"))
        assertTrue(detail.contains("snapshot FULL"))
        assertTrue(detail.contains("差異 2件"))
        assertTrue(detail.contains("純売上: 9000 -> 9500"))
        assertTrue(detail.contains("現金理論: 8000 -> 8500"))
    }

    @Test
    fun sourceUsesAppendOnlyAuditAndFailsClosedBeforeShowingSuccess() {
        val root = File("..")
        val audit = File("src/main/java/jp/co/tenposinfo/register/SettlementReconciliationAuditV079.kt").readText()
        val operations = File("src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()

        assertTrue(audit.contains("insertOrThrow(\"operation_audit\""))
        assertTrue(audit.contains("reference_id"))
        assertTrue(audit.contains("operator_name"))
        assertTrue(audit.contains("created_at"))
        assertFalse(audit.contains("UPDATE "))
        assertFalse(audit.contains("DELETE FROM"))
        assertFalse(audit.contains("sales_json"))
        assertFalse(audit.contains("Authorization"))
        assertFalse(audit.contains("access_token"))
        assertFalse(audit.contains("content://"))

        val comparePos = operations.indexOf("SettlementReconciliationPolicyV078.compare")
        val appendPos = operations.indexOf("reconciliationAuditStore.append")
        val successMessagePos = operations.indexOf("整合確認を監査履歴へ記録しました")
        assertTrue(comparePos >= 0)
        assertTrue(appendPos > comparePos)
        assertTrue(successMessagePos > appendPos)
        assertTrue(operations.contains("reconciliationAuditStore.append(reconciliationResult, current.name)"))
        assertTrue(operations.contains("reconciliationAuditStore.close()"))

        // Cumulative development: v0.79 owns the audit semantics, not the current release number.
        assertTrue(build.contains("applicationId = \"jp.co.tenposinfo.register\""))
        assertTrue(build.contains("compileSdk = 36"))
        assertTrue(workflow.contains("V079SettlementReconciliationAuditTest.kt"))
        assertTrue(workflow.contains("SETTLEMENT_RECONCILIATION_AUDIT=true"))
        assertTrue(File(root, "docs/V0.79_SETTLEMENT_RECONCILIATION_AUDIT.md").isFile)
        assertTrue(File(root, "docs/V0.79_RELEASE_NOTES.md").isFile)
    }
}
