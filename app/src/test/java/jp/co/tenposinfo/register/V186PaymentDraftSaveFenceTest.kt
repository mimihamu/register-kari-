package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.86: a write may update its own tender, never overwrite another sale's. */
class V186PaymentDraftSaveFenceTest {
    private val first = "11111111-1111-4111-8111-111111111111"
    private val second = "22222222-2222-4222-8222-222222222222"

    private fun check(
        savedFingerprint: String? = "cart-A",
        savedKey: String? = first,
        nextFingerprint: String = "cart-A",
        nextKey: String = first,
        hasTender: Boolean = true,
    ) = PaymentDraftSaveFenceV186.requireSameDraft(
        hasTender, savedFingerprint, savedKey, nextFingerprint, nextKey,
    )

    @Test fun existingSessionCanUpdateItsOwnPartialPayments() {
        check()
        check(hasTender = false, savedFingerprint = null, savedKey = null, nextKey = second)
    }

    @Test fun anotherCartOrAnotherKeyMustNeverReplaceSavedTender() {
        assertThrows(IllegalArgumentException::class.java) { check(nextFingerprint = "cart-B") }
        assertThrows(IllegalArgumentException::class.java) { check(nextKey = second) }
    }

    @Test fun corruptedOrMissingMetadataMustNeverReplaceSavedTender() {
        assertThrows(IllegalArgumentException::class.java) { check(savedFingerprint = null) }
        assertThrows(IllegalArgumentException::class.java) { check(savedKey = null) }
        assertThrows(IllegalArgumentException::class.java) { check(savedKey = "not-a-uuid", nextKey = "not-a-uuid") }
    }

    @Test fun sqliteChecksRunBeforeTheDestructiveWriteWithinOneTransaction() {
        val file = File("src/main/java/jp/co/tenposinfo/register/PaymentDraftStore.kt").readText()
        val save = file.substringAfter("    fun save(items: List<CartItem>, state: PaymentState, commitKey: String) {")
            .substringBefore("    fun loadOrCreate(items: List<CartItem>)")
        val transaction = save.substringAfter("db.beginTransaction()").substringBefore("db.setTransactionSuccessful()")
        assertTrue(transaction.contains("PaymentDraftSaveFenceV186.requireSameDraft("))
        assertTrue(transaction.contains("previousAllocations"))
        assertTrue(transaction.contains("PaymentDraftIntegrityV181.validAllocations("))
        assertTrue(transaction.contains("SaleCommitIdempotencySchema.find(db, commitKey) == null"))
        assertTrue(transaction.indexOf("PaymentDraftSaveFenceV186.requireSameDraft(") <
            transaction.indexOf("db.delete(TABLE_ALLOCATIONS, null, null)"))
        assertTrue(transaction.indexOf("SaleCommitIdempotencySchema.find(db, commitKey) == null") <
            transaction.indexOf("db.delete(TABLE_META, null, null)"))
    }
}
