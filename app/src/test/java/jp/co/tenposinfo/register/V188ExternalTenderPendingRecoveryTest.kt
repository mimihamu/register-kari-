package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V188ExternalTenderPendingRecoveryTest {
    private fun source(name: String): String =
        File("src/main/java/jp/co/tenposinfo/register/$name").readText()
    private val fingerprint = "cart-verified"
    private val key = "11111111-1111-4111-8111-111111111111"
    private val pending = ExternalTenderPendingV188(
        fingerprint, key, PaymentAllocation(PaymentMethod.CARD, 700L, 700L), 100L,
    )

    @Test fun onlyExternalTerminalMethodsRequireDurableHandoff() {
        assertTrue(ExternalTenderLedgerV188.requiresHandoff(PaymentMethod.CARD))
        assertTrue(ExternalTenderLedgerV188.requiresHandoff(PaymentMethod.ELECTRONIC_MONEY))
        assertTrue(ExternalTenderLedgerV188.requiresHandoff(PaymentMethod.QR))
        for (method in PaymentMethod.entries.filterNot(ExternalTenderLedgerV188::requiresHandoff)) {
            assertFalse(ExternalTenderLedgerV188.requiresHandoff(method))
        }
    }

    @Test fun unknownAndWrongTransactionCannotBeRestoredAsSafe() {
        assertTrue(ExternalTenderLedgerV188.matchesDraft(pending, fingerprint, key, emptyList(), 700L))
        assertFalse(ExternalTenderLedgerV188.matchesDraft(pending, "other-cart", key, emptyList(), 700L))
        assertFalse(ExternalTenderLedgerV188.matchesDraft(pending, fingerprint, "bad", emptyList(), 700L))
        assertFalse(ExternalTenderLedgerV188.matchesDraft(pending, fingerprint, key, emptyList(), 600L))
        assertFalse(ExternalTenderLedgerV188.matchesDraft(
            pending.copy(createdAt = 0L), fingerprint, key, emptyList(), 700L,
        ))
    }

    @Test fun beginMustPersistHandoffAndAuditBeforeTerminalOperation() {
        val store = source("PaymentDraftStore.kt")
        val begin = store.substringAfter("    fun beginExternalTender(")
            .substringBefore("    fun resolveExternalTender(")
        assertTrue(begin.contains("ExternalTenderLedgerV188.requireAbsent(db)"))
        assertTrue(begin.contains("persisted == oldAllocations"))
        assertTrue(begin.contains("ExternalTenderLedgerV188.insert(db, pending)"))
        assertTrue(begin.contains("PAYMENT_EXTERNAL_HANDOFF_STARTED"))
        assertTrue(begin.contains("db.setTransactionSuccessful()"))
    }

    @Test fun resolveMustAuditAndModifyPaymentAtomicallyOrNotAtAll() {
        val store = source("PaymentDraftStore.kt")
        val resolve = store.substringAfter("    fun resolveExternalTender(")
            .substringBefore("    fun loadOrCreate(")
        assertTrue(resolve.contains("pending == expectedPending"))
        assertTrue(resolve.contains("persisted == expectedState.allocations"))
        assertTrue(resolve.contains("PAYMENT_EXTERNAL_APPROVED_BY_OPERATOR"))
        assertTrue(resolve.contains("PAYMENT_EXTERNAL_NOT_EXECUTED_OR_REVERSED_BY_OPERATOR"))
        assertTrue(resolve.contains("ExternalTenderLedgerV188.audit("))
        assertTrue(resolve.contains("ExternalTenderLedgerV188.remove(db, pending)"))
        assertTrue(resolve.contains("db.setTransactionSuccessful()"))
        assertTrue(resolve.indexOf("ExternalTenderLedgerV188.audit(") <
            resolve.indexOf("ExternalTenderLedgerV188.remove(db, pending)"))
    }

    @Test fun restartAndAllDestructivePathsPreserveUnknownResult() {
        val store = source("PaymentDraftStore.kt")
        val load = store.substringAfter("    fun load(items: List<CartItem>): PaymentDraftLoadResult {")
            .substringBefore("    fun clear()")
        assertTrue(load.contains("pendingExternalV188 = pendingExternalV188"))
        assertTrue(load.contains("ExternalTenderLedgerV188.REVIEW_MESSAGE"))
        assertTrue(store.contains("ExternalTenderLedgerV188.requireAbsent(db)"))
        val clear = store.substringAfter("    fun clear() {")
            .substringBefore("    private fun ensureSchema()")
        assertTrue(clear.contains("ExternalTenderLedgerV188.requireAbsent(db)"))
        val saveSale = source("RegisterDatabase.kt")
        assertTrue(saveSale.contains("ExternalTenderLedgerV188.requireAbsent(this)"))
        val main = source("MainActivity.kt")
        assertTrue(main.contains("paymentDraftStore.beginExternalTender("))
        assertTrue(main.contains("paymentDraftStore.resolveExternalTender("))
        assertTrue(main.contains("pendingExternalV188 = draft.pendingExternalV188"))
        val ui = source("PaymentScreenV135.kt")
        assertTrue(ui.contains("onStartExternalV188(it)"))
        assertTrue(ui.contains("onResolveExternalV188(ExternalTenderResolutionV188.APPROVED_BY_OPERATOR)"))
        assertTrue(ui.contains("pendingExternalV188 == null"))
    }
}
