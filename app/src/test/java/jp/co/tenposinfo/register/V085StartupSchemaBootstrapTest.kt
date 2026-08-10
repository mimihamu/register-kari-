package jp.co.tenposinfo.register

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V085StartupSchemaBootstrapTest {
    @Test
    fun catalogBootstrapRunsSchemaPreparationBeforeCatalogAccess() {
        val source = File("src/main/java/jp/co/tenposinfo/register/CatalogBootstrapProvider.kt").readText()
        val schemaCall = source.indexOf("DatabaseStartupSchemaBootstrapV085.ensureBeforeUi(appContext)")
        val catalogCall = source.indexOf("CatalogMasterStore(appContext)")

        assertTrue(schemaCall >= 0)
        assertTrue(catalogCall >= 0)
        assertTrue(schemaCall < catalogCall)
    }

    @Test
    fun startupBootstrapPreparesCoreLazyStoresBeforeUi() {
        val source = File("src/main/java/jp/co/tenposinfo/register/DatabaseStartupSchemaBootstrapV085.kt").readText()

        assertTrue(source.contains("OperationsStore(appContext).close()"))
        assertTrue(source.contains("ReceiptVoucherStore(appContext).close()"))
        assertTrue(source.contains("SaleReceiptReprintAuditStore(appContext).close()"))
        assertTrue(source.contains("SaleCommitIdempotencySchema.ensure(db)"))
        assertTrue(source.contains("BusinessSessionSchema.ensure(db)"))
        assertTrue(source.contains("SettlementSnapshotSchemaV027.ensure(db)"))
        assertTrue(source.contains("DocumentPrintSafetySchema.ensure(db)"))
    }

    @Test
    fun startupBootstrapVerifiesOperationalReceiptAndReprintSchema() {
        val source = File("src/main/java/jp/co/tenposinfo/register/DatabaseStartupSchemaBootstrapV085.kt").readText()

        listOf(
            "business_sessions",
            "cash_movements",
            "reversal_transactions",
            "reversal_items",
            "document_print_jobs",
            "settlement_reports",
            "operation_commit_keys",
            "operation_audit",
            "receipt_voucher_batches",
            "receipt_voucher_issuances",
            "receipt_voucher_reprints",
            "sale_receipt_reprint_requests",
        ).forEach { table -> assertTrue(source.contains("\"$table\"")) }

        assertTrue(source.contains("\"sales\" to setOf(\"print_count\", \"business_session_id\", \"business_date\")"))
        assertTrue(source.contains("\"settlement_reports\" to setOf(\"opening_cash\", \"cash_in\", \"cash_out\", \"snapshot_version\")"))
    }

    @Test
    fun startupBootstrapDoesNotDeleteOrRewriteBusinessRows() {
        val source = File("src/main/java/jp/co/tenposinfo/register/DatabaseStartupSchemaBootstrapV085.kt").readText()

        assertFalse(source.contains("delete("))
        assertFalse(source.contains("DROP TABLE"))
        assertFalse(source.contains("DELETE FROM"))
        assertFalse(source.contains("UPDATE sales"))
        assertFalse(source.contains("UPDATE sale_items"))
        assertFalse(source.contains("UPDATE receipt_voucher"))
        assertFalse(source.contains("UPDATE sale_receipt_reprint"))
    }

    @Test
    fun currentReleaseAndDocsArePresentWithoutPinningFutureVersions() {
        val root = File("..")
        val build = File("build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()

        assertTrue(build.contains("applicationId = \"jp.co.tenposinfo.register\""))
        assertTrue(build.contains("compileSdk = 36"))
        assertTrue(workflow.contains(":app:testDebugUnitTest"))
        assertTrue(workflow.contains(":customer-display:testDebugUnitTest"))
        assertTrue(workflow.contains(":management-app:testDebugUnitTest"))
        assertTrue(File(root, "docs/V0.85_STARTUP_SCHEMA_BOOTSTRAP.md").isFile)
        assertTrue(File(root, "docs/V0.85_RELEASE_NOTES.md").isFile)
    }
}
