package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V137CustomerDisplaySessionBoundaryTest {
    private fun snapshot(
        mode: CustomerDisplayMode,
        transactionId: String? = null,
        change: Long = 0L,
    ) = CustomerDisplaySnapshot(
        mode = mode,
        storeName = "テスト店舗",
        transactionId = transactionId,
        changeAmount = change,
    )

    @Test
    fun changeToCompleteOfSameSaleDoesNotRotateSession() {
        val change = snapshot(CustomerDisplayMode.COMPLETE, transactionId = "sale-101", change = 570L)
        val thanks = change.copy(changeAmount = 0L, message = "ありがとうございました")
        assertFalse(CustomerDisplaySessionBoundary.startsNewSession(change, thanks))
    }

    @Test
    fun newSaleMustStartNewSessionEvenIfBothSnapshotsAreComplete() {
        val oldSale = snapshot(CustomerDisplayMode.COMPLETE, transactionId = "sale-101")
        val nextSale = snapshot(CustomerDisplayMode.COMPLETE, transactionId = "sale-102")
        assertTrue(CustomerDisplaySessionBoundary.startsNewSession(oldSale, nextSale))
        assertTrue(CustomerDisplaySessionBoundary.startsNewSession(
            oldSale, snapshot(CustomerDisplayMode.SALES),
        ))
    }

    @Test
    fun standbyAndNextCartHaveSeparateSessions() {
        val complete = snapshot(CustomerDisplayMode.COMPLETE, "sale-103")
        val standby = snapshot(CustomerDisplayMode.STANDBY)
        val cart = snapshot(CustomerDisplayMode.SALES)
        assertTrue(CustomerDisplaySessionBoundary.startsNewSession(complete, standby))
        assertFalse(CustomerDisplaySessionBoundary.startsNewSession(standby, standby))
        assertTrue(CustomerDisplaySessionBoundary.startsNewSession(standby, cart))
    }

    @Test
    fun intraTransactionCartSubtotalAndPaymentKeepSession() {
        val cart = snapshot(CustomerDisplayMode.SALES)
        val subtotal = snapshot(CustomerDisplayMode.SUBTOTAL)
        val payment = snapshot(CustomerDisplayMode.ACCOUNTING)
        assertFalse(CustomerDisplaySessionBoundary.startsNewSession(cart, subtotal))
        assertFalse(CustomerDisplaySessionBoundary.startsNewSession(subtotal, payment))
        assertFalse(CustomerDisplaySessionBoundary.startsNewSession(payment, snapshot(CustomerDisplayMode.COMPLETE, "sale-104")))
    }

    @Test
    fun completionTimersUseMonotonicElapsedTimeNotWallClock() {
        val source = File("src/main/java/jp/co/tenposinfo/register/CustomerDisplayRuntime.kt").readText()
        val poll = source.substringAfter("private fun poll()").substringBefore("private fun detectLatestProduct(", "")
            .ifEmpty { source.substringAfter("private fun poll()").substringBefore("companion object") }
        assertTrue(poll.contains("android.os.SystemClock.elapsedRealtime()"))
        assertFalse(poll.contains("val now = System.currentTimeMillis()"))
    }
}
