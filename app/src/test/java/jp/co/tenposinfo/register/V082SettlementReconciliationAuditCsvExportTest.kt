package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

class V082SettlementReconciliationAuditCsvExportTest {
    @Test
    fun detailFieldsAreSplitIntoAuditColumns() {
        val fields = SettlementReconciliationAuditCsvPolicyV082.fields(
            "Z精算 No.42 / 営業日 2026-08-09 / セッションNo.7 / 判定 ALERT / snapshot FULL / 差異 2件 / 純売上: 100 -> 90",
        )
        assertEquals("Z精算", fields.reportType)
        assertEquals("2026-08-09", fields.businessDate)
        assertEquals("7", fields.businessSessionId)
        assertEquals("FULL", fields.snapshotType)
        assertEquals("2", fields.differenceCount)
    }

    @Test
    fun csvUsesStableColumnsFormulaGuardAndDeterministicFileName() {
        val record = SettlementReconciliationAuditRecordV081(
            id = 9L,
            eventType = "SETTLEMENT_RECONCILIATION_ALERT",
            reportId = 42L,
            detail = "Z精算 No.42 / 営業日 2026-08-09 / セッションNo.7 / 判定 ALERT / snapshot FULL / 差異 1件 / 現金理論: 100 -> 90",
            operatorName = "=HYPERLINK(test)",
            createdAt = 0L,
        )
        val row = SettlementReconciliationAuditCsvPolicyV082.row(record, ZoneId.of("UTC"))
        assertTrue(row.contains("\"ALERT\""))
        assertTrue(row.contains("\"2026-08-09\""))
        assertTrue(row.contains("\"7\""))
        assertTrue(row.contains("\"FULL\""))
        assertTrue(row.contains("\"'=HYPERLINK(test)\""))
        assertEquals(
            "tsuguregi_reconciliation_audit_19700101_000000.csv",
            SettlementReconciliationAuditCsvPolicyV082.fileName(0L, ZoneId.of("UTC")),
        )
    }

    @Test
    fun exporterAndUiStayReadOnlySnapshotBoundAndPermissionChecked() {
        val root = File("..")
        val exporter = File("src/main/java/jp/co/tenposinfo/register/SettlementReconciliationAuditCsvExportV082.kt").readText()
        val activity = File("src/main/java/jp/co/tenposinfo/register/SettlementReconciliationAuditCsvExportActivityV082.kt").readText()
        val action = File("src/main/java/jp/co/tenposinfo/register/SettlementHistoryReconciliationV080.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()

        assertTrue(exporter.contains("database.readableDatabase"))
        assertTrue(exporter.contains("created_at < ? OR (created_at = ? AND id <= ?)"))
        assertTrue(exporter.contains("SettlementReconciliationAuditLedgerPolicyV081.query"))
        assertTrue(exporter.contains("writer.write('\\uFEFF'.code)"))
        assertTrue(exporter.contains("writer.write(\"\\r\\n\")"))
        assertFalse(exporter.contains("insertOrThrow"))
        assertFalse(exporter.contains("execSQL"))
        assertFalse(exporter.contains("UPDATE operation_audit"))
        assertFalse(exporter.contains("DELETE FROM operation_audit"))
        assertFalse(exporter.contains("sales_json"))
        assertFalse(exporter.contains("Authorization"))
        assertFalse(exporter.contains("access_token"))
        assertFalse(exporter.contains("content://"))

        assertTrue(activity.contains("ActivityResultContracts.CreateDocument(\"text/csv\")"))
        assertTrue(activity.contains("captureSnapshot(filter, appliedSearch)"))
        assertTrue(activity.contains("OperatorSessionRegistry.current(appContext)"))
        assertTrue(activity.contains("SettlementReconciliationAuditLedgerPolicyV081.canView"))
        assertTrue(activity.contains("Dispatchers.IO"))
        assertTrue(activity.contains("openOutputStream(uri, \"wt\")"))
        assertFalse(activity.contains("content://"))
        assertTrue(action.contains("SettlementReconciliationAuditCsvExportActivityV082::class.java"))
        assertTrue(action.contains("整合確認監査をCSV出力"))
        assertTrue(manifest.contains(".SettlementReconciliationAuditCsvExportActivityV082"))
        assertTrue(build.contains("applicationId = \"jp.co.tenposinfo.register\""))
        assertTrue(build.contains("compileSdk = 36"))
        assertTrue(workflow.contains("V082SettlementReconciliationAuditCsvExportTest.kt"))
        assertTrue(workflow.contains(":app:testDebugUnitTest"))
        assertTrue(File(root, "docs/V0.82_SETTLEMENT_RECONCILIATION_AUDIT_CSV_EXPORT.md").isFile)
        assertTrue(File(root, "docs/V0.82_RELEASE_NOTES.md").isFile)
    }
}
