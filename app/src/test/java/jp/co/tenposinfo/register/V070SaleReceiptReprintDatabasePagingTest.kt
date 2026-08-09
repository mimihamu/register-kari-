package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V070SaleReceiptReprintDatabasePagingTest {
    @Test
    fun databaseQueryBindsStatusAndEscapesLikeWildcards() {
        val spec = SaleReceiptReprintLedgerPolicy.buildDatabaseQuery(
            SaleReceiptReprintLedgerCriteria(
                filter = SaleReceiptReprintLedgerFilter.ACTION_REQUIRED,
                query = "山田%_\\",
            ),
        )

        assertTrue(spec.whereSql.contains("j.status IN (?, ?)"))
        assertTrue(spec.whereSql.contains("CAST(r.sale_id AS TEXT) LIKE ? ESCAPE"))
        assertTrue(spec.whereSql.contains("LOWER(r.operator_name) LIKE ? ESCAPE"))
        assertTrue(spec.whereSql.contains("LOWER(COALESCE(j.last_error, '')) LIKE ? ESCAPE"))
        assertFalse(spec.whereSql.contains("山田"))
        assertEquals(PrintJobStatus.RETRY.name, spec.args[0])
        assertEquals(PrintJobStatus.FAILED.name, spec.args[1])
        assertEquals("%山田\\%\\_\\\\%", spec.args[2])
        assertEquals(11, spec.args.size)
    }

    @Test
    fun statusFiltersAreTranslatedToSql() {
        val active = SaleReceiptReprintLedgerPolicy.buildDatabaseQuery(
            SaleReceiptReprintLedgerCriteria(filter = SaleReceiptReprintLedgerFilter.ACTIVE),
        )
        assertTrue(active.whereSql.contains("j.status IN (?, ?, ?, ?)"))
        assertEquals(
            listOf("PENDING", "RETRY", "FAILED", "PRINTING"),
            active.args,
        )

        val completed = SaleReceiptReprintLedgerPolicy.buildDatabaseQuery(
            SaleReceiptReprintLedgerCriteria(filter = SaleReceiptReprintLedgerFilter.COMPLETED),
        )
        assertEquals("WHERE j.status = ?", completed.whereSql)
        assertEquals(listOf("COMPLETED"), completed.args)
    }

    @Test
    fun pagingBoundaryIsStable() {
        assertEquals(200, SaleReceiptReprintLedgerPolicy.DATABASE_PAGE_SIZE)
        val legacyPage = SaleReceiptReprintLedgerPage(
            entries = emptyList(),
            offset = 400,
            pageSize = 200,
            totalMatches = 999,
            hasNext = true,
        )
        assertEquals(400, legacyPage.offset)
        assertEquals(200, legacyPage.pageSize)
        assertEquals(999, legacyPage.totalMatches)
        assertTrue(legacyPage.hasNext)
    }

    @Test
    fun sourceKeepsDirectBoundDatabaseSearchWithoutOneThousandCap() {
        val root = File("..")
        val store = File("src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintOperations.kt").readText()
        val stable = File("src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintStablePaging.kt").readText()
        val activity = File("src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        val docs = File(root, "docs/V0.70_DATABASE_PAGING_FOUNDATION.md")
        val notes = File(root, "docs/V0.70_RELEASE_NOTES.md")

        assertTrue(store.contains("DATABASE_PAGE_SIZE = 200"))
        assertTrue(store.contains("LIMIT ? OFFSET ?")) // legacy compatibility API remains available
        assertTrue(store.contains("safePageSize + 1"))
        assertTrue(store.contains("SELECT COUNT(*)"))
        assertTrue(store.contains("escapeLike"))
        assertTrue(store.contains("ESCAPE"))
        assertFalse(store.contains("LOAD_LIMIT"))
        assertFalse(store.contains("LIMIT 1000"))
        assertFalse(store.contains("UPDATE sale_receipt_reprint_requests"))
        assertFalse(store.contains("DELETE FROM sale_receipt_reprint_requests"))

        // Current SCR-648 still satisfies v0.70's DB-direct/200-row requirement, now via keyset paging.
        assertTrue(stable.contains("DATABASE_PAGE_SIZE"))
        assertTrue(stable.contains("LIMIT ?"))
        assertTrue(stable.contains("SELECT COUNT(*)"))
        assertTrue(activity.contains("SaleReceiptReprintStablePagingStore"))
        assertTrue(activity.contains("前へ"))
        assertTrue(activity.contains("次へ"))
        assertTrue(activity.contains("SQLite直接検索"))
        assertFalse(activity.contains("store.list()"))

        assertTrue(build.contains("applicationId = \"jp.co.tenposinfo.register\""))
        assertTrue(build.contains("compileSdk = 36"))
        assertTrue(workflow.contains("V070SaleReceiptReprintDatabasePagingTest.kt"))
        assertTrue(workflow.contains(":app:assembleDebug"))
        assertTrue(docs.isFile)
        assertTrue(notes.isFile)
    }
}
