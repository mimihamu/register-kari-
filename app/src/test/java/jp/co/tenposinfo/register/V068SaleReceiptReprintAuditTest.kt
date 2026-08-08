package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class V068SaleReceiptReprintAuditTest {
    @Test
    fun policyNormalizesUuidOperatorAndPaperWidth() {
        val requestId = UUID.randomUUID().toString()
        assertEquals(requestId, SaleReceiptReprintAuditPolicy.normalizeRequestId("  $requestId  "))
        assertEquals("山田", SaleReceiptReprintAuditPolicy.normalizeOperatorName("  山田  "))
        assertEquals(58, SaleReceiptReprintAuditPolicy.normalizePaperWidth(58))
        assertEquals(80, SaleReceiptReprintAuditPolicy.normalizePaperWidth(80))
        assertEquals(80, SaleReceiptReprintAuditPolicy.normalizePaperWidth(99))
        assertTrue(runCatching { SaleReceiptReprintAuditPolicy.normalizeRequestId("bad") }.isFailure)
        assertTrue(runCatching { SaleReceiptReprintAuditPolicy.normalizeOperatorName("   ") }.isFailure)
    }

    @Test
    fun sourceKeepsAuditAppendOnlyAtomicAndIdempotent() {
        val root = File("..")
        val audit = File("src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintAudit.kt").readText()
        val activity = File("src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintActivity.kt").readText()
        val main = File("src/main/java/jp/co/tenposinfo/register/MainActivity.kt").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        val docs = File(root, "docs/V0.68_SALE_RECEIPT_REPRINT_AUDIT.md")
        val notes = File(root, "docs/V0.68_RELEASE_NOTES.md")

        assertTrue(audit.contains("const val TABLE = \"sale_receipt_reprint_requests\""))
        assertTrue(audit.contains("CREATE TABLE IF NOT EXISTS \$TABLE"))
        assertTrue(audit.contains("request_id TEXT NOT NULL UNIQUE"))
        assertTrue(audit.contains("print_job_id INTEGER NOT NULL UNIQUE"))
        assertTrue(audit.contains("FOREIGN KEY(sale_id) REFERENCES sales(id)"))
        assertTrue(audit.contains("FOREIGN KEY(print_job_id) REFERENCES print_jobs(id)"))
        assertTrue(audit.contains("db.beginTransaction()"))
        assertTrue(audit.contains("db.setTransactionSuccessful()"))
        assertTrue(audit.contains("findByRequestId"))
        assertTrue(audit.contains("newlyCreated = false"))
        assertTrue(audit.contains("val jobId = db.insertOrThrow("))
        assertTrue(audit.contains("\"print_jobs\""))
        assertTrue(audit.contains("val auditId = db.insertOrThrow("))
        assertTrue(audit.contains("TABLE,"))
        assertFalse(audit.contains("DELETE FROM sale_receipt_reprint_requests"))
        assertFalse(audit.contains("delete(TABLE"))
        assertFalse(audit.contains("UPDATE sales"))

        assertTrue(activity.contains("SaleReceiptReprintAuditStore"))
        assertTrue(activity.contains("UUID.randomUUID().toString()"))
        assertTrue(activity.contains("auditStore.request("))
        assertTrue(activity.contains("operatorName = current.name"))
        assertTrue(activity.contains("再印字要求履歴（追記専用）"))
        assertTrue(activity.contains("result.newlyCreated"))
        assertTrue(activity.contains("二重登録せず"))
        assertFalse(activity.contains("database.enqueueReprint(detail.summary.id)"))

        assertTrue(main.contains("lastSaleId?.let { saleId"))
        assertTrue(main.contains("SaleReceiptNavigation.intent(context, saleId)"))
        assertTrue(main.contains("SaleReceiptNavigation.intent(context, detail.summary.id)"))
        assertTrue(build.contains("versionCode = 105"))
        assertTrue(build.contains("versionName = \"0.75.0-dev.1\""))
        assertTrue(workflow.contains("V068SaleReceiptReprintAuditTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_v0.75.0_dev1_sale_receipt_reprint_csv_export_debug.apk"))
        assertTrue(docs.isFile)
        assertTrue(notes.isFile)
    }
}
