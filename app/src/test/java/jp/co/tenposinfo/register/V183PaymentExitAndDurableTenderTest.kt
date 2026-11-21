package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.83: never silently delete tender evidence or accept an unsaved payment. */
class V183PaymentExitAndDurableTenderTest {
    private fun source(file: String) =
        File("src/main/java/jp/co/tenposinfo/register/$file").readText()

    @Test fun paymentExitAllowedOnlyWithNoAllocations() {
        assertFalse(PaymentExitPolicyV183.mustKeepPaymentOpen(0))
        assertTrue(PaymentExitPolicyV183.mustKeepPaymentOpen(1))
        assertTrue(PaymentExitPolicyV183.mustKeepPaymentOpen(10))
        assertThrows(IllegalArgumentException::class.java) {
            PaymentExitPolicyV183.mustKeepPaymentOpen(-1)
        }
        assertTrue(PaymentExitPolicyV183.BLOCKED_EXIT_MESSAGE.contains("決済端末側"))
    }

    @Test fun bothPhysicalAndScreenBackUseTheSameExitProtection() {
        val main = source("MainActivity.kt")
        val exit = main.substringAfter("fun returnFromPayment() {")
            .substringBefore("// Android system Back and on-screen Back")
        assertTrue(exit.contains("PaymentExitPolicyV183.mustKeepPaymentOpen(paymentState.allocations.size)"))
        assertTrue(exit.contains("paymentExitWarningV183 = true"))
        assertTrue(exit.indexOf("paymentExitWarningV183 = true") < exit.indexOf("paymentDraftStore.clear()"))
        assertTrue(exit.contains("runCatching { paymentDraftStore.clear() }"))
        assertTrue(exit.contains("PaymentDraftRecoveryPolicyV182.STORAGE_REVIEW_MESSAGE"))
        assertTrue(main.contains("AppScreen.PAYMENT -> returnFromPayment()"))
        assertTrue(main.contains("onBack = ::returnFromPayment"))
        assertTrue(main.contains("Text(PaymentExitPolicyV183.BLOCKED_EXIT_MESSAGE)"))
    }

    @Test fun durableTenderIsStoredBeforeUpdatingTheUi() {
        val main = source("MainActivity.kt")
        val stateCallback = main.substringAfter("onStateChange = tenderUpdate@{")
            .substringBefore("onBack = ::returnFromPayment")
        assertTrue(stateCallback.contains("runCatching {"))
        assertTrue(stateCallback.contains("paymentDraftStore.save(cart.toList(), it, commitKey)"))
        assertTrue(stateCallback.contains("return@tenderUpdate"))
        assertTrue(stateCallback.contains("paymentCommitKey = commitKey"))
        assertTrue(stateCallback.contains("paymentState = it"))
        assertTrue(stateCallback.indexOf("paymentDraftStore.save(cart.toList(), it, commitKey)") <
            stateCallback.indexOf("paymentState = it"))
        assertTrue(stateCallback.indexOf("return@tenderUpdate") <
            stateCallback.indexOf("paymentState = it"))
        assertTrue(main.contains("paymentMessage = \"支払情報の保存に失敗しました。会計を続けないでください\""))
    }

    @Test fun commitAndPersistedDraftRecoveryRemainIndependent() {
        val main = source("MainActivity.kt")
        val store = source("PaymentDraftStore.kt")
        assertTrue(main.contains("saleCommitGuard.tryBegin()"))
        assertTrue(main.contains("paymentDraftStore.loadOrCreate(cart.toList())"))
        assertTrue(main.contains("database.saveSale("))
        assertTrue(store.contains("PaymentDraftFingerprint.of(items)"))
        assertTrue(store.contains("PaymentDraftRecoveryPolicyV182.mustPreserveDifferentCart(allocations.size)"))
    }
}
