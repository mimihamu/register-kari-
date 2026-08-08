package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

class V071SaleReceiptReprintPeriodIndexTest {
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val now = ZonedDateTime.of(2026, 8, 8, 19, 30, 0, 0, tokyo)
        .toInstant()
        .toEpochMilli()

    @Test
    fun periodCutoffsAreDeterministic() {
        assertNull(
            SaleReceiptReprintLedgerPolicy.earliestRequestedAt(
                SaleReceiptReprintLedgerPeriod.ALL,
                now,
                tokyo,
            ),
        )
        val todayStart = ZonedDateTime.of(2026, 8, 8, 0, 0, 0, 0, tokyo)
            .toInstant()
            .toEpochMilli()
        assertEquals(
            todayStart,
            SaleReceiptReprintLedgerPolicy.earliestRequestedAt(
                SaleReceiptReprintLedgerPeriod.TODAY,
                now,
                tokyo,
            ),
        )
        assertEquals(
            now - 7L * 24L * 60L * 60L * 1_000L,
            SaleReceiptReprintLedgerPolicy.earliestRequestedAt(
                SaleReceiptReprintLedgerPeriod.LAST_7_DAYS,
                now,
                tokyo,
            ),
        )
        assertEquals(
            now - 30L * 24L * 60L * 60L * 1_000L,
            SaleReceiptReprintLedgerPolicy.earliestRequestedAt(
                SaleReceiptReprintLedgerPeriod.LAST_30_DAYS,
                now,
                tokyo,
            ),
        )
    }

    @Test
    fun periodIsBoundIntoDatabaseQueryBeforeTextPatterns() {
        val spec = SaleReceiptReprintLedgerPolicy.buildDatabaseQuery(
            criteria = SaleReceiptReprintLedgerCriteria(
                filter = SaleReceiptReprintLedgerFilter.COMPLETED,
                period = SaleReceiptReprintLedgerPeriod.LAST_7_DAYS,
                query = "山田",
            ),
            nowMillis = now,
            zoneId = tokyo,
        )
        assertTrue(spec.whereSql.contains("j.status = ?"))
        assertTrue(spec.whereSql.contains("r.requested_at >= ?"))
        assertFalse(spec.whereSql.contains(now.toString()))
        assertEquals("COMPLETED", spec.args[0])
        assertEquals((now - 7L * 24L * 60L * 60L * 1_000L).toString(), spec.args[1])
        assertEquals("%山田%", spec.args[2])
        assertEquals(11, spec.args.size)
    }

    @Test
    fun inMemoryCompatibilityFilterHonorsPeriod() {
        val entries = listOf(
            entry(1L, now - 1_000L),
            entry(2L, now - 8L * 24L * 60L * 60L * 1_000L),
        )
        val filtered = SaleReceiptReprintLedgerPolicy.filter(
            entries,
            SaleReceiptReprintLedgerCriteria(period = SaleReceiptReprintLedgerPeriod.LAST_7_DAYS),
            now,
            tokyo,
        )
        assertEquals(listOf(1L), filtered.map { it.auditId })
    }

    @Test
    fun sourceAddsGlobalTimelineIndexAndPeriodUiWithoutMutations() {
        val root = File("..")
        val audit = File("src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintAudit.kt").readText()
        val store = File("src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintOperations.kt").readText()
        val activity = File("src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        val docs = File(root, "docs/V0.71_REPRINT_LEDGER_PERIOD_INDEX.md")
        val notes = File(root, "docs/V0.71_RELEASE_NOTES.md")

        assertTrue(audit.contains("idx_sale_receipt_reprint_requested_time"))
        assertTrue(audit.contains("requested_at DESC, id DESC"))
        assertTrue(store.contains("enum class SaleReceiptReprintLedgerPeriod"))
        assertTrue(store.contains("ALL(\"全期間\")"))
        assertTrue(store.contains("r.requested_at >= ?"))
        assertTrue(store.contains("earliestRequestedAt"))
        assertTrue(activity.contains("SaleReceiptReprintLedgerPeriod.entries"))
        assertTrue(activity.contains("期間DB絞込"))
        assertTrue(activity.contains("period = item"))
        assertFalse(audit.contains("DELETE FROM sale_receipt_reprint_requests"))
        assertFalse(store.contains("UPDATE sale_receipt_reprint_requests"))

        assertTrue(build.contains("versionCode = 101"))
        assertTrue(build.contains("versionName = \"0.71.0-dev.1\""))
        assertTrue(workflow.contains("V071SaleReceiptReprintPeriodIndexTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_v0.71.0_dev1_sale_receipt_reprint_period_index_debug.apk"))
        assertTrue(docs.isFile)
        assertTrue(notes.isFile)
    }

    private fun entry(id: Long, requestedAt: Long) = SaleReceiptReprintLedgerEntry(
        auditId = id,
        requestId = "request-$id",
        saleId = id,
        saleAmount = 1_000L,
        saleCreatedAt = requestedAt,
        printJobId = id,
        operatorName = "担当",
        paperWidthMm = 80,
        requestedAt = requestedAt,
        status = PrintJobStatus.COMPLETED,
        attemptCount = 1,
        lastError = null,
    )
}
