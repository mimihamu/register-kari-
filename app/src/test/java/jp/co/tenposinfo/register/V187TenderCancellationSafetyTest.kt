package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class V187TenderCancellationSafetyTest {
    private fun src(file: String) =
        File("src/main/java/jp/co/tenposinfo/register/$file").readText()

    private val cash = PaymentAllocation(PaymentMethod.CASH, 500L, 1000L)
    private val card = PaymentAllocation(PaymentMethod.CARD, 500L, 500L)

    @Test fun nonCashTenderRequiresAnExternalReversalAcknowledgement() {
        assertFalse(PaymentTenderCancellationPolicyV187.needsReversalVerification(PaymentMethod.CASH))
        for (method in PaymentMethod.entries.filterNot { it == PaymentMethod.CASH }) {
            assertTrue(PaymentTenderCancellationPolicyV187.needsReversalVerification(method))
            assertThrows(IllegalArgumentException::class.java) {
                PaymentTenderCancellationPolicyV187.requireReversalVerification(method, false)
            }
            PaymentTenderCancellationPolicyV187.requireReversalVerification(method, true)
        }
        PaymentTenderCancellationPolicyV187.requireReversalVerification(PaymentMethod.CASH, false)
    }

    @Test fun ordinaryDraftSaveCanAppendButNeverRemoveOrRewriteExistingAllocations() {
        val policy = PaymentTenderCancellationPolicyV187
        policy.requireAppendOnly(emptyList(), listOf(cash))
        policy.requireAppendOnly(listOf(cash), listOf(cash, card))
        policy.requireAppendOnly(listOf(cash, card), listOf(cash, card))
        assertThrows(IllegalArgumentException::class.java) {
            policy.requireAppendOnly(listOf(cash, card), listOf(cash))
        }
        assertThrows(IllegalArgumentException::class.java) {
            policy.requireAppendOnly(listOf(card), listOf(cash))
        }
        assertThrows(IllegalArgumentException::class.java) {
            policy.requireAppendOnly(listOf(card), listOf(card.copy(appliedAmount = 300L, receivedAmount = 300L)))
        }
    }

    @Test fun cancellationValidatesRecordAndPersistsAuditBeforeMutatingDraft() {
        val store = src("PaymentDraftStore.kt")
        val save = store.substringAfter("    fun save(items: List<CartItem>, state: PaymentState, commitKey: String) {")
            .substringBefore("    fun cancelAllocation(")
        assertTrue(save.contains("PaymentTenderCancellationPolicyV187.requireAppendOnly("))
        assertTrue(save.indexOf("PaymentTenderCancellationPolicyV187.requireAppendOnly(") <
            save.indexOf("db.delete(TABLE_ALLOCATIONS, null, null)"))
        val cancel = store.substringAfter("    fun cancelAllocation(")
            .substringBefore("    fun loadOrCreate(")
        assertTrue(cancel.contains("persisted == expectedState.allocations"))
        assertTrue(cancel.contains("savedIdentity?.first == fingerprint && savedIdentity?.second == commitKey"))
        assertTrue(cancel.contains("SaleCommitIdempotencySchema.find(db, commitKey) == null"))
        assertTrue(cancel.contains("PaymentTenderCancellationPolicyV187.requireReversalVerification("))
        assertTrue(cancel.contains("OperationAuditSchemaV136.ensure(db)"))
        assertTrue(cancel.contains("PAYMENT_TENDER_CANCELLED"))
        assertTrue(cancel.contains("db.beginTransaction()"))
        assertTrue(cancel.contains("db.setTransactionSuccessful()"))
        assertTrue(cancel.indexOf("db.insertOrThrow(\n                \"operation_audit\"") <
            cancel.indexOf("db.delete(TABLE_ALLOCATIONS, null, null)"))
    }

    @Test fun screenDoesNotDeleteNonCashWithoutConfirmationAndMainUsesAuditedPath() {
        val ui = src("PaymentScreenV135.kt")
        val main = src("MainActivity.kt")
        assertTrue(ui.contains("pendingTenderCancelIndexV187"))
        assertTrue(ui.contains("needsReversalVerification(payment.method)"))
        assertTrue(ui.contains("onCancelAllocation(cancelIndex, true)"))
        assertTrue(ui.contains("onCancelAllocation(index, false)"))
        assertFalse(ui.contains("onStateChange(PaymentEngine.removeAt(state, index))"))
        val controller = main.substringAfter("onCancelAllocation = cancelTender@")
            .substringBefore("onBack = ::returnFromPayment")
        assertTrue(controller.contains("paymentDraftStore.cancelAllocation("))
        assertTrue(controller.contains("paymentState = newState"))
        assertTrue(controller.contains("onFailure = { error ->"))
    }
}
