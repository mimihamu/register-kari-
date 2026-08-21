package jp.co.tenposinfo.register

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** UC-13 / REP-002..005 cumulative acceptance gate for Z settlement. */
class V135Uc13SettlementAuditTest {
    @Test
    fun rep004UsesVersion2JsonAndNormalizedSnapshotTables() {
        assertTrue(SettlementSnapshotSchemaV135.SNAPSHOT_VERSION >= 2)

        val snapshot = source("src/main/java/jp/co/tenposinfo/register/SettlementSnapshotV135.kt")
        listOf(
            "settlement_snapshot_metrics_v135",
            "settlement_snapshot_tax_v135",
            "settlement_snapshot_json_v135",
            "snapshot_json TEXT NOT NULL",
            "discount_total_yen",
            "guest_count",
            "actual_cash_entered",
            "registration_number",
            "target_amount_yen",
            "tax_amount_yen",
            "\\\"paymentTotals\\\"",
            "\\\"taxRateTotals\\\"",
        ).forEach { marker -> assertTrue("missing REP-004 snapshot marker: $marker", snapshot.contains(marker)) }
    }

    @Test
    fun snapshotWriteIsChainedToSettlementPaymentSnapshotInsideTheSameDatabaseTransaction() {
        val history = source("src/main/java/jp/co/tenposinfo/register/SettlementHistoryPolicyV027.kt")
        val save = history.functionBody("fun savePaymentTotals(", "fun loadPaymentTotals(")
        assertTrue(save.contains("db.insertOrThrow("))
        assertTrue(save.contains("SettlementSnapshotSchemaV135.save(db, reportId, totals)"))

        val store = source("src/main/java/jp/co/tenposinfo/register/OperationsStore.kt")
        val settlement = store.functionBody("fun recordSettlement(", "fun recentSettlements(")
        assertTrue(settlement.contains("SettlementSnapshotSchemaV027.savePaymentTotals(this, id, summary.paymentTotals)"))
        assertTrue(settlement.contains("insertOrThrow(\n                \"settlement_reports\""))
        assertTrue(settlement.contains("update(\n                    \"business_sessions\""))
    }

    @Test
    fun historicalPreviewReprintAndPdfPreferFrozenTaxIssuerAndCashPresentation() {
        val renderer = source("src/main/java/jp/co/tenposinfo/register/OperationDocuments.kt")
        val body = renderer.functionBody("fun renderSettlement(", "private fun formatDate(")
        assertTrue(body.contains("SettlementSnapshotRuntimeV135.document(data.reportId)"))
        assertTrue(body.contains("frozen?.issuer ?: TaxInvoiceSettingsRegistry.current().issuer"))
        assertTrue(body.contains("frozen?.rep001Totals ?: SettlementReportingRuntimeV135.documentTotals"))
        assertTrue(body.contains("frozen?.taxBreakdown ?: SettlementTaxBreakdownRuntimeV135.document"))
        assertTrue(body.contains("frozen?.actualCashEntered ?:"))

        val pdf = source("src/main/java/jp/co/tenposinfo/register/SettlementPdfExportV135.kt")
        assertTrue(pdf.contains("store.previewSettlement(reportId)"))
    }

    @Test
    fun rep002BlocksDuplicateZAndClosesOnlyTheOpenBusinessSession() {
        val store = source("src/main/java/jp/co/tenposinfo/register/OperationsStore.kt")
        val settlement = store.functionBody("fun recordSettlement(", "fun recentSettlements(")
        assertTrue(settlement.contains("if (type == SettlementReportType.Z_SETTLEMENT && summary.settled)"))
        assertTrue(settlement.contains("OperationsIdempotencyPolicy.settlementKey"))
        assertTrue(settlement.contains("BusinessSessionLifecyclePolicy.resultStatus(type, session.status).name"))
        assertTrue(settlement.contains("\"id = ? AND status = ?\""))
        assertTrue(settlement.contains("BusinessSessionStatus.OPEN.name"))
        assertTrue(settlement.contains("check(updated == 1)"))
    }

    @Test
    fun rep003RequiresCashAndKeepsAcknowledgedWarningsExplicit() {
        val preflight = source("src/main/java/jp/co/tenposinfo/register/SettlementPreflightV026.kt")
        listOf(
            "heldTickets",
            "pendingPrints",
            "openCartItems",
            "incompletePayments",
            "backupFailureMessage",
            "actualCashEntered",
            "pendingPrintsAcknowledged",
            "backupFailureAcknowledged",
        ).forEach { marker -> assertTrue("missing REP-003 marker: $marker", preflight.contains(marker)) }
    }

    @Test
    fun rep005HasNoSettlementDeleteOrRollbackPathInNormalSettlementExecution() {
        val store = source("src/main/java/jp/co/tenposinfo/register/OperationsStore.kt")
        val settlement = store.functionBody("fun recordSettlement(", "fun recentSettlements(")
        assertFalse(settlement.contains("delete(\"settlement_reports\""))
        assertFalse(settlement.contains("DELETE FROM settlement_reports"))
        assertFalse(settlement.contains("status = BusinessSessionStatus.OPEN"))
        assertTrue(settlement.contains("eventType = \"BUSINESS_CLOSE\""))
    }

    @Test
    fun uc13AuditKeepsRealDeviceAndV136RemoteSyncOutsideAutomatedPass() {
        val audit = source("../docs/v1.35-uc-13-settlement-audit.md")
        assertTrue(audit.contains("実機未確認"))
        assertTrue(audit.contains("58mm"))
        assertTrue(audit.contains("80mm"))
        assertTrue(audit.contains("Google Drive"))
        assertTrue(audit.contains("v1.36"))
    }

    private fun source(path: String): String =
        String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)

    private fun String.functionBody(startMarker: String, nextMarker: String): String {
        val start = indexOf(startMarker)
        require(start >= 0) { "start marker not found: $startMarker" }
        val end = indexOf(nextMarker, start + startMarker.length)
        require(end > start) { "next marker not found: $nextMarker" }
        return substring(start, end)
    }
}
