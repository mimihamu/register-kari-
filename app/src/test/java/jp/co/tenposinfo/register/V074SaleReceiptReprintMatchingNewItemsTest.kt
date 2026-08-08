package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V074SaleReceiptReprintMatchingNewItemsTest {
    @Test
    fun newerBoundIsAppendedAfterExistingCriteriaWithoutLosingThem() {
        val criteria = SaleReceiptReprintLedgerCriteria(
            filter = SaleReceiptReprintLedgerFilter.ACTION_REQUIRED,
            period = SaleReceiptReprintLedgerPeriod.CUSTOM,
            customStartInclusive = 1_000L,
            customEndExclusive = 9_000L,
            query = "山田",
        )
        val base = SaleReceiptReprintLedgerPolicy.buildDatabaseQuery(criteria)
        val snapshot = SaleReceiptReprintLedgerSnapshot(
            SaleReceiptReprintLedgerCursor(requestedAt = 5_000L, auditId = 77L),
        )
        val newer = SaleReceiptReprintStablePagingPolicy.appendNewerThanSnapshot(base, snapshot)

        assertTrue(newer.whereSql.contains("j.status IN (?, ?)"))
        assertTrue(newer.whereSql.contains("r.requested_at >= ?"))
        assertTrue(newer.whereSql.contains("r.requested_at < ?"))
        assertTrue(newer.whereSql.contains("LOWER(CAST(r.sale_id AS TEXT)) LIKE ? ESCAPE '\\'"))
        assertTrue(newer.whereSql.contains("r.requested_at > ? OR (r.requested_at = ? AND r.id > ?)"))
        assertEquals(base.args, newer.args.dropLast(3))
        assertEquals(listOf("5000", "5000", "77"), newer.args.takeLast(3))
    }

    @Test
    fun sourceCountsNewerRowsWithCurrentAppliedCriteriaAndRefreshesSameCriteria() {
        val root = File("..")
        val stable = File("src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintStablePaging.kt").readText()
        val activity = File("src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        val docs = File(root, "docs/V0.74_REPRINT_LEDGER_MATCHING_NEW_ITEMS.md")
        val notes = File(root, "docs/V0.74_RELEASE_NOTES.md")

        assertTrue(stable.contains("appendNewerThanSnapshot"))
        assertTrue(stable.contains("countMatchingNewerThan"))
        assertTrue(stable.contains("SaleReceiptReprintLedgerPolicy.buildDatabaseQuery(criteria)"))
        assertTrue(stable.contains("newerAuditCount = countMatchingNewerThan(criteria, snapshot)"))
        assertTrue(activity.contains("条件一致の新着"))
        assertTrue(activity.contains("新着を反映"))
        assertTrue(activity.contains("applyCriteria(appliedCriteria)"))
        assertFalse(activity.contains("検索再実行で反映"))
        assertFalse(stable.contains("UPDATE sale_receipt_reprint_requests"))
        assertFalse(stable.contains("DELETE FROM sale_receipt_reprint_requests"))

        assertTrue(build.contains("versionCode = 104"))
        assertTrue(build.contains("versionName = \"0.74.0-dev.1\""))
        assertTrue(workflow.contains("V074SaleReceiptReprintMatchingNewItemsTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_v0.74.0_dev1_sale_receipt_reprint_matching_new_items_debug.apk"))
        assertTrue(docs.isFile)
        assertTrue(notes.isFile)
    }
}
