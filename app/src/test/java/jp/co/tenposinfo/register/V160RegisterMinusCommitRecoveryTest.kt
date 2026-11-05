package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same checkout ID must never silently become a second negative ledger posting. */
class V160RegisterMinusCommitRecoveryTest {
    private val src = File("src/main/java/jp/co/tenposinfo/register")
    private fun source(name: String): String = File(src, name).readText()

    private val original = ManualReturnReplayFingerprintV160(
        signedAmount = -2_200L,
        refundMethod = ManualRefundMethodV135.CASH.name,
        reason = "",
        operator = "担当A",
        manager = "責任者",
        lines = listOf(listOf("P1", "商品1", "1100", "-200")),
    )

    @Test fun retryWithIdenticalCommittedOperationIsRecoverable() {
        assertTrue(ManualReturnReplaySafetyV160.isSameCommittedOperation(original, original.copy()))
    }

    @Test fun changingAnyFinancialFieldOrIdentityRejectsReplay() {
        for (changed in listOf(
            original.copy(signedAmount = -2_300L),
            original.copy(refundMethod = ManualRefundMethodV135.CARD.name),
            original.copy(reason = "異なる理由"),
            original.copy(operator = "担当B"),
            original.copy(manager = "責任者B"),
            original.copy(lines = listOf(listOf("P2", "商品1", "1100", "-200"))),
            original.copy(lines = listOf(listOf("P1", "商品2", "1100", "-200"))),
            original.copy(lines = listOf(listOf("P1", "商品1", "1200", "-200"))),
            original.copy(lines = listOf(listOf("P1", "商品1", "1100", "-100"))),
            original.copy(lines = emptyList()),
        )) {
            assertFalse(ManualReturnReplaySafetyV160.isSameCommittedOperation(original, changed))
        }
    }

    @Test fun coordinatorAndScreenKeepOneRequestIdAcrossRetryAndDismissal() {
        val store = source("ManualReturnV135.kt")
        val ui = source("ManualReturnActivityV135.kt")
        assertTrue(store.contains("approval_request_id TEXT NOT NULL UNIQUE"))
        assertTrue(store.contains("recoverCommittedReturn(requestId, signedGross, request, items, operatorName, managerName)"))
        assertTrue(store.contains("return ManualReturnResultV135(saved.id, saved.amount, job.first, job.second)"))
        assertTrue(store.contains("ManualReturnReplaySafetyV160.isSameCommittedOperation(original, requested)"))
        assertTrue(store.contains("requestId: String = UUID.randomUUID().toString()"))
        assertTrue(store.contains("requestId = requestId,"))
        assertTrue(ui.contains("var checkoutRequestId by rememberSaveable"))
        assertTrue(ui.contains("coordinator.create(request, managerPin, checkoutRequestId)"))
        assertTrue(ui.contains("if (submissionGate.tryStart())"))
        assertTrue(ui.contains("submissionGate.release()"))
    }

    @Test fun postCommitPeripheralErrorsNeverRaiseCheckoutFailure() {
        val store = source("ManualReturnV135.kt")
        assertTrue(store.contains("runCatching { CashDrawerRuntimeV136.dispatchAsync("))
        assertTrue(store.contains("runCatching { AutomaticPrintScheduler.enqueueNow(appContext) }"))
        assertTrue(store.contains("eventKey = "))
        assertTrue(store.contains("db.setTransactionSuccessful()"))
        assertTrue(store.contains("db.endTransaction()"))
        assertFalse(store.contains("DELETE FROM manual_return_transactions"))
    }
}
