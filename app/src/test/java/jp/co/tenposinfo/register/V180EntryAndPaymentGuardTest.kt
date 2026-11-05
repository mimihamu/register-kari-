package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V180EntryAndPaymentGuardTest {
    private val folder = File("src/main/java/jp/co/tenposinfo/register")
    private fun source(file: String) = File(folder, file).readText()

    @Test fun tenderMayBeAddedOnlyWhilePositiveBalanceIsOpen() {
        assertTrue(RegisterPaymentInputSafetyV180.canEditTender(1, false))
        assertTrue(RegisterPaymentInputSafetyV180.canEditTender(Long.MAX_VALUE, false))
        assertFalse(RegisterPaymentInputSafetyV180.canEditTender(0, false))
        assertFalse(RegisterPaymentInputSafetyV180.canEditTender(-1, false))
        assertFalse(RegisterPaymentInputSafetyV180.canEditTender(100, true))
        assertFalse(RegisterPaymentInputSafetyV180.canClear(true))
        assertTrue(RegisterPaymentInputSafetyV180.canClear(false))
    }

    @Test fun expandedDialogAdjustsForSmallAndLargeTablets() {
        assertEquals(0.92f, RegisterExpandedKeypadLayoutV180.widthFraction(800, 1f))
        assertEquals(0.84f, RegisterExpandedKeypadLayoutV180.widthFraction(1024, 1f))
        assertEquals(0.76f, RegisterExpandedKeypadLayoutV180.widthFraction(1280, 1f))
        assertEquals(0.68f, RegisterExpandedKeypadLayoutV180.widthFraction(1920, 1f))
        assertEquals(0.92f, RegisterExpandedKeypadLayoutV180.widthFraction(1920, 1.5f))
        assertTrue(source("RegisterExpandedKeypadV179.kt").contains("Modifier.fillMaxWidth(widthFraction)"))
    }

    @Test fun changingSaleLineClearsEntryAndClosesExpandedPad() {
        val main = source("MainActivity.kt")
        val sale = main.substring(main.indexOf("internal fun SalesScreen("), main.indexOf("private fun LineEditScreen("))
        assertTrue(sale.contains("val selectedLineIdV180 = selectedIndex?.let { cart.getOrNull(it)?.lineId }"))
        assertTrue(sale.contains("LaunchedEffect(selectedLineIdV180)"))
        assertTrue(sale.contains("numericInput = \"\""))
        assertTrue(sale.contains("salePadOpenV179 = false"))
        assertTrue(sale.contains("quantityMode.requireAllowed(parsed)"))
    }

    @Test fun busyPaymentDisablesEveryKeyAndTheCashEntryAction() {
        val main = source("MainActivity.kt")
        val payment = source("PaymentScreenV135.kt")
        val keypad = main.substring(main.indexOf("private fun NumberPad("), main.indexOf("private fun ValueBox("))
        assertTrue(keypad.contains("enabled: Boolean = true"))
        assertTrue(keypad.contains("enabled = enabled"))
        assertTrue(keypad.contains("enabled = enabled && decimalEnabled"))
        assertTrue(keypad.contains("enabled = enabled)"))
        assertTrue(main.contains("enabled = RegisterPaymentInputSafetyV180.canEditTender(remaining, completing)"))
        assertTrue(payment.contains("clearEnabled = RegisterPaymentInputSafetyV180.canClear(completing)"))
        assertTrue(payment.contains("enabled = clearEnabled"))
        assertFalse(payment.contains("!enabled || enabled"))
        assertTrue(payment.contains("LaunchedEffect(completing, remaining)"))
        assertTrue(payment.contains("if (remaining <= 0L) return"))
    }

    @Test fun existingPaymentCommitGuardAndDraftRecoveryStayInPlace() {
        val main = source("MainActivity.kt")
        val payment = source("PaymentScreenV135.kt")
        assertTrue(main.contains("saleCommitGuard.tryBegin()"))
        assertTrue(main.contains("paymentDraftStore.loadOrCreate(cart.toList())"))
        assertTrue(main.contains("paymentDraftStore.save(cart.toList(), it, commitKey)"))
        assertTrue(main.contains("onStateChange(PaymentEngine.removeAt(state, index))"))
        assertTrue(payment.contains("onStateChange(PaymentEngine.removeAt(state, index))"))
        assertTrue(payment.contains("MixedTaxPaymentAcknowledgementV135.record"))
    }
}
