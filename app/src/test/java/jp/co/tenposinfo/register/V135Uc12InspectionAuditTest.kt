package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** UC-12 / REP-001 cumulative acceptance gate for X inspection. */
class V135Uc12InspectionAuditTest {
    @Test
    fun xInspectionIsRepeatableWhileZKeepsPersistentSingleShotKey() {
        assertNull(OperationsIdempotencyPolicy.settlementKey(SettlementReportType.X_INSPECTION, 77L))
        assertTrue(OperationsIdempotencyPolicy.settlementKey(SettlementReportType.Z_SETTLEMENT, 77L) != null)

        val store = File("src/main/java/jp/co/tenposinfo/register/OperationsStore.kt").readText()
        val body = store.functionBody("fun recordSettlement(", "fun recentSettlements(")
        assertTrue(body.contains("if (type == SettlementReportType.Z_SETTLEMENT && summary.settled)"))
        assertTrue(body.contains("if (type == SettlementReportType.Z_SETTLEMENT) {"))
        assertTrue(body.contains("update(\n                    \"business_sessions\""))
        assertFalse(body.contains("if (type == SettlementReportType.X_INSPECTION) {\n                val updated = update("))
    }

    @Test
    fun xInspectionRequiresItsPermissionButNotManagerApproval() {
        assertTrue(OperationsAction.X_INSPECTION.permission == RegisterPermission.X_INSPECTION)
        assertFalse(OperationsAuthorizationPolicy.requiresManagerApproval(OperationsAction.X_INSPECTION, SettlementReportType.X_INSPECTION))
        assertTrue(OperationsAuthorizationPolicy.requiresManagerApproval(OperationsAction.Z_SETTLEMENT, SettlementReportType.Z_SETTLEMENT))

        val source = File("src/main/java/jp/co/tenposinfo/register/SecureOperationsCoordinator.kt").readText()
        assertTrue(source.contains("SettlementReportType.X_INSPECTION -> OperationsAction.X_INSPECTION"))
        assertTrue(source.contains("val operator = requireOperator(action)"))
        assertTrue(source.contains("AutomaticPrintScheduler.enqueueNow(appContext)"))
    }

    @Test
    fun xSnapshotContainsSalesReversalsTenderAndCashControlTotals() {
        val store = File("src/main/java/jp/co/tenposinfo/register/OperationsStore.kt").readText()
        val summary = store.functionBody("private fun summaryForSession(session: BusinessSessionWindow)", "fun recordCashMovement(")
        val settlement = store.functionBody("fun recordSettlement(", "fun recentSettlements(")

        assertTrue(summary.contains("SUM(total_amount)"))
        assertTrue(summary.contains("SUM(gross_amount)"))
        assertTrue(summary.contains("manual_return_transactions"))
        assertTrue(summary.contains("sale_payments"))
        assertTrue(summary.contains("reversal_payments"))
        assertTrue(summary.contains("manual_return_payments"))
        assertTrue(summary.contains("movementTotal(CashMovementType.IN, sessionId)"))
        assertTrue(summary.contains("movementTotal(CashMovementType.OUT, sessionId)"))
        assertTrue(summary.contains("OperationsMath.expectedCash("))

        assertTrue(settlement.contains("SettlementSnapshotSchemaV027.savePaymentTotals(this, id, summary.paymentTotals)"))
        assertTrue(settlement.contains("OperationDocumentRenderer.renderSettlement("))
        assertTrue(settlement.contains("OperationDocumentType.SETTLEMENT_REPORT"))
        assertTrue(settlement.contains("eventType = type.name"))
    }

