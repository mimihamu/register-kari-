package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.91: damaged saved allocation sequence must remain evidence, not be rewritten. */
class V191PaymentDraftSequenceIntegrityTest {
    private fun code(name: String): String =
        File("src/main/java/jp/co/tenposinfo/register/$name").readText()

    @Test fun validEmptyAndContiguousSequencesWork() {
        assertTrue(PaymentDraftSequenceV191.contiguous(emptyList()))
        assertTrue(PaymentDraftSequenceV191.contiguous(listOf(1L)))
        assertTrue(PaymentDraftSequenceV191.contiguous(listOf(1L, 2L, 3L)))
    }

    @Test fun holesWrongStartDuplicatesAndHugeSQLiteNumbersAreRejected() {
        for (bad in listOf(
            listOf(0L), listOf(-1L), listOf(2L), listOf(1L, 3L),
            listOf(1L, 1L), listOf(2L, 1L), listOf(1L, 2L, 4L),
            listOf(4_294_967_297L), listOf(Long.MAX_VALUE),
        )) {
            assertFalse(PaymentDraftSequenceV191.contiguous(bad))
        }
    }

    @Test fun everyMutationChecksSequenceBeforeRewritingOrAppending() {
        val s = code("PaymentDraftStore.kt")
        val saves = s.substringAfter("    fun save(items: List<CartItem>, state: PaymentState, commitKey: String) {")
            .substringBefore("    fun cancelAllocation(")
        assertTrue(saves.contains("PaymentDraftSequenceV191.requireContiguous(db)"))
        assertTrue(saves.indexOf("PaymentDraftSequenceV191.requireContiguous(db)") <
            saves.indexOf("db.delete(TABLE_ALLOCATIONS, null, null)"))

        val cancel = s.substringAfter("    fun cancelAllocation(")
            .substringBefore("    fun beginExternalTender(")
        assertTrue(cancel.contains("PaymentDraftSequenceV191.requireContiguous(db)"))
        assertTrue(cancel.indexOf("PaymentDraftSequenceV191.requireContiguous(db)") <
            cancel.indexOf("db.delete(TABLE_ALLOCATIONS, null, null)"))

        val begin = s.substringAfter("    fun beginExternalTender(")
            .substringBefore("    fun resolveExternalTender(")
        assertTrue(begin.contains("PaymentDraftSequenceV191.requireContiguous(db)"))
        assertTrue(begin.indexOf("PaymentDraftSequenceV191.requireContiguous(db)") <
            begin.indexOf("ExternalTenderLedgerV188.insert(db, pending)"))

        val resolve = s.substringAfter("    fun resolveExternalTender(")
            .substringBefore("    fun loadOrCreate(")
        assertTrue(resolve.contains("PaymentDraftSequenceV191.requireContiguous(db)"))
        assertTrue(resolve.indexOf("PaymentDraftSequenceV191.requireContiguous(db)") <
            resolve.indexOf("ExternalTenderLedgerV188.remove(db, pending)"))
    }

    @Test fun restoreAndCleanupRejectDamagedSequencesBeforeAnyDeletion() {
        val s = code("PaymentDraftStore.kt")
        val load = s.substringAfter("    fun load(items: List<CartItem>): PaymentDraftLoadResult {")
            .substringBefore("    fun clear()")
        assertTrue(load.contains("PaymentDraftSequenceV191.requireContiguous(db)"))
        assertTrue(load.indexOf("PaymentDraftSequenceV191.requireContiguous(db)") <
            load.indexOf("val confirmedSale = metadata.third"))
        assertTrue(load.indexOf("PaymentDraftSequenceV191.requireContiguous(db)") <
            load.indexOf("            clear()\n            return PaymentDraftLoadResult(\n                PaymentState(), false, null, null,\n                recoveredConfirmedSaleId"))
        assertTrue(load.contains("PaymentDraftIntegrityV181.REVIEW_MESSAGE"))

        val clear = s.substringAfter("    fun clear() {")
            .substringBefore("    private fun ensureSchema()")
        assertTrue(clear.contains("PaymentDraftSequenceV191.requireContiguous(db)"))
        assertTrue(clear.indexOf("PaymentDraftSequenceV191.requireContiguous(db)") <
            clear.indexOf("db.delete(TABLE_ALLOCATIONS, null, null)"))
    }

    @Test fun checkerReadsOriginalSqliteSequenceNotListPosition() {
        val source = code("PaymentDraftSequenceV191.kt")
        assertTrue(source.contains("cursor.getLong(0)"))
        assertTrue(source.contains("\"sequence_no ASC\""))
        assertFalse(source.contains("db.update("))
        assertFalse(source.contains("db.delete("))
    }
}
