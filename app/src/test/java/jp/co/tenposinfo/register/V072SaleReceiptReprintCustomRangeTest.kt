package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

class V072SaleReceiptReprintCustomRangeTest {
    private val tokyo = ZoneId.of("Asia/Tokyo")

    @Test
    fun customRangeIncludesWholeEndDateWithExclusiveNextMidnight() {
        val range = SaleReceiptReprintLedgerPolicy.parseCustomRange("2026/8/1", "2026-08-03", tokyo)
        assertEquals(epoch(2026, 8, 1), range.startInclusive)
        assertEquals(epoch(2026, 8, 4), range.endExclusive)
    }

    @Test
    fun customRangeAllowsOneSidedConditions() {
        val startOnly = SaleReceiptReprintLedgerPolicy.parseCustomRange("2026/8/1", "", tokyo)
        assertEquals(epoch(2026, 8, 1), startOnly.startInclusive)
        assertNull(startOnly.endExclusive)

        val endOnly = SaleReceiptReprintLedgerPolicy.parseCustomRange("", "2026/8/3", tokyo)
        assertNull(endOnly.startInclusive)
        assertEquals(epoch(2026, 8, 4), endOnly.endExclusive)
    }

    @Test
    fun invalidOrReversedCustomRangeIsRejected() {
        assertTrue(runCatching { SaleReceiptReprintLedgerPolicy.parseCustomRange("", "", tokyo) }.isFailure)
        assertTrue(runCatching { SaleReceiptReprintLedgerPolicy.parseCustomRange("2026/8/4", "2026/8/3", tokyo) }.isFailure)
        assertTrue(runCatching { SaleReceiptReprintLedgerPolicy.parseCustomRange("2026/2/30", "", tokyo) }.isFailure)
    }

    @Test
    fun customRangeIsBoundAsInclusiveStartExclusiveEnd() {
        val range = SaleReceiptReprintLedgerPolicy.parseCustomRange("2026/8/1", "2026/8/3", tokyo)
        val spec = SaleReceiptReprintLedgerPolicy.buildDatabaseQuery(
            SaleReceiptReprintLedgerCriteria(
                filter = SaleReceiptReprintLedgerFilter.COMPLETED,
                period = SaleReceiptReprintLedgerPeriod.CUSTOM,
                customStartInclusive = range.startInclusive,
                customEndExclusive = range.endExclusive,
                query = "job",
            ),
            zoneId = tokyo,
        )
        assertTrue(spec.whereSql.contains("j.status = ?"))
        assertTrue(spec.whereSql.contains("r.requested_at >= ?"))
        assertTrue(spec.whereSql.contains("r.requested_at < ?"))
        assertEquals("COMPLETED", spec.args[0])
        assertEquals(range.startInclusive.toString(), spec.args[1])
        assertEquals(range.endExclusive.toString(), spec.args[2])
        assertEquals("%job%", spec.args[3])
        assertEquals(12, spec.args.size)
    }

    @Test
    fun sourceKeepsCustomRangeValidatedBoundAndReadOnly() {
        val root = File("..")
        val store = File("src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintOperations.kt").readText()
        val activity = File("src/main/java/jp/co/tenposinfo/register/SaleReceiptReprintLedgerActivity.kt").readText()
        val build = File("build.gradle.kts").readText()
        val workflow = File(root, ".github/workflows/build-apk.yml").readText()
        val docs = File(root, "docs/V0.72_REPRINT_LEDGER_CUSTOM_RANGE.md")
        val notes = File(root, "docs/V0.72_RELEASE_NOTES.md")

        assertTrue(store.contains("CUSTOM(\"任意期間\")"))
        assertTrue(store.contains("parseCustomRange"))
        assertTrue(store.contains("r.requested_at >= ?"))
        assertTrue(store.contains("r.requested_at < ?"))
        assertTrue(store.contains("endDate?.plusDays(1)"))
        assertTrue(activity.contains("開始日 yyyy/MM/dd"))
        assertTrue(activity.contains("終了日 yyyy/MM/dd"))
        assertTrue(activity.contains("任意期間を適用"))
        assertTrue(activity.contains("parseCustomRange"))
        assertTrue(activity.contains("dateError"))
        assertFalse(store.contains("UPDATE sale_receipt_reprint_requests"))
        assertFalse(store.contains("DELETE FROM sale_receipt_reprint_requests"))

        assertTrue(build.contains("versionCode = 104"))
        assertTrue(build.contains("versionName = \"0.74.0-dev.1\""))
        assertTrue(workflow.contains("V072SaleReceiptReprintCustomRangeTest.kt"))
        assertTrue(workflow.contains("TSUGUREGI_v0.74.0_dev1_sale_receipt_reprint_matching_new_items_debug.apk"))
        assertTrue(docs.isFile)
        assertTrue(notes.isFile)
    }

    private fun epoch(year: Int, month: Int, day: Int): Long =
        ZonedDateTime.of(year, month, day, 0, 0, 0, 0, tokyo).toInstant().toEpochMilli()
}