    @Test
    fun reportRendersRequiredRep001BreakdownAndKeepsBusinessOpenMessage() {
        val renderer = File("src/main/java/jp/co/tenposinfo/register/OperationDocuments.kt").readText()
        val reporting = File("src/main/java/jp/co/tenposinfo/register/SettlementReportingV135.kt").readText()
        val tax = File("src/main/java/jp/co/tenposinfo/register/SettlementTaxBreakdownV135.kt").readText()

        listOf(
            "【X点検票】",
            "売上総額",
            "値引・割引",
            "返品・取消",
            "純売上",
            "売上件数",
            "客数",
            "点数",
            "客単価",
            "税率別",
            "現金売上",
            "非現金売上",
            "開始釣銭",
            "入金",
            "出金",
            "現金理論",
            "現金実査",
            "現金過不足",
            "営業日は継続中です",
        ).forEach { marker -> assertTrue("missing report marker: $marker", renderer.contains(marker)) }

        assertTrue(reporting.contains("discount_total_yen"))
        assertTrue(reporting.contains("tax_total_yen"))
        assertTrue(reporting.contains("item_count"))
        assertTrue(reporting.contains("guest_count"))
        assertTrue(tax.contains("reversal_transactions"))
    }

    @Test
    fun blankXActualCashStaysBlankForPrintPdfAndReprint() {
        assertFalse(
            SettlementActualCashPresentationV135.shouldShowActualCash(
                SettlementReportType.X_INSPECTION,
                entered = false,
            ),
        )
        assertTrue(
            SettlementActualCashPresentationV135.shouldShowActualCash(
                SettlementReportType.X_INSPECTION,
                entered = true,
            ),
        )
        assertTrue(
            SettlementActualCashPresentationV135.shouldShowActualCash(
                SettlementReportType.Z_SETTLEMENT,
                entered = true,
            ),
        )

        val presentation = File("src/main/java/jp/co/tenposinfo/register/SettlementActualCashPresentationV135.kt").readText()
        val coordinator = File("src/main/java/jp/co/tenposinfo/register/SecureOperationsCoordinator.kt").readText()
        val renderer = File("src/main/java/jp/co/tenposinfo/register/OperationDocuments.kt").readText()

        assertTrue(presentation.contains("settlement_actual_cash_input_v135"))
        assertTrue(presentation.contains("ThreadLocal<Boolean?>()"))
        assertTrue(presentation.contains("recoverFromPersistedPayload"))
        assertTrue(coordinator.contains("SettlementActualCashPresentationV135.withInput(appContext, actualCashEntered)"))
        assertTrue(coordinator.contains("SettlementActualCashPresentationV135.bind(appContext, settlementId, actualCashEntered)"))
        assertTrue(renderer.contains("SettlementActualCashPresentationV135.wasEntered(data.reportId)"))
        assertTrue(renderer.contains("if (actualCashEntered) yen(data.actualCash) else \"\""))
        assertTrue(renderer.contains("if (actualCashEntered) signedYen(data.variance) else \"\""))
    }

    @Test
    fun pdfExportIsPostCommitAndCannotInvalidateInspectionRecord() {
        val activity = File("src/main/java/jp/co/tenposinfo/register/SettlementActivityV030.kt").readText()
        val pdf = File("src/main/java/jp/co/tenposinfo/register/SettlementPdfExportV135.kt").readText()
        val store = File("src/main/java/jp/co/tenposinfo/register/OperationsStore.kt").readText()
        val settlement = store.functionBody("fun recordSettlement(", "fun recentSettlements(")

        assertTrue(activity.contains("ActivityResultContracts.CreateDocument(SettlementPdfExportPolicyV135.MIME_TYPE)"))
        assertTrue(activity.contains("SettlementPdfExportV135.write(context, record.id, uri)"))
        assertTrue(pdf.contains("store.previewSettlement(reportId)"))
        assertFalse(settlement.contains("SettlementPdfExportV135"))
    }

    @Test
    fun settlementOnlyRemoteSyncRemainsAnExplicitV136DependencyNotAFalseUc12Pass() {
        val audit = File("../docs/v1.35-uc-12-inspection-audit.md").readText()
        assertTrue(audit.contains("SETTLEMENT_ONLY"))
        assertTrue(audit.contains("v1.36"))
        assertTrue(audit.contains("実機未確認"))
        assertTrue(audit.contains("Google Drive"))
    }

    private fun String.functionBody(startMarker: String, nextMarker: String): String {
        val start = indexOf(startMarker)
        require(start >= 0) { "start marker not found: $startMarker" }
        val end = indexOf(nextMarker, start + startMarker.length)
        require(end > start) { "next marker not found: $nextMarker" }
        return substring(start, end)
    }
}
