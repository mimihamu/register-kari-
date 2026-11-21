package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.82 - never discard evidence of a tender when the in-memory cart changed. */
class V182DraftRecoveryFailClosedTest {
    private val root = File("src/main/java/jp/co/tenposinfo/register")
    private fun source(name: String) = File(root, name).readText()

    @Test fun emptyDraftCanBeReplacedButNotAnyPersistedPayment() {
        assertFalse(PaymentDraftRecoveryPolicyV182.mustPreserveDifferentCart(0))
        assertTrue(PaymentDraftRecoveryPolicyV182.mustPreserveDifferentCart(1))
        assertTrue(PaymentDraftRecoveryPolicyV182.mustPreserveDifferentCart(20))
        assertThrows(IllegalArgumentException::class.java) {
            PaymentDraftRecoveryPolicyV182.mustPreserveDifferentCart(-1)
        }
    }

    @Test fun newAndOldFingerprintMismatchesPreservePaidAllocations() {
        val store = source("PaymentDraftStore.kt")
        val mismatch = store.substringAfter("if (metadata.first != PaymentDraftFingerprint.of(items)) {")
            .substringBefore("if (!PaymentDraftIntegrityV181.validAllocations(")
        assertTrue(mismatch.contains("PaymentDraftFingerprint.legacyV180Of(items)"))
        assertTrue(mismatch.contains("PaymentDraftRecoveryPolicyV182.mustPreserveDifferentCart(allocations.size)"))
        assertTrue(mismatch.contains("PaymentDraftRecoveryPolicyV182.OTHER_CART_REVIEW_MESSAGE"))
        assertTrue(mismatch.contains("PaymentDraftIntegrityV181.REVIEW_MESSAGE"))
        assertTrue(mismatch.contains("clear()"))
        assertTrue(mismatch.indexOf("return PaymentDraftLoadResult(") < mismatch.indexOf("clear()"))
        assertTrue(mismatch.contains("if (matchingOldFormat)"))
    }

    @Test fun missingMetadataWithOrphanedAllocationsStopsNewPayment() {
        val store = source("PaymentDraftStore.kt")
        val orphan = store.substringAfter("val metadata = db.query(")
            .substringBefore("val allocations = runCatching {")
        assertTrue(orphan.contains("orphanedAllocations"))
        assertTrue(orphan.contains("PaymentDraftRecoveryPolicyV182.ORPHAN_REVIEW_MESSAGE"))
        assertTrue(orphan.contains("db.query("))
        assertFalse(orphan.contains("clear()"))
    }

    @Test fun storageFailureShowsVisibleWarningNotAnAppCrash() {
        val main = source("MainActivity.kt")
        val section = main.substringAfter("onPayment = {")
            .substringBefore("onSalesHistory = {")
        assertTrue(section.contains("runCatching {"))
        assertTrue(section.contains("paymentDraftStore.loadOrCreate(cart.toList())"))
        assertTrue(section.contains("PaymentDraftRecoveryPolicyV182.STORAGE_REVIEW_MESSAGE"))
        assertTrue(section.contains("accessMessage = \"支払データ要確認\""))
        assertTrue(section.contains("return@SalesScreen"))
        assertTrue(main.contains("Text(paymentIntegrityWarningV181.orEmpty())"))
        assertTrue(main.contains("saleCommitGuard.tryBegin()"))
    }

    @Test fun threeReasonsOfferExplicitSafeNextSteps() {
        for (reason in listOf(
            PaymentDraftRecoveryPolicyV182.OTHER_CART_REVIEW_MESSAGE,
            PaymentDraftRecoveryPolicyV182.ORPHAN_REVIEW_MESSAGE,
            PaymentDraftRecoveryPolicyV182.STORAGE_REVIEW_MESSAGE,
        )) {
            assertTrue(reason.contains("再決済") || reason.contains("支払記録"))
            assertTrue(reason.contains("売上履歴"))
        }
    }
}
