package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V081SettlementReconciliationAuditLedgerTest {
    @Test
    fun viewingRequiresSalesAndAtLeastOneSettlementPermission() {
        assertTrue(
            SettlementReconciliationAuditLedgerPolicyV081.canView(
                setOf(RegisterPermission.VIEW_SALES, RegisterPermission.X_INSPECTION),
            ),
        )
        assertTrue(
            SettlementReconciliationAuditLedgerPolicyV081.canView(
                setOf(RegisterPermission.VIEW_SALES, RegisterPermission.Z_SETTLEMENT),
            ),
        )
        assertFalse(
            SettlementReconciliationAuditLedgerPolicyV081.canView(
                setOf(RegisterPermission.VIEW_SALES),
            ),
        )
        assertFalse(
            SettlementReconciliationAuditLedgerPolicyV081.canView(
                setOf(RegisterPermission.X_INSPECTION, RegisterPermission.Z_SETTLEMENT),
            ),
        )
    }

    @Test
    fun queryLimitsRowsToReconciliationEventsAndAppliesSafeFilters() {
        val all = SettlementReconciliationAuditLedgerPolicyV081.query(
            SettlementReconciliationAuditFilterV081.ALL,
            "",
        )
        assertEquals("event_type LIKE ?", all.selection)
        assertEquals(listOf("SETTLEMENT_RECONCILIATION_%"), all.args)

        val alert = SettlementReconciliationAuditLedgerPolicyV081.query(
            SettlementReconciliationAuditFilterV081.ALERT,
            " 2026-08-09 ",
        )
        assertTrue(alert.selection.contains("event_type = ?"))
        assertTrue(alert.selection.contains("CAST(reference_id AS TEXT) LIKE ?"))
        assertTrue(alert.selection.contains("operator_name LIKE ?"))
        assertTrue(alert.selection.contains("detail LIKE ?"))
        assertEquals("SETTLEMENT_RECONCILIATION_ALERT", alert.args[1])
        assertEquals("%2026-08-09%", alert.args[2])
        assertEquals("%2026-08-09%", alert.args[3])
        assertEquals("%2026-08-09%", alert.args[4])
    }

    @Test
    fun ledgerIsReadOnlyConnectedFromScr520AndDocumented() {
        val root = File("..")
        val source = File("src/main/java/jp/co/tenposinfo/register/SettlementReconciliationAuditLedgerV081.kt").readText()
        val action = File("src/main/java/jp/co/tenposinfo/register/SettlementHistoryReconciliationV080.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()

        assertTrue(source.contains("baseDatabase.readableDatabase"))
        assertTrue(source.contains("\"operation_audit\""))
        assertTrue(source.contains("SETTLEMENT_RECONCILIATION_"))
        assertTrue(source.contains("created_at DESC, id DESC"))
        assertTrue(source.contains("OperatorSessionRegistry.current(appContext)"))
        assertFalse(source.contains("insertOrThrow"))
        assertFalse(source.contains("execSQL"))
        assertFalse(source.contains("UPDATE operation_audit"))
        assertFalse(source.contains("DELETE FROM operation_audit"))
        assertFalse(source.contains("sales_json"))
        assertFalse(source.contains("Authorization"))
        assertFalse(source.contains("access_token"))
        assertFalse(source.contains("content://"))

        assertTrue(action.contains("SettlementReconciliationAuditLedgerActivityV081::class.java"))
        assertTrue(action.contains("整合確認監査台帳を開く"))
        assertTrue(manifest.contains(".SettlementReconciliationAuditLedgerActivityV081"))
        assertTrue(build.contains("applicationId = \"jp.co.tenposinfo.register\""))
        assertTrue(workflow.contains("V081SettlementReconciliationAuditLedgerTest.kt"))
        assertTrue(workflow.contains(":app:testDebugUnitTest"))
        assertTrue(workflow.contains(":management-app:testDebugUnitTest"))
        assertTrue(workflow.contains(":customer-display:testDebugUnitTest"))
        assertTrue(File(root, "docs/V0.81_SETTLEMENT_RECONCILIATION_AUDIT_LEDGER.md").isFile)
        assertTrue(File(root, "docs/V0.81_RELEASE_NOTES.md").isFile)
    }
}
