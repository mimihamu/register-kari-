package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Do not create a second approval ID when the operator reopens the same negative checkout.
 * If a previous DB commit was completed but the UI lost the result, a new ID would duplicate it.
 */
class V161RegisterMinusReopenSafetyTest {
    private val ui = File("src/main/java/jp/co/tenposinfo/register/ManualReturnActivityV135.kt").readText()
    private val ledger = File("src/main/java/jp/co/tenposinfo/register/ManualReturnV135.kt").readText()

    @Test fun reopenReusesOriginalApprovalId() {
        val payment = ui.substringAfter("        onPayment = {").substringBefore("        onSalesHistory =")
        assertTrue(payment.contains("showCheckout = true"))
        assertFalse(payment.contains("checkoutRequestId ="))
        assertTrue(ui.contains("coordinator.create(request, managerPin, checkoutRequestId)"))
        assertTrue(ui.contains("var checkoutRequestId by rememberSaveable"))
    }

    @Test fun explicitlyCancellingTransactionRotatesApprovalId() {
        val cancellation = ui.substringAfter("        onCancelTransaction = {").substringBefore("        onDiscount =")
        assertTrue(cancellation.contains("cart.clear()"))
        assertTrue(cancellation.contains("checkoutRequestId = UUID.randomUUID().toString()"))
    }

    @Test fun storeDoesNotInsertSecondLedgerForSameApprovalId() {
        assertTrue(ledger.contains("approval_request_id TEXT NOT NULL UNIQUE"))
        assertTrue(ledger.contains("recoverCommittedReturn(requestId, signedGross, request, items, operatorName, managerName)"))
        assertTrue(ledger.contains("ManualReturnReplaySafetyV160.isSameCommittedOperation(original, requested)"))
        assertTrue(ledger.contains("return ManualReturnResultV135(saved.id, saved.amount, job.first, job.second)"))
    }
}
